"""رفعِ واقعیِ مسابقه‌ی سوئیپر با ورکر کند — بازپخش نسل ۴۲۶۸ کاربر ۳۶۷۱:

فیکسِ c216eaa (آستانه‌ی ۲۵۰ ثانیه + چکِ _swept_by_cleaner) در تست‌های قبلی
سبز بود ولی در پروداکشن یک‌بار دیگر نشت کرد: نسل ۴۲۶۸ (دنیا ۱۶۴ رستم،
مدل ۶) ساعت ۱۹:۲۷:۵۲ ساخته شد، سویپر ساعت ۱۹:۳۲:۰۲ آن را fail زد و
۱۰ سکه را برگرداند، ولی ورکر بعداً وضعیت را done زد — عکس مجانی،
charged=0، و error_message همان «stale» سویپر سر جایش ماند.

علت: _swept_by_cleaner با SELECT خام از همان سشنِ ورکر می‌خواند؛ سشنِ
ورکر از اولِ کار (خواندن world/model پیش از تماس با پرووایدر) باز است
و زیر REPEATABLE READ (ایزولیشن MySQL، روی سرور تأیید شده) اسنپ‌شاتش
مال اولِ کار است — کامیتِ سویپر را نمی‌بیند. رفع: چک باید با یک اتصالِ
تازه از موتور بخواند (تراکنشِ تازه → اسنپ‌شاتِ تازه)، نه از سشنِ ورکر.

نکته‌ی مهم درباره‌ی این تست: SQLite (حتی با WAL و دو سشنِ جدا) رفتار
اسنپ‌شاتِ REPEATABLE READ را بازتولید نمی‌کند — خواندنِ دوباره در همان
تراکنش داده‌ی تازه را می‌بیند (سه پروبِ جداگانه تأیید کرد) — پس بخشِ
رفتاریِ این تست روی SQLite روی کدِ قدیمی هم سبز می‌شود و به‌تنهایی
اثبات نمی‌کند. به همین دلیل این تست علاوه بر رفتار، خودِ سازوکار را
هم قفل می‌کند: چک باید اتصالِ تازه‌ی موتور را باز کند (روی کدِ قدیمی
این شمارنده صفر است و تست قرمز می‌شود). رفتارِ اسنپ‌شات روی MySQL با
ردیفِ واقعیِ ۴۲۶۸ و ایزولیشنِ تأییدشده‌ی سرور اثبات شده است.

اجرا:
    /tmp/chehre-test-venv/bin/python backend/tests/test_sweeper_race_fresh_read.py

فقط یک فایلِ موقتِ SQLite در /tmp ساخته می‌شود و آخرِ تست پاک می‌شود؛
هیچ فایلِ واقعیِ کاربر (static/uploads) و هیچ دیتابیسِ واقعی دست
نمی‌خورد.
"""

import datetime
import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from sqlalchemy import text  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402

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
from app.services.cleanup import STALE_GENERATION_ERROR  # noqa: E402
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
    fd, db_path = tempfile.mkstemp(prefix="chehre-sweeper-race-", suffix=".db")
    os.close(fd)
    uri = f"sqlite:///{db_path}"

    class TestConfig(Config):
        SQLALCHEMY_DATABASE_URI = uri
        TESTING = True

    app = create_app(TestConfig)
    try:
        with app.app_context():
            from sqlalchemy import BigInteger, Integer

            for _table in db.metadata.tables.values():
                for _col in _table.columns:
                    if _col.primary_key and isinstance(_col.type, BigInteger):
                        _col.type = Integer()
            db.create_all()
            # WAL تا خواننده‌ی باز (سشن ورکر) نویسنده (سشن سویپر) را
            # قفل نکند.
            with db.engine.connect() as conn:
                conn.execute(text("PRAGMA journal_mode=WAL"))
                conn.commit()

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

            # --- ۱) کنترل: نسل سالم، بدون سویپر → تحویل عادی ---
            u0 = make_user(balance=50)
            g0 = make_gen(u0)
            check(
                "healthy gen not swept",
                _swept_by_cleaner(g0) is False,
            )
            delivered = _finalize_delivery(g0)
            db.session.commit()
            check("healthy delivery returns True", delivered is True)
            check("healthy gen done", _fresh_gen(g0.id).status == "done")

            # --- ۲) بازپخش ۴۲۶۸: سشن ورکر باز می‌ماند، سویپر جدا کامیت می‌کند ---
            u1 = make_user(balance=50)
            g1 = make_gen(u1, age_s=300)
            check(
                "reserve coins (4268)",
                coins_svc.reserve_for_generation(u1, "image", 10, g1.id)
                == "coins",
            )
            check("balance after reserve", _balance(u1.id) == 40)
            # سشنِ ورکر ردیف را می‌خواند (مثل خواندن world/model پیش از
            # تماس با پرووایدر) و تراکنشش تا آخرِ کار باز می‌ماند.
            worker_gen = AppGeneration.query.get(g1.id)
            _ = worker_gen.status
            # سشنِ سویپر: اتصالِ جدا، مثل کرون در پراسسِ جدا.
            SweeperSession = sessionmaker(bind=db.engine)
            sweeper = SweeperSession()
            try:
                sg = sweeper.get(AppGeneration, g1.id)
                sg.status = "failed"
                sg.error_message = STALE_GENERATION_ERROR
                bal = sweeper.get(AppCoinBalance, u1.id)
                bal.balance += 10
                sweeper.add(
                    AppCoinLedger(
                        user_id=u1.id,
                        delta=10,
                        reason="refund",
                        ref_type="generation",
                        ref_id=g1.id,
                    )
                )
                sweeper.commit()
            finally:
                sweeper.close()
            # چکِ ورکر باید fail سویپر را ببیند. سازوکارِ درست این است که
            # چک با اتصالِ تازه‌ی موتور بخواند، نه با سشنِ ورکر — این را
            # با شمارشِ بازشدنِ اتصال قفل می‌کنیم (روی کدِ قدیمی صفر است).
            real_connect = db.engine.connect
            connect_calls = []

            def _counting_connect(*args, **kwargs):
                connect_calls.append(1)
                return real_connect(*args, **kwargs)

            db.engine.connect = _counting_connect
            try:
                seen = _swept_by_cleaner(worker_gen)
            finally:
                db.engine.connect = real_connect
            check(
                "helper reads via a fresh engine connection",
                len(connect_calls) >= 1,
            )
            check(
                "helper sees the sweep across sessions",
                seen is True,
            )
            delivered = _finalize_delivery(worker_gen)
            db.session.commit()
            check("swept delivery returns False", delivered is False)
            check(
                "swept gen stays failed (no free image)",
                _fresh_gen(g1.id).status == "failed",
            )
            check(
                "output dropped on discard",
                _fresh_gen(g1.id).output_url is None,
            )
            check("no second refund", _refund_rows(g1.id) == 1)
            check("no extra spend", _spend_rows(g1.id) == 1)
            check("balance still 50", _balance(u1.id) == 50)
    finally:
        with app.app_context():
            db.session.remove()
        for suffix in ("", "-wal", "-shm"):
            try:
                os.unlink(db_path + suffix)
            except FileNotFoundError:
                pass

    print(f"\n{PASSED} passed, {FAILED} failed")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
