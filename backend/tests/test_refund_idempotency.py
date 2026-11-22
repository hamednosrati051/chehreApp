"""ایدم‌پوتنسی برگشت رزرو (۲۰۲۶-۱۱-۲۲، باگ نسل ۲۳۹۵):

نسل ۲۳۹۵ (کاربر ۳۳۰) یک بار -۱۰ شارژ شد ولی دو بار +۱۰ برگشت خورد:
سوئیپر (گیرکرده >۱۲۰ ثانیه) و ورکر (خطای provider) هر دو با یک شیء
قدیمی در حافظه charged=True می‌دیدند و هر دو برگشت می‌زدند.
رفع: refund_reservation حالا ادعای اتمیک می‌کند — یک UPDATE مشروط روی
سطر دیتابیس (charged True → False)؛ فقط وقتی rowcount=1 باشد ادامه
می‌دهد. force=True فقط برای _refund_and_drop_generation است (ردیف
چندثانیه‌ای، هرگز صف‌نرفته، بلافاصله حذف می‌شود).

اجرا (روی سرور):
    /opt/new-app/venv/bin/python tests/test_refund_idempotency.py

فقط دیتابیسِ موقتِ SQLite؛ هیچ فایلی ساخته یا پاک نمی‌شود و پوشه‌ی
واقعی static/uploads دست نمی‌خورد.
"""

import datetime
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app import create_app  # noqa: E402
from app.config import Config  # noqa: E402
from app.extensions import db  # noqa: E402
from app.models import (  # noqa: E402
    AppCoinBalance,
    AppCoinLedger,
    AppGeneration,
    AppUser,
)
from app.services import coins as coins_svc  # noqa: E402
from app.services.cleanup import fail_stale_generations  # noqa: E402

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


def _refund_rows(gen_id):
    return (
        AppCoinLedger.query.filter_by(
            ref_type="generation", ref_id=gen_id, reason="refund"
        ).count()
    )


def _spend_rows(gen_id):
    return (
        AppCoinLedger.query.filter_by(
            ref_type="generation", ref_id=gen_id, reason="spend"
        ).count()
    )


def _balance(user_id):
    db.session.expire_all()
    row = AppCoinBalance.query.get(user_id)
    return row.balance if row else 0


def _fresh_user(user_id):
    db.session.expire_all()
    return AppUser.query.get(user_id)


def _stale_gen(gen_id, kind="image", cost=10):
    """شیء جدا (detached) با charged=True — مدلِ دیدِ قدیمیِ ورکر که
    ردیف را پیش از برگشتِ سوئیپر خوانده بود."""
    return AppGeneration(
        id=gen_id, user_id=0, kind=kind, coin_cost=cost, charged=True
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

        def make_user(balance=0, gift_image=0, gift_voice=0):
            user = AppUser(
                id=next_id(),
                mobile=f"09120000{next_id():03d}",
                free_image_credits=gift_image,
                free_voice_credits=gift_voice,
            )
            db.session.add(user)
            db.session.commit()
            db.session.add(AppCoinBalance(user_id=user.id, balance=balance))
            db.session.commit()
            return user

        def make_gen(user, kind="image", cost=10, charged=True,
                     status="processing", age_s=0):
            gen = AppGeneration(
                id=next_id(),
                user_id=user.id,
                kind=kind,
                world_id=None,
                model_id=6,
                input_url="https://example.invalid/in.jpg",
                coin_cost=cost,
                charged=charged,
                status=status,
                created_at=datetime.datetime.utcnow()
                - datetime.timedelta(seconds=age_s),
            )
            db.session.add(gen)
            db.session.commit()
            return gen

        # --- ۱) خطای ورکر فقط یک بار برمی‌گرداند (سکه) ---
        u1 = make_user(balance=50)
        g1 = make_gen(u1)
        check(
            "reserve coins",
            coins_svc.reserve_for_generation(u1, "image", 10, g1.id)
            == "coins",
        )
        check("balance after reserve", _balance(u1.id) == 40)
        coins_svc.refund_reservation(_fresh_user(u1.id), g1)
        check("balance after worker refund", _balance(u1.id) == 50)
        check("one refund row", _refund_rows(g1.id) == 1)
        check("charged cleared", AppGeneration.query.get(g1.id).charged is False)
        # تلاش دوم با همان شیء + شیء قدیمی (دید ورکر پیش از برگشت)
        coins_svc.refund_reservation(_fresh_user(u1.id), g1)
        coins_svc.refund_reservation(
            _fresh_user(u1.id), _stale_gen(g1.id)
        )
        check("second refund is a no-op", _refund_rows(g1.id) == 1)
        check("balance still 50", _balance(u1.id) == 50)

        # --- ۲) توالی دقیق ۲۳۹۵: سوئیپر اول، بعد ورکر با شیء قدیمی ---
        u2 = make_user(balance=50)
        g2 = make_gen(u2, age_s=200)  # گیرکرده >۱۲۰ ثانیه
        check(
            "reserve coins (2395)",
            coins_svc.reserve_for_generation(u2, "image", 10, g2.id)
            == "coins",
        )
        n = fail_stale_generations()
        check("sweeper failed it", n >= 1)
        check(
            "sweeper marked failed",
            AppGeneration.query.get(g2.id).status == "failed",
        )
        check("one refund after sweeper", _refund_rows(g2.id) == 1)
        check("balance back to 50", _balance(u2.id) == 50)
        # ورکر تازه از provider برمی‌گردد و با شیء قدیمی برگشت می‌زند
        coins_svc.refund_reservation(
            _fresh_user(u2.id), _stale_gen(g2.id)
        )
        check("no second refund (2395 bug)", _refund_rows(g2.id) == 1)
        check("balance still 50", _balance(u2.id) == 50)

        # --- ۳) ترتیب برعکس: ورکر اول، بعد سوئیپر ---
        u3 = make_user(balance=50)
        g3 = make_gen(u3, age_s=200)
        coins_svc.reserve_for_generation(u3, "image", 10, g3.id)
        coins_svc.refund_reservation(_fresh_user(u3.id), g3)
        check("one refund after worker", _refund_rows(g3.id) == 1)
        coins_svc.refund_reservation(
            _fresh_user(u3.id), _stale_gen(g3.id)
        )
        check("stale retry is a no-op", _refund_rows(g3.id) == 1)
        # سوئیپر ردیفِ کهنه را failed می‌کند ولی برگشتِ دوم نمی‌زند
        n = fail_stale_generations()
        check("sweeper adds no second refund", _refund_rows(g3.id) == 1)
        check("balance still 50", _balance(u3.id) == 50)

        # --- ۴) نسل موفق / بدون شارژ هرگز برگشت نمی‌خورد ---
        u4 = make_user(balance=40)
        g4 = make_gen(u4, status="done")  # تحویل موفق، شارژ شده
        coins_svc.reserve_for_generation(u4, "image", 10, g4.id)
        check("done gen keeps the charge", _balance(u4.id) == 30)
        check("no refund for done gen", _refund_rows(g4.id) == 0)
        u5 = make_user(balance=40)
        g5 = make_gen(u5, charged=False)  # هرگز شارژ نشده
        coins_svc.refund_reservation(_fresh_user(u5.id), g5)
        check("uncharged gen: no refund", _refund_rows(g5.id) == 0)
        check("balance untouched", _balance(u5.id) == 40)
        g6 = make_gen(u5, cost=0, charged=False)  # رایگان
        coins_svc.refund_reservation(_fresh_user(u5.id), g6)
        check("free gen: no refund", _refund_rows(g6.id) == 0)

        # --- ۵) هدیه از همان گارد می‌گذرد ---
        u6 = make_user(balance=0, gift_image=1)
        g7 = make_gen(u6)
        check(
            "reserve gift",
            coins_svc.reserve_for_generation(u6, "image", 10, g7.id)
            == "gift",
        )
        check(
            "gift consumed",
            _fresh_user(u6.id).free_image_credits == 0,
        )
        coins_svc.refund_reservation(_fresh_user(u6.id), g7)
        check(
            "gift restored once",
            _fresh_user(u6.id).free_image_credits == 1,
        )
        check("no ledger row for gift refund", _refund_rows(g7.id) == 0)
        coins_svc.refund_reservation(
            _fresh_user(u6.id), _stale_gen(g7.id)
        )
        check(
            "gift not restored twice",
            _fresh_user(u6.id).free_image_credits == 1,
        )
        # سوئیپر اول برای هدیه
        u7 = make_user(balance=0, gift_image=1)
        g8 = make_gen(u7, age_s=200)
        coins_svc.reserve_for_generation(u7, "image", 10, g8.id)
        fail_stale_generations()
        check(
            "sweeper restores gift once",
            _fresh_user(u7.id).free_image_credits == 1,
        )
        coins_svc.refund_reservation(
            _fresh_user(u7.id), _stale_gen(g8.id)
        )
        check(
            "stale gift retry is a no-op",
            _fresh_user(u7.id).free_image_credits == 1,
        )

        # --- ۶) مسیر force: _refund_and_drop_generation ---
        from app.routes import _refund_and_drop_generation  # noqa: E402

        u8 = make_user(balance=50)
        g9 = make_gen(u8, charged=False)  # commitِ پرچم ناموفق بود
        coins_svc.reserve_for_generation(u8, "image", 10, g9.id)
        check("spend row exists", _spend_rows(g9.id) == 1)
        check("balance after reserve", _balance(u8.id) == 40)
        _refund_and_drop_generation(g9, reserved=True)
        check("force refund restores coins", _balance(u8.id) == 50)
        check("one refund row (force)", _refund_rows(g9.id) == 1)
        check(
            "row deleted",
            AppGeneration.query.get(g9.id) is None,
        )
        # هدیه با همان مسیر
        u9 = make_user(balance=0, gift_image=1)
        g10 = make_gen(u9, charged=False)
        coins_svc.reserve_for_generation(u9, "image", 10, g10.id)
        check(
            "gift consumed (force)",
            _fresh_user(u9.id).free_image_credits == 0,
        )
        _refund_and_drop_generation(g10, reserved=True)
        check(
            "force refund restores gift",
            _fresh_user(u9.id).free_image_credits == 1,
        )
        check(
            "gift row deleted",
            AppGeneration.query.get(g10.id) is None,
        )

        # --- ۷) برگشت، تغییر هم‌زمانِ موجودی را گم نمی‌کند ---
        # سناریوی نسل ۲۳۹۶: سشنِ ورکر موجودی را ۲۰ دیده بود (هویت‌مپ)،
        # ولی دیتابیس واقعاً ۱۰ بود؛ برگشت با خواندن-بعد-نوشتن ۳۰
        # می‌نوشت (اشتباه) — افزایش باید در خود دیتابیس حساب شود (۲۰).
        # نکته: expire_all یا _fresh_user هویت‌مپ را تازه می‌کند و
        # سناریو را از بین می‌برد؛ پس این‌جا کاربر را بدون expire
        # می‌خوانیم و مقدار دیتابیس را از یک کانکشن جدا می‌گیریم.
        from sqlalchemy import text as _text  # noqa: E402

        # نکته: expire_on_commit را باید روی سشنِ واقعی گذاشت
        # (db.session()‎)؛ گذاشتنش روی پروکسیِ scoped_session اثری
        # ندارد و آبجکت‌ها سرِ هر commit expire می‌شوند.
        _real_session = db.session()
        _real_session.expire_on_commit = False
        try:
            u10 = make_user(balance=20)
            # INSERT موجودی را expire می‌کند (server_default)؛ پس اول
            # صریح می‌خوانیمش و ارجاعش را نگه می‌داریم تا سشن آن را
            # با ۲۰ نگه دارد.
            _bal_obj = AppCoinBalance.query.get(u10.id)
            check("balance loaded as 20", _bal_obj.balance == 20)
            g12 = make_gen(u10)
            coins_svc.reserve_for_generation(u10, "image", 10, g12.id)
            # دیتابیس ۱۰ است؛ شیء موجودی در همین سشن هنوز ۲۰ می‌گوید
            check("stale object still says 20", _bal_obj.balance == 20)
            _conn = db.engine.connect()
            _db_val = _conn.execute(
                _text(
                    "SELECT balance FROM app_coin_balances "
                    "WHERE user_id = :u"
                ),
                {"u": u10.id},
            ).first()[0]
            _conn.close()
            check("db really is 10", _db_val == 10)
            coins_svc.refund_reservation(
                AppUser.query.get(u10.id), g12
            )
            check(
                "refund lands on the db value (20)",
                _balance(u10.id) == 20,
            )
            check("one refund row", _refund_rows(g12.id) == 1)
        finally:
            _real_session.expire_on_commit = True

    print(f"\n{ PASSED} passed, {FAILED} failed")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
