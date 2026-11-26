"""تلاش مجدد دانلود نتیجه‌ی provider (رفع ۲۰۲۶-۱۱-۲۷ — تایم‌اوت OVH):

GapGPT نتیجه را روی باکت OVH خودش می‌گذارد؛ _download_to_temp آن را
می‌گیرد. اتصال گاهی ساکت می‌ماند و کل تولید می‌مرد (۵ بار در ۲۰۲۶-۱۱-۲۶،
همه مدل ۶). حالا حداکثر ۳ تلاش (فاصله‌ی ۲ و ۵ ثانیه، هر تلاش ۲۰ ثانیه
مهلت)؛ دانلود سالم چند ثانیه است، پس مهلت ۲۰ ثانیه حاشیه کافی دارد.

اجرا:
    /tmp/chehre-test-venv/bin/python backend/tests/test_download_retry.py

فقط فایل‌های موقت /tmp که خود تابع می‌سازد (و همین‌جا پاک می‌شوند)؛
پوشه‌ی واقعی static/uploads دست نمی‌خورد.
"""

import os
import sys
import types
from unittest.mock import patch

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import requests  # noqa: E402

from app.worker import (  # noqa: E402
    DOWNLOAD_ATTEMPTS,
    DOWNLOAD_BACKOFFS,
    DOWNLOAD_TIMEOUT,
    _download_to_temp,
)

PASSED = 0
FAILED = 0
_TMP_FILES = []


def check(name, cond, extra=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  ok: {name}")
    else:
        FAILED += 1
        print(f"  FAIL: {name} {extra}")


def _ok_response(body=b"fake-image-bytes"):
    return types.SimpleNamespace(
        content=body, raise_for_status=lambda: None
    )


def _timeout():
    return requests.exceptions.ReadTimeout("Read timed out.")


def main():
    url = "https://s3.us-east-va.io.cloud.ovh.us/bucket/app/x-result.png"

    # --- ۱) دوبار تایم‌اوت، بار سوم موفق ---
    sleeps = []
    calls = []
    import app.worker as worker_mod

    def _get_fail_twice(u, timeout=None):
        calls.append(timeout)
        if len(calls) <= 2:
            raise _timeout()
        return _ok_response()

    with (
        patch.object(worker_mod.requests, "get", side_effect=_get_fail_twice),
        patch.object(worker_mod.time, "sleep", side_effect=sleeps.append),
    ):
        path = _download_to_temp(url)
    _TMP_FILES.append(path)
    check("retry succeeds on 3rd attempt", os.path.exists(path))
    with open(path, "rb") as f:
        check("downloaded content", f.read() == b"fake-image-bytes")
    check("requests.get called 3 times", len(calls) == 3, f"got {len(calls)}")
    check("backoffs are 2s then 5s", sleeps == [2, 5], f"got {sleeps}")
    check("timeout is 20 on every call", all(t == 20 for t in calls), f"got {calls}")
    check("png extension preserved", path.endswith(".png"), path)

    # --- ۲) هر سه تلاش ناموفق → خطا بالا می‌آید ---
    sleeps2 = []
    calls2 = []

    def _get_always_fail(u, timeout=None):
        calls2.append(timeout)
        raise _timeout()

    raised = None
    with (
        patch.object(worker_mod.requests, "get", side_effect=_get_always_fail),
        patch.object(worker_mod.time, "sleep", side_effect=sleeps2.append),
    ):
        try:
            _download_to_temp(url)
        except requests.exceptions.ReadTimeout as exc:
            raised = exc
    check("all-fail raises after 3 attempts", raised is not None)
    check(
        "all-fail called requests.get 3 times",
        len(calls2) == 3,
        f"got {len(calls2)}",
    )
    check(
        "all-fail backoffs are 2s then 5s", sleeps2 == [2, 5], f"got {sleeps2}"
    )

    # --- ۳) تلاش اول موفق → بدون تکرار و بدون صبر ---
    sleeps3 = []
    calls3 = []

    def _get_ok(u, timeout=None):
        calls3.append(timeout)
        return _ok_response()

    with (
        patch.object(worker_mod.requests, "get", side_effect=_get_ok),
        patch.object(worker_mod.time, "sleep", side_effect=sleeps3.append),
    ):
        path3 = _download_to_temp(
            "https://s3.us-east-va.io.cloud.ovh.us/bucket/app/y-result"
        )
    _TMP_FILES.append(path3)
    check("first-attempt success", os.path.exists(path3))
    check(
        "first-attempt calls requests.get once",
        len(calls3) == 1,
        f"got {len(calls3)}",
    )
    check("first-attempt never sleeps", sleeps3 == [], f"got {sleeps3}")
    check("no extension defaults to .jpg", path3.endswith(".jpg"), path3)

    # --- ۴) ثابت‌ها ---
    check("DOWNLOAD_ATTEMPTS is 3", DOWNLOAD_ATTEMPTS == 3)
    check("DOWNLOAD_BACKOFFS is (2, 5)", DOWNLOAD_BACKOFFS == (2, 5))
    check("DOWNLOAD_TIMEOUT is 20", DOWNLOAD_TIMEOUT == 20)

    for p in _TMP_FILES:
        try:
            os.unlink(p)
        except OSError:
            pass
    print(f"\n{PASSED} passed, {FAILED} failed")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
