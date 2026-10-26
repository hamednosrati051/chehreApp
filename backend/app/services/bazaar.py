"""تایید خرید بازار (سروری) — فقط با کلید RSA خود اپ.

قوانین:
- بدون کلید RSA پیکربندی‌شده، تایید همیشه fail می‌شود؛ خرید pending
  می‌ماند تا کلید ست شود و بازیابی (recovery) آن را وریفای کند.
- purchase_token یکتا است: یک توکن هرگز دوبار شارژ نمی‌شود
  (idempotency) — خرید تکراری همان ردیف قبلی را برمی‌گرداند.
- بعد از وریفای موفق، سکه شارژ و خرید consumed می‌شود تا همان پک
  بلافاصله دوباره قابل خرید باشد (پک‌ها consumable هستند).
"""

import base64
import json

from flask import current_app

from ..extensions import db
from ..models import AppCoinPack, AppPurchase
from . import coins as coins_svc


def _load_public_key():
    """کلید عمومی بازار: یا PEM کامل، یا base64 خام DER (فرمت پنل بازار)."""
    from cryptography.hazmat.backends import default_backend
    from cryptography.hazmat.primitives.serialization import load_der_public_key
    from cryptography.hazmat.primitives.serialization import load_pem_public_key

    raw = (current_app.config.get("BAZAAR_RSA_PUBLIC_KEY") or "").strip()
    if not raw:
        raise RuntimeError("bazaar RSA public key is not configured")
    if raw.startswith("-----BEGIN"):
        return load_pem_public_key(raw.encode(), backend=default_backend())
    return load_der_public_key(base64.b64decode(raw), backend=default_backend())


def verify_signature(purchase_data: str, signature_b64: str) -> bool:
    """امضای خرید بازار را با کلید RSA خود اپ بررسی می‌کند.

    بازار (مثل Google Play IAB) داده‌ی خرید را با RSA/SHA1 امضا می‌کند.
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


def verify_purchase(user, pack, purchase_token, purchase_data, signature):
    """خرید را وریفای و (فقط یک بار) سکه شارژ می‌کند.

    برمی‌گرداند: (purchase, already_credited: bool).
    خطا می‌دهد (ValueError) اگر امضا نامعتبر باشد یا داده با پک نخواند؛
    در آن صورت خرید pending می‌ماند و سکه‌ای شارژ نمی‌شود.
    """
    if not purchase_token:
        raise ValueError("purchase_token required")

    # Idempotency: همین توکن قبلاً وریفای/مصرف شده؟ شارژ مجدد ممنوع.
    existing = AppPurchase.query.filter_by(
        bazaar_purchase_token=purchase_token
    ).first()
    if existing is not None and existing.status in ("verified", "consumed"):
        return existing, True
    if existing is not None and existing.user_id != user.id:
        raise ValueError("purchase token belongs to another user")

    # اگر پک داده نشده ولی خرید pending از قبل هست، از همان ردیف بردار.
    if pack is None and existing is not None:
        pack = AppCoinPack.query.get(existing.pack_id)

    # داده‌ی خرید (اگر داده شد) باید با SKU همین پک بخواند.
    if purchase_data:
        try:
            data = json.loads(purchase_data)
        except ValueError:
            raise ValueError("purchase_data is not valid JSON")
        sku = data.get("productId") or data.get("sku")
        if sku and pack is not None and sku != pack.bazaar_sku:
            raise ValueError("purchase sku does not match pack")
        token_in_data = data.get("purchaseToken") or data.get("token")
        if token_in_data and token_in_data != purchase_token:
            raise ValueError("purchase token mismatch")

    if not signature or not verify_signature(purchase_data or "", signature):
        raise ValueError("invalid bazaar signature")

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
        db.session.commit()

    coins_svc.credit_coins(
        user, pack.coins if pack else 0,
        reason="purchase", ref_type="purchase", ref_id=existing.id,
    )
    # credit_coins خودش commit می‌کند؛ بعد وضعیت را consumed می‌کنیم.
    existing.status = "consumed"
    existing.verified_at = db.func.now()
    db.session.add(existing)
    db.session.commit()
    return existing, False
