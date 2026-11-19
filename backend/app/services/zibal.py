"""درگاه زیبال (نسخه‌ی ۱۷) — همان مرچنت نورا، با کلیدهای جدا در env خود اپ.

پورت adapter نورا (providers/zibal.py) با requests به‌جای httpx؛
همه‌ی قواعد ایمنی همان است: موفقیت فقط بعد از وریفای سروری با
trackId + مبلغ یکسان؛ تکرار callback هرگز دوبار شارژ نمی‌کند
(settle با بررسی وضعیت ردیف idempotent است، مثل bazaar).
"""

import logging
import time

import requests
from flask import current_app

from ..extensions import db
from ..models import AppCoinPack, AppPurchase
from . import coins as coins_svc

log = logging.getLogger(__name__)

_CREATE_PATH = "/request"
_VERIFY_PATH = "/verify"
_START_URL_TEMPLATE = "https://gateway.zibal.ir/start/{track_id}"
_REQUEST_TIMEOUT_SECONDS = 15
_MAX_RETRIES = 2
_BACKOFF_SECONDS = (1, 3)

_RESULT_OK = 100
_RESULT_ALREADY_VERIFIED = 201


class ZibalNotConfigured(RuntimeError):
    pass


class ZibalError(RuntimeError):
    """خطای درگاه (پاسخ غیرمنتظره یا قطع ارتباط)؛ پیامش هرگز به کاربر نمی‌رسد."""


def _config():
    cfg = current_app.config
    return (
        (cfg.get("ZIBAL_API_BASE_URL") or "https://gateway.zibal.ir/v1").rstrip("/"),
        cfg.get("ZIBAL_MERCHANT") or "",
        cfg.get("ZIBAL_CALLBACK_URL") or "",
    )


def _post(url, body):
    """POST با ۱۵ ثانیه timeout و ۲ retry روی 5xx/timeout (مثل نورا)."""
    last_exc = None
    for attempt in range(_MAX_RETRIES + 1):
        try:
            resp = requests.post(
                url,
                json=body,
                headers={"Referer": "https://inourai.ir/"},
                timeout=_REQUEST_TIMEOUT_SECONDS,
            )
            if resp.status_code >= 500 and attempt < _MAX_RETRIES:
                time.sleep(_BACKOFF_SECONDS[min(attempt, len(_BACKOFF_SECONDS) - 1)])
                continue
            try:
                return resp.json()
            except ValueError as exc:
                raise ZibalError("zibal bad response: {}".format(exc)) from exc
        except (requests.Timeout, requests.ConnectionError) as exc:
            last_exc = exc
            if attempt < _MAX_RETRIES:
                time.sleep(_BACKOFF_SECONDS[min(attempt, len(_BACKOFF_SECONDS) - 1)])
                continue
            break
    raise ZibalError("zibal unreachable: {}".format(last_exc))


def create_payment(amount_irr, callback_url, metadata):
    """ساخت پرداخت زیبال؛ برمی‌گرداند (track_id: str, payment_url: str)."""
    base_url, merchant, _ = _config()
    if not merchant or not base_url:
        raise ZibalNotConfigured("zibal merchant is not configured")
    if not isinstance(amount_irr, int) or amount_irr <= 0:
        raise ValueError("amount_irr must be a positive integer")
    body = {
        "merchant": merchant,
        "amount": amount_irr,
        "callbackUrl": callback_url,
        "description": "ChehreSaz pack {} (purchase {}, user {})".format(
            metadata.get("pack_name", ""),
            metadata.get("purchase_id", ""),
            metadata.get("user_id", ""),
        ),
        "orderId": str(metadata.get("purchase_id") or ""),
    }
    mobile = metadata.get("mobile")
    if mobile:
        body["mobile"] = str(mobile)
    data = _post("{}{}".format(base_url, _CREATE_PATH), body)
    result = data.get("result")
    track_id = data.get("trackId")
    if result != _RESULT_OK or not track_id:
        raise ZibalError(
            "zibal request failed: result={} message={}".format(
                result, data.get("message")
            )
        )
    track_id = str(track_id)
    return track_id, _START_URL_TEMPLATE.format(track_id=track_id)


def verify_payment(track_id, amount_irr):
    """وریفای سروری؛ برمی‌گرداند dict(paid, ref_number, error_code).
    paid فقط وقتی True است که result ۱۰۰/۲۰۱ باشد و مبلغ پاسخ وریفای
    با مبلغ ردیف یکی باشد (قانون نورا)."""
    base_url, merchant, _ = _config()
    if not merchant or not base_url:
        raise ZibalNotConfigured("zibal merchant is not configured")
    if not track_id:
        raise ValueError("track_id is required")
    data = _post(
        "{}{}".format(base_url, _VERIFY_PATH),
        {"merchant": merchant, "trackId": int(track_id)},
    )
    result = data.get("result")
    paid = result in (_RESULT_OK, _RESULT_ALREADY_VERIFIED)
    gateway_amount = data.get("amount")
    try:
        gateway_amount = int(gateway_amount) if gateway_amount is not None else None
    except (TypeError, ValueError):
        gateway_amount = None
    if paid and gateway_amount is not None and gateway_amount != amount_irr:
        log.warning(
            "zibal verify amount mismatch: expected %s got %s",
            amount_irr,
            gateway_amount,
        )
        paid = False
    return {
        "paid": paid,
        "ref_number": str(data.get("refNumber") or "") or None,
        "error_code": str(result) if not paid else None,
        "error_message": str(data.get("message") or "") or None,
    }


def settle_purchase(user, pack, purchase):
    """تسویه‌ی خرید زیبال (دقیقاً یک بار): شارژ سکه + هدیه + consumed.
    برمی‌گرداند (purchase, already_credited, gift_granted) — همان قرارداد
    bazaar.verify_purchase؛ اگر ردیف قبلاً تسویه شده، شارژ مجدد نمی‌شود.
    منطق هدیه عین bazaar.py است (scope 'first' = فقط اولین خرید
    وریفای‌شده‌ی همین پک برای همین کاربر، بدون شمردن ردیف فعلی)."""
    if purchase.status in ("verified", "consumed"):
        return purchase, True, 0
    if pack is None:
        pack = AppCoinPack.query.get(purchase.pack_id)
    coins_svc.credit_coins(
        user,
        pack.coins if pack else 0,
        reason="purchase",
        ref_type="purchase",
        ref_id=purchase.id,
        commit=False,
    )
    gift_granted = 0
    gift_coins = getattr(pack, "gift_coins", 0) or 0
    if gift_coins > 0 and pack is not None:
        gift_scope = getattr(pack, "gift_scope", "every") or "every"
        grant = gift_scope != "first"
        if not grant:
            prior = (
                AppPurchase.query.filter_by(user_id=user.id, pack_id=pack.id)
                .filter(AppPurchase.status.in_(("verified", "consumed")))
                .filter(AppPurchase.id != purchase.id)
                .first()
            )
            grant = prior is None
        if grant:
            coins_svc.credit_coins(
                user,
                gift_coins,
                reason="gift",
                ref_type="purchase",
                ref_id=purchase.id,
                commit=False,
            )
            gift_granted = gift_coins
    purchase.status = "consumed"
    purchase.verified_at = db.func.now()
    db.session.add(purchase)
    db.session.commit()
    return purchase, False, gift_granted
