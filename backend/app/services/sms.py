"""ارسال OTP با ملی‌پیامک — الگوی اثبات‌شده‌ی نورا (BaseServiceNumber).

POST {APP_SMS_API_URL}/api/SendSMS/BaseServiceNumber با فرم (data=):
username, password, text=code, to, bodyId.
موفقیت: HTTP 200 و RetStatus==1؛ بقیه‌اش خطاست.
APP_SMS_API_URL همان base پنل است (مثل base نورا)، نه URL کامل endpoint.
"""

import requests
from flask import current_app


def send_otp_sms(mobile, code):
    base = (current_app.config["SMS_API_URL"] or "").rstrip("/")
    username = current_app.config["SMS_USERNAME"]
    password = current_app.config["SMS_PASSWORD"]
    body_id = current_app.config["SMS_BODY_ID"]
    if not base:
        raise RuntimeError("APP_SMS_API_URL is not configured")
    if not username or not password or not body_id:
        raise RuntimeError("SMS credentials/bodyId are not configured")
    resp = requests.post(
        f"{base}/api/SendSMS/BaseServiceNumber",
        data={
            "username": username,
            "password": password,
            "text": code,
            "to": mobile,
            "bodyId": body_id,
        },
        timeout=15,
    )
    resp.raise_for_status()
    try:
        payload = resp.json()
    except ValueError:
        raise RuntimeError("sms gateway non-JSON response")
    if int(payload.get("RetStatus", 0) or 0) != 1:
        raise RuntimeError(
            f"sms rejected: {payload.get('StrRetStatus', '')}"[:200]
        )
    return True
