"""ارسال عکس به کانال بله @iNourAi با ربات خود چهره‌ساز.

فقط برای انتشار گالری از ادمین («تاریخچه گالری» -> «انتشار در کانال»).
الگو از نورا (app/api/v1/bale.py): همان API بله
(POST https://tapi.bale.ai/bot<TOKEN>/sendPhoto)، همان کانال.
توکن از env خوانده می‌شود (APP_BALE_BOT_TOKEN) و هرگز در کد،
لاگ یا خطا نمی‌آید؛ تا وقتی ست نشده، انتشار با خطای روشن رد می‌شود.
"""

import logging

import requests
from flask import current_app

log = logging.getLogger(__name__)

BALE_API = "https://tapi.bale.ai"


class BaleNotConfigured(RuntimeError):
    pass


class BaleSendError(RuntimeError):
    pass


def _bale_token():
    token = (current_app.config.get("BALE_BOT_TOKEN") or "").strip()
    if not token:
        raise BaleNotConfigured("bale bot token not configured")
    return token


def send_photo_to_channel(photo_bytes, caption):
    """عکس را در کانال بله می‌فرستد؛ message_id بله را برمی‌گرداند.

    خطا می‌دهد (BaleNotConfigured / BaleSendError) اگر توکن ست نشده
    باشد یا بله قبول نکند — در آن صورت چیزی ثبت نمی‌شود.
    """
    token = _bale_token()
    url = f"{BALE_API}/bot{token}/sendPhoto"
    try:
        resp = requests.post(
            url,
            data={
                "chat_id": current_app.config.get("BALE_CHANNEL", "@iNourAi"),
                "caption": caption or "",
            },
            files={"photo": ("image.jpg", photo_bytes, "image/jpeg")},
            timeout=60,
        )
        data = resp.json()
    except Exception as exc:  # noqa: BLE001 - transport or non-JSON reply
        raise BaleSendError(f"bale sendPhoto failed: {exc}") from exc
    if not isinstance(data, dict) or not data.get("ok"):
        raise BaleSendError(f"bale sendPhoto failed: {data}")
    result = data.get("result") or {}
    mid = result.get("message_id")
    return str(mid) if mid is not None else None
