"""ارسال OTP با سرویس ملی‌پیامک — با مشخصات خود اپ.

نام کاربری، رمز و body/template از env خود اپ می‌آید.
هیچ مقداری از تنظیمات نورا کپی یا خوانده نشده.
"""

import requests
from flask import current_app


def send_otp_sms(mobile, code):
    url = current_app.config["SMS_API_URL"]
    if not url:
        raise RuntimeError("APP_SMS_API_URL is not configured")
    payload = {
        "username": current_app.config["SMS_USERNAME"],
        "password": current_app.config["SMS_PASSWORD"],
        "to": mobile,
        "from": current_app.config.get("SMS_SENDER", ""),
        "text": f"کد ورود شما: {code}",
        "bodyId": current_app.config.get("SMS_BODY_ID", ""),
    }
    # TODO (integration): شکل دقیق endpoint و payload ملی‌پیامک را
    # موقع تجهیز مشخصات پیامک خود اپ نهایی کن.
    resp = requests.post(url, json=payload, timeout=15)
    resp.raise_for_status()
    return True
