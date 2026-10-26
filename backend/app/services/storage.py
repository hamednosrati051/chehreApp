"""ذخیره‌سازی فایل خود اپ (S3-compatible، باکت جدای اپ).

مشخصات از env خود اپ می‌آید. باکت اپ جدا از باکت نورا است؛
سرویس S3 فقط زیرساخت است و دیتا با باکت جدا تفکیک می‌شود.
"""

import uuid

import boto3
from flask import current_app


def _client():
    return boto3.client(
        "s3",
        endpoint_url=current_app.config.get("S3_ENDPOINT"),
        aws_access_key_id=current_app.config.get("S3_ACCESS_KEY"),
        aws_secret_access_key=current_app.config.get("S3_SECRET_KEY"),
    )


def upload_bytes(data: bytes, filename: str) -> str:
    bucket = current_app.config["S3_BUCKET"]
    key = f"app/{uuid.uuid4().hex}-{filename}"
    _client().put_object(Bucket=bucket, Key=key, Body=data)
    base = current_app.config.get("S3_PUBLIC_BASE") or current_app.config.get(
        "S3_ENDPOINT", ""
    )
    return f"{base.rstrip('/')}/{bucket}/{key}"
