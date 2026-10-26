"""سکه: موجودی، دفتر، شارژ موقع تحویل.

قانون: هدیه‌ی ورود (۱ عکس + ۱ صدا) اول مصرف می‌شود، بعد سکه.
سکه فقط وقتی کم می‌شود که خروجی آماده و تحویل داده شده باشد.
"""

from ..extensions import db
from ..models import AppCoinBalance, AppCoinLedger


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


def credit_coins(user, amount, reason="purchase", ref_type=None, ref_id=None):
    row = AppCoinBalance.query.get(user.id)
    if row is None:
        row = AppCoinBalance(user_id=user.id, balance=0)
    row.balance += amount
    db.session.add(row)
    _ledger(user.id, amount, reason, ref_type, ref_id)
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
    پیش‌بررسی can_afford رخ دهد؛ در آن صورت admin پیگیری می‌کند)."""
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
