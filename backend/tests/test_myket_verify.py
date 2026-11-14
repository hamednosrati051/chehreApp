"""ChehreSaz: وریفای خرید مایکت در کنار بازار (هر دو استور، یک endpoint).

اجرا (از ریشه‌ی ریپو):
    python3 backend/tests/test_myket_verify.py

سبک مثل tests/test_v14_in_gallery.py: اسکریپت مستقل، بدون pytest،
با SQLite در حافظه (TestConfig) و assertهای شمارش‌شده.
دو جفت‌کلید RSA یک‌بارمصرف در تست ساخته می‌شود (یکی برای بازار، یکی
برای مایکت)؛ کلید عمومی base64 DER در کانفیگ ست می‌شود — همان
فرمتی که پنل بازار/مایکت می‌دهد. هیچ سرویس خارجی صدا زده نمی‌شود.

پوشش:
- خرید با امضای بازار وریفای می‌شود (رگرسیون: رفتار بازار عوض نشده).
- خرید با امضای مایکت از همان endpoint وریفای می‌شود (fallback).
- تکرار همان توکن دوباره شارژ نمی‌کند (idempotency، هر دو استور).
- امضای کلید ناشناس رد می‌شود (هر دو کلید نمی‌خوانند → ۴۰۰).
- خرید ناتمام (purchaseState!=0) و SKU ناهمخوان، بدون fallback
  به مایکت، ۴۰۰ می‌شوند.
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
from app.models import AppCoinPack, AppOtpCode, AppUser  # noqa: E402
from app.services import coins as coins_svc  # noqa: E402

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
    """جفت‌کلید RSA یک‌بارمصرف؛ برمی‌گرداند (کلید عمومی base64 DER،
    کلید خصوصی برای امضا)."""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa

    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    der = private.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return base64.b64encode(der).decode(), private


def _sign(private, purchase_data):
    """امضای IAB: RSA/PKCS1v15 + SHA1 روی همان رشته‌ی JSON."""
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
    r = client.post(
        "/api/v1/auth/otp/verify", json={"mobile": mobile, "code": code}
    )
    assert r.status_code == 200, f"otp verify failed: {r.status_code} {r.data!r}"
    return r.get_json()["token"]


def _verify(client, token, pack_id, purchase_token, purchase_data, signature):
    return client.post(
        "/api/v1/coins/purchases/verify",
        json={
            "pack_id": pack_id,
            "purchase_token": purchase_token,
            "purchase_data": purchase_data,
            "signature": signature,
        },
        headers={"Authorization": f"Bearer {token}"},
    )


def main():
    # دو جفت‌کلید جدا: بازار و مایکت (و یکی ناشناس برای تست منفی).
    bazaar_pub, bazaar_priv = _make_keypair()
    myket_pub, myket_priv = _make_keypair()
    _, other_priv = _make_keypair()
    TestConfig.BAZAAR_RSA_PUBLIC_KEY = bazaar_pub
    TestConfig.MYKET_RSA_PUBLIC_KEY = myket_pub

    app = create_app(TestConfig)
    with app.app_context():
        # SQLite به BIGINT PRIMARY KEY مقدار خودکار نمی‌دهد؛ فقط برای
        # همین تستِ درون‌حافظه‌ای، نوع همه‌ی ستون‌های کلید اصلی را
        # INTEGER می‌کنیم تا INSERTها (خرید، دفتر سکه و...) مثل MySQL
        # واقعی مقدار بدهند. مدل واقعی (backend/app/models.py) دست
        # نخورده است.
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
        db.session.add(pack)
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

        # --- ۱) خرید بازار: امضای بازار وریفای می‌شود (رگرسیون) ---
        data_b = _purchase_data("tok-bazaar-1")
        r = _verify(client, auth, pack.id, "tok-bazaar-1", data_b,
                    _sign(bazaar_priv, data_b))
        check("bazaar: 200", r.status_code == 200, f"got {r.status_code} {r.data!r}")
        j = r.get_json() or {}
        check("bazaar: consumed", j.get("status") == "consumed", f"got {j}")
        check("bazaar: coins credited (50)",
              j.get("coins") == 50 and balance() == 50, f"got {j} balance={balance()}")
        check("bazaar: not already_credited", j.get("already_credited") is False)

        # --- ۲) همان توکن بازار دوباره: شارژ مجدد ممنوع ---
        r = _verify(client, auth, pack.id, "tok-bazaar-1", data_b,
                    _sign(bazaar_priv, data_b))
        j = r.get_json() or {}
        check("bazaar replay: 200 + already_credited",
              r.status_code == 200 and j.get("already_credited") is True,
              f"got {r.status_code} {j}")
        check("bazaar replay: balance still 50", balance() == 50)

        # --- ۳) خرید مایکت: امضای مایکت از همان endpoint وریفای می‌شود ---
        data_m = _purchase_data("tok-myket-1")
        r = _verify(client, auth, pack.id, "tok-myket-1", data_m,
                    _sign(myket_priv, data_m))
        j = r.get_json() or {}
        check("myket: 200", r.status_code == 200, f"got {r.status_code} {r.data!r}")
        check("myket: consumed", j.get("status") == "consumed", f"got {j}")
        check("myket: coins credited (balance 100)",
              j.get("coins") == 50 and balance() == 100, f"got {j} balance={balance()}")

        # --- ۴) همان توکن مایکت دوباره: شارژ مجدد ممنوع ---
        r = _verify(client, auth, pack.id, "tok-myket-1", data_m,
                    _sign(myket_priv, data_m))
        j = r.get_json() or {}
        check("myket replay: already_credited",
              r.status_code == 200 and j.get("already_credited") is True,
              f"got {r.status_code} {j}")
        check("myket replay: balance still 100", balance() == 100)

        # --- ۵) امضای کلید ناشناس: هر دو کلید نمی‌خوانند → ۴۰۰ ---
        data_x = _purchase_data("tok-unknown-1")
        r = _verify(client, auth, pack.id, "tok-unknown-1", data_x,
                    _sign(other_priv, data_x))
        j = r.get_json() or {}
        check("unknown key: 400 invalid myket signature",
              r.status_code == 400 and j.get("error") == "invalid myket signature",
              f"got {r.status_code} {j}")
        check("unknown key: no credit", balance() == 100)

        # --- ۶) خرید مایکت ناتمام: بدون fallback، ۴۰۰ ---
        data_c = _purchase_data("tok-myket-cancelled", state=1)
        r = _verify(client, auth, pack.id, "tok-myket-cancelled", data_c,
                    _sign(myket_priv, data_c))
        j = r.get_json() or {}
        check("myket cancelled: 400 purchase not completed",
              r.status_code == 400 and j.get("error") == "purchase not completed",
              f"got {r.status_code} {j}")
        check("myket cancelled: no credit", balance() == 100)

        # --- ۷) SKU ناهمخوان با امضای معتبر مایکت: بدون fallback، ۴۰۰ ---
        data_s = _purchase_data("tok-myket-wrongsku", sku="pack_other")
        r = _verify(client, auth, pack.id, "tok-myket-wrongsku", data_s,
                    _sign(myket_priv, data_s))
        j = r.get_json() or {}
        check("myket wrong sku: 400 purchase sku does not match pack",
              r.status_code == 400
              and j.get("error") == "purchase sku does not match pack",
              f"got {r.status_code} {j}")
        check("myket wrong sku: no credit", balance() == 100)

    print(f"\n{PASSED} passed, {FAILED} failed")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
