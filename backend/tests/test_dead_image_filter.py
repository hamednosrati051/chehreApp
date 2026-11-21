"""فیلتر ردیف‌های مرده (عکسِ بدون فایل روی دیسک) در گالری و «تصاویر من».

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

            r = client.get("/api/v1/gallery/public")
            gal = r.get_json() or []
            gal_ids = {it["id"] for it in gal}
            check("gallery: ردیف با فایل موجود نشان داده می‌شود",
                  r.status_code == 200 and gal_ok.id in gal_ids,
                  f"got {r.status_code} {gal_ids}")
            check("gallery: ردیف با فایل حذف‌شده پنهان می‌شود",
                  gal_dead.id not in gal_ids,
                  f"got {gal_ids}")

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
            del app.config["S3_ENDPOINT"]

            print(f"\nPASSED={PASSED} FAILED={FAILED}")
            return 1 if FAILED else 0
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
