"""ChehreSaz v17: زیبال + پرچم‌های پک + myket_url + انقضای gateway-aware.

اجرا (از ریشه‌ی ریپو):
    python3 backend/tests/test_v17.py

سبک مثل tests/test_v16.py: اسکریپت مستقل، بدون pytest،
با SQLite در حافظه (TestConfig) و assertهای شمارش‌شده.
هیچ سرویس خارجی صدا زده نمی‌شود: فراخوان‌های زیبال mock است
(_post در app.services.zibal جایگزین می‌شود).

پوشش:
- (a) پرچم‌های پک در GET /coins/packs + PATCH ادمین.
- (b) ساخت زیبال: بدون مرچنت → ۵۰۳؛ پک با zibal_enabled=false → ۴۰۳؛
  با مرچنت + پرچم روشن → ۲۰۱ با آدرس bounce؛ تکرار همان پک → همان
  ردیف (بدون درخواست دوم به زیبال).
- (c) کالبک: پرداخت‌شده → consumed + سکه + هدیه؛ تکرار کالبک →
  بدون شارژ دوباره؛ پرداخت‌نشده → failed؛ خطای ارتباط → pending می‌ماند.
- (d) recheck: pending → تسویه؛ مصرف‌شده → همان؛ کاربر دیگر → ۴۰۴؛
  خرید بازار → ۴۰۰؛ هدیه‌ی 'first' فقط بار اول.
- (e) انقضا (G3): بازارِ بدون توکنِ قدیمی حذف می‌شود؛ زیبالِ ۴۰ دقیقه‌ای
  نه؛ زیبالِ ۸ روزه حذف می‌شود؛ بازارِ دارای توکن می‌ماند.
- (f) myket_url در تنظیمات عمومی (S2) + صفحه‌ی bounce (G2).
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
    AppVerifyAttempt,
)
from app.services import coins as coins_svc  # noqa: E402
from app.services import zibal as zibal_svc  # noqa: E402
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

        pack = AppCoinPack(
            id=next_id(),
            name="test pack",
            coins=50,
            price_toman=149900,
            bazaar_sku="pack_test",
            is_active=True,
            gift_coins=5,
            gift_scope="every",
            zibal_enabled=False,
        )
        pack2 = AppCoinPack(
            id=next_id(),
            name="first pack",
            coins=30,
            price_toman=89900,
            bazaar_sku="pack_first",
            is_active=True,
            gift_coins=10,
            gift_scope="first",
            zibal_enabled=True,
        )
        db.session.add_all([pack, pack2])
        db.session.commit()

        user = AppUser(
            id=next_id(),
            mobile="09120000001",
            free_image_credits=1,
            free_voice_credits=0,
        )
        user2 = AppUser(
            id=next_id(),
            mobile="09120000002",
            free_image_credits=1,
            free_voice_credits=0,
        )
        db.session.add_all([user, user2])
        db.session.commit()
        auth = _otp_login(client, "09120000001")
        auth2 = _otp_login(client, "09120000002")

        def balance(u=user):
            db.session.expire_all()
            return coins_svc.get_balance(AppUser.query.get(u.id))

        # --- mock زیبال: _post جایگزین می‌شود؛ هیچ تماسی به بیرون نیست ---
        zibal_calls = []
        verify_results = {}
        verify_raises = [False]
        request_seq = [800000]

        def _fake_post(url, body):
            zibal_calls.append((url, body))
            if url.endswith("/request"):
                request_seq[0] += 1
                return {"result": 100, "trackId": request_seq[0]}
            if url.endswith("/verify"):
                if verify_raises[0]:
                    raise zibal_svc.ZibalError("boom")
                tid = str(body.get("trackId"))
                if tid in verify_results:
                    return verify_results[tid]
                return {"result": 100, "trackId": int(tid), "amount": 1499000}
            return {"result": 101, "message": "unexpected"}

        zibal_svc._post = _fake_post

        # --- (a) پرچم‌های پک ---
        r = client.get("/api/v1/coins/packs")
        j = r.get_json() or []
        p1 = next((p for p in j if p["id"] == pack.id), None)
        check("packs: flags present, zibal OFF by default",
              p1 is not None
              and p1.get("bazaar_enabled") is True
              and p1.get("zibal_enabled") is False,
              f"got {p1}")
        r = _admin(client, f"/admin/packs/{pack.id}", "PATCH",
                   {"zibal_enabled": True})
        check("admin PATCH: zibal_enabled flips on",
              r.status_code == 200
              and (r.get_json() or {}).get("zibal_enabled") is True,
              f"got {r.status_code} {r.data!r}")
        r = client.get("/api/v1/coins/packs")
        p1 = next((p for p in (r.get_json() or []) if p["id"] == pack.id), None)
        check("packs: zibal_enabled now true",
              p1 is not None and p1.get("zibal_enabled") is True)

        # --- (b) ساخت زیبال ---
        r = _auth(client, auth, "/coins/purchases/zibal", "POST",
                  {"pack_id": pack.id})
        check("zibal create without merchant: 503",
              r.status_code == 503,
              f"got {r.status_code} {r.data!r}")
        app.config["ZIBAL_MERCHANT"] = "zibal"
        app.config["ZIBAL_CALLBACK_URL"] = (
            "https://chehre.inourai.ir/api/v1/coins/purchases/zibal/callback"
        )
        r = _admin(client, f"/admin/packs/{pack.id}", "PATCH",
                   {"zibal_enabled": False})
        assert r.status_code == 200
        r = _auth(client, auth, "/coins/purchases/zibal", "POST",
                  {"pack_id": pack.id})
        check("zibal create with flag OFF: 403",
              r.status_code == 403,
              f"got {r.status_code} {r.data!r}")
        _admin(client, f"/admin/packs/{pack.id}", "PATCH",
               {"zibal_enabled": True})
        calls_before = len(zibal_calls)
        r = _auth(client, auth, "/coins/purchases/zibal", "POST",
                  {"pack_id": pack.id})
        j = r.get_json() or {}
        check("zibal create: 201 + bounce URL (not raw gateway)",
              r.status_code == 201
              and "/coins/purchases/zibal/go/" in (j.get("payment_url") or "")
              and "gateway.zibal.ir" not in (j.get("payment_url") or ""),
              f"got {r.status_code} {j}")
        pid1 = j.get("purchase_id")
        row1 = AppPurchase.query.get(pid1)
        check("zibal create: row is gateway=zibal with trackId, pending",
              row1 is not None
              and row1.gateway == "zibal"
              and row1.zibal_track_id is not None
              and row1.status == "pending",
              f"got {row1.gateway if row1 else None} {row1.zibal_track_id if row1 else None}")
        check("zibal create: exactly one Zibal request",
              len(zibal_calls) == calls_before + 1,
              f"got {len(zibal_calls) - calls_before}")
        tid1 = row1.zibal_track_id
        verify_results[tid1] = {
            "result": 100, "trackId": int(tid1), "amount": 1499000,
        }
        r = _auth(client, auth, "/coins/purchases/zibal", "POST",
                  {"pack_id": pack.id})
        check("zibal create again: same row, no second Zibal request",
              r.status_code == 200
              and (r.get_json() or {}).get("purchase_id") == pid1
              and len(zibal_calls) == calls_before + 1,
              f"got {r.status_code} {r.get_json()} calls={len(zibal_calls)}")

        # --- (c) کالبک ---
        r = client.get(
            "/api/v1/coins/purchases/zibal/callback"
            f"?trackId={tid1}&success=1"
        )
        check("callback paid: success page",
              r.status_code == 200 and "پرداخت موفق شد".encode() in r.data,
              f"got {r.status_code} {r.data[:120]!r}")
        db.session.expire_all()
        row1 = AppPurchase.query.get(pid1)
        check("callback paid: consumed + coins + gift (50+5)",
              row1.status == "consumed" and balance() == 55,
              f"got {row1.status} balance={balance()}")
        r = client.get(
            "/api/v1/coins/purchases/zibal/callback"
            f"?trackId={tid1}&success=1"
        )
        check("callback again: still success, no double credit",
              r.status_code == 200
              and "پرداخت موفق شد".encode() in r.data
              and balance() == 55,
              f"got balance={balance()}")
        r = client.get(
            "/api/v1/coins/purchases/zibal/callback?trackId=999999&success=1"
        )
        check("callback unknown trackId: failure page",
              r.status_code == 200 and "پیدا نشد".encode() in r.data,
              f"got {r.status_code} {r.data[:120]!r}")

        # پرداخت‌نشده → failed (پک دوم).
        r = _auth(client, auth, "/coins/purchases/zibal", "POST",
                  {"pack_id": pack2.id})
        pid2 = (r.get_json() or {}).get("purchase_id")
        row2 = AppPurchase.query.get(pid2)
        tid2 = row2.zibal_track_id
        verify_results[tid2] = {
            "result": 102, "trackId": int(tid2), "amount": 899000,
        }
        r = client.get(
            "/api/v1/coins/purchases/zibal/callback"
            f"?trackId={tid2}&success=0"
        )
        db.session.expire_all()
        row2 = AppPurchase.query.get(pid2)
        check("callback not paid: failure page + status failed",
              r.status_code == 200
              and "پرداخت ناموفق".encode() in r.data
              and row2.status == "failed",
              f"got {r.status_code} status={row2.status}")

        # خطای ارتباط → نامشخص، pending می‌ماند.
        r = _auth(client, auth, "/coins/purchases/zibal", "POST",
                  {"pack_id": pack.id})
        pid3 = (r.get_json() or {}).get("purchase_id")
        row3 = AppPurchase.query.get(pid3)
        tid3 = row3.zibal_track_id
        verify_raises[0] = True
        r = client.get(
            "/api/v1/coins/purchases/zibal/callback"
            f"?trackId={tid3}&success=1"
        )
        db.session.expire_all()
        row3 = AppPurchase.query.get(pid3)
        check("callback transport error: unknown page, still pending",
              r.status_code == 200
              and "نامشخص".encode() in r.data
              and row3.status == "pending",
              f"got {r.status_code} status={row3.status}")
        verify_raises[0] = False

        # --- (d) recheck ---
        verify_results[tid3] = {
            "result": 100, "trackId": int(tid3), "amount": 1499000,
        }
        r = _auth(client, auth, f"/coins/purchases/{pid3}/zibal/recheck")
        j = r.get_json() or {}
        check("recheck pending paid: consumed + balance 110",
              r.status_code == 200
              and j.get("status") == "consumed"
              and balance() == 110,
              f"got {r.status_code} {j} balance={balance()}")
        r = _auth(client, auth, f"/coins/purchases/{pid3}/zibal/recheck")
        check("recheck consumed: as-is",
              r.status_code == 200
              and (r.get_json() or {}).get("status") == "consumed",
              f"got {r.status_code} {r.get_json()}")
        r = _auth(client, auth2, f"/coins/purchases/{pid3}/zibal/recheck")
        check("recheck other user: 404",
              r.status_code == 404, f"got {r.status_code}")
        baz = AppPurchase(
            id=next_id(), user_id=user.id, pack_id=pack.id,
            bazaar_purchase_token="tok-baz", amount_irr=1499000,
            status="pending", gateway="bazaar",
        )
        db.session.add(baz)
        db.session.commit()
        r = _auth(client, auth, f"/coins/purchases/{baz.id}/zibal/recheck")
        check("recheck non-zibal purchase: 400",
              r.status_code == 400, f"got {r.status_code}")

        # هدیه‌ی 'first': بار اول دارد، بار دوم نه.
        bal_before = balance()
        r = _auth(client, auth, "/coins/purchases/zibal", "POST",
                  {"pack_id": pack2.id})
        pid4 = (r.get_json() or {}).get("purchase_id")
        row4 = AppPurchase.query.get(pid4)
        tid4 = row4.zibal_track_id
        verify_results[tid4] = {
            "result": 100, "trackId": int(tid4), "amount": 899000,
        }
        client.get(
            "/api/v1/coins/purchases/zibal/callback"
            f"?trackId={tid4}&success=1"
        )
        check("gift first: first purchase gets +10",
              balance() == bal_before + 40,
              f"got {balance()} expected {bal_before + 40}")
        r = _auth(client, auth, "/coins/purchases/zibal", "POST",
                  {"pack_id": pack2.id})
        pid5 = (r.get_json() or {}).get("purchase_id")
        row5 = AppPurchase.query.get(pid5)
        tid5 = row5.zibal_track_id
        verify_results[tid5] = {
            "result": 100, "trackId": int(tid5), "amount": 899000,
        }
        client.get(
            "/api/v1/coins/purchases/zibal/callback"
            f"?trackId={tid5}&success=1"
        )
        check("gift first: second purchase gets no gift (+30 only)",
              balance() == bal_before + 70,
              f"got {balance()} expected {bal_before + 70}")
        check("verify attempts logged with store=zibal",
              AppVerifyAttempt.query.filter_by(store="zibal").count() >= 5,
              f"got {AppVerifyAttempt.query.filter_by(store='zibal').count()}")

        # --- (e) انقضا (G3) ---
        # ردیف‌های ساخته‌شده از راه API شناسه‌ی خودکار گرفته‌اند؛ شمارنده‌ی
        # دستی را جلوتر از بزرگ‌ترین شناسه‌ی موجود می‌بریم تا برخورد نکند.
        max_pid = db.session.query(db.func.max(AppPurchase.id)).scalar() or 0
        _NEXT_ID[0] = max(_NEXT_ID[0], max_pid)
        now = datetime.datetime.utcnow()
        old = now - datetime.timedelta(minutes=40)
        old8d = now - datetime.timedelta(days=8)
        p_old_baz = AppPurchase(
            id=next_id(), user_id=user.id, pack_id=pack.id,
            bazaar_purchase_token=None, amount_irr=1499000,
            status="pending", gateway="bazaar", created_at=old,
        )
        p_old_baz_tok = AppPurchase(
            id=next_id(), user_id=user.id, pack_id=pack.id,
            bazaar_purchase_token="tok-old", amount_irr=1499000,
            status="pending", gateway="bazaar", created_at=old,
        )
        p_old_zib = AppPurchase(
            id=next_id(), user_id=user.id, pack_id=pack.id,
            bazaar_purchase_token=None, amount_irr=1499000,
            status="pending", gateway="zibal", zibal_track_id="777001",
            created_at=old,
        )
        p_old_zib_8d = AppPurchase(
            id=next_id(), user_id=user.id, pack_id=pack.id,
            bazaar_purchase_token=None, amount_irr=1499000,
            status="pending", gateway="zibal", zibal_track_id="777002",
            created_at=old8d,
        )
        p_fresh_zib = AppPurchase(
            id=next_id(), user_id=user.id, pack_id=pack.id,
            bazaar_purchase_token=None, amount_irr=1499000,
            status="pending", gateway="zibal", zibal_track_id="777003",
            created_at=now,
        )
        db.session.add_all([
            p_old_baz, p_old_baz_tok, p_old_zib, p_old_zib_8d, p_fresh_zib,
        ])
        db.session.commit()
        deleted = expire_stale_pending_purchases()
        check("expiry: deletes exactly 2 rows (old bazaar + 8-day zibal)",
              deleted == 2, f"got {deleted}")
        check("expiry: old bazaar token-less deleted",
              AppPurchase.query.get(p_old_baz.id) is None)
        check("expiry: old bazaar WITH token kept",
              AppPurchase.query.get(p_old_baz_tok.id) is not None)
        check("expiry: 40-min zibal kept (G3)",
              AppPurchase.query.get(p_old_zib.id) is not None)
        check("expiry: 8-day zibal deleted",
              AppPurchase.query.get(p_old_zib_8d.id) is None)
        check("expiry: fresh zibal kept",
              AppPurchase.query.get(p_fresh_zib.id) is not None)

        # --- (f) myket_url (S2) + bounce (G2) ---
        r = client.get("/api/v1/app/settings")
        j = r.get_json() or {}
        check("public settings: myket_url key present (empty)",
              "myket_url" in j and j.get("myket_url") == "",
              f"got {j}")
        r = _admin(client, "/admin/settings", "PATCH",
                   {"key": "myket_url",
                    "value": "https://myket.ir/app/ir.inourai.newapp"})
        check("admin settings PATCH myket_url: 200",
              r.status_code == 200, f"got {r.status_code} {r.data!r}")
        r = client.get("/api/v1/app/settings")
        check("public settings: myket_url returned",
              (r.get_json() or {}).get("myket_url")
              == "https://myket.ir/app/ir.inourai.newapp",
              f"got {r.get_json()}")
        r = client.get("/api/v1/coins/purchases/zibal/go/777001")
        check("go bounce: meta-refresh to the gateway",
              r.status_code == 200
              and b"https://gateway.zibal.ir/start/777001" in r.data,
              f"got {r.status_code} {r.data[:120]!r}")

    print(f"\n{PASSED} passed, {FAILED} failed")
    return 1 if FAILED else 0


if __name__ == "__main__":
    raise SystemExit(main())
