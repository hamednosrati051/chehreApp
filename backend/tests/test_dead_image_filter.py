"""فیلتر ردیف‌های مرده (عکسِ بدون فایل روی دیسک) در گالری، «تصاویر من»
و تب پسندیده‌ها.

اجرا (روی سرور):
    /opt/new-app/venv/bin/python tests/test_dead_image_filter.py

فقط GET — خواندنی؛ هیچ فایلی پاک نمی‌شود، cleanup صدا زده نمی‌شود و
هیچ importای از test_v18 انجام نمی‌شود. _uploads_dir به یک پوشه‌ی
موقت در /tmp وصله می‌شود تا تست هرگز به پوشه‌ی واقعی
static/uploads دست نزند؛ فایل‌های تست فقط همان‌جا ساخته و در آخر
پاک می‌شوند.
"""

import datetime
import os
import shutil
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from werkzeug.security import generate_password_hash  # noqa: E402

import app.routes as routes_mod  # noqa: E402
from app import create_app  # noqa: E402
from app.config import Config  # noqa: E402
from app.extensions import db  # noqa: E402
from app.models import (  # noqa: E402
    AppGallery,
    AppGeneration,
    AppImageLike,
    AppImageReport,
    AppModel,
    AppOtpCode,
    AppUser,
    AppWorld,
)

PASSED = 0
FAILED = 0


def check(name, cond, extra=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  ok: {name}")
    else:
        FAILED += 1
        print(f"  FAIL: {name} {extra}")


_NEXT_ID = [0]


def next_id():
    _NEXT_ID[0] += 1
    return _NEXT_ID[0]


class TestConfig(Config):
    SQLALCHEMY_DATABASE_URI = "sqlite://"
    TESTING = True
    ADMIN_TOKEN = "test-admin-token"


def _otp_login(client, mobile, code="123456"):
    db.session.add(
        AppOtpCode(
            id=next_id(),
            mobile=mobile,
            code_hash=generate_password_hash(code),
            expires_at=datetime.datetime.utcnow() + datetime.timedelta(minutes=10),
        )
    )
    db.session.commit()
    r = client.post("/api/v1/auth/otp/verify", json={"mobile": mobile, "code": code})
    assert r.status_code == 200, f"otp verify failed: {r.status_code} {r.data!r}"
    return r.get_json()["token"]


def _auth(client, token, path, method="GET", body=None):
    return client.open(
        "/api/v1" + path,
        method=method,
        json=body,
        headers={"Authorization": f"Bearer {token}"},
    )


def main():
    tmpdir = tempfile.mkdtemp(prefix="chehre-dead-filter-")
    try:
        app = create_app(TestConfig)
        with app.app_context():
            from sqlalchemy import BigInteger, Integer

            for _table in db.metadata.tables.values():
                for _col in _table.columns:
                    if _col.primary_key and isinstance(_col.type, BigInteger):
                        _col.type = Integer()
            db.create_all()
            # پوشه‌ی آپلود فقط همین پوشه‌ی موقت است — هرگز پوشه‌ی واقعی.
            routes_mod._uploads_dir = lambda: tmpdir
            client = app.test_client()

            model = AppModel(
                id=next_id(),
                kind="image",
                name="test image model",
                slug="test-image",
                provider="test",
                capability="edit_image",
                is_active=True,
            )
            world = AppWorld(
                id=next_id(),
                name="دنیای تست",
                slug="test-world",
                hidden_prompt="test prompt",
                model_id=model.id,
                coin_price=10,
                is_active=True,
            )
            user = AppUser(
                id=next_id(), mobile="09120000001",
                free_image_credits=0, free_voice_credits=0,
            )
            db.session.add_all([model, world, user])
            db.session.commit()
            token = _otp_login(client, "09120000001")

            # فقط یک فایل واقعاً هست؛ بقیه ارجاع مرده‌اند.
            with open(os.path.join(tmpdir, "exists.jpg"), "wb") as f:
                f.write(b"fake-image-bytes")

            gen_ok = AppGeneration(
                id=next_id(), user_id=user.id, kind="image",
                world_id=world.id, model_id=model.id, status="done",
                output_url="https://chehre.inourai.ir/static/uploads/exists.jpg",
            )
            gen_dead = AppGeneration(
                id=next_id(), user_id=user.id, kind="image",
                world_id=world.id, model_id=model.id, status="done",
                output_url="https://chehre.inourai.ir/static/uploads/missing.jpg",
            )
            gen_queued = AppGeneration(
                id=next_id(), user_id=user.id, kind="image",
                world_id=world.id, model_id=model.id, status="queued",
                output_url=None,
            )
            db.session.add_all([gen_ok, gen_dead, gen_queued])
            db.session.commit()

            # ۵۵ نسل مرده‌ی جدیدتر — برای اینکه سقف ۵۰ (و total صفحه‌بندی)
            # قبل از فیلتر، نسل سالم را بیرون نیندازد.
            extra_dead = [
                AppGeneration(
                    id=next_id(), user_id=user.id, kind="image",
                    world_id=world.id, model_id=model.id, status="done",
                    output_url=f"https://chehre.inourai.ir/static/uploads/missing-{i}.jpg",
                )
                for i in range(55)
            ]
            db.session.add_all(extra_dead)
            db.session.commit()

            r = _auth(client, token, "/generations/mine", method="GET")
            mine = r.get_json() or []
            mine_ids = {it["id"] for it in mine}
            check("mine: done با فایل موجود نشان داده می‌شود",
                  r.status_code == 200 and gen_ok.id in mine_ids,
                  f"got {r.status_code} {mine_ids}")
            check("mine: done با فایل حذف‌شده پنهان می‌شود",
                  gen_dead.id not in mine_ids,
                  f"got {mine_ids}")
            check("mine: queued بدون output_url مثل قبل می‌ماند",
                  gen_queued.id in mine_ids,
                  f"got {mine_ids}")
            check("mine: نسل سالم با وجود ۵۵ نسل مرده‌ی جدیدتر هنوز دیده می‌شود",
                  gen_ok.id in mine_ids,
                  f"got {mine_ids}")

            gal_ok = AppGallery(
                id=next_id(), user_id=user.id, generation_id=gen_ok.id,
                image_url="https://chehre.inourai.ir/static/uploads/exists.jpg",
                status="approved", published_in_app=True,
            )
            gal_dead = AppGallery(
                id=next_id(), user_id=user.id, generation_id=gen_dead.id,
                image_url="https://chehre.inourai.ir/static/uploads/missing.jpg",
                status="approved", published_in_app=True,
            )
            db.session.add_all([gal_ok, gal_dead])
            db.session.commit()

            extra_gal_dead = [
                AppGallery(
                    id=next_id(), user_id=user.id, generation_id=g.id,
                    image_url=g.output_url,
                    status="approved", published_in_app=True,
                )
                for g in extra_dead
            ]
            db.session.add_all(extra_gal_dead)
            db.session.commit()

            r = client.get("/api/v1/gallery/public")
            gal = r.get_json() or []
            gal_ids = {it["id"] for it in gal}
            check("gallery: ردیف با فایل موجود نشان داده می‌شود",
                  r.status_code == 200 and gal_ok.id in gal_ids,
                  f"got {r.status_code} {gal_ids}")
            check("gallery: ردیف با فایل حذف‌شده پنهان می‌شود",
                  gal_dead.id not in gal_ids,
                  f"got {gal_ids}")
            check("gallery: ردیف سالم با وجود ۵۵ ردیف مرده‌ی جدیدتر هنوز دیده می‌شود",
                  gal_ok.id in gal_ids,
                  f"got {gal_ids}")

            # تب پسندیده‌ها: لایک روی نسل سالم نشان داده می‌شود، روی نسل
            # مرده پنهان می‌شود، و لایک روی نسل بدون خروجی مثل قبل نیست.
            db.session.add_all([
                AppImageLike(id=next_id(), user_id=user.id, generation_id=gen_ok.id),
                AppImageLike(id=next_id(), user_id=user.id, generation_id=gen_dead.id),
                AppImageLike(id=next_id(), user_id=user.id, generation_id=gen_queued.id),
            ])
            db.session.commit()

            r = _auth(client, token, "/image-likes", method="GET")
            like_ids = {it["id"] for it in (r.get_json() or [])}
            check("image-likes: لایک روی فایل موجود نشان داده می‌شود",
                  r.status_code == 200 and gen_ok.id in like_ids,
                  f"got {r.status_code} {like_ids}")
            check("image-likes: لایک روی فایل حذف‌شده پنهان می‌شود",
                  gen_dead.id not in like_ids,
                  f"got {like_ids}")
            check("image-likes: لایک روی نسل بدون خروجی مثل قبل نیست",
                  gen_queued.id not in like_ids,
                  f"got {like_ids}")

            # حالت S3: هیچ فیلتری نباید اعمال شود.
            app.config["S3_ENDPOINT"] = "https://s3.example.com"
            r = client.get("/api/v1/gallery/public")
            gal_ids_s3 = {it["id"] for it in (r.get_json() or [])}
            check("gallery S3: ردیف مرده فیلتر نمی‌شود",
                  gal_dead.id in gal_ids_s3,
                  f"got {gal_ids_s3}")
            r = _auth(client, token, "/generations/mine", method="GET")
            mine_ids_s3 = {it["id"] for it in (r.get_json() or [])}
            check("mine S3: نسل مرده فیلتر نمی‌شود",
                  gen_dead.id in mine_ids_s3,
                  f"got {mine_ids_s3}")
            r = _auth(client, token, "/image-likes", method="GET")
            like_ids_s3 = {it["id"] for it in (r.get_json() or [])}
            check("image-likes S3: لایک روی نسل مرده فیلتر نمی‌شود",
                  gen_dead.id in like_ids_s3,
                  f"got {like_ids_s3}")
            del app.config["S3_ENDPOINT"]

            # total صفحه‌بندی فقط ردیف‌های دیده‌شده را می‌شمارد.
            rep_ok = AppImageReport(
                id=next_id(), user_id=user.id, generation_id=gen_ok.id,
                reason="test", status="pending",
            )
            rep_dead = AppImageReport(
                id=next_id(), user_id=user.id, generation_id=gen_dead.id,
                reason="test", status="pending",
            )
            db.session.add_all([rep_ok, rep_dead])
            db.session.commit()

            admin_h = {"X-Admin-Token": app.config.get("ADMIN_TOKEN") or ""}
            r = client.get("/api/v1/admin/generations", headers=admin_h)
            body = r.get_json() or {}
            gen_items = {it["id"] for it in body.get("items", [])}
            check("admin generations: total فقط ردیف‌های دیده‌شده است",
                  r.status_code == 200 and body.get("total") == 2,
                  f"got {r.status_code} total={body.get('total')}")
            check("admin generations: صفحه‌ی اول فقط ردیف‌های دیده‌شده را دارد",
                  gen_items == {gen_ok.id, gen_queued.id},
                  f"got {gen_items}")
            r = client.get("/api/v1/admin/generations?page=2&page_size=1", headers=admin_h)
            body = r.get_json() or {}
            check("admin generations: صفحه‌ی دوم با total درست، خالی/کوتاه نیست",
                  body.get("total") == 2
                  and [it["id"] for it in body.get("items", [])] == [gen_ok.id],
                  f"got total={body.get('total')} items={body.get('items')}")
            r = client.get("/api/v1/admin/gallery", headers=admin_h)
            body = r.get_json() or {}
            check("admin gallery: total فقط ردیف‌های دیده‌شده است",
                  r.status_code == 200 and body.get("total") == 1,
                  f"got {r.status_code} total={body.get('total')}")
            r = client.get("/api/v1/admin/reports", headers=admin_h)
            body = r.get_json() or {}
            rep_items = {it["id"] for it in body.get("items", [])}
            check("admin reports: total فقط ردیف‌های دیده‌شده است",
                  r.status_code == 200 and body.get("total") == 1
                  and rep_items == {rep_ok.id},
                  f"got {r.status_code} total={body.get('total')} items={rep_items}")
            r = client.get(f"/api/v1/admin/users/{user.id}", headers=admin_h)
            body = r.get_json() or {}
            u_gen_ids = {g["id"] for g in body.get("generations", [])}
            u_gal_ids = {g["id"] for g in body.get("gallery", [])}
            check("admin user: لیست نسل‌ها فقط ردیف‌های دیده‌شده است",
                  r.status_code == 200 and u_gen_ids == {gen_ok.id, gen_queued.id},
                  f"got {r.status_code} {u_gen_ids}")
            check("admin user: لیست گالری فقط ردیف‌های دیده‌شده است",
                  u_gal_ids == {gal_ok.id},
                  f"got {u_gal_ids}")

            # حالت S3: total همه‌ی ردیف‌ها را می‌شمارد (بدون فیلتر).
            app.config["S3_ENDPOINT"] = "https://s3.example.com"
            r = client.get("/api/v1/admin/generations", headers=admin_h)
            body = r.get_json() or {}
            check("admin generations S3: total بدون فیلتر همه‌ی ردیف‌هاست",
                  body.get("total") == 58,
                  f"got total={body.get('total')}")
            del app.config["S3_ENDPOINT"]

            print(f"\nPASSED={PASSED} FAILED={FAILED}")
            return 1 if FAILED else 0
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
