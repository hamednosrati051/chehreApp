import os


class Config:
    # همه از env خود اپ. هیچ‌چیز از .env نورا خوانده نمی‌شود.
    SECRET_KEY = os.environ.get("APP_SECRET_KEY", "dev-only-change-me")
    SQLALCHEMY_DATABASE_URI = os.environ.get(
        "APP_DATABASE_URL",
        # placeholder؛ در محیط واقعی حتماً با env ست شود.
        "mysql+pymysql://user:pass@127.0.0.1:3306/new_app",
    )
    SQLALCHEMY_TRACK_MODIFICATIONS = False

    CELERY_BROKER_URL = os.environ.get(
        "APP_CELERY_BROKER_URL", "redis://127.0.0.1:6379/1"
    )
    CELERY_RESULT_BACKEND = os.environ.get(
        "APP_CELERY_RESULT_BACKEND", "redis://127.0.0.1:6379/1"
    )

    # پیامک خود اپ (MeliPayamak) — مشخصات را حامد در تنظیمات خود اپ می‌دهد.
    SMS_API_URL = os.environ.get("APP_SMS_API_URL", "")
    SMS_USERNAME = os.environ.get("APP_SMS_USERNAME", "")
    SMS_PASSWORD = os.environ.get("APP_SMS_PASSWORD", "")
    SMS_SENDER = os.environ.get("APP_SMS_SENDER", "")
    SMS_BODY_ID = os.environ.get("APP_SMS_BODY_ID", "")

    # فضای ذخیره‌سازی خود اپ (S3-compatible، باکت جدا از نورا).
    S3_ENDPOINT = os.environ.get("APP_S3_ENDPOINT", "")
    S3_BUCKET = os.environ.get("APP_S3_BUCKET", "")
    S3_ACCESS_KEY = os.environ.get("APP_S3_ACCESS_KEY", "")
    S3_SECRET_KEY = os.environ.get("APP_S3_SECRET_KEY", "")
    S3_PUBLIC_BASE = os.environ.get("APP_S3_PUBLIC_BASE", "")

    # کلید RSA عمومی خود اپ از پنل بازار خود اپ (برای تایید خرید).
    # فقط placeholder؛ مقدار واقعی را حامد در تنظیمات خود اپ می‌دهد.
    # تا وقتی ست نشده، تایید خرید با خطای روشن fail می‌شود — هرگز
    # بدون تایید، سکه شارژ نمی‌شود.
    BAZAAR_RSA_PUBLIC_KEY = os.environ.get("APP_BAZAAR_RSA_PUBLIC_KEY", "")

    # توکن ادمین خود اپ (هدر X-Admin-Token). تا وقتی ست نشده،
    # همه‌ی endpointهای ‎/admin بسته‌اند (fail-closed).
    ADMIN_TOKEN = os.environ.get("APP_ADMIN_TOKEN", "")

    # حالت تست (پیش از انتشار در بازار): شارژ تستی سکه بدون پرداخت،
    # فقط با توکن ادمین. با APP_TEST_GRANTS_ENABLED=false بسته می‌شود
    # (مثلاً بعد از انتشار). پرداخت واقعی همیشه فقط بازار است.
    TEST_GRANTS_ENABLED = os.environ.get("APP_TEST_GRANTS_ENABLED", "true").lower() in (
        "1", "true", "yes",
    )
