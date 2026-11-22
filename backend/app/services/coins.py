"""سکه: موجودی، دفتر، رزرو موقع ثبت و برگشت موقع شکست.

قانون: هدیه‌ی ورود (۱ عکس + ۱ صدا) اول مصرف می‌شود، بعد سکه.
پرداخت موقع ثبت job رزرو می‌شود (اتمیک)؛ تحویل موفق آن را قطعی می‌کند و
خروجی ناموفق آن را برمی‌گرداند — پس خروجی ناموفق پولی نمی‌گیرد و دو job
هم‌زمان نمی‌توانند یک اعتبار/موجودی را دوبار مصرف کنند.
"""

from ..extensions import db
from ..models import AppCoinBalance, AppCoinLedger, AppGeneration, AppUser


def get_balance(user):
    row = AppCoinBalance.query.get(user.id)
    return row.balance if row else 0


def _ledger(user_id, delta, reason, ref_type=None, ref_id=None):
    db.session.add(
        AppCoinLedger(
            user_id=user_id, delta=delta, reason=reason,
            ref_type=ref_type, ref_id=ref_id,
        )
    )


def credit_coins(user, amount, reason="purchase", ref_type=None, ref_id=None, commit=True):
    """شارژ سکه با افزایش اتمی در خود دیتابیس (balance = balance + amount).

    باگ قدیمی: خواندن-بعد-نوشتن (row.balance += amount) بود؛ دو تسویه‌ی
    هم‌زمان یک موجودیِ قدیمی را می‌خواندند و آخری روی اولی می‌نوشت و
    شارژِ اولی گم می‌شد. حالا UPDATE یک‌مرحله‌ای است — همان الگوی
    refund_reservation (رفع نسل ۲۳۹۶) — و مقدارِ در حافظه دیگر ملاک
    نیست. برای amount منفی هم همان‌طور کار می‌کند (کسر).
    """
    rows = (
        AppCoinBalance.query.filter(
            AppCoinBalance.user_id == user.id,
        ).update(
            {AppCoinBalance.balance: AppCoinBalance.balance + amount},
            synchronize_session=False,
        )
    )
    if not rows:
        # ردیف موجودی نیست (کاربر تازه، هنوز شارژی نگرفته)؛ ساخته
        # می‌شود تا شارژ گم نشود.
        db.session.add(AppCoinBalance(user_id=user.id, balance=amount))
    _ledger(user.id, amount, reason, ref_type, ref_id)
    if commit:
        db.session.commit()


def adjust_coins(user, delta, reason="admin", ref_type=None, ref_id=None):
    """تعدیل دستی ادمین (مثل wallet-adjustment نورا): مثبت یا منفی.

    منفی نباید موجودی را زیر صفر ببرد.
    """
    if delta == 0:
        return get_balance(user)
    row = AppCoinBalance.query.get(user.id)
    balance = row.balance if row else 0
    if balance + delta < 0:
        raise ValueError("insufficient balance")
    if row is None:
        row = AppCoinBalance(user_id=user.id, balance=0)
    row.balance += delta
    db.session.add(row)
    _ledger(user.id, delta, reason, ref_type, ref_id)
    db.session.commit()
    return row.balance


def can_afford(user, kind, cost):
    """پیش‌بررسی موقع ثبت job: هدیه هست یا سکه کافی است؟"""
    if cost <= 0:
        return True
    if kind == "image" and user.free_image_credits > 0:
        return True
    if kind == "voice" and user.free_voice_credits > 0:
        return True
    return get_balance(user) >= cost


def spend_for_generation(user, generation):
    """شارژ موقع تحویل موفق. False یعنی موجودی ناکافی (نباید با
    پیش‌بررسی can_afford رخ دهد؛ در آن صورت admin پیگیری می‌کند).
    فقط برای jobهای ثبت‌شده قبل از تغییر رزرو استفاده می‌شود؛ jobهای جدید
    موقع ثبت رزرو شده‌اند و موقع تحویل دیگر کسر نمی‌شوند."""
    cost = generation.coin_cost
    if cost <= 0:
        return True
    if generation.kind == "image" and user.free_image_credits > 0:
        user.free_image_credits -= 1
        db.session.add(user)
        db.session.commit()
        return True
    if generation.kind == "voice" and user.free_voice_credits > 0:
        user.free_voice_credits -= 1
        db.session.add(user)
        db.session.commit()
        return True
    row = AppCoinBalance.query.get(user.id)
    if not row or row.balance < cost:
        return False
    row.balance -= cost
    db.session.add(row)
    _ledger(user.id, -cost, "spend", "generation", generation.id)
    db.session.commit()
    return True


def reserve_for_generation(user, kind, cost, generation_id=None):
    """رزرو اتمیک موقع ثبت job. برمی‌گرداند: 'gift' | 'coins' | 'free' | None.

    قبل از این تغییر، ثبت فقط موجودی را چک می‌کرد (can_afford) و کسر موقع
    تحویل بود؛ دو job هم‌زمان با یک اعتبار قبول می‌شدند و هر دو تحویل
    می‌گرفتند (کاربر ۱۵، آبان ۱۴۰۵). رزرو باید اتمیک باشد: یک UPDATE مشروط
    که فقط وقتی سطر را واقعاً تغییر داد موفق است (rowcount) — وگرنه همان
    race فقط از موقع تحویل به موقع ثبت منتقل می‌شود.
    اولویت مثل قبل: هدیه اول، بعد سکه. None یعنی هیچ منبعی هزینه را
    پوشش نمی‌دهد."""
    if cost <= 0:
        return "free"
    gift_col = None
    if kind == "image":
        gift_col = AppUser.free_image_credits
    elif kind == "voice":
        gift_col = AppUser.free_voice_credits
    if gift_col is not None:
        rows = (
            AppUser.query.filter(AppUser.id == user.id, gift_col > 0)
            .update({gift_col: gift_col - 1}, synchronize_session=False)
        )
        if rows:
            db.session.commit()
            return "gift"
    rows = (
        AppCoinBalance.query.filter(
            AppCoinBalance.user_id == user.id,
            AppCoinBalance.balance >= cost,
        ).update(
            {AppCoinBalance.balance: AppCoinBalance.balance - cost},
            synchronize_session=False,
        )
    )
    if rows:
        _ledger(user.id, -cost, "spend", "generation", generation_id)
        db.session.commit()
        return "coins"
    return None


def refund_reservation(user, generation, force=False):
    """برگرداندن رزرو موقع ثبت برای job ناموفق. ایدم‌پوتنت: دقیقاً یک
    بار برمی‌گردد، هر چند مسیر آن را صدا بزند.

    باگ قدیمی (نسل ۲۳۹۵، آبان ۱۴۰۵): سوئیپر (گیرکرده >۱۲۰ ثانیه) و
    ورکر (خطای provider) هر دو با یک شیء قدیمی در حافظه charged=True
    می‌دیدند و هر دو برگشت می‌زدند (+۲۰ به‌جای +۱۰). حالا ادعا
    اتمیک است: یک UPDATE مشروط روی سطر دیتابیس که فقط وقتی سطر را
    واقعاً تغییر داد (rowcount=1) ادامه می‌دهد — همان الگوی
    reserve_for_generation. پرچم در حافظه دیگر ملاک نیست.
    force=True فقط برای _refund_and_drop_generation است: ردیفش چند
    ثانیه‌ای است، هرگز صف نرفته و بلافاصله حذف می‌شود، پس هیچ مسیر
    هم‌روندی نمی‌تواند همان رزرو را برگرداند؛ ولی commitِ ست‌کردن
    charged ممکن است ناموفق بوده باشد (پرچم در DB هنوز False است).
    منبع از روی ledger فهمیده می‌شود: سطر spend برای این generation یعنی
    سکه رزرو شده بود، نبودنش یعنی اعتبار هدیه."""
    if generation.coin_cost <= 0:
        return
    if not force:
        claimed = (
            AppGeneration.query.filter(
                AppGeneration.id == generation.id,
                AppGeneration.charged == True,  # noqa: E712
            ).update(
                {AppGeneration.charged: False},
                synchronize_session=False,
            )
        )
        if not claimed:
            return
    generation.charged = False
    spent = (
        AppCoinLedger.query.filter_by(
            user_id=user.id,
            ref_type="generation",
            ref_id=generation.id,
            reason="spend",
        ).first()
    )
    if spent is not None:
        # افزایش اتمیِ موجودی در خود دیتابیس (balance = balance + cost):
        # ورکر Celery تراکنشِ چنددقیقه‌ای دارد؛ خواندن-بعد-نوشتن با
        # snapshot قدیمی (REPEATABLE READ) تغییرهای هم‌زمان را گم
        # می‌کند — نسل ۲۳۹۶ در ۰۵:۱۹:۵۹ دقیقاً همین‌طور ۱۰ سکه گم کرد
        # (سطر refund نوشته شد ولی موجودی ۱۰ تا کمتر ماند).
        rows = (
            AppCoinBalance.query.filter(
                AppCoinBalance.user_id == user.id,
            ).update(
                {
                    AppCoinBalance.balance: AppCoinBalance.balance
                    + generation.coin_cost
                },
                synchronize_session=False,
            )
        )
        if not rows:
            # ردیف موجودی نیست (نباید برای برگشت رخ دهد — سکه از همین
            # ردیف خرج شده بود)؛ ساخته می‌شود تا برگشت گم نشود.
            db.session.add(
                AppCoinBalance(
                    user_id=user.id, balance=generation.coin_cost
                )
            )
        _ledger(
            user.id,
            generation.coin_cost,
            "refund",
            "generation",
            generation.id,
        )
        db.session.commit()
    elif generation.kind == "image":
        user.free_image_credits += 1
        db.session.add(user)
        db.session.commit()
    elif generation.kind == "voice":
        user.free_voice_credits += 1
        db.session.add(user)
        db.session.commit()
    else:
        return
