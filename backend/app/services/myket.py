"""تایید خرید مایکت (سروری) — فقط با کلید RSA خود اپ.

قوانین:
- بدون کلید RSA پیکربندی‌شده، تایید همیشه fail می‌شود؛ خرید pending
  می‌ماند تا کلید ست شود و بازیابی (recovery) آن را وریفای کند.
- purchase_token یکتا است (قید دیتابیسی + برگرداندن برنده در race):
  یک توکن هرگز دوبار شارژ نمی‌شود (idempotency) — خرید تکراری همان ردیف
  قبلی را برمی‌گرداند.
- فقط خرید با purchaseState==0 (خرید موفق) وریفای می‌شود؛ خرید لغوشده
  یا برگشتی رد می‌شود.
- بعد از وریفای موفق، سکه شارژ و خرید consumed می‌شود تا همان پک
  بلافاصله دوباره قابل خرید باشد (پک‌ها consumable هستند).
"""

import base64
import json

from flask import current_app
from sqlalchemy.exc import IntegrityError

from ..extensions import db
from ..models import AppCoinPack, AppPurchase
from . import coins as coins_svc


def _load_public_key():
    """کلید عمومی مایکت: یا PEM کامل، یا base64 خام DER (فرمت پنل مایکت)."""
    from cryptography.hazmat.backends import default_backend
    from cryptography.hazmat.primitives.serialization import load_der_public_key
    from cryptography.hazmat.primitives.serialization import load_pem_public_key

    raw = (current_app.config.get("MYKET_RSA_PUBLIC_KEY") or "").strip()
    if not raw:
        raise RuntimeError("myket RSA public key is not configured")
    if raw.startswith("-----BEGIN"):
        return load_pem_public_key(raw.encode(), backend=default_backend())
    return load_der_public_key(base64.b64decode(raw), backend=default_backend())


def verify_signature(purchase_data: str, signature_b64: str) -> bool:
    """امضای خرید مایکت را با کلید RSA خود اپ بررسی می‌کند.

    مایکت (مثل Google Play IAB) داده‌ی خرید را با RSA/SHA1 امضا می‌کند.
    """
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding

    try:
        key = _load_public_key()
        key.verify(
            base64.b64decode(signature_b64),
            purchase_data.encode("utf-8"),
            padding.PKCS1v15(),
            hashes.SHA1(),
        )
        return True
    except (InvalidSignature, ValueError, RuntimeError):
        return False
    except Exception:
        return False


def verify_purchase(user, pack, purchase_token, purchase_data, signature,
                    pending_id=None):
    """خرید را وریفای و (فقط یک بار) سکه شارژ می‌کند.

    برمی‌گرداند: (purchase, already_credited: bool, gift_granted: int).
    هدیه‌ی خرید (v11): فقط در وریفای تازه (نه مسیر already_credited) و فقط
    وقتی پک gift_coins>0 دارد؛ در همان تراکنش شارژ می‌شود.
    خطا می‌دهد (ValueError) اگر امضا نامعتبر باشد یا داده با پک نخواند؛
    در آن صورت خرید pending می‌ماند و سکه‌ای شارژ نمی‌شود.
    نسخه‌ی ۱۶ (pending_id، اختیاری): اگر توکن هنوز ردیفی ندارد و
    pending_id داده شده، همان ردیف pending به توکن وصل می‌شود
    (به‌جای ساختن ردیف تازه) — ردیفی که اپ موقع tap-buy ساخته بود.
    """
    if not purchase_token:
        raise ValueError("purchase_token required")

    # Idempotency: همین توکن قبلاً وریفای/مصرف شده؟ شارژ مجدد ممنوع.
    # ترتیب id ثابت است تا اگر به هر دلیلی ردیف تکراری بود، همیشه
    # قدیمی‌ترین (همان که مصرف شده) برگردد.
    existing = (
        AppPurchase.query.filter_by(bazaar_purchase_token=purchase_token)
        .order_by(AppPurchase.id)
        .first()
    )
    # مالکیت توکن مقدم بر وضعیت آن است: توکن مصرف‌شده‌ی کاربر دیگر
    # نباید برای کاربر فعلی «موفق» برگردد.
    if existing is not None and existing.user_id != user.id:
        raise ValueError("purchase token belongs to another user")
    if (
        existing is not None
        and existing.deleted_at is not None
        and existing.status in ("verified", "consumed")
    ):
        # حذف منطقی (۲۰۲۶-۱۱-۲۲): ردیف مصرف‌شده‌ی حذف‌شده برگردد.
        # (ردیف مصرف‌شده یعنی قبلاً تسویه شده؛ شارژ مجدد نیست.)
        existing.deleted_at = None
        db.session.commit()
        return existing, True, 0
    if existing is not None and existing.status in ("verified", "consumed"):
        return existing, True, 0

    # نسخه‌ی ۱۶: وصل‌کردن ردیف pendingِ از قبل ساخته‌شده به این توکن.
    # فقط وقتی توکن هنوز ردیفی ندارد؛ idempotency توکن دست نمی‌خورد.
    if existing is None and pending_id is not None:
        pending = AppPurchase.query.get(pending_id)
        if pending is None:
            raise ValueError("pending purchase not found")
        if pending.user_id != user.id:
            raise ValueError("pending purchase belongs to another user")
        if pending.status != "pending":
            raise ValueError("pending purchase is not pending")
        if (
            pending.bazaar_purchase_token
            and pending.bazaar_purchase_token != purchase_token
        ):
            raise ValueError("pending purchase token mismatch")
        if pack is not None and pack.id != pending.pack_id:
            raise ValueError("pending purchase pack mismatch")
        existing = pending

    # اگر پک داده نشده ولی خرید pending از قبل هست، از همان ردیف بردار.
    if pack is None and existing is not None:
        pack = AppCoinPack.query.get(existing.pack_id)

    # داده‌ی خرید (اگر داده شد) باید با SKU همین پک بخواند.
    if purchase_data:
        try:
            data = json.loads(purchase_data)
        except ValueError:
            raise ValueError("purchase_data is not valid JSON")
        # فقط خرید موفق (state==0) وریفای می‌شود؛ لغوشده (1) یا برگشتی
        # (2) با امضای معتبر هم نباید سکه بدهد.
        state = data.get("purchaseState")
        if state is not None:
            try:
                state = int(state)
            except (TypeError, ValueError):
                raise ValueError("invalid purchaseState")
            if state != 0:
                raise ValueError("purchase not completed")
        sku = data.get("productId") or data.get("sku")
        if sku and pack is not None and sku != pack.bazaar_sku:
            raise ValueError("purchase sku does not match pack")
        token_in_data = data.get("purchaseToken") or data.get("token")
        if token_in_data and token_in_data != purchase_token:
            raise ValueError("purchase token mismatch")

    if not signature or not verify_signature(purchase_data or "", signature):
        raise ValueError("invalid myket signature")

    # حذف منطقی (۲۰۲۶-۱۱-۲۲): امضا درست است — ردیف حذف‌شده برگردد.
    # پاک‌کردنِ deleted_at با commitِ تسویه در انتها ذخیره می‌شود.
    # این پاک‌کردن عمداً بعد از وریفای امضاست: اگر امضا نامعتبر بود،
    # هیچ تغییری در سشن نیست تا commitِ لاگِ وریفای آن را ذخیره کند —
    # پس وریفای ناموفق ردیف را برنمی‌گرداند.
    if existing is not None and existing.deleted_at is not None:
        existing.deleted_at = None

    if existing is None:
        if pack is None:
            raise ValueError("pack required for new purchase")
        existing = AppPurchase(
            user_id=user.id,
            pack_id=pack.id,
            bazaar_purchase_token=purchase_token,
            amount_irr=pack.price_toman * 10,
            status="pending",
        )
        db.session.add(existing)
        try:
            db.session.commit()
        except IntegrityError:
            # Race: یک وریفای هم‌زمان همین توکن را اول ثبت کرد. برنده را
            # برگردان تا ۵۰۰ نشود؛ اگر هنوز pending است یعنی برنده دارد
            # شارژ می‌کند — دوباره شارژ نکن، pending برگردان تا مشتری
            # بعداً (recovery) همان ردیف مصرف‌شده را بگیرد.
            db.session.rollback()
            existing = (
                AppPurchase.query.filter_by(
                    bazaar_purchase_token=purchase_token
                )
                .order_by(AppPurchase.id)
                .first()
            )
            if existing is None:
                raise
            if existing.user_id != user.id:
                raise ValueError("purchase token belongs to another user")
            if existing.status in ("verified", "consumed"):
                return existing, True, 0
            return existing, False, 0

    # شارژ سکه و تغییر وضعیت در یک تراکنش: اگر بین آن دو خطا رخ دهد،
    # نه سکه‌ای بی‌سند شارژ می‌شود و نه خریدی pending می‌ماند که بعداً
    # دوباره شارژ شود.
    # نسخه‌ی ۱۶: ردیف pendingِ وصل‌شده اینجا توکنش را می‌گیرد.
    if not existing.bazaar_purchase_token:
        existing.bazaar_purchase_token = purchase_token
    coins_svc.credit_coins(
        user, pack.coins if pack else 0,
        reason="purchase", ref_type="purchase", ref_id=existing.id,
        commit=False,
    )
    # هدیه‌ی خرید (v11): فقط همین‌جا (وریفای تازه)، در همان تراکنش.
    # scope 'every' = هر خرید این پک؛ 'first' = فقط اولین خرید وریفای‌شده‌ی
    # همین پک برای همین کاربر (ردیف فعلی هنوز pending است، پس در شمار نمی‌آید).
    gift_granted = 0
    gift_coins = getattr(pack, "gift_coins", 0) or 0
    if gift_coins > 0 and pack is not None:
        gift_scope = getattr(pack, "gift_scope", "every") or "every"
        grant = gift_scope != "first"
        if not grant:
            prior = (
                AppPurchase.query.filter_by(user_id=user.id, pack_id=pack.id)
                .filter(AppPurchase.status.in_(("verified", "consumed")))
                .filter(AppPurchase.id != existing.id)
                .first()
            )
            grant = prior is None
        if grant:
            coins_svc.credit_coins(
                user, gift_coins,
                reason="gift", ref_type="purchase", ref_id=existing.id,
                commit=False,
            )
            gift_granted = gift_coins
    existing.status = "consumed"
    existing.verified_at = db.func.now()
    db.session.add(existing)
    db.session.commit()
    return existing, False, gift_granted
