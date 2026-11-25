"""ChehreSaz v19 item 7: مدل پولی دوم (fallback) برای نسل‌های پولی.

اجرا (از ریشه‌ی ریپو):
    python3 backend/tests/test_v19_paid_fallback.py

سبک مثل tests/test_v19_myket_step1.py: اسکریپت مستقل، بدون pytest،
با SQLite در حافظه (TestConfig) و assertهای شمارش‌شده.
هیچ سرویس خارجی صدا زده نمی‌شود و هیچ فایلی روی دیسک واقعی نوشته
نمی‌شود: دانلود ورودی، فراخوانی مدل و آپلود خروجی همه mock هستند —
به‌خصوص هرگز به پوشه‌ی static/uploads واقعی دست نمی‌زنیم.

پوشش:
- (a) انتخاب مدل پولی دوم: فقط نسل پولی + تنظیم درست → مدل؛
  نسل هدیه، تنظیم خالی، مدلِ برابرِ مدل فعلی، مدل غیرفعال،
  قابلیتِ غیر edit_image، نسل غیر image → None.
- (b) تلاش مجدد پولی موفق: مدل اصلی خطا → مدل دوم موفق؛ نسل done،
  model_id عوض می‌شود، کاربر دقیقاً یک‌بار شارژ می‌شود.
- (c) تنظیم خالی → یک خطا و تمام: نسل failed + رزرو برمی‌گردد.
- (d) هر دو مدل خطا → failed + رزرو برمی‌گردد.
- (e) نسل هدیه هنوز از fallback هدیه استفاده می‌کند، نه پولی؛
  نسل پولی هم از fallback هدیه استفاده نمی‌کند.
- (f) اعتبارسنجی تنظیم در PATCH ادمین: مقدار درست → ۲۰۰؛
  مدل غیرفعال → ۴۰۰؛ مقدار خالی → حذف تنظیم؛ غیرعدد → ۴۰۰.
"""

import base64
import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app import create_app  # noqa: E402
from app import worker as worker_mod  # noqa: E402
from app.config import Config  # noqa: E402
from app.extensions import db  # noqa: E402
from app.models import (  # noqa: E402
    AppCoinBalance,
    AppCoinLedger,
    AppGeneration,
    AppModel,
    AppSetting,
    AppUser,
    AppWorld,
)

PASSED = 0
FAILED = 0

# PNG یک‌پیکسلی برای خروجی mock مدل.
PNG_B64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8"
    "z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)


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


def _admin(client, path, method="GET", body=None):
    return client.open(
        "/api/v1" + path,
        method=method,
        json=body,
        headers={"X-Admin-Token": "test-admin-token"},
    )


def _set_setting(key, value):
    row = AppSetting.query.get(key)
    if row:
        row.value = value
    else:
        db.session.add(AppSetting(key=key, value=value))
    db.session.commit()


def _del_setting(key):
    row = AppSetting.query.get(key)
    if row:
        db.session.delete(row)
        db.session.commit()


def _make_model(name, capability="edit_image", is_active=True):
    m = AppModel(
        id=next_id(),
        kind="image",
        name=name,
        slug=name,
        provider="test",
        capability=capability,
        is_active=is_active,
    )
    db.session.add(m)
    db.session.commit()
    return m


def _make_user(mobile, free_image_credits=0, balance=None):
    u = AppUser(
        id=next_id(),
        mobile=mobile,
        free_image_credits=free_image_credits,
        free_voice_credits=0,
    )
    db.session.add(u)
    if balance is not None:
        db.session.add(AppCoinBalance(user_id=u.id, balance=balance))
    db.session.commit()
    return u


def _balance(user):
    row = AppCoinBalance.query.get(user.id)
    return row.balance if row else 0


def _make_gen(user, world, model, coin_cost=10, charged=True, kind="image"):
    from app.services import coins as coins_svc

    gen = AppGeneration(
        id=next_id(),
        user_id=user.id,
        kind=kind,
        world_id=world.id,
        model_id=model.id,
        input_url="https://example.invalid/in.jpg",
        status="queued",
        coin_cost=coin_cost,
        charged=charged,
    )
    db.session.add(gen)
    db.session.commit()
    res = coins_svc.reserve_for_generation(user, kind, coin_cost, gen.id)
    db.session.commit()
    return gen, res


def _run_worker(test_app, gen_id, script):
    """اجرای هم‌زمان process_image_job با mock کامل:
    دانلود ورودی، فراخوانی مدل و آپلود خروجی — بدون شبکه و بدون دیسک واقعی.
    برمی‌گرداند: لیست id مدل‌هایی که edit_image برایشان صدا زده شد."""
    import app as app_pkg
    import app.services.image as image_mod
    import app.services.storage as storage_mod

    calls = []
    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".jpg")
    tmp.write(b"fake-input")
    tmp.close()

    def _fake_edit(model, local, prompt):
        calls.append(model.id)
        step = script.pop(0)
        if isinstance(step, Exception):
            raise step
        return step

    orig_create = app_pkg.create_app
    orig_dl = worker_mod._download_to_temp
    orig_edit = image_mod.edit_image
    orig_up = storage_mod.upload_bytes
    app_pkg.create_app = lambda *a, **k: test_app
    worker_mod._download_to_temp = lambda url: tmp.name
    image_mod.edit_image = _fake_edit
    storage_mod.upload_bytes = (
        lambda data, filename: "https://example.invalid/result.png"
    )
    try:
        worker_mod.process_image_job(gen_id)
    finally:
        app_pkg.create_app = orig_create
        worker_mod._download_to_temp = orig_dl
        image_mod.edit_image = orig_edit
        storage_mod.upload_bytes = orig_up
        if os.path.exists(tmp.name):
            os.unlink(tmp.name)
    return calls


def _fresh_gen(gen_id):
    db.session.expire_all()
    return AppGeneration.query.get(gen_id)


def _spend_rows(gen_id):
    return AppCoinLedger.query.filter_by(
        ref_type="generation", ref_id=gen_id, reason="spend"
    ).all()


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

        main_m = _make_model("paid-main")
        paid2_m = _make_model("paid-second")
        gift_m = _make_model("gift-main")
        gift2_m = _make_model("gift-second")
        inactive_m = _make_model("paid-inactive", is_active=False)
        wrongcap_m = _make_model("wrong-cap", capability="generate_image")
        world = AppWorld(
            id=next_id(),
            name="w1",
            slug="w1",
            hidden_prompt="p",
            model_id=main_m.id,
            coin_price=10,
            is_active=True,
        )
        db.session.add(world)
        db.session.commit()

        # ============ (a) انتخاب مدل پولی دوم ============
        print("(a) _secondary_paid_model selection")
        u_a = _make_user("09120000011", balance=10)
        gen_paid, res_paid = _make_gen(u_a, world, main_m)
        check("paid gen: reserve returns coins", res_paid == "coins")
        u_g = _make_user("09120000012", free_image_credits=1)
        gen_gift, res_gift = _make_gen(u_g, world, gift_m)
        check("gift gen: reserve returns gift", res_gift == "gift")
        gen_voice = AppGeneration(
            id=next_id(), user_id=u_a.id, kind="voice",
            world_id=world.id, model_id=main_m.id,
            input_url="https://example.invalid/in.mp3",
            status="queued", coin_cost=0, charged=False,
        )
        db.session.add(gen_voice)
        db.session.commit()

        _set_setting("main_image_model_id_2", str(paid2_m.id))
        got = worker_mod._secondary_paid_model(gen_paid)
        check("paid + valid setting -> second model",
              got is not None and got.id == paid2_m.id)
        _del_setting("main_image_model_id_2")
        check("paid + empty setting -> None",
              worker_mod._secondary_paid_model(gen_paid) is None)
        _set_setting("main_image_model_id_2", str(main_m.id))
        check("paid + setting == current model -> None",
              worker_mod._secondary_paid_model(gen_paid) is None)
        _set_setting("main_image_model_id_2", str(inactive_m.id))
        check("paid + inactive model -> None",
              worker_mod._secondary_paid_model(gen_paid) is None)
        _set_setting("main_image_model_id_2", str(wrongcap_m.id))
        check("paid + wrong capability -> None",
              worker_mod._secondary_paid_model(gen_paid) is None)
        _set_setting("main_image_model_id_2", str(paid2_m.id))
        check("gift gen -> None (no paid fallback for gifts)",
              worker_mod._secondary_paid_model(gen_gift) is None)
        check("non-image gen -> None",
              worker_mod._secondary_paid_model(gen_voice) is None)
        _del_setting("main_image_model_id_2")

        # ============ (b) تلاش مجدد پولی موفق ============
        print("(b) paid retry succeeds once")
        _set_setting("main_image_model_id_2", str(paid2_m.id))
        u_b = _make_user("09120000013", balance=10)
        gen_b, _ = _make_gen(u_b, world, main_m)
        calls = _run_worker(
            app, gen_b.id,
            [RuntimeError("provider http 500: boom"),
             {"b64_json": PNG_B64}],
        )
        g = _fresh_gen(gen_b.id)
        check("paid retry: done", g.status == "done", f"got {g.status} {g.error_message}")
        check("paid retry: model switched to second",
              g.model_id == paid2_m.id, f"got {g.model_id}")
        check("paid retry: tried main then second",
              calls == [main_m.id, paid2_m.id], f"got {calls}")
        check("paid retry: charged exactly once (balance 0)",
              _balance(u_b) == 0, f"got {_balance(u_b)}")
        check("paid retry: one spend row",
              len(_spend_rows(gen_b.id)) == 1)
        check("paid retry: output saved",
              (g.output_url or "").startswith("https://example.invalid/"))

        # ============ (c) تنظیم خالی → یک خطا و تمام ============
        print("(c) empty setting: single failure + refund")
        _del_setting("main_image_model_id_2")
        u_c = _make_user("09120000014", balance=10)
        gen_c, _ = _make_gen(u_c, world, main_m)
        calls = _run_worker(
            app, gen_c.id, [RuntimeError("provider http 500: boom")])
        g = _fresh_gen(gen_c.id)
        check("empty setting: failed", g.status == "failed", f"got {g.status}")
        check("empty setting: tried main only",
              calls == [main_m.id], f"got {calls}")
        check("empty setting: reservation refunded (balance 10)",
              _balance(u_c) == 10, f"got {_balance(u_c)}")
        check("empty setting: charged flag cleared",
              g.charged is False)
        check("empty setting: error recorded",
              "500" in (g.error_message or ""))

        # ============ (d) هر دو مدل خطا ============
        print("(d) both models fail: failed + refund")
        _set_setting("main_image_model_id_2", str(paid2_m.id))
        u_d = _make_user("09120000015", balance=10)
        gen_d, _ = _make_gen(u_d, world, main_m)
        calls = _run_worker(
            app, gen_d.id,
            [RuntimeError("provider http 500: boom"),
             RuntimeError("provider http 503: down")],
        )
        g = _fresh_gen(gen_d.id)
        check("both fail: failed", g.status == "failed", f"got {g.status}")
        check("both fail: tried main then second",
              calls == [main_m.id, paid2_m.id], f"got {calls}")
        check("both fail: reservation refunded (balance 10)",
              _balance(u_d) == 10, f"got {_balance(u_d)}")
        check("both fail: charged flag cleared", g.charged is False)
        _del_setting("main_image_model_id_2")

        # ============ (e) هدیه هنوز fallback هدیه؛ پولی نه ============
        print("(e) gift still uses gift fallback only")
        _set_setting("gift_image_model_id", str(gift_m.id))
        _set_setting("gift_image_model_id_2", str(gift2_m.id))
        _set_setting("main_image_model_id_2", str(paid2_m.id))
        u_e = _make_user("09120000016", free_image_credits=1)
        gen_e, res_e = _make_gen(u_e, world, gift_m)
        check("gift gen for (e): reserve returns gift", res_e == "gift")
        calls = _run_worker(
            app, gen_e.id,
            [RuntimeError("provider http 500: boom"),
             {"b64_json": PNG_B64}],
        )
        g = _fresh_gen(gen_e.id)
        check("gift retry: done", g.status == "done", f"got {g.status} {g.error_message}")
        check("gift retry: model switched to gift second (not paid second)",
              g.model_id == gift2_m.id, f"got {g.model_id}")
        check("gift retry: tried gift main then gift second",
              calls == [gift_m.id, gift2_m.id], f"got {calls}")
        _del_setting("main_image_model_id_2")
        u_e2 = _make_user("09120000017", balance=10)
        gen_e2, _ = _make_gen(u_e2, world, main_m)
        calls = _run_worker(
            app, gen_e2.id, [RuntimeError("provider http 500: boom")])
        g = _fresh_gen(gen_e2.id)
        check("paid gen does not use gift fallback",
              g.status == "failed" and calls == [main_m.id],
              f"got {g.status} {calls}")
        check("paid gen without paid fallback: refunded",
              _balance(u_e2) == 10, f"got {_balance(u_e2)}")
        _del_setting("gift_image_model_id")
        _del_setting("gift_image_model_id_2")

        # ============ (f) اعتبارسنجی PATCH ادمین ============
        print("(f) admin PATCH validation for main_image_model_id_2")
        r = _admin(client, "/admin/settings", "PATCH",
                   {"key": "main_image_model_id_2", "value": str(paid2_m.id)})
        check("PATCH valid model -> 200",
              r.status_code == 200, f"got {r.status_code} {r.data!r}")
        row = AppSetting.query.get("main_image_model_id_2")
        check("PATCH valid model -> stored",
              row is not None and row.value == str(paid2_m.id))
        r = _admin(client, "/admin/settings", "PATCH",
                   {"key": "main_image_model_id_2", "value": str(inactive_m.id)})
        check("PATCH inactive model -> 400",
              r.status_code == 400, f"got {r.status_code} {r.data!r}")
        r = _admin(client, "/admin/settings", "PATCH",
                   {"key": "main_image_model_id_2", "value": "abc"})
        check("PATCH non-integer -> 400",
              r.status_code == 400, f"got {r.status_code} {r.data!r}")
        r = _admin(client, "/admin/settings", "PATCH",
                   {"key": "main_image_model_id_2", "value": ""})
        check("PATCH empty -> 200 and setting deleted",
              r.status_code == 200
              and AppSetting.query.get("main_image_model_id_2") is None,
              f"got {r.status_code} {r.data!r}")


if __name__ == "__main__":
    main()
    print(f"\nPASSED: {PASSED}  FAILED: {FAILED}")
    sys.exit(1 if FAILED else 0)
