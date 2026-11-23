"""ChehreSaz v19 step 1: فلِیور مایکت — بک‌اند + ادمین، فقط گیت (بدون لایو).

اجرا (از ریشه‌ی ریپو):
    python3 backend/tests/test_v19_myket_step1.py

سبک مثل tests/test_v17.py: اسکریپت مستقل، بدون pytest،
با SQLite در حافظه (TestConfig) و assertهای شمارش‌شده.
هیچ سرویس خارجی صدا زده نمی‌شود: فراخوان‌های زیبال mock است
(_post در app.services.zibal جایگزین می‌شود) و پیامک هم mock است.

پوشش:
- (a) پرچم‌های جدید پک (myket_enabled / myket_zibal_enabled /
  show_fallback_button_myket) در GET /coins/packs + لیست ادمین، با
  پیش‌فرض‌های درست؛ پرچم‌های بازار سر جاشان.
- (b) PATCH ادمین: سه پرچم مایکت عوض می‌شوند و پرچم‌های بازار همان
  PATCH دست نمی‌خورند.
- (c) POST ادمین: ساخت پک با پرچم‌های مایکت.
- (d) ‎/app-config: min_app_version_code_myket جدا (پیش‌فرض ۰)؛
  PATCH معتبر/نامعتبر/خالی مثل کلید بازار؛ کلید بازار هنوز کار می‌کند.
- (e) تنظیمات عمومی: دو کلید ورود مایکت در PUBLIC_SETTINGS هستند.
- (f) دروازه‌ی زیبال: شاخه‌ی جدا مایکت (if/elif) — درخواست مایکت فقط
  پرچم‌های مایکت را می‌خواند؛ مسیر بازار byte-identical می‌ماند.
  فالبک مایکت هم جدا.
- (g) ورود با پیامک: چکِ سراسری بازار دست‌نخورده (master switch) +
  شاخه‌ی جدا مایکت؛ تاگل مایکت روی بازار اثر ندارد.
- (h) ورود با بازار: تاگل مایکت فقط درخواست مایکت را ۴۰۳ می‌کند؛
  نبودن کلید یعنی خاموش (پیش‌فرض)؛ روی بازار اثر ندارد.
"""

import datetime
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from werkzeug.security import generate_password_hash  # noqa: E402

from app import create_app  # noqa: E402
from app.config import Config  # noqa: E402
from app.extensions import db  # noqa: E402
from app.models import (  # noqa: E402
    AppCoinPack,
    AppOtpCode,
    AppPurchase,
    AppSetting,
    AppUser,
)
from app.services import sms as sms_module  # noqa: E402
from app.services import zibal as zibal_svc  # noqa: E402

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

        pack_bazaar = AppCoinPack(
            id=next_id(),
            name="bazaar pack",
            coins=50,
            price_toman=149900,
            bazaar_sku="pack_v19_bazaar",
            is_active=True,
            gift_coins=0,
            gift_scope="every",
            zibal_enabled=True,
        )
        pack_myket = AppCoinPack(
            id=next_id(),
            name="myket pack",
            coins=50,
            price_toman=149900,
            bazaar_sku="pack_v19_myket",
            is_active=True,
            gift_coins=0,
            gift_scope="every",
            zibal_enabled=False,
        )
        pack_fallback = AppCoinPack(
            id=next_id(),
            name="fallback pack",
            coins=10,
            price_toman=29900,
            bazaar_sku="pack_v19_fallback",
            is_active=True,
            gift_coins=0,
            gift_scope="every",
            zibal_enabled=False,
        )
        db.session.add_all([pack_bazaar, pack_myket, pack_fallback])
        db.session.commit()

        user = AppUser(
            id=next_id(),
            mobile="09120000001",
            free_image_credits=1,
            free_voice_credits=0,
        )
        db.session.add(user)
        db.session.commit()
        auth = _otp_login(client, "09120000001")

        # --- mock زیبال + پیامک: هیچ تماسی به بیرون نیست ---
        app.config["ZIBAL_MERCHANT"] = "zibal"
        request_seq = [900000]

        def _fake_post(url, body):
            if url.endswith("/request"):
                request_seq[0] += 1
                return {"result": 100, "trackId": request_seq[0]}
            if url.endswith("/verify"):
                return {"result": 100, "trackId": int(body.get("trackId")), "amount": 1499000}
            return {"result": 101, "message": "unexpected"}

        zibal_svc._post = _fake_post
        sms_calls = []
        sms_module.send_otp_sms = (
            lambda mobile, code: sms_calls.append((mobile, code)) or True
        )

        # --- (a) پرچم‌های پک: پیش‌فرض‌ها ---
        r = client.get("/api/v1/coins/packs")
        j = r.get_json() or []
        p_b = next((p for p in j if p["id"] == pack_bazaar.id), None)
        p_m = next((p for p in j if p["id"] == pack_myket.id), None)
        check("packs: myket flags present with right defaults",
              p_m is not None
              and p_m.get("myket_enabled") is True
              and p_m.get("myket_zibal_enabled") is False
              and p_m.get("show_fallback_button_myket") is False,
              f"got {p_m}")
        check("packs: bazaar flags untouched",
              p_b is not None
              and p_b.get("bazaar_enabled") is True
              and p_b.get("zibal_enabled") is True
              and p_b.get("show_fallback_button") is False,
              f"got {p_b}")
        r = _admin(client, "/admin/packs")
        j = r.get_json() or []
        p_m = next((p for p in j if p["id"] == pack_myket.id), None)
        check("admin packs list: myket flags present",
              p_m is not None
              and p_m.get("myket_enabled") is True
              and p_m.get("myket_zibal_enabled") is False
              and p_m.get("show_fallback_button_myket") is False,
              f"got {p_m}")

        # --- (b) PATCH ادمین: پرچم‌های مایکت عوض می‌شوند، بازار نه ---
        r = _admin(client, f"/admin/packs/{pack_myket.id}", "PATCH",
                   {"myket_enabled": False, "myket_zibal_enabled": True,
                    "show_fallback_button_myket": True})
        j = r.get_json() or {}
        check("admin PATCH: myket flags flip",
              r.status_code == 200
              and j.get("myket_enabled") is False
              and j.get("myket_zibal_enabled") is True
              and j.get("show_fallback_button_myket") is True,
              f"got {r.status_code} {j}")
        check("admin PATCH: bazaar flags untouched by the same PATCH",
              j.get("bazaar_enabled") is True
              and j.get("zibal_enabled") is False
              and j.get("show_fallback_button") is False,
              f"got {j}")
        # برگرداندن به پیش‌فرض برای بخش‌های بعد
        _admin(client, f"/admin/packs/{pack_myket.id}", "PATCH",
               {"myket_enabled": True, "myket_zibal_enabled": False,
                "show_fallback_button_myket": False})

        # --- (c) POST ادمین: ساخت پک با پرچم‌های مایکت ---
        r = _admin(client, "/admin/packs", "POST",
                   {"name": "new myket pack", "coins": 20,
                    "price_toman": 59900, "bazaar_sku": "pack_v19_new",
                    "myket_enabled": False, "myket_zibal_enabled": True,
                    "show_fallback_button_myket": True})
        j = r.get_json() or {}
        check("admin POST: pack created with myket flags",
              r.status_code == 201
              and j.get("myket_enabled") is False
              and j.get("myket_zibal_enabled") is True
              and j.get("show_fallback_button_myket") is True,
              f"got {r.status_code} {j}")

        # --- (d) ‎/app-config: حداقل نسخه‌ی مایکت جدا ---
        r = client.get("/api/v1/app-config")
        j = r.get_json() or {}
        check("app-config: both keys present, myket defaults 0",
              j.get("min_app_version_code") == 0
              and j.get("min_app_version_code_myket") == 0,
              f"got {j}")
        r = _admin(client, "/admin/settings", "PATCH",
                   {"key": "min_app_version_code_myket", "value": "18"})
        check("settings PATCH: myket min version saved",
              r.status_code == 200
              and (r.get_json() or {}).get("value") == "18",
              f"got {r.status_code} {r.data!r}")
        r = client.get("/api/v1/app-config")
        j = r.get_json() or {}
        check("app-config: myket min version 18, bazaar still 0",
              j.get("min_app_version_code_myket") == 18
              and j.get("min_app_version_code") == 0,
              f"got {j}")
        r = _admin(client, "/admin/settings", "PATCH",
                   {"key": "min_app_version_code_myket", "value": "abc"})
        check("settings PATCH: myket min version invalid -> 400",
              r.status_code == 400, f"got {r.status_code} {r.data!r}")
        r = _admin(client, "/admin/settings", "PATCH",
                   {"key": "min_app_version_code", "value": "13"})
        check("settings PATCH: bazaar min version still works",
              r.status_code == 200
              and (r.get_json() or {}).get("value") == "13",
              f"got {r.status_code} {r.data!r}")
        r = client.get("/api/v1/app-config")
        j = r.get_json() or {}
        check("app-config: bazaar 13, myket 18, independent",
              j.get("min_app_version_code") == 13
              and j.get("min_app_version_code_myket") == 18,
              f"got {j}")
        r = _admin(client, "/admin/settings", "PATCH",
                   {"key": "min_app_version_code_myket", "value": ""})
        check("settings PATCH: myket min version empty deletes row",
              r.status_code == 200, f"got {r.status_code} {r.data!r}")
        r = client.get("/api/v1/app-config")
        check("app-config: myket min version back to 0 after delete",
              (r.get_json() or {}).get("min_app_version_code_myket") == 0,
              f"got {r.data!r}")
        _del_setting("min_app_version_code")

        # --- (e) تنظیمات عمومی: کلیدهای ورود مایکت ---
        r = client.get("/api/v1/app/settings")
        j = r.get_json() or {}
        check("public settings: myket login keys present",
              "myket_bazaar_login_disabled" in j
              and "myket_sms_login_disabled" in j,
              f"got {sorted(j.keys())}")

        # --- (f) دروازه‌ی زیبال: شاخه‌ی جدا مایکت ---
        def _zibal(pack_id, store=None, fallback=False):
            body = {"pack_id": pack_id, "fallback": fallback}
            if store is not None:
                body["store"] = store
            return _auth(client, auth, "/coins/purchases/zibal", body=body)

        r = _zibal(pack_bazaar.id, store="myket")
        check("zibal gate: myket + myket_zibal off + bazaar flag on -> 403",
              r.status_code == 403, f"got {r.status_code} {r.data!r}")
        _admin(client, f"/admin/packs/{pack_myket.id}", "PATCH",
               {"myket_zibal_enabled": True})
        r = _zibal(pack_myket.id, store="myket")
        check("zibal gate: myket + myket_zibal on -> 201",
              r.status_code == 201, f"got {r.status_code} {r.data!r}")
        _admin(client, f"/admin/packs/{pack_myket.id}", "PATCH",
               {"myket_zibal_enabled": False})
        r = _zibal(pack_myket.id)
        check("zibal gate: no store + zibal off -> 403 (old check)",
              r.status_code == 403, f"got {r.status_code} {r.data!r}")
        r = _zibal(pack_bazaar.id, store="bazaar")
        check("zibal gate: bazaar + zibal on -> 201 (old path)",
              r.status_code == 201, f"got {r.status_code} {r.data!r}")
        _admin(client, f"/admin/packs/{pack_fallback.id}", "PATCH",
               {"show_fallback_button_myket": True})
        r = _zibal(pack_fallback.id, store="myket", fallback=True)
        check("zibal gate: myket fallback + myket flag on -> 201",
              r.status_code == 201, f"got {r.status_code} {r.data!r}")
        _admin(client, f"/admin/packs/{pack_fallback.id}", "PATCH",
               {"show_fallback_button_myket": False})
        # پاک‌کردن ردیف pending ساخته‌شده تا ۴۰۳ بعدی واقعاً ۴۰۳ باشد
        AppPurchase.query.filter_by(
            user_id=user.id, pack_id=pack_fallback.id, gateway="zibal"
        ).delete()
        db.session.commit()
        r = _zibal(pack_fallback.id, store="myket", fallback=True)
        check("zibal gate: myket fallback + myket flag off -> 403",
              r.status_code == 403, f"got {r.status_code} {r.data!r}")
        _admin(client, f"/admin/packs/{pack_fallback.id}", "PATCH",
               {"show_fallback_button": True})
        r = _zibal(pack_fallback.id, fallback=True)
        check("zibal gate: no store + bazaar fallback on -> 201 (old path)",
              r.status_code == 201, f"got {r.status_code} {r.data!r}")

        # --- (g) ورود با پیامک: چکِ سراسری + شاخه‌ی جدا مایکت ---
        def _otp_req(mobile, store=None):
            body = {"mobile": mobile}
            if store is not None:
                body["store"] = store
            return client.post("/api/v1/auth/otp/request", json=body)

        _set_setting("myket_sms_login_disabled", "1")
        n_sms = len(sms_calls)
        r = _otp_req("09120000010", store="myket")
        check("otp: myket + myket sms off -> 503",
              r.status_code == 503
              and (r.get_json() or {}).get("error") == "sms_disabled",
              f"got {r.status_code} {r.data!r}")
        check("otp: myket sms off -> no OTP row, no sms call",
              AppOtpCode.query.filter_by(mobile="09120000010").count() == 0
              and len(sms_calls) == n_sms)
        _del_setting("myket_sms_login_disabled")
        r = _otp_req("09120000011", store="myket")
        check("otp: myket + myket key absent -> 200",
              r.status_code == 200, f"got {r.status_code} {r.data!r}")
        check("otp: myket enabled -> OTP row + sms call",
              AppOtpCode.query.filter_by(mobile="09120000011").count() == 1
              and len(sms_calls) == n_sms + 1)
        _set_setting("sms_login_disabled", "1")
        r = _otp_req("09120000012")
        check("otp: no store + global off -> 503 (old check)",
              r.status_code == 503, f"got {r.status_code} {r.data!r}")
        r = _otp_req("09120000013", store="bazaar")
        check("otp: bazaar + global off -> 503 (old check)",
              r.status_code == 503, f"got {r.status_code} {r.data!r}")
        r = _otp_req("09120000014", store="myket")
        check("otp: myket + global off -> 503 (global master switch)",
              r.status_code == 503, f"got {r.status_code} {r.data!r}")
        _del_setting("sms_login_disabled")
        _set_setting("myket_sms_login_disabled", "1")
        r = _otp_req("09120000015", store="bazaar")
        check("otp: bazaar + myket key on -> 200 (myket gate is myket-only)",
              r.status_code == 200, f"got {r.status_code} {r.data!r}")
        _del_setting("myket_sms_login_disabled")

        # --- (h) ورود با بازار: تاگل مایکت ---
        def _bazaar_login(account_id, store=None):
            body = {"account_id": account_id}
            if store is not None:
                body["store"] = store
            return client.post("/api/v1/auth/bazaar", json=body)

        _set_setting("myket_bazaar_login_disabled", "1")
        r = _bazaar_login("acct-myket-1", store="myket")
        check("bazaar login: myket + toggle on -> 403",
              r.status_code == 403
              and (r.get_json() or {}).get("error") == "bazaar_login_disabled",
              f"got {r.status_code} {r.data!r}")
        _set_setting("myket_bazaar_login_disabled", "0")
        r = _bazaar_login("acct-myket-2", store="myket")
        check("bazaar login: myket + toggle off -> 200",
              r.status_code == 200, f"got {r.status_code} {r.data!r}")
        _set_setting("myket_bazaar_login_disabled", "1")
        r = _bazaar_login("acct-myket-3")
        check("bazaar login: no store + myket toggle on -> 200 (bazaar path)",
              r.status_code == 200, f"got {r.status_code} {r.data!r}")
        r = _bazaar_login("acct-myket-4", store="bazaar")
        check("bazaar login: bazaar + myket toggle on -> 200",
              r.status_code == 200, f"got {r.status_code} {r.data!r}")
        _del_setting("myket_bazaar_login_disabled")
        r = _bazaar_login("acct-myket-5", store="myket")
        check("bazaar login: myket + key absent -> 403 (default disabled)",
              r.status_code == 403, f"got {r.status_code} {r.data!r}")

    print(f"\n{PASSED} passed, {FAILED} failed")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
