"""رفع مسابقه‌ی سوئیپر با ورکر کند (۲۰۲۶-۱۱-۲۶، نسل ۳۴۳۳ کاربر ۲۹۰۳):

نسل ۳۴۳۳ (باغ قاجار، مدل ۶) بیش از ۲ دقیقه طول کشید؛ کرونِ تولیدهای کهنه
(آستانه‌ی ۱۲۰ ثانیه) آن را ناموفق زد و ۱۰ سکه را برگرداند، ولی ورکر هنوز
داشت کار می‌کرد و بعداً عکس را تحویل داد — موقع تحویل کسر ناموفق بود
(موجودی ۰) و نسل done با charged=0 ماند: یک عکس مجانی. در ۷ روز گذشته
۹ تا از این عکس‌های مجانی بوده است.

رفع (سه بخش):
۱) ورکر پیش از تحویل، وضعیتِ تازه‌ی ردیف را از دیتابیس می‌خواند
   (_swept_by_cleaner)؛ اگر کرون قبلاً آن را کهنه کرده بود، نتیجه دور
   انداخته می‌شود و تحویل داده نمی‌شود (_finalize_delivery).
۲) آستانه‌ی کرون از ۱۲۰ به ۲۵۰ ثانیه رسید (۹۹٫۷٪ تولیدهای سالم GapGPT
   در ۷ روز گذشته زیر ۳۰۰ ثانیه بودند؛ ۹۴٪ زیر ۱۲۰ ثانیه).
۳) اگر کسر موقع تحویل ناموفق بود، عکس تحویل داده نمی‌شود — نسل ناموفق
   می‌ماند تا کاربر دوباره بزند (حفره‌ی قدیمی charged=False هم بسته شد).

اجرا:
    /tmp/chehre-test-venv/bin/python backend/tests/test_sweeper_race_fix.py

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
from app.services.cleanup import (  # noqa: E402
    STALE_GENERATION_SECONDS,
    fail_stale_generations,
)
from app.worker import _finalize_delivery, _swept_by_cleaner  # noqa: E402

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


def _fresh_gen(gen_id):
    db.session.expire_all()
    return AppGeneration.query.get(gen_id)


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


def main():
    app = create_app(TestConfig)
    with app.app_context():
        from sqlalchemy import BigInteger, Integer

        for _table in db.metadata.tables.values():
            for _col in _table.columns:
                if _col.primary_key and isinstance(_col.type, BigInteger):
                    _col.type = Integer()
        db.create_all()

        def make_user(balance=0, gift_image=0):
            user = AppUser(
                id=next_id(),
                mobile=f"09120000{next_id():03d}",
                free_image_credits=gift_image,
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
                output_url="https://example.invalid/out.jpg",
                coin_cost=cost,
                charged=charged,
                status=status,
                created_at=datetime.datetime.utcnow()
                - datetime.timedelta(seconds=age_s),
            )
            db.session.add(gen)
            db.session.commit()
            return gen

        # --- ۱) آستانه‌ی جدید ۲۵۰ ثانیه است ---
        check("threshold is 250", STALE_GENERATION_SECONDS == 250)

        # --- ۲) نسل ۲۰۰ ثانیه‌ای دیگر کهنه نیست؛ ۳۰۰ ثانیه‌ای هست ---
        u0 = make_user(balance=50)
        g0 = make_gen(u0, age_s=200)
        fail_stale_generations()
        check(
            "200s gen not swept",
            _fresh_gen(g0.id).status == "processing",
        )
        g0b = make_gen(u0, age_s=300)
        fail_stale_generations()
        check(
            "300s gen swept",
            _fresh_gen(g0b.id).status == "failed",
        )
        check(
            "swept error mentions 250s",
            "250s" in (_fresh_gen(g0b.id).error_message or ""),
        )

        # --- ۳) _swept_by_cleaner: سالم False، کهنه‌شده True ---
        u1 = make_user(balance=50)
        g1 = make_gen(u1)
        check("healthy gen not swept", _swept_by_cleaner(g1) is False)

        # --- ۴) تحویل عادی: شارژشده، سالم → done ---
        delivered = _finalize_delivery(g1)
        db.session.commit()
        check("normal delivery returns True", delivered is True)
        check("normal gen done", _fresh_gen(g1.id).status == "done")
        check(
            "output kept on delivery",
            _fresh_gen(g1.id).output_url is not None,
        )

        # --- ۵) بازپخش دقیق ۳۴۳۳: رزرو → سوئیپر → تحویل = دور انداختن ---
        u2 = make_user(balance=50)
        g2 = make_gen(u2, age_s=300)
        check(
            "reserve coins (3433)",
            coins_svc.reserve_for_generation(u2, "image", 10, g2.id)
            == "coins",
        )
        check("balance after reserve", _balance(u2.id) == 40)
        fail_stale_generations()
        check(
            "sweeper failed it",
            _fresh_gen(g2.id).status == "failed",
        )
        check("one refund after sweeper", _refund_rows(g2.id) == 1)
        check("balance back to 50", _balance(u2.id) == 50)
        # ورکر دیر برمی‌گردد؛ شیء قدیمی هنوز processing/charged می‌گوید
        check("helper sees the sweep", _swept_by_cleaner(g2) is True)
        delivered = _finalize_delivery(g2)
        db.session.commit()
        check("swept delivery returns False", delivered is False)
        check(
            "swept gen stays failed (no free image)",
            _fresh_gen(g2.id).status == "failed",
        )
        check(
            "output dropped on discard",
            _fresh_gen(g2.id).output_url is None,
        )
        check("no second refund", _refund_rows(g2.id) == 1)
        check("no extra spend", _spend_rows(g2.id) == 1)
        check("balance still 50", _balance(u2.id) == 50)

        # --- ۶) کسر موقع تحویل ناموفق → تحویل داده نمی‌شود ---
        u3 = make_user(balance=0)
        g3 = make_gen(u3, charged=False)
        delivered = _finalize_delivery(g3)
        db.session.commit()
        check("unpaid delivery returns False", delivered is False)
        fg3 = _fresh_gen(g3.id)
        check("unpaid gen failed", fg3.status == "failed")
        check(
            "unpaid error message",
            fg3.error_message == "insufficient balance at delivery",
        )
        check("unpaid output dropped", fg3.output_url is None)
        check("no spend row for unpaid", _spend_rows(g3.id) == 0)

        # --- ۷) کار قدیمی با موجودی کافی → تحویل + کسر ---
        u4 = make_user(balance=50)
        g4 = make_gen(u4, charged=False)
        delivered = _finalize_delivery(g4)
        db.session.commit()
        check("old paid delivery returns True", delivered is True)
        check("old gen done", _fresh_gen(g4.id).status == "done")
        check("old gen charged", _fresh_gen(g4.id).charged is True)
        check("balance deducted once", _balance(u4.id) == 40)
        check("one spend row", _spend_rows(g4.id) == 1)

        # --- ۸) کار قدیمی هدیه‌دار → تحویل با مصرف اعتبار هدیه ---
        u5 = make_user(balance=0, gift_image=1)
        g5 = make_gen(u5, charged=False)
        delivered = _finalize_delivery(g5)
        db.session.commit()
        check("gift delivery returns True", delivered is True)
        check("gift gen done", _fresh_gen(g5.id).status == "done")
        db.session.expire_all()
        check(
            "gift credit consumed",
            AppUser.query.get(u5.id).free_image_credits == 0,
        )

    print(f"\n{ PASSED} passed, {FAILED} failed")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
