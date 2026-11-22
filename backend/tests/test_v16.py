"""ChehreSaz v16: SMS-disable، store، pending-link، لاگ وریفای، گزارش، انقضا.

اجرا (از ریشه‌ی ریپو):
    python3 backend/tests/test_v16.py

سبک مثل tests/test_myket_verify.py: اسکریپت مستقل، بدون pytest،
با SQLite در حافظه (TestConfig) و assertهای شمارش‌شده.
دو جفت‌کلید RSA یک‌بارمصرف در تست ساخته می‌شود (یکی برای بازار، یکی
برای مایکت)؛ هیچ سرویس خارجی صدا زده نمی‌شود (پیامک mock است).

پوشش:
- (a) ورود با پیامک خاموش → 503 sms_disabled، بدون ردیف OTP و بدون
  تماس پیامکی؛ روشن → مسیر عادی 200.
- (b) ساخت pending و وریفای با pending_id همان ردیف را مصرف می‌کند
  (بدون ردیف تکراری، یک بار شارژ)؛ وریفای بدون pending_id مثل قبل است؛
  pending کاربر دیگر / پک دیگر ۴۰۰ می‌شود.
- (c) store در ورود با بازار و OTP verify ذخیره می‌شود؛ مقدار نامعتبر
  نادیده گرفته می‌شود.
- (d) هر تلاش وریفای (موفق و ناموفق) یک ردیف در app_verify_attempts
  می‌گیرد.
- (e) گزارش عکس + تکراری همان ردیف را برمی‌گرداند + ادمین می‌بیند و
  وضعیت را عوض می‌کند.
- (f) انقضا فقط pendingهای قدیمیِ بدون توکن را حذف می‌کند.
"""

import base64
import datetime
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from werkzeug.security import generate_password_hash  # noqa: E402

from app import create_app  # noqa: E402
from app.config import Config  # noqa: E402
from app.extensions import db  # noqa: E402
from app.models import (  # noqa: E402
    AppCoinPack,
    AppGeneration,
    AppImageReport,
    AppModel,
    AppOtpCode,
    AppPurchase,
    AppSetting,
    AppUser,
    AppVerifyAttempt,
    AppWorld,
)
from app.services import coins as coins_svc  # noqa: E402
from app.services import sms as sms_module  # noqa: E402
from app.services.cleanup import expire_stale_pending_purchases  # noqa: E402

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


# SQLite به BIGINT PRIMARY KEY مقدار خودکار نمی‌دهد؛ مثل تست‌های قبلی
# idها را دستی می‌دهیم. در MySQL واقعی autoincrement کار می‌کند.
_NEXT_ID = [0]


def next_id():
    _NEXT_ID[0] += 1
    return _NEXT_ID[0]


def _make_keypair():
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa

    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    der = private.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return base64.b64encode(der).decode(), private


def _sign(private, purchase_data):
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding

    sig = private.sign(
        purchase_data.encode("utf-8"),
        padding.PKCS1v15(),
        hashes.SHA1(),
    )
    return base64.b64encode(sig).decode()


def _purchase_data(token, sku="pack_test", state=0):
    return json.dumps(
        {
            "orderId": "TEST." + token,
            "packageName": "ir.inourai.newapp",
            "productId": sku,
            "purchaseTime": 1763000000000,
            "purchaseState": state,
            "purchaseToken": token,
            "acknowledged": False,
        },
        separators=(",", ":"),
    )


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


def _verify(client, token, pack_id, purchase_token, purchase_data, signature,
            pending_id=None):
    body = {
        "pack_id": pack_id,
        "purchase_token": purchase_token,
        "purchase_data": purchase_data,
        "signature": signature,
    }
    if pending_id is not None:
        body["pending_id"] = pending_id
    return client.post(
        "/api/v1/coins/purchases/verify",
        json=body,
        headers={"Authorization": f"Bearer {token}"},
    )


def _admin(client, path, method="GET", body=None):
    return client.open(
        "/api/v1" + path,
        method=method,
        json=body,
        headers={"X-Admin-Token": "test-admin-token"},
    )


def main():
    bazaar_pub, bazaar_priv = _make_keypair()
    myket_pub, myket_priv = _make_keypair()
    _, other_priv = _make_keypair()
    TestConfig.BAZAAR_RSA_PUBLIC_KEY = bazaar_pub
    TestConfig.MYKET_RSA_PUBLIC_KEY = myket_pub

    app = create_app(TestConfig)
    with app.app_context():
        from sqlalchemy import BigInteger, Integer

        for _table in db.metadata.tables.values():
            for _col in _table.columns:
                if _col.primary_key and isinstance(_col.type, BigInteger):
                    _col.type = Integer()
        db.create_all()
        client = app.test_client()

        pack = AppCoinPack(
            id=next_id(),
            name="test pack",
            coins=50,
            price_toman=149900,
            bazaar_sku="pack_test",
            is_active=True,
            gift_coins=0,
        )
        pack2 = AppCoinPack(
            id=next_id(),
            name="other pack",
            coins=30,
            price_toman=89900,
            bazaar_sku="pack_other",
            is_active=True,
            gift_coins=0,
        )
        db.session.add_all([pack, pack2])
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

        def balance():
            db.session.expire_all()
            return coins_svc.get_balance(AppUser.query.get(user.id))

        # --- (a) ورود با پیامک خاموش → 503، بدون OTP، بدون تماس پیامکی ---
        sms_calls = []

        def _sms_must_not_run(mobile, code):
            raise AssertionError("send_otp_sms must not be called when disabled")

        sms_module.send_otp_sms = _sms_must_not_run
        db.session.add(AppSetting(key="sms_login_disabled", value="1"))
        db.session.commit()
        r = client.post(
            "/api/v1/auth/otp/request", json={"mobile": "09120000002"}
        )
        check("sms disabled: 503 sms_disabled",
              r.status_code == 503
              and (r.get_json() or {}).get("error") == "sms_disabled",
              f"got {r.status_code} {r.data!r}")
        check("sms disabled: no OTP row created",
              AppOtpCode.query.filter_by(mobile="09120000002").count() == 0)
        # روشن → مسیر عادی (پیامک mock).
        db.session.query(AppSetting).filter_by(
            key="sms_login_disabled"
        ).update({"value": "0"})
        db.session.commit()
        sms_module.send_otp_sms = (
            lambda mobile, code: sms_calls.append((mobile, code)) or True
        )
        r = client.post(
            "/api/v1/auth/otp/request", json={"mobile": "09120000002"}
        )
        check("sms enabled: 200",
              r.status_code == 200, f"got {r.status_code} {r.data!r}")
        check("sms enabled: OTP row created + sms called once",
              AppOtpCode.query.filter_by(mobile="09120000002").count() == 1
              and len(sms_calls) == 1)

        # --- (b) pending + وریفای با pending_id همان ردیف را مصرف می‌کند ---
        r = client.post(
            "/api/v1/coins/purchases",
            json={"pack_id": pack.id},
            headers={"Authorization": f"Bearer {auth}"},
        )
        check("pending create: 201", r.status_code == 201,
              f"got {r.status_code} {r.data!r}")
        pending_id = (r.get_json() or {}).get("id")
        data_p = _purchase_data("tok-v16-pending-1")
        r = _verify(client, auth, pack.id, "tok-v16-pending-1", data_p,
                    _sign(bazaar_priv, data_p), pending_id=pending_id)
        j = r.get_json() or {}
        check("pending verify: 200 consumed, same row id",
              r.status_code == 200 and j.get("status") == "consumed"
              and j.get("id") == pending_id,
              f"got {r.status_code} {j}")
        check("pending verify: coins credited once (50)",
              j.get("coins") == 50 and balance() == 50,
              f"got {j} balance={balance()}")
        check("pending verify: no duplicate purchase row",
              AppPurchase.query.filter_by(user_id=user.id).count() == 1)
        check("pending verify: token attached to the pending row",
              AppPurchase.query.get(pending_id).bazaar_purchase_token
              == "tok-v16-pending-1")
        # تکرار همان توکن بدون pending_id: idempotent مثل قبل.
        r = _verify(client, auth, pack.id, "tok-v16-pending-1", data_p,
                    _sign(bazaar_priv, data_p))
        j = r.get_json() or {}
        check("pending replay: already_credited, balance still 50",
              r.status_code == 200 and j.get("already_credited") is True
              and balance() == 50, f"got {r.status_code} {j}")
        # وریفای بدون pending_id مثل قبل: ردیف تازه می‌سازد.
        data_n = _purchase_data("tok-v16-nopending-1")
        r = _verify(client, auth, pack.id, "tok-v16-nopending-1", data_n,
                    _sign(bazaar_priv, data_n))
        j = r.get_json() or {}
        check("no-pending verify: 200 consumed (old behavior)",
              r.status_code == 200 and j.get("status") == "consumed",
              f"got {r.status_code} {j}")
        check("no-pending verify: balance 100",
              balance() == 100, f"balance={balance()}")
        # pending پک دیگر با همین پک → ۴۰۰.
        r = client.post(
            "/api/v1/coins/purchases",
            json={"pack_id": pack2.id},
            headers={"Authorization": f"Bearer {auth}"},
        )
        other_pack_pending = (r.get_json() or {}).get("id")
        data_x = _purchase_data("tok-v16-wrongpack")
        r = _verify(client, auth, pack.id, "tok-v16-wrongpack", data_x,
                    _sign(bazaar_priv, data_x),
                    pending_id=other_pack_pending)
        check("pending wrong pack: 400 pack mismatch",
              r.status_code == 400
              and (r.get_json() or {}).get("error")
              == "pending purchase pack mismatch",
              f"got {r.status_code} {r.data!r}")
        check("pending wrong pack: no credit", balance() == 100)
        # pending کاربر دیگر → ۴۰۰.
        user2 = AppUser(
            id=next_id(),
            mobile="09120000003",
            free_image_credits=1,
            free_voice_credits=0,
        )
        db.session.add(user2)
        db.session.commit()
        auth2 = _otp_login(client, "09120000003")
        r = client.post(
            "/api/v1/coins/purchases",
            json={"pack_id": pack.id},
            headers={"Authorization": f"Bearer {auth2}"},
        )
        others_pending = (r.get_json() or {}).get("id")
        data_y = _purchase_data("tok-v16-otheruser")
        r = _verify(client, auth, pack.id, "tok-v16-otheruser", data_y,
                    _sign(bazaar_priv, data_y),
                    pending_id=others_pending)
        check("pending other user: 400 belongs to another user",
              r.status_code == 400
              and (r.get_json() or {}).get("error")
              == "pending purchase belongs to another user",
              f"got {r.status_code} {r.data!r}")
        check("pending other user: no credit", balance() == 100)

        # --- (c) store در لاگین ذخیره می‌شود؛ نامعتبر نادیده گرفته می‌شود ---
        r = client.post(
            "/api/v1/auth/bazaar",
            json={"account_id": "bazaar-acct-1", "store": "myket"},
        )
        check("bazaar login with store: 200",
              r.status_code == 200, f"got {r.status_code} {r.data!r}")
        u_b = AppUser.query.filter_by(
            bazaar_account_id="bazaar-acct-1"
        ).first()
        check("bazaar login: store myket stored",
              u_b is not None and u_b.store == "myket",
              f"got {u_b.store if u_b else None}")
        r = client.post(
            "/api/v1/auth/bazaar",
            json={"account_id": "bazaar-acct-1", "store": "bazaar"},
        )
        db.session.expire_all()
        u_b = AppUser.query.filter_by(
            bazaar_account_id="bazaar-acct-1"
        ).first()
        check("bazaar re-login: store updated to bazaar",
              u_b.store == "bazaar", f"got {u_b.store}")
        r = client.post(
            "/api/v1/auth/bazaar",
            json={"account_id": "bazaar-acct-1", "store": "foo"},
        )
        db.session.expire_all()
        u_b = AppUser.query.filter_by(
            bazaar_account_id="bazaar-acct-1"
        ).first()
        check("bazaar login: invalid store ignored",
              u_b.store == "bazaar", f"got {u_b.store}")
        auth3 = _otp_login(
            client, "09120000004", extra={"store": "myket"}
        )
        u3 = AppUser.query.filter_by(mobile="09120000004").first()
        check("otp verify: store myket stored on new user",
              u3 is not None and u3.store == "myket",
              f"got {u3.store if u3 else None}")
        auth4 = _otp_login(
            client, "09120000005", extra={"store": "not-a-store"}
        )
        u4 = AppUser.query.filter_by(mobile="09120000005").first()
        check("otp verify: invalid store ignored (None)",
              u4 is not None and u4.store is None,
              f"got {u4.store if u4 else None}")
        # فیلتر ادمین ?store=
        r = _admin(client, "/admin/users?store=myket")
        j = r.get_json() or {}
        ids = [u["id"] for u in j.get("items", [])]
        check("admin users ?store=myket filters",
              r.status_code == 200 and u3.id in ids and u_b.id not in ids,
              f"got {r.status_code} {ids}")
        r = _admin(client, "/admin/users?store=bazaar")
        j = r.get_json() or {}
        ids = [u["id"] for u in j.get("items", [])]
        check("admin users ?store=bazaar filters",
              r.status_code == 200 and u_b.id in ids and u3.id not in ids,
              f"got {r.status_code} {ids}")

        # --- (d) لاگ تلاش وریفای: موفق و ناموفق ---
        ok_rows = AppVerifyAttempt.query.filter_by(
            purchase_token="tok-v16-pending-1", success=True,
            pending_id=pending_id,
        ).all()
        check("verify log: success row for pending verify",
              len(ok_rows) == 1 and ok_rows[0].store == "bazaar",
              f"got {[(a.store, a.pending_id) for a in ok_rows]}")
        data_bad = _purchase_data("tok-v16-badsig")
        r = _verify(client, auth, pack.id, "tok-v16-badsig", data_bad,
                    _sign(other_priv, data_bad))
        check("bad signature: 400 invalid myket signature",
              r.status_code == 400
              and (r.get_json() or {}).get("error")
              == "invalid myket signature",
              f"got {r.status_code} {r.data!r}")
        bad_rows = AppVerifyAttempt.query.filter_by(
            purchase_token="tok-v16-badsig"
        ).all()
        check("verify log: failure row logged",
              len(bad_rows) == 1 and bad_rows[0].success is False
              and bad_rows[0].error == "invalid myket signature"
              and bad_rows[0].store == "myket",
              f"got {[(a.success, a.error, a.store) for a in bad_rows]}")
        r = _admin(client, "/admin/verify-attempts")
        j = r.get_json() or {}
        check("admin verify-attempts: lists attempts",
              r.status_code == 200 and j.get("total", 0) >= 2,
              f"got {r.status_code} total={j.get('total')}")

        # --- (e) گزارش عکس + تکراری + ادمین ---
        model = AppModel(
            id=next_id(),
            kind="image",
            name="m1",
            slug="m1",
            provider="test",
            capability="edit_image",
            is_active=True,
        )
        world = AppWorld(
            id=next_id(),
            name="w1",
            slug="w1",
            hidden_prompt="p",
            model_id=model.id,
            coin_price=10,
            is_active=True,
        )
        db.session.add_all([model, world])
        db.session.commit()
        gen = AppGeneration(
            id=next_id(),
            user_id=user.id,
            kind="image",
            world_id=world.id,
            model_id=model.id,
            output_url="https://example.invalid/out.jpg",
            status="done",
            coin_cost=10,
        )
        db.session.add(gen)
        db.session.commit()
        r = client.post(
            "/api/v1/gallery/reports",
            json={"generation_id": gen.id, "reason": "نامناسب"},
            headers={"Authorization": f"Bearer {auth}"},
        )
        j = r.get_json() or {}
        check("report create: 201 pending",
              r.status_code == 201 and j.get("status") == "pending"
              and j.get("id"),
              f"got {r.status_code} {j}")
        rep_id = j.get("id")
        r = client.post(
            "/api/v1/gallery/reports",
            json={"generation_id": gen.id, "reason": "دوباره"},
            headers={"Authorization": f"Bearer {auth}"},
        )
        j2 = r.get_json() or {}
        check("report duplicate: 200 same row, no duplicate",
              r.status_code == 200 and j2.get("id") == rep_id
              and j2.get("duplicate") is True
              and AppImageReport.query.filter_by(
                  generation_id=gen.id
              ).count() == 1,
              f"got {r.status_code} {j2}")
        r = client.post(
            "/api/v1/gallery/reports",
            json={"generation_id": 999999},
            headers={"Authorization": f"Bearer {auth}"},
        )
        check("report unknown generation: 404",
              r.status_code == 404, f"got {r.status_code} {r.data!r}")
        r = _admin(client, "/admin/reports")
        j = r.get_json() or {}
        items = j.get("items", [])
        check("admin reports: lists it with image_url + reporter",
              r.status_code == 200 and len(items) == 1
              and items[0]["image_url"] == "https://example.invalid/out.jpg"
              and items[0]["user_id"] == user.id,
              f"got {r.status_code} {items}")
        r = _admin(client, f"/admin/reports/{rep_id}", "PATCH",
                   {"status": "reviewed"})
        check("admin report patch reviewed: 200",
              r.status_code == 200
              and (r.get_json() or {}).get("status") == "reviewed",
              f"got {r.status_code} {r.data!r}")
        r = _admin(client, f"/admin/reports/{rep_id}", "PATCH",
                   {"status": "pending"})
        check("admin report patch invalid status: 400",
              r.status_code == 400, f"got {r.status_code} {r.data!r}")

        # --- (f) انقضا فقط pendingهای قدیمیِ بدون توکن را حذف می‌کند ---
        old = datetime.datetime.utcnow() - datetime.timedelta(hours=1)
        now = datetime.datetime.utcnow()
        p_old_notok = AppPurchase(
            id=next_id(), user_id=user.id, pack_id=pack.id,
            bazaar_purchase_token=None, amount_irr=1499000,
            status="pending", created_at=old,
        )
        p_old_tok = AppPurchase(
            id=next_id(), user_id=user.id, pack_id=pack.id,
            bazaar_purchase_token="tok-v16-kept", amount_irr=1499000,
            status="pending", created_at=old,
        )
        p_fresh_notok = AppPurchase(
            id=next_id(), user_id=user.id, pack_id=pack.id,
            bazaar_purchase_token=None, amount_irr=1499000,
            status="pending", created_at=now,
        )
        db.session.add_all([p_old_notok, p_old_tok, p_fresh_notok])
        db.session.commit()
        r = _admin(client, "/admin/purchases/stale")
        j = r.get_json() or {}
        stale_ids = [i["id"] for i in j.get("items", [])]
        check("admin stale: lists both old pendings",
              r.status_code == 200
              and p_old_notok.id in stale_ids
              and p_old_tok.id in stale_ids
              and p_fresh_notok.id not in stale_ids,
              f"got {r.status_code} {stale_ids}")
        deleted = expire_stale_pending_purchases()
        check("expiry: deletes exactly 1 row",
              deleted == 1, f"got {deleted}")
        # حذف منطقی (۲۰۲۶-۱۱-۲۲): ردیف می‌ماند، فقط deleted_at می‌خورد.
        _expired = AppPurchase.query.get(p_old_notok.id)
        check("expiry: old token-less soft-deleted",
              _expired is not None and _expired.deleted_at is not None)
        check("expiry: old WITH token kept",
              AppPurchase.query.get(p_old_tok.id) is not None)
        check("expiry: fresh token-less kept",
              AppPurchase.query.get(p_fresh_notok.id) is not None)

    print(f"\n{PASSED} passed, {FAILED} failed")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
