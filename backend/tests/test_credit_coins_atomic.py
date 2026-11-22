"""اتمیک بودن credit_coins (۲۰۲۶-۱۱-۲۲):

باگ: credit_coins خواندن-بعد-نوشتن می‌کرد (row.balance += amount)؛ دو
تسویه‌ی هم‌زمان یک موجودیِ قدیمی را می‌خواندند و آخری روی اولی می‌نوشت
و شارژِ اولی گم می‌شد (lost update — همان خانواده‌ی باگ نسل ۲۳۹۶ که
refund_reservation را اتمیک کرد). رفع: یک UPDATE یک‌مرحله‌ای
(balance = balance + amount) در خود دیتابیس — همان الگوی
refund_reservation. هر سه درگاه (بازار، مایکت، زیبال) از همین یک تابع
تسویه می‌کنند، پس یک تغییر هر سه را می‌پوشاند.

اجرا (روی سرور):
    /opt/new-app/venv/bin/python tests/test_credit_coins_atomic.py

فقط دیتابیسِ موقتِ SQLite؛ هیچ فایلی ساخته یا پاک نمی‌شود و پوشه‌ی
واقعی static/uploads دست نمی‌خورد.
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app import create_app  # noqa: E402
from app.config import Config  # noqa: E402
from app.extensions import db  # noqa: E402
from app.models import (  # noqa: E402
    AppCoinBalance,
    AppCoinLedger,
    AppCoinPack,
    AppPurchase,
    AppUser,
)
from app.services import coins as coins_svc  # noqa: E402
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


def _balance(user_id):
    db.session.expire_all()
    row = AppCoinBalance.query.get(user_id)
    return row.balance if row else 0


def _ledger_deltas(user_id, reason=None):
    q = AppCoinLedger.query.filter_by(user_id=user_id)
    if reason is not None:
        q = q.filter_by(reason=reason)
    return sorted(r.delta for r in q.all())


def main():
    app = create_app(TestConfig)
    with app.app_context():
        from sqlalchemy import BigInteger, Integer

        for _table in db.metadata.tables.values():
            for _col in _table.columns:
                if _col.primary_key and isinstance(_col.type, BigInteger):
                    _col.type = Integer()
        db.create_all()

        def make_user(balance=None, gift_image=0, gift_voice=0):
            user = AppUser(
                id=next_id(),
                mobile=f"09120000{next_id():03d}",
                free_image_credits=gift_image,
                free_voice_credits=gift_voice,
            )
            db.session.add(user)
            db.session.commit()
            if balance is not None:
                db.session.add(
                    AppCoinBalance(user_id=user.id, balance=balance)
                )
                db.session.commit()
            return user

        # نکته‌ی §۷ تست برگشت: expire_on_commit را باید روی سشنِ واقعی
        # گذاشت (db.session())؛ گذاشتنش روی پروکسیِ scoped_session
        # اثری ندارد و آبجکت‌ها سرِ هر commit expire می‌شوند.
        _real_session = db.session()
        _real_session.expire_on_commit = False
        try:
            # --- ۱) دو تسویه‌ی هم‌زمان: هر دو می‌نشینند (lost update) ---
            # سناریو: ورکرِ اول موجودی را ۲۰ دیده و نگه داشته؛ تسویه‌ی
            # دوم هم‌زمان در دیتابیس +۱۰ می‌زند (۳۰)؛ حالا ورکرِ اول
            # +۱۰ خودش را می‌زند. با خواندن-بعد-نوشتن، ۲۰+۱۰=۳۰ روی
            # ۳۰ نوشته می‌شد و شارژِ دوم گم می‌شد؛ با افزایشِ
            # دیتابیسی باید ۴۰ شود.
            u1 = make_user(balance=20)
            bal_obj = AppCoinBalance.query.get(u1.id)
            check("balance loaded as 20", bal_obj.balance == 20)
            AppCoinBalance.query.filter(
                AppCoinBalance.user_id == u1.id
            ).update(
                {AppCoinBalance.balance: AppCoinBalance.balance + 10},
                synchronize_session=False,
            )
            db.session.commit()
            check("stale object still says 20", bal_obj.balance == 20)
            # خواندن مقدار دیتابیس از یک کانکشن جدا (مثل §۷ تست برگشت):
            # ‎_balance()‎ هویت‌مپ را expire می‌کند و شیءِ قدیمی را
            # تازه می‌خواند — سناریو را از بین می‌برد.
            from sqlalchemy import text as _text  # noqa: E402

            _conn = db.engine.connect()
            _db_val = _conn.execute(
                _text(
                    "SELECT balance FROM app_coin_balances "
                    "WHERE user_id = :u"
                ),
                {"u": u1.id},
            ).first()[0]
            _conn.close()
            check("db really is 30", _db_val == 30)
            # شیءِ قدیمی هنوز در هویت‌مپ است؛ credit_coins نباید
            # به آن نگاه کند.
            coins_svc.credit_coins(
                u1, 10, reason="purchase",
                ref_type="purchase", ref_id=next_id(),
            )
            check(
                "both concurrent credits land (40)",
                _balance(u1.id) == 40,
                f"got {_balance(u1.id)}",
            )
            check(
                "one purchase ledger row for our credit",
                _ledger_deltas(u1.id, "purchase") == [10],
            )

            # --- ۱ب) همان race با commit=False (مسیر واقعی تسویه) ---
            # دو credit_coins در یک تراکنش (خرید + هدیه)، با یک شارژِ
            # هم‌زمان وسطشان.
            u2 = make_user(balance=20)
            bal_obj2 = AppCoinBalance.query.get(u2.id)
            check("balance loaded as 20 (1b)", bal_obj2.balance == 20)
            AppCoinBalance.query.filter(
                AppCoinBalance.user_id == u2.id
            ).update(
                {AppCoinBalance.balance: AppCoinBalance.balance + 10},
                synchronize_session=False,
            )
            db.session.commit()
            coins_svc.credit_coins(
                u2, 10, reason="purchase",
                ref_type="purchase", ref_id=next_id(), commit=False,
            )
            coins_svc.credit_coins(
                u2, 5, reason="gift",
                ref_type="purchase", ref_id=next_id(), commit=False,
            )
            db.session.commit()
            check(
                "purchase + gift + concurrent credit all land (45)",
                _balance(u2.id) == 45,
                f"got {_balance(u2.id)}",
            )
            check(
                "ledger has purchase 10 and gift 5",
                _ledger_deltas(u2.id) == [5, 10],
            )
        finally:
            _real_session.expire_on_commit = True

        # --- ۲) مقدار منفی (کسر) کار می‌کند ---
        u3 = make_user(balance=50)
        coins_svc.credit_coins(u3, -15, reason="test")
        check("negative amount deducts", _balance(u3.id) == 35)
        check(
            "negative ledger row",
            _ledger_deltas(u3.id, "test") == [-15],
        )

        # --- ۳) ردیف موجودی نباشد ساخته می‌شود ---
        u4 = make_user(balance=None)
        check("no balance row yet", _balance(u4.id) == 0)
        ret = coins_svc.credit_coins(
            u4, 25, reason="purchase",
            ref_type="purchase", ref_id=next_id(),
        )
        check("return stays None", ret is None)
        check("row created with 25", _balance(u4.id) == 25)

        # --- ۴) خرید + هدیه در یک تسویه‌ی واقعی (زیبال) هر دو می‌نشینند ---
        pack = AppCoinPack(
            id=next_id(),
            name="test pack",
            coins=50,
            price_toman=149900,
            bazaar_sku="pack_test",
            is_active=True,
            gift_coins=10,
            gift_scope="every",
        )
        db.session.add(pack)
        db.session.commit()
        u5 = make_user(balance=0)
        purchase = AppPurchase(
            id=next_id(),
            user_id=u5.id,
            pack_id=pack.id,
            gateway="zibal",
            zibal_track_id="track-test-1",
            amount_irr=149900,
            status="pending",
        )
        db.session.add(purchase)
        db.session.commit()
        settled, already, gift = zibal_svc.settle_purchase(
            u5, pack, purchase
        )
        check("settle: consumed", settled.status == "consumed")
        check("settle: not already_credited", already is False)
        check("settle: gift granted 10", gift == 10)
        check(
            "settle: purchase 50 + gift 10 land (60)",
            _balance(u5.id) == 60,
            f"got {_balance(u5.id)}",
        )
        check(
            "settle: ledger purchase 50 + gift 10",
            _ledger_deltas(u5.id) == [10, 50],
        )
        # تسویه‌ی دوم همان ردیف: شارژ مجدد ممنوع
        settled2, already2, gift2 = zibal_svc.settle_purchase(
            u5, pack, purchase
        )
        check("re-settle: already_credited", already2 is True)
        check("re-settle: no extra gift", gift2 == 0)
        check("re-settle: balance still 60", _balance(u5.id) == 60)
        check(
            "re-settle: ledger unchanged",
            _ledger_deltas(u5.id) == [10, 50],
        )

    print(f"\n{PASSED} passed, {FAILED} failed")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
