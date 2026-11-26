"""تلاش مجدد آپلود S3 (رفع ۲۰۲۶-۱۱-۲۶ — تایم‌اوت خواندن OVH):

put_object گاهی با «Read timed out» می‌مرد و کل تولید ناموفق می‌شد، در
حالی که عکس ساخته شده بود. حالا upload_bytes حداکثر ۳ تلاش می‌کند
(فاصله‌ی ۲ و ۵ ثانیه، هر تلاش حداکثر ۶۰ ثانیه).

اجرا:
    /tmp/chehre-test-venv/bin/python backend/tests/test_storage_retry.py

فقط دیتابیسِ موقتِ SQLite و پوشه‌ی موقت؛ پوشه‌ی واقعی static/uploads
دست نمی‌خورد.
"""

import os
import sys
import tempfile
from unittest.mock import patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import boto3  # noqa: E402
from botocore.exceptions import ReadTimeoutError  # noqa: E402

from app import create_app  # noqa: E402
from app.config import Config  # noqa: E402
from app.services import storage as storage_svc  # noqa: E402

PASSED = 0
FAILED = 0


def check(name, cond, extra=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  ok: {name}")
    else:
        FAILED += 1
        print(f"  FAIL: {name} {extra}")


class TestConfig(Config):
    SQLALCHEMY_DATABASE_URI = "sqlite://"
    TESTING = True


class FakeS3:
    """کلاینت S3 تقلبی؛ N بار اول خطای تایم‌اوت می‌دهد، بعد موفق می‌شود."""

    def __init__(self, failures):
        self.failures = failures
        self.calls = 0

    def put_object(self, **kwargs):
        self.calls += 1
        if self.calls <= self.failures:
            raise ReadTimeoutError(
                endpoint_url="https://s3.us-east-va.io.cloud.ovh.us",
                error="Read timed out.",
            )
        return {"ResponseMetadata": {"HTTPStatusCode": 200}}


def main():
    app = create_app(TestConfig)
    app.config["S3_ENDPOINT"] = "https://s3.us-east-va.io.cloud.ovh.us"
    app.config["S3_BUCKET"] = "test-bucket"
    app.config["S3_PUBLIC_BASE"] = "https://s3.us-east-va.io.cloud.ovh.us"
    app.config["S3_ACCESS_KEY"] = "tc1"
    app.config["S3_SECRET_KEY"] = "tc1"
    with app.app_context():
        # --- ۱) دوبار تایم‌اوت، بار سوم موفق ---
        fake = FakeS3(failures=2)
        sleeps = []
        with (
            patch.object(storage_svc, "_s3_client", return_value=fake),
            patch.object(storage_svc.time, "sleep", side_effect=sleeps.append),
        ):
            url = storage_svc.upload_bytes(b"fake-image-bytes", "result.png")
        check(
            "retry succeeds on 3rd attempt",
            url.startswith(
                "https://s3.us-east-va.io.cloud.ovh.us/test-bucket/app/"
            ),
            url,
        )
        check("put_object called 3 times", fake.calls == 3, f"got {fake.calls}")
        check("backoffs are 2s then 5s", sleeps == [2, 5], f"got {sleeps}")

        # --- ۲) هر سه تلاش ناموفق → خطا بالا می‌آید ---
        fake2 = FakeS3(failures=99)
        sleeps2 = []
        raised = None
        with (
            patch.object(storage_svc, "_s3_client", return_value=fake2),
            patch.object(storage_svc.time, "sleep", side_effect=sleeps2.append),
        ):
            try:
                storage_svc.upload_bytes(b"fake-image-bytes", "result.png")
            except ReadTimeoutError as exc:
                raised = exc
        check("all-fail raises after 3 attempts", raised is not None)
        check(
            "all-fail called put_object 3 times",
            fake2.calls == 3,
            f"got {fake2.calls}",
        )
        check(
            "all-fail backoffs are 2s then 5s", sleeps2 == [2, 5], f"got {sleeps2}"
        )

        # --- ۳) تلاش اول موفق → بدون تکرار و بدون صبر ---
        fake3 = FakeS3(failures=0)
        sleeps3 = []
        with (
            patch.object(storage_svc, "_s3_client", return_value=fake3),
            patch.object(storage_svc.time, "sleep", side_effect=sleeps3.append),
        ):
            url3 = storage_svc.upload_bytes(b"fake-image-bytes", "result.png")
        check(
            "first-attempt success",
            url3.startswith(
                "https://s3.us-east-va.io.cloud.ovh.us/test-bucket/app/"
            ),
            url3,
        )
        check(
            "first-attempt calls put_object once",
            fake3.calls == 1,
            f"got {fake3.calls}",
        )
        check("first-attempt never sleeps", sleeps3 == [], f"got {sleeps3}")

        # --- ۴) کانفیگ کلاینت boto3 ---
        captured = {}

        def _capture(*args, **kwargs):
            captured.update(kwargs)
            return FakeS3(failures=0)

        with patch.object(boto3, "client", side_effect=_capture):
            storage_svc._s3_client()
        cfg = captured.get("config")
        check("boto3 config present", cfg is not None)
        if cfg is not None:
            check(
                "read_timeout is 60",
                cfg.read_timeout == 60,
                f"got {cfg.read_timeout}",
            )
            check(
                "connect_timeout is 10",
                cfg.connect_timeout == 10,
                f"got {cfg.connect_timeout}",
            )
            check(
                "botocore retries off",
                cfg.retries == {"max_attempts": 1},
                f"got {cfg.retries}",
            )

        # --- ۵) مسیر دیسک-لوکال بدون تغییر ---
        app.config["S3_ENDPOINT"] = ""
        app.config["S3_PUBLIC_BASE"] = ""
        with tempfile.TemporaryDirectory() as tmpdir:
            app.static_folder = tmpdir
            url5 = storage_svc.upload_bytes(b"local-bytes", "result.png")
            check("local path returns static URL", "/static/uploads/" in url5, url5)
            name5 = url5.rsplit("/", 1)[-1]
            path5 = os.path.join(tmpdir, "uploads", name5)
            check("local file written", os.path.exists(path5), path5)
            if os.path.exists(path5):
                with open(path5, "rb") as f:
                    check("local file content", f.read() == b"local-bytes")

    print(f"\n{PASSED} passed, {FAILED} failed")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
