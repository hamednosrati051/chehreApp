"""حذف منطقی پرداخت‌ها (۲۰۲۶-۱۱-۲۲):

- انقضا و حذف گروهی ادمین ردیف را علامت می‌زنند (deleted_at)، نه DELETE.
- لیست‌های کاربر/ادمین و totals ردیف حذف‌شده را نمی‌بینند؛
  مودال GET /admin/purchases/deleted آن‌ها را نشان می‌دهد و با توکن
  (بازار یا trackId زیبال) قابل جست‌وجوست.
- وریفای با همان توکن (یا pending_id) ردیف حذف‌شده را برمی‌گرداند و
  دقیقاً یک بار شارژ می‌کند؛ وریفای ناموفق آن را برنمی‌گرداند.

اجرا (روی سرور):
    /opt/new-app/venv/bin/python tests/test_soft_delete_purchases.py

فقط دیتابیسِ موقتِ SQLite؛ هیچ فایلی ساخته یا پاک نمی‌شود و پوشه‌ی
واقعی static/uploads دست نمی‌خورد.
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
from app.models import AppCoinPack, AppOtpCode, AppPurchase, AppUser  # noqa: E402
from app.services import coins as coins_svc  # noqa: E402
from app.services import zibal as zibal_svc  # noqa: E402
from app.services.cleanup import (  # noqa: E402
    expire_stale_pending_purchases,
    reconcile_zibal_purchases,
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


def _purchase_data(token, sku="test-pack", state=0):
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
    ADMIN_TOKEN = "tc1"


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
        admin_h = {"X-Admin-Token": app.config.get("ADMIN_TOKEN") or ""}

        pack = AppCoinPack(
            id=next_id(), name="test pack", coins=20,
            price_toman=59900, bazaar_sku="test-pack",
            is_active=True, gift_coins=0,
        )
        db.session.add(pack)
        db.session.commit()

        user = AppUser(
            id=next_id(), mobile="09120000001",
            free_image_credits=0, free_voice_credits=0,
        )
        db.session.add(user)
        db.session.commit()
        auth = _otp_login(client, "09120000001")

        def balance():
            db.session.expire_all()
            return coins_svc.get_balance(AppUser.query.get(user.id))

        def purchase(pid, status="pending", gateway="bazaar", token=None,
                     track=None, minutes_ago=0, amount=599000):
            row = AppPurchase(
                id=pid, user_id=user.id, pack_id=pack.id,
                bazaar_purchase_token=token, zibal_track_id=track,
                gateway=gateway, amount_irr=amount, status=status,
                created_at=datetime.datetime.utcnow()
                - datetime.timedelta(minutes=minutes_ago),
            )
            db.session.add(row)
            db.session.commit()
            return row

        now = datetime.datetime.utcnow()

        # --- ۱) انقضا علامت می‌زند، پاک نمی‌کند ---
        old_id = next_id()
        purchase(old_id, minutes_ago=40)
        fresh_id = next_id()
        purchase(fresh_id, minutes_ago=5)
        n = expire_stale_pending_purchases()
        db.session.expire_all()
        old = AppPurchase.query.get(old_id)
        fresh = AppPurchase.query.get(fresh_id)
        check("expire: old tokenless pending is marked, not deleted",
              n == 1 and old is not None and old.deleted_at is not None,
              f"n={n} old={old}")
        check("expire: fresh pending untouched",
              fresh is not None and fresh.deleted_at is None)
        check("expire: second run finds nothing for the same row",
              expire_stale_pending_purchases() == 0)

        # --- ۲) حذف گروهی ادمین علامت می‌زند؛ مصرف‌شده رد می‌شود ---
        failed_id = next_id()
        purchase(failed_id, status="failed", token="tok-failed-1")
        zibal_id = next_id()
        purchase(zibal_id, status="failed", gateway="zibal",
                 track="TRACK-DEL-1", minutes_ago=120)
        consumed_id = next_id()
        purchase(consumed_id, status="consumed", token="tok-consumed-1")
        r = client.delete(
            "/api/v1/admin/purchases/bulk",
            json={"ids": [failed_id, zibal_id, consumed_id]},
            headers=admin_h,
        )
        j = r.get_json() or {}
        db.session.expire_all()
        failed = AppPurchase.query.get(failed_id)
        zibal_row = AppPurchase.query.get(zibal_id)
        consumed = AppPurchase.query.get(consumed_id)
        check("bulk delete: marks 2, skips consumed",
              r.status_code == 200 and j.get("deleted") == 2
              and len(j.get("skipped") or []) == 1,
              f"got {r.status_code} {j}")
        check("bulk delete: rows still exist with deleted_at",
              failed is not None and failed.deleted_at is not None
              and zibal_row is not None and zibal_row.deleted_at is not None)
        check("bulk delete: consumed row untouched",
              consumed is not None and consumed.deleted_at is None)

        # --- ۳) لیست‌ها و totals ردیف حذف‌شده را نمی‌بینند ---
        r = client.get("/api/v1/coins/purchases/pending",
                       headers={"Authorization": f"Bearer {auth}"})
        pending_ids = [x["id"] for x in (r.get_json() or [])]
        check("user pending list hides soft-deleted",
              old_id not in pending_ids and fresh_id in pending_ids,
              f"got {pending_ids}")
        r = client.get("/api/v1/coins/purchases/mine",
                       headers={"Authorization": f"Bearer {auth}"})
        mine_ids = [x["id"] for x in (r.get_json() or [])]
        check("user mine list hides soft-deleted",
              old_id not in mine_ids and fresh_id in mine_ids)
        r = client.get("/api/v1/admin/purchases", headers=admin_h)
        j = r.get_json() or {}
        admin_ids = [x["id"] for x in j.get("items", [])]
        totals = j.get("totals") or {}
        live = AppPurchase.query.filter(
            AppPurchase.deleted_at.is_(None)).count()
        check("admin list hides soft-deleted",
              old_id not in admin_ids and failed_id not in admin_ids
              and fresh_id in admin_ids, f"got {admin_ids}")
        check("admin totals exclude soft-deleted",
              totals.get("count") == live,
              f"got {totals} live={live}")

        # --- ۴) مودال حذف‌شده‌ها + جست‌وجو با توکن ---
        r = client.get("/api/v1/admin/purchases/deleted", headers=admin_h)
        j = r.get_json() or {}
        del_ids = [x["id"] for x in j.get("items", [])]
        check("deleted endpoint lists soft-deleted with deleted_at",
              set(del_ids) == {old_id, failed_id, zibal_id}
              and all(x.get("deleted_at") for x in j.get("items", [])),
              f"got {del_ids}")
        r = client.get("/api/v1/admin/purchases/deleted?q=tok-failed-1",
                       headers=admin_h)
        j = r.get_json() or {}
        check("deleted search by bazaar token",
              [x["id"] for x in j.get("items", [])] == [failed_id],
              f"got {j}")
        r = client.get("/api/v1/admin/purchases/deleted?q=TRACK-DEL-1",
                       headers=admin_h)
        j = r.get_json() or {}
        check("deleted search by zibal track id",
              [x["id"] for x in j.get("items", [])] == [zibal_id],
              f"got {j}")
        r = client.get("/api/v1/admin/purchases/deleted?q=zzz-no-match",
                       headers=admin_h)
        check("deleted search no match -> empty",
              (r.get_json() or {}).get("items") == [])
        r = client.get("/api/v1/admin/purchases/deleted")
        check("deleted endpoint requires admin",
              r.status_code in (401, 403), f"got {r.status_code}")

        # --- ۵) وریفای با همان توکن ردیف حذف‌شده را برمی‌گرداند ---
        tok_id = next_id()
        purchase(tok_id, token="tok-restore-1")
        _row = AppPurchase.query.get(tok_id)
        _row.deleted_at = now
        db.session.commit()
        before = balance()
        data = _purchase_data("tok-restore-1")
        r = _verify(client, auth, pack.id, "tok-restore-1", data,
                    _sign(bazaar_priv, data))
        db.session.expire_all()
        restored = AppPurchase.query.get(tok_id)
        check("verify restores soft-deleted row by token",
              r.status_code == 200 and restored is not None
              and restored.status == "consumed"
              and restored.deleted_at is None,
              f"got {r.status_code} {r.data!r}")
        check("verify restores: single credit, no duplicate row",
              balance() == before + pack.coins
              and AppPurchase.query.filter_by(
                  bazaar_purchase_token="tok-restore-1").count() == 1,
              f"balance={balance()} before={before}")
        r = _verify(client, auth, pack.id, "tok-restore-1", data,
                    _sign(bazaar_priv, data))
        check("verify again: already credited, no double credit",
              r.status_code == 200 and balance() == before + pack.coins,
              f"got {r.status_code} {r.data!r} balance={balance()}")

        # --- ۶) وریفای با pending_id ردیف حذف‌شده را برمی‌گرداند ---
        pend_id = next_id()
        purchase(pend_id, minutes_ago=40)
        _row = AppPurchase.query.get(pend_id)
        _row.deleted_at = now
        db.session.commit()
        before = balance()
        data2 = _purchase_data("tok-restore-2")
        r = _verify(client, auth, pack.id, "tok-restore-2", data2,
                    _sign(bazaar_priv, data2), pending_id=pend_id)
        db.session.expire_all()
        restored2 = AppPurchase.query.get(pend_id)
        check("verify with pending_id restores soft-deleted pending",
              r.status_code == 200 and restored2 is not None
              and restored2.status == "consumed"
              and restored2.deleted_at is None
              and restored2.bazaar_purchase_token == "tok-restore-2",
              f"got {r.status_code} {r.data!r}")
        check("verify with pending_id: single credit",
              balance() == before + pack.coins,
              f"balance={balance()} before={before}")

        # --- ۷) وریفای ناموفق ردیف حذف‌شده را برنمی‌گرداند ---
        bad_id = next_id()
        purchase(bad_id, token="tok-bad-1")
        _row = AppPurchase.query.get(bad_id)
        _row.deleted_at = now
        db.session.commit()
        before = balance()
        data3 = _purchase_data("tok-bad-1")
        r = _verify(client, auth, pack.id, "tok-bad-1", data3,
                    _sign(other_priv, data3))
        db.session.expire_all()
        bad = AppPurchase.query.get(bad_id)
        check("failed verify does not restore soft-deleted row",
              r.status_code == 400 and bad is not None
              and bad.deleted_at is not None and bad.status == "pending",
              f"got {r.status_code} {r.data!r}")
        check("failed verify: no credit",
              balance() == before, f"balance={balance()} before={before}")

        # --- ۸) تسویه‌ی زیبال ردیف حذف‌شده را برمی‌گرداند ---
        zb_id = next_id()
        purchase(zb_id, status="pending", gateway="zibal",
                 track="TRACK-DEL-2", minutes_ago=120)
        _row = AppPurchase.query.get(zb_id)
        _row.deleted_at = now
        db.session.commit()
        before = balance()
        zb = AppPurchase.query.get(zb_id)
        _, already, _gift = zibal_svc.settle_purchase(user, pack, zb)
        db.session.expire_all()
        zb = AppPurchase.query.get(zb_id)
        check("zibal settle restores soft-deleted row",
              already is False and zb.status == "consumed"
              and zb.deleted_at is None,
              f"already={already} status={zb.status}")
        check("zibal settle: single credit",
              balance() == before + pack.coins,
              f"balance={balance()} before={before}")
        _, already2, _ = zibal_svc.settle_purchase(user, pack, zb)
        check("zibal settle again: already credited",
              already2 is True and balance() == before + pack.coins)

        # --- ۹) مایکت: وریفای با امضای مایکت ردیف حذف‌شده را برمی‌گرداند ---
        # مایکت همان ستون bazaar_purchase_token را دارد (ستون جداگانه‌ای
        # برای توکن مایکت نیست)؛ مسیر HTTP اول بازار را امتحان می‌کند و
        # با «invalid bazaar signature» به مایکت می‌افتد.
        mk_id = next_id()
        purchase(mk_id, token="tok-myket-1")
        _row = AppPurchase.query.get(mk_id)
        _row.deleted_at = now
        db.session.commit()
        before = balance()
        data_m = _purchase_data("tok-myket-1")
        r = _verify(client, auth, pack.id, "tok-myket-1", data_m,
                    _sign(myket_priv, data_m))
        db.session.expire_all()
        mk = AppPurchase.query.get(mk_id)
        check("myket verify restores soft-deleted row by token",
              r.status_code == 200 and mk is not None
              and mk.status == "consumed" and mk.deleted_at is None,
              f"got {r.status_code} {r.data!r}")
        check("myket verify: single credit, no duplicate row",
              balance() == before + pack.coins
              and AppPurchase.query.filter_by(
                  bazaar_purchase_token="tok-myket-1").count() == 1,
              f"balance={balance()} before={before}")
        r = _verify(client, auth, pack.id, "tok-myket-1", data_m,
                    _sign(myket_priv, data_m))
        check("myket verify again: already credited, no double credit",
              r.status_code == 200 and balance() == before + pack.coins,
              f"got {r.status_code} {r.data!r} balance={balance()}")

        # --- ۱۰) زیبال: کالبکِ دیرهنگام + ریچک ردیف حذف‌شده را برمی‌گردانند ---
        # شبیه‌سازی باگ ۷۵۴: ردیف بعد از انقضای ۱ ساعته حذف منطقی شده و
        # کالبک دیر می‌رسد. verify_payment را mock می‌کنیم (بدون شبکه).
        orig_verify = zibal_svc.verify_payment
        try:
            zibal_svc.verify_payment = (
                lambda track_id, amount: {"paid": True}
            )
            late_id = next_id()
            purchase(late_id, status="pending", gateway="zibal",
                     track="TRACK-LATE-1", minutes_ago=120)
            _row = AppPurchase.query.get(late_id)
            _row.deleted_at = now
            db.session.commit()
            before = balance()
            r = client.post(
                "/api/v1/coins/purchases/zibal/callback"
                "?trackId=TRACK-LATE-1"
            )
            db.session.expire_all()
            late = AppPurchase.query.get(late_id)
            check("zibal late callback restores soft-deleted row",
                  r.status_code == 200 and late is not None
                  and late.status == "consumed" and late.deleted_at is None,
                  f"got {r.status_code}")
            check("zibal late callback: single credit",
                  balance() == before + pack.coins,
                  f"balance={balance()} before={before}")

            late2_id = next_id()
            purchase(late2_id, status="pending", gateway="zibal",
                     track="TRACK-LATE-2", minutes_ago=120)
            _row = AppPurchase.query.get(late2_id)
            _row.deleted_at = now
            db.session.commit()
            before = balance()
            r = client.post(
                f"/api/v1/coins/purchases/{late2_id}/zibal/recheck",
                headers={"Authorization": f"Bearer {auth}"},
            )
            db.session.expire_all()
            late2 = AppPurchase.query.get(late2_id)
            check("zibal recheck restores soft-deleted row",
                  r.status_code == 200 and late2 is not None
                  and late2.status == "consumed"
                  and late2.deleted_at is None,
                  f"got {r.status_code} {r.data!r}")
            check("zibal recheck: single credit",
                  balance() == before + pack.coins,
                  f"balance={balance()} before={before}")
        finally:
            zibal_svc.verify_payment = orig_verify

        # --- ۱۱) جارو ردیف‌های حذف‌شده‌ی زیبال را هم چک می‌کند ---
        seen = []

        def _mock_verify(track_id, amount):
            seen.append(track_id)
            return {"paid": track_id == "TRACK-SWEEP-1"}

        orig_verify = zibal_svc.verify_payment
        try:
            zibal_svc.verify_payment = _mock_verify
            sw1_id = next_id()
            purchase(sw1_id, status="pending", gateway="zibal",
                     track="TRACK-SWEEP-1", minutes_ago=120)
            sw2_id = next_id()
            purchase(sw2_id, status="failed", gateway="zibal",
                     track="TRACK-SWEEP-2", minutes_ago=120)
            old_id2 = next_id()
            purchase(old_id2, status="pending", gateway="zibal",
                     track="TRACK-OLD-1", minutes_ago=60 * 30)
            for _id in (sw1_id, sw2_id, old_id2):
                _row = AppPurchase.query.get(_id)
                _row.deleted_at = now
            db.session.commit()
            before = balance()
            checked, settled = reconcile_zibal_purchases()
            db.session.expire_all()
            sw1 = AppPurchase.query.get(sw1_id)
            sw2 = AppPurchase.query.get(sw2_id)
            old2 = AppPurchase.query.get(old_id2)
            check("sweep settles soft-deleted paid row and restores it",
                  settled == 1 and sw1 is not None
                  and sw1.status == "consumed" and sw1.deleted_at is None,
                  f"checked={checked} settled={settled}")
            check("sweep: single credit for the restored row",
                  balance() == before + pack.coins,
                  f"balance={balance()} before={before}")
            check("sweep: unpaid soft-deleted row stays deleted, no credit",
                  sw2 is not None and sw2.deleted_at is not None
                  and sw2.status == "failed")
            check("sweep: row older than 24h lookback is not checked",
                  "TRACK-OLD-1" not in seen
                  and old2 is not None and old2.deleted_at is not None,
                  f"seen={seen}")
        finally:
            zibal_svc.verify_payment = orig_verify

    print(f"\n{PASSED} passed, {FAILED} failed")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
