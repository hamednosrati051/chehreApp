"""ChehreSaz v18: with_image + orphan cleanup + tickets + regenerate +
gallery is_free/world_name + promo settings + pack original_price_toman.

اجرا (از ریشه‌ی ریپو):
    python3 backend/tests/test_v18.py

سبک مثل tests/test_v17.py: اسکریپت مستقل، بدون pytest،
با SQLite در حافظه (TestConfig) و assertهای شمارش‌شده.
هیچ سرویس خارجی صدا زده نمی‌شود: Celery با send_task جعلی است
(هیچ نسلی واقعاً پردازش نمی‌شود) و ذخیره‌ی فایل روی دیسکِ
static/uploadsِ همین کپیِ تست انجام می‌شود (بعد از هر بخش پاک می‌شود).

پوشش:
- (a) with_image: موفقیت با اعتبار هدیه (فایل + ردیف وصل‌اند،
  سکه کم نمی‌شود) و با سکه (موجودی کم + سطر spend)؛ 402 پیش از
  store (هیچ فایلی اضافه نمی‌شود)؛ 404 دنیا؛ 400 بدون فایل؛ 401
  بدون توکن؛ خطای DB بعد از store → فایل پاک + رزرو برگشت + ردیف حذف.
- (b) orphan cleanup: فقط فایل قدیمیِ بدون ارجاع حذف می‌شود (تازه،
  ارجاع‌شده و زیردایرکتوری می‌مانند)؛ با S3_ENDPOINT ست‌شده هیچ‌چیز
  حذف نمی‌شود.
- (c) tickets: ساخت/لیست/جزئیات/پاسخ کاربر؛ مالکیت (403 روی تیکت
  دیگری)؛ پاسخ ادمین + بستن/بازکردن؛ پاسخ کاربر به تیکت بسته → 422.
- (d) regenerate: happy path با قیمتِ جاری دنیا، 403 دیگری، 404 نبودن.
- (e) gallery public: is_free + world_name؛ ردیف pending نمی‌آید.
- (f) packs: original_price_toman در پک‌های عمومی + ادمین (ساخت/ PATCH/
  خالی‌کردن/مقدار بد)؛ price_toman (مبلغ پرداختی) دست نمی‌خورد.
- (g) promo: چهار کلید در whitelist تنظیمات عمومی؛ پیش‌فرض خالی‌اند.
"""

import datetime
import io
import os
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from werkzeug.security import generate_password_hash  # noqa: E402

from app import create_app  # noqa: E402
from app import worker as worker_mod  # noqa: E402
from app.config import Config  # noqa: E402
from app.extensions import db  # noqa: E402
from app.models import (  # noqa: E402
    AppCoinBalance,
    AppCoinLedger,
    AppCoinPack,
    AppGallery,
    AppGeneration,
    AppModel,
    AppOtpCode,
    AppTicket,
    AppUser,
    AppWorld,
)
from app.services import coins as coins_svc  # noqa: E402
from app.services import storage as storage_svc  # noqa: E402
from app.services.cleanup import cleanup_orphan_uploads  # noqa: E402

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


def _otp_login(client, mobile, code="123456", extra=None):
    db.session.add(
        AppOtpCode(
            id=next_id(),
            mobile=mobile,
            code_hash=generate_password_hash(code),
            expires_at=datetime.datetime.utcnow() + datetime.timedelta(minutes=10),
        )
    )
    db.session.commit()
    body = {"mobile": mobile, "code": code}
    if extra:
        body.update(extra)
    r = client.post("/api/v1/auth/otp/verify", json=body)
    assert r.status_code == 200, f"otp verify failed: {r.status_code} {r.data!r}"
    return r.get_json()["token"]


def _admin(client, path, method="GET", body=None):
    return client.open(
        "/api/v1" + path,
        method=method,
        json=body,
        headers={"X-Admin-Token": "test-admin-token"},
    )


def _auth(client, token, path, method="POST", body=None):
    return client.open(
        "/api/v1" + path,
        method=method,
        json=body,
        headers={"Authorization": f"Bearer {token}"},
    )


def _with_image(client, token, world_id, data=b"\x89PNG\r\n\x1a\nfake-image-bytes"):
    return client.post(
        "/api/v1/generations/with_image",
        data={
            "world_id": str(world_id),
            "file": (io.BytesIO(data), "selfie.jpg"),
        },
        headers={"Authorization": f"Bearer {token}"},
        content_type="multipart/form-data",
    )


def main():
    app = create_app(TestConfig)
    with app.app_context():
        from sqlalchemy import BigInteger, Integer

        for _table in db.metadata.tables.values():
            for _col in _table.columns:
                if _col.primary_key and isinstance(_col.type, BigInteger):
                    _col.type = Integer()
        db.create_all()
        client = app.test_client()
        uploads_dir = os.path.join(app.static_folder, "uploads")
        os.makedirs(uploads_dir, exist_ok=True)

        # --- Celery جعلی: هیچ تسکی واقعاً اجرا نمی‌شود ---
        sent_tasks = []
        orig_send_task = worker_mod.celery_app.send_task

        def _fake_send_task(name, args=None, **kw):
            sent_tasks.append((name, list(args or [])))
            return None

        worker_mod.celery_app.send_task = _fake_send_task

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
        pack_a = AppCoinPack(
            id=next_id(),
            name="pack A",
            coins=20,
            price_toman=59900,
            bazaar_sku="pack_a",
            is_active=True,
        )
        pack_b = AppCoinPack(
            id=next_id(),
            name="pack B",
            coins=50,
            price_toman=149900,
            bazaar_sku="pack_b",
            is_active=True,
            original_price_toman=199900,
        )
        db.session.add_all([model, world, pack_a, pack_b])
        db.session.commit()

        user_gift = AppUser(
            id=next_id(), mobile="09120000001",
            free_image_credits=1, free_voice_credits=0,
        )
        user_coins = AppUser(
            id=next_id(), mobile="09120000002",
            free_image_credits=0, free_voice_credits=0,
        )
        user_broke = AppUser(
            id=next_id(), mobile="09120000003",
            free_image_credits=0, free_voice_credits=0,
        )
        db.session.add_all([user_gift, user_coins, user_broke])
        db.session.add(AppCoinBalance(user_id=user_coins.id, balance=50))
        db.session.commit()
        auth_gift = _otp_login(client, "09120000001")
        auth_coins = _otp_login(client, "09120000002")
        auth_broke = _otp_login(client, "09120000003")

        def balance(u):
            db.session.expire_all()
            return coins_svc.get_balance(AppUser.query.get(u.id))

        def fresh_user(u):
            db.session.expire_all()
            return AppUser.query.get(u.id)

        # ============ (a) with_image ============
        before = set(os.listdir(uploads_dir))
        r = _with_image(client, auth_gift, world.id)
        j = r.get_json() or {}
        gen_id_gift = j.get("id")
        check("with_image gift: 201 + queued",
              r.status_code == 201 and j.get("status") == "queued",
              f"got {r.status_code} {j}")
        gen = AppGeneration.query.get(gen_id_gift) if gen_id_gift else None
        stored_name = None
        if gen is not None and gen.input_url:
            stored_name = os.path.basename(gen.input_url)
        check("with_image gift: row linked to stored file, file on disk",
              gen is not None
              and gen.input_url is not None
              and stored_name is not None
              and os.path.exists(os.path.join(uploads_dir, stored_name)),
              f"got input_url={gen.input_url if gen else None}")
        check("with_image gift: gift credit spent, no coin spend",
              fresh_user(user_gift).free_image_credits == 0
              and balance(user_gift) == 0
              and AppCoinLedger.query.filter_by(
                  ref_type="generation", ref_id=gen_id_gift,
                  reason="spend").first() is None,
              f"got free={fresh_user(user_gift).free_image_credits}")
        check("with_image gift: celery task enqueued once",
              (len(sent_tasks) == 1
               and sent_tasks[0][0] == "app.worker.process_image_job"
               and sent_tasks[0][1] == [gen_id_gift]),
              f"got {sent_tasks}")
        if stored_name:
            os.unlink(os.path.join(uploads_dir, stored_name))

        r = _with_image(client, auth_coins, world.id)
        j = r.get_json() or {}
        gen_id_coins = j.get("id")
        check("with_image coins: 201",
              r.status_code == 201 and gen_id_coins is not None,
              f"got {r.status_code} {j}")
        check("with_image coins: balance 50 -> 40 + spend ledger row",
              balance(user_coins) == 40
              and AppCoinLedger.query.filter_by(
                  ref_type="generation", ref_id=gen_id_coins,
                  reason="spend").first() is not None,
              f"got balance={balance(user_coins)}")
        gen2 = AppGeneration.query.get(gen_id_coins)
        if gen2 is not None and gen2.input_url:
            os.unlink(os.path.join(uploads_dir, os.path.basename(gen2.input_url)))

        gens_before = AppGeneration.query.count()
        before = set(os.listdir(uploads_dir))
        r = _with_image(client, auth_broke, world.id)
        check("with_image broke: 402 insufficient coins",
              r.status_code == 402,
              f"got {r.status_code} {r.data!r}")
        check("with_image 402: no file stored",
              set(os.listdir(uploads_dir)) == before,
              f"got {set(os.listdir(uploads_dir)) - before}")
        check("with_image 402: no generation row created",
              AppGeneration.query.count() == gens_before)

        r = _with_image(client, auth_gift, 999999)
        check("with_image unknown world: 404",
              r.status_code == 404, f"got {r.status_code} {r.data!r}")
        r = client.post(
            "/api/v1/generations/with_image",
            data={"world_id": str(world.id)},
            headers={"Authorization": f"Bearer {auth_gift}"},
            content_type="multipart/form-data",
        )
        check("with_image no file: 400",
              r.status_code == 400, f"got {r.status_code} {r.data!r}")
        r = client.post(
            "/api/v1/generations/with_image",
            data={"world_id": str(world.id),
                  "file": (io.BytesIO(b"x"), "s.jpg")},
            content_type="multipart/form-data",
        )
        check("with_image no token: 401",
              r.status_code == 401, f"got {r.status_code} {r.data!r}")

        # --- خطای DB بعد از store: فایل پاک + رزرو برگشت + ردیف حذف ---
        # commit را فقط یک بار (همان commit بعد از store) می‌شکنیم:
        # upload واقعی انجام می‌شود، بعد fail_once مسلح می‌شود.
        orig_upload = storage_svc.upload_bytes
        orig_commit = db.session.commit
        state = {"fail_once": False}

        def _upload_then_arm(data, filename):
            url = orig_upload(data, filename)
            state["fail_once"] = True
            return url

        def _commit_once_fail(*a, **k):
            if state["fail_once"]:
                state["fail_once"] = False
                raise RuntimeError("simulated db failure")
            return orig_commit(*a, **k)

        storage_svc.upload_bytes = _upload_then_arm
        db.session.commit = _commit_once_fail
        gens_before = AppGeneration.query.count()
        before = set(os.listdir(uploads_dir))
        try:
            r = _with_image(client, auth_coins, world.id)
        finally:
            storage_svc.upload_bytes = orig_upload
            try:
                del db.session.commit
            except AttributeError:
                pass
        check("with_image db-failure: 500",
              r.status_code == 500, f"got {r.status_code} {r.data!r}")
        check("with_image db-failure: just-stored file deleted",
              set(os.listdir(uploads_dir)) == before,
              f"got {set(os.listdir(uploads_dir)) - before}")
        check("with_image db-failure: generation row removed",
              AppGeneration.query.count() == gens_before)
        check("with_image db-failure: coin reservation refunded (40)",
              balance(user_coins) == 40,
              f"got balance={balance(user_coins)}")

        # --- یتیم‌های ازپیش‌موجود در uploadsِ کپیِ تست (فایل‌های نمونه‌ی
        # ریپو، قدیمی و بدون ارجاع در DB خالی تست) هم پاک می‌شوند؛
        # پس تعداد موردانتظار = آن‌ها + ۱ فایل یتیم قدیمی خود تست. ---
        from app.routes import (  # noqa: E402
            _orphan_upload_files,
            _referenced_upload_basenames,
            _uploads_dir,
        )

        pre_orphans = _orphan_upload_files(
            _uploads_dir(), _referenced_upload_basenames()
        )
        now = time.time()
        old_orphan = os.path.join(uploads_dir, "v18-old-orphan.jpg")
        fresh_orphan = os.path.join(uploads_dir, "v18-fresh-orphan.jpg")
        ref_file = os.path.join(uploads_dir, "v18-ref.jpg")
        sub_dir = os.path.join(uploads_dir, "v18-sub")
        os.makedirs(sub_dir, exist_ok=True)
        sub_file = os.path.join(sub_dir, "v18-sub-old.jpg")
        for p in (old_orphan, fresh_orphan, ref_file, sub_file):
            with open(p, "wb") as fh:
                fh.write(b"v18-test-bytes")
        os.utime(old_orphan, (now - 7200, now - 7200))
        os.utime(ref_file, (now - 7200, now - 7200))
        os.utime(sub_file, (now - 7200, now - 7200))
        ref_gen = AppGeneration(
            id=next_id(), user_id=user_gift.id, kind="image",
            world_id=world.id, model_id=model.id,
            input_url="http://171.22.25.152:5001/static/uploads/v18-ref.jpg",
            status="done", coin_cost=0,
        )
        db.session.add(ref_gen)
        db.session.commit()
        result = cleanup_orphan_uploads()
        check("orphan cleanup: old unreferenced deleted (1 + pre-existing)",
              result.get("deleted") == len(pre_orphans) + 1
              and result.get("bytes", 0) > 0,
              f"got {result} pre={len(pre_orphans)}")
        check("orphan cleanup: old orphan gone, fresh/referenced/sub stay",
              not os.path.exists(old_orphan)
              and os.path.exists(fresh_orphan)
              and os.path.exists(ref_file)
              and os.path.exists(sub_file),
              f"old={os.path.exists(old_orphan)} fresh={os.path.exists(fresh_orphan)}")
        check("orphan cleanup: second run deletes 0",
              cleanup_orphan_uploads().get("deleted") == 0)
        app.config["S3_ENDPOINT"] = "https://s3.example.invalid"
        result = cleanup_orphan_uploads()
        check("orphan cleanup S3: skipped, nothing deleted",
              result.get("skipped_s3") is True
              and result.get("deleted") == 0
              and os.path.exists(fresh_orphan),
              f"got {result}")
        app.config["S3_ENDPOINT"] = ""
        for p in (fresh_orphan, ref_file, sub_file):
            if os.path.exists(p):
                os.unlink(p)
        os.rmdir(sub_dir)

        # ============ (c) tickets ============
        r = _auth(client, auth_gift, "/tickets", "POST",
                  {"subject": "مشکل ورود", "message": "سلام، وارد نمی‌شوم"})
        j = r.get_json() or {}
        tid = j.get("id")
        check("tickets create: 201 + open",
              r.status_code == 201 and j.get("status") == "open"
              and j.get("subject") == "مشکل ورود",
              f"got {r.status_code} {j}")
        r = _auth(client, auth_gift, "/tickets", "POST",
                  {"subject": "  ", "message": "x"})
        check("tickets create empty subject: 400",
              r.status_code == 400, f"got {r.status_code}")
        r = _auth(client, auth_gift, "/tickets", "GET")
        items = r.get_json() or []
        check("tickets list: own ticket only",
              r.status_code == 200 and len(items) == 1
              and items[0].get("id") == tid,
              f"got {r.status_code} {items}")
        r = _auth(client, auth_coins, "/tickets", "GET")
        check("tickets list: other user sees none",
              r.status_code == 200 and (r.get_json() or []) == [],
              f"got {r.get_json()}")
        r = _auth(client, auth_gift, f"/tickets/{tid}", "GET")
        j = r.get_json() or {}
        check("tickets detail: first message sender=user",
              r.status_code == 200
              and len(j.get("messages") or []) == 1
              and j["messages"][0].get("sender") == "user"
              and j["messages"][0].get("body") == "سلام، وارد نمی‌شوم",
              f"got {r.status_code} {j}")
        r = _auth(client, auth_coins, f"/tickets/{tid}", "GET")
        check("tickets detail other user: 403",
              r.status_code == 403, f"got {r.status_code}")
        r = _auth(client, auth_coins, f"/tickets/{tid}/messages", "POST",
                  {"body": "hijack"})
        check("tickets reply other user: 403",
              r.status_code == 403, f"got {r.status_code}")
        r = _auth(client, auth_gift, "/tickets/999999", "GET")
        check("tickets detail missing: 404",
              r.status_code == 404, f"got {r.status_code}")
        r = _auth(client, auth_gift, f"/tickets/{tid}/messages", "POST",
                  {"body": "هنوز مشکل دارم"})
        check("tickets user reply: 201 sender=user",
              r.status_code == 201
              and (r.get_json() or {}).get("sender") == "user",
              f"got {r.status_code} {r.data!r}")
        r = _admin(client, "/admin/tickets")
        items = r.get_json() or []
        check("admin tickets list: sees the ticket",
              r.status_code == 200 and len(items) == 1
              and items[0].get("user_id") == user_gift.id,
              f"got {r.status_code} {items}")
        r = _admin(client, f"/admin/tickets/{tid}")
        j = r.get_json() or {}
        check("admin tickets detail: 2 messages",
              r.status_code == 200 and len(j.get("messages") or []) == 2,
              f"got {r.status_code} {j}")
        r = _admin(client, f"/admin/tickets/{tid}/messages", "POST",
                   {"body": "بررسی می‌کنیم"})
        check("admin tickets reply: 201 sender=admin",
              r.status_code == 201
              and (r.get_json() or {}).get("sender") == "admin",
              f"got {r.status_code} {r.data!r}")
        r = _admin(client, f"/admin/tickets/{tid}", "PATCH",
                   {"status": "closed"})
        check("admin tickets close: 200 status=closed",
              r.status_code == 200
              and (r.get_json() or {}).get("status") == "closed",
              f"got {r.status_code} {r.data!r}")
        r = _auth(client, auth_gift, f"/tickets/{tid}/messages", "POST",
                  {"body": "دوباره"})
        check("tickets reply to closed: 422",
              r.status_code == 422, f"got {r.status_code} {r.data!r}")
        r = _admin(client, f"/admin/tickets/{tid}", "PATCH",
                   {"status": "bogus"})
        check("admin tickets bad status: 400",
              r.status_code == 400, f"got {r.status_code}")
        r = _admin(client, f"/admin/tickets/{tid}", "PATCH",
                   {"status": "open"})
        check("admin tickets reopen: 200 status=open",
              r.status_code == 200
              and (r.get_json() or {}).get("status") == "open",
              f"got {r.status_code} {r.data!r}")
        r = _auth(client, auth_gift, f"/tickets/{tid}/messages", "POST",
                  {"body": "ممنون، درست شد"})
        check("tickets reply after reopen: 201",
              r.status_code == 201, f"got {r.status_code} {r.data!r}")
        r = _admin(client, "/admin/tickets/999999", "GET")
        check("admin tickets missing: 404",
              r.status_code == 404, f"got {r.status_code}")

        # ============ (d) regenerate ============
        regen_gen = AppGeneration(
            id=next_id(), user_id=user_gift.id, kind="image",
            world_id=world.id, model_id=model.id,
            input_url="http://171.22.25.152:5001/static/uploads/v18-ref.jpg",
            status="done", coin_cost=10,
        )
        db.session.add(regen_gen)
        db.session.commit()
        world.coin_price = 7
        db.session.commit()
        r = client.get(
            f"/api/v1/generations/{regen_gen.id}/regenerate",
            headers={"Authorization": f"Bearer {auth_gift}"},
        )
        j = r.get_json() or {}
        check("regenerate happy: current world price (7, not 10)",
              r.status_code == 200
              and j.get("generation_id") == regen_gen.id
              and j.get("world_id") == world.id
              and j.get("input_url") == regen_gen.input_url
              and j.get("coin_price") == 7,
              f"got {r.status_code} {j}")
        check("regenerate: no charge (balance/credit untouched)",
              balance(user_gift) == 0
              and fresh_user(user_gift).free_image_credits == 0)
        r = client.get(
            f"/api/v1/generations/{regen_gen.id}/regenerate",
            headers={"Authorization": f"Bearer {auth_coins}"},
        )
        check("regenerate other user: 403",
              r.status_code == 403, f"got {r.status_code}")
        r = client.get(
            "/api/v1/generations/999999/regenerate",
            headers={"Authorization": f"Bearer {auth_gift}"},
        )
        check("regenerate missing: 404",
              r.status_code == 404, f"got {r.status_code}")
        r = client.get(f"/api/v1/generations/{regen_gen.id}/regenerate")
        check("regenerate no token: 401",
              r.status_code == 401, f"got {r.status_code}")

        # ============ (e) gallery public ============
        paid_gen = AppGeneration(
            id=next_id(), user_id=user_coins.id, kind="image",
            world_id=world.id, model_id=model.id,
            output_url="http://171.22.25.152:5001/static/uploads/paid.jpg",
            status="done", coin_cost=10,
        )
        free_gen = AppGeneration(
            id=next_id(), user_id=user_gift.id, kind="image",
            world_id=world.id, model_id=model.id,
            output_url="http://171.22.25.152:5001/static/uploads/free.jpg",
            status="done", coin_cost=10,
        )
        db.session.add_all([paid_gen, free_gen])
        db.session.add(AppCoinLedger(
            id=next_id(), user_id=user_coins.id, delta=-10,
            reason="spend", ref_type="generation", ref_id=paid_gen.id,
        ))
        db.session.add_all([
            AppGallery(
                id=next_id(), user_id=user_coins.id,
                generation_id=paid_gen.id,
                image_url=paid_gen.output_url,
                status="approved", published_in_app=True,
            ),
            AppGallery(
                id=next_id(), user_id=user_gift.id,
                generation_id=free_gen.id,
                image_url=free_gen.output_url,
                status="approved", published_in_app=True,
            ),
            AppGallery(
                id=next_id(), user_id=user_gift.id,
                generation_id=free_gen.id,
                image_url=free_gen.output_url,
                status="pending", published_in_app=False,
            ),
        ])
        db.session.commit()
        r = client.get("/api/v1/gallery/public")
        rows = r.get_json() or []
        by_gen = {row["generation_id"]: row for row in rows}
        check("gallery public: only approved+published (2 rows)",
              r.status_code == 200 and len(rows) == 2,
              f"got {r.status_code} {rows}")
        check("gallery public paid: is_free=false + world_name + world_id",
              by_gen.get(paid_gen.id, {}).get("is_free") is False
              and by_gen.get(paid_gen.id, {}).get("world_name") == "دنیای تست"
              and by_gen.get(paid_gen.id, {}).get("world_id") == test_world.id,
              f"got {by_gen.get(paid_gen.id)}")
        check("gallery public free: is_free=true + world_name + world_id",
              by_gen.get(free_gen.id, {}).get("is_free") is True
              and by_gen.get(free_gen.id, {}).get("world_name") == "دنیای تست"
              and by_gen.get(free_gen.id, {}).get("world_id") == test_world.id,
              f"got {by_gen.get(free_gen.id)}")

        # ============ (f) packs original_price_toman ============
        r = client.get("/api/v1/coins/packs")
        packs = {p["id"]: p for p in (r.get_json() or [])}
        check("packs public: A null, B 199900",
              packs.get(pack_a.id, {}).get("original_price_toman") is None
              and packs.get(pack_b.id, {}).get("original_price_toman") == 199900,
              f"got {packs}")
        r = _admin(client, "/admin/packs", "POST",
                   {"name": "pack C", "coins": 10, "price_toman": 29900,
                    "bazaar_sku": "pack_c", "original_price_toman": 39900})
        j = r.get_json() or {}
        check("admin create pack with original: 201 + field",
              r.status_code == 201
              and j.get("original_price_toman") == 39900,
              f"got {r.status_code} {j}")
        pack_c_id = j.get("id")
        r = _admin(client, f"/admin/packs/{pack_a.id}", "PATCH",
                   {"original_price_toman": 79900})
        check("admin PATCH pack original: 200 + field",
              r.status_code == 200
              and (r.get_json() or {}).get("original_price_toman") == 79900,
              f"got {r.status_code} {r.data!r}")
        r = client.get("/api/v1/coins/packs")
        packs = {p["id"]: p for p in (r.get_json() or [])}
        check("packs public after PATCH: A 79900, price_toman unchanged",
              packs.get(pack_a.id, {}).get("original_price_toman") == 79900
              and packs.get(pack_a.id, {}).get("price_toman") == 59900,
              f"got {packs.get(pack_a.id)}")
        r = _admin(client, f"/admin/packs/{pack_a.id}", "PATCH",
                   {"original_price_toman": None})
        check("admin PATCH pack original null: 200 + null",
              r.status_code == 200
              and (r.get_json() or {}).get("original_price_toman") is None,
              f"got {r.status_code} {r.data!r}")
        r = _admin(client, f"/admin/packs/{pack_a.id}", "PATCH",
                   {"original_price_toman": "abc"})
        check("admin PATCH pack original bad: 400",
              r.status_code == 400, f"got {r.status_code} {r.data!r}")
        check("packs: charge amount untouched (price_toman still 59900)",
              AppCoinPack.query.get(pack_a.id).price_toman == 59900)

        # ============ (g) promo settings ============
        r = client.get("/api/v1/app/settings")
        j = r.get_json() or {}
        check("promo keys present in public settings (empty by default)",
              all(k in j for k in (
                  "promo_modal_title", "promo_modal_body",
                  "promo_modal_show_number", "promo_modal_enabled"))
              and j.get("promo_modal_title") == "",
              f"got {j}")
        for key, val in (
            ("promo_modal_title", "بلک‌فرایدی"),
            ("promo_modal_body", "تخفیف ویژه"),
            ("promo_modal_show_number", "3"),
            ("promo_modal_enabled", "1"),
        ):
            rr = _admin(client, "/admin/settings", "PATCH",
                        {"key": key, "value": val})
            assert rr.status_code == 200, f"settings PATCH {key}: {rr.data!r}"
        r = client.get("/api/v1/app/settings")
        j = r.get_json() or {}
        check("promo keys served after admin PATCH",
              j.get("promo_modal_title") == "بلک‌فرایدی"
              and j.get("promo_modal_body") == "تخفیف ویژه"
              and j.get("promo_modal_show_number") == "3"
              and j.get("promo_modal_enabled") == "1",
              f"got {j}")

        worker_mod.celery_app.send_task = orig_send_task
        print(f"\ntest_v18: {PASSED} passed, {FAILED} failed")
        return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
