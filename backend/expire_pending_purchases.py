"""انقضای خریدهای pendingِ قدیمیِ بدون توکن (نسخه‌ی ۱۶).

کرون هر ۳۰ دقیقه روی سرور (از دایرکتوری backend):
  */30 * * * * cd /opt/new-app/backend && /opt/new-app/venv/bin/python expire_pending_purchases.py >> /var/log/chehre-expire-pending.log 2>&1

فقط ردیف‌های pending که توکن ندارند و قدیمی‌تر از ۳۰ دقیقه‌اند حذف
می‌شوند (پرداخت لغوشده)؛ ردیفی که توکن دارد هرگز لمس نمی‌شود.
فایل .env همان دایرکتوری (اگر هست) خوانده می‌شود تا APP_DATABASE_URL
و بقیه‌ی envهای سرویس در دسترس باشند؛ مقدارهای از قبل ست‌شده اولویت دارند.
"""

import json
import os
import sys


def _load_dotenv():
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
    if not os.path.exists(path):
        return
    try:
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, val = line.partition("=")
                key = key.strip()
                val = val.strip().strip('"').strip("'")
                if key and key not in os.environ:
                    os.environ[key] = val
    except OSError:
        pass


# Config مقدارهای env را موقع import می‌خواند؛ پس .env باید پیش از
# import اپ بارگذاری شود.
_load_dotenv()

from app import create_app  # noqa: E402
from app.services.cleanup import expire_stale_pending_purchases  # noqa: E402


def main():
    app = create_app()
    with app.app_context():
        deleted = expire_stale_pending_purchases()
    print(json.dumps({"deleted": deleted}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
