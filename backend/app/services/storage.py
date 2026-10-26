"""ذخیره‌سازی فایل خود اپ.

اگر S3 در env ست شده باشد همان مسیر قدیمی استفاده می‌شود؛ وگرنه فایل
روی دیسک خود سرور (زیر static/uploads) ذخیره و با همان URL عمومی
سرو می‌شود. ورکر input_url را با HTTP می‌خواند، پس هر دو مسیر برایش
یکی است. این fallback یعنی بدون دادن مشخصات S3 هم آپلود کار می‌کند
(دلیل خطای ۵۰۰ آپلود: APP_S3_ENDPOINT خالی بود و boto3 روی endpoint
خالی ValueError می‌داد).
"""

import os
import uuid

from flask import current_app


def _s3_enabled():
    return bool(current_app.config.get("S3_ENDPOINT"))


def _s3_client():
    import boto3

    return boto3.client(
        "s3",
        endpoint_url=current_app.config.get("S3_ENDPOINT"),
        aws_access_key_id=current_app.config.get("S3_ACCESS_KEY"),
        aws_secret_access_key=current_app.config.get("S3_SECRET_KEY"),
    )


def _local_dir():
    d = os.path.join(current_app.static_folder, "uploads")
    os.makedirs(d, exist_ok=True)
    return d


def _safe_filename(filename):
    base = os.path.basename(filename or "upload.bin")
    base = "".join(c for c in base if c.isalnum() or c in ("-", "_", "."))
    return base or "upload.bin"


def upload_bytes(data: bytes, filename: str) -> str:
    if _s3_enabled():
        bucket = current_app.config["S3_BUCKET"]
        key = f"app/{uuid.uuid4().hex}-{filename}"
        _s3_client().put_object(Bucket=bucket, Key=key, Body=data)
        base = current_app.config.get("S3_PUBLIC_BASE") or current_app.config.get(
            "S3_ENDPOINT", ""
        )
        return f"{base.rstrip('/')}/{bucket}/{key}"
    name = f"{uuid.uuid4().hex}-{_safe_filename(filename)}"
    path = os.path.join(_local_dir(), name)
    with open(path, "wb") as f:
        f.write(data)
    # پایه‌ی عمومی؛ وقتی دامنه‌ی برند آمد APP_PUBLIC_BASE عوض می‌شود.
    base = (
        current_app.config.get("S3_PUBLIC_BASE")
        or current_app.config.get("PUBLIC_BASE")
        or "http://171.22.25.152:5001"
    )
    return f"{base.rstrip('/')}/static/uploads/{name}"
