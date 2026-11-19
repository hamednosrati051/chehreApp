"""وریفای مجدد خریدهای زیبالِ pending/failed با trackId (فیکس باگ ۷۵۴).

کرون هر ۲۰ دقیقه روی سرور (از دایرکتوری backend):
  */20 * * * * cd /opt/new-app/backend && /opt/new-app/venv/bin/python reconcile_zibal_purchases.py >> /var/log/chehre-reconcile-zibal.log 2>&1

خریدهای زیبالِ قدیمی‌تر از ۱۰ دقیقه و تازه‌تر از ۱ ساعت دوباره وریفای
می‌شوند — یعنی هر خرید درانتظار تو اون ۱ ساعت دست‌کم دوبار چک می‌شه؛
اگر پرداخت شده بود تسویه می‌شوند. خطای ارتباط ردیف را
دست نمی‌زند. فایل .env همان دایرکتوری (اگر هست) خوانده می‌شود تا
APP_DATABASE_URL و بقیه‌ی envهای سرویس در دسترس باشند؛ مقدارهای
از قبل ست‌شده اولویت دارند.
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
from app.services.cleanup import reconcile_zibal_purchases  # noqa: E402


def main():
    app = create_app()
    with app.app_context():
        checked, settled = reconcile_zibal_purchases()
    print(
        json.dumps(
            {"checked": checked, "settled": settled}, ensure_ascii=False
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
