"""ارسال پوش‌نوتیفیکیشن با Firebase Cloud Messaging (FCM v1).

بدون google-auth: امضای RS256 JWT دستی با cryptography و تبادل آن
با access token از راه requests. کلید service account هرگز در کد
نیست و هرگز لاگ/برگردانده نمی‌شود؛ فقط مسیر فایل آن از
APP_FCM_SERVICE_ACCOUNT_FILE (نام env) خوانده می‌شود، آن هم در
لحظه‌ی ارسال، نه در import.

وقتی کلید پیکربندی نشده/خوانا/معتبر نیست، FcmNotConfigured بالا
می‌آید تا روت آن را به HTTP 503 با {"error": "fcm_not_configured"}
تبدیل کند. پیام‌ها data-only هستند (بدون notification) تا اپ خودش
نمایششان را کنترل کند. توکن‌های منقضی‌شده (UNREGISTERED یا
INVALID_ARGUMENT) حذف نمی‌شوند — فقط در stale_tokens برگردانده
می‌شوند تا روت آن‌ها را پاک کند.
"""

import base64
import json
import time

import requests
from flask import current_app

_FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
_TOKEN_URL = "https://oauth2.googleapis.com/token"

# حافظه‌ی موقت access token: (token, expires_at_epoch). انقضا ۵۵
# دقیقه‌ای است تا کمی پیش از انقضای واقعی (۶۰ دقیقه) تازه شود.
_cached_token = None
_cached_token_expires_at = 0


class FcmNotConfigured(Exception):
    """کلید service account فایربیس پیکربندی نشده یا خوانا/معتبر نیست."""


def _load_service_account():
    """خواندن فایل کلید در لحظه‌ی ارسال؛ مسیر از config می‌آید."""
    path = (current_app.config.get("FCM_SERVICE_ACCOUNT_FILE") or "").strip()
    if not path:
        raise FcmNotConfigured("FCM service account is not configured")
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        raise FcmNotConfigured("FCM service account file is unreadable")
    if not isinstance(data, dict):
        raise FcmNotConfigured("FCM service account file is invalid")
    project_id = data.get("project_id")
    client_email = data.get("client_email")
    private_key = data.get("private_key")
    if not project_id or not client_email or not private_key:
        raise FcmNotConfigured("FCM service account file is incomplete")
    return project_id, client_email, private_key


def _b64url(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def _signed_jwt(client_email, private_key):
    """ساخت JWT با RS256 برای تبادل با access token."""
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding

    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    claims = {
        "iss": client_email,
        "scope": _FCM_SCOPE,
        "aud": _TOKEN_URL,
        "exp": now + 3600,
        "iat": now,
    }
    signing_input = (
        _b64url(json.dumps(header, separators=(",", ":")).encode("utf-8"))
        + "."
        + _b64url(json.dumps(claims, separators=(",", ":")).encode("utf-8"))
    ).encode("ascii")
    try:
        key = serialization.load_pem_private_key(
            private_key.encode("utf-8"), password=None
        )
    except (ValueError, TypeError):
        raise FcmNotConfigured("FCM service account private key is invalid")
    signature = key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())
    return signing_input.decode("ascii") + "." + _b64url(signature)


def _get_access_token(client_email, private_key):
    """access token کش‌شده یا تازه (تبادل JWT با گوگل)."""
    global _cached_token, _cached_token_expires_at
    if _cached_token and time.time() < _cached_token_expires_at:
        return _cached_token
    jwt_assertion = _signed_jwt(client_email, private_key)
    resp = requests.post(
        _TOKEN_URL,
        data={
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": jwt_assertion,
        },
        timeout=15,
    )
    resp.raise_for_status()
    payload = resp.json()
    token = payload.get("access_token")
    if not token:
        raise RuntimeError("fcm token exchange returned no access token")
    expires_in = payload.get("expires_in") or 3600
    try:
        expires_in = int(expires_in)
    except (TypeError, ValueError):
        expires_in = 3600
    _cached_token = token
    # ۵ دقیقه زودتر از انقضا منقضی می‌شود (~۵۵ دقیقه برای ۳۶۰۰ ثانیه).
    _cached_token_expires_at = time.time() + max(expires_in - 300, 60)
    return token


def _invalidate_cached_token():
    global _cached_token, _cached_token_expires_at
    _cached_token = None
    _cached_token_expires_at = 0


def _send_one(project_id, access_token, device_token, title, body):
    """ارسال یک پیام data-only؛ (ok, error_status, http_status) برمی‌گرداند."""
    url = (
        f"https://fcm.googleapis.com/v1/projects/{project_id}/messages:send"
    )
    resp = requests.post(
        url,
        headers={
            "Authorization": f"Bearer {access_token}",
            "Content-Type": "application/json",
        },
        json={
            "message": {
                "token": device_token,
                "data": {"title": title, "body": body},
            }
        },
        timeout=15,
    )
    if resp.status_code == 200:
        return True, None, resp.status_code
    status = None
    try:
        status = (resp.json().get("error") or {}).get("status")
    except ValueError:
        status = None
    return False, status, resp.status_code


def send_push_to_tokens(tokens, title, body):
    """ارسال پوش به فهرست توکن‌ها.

    برمی‌گرداند: {"sent": n, "failed": m, "total": t,
    "stale_tokens": [...]} که stale_tokens توکن‌هایی‌اند که FCM
    آن‌ها را UNREGISTERED یا INVALID_ARGUMENT دانسته و روت باید
    آن‌ها را از app_device_tokens پاک کند. خود این تابع چیزی حذف
    نمی‌کند. در صورت 401، access token یک بار تازه و همان پیام
    دوباره فرستاده می‌شود. مقدار هیچ توکن یا کلیدی لاگ/برگردانده
    نمی‌شود (فقط شمارش).
    """
    tokens = list(tokens or [])
    project_id, client_email, private_key = _load_service_account()
    access_token = _get_access_token(client_email, private_key)
    sent = failed = 0
    stale_tokens = []
    for device_token in tokens:
        ok, status, http_status = _send_one(
            project_id, access_token, device_token, title, body
        )
        if not ok and http_status == 401:
            # access token منقضی/نامعتبر: یک بار تازه می‌کنیم و همان
            # پیام را دوباره می‌فرستیم؛ شکست دوم، شکست نهایی است.
            _invalidate_cached_token()
            access_token = _get_access_token(client_email, private_key)
            ok, status, _ = _send_one(
                project_id, access_token, device_token, title, body
            )
        if ok:
            sent += 1
        else:
            failed += 1
            if status in ("UNREGISTERED", "INVALID_ARGUMENT"):
                stale_tokens.append(device_token)
    return {
        "sent": sent,
        "failed": failed,
        "total": len(tokens),
        "stale_tokens": stale_tokens,
    }
