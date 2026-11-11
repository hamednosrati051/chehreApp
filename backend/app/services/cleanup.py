"""پاکسازی دوره‌ای عکس‌های هدیه‌ی منقضی‌شده (نسخه‌ی ۱۳).

قانون (تصمیم حامد، ۲۰۲۶-۱۱-۰۹): عکس‌های پولی برای همیشه می‌مانند؛
عکس‌های هدیه (رایگان) حداکثر ۲۴ ساعت بعد از تکمیل روی سرور می‌مانند
و بعد حذف می‌شوند (فقط کپی سرور؛ کپی دانلودشده‌ی گوشی می‌ماند).

تشخیص رایگان/پولی از روی دفتر سکه است (همان قانونی که
refund_reservation و لیست تولیدهای ادمین استفاده می‌کنند):
سطر spend با ref_type='generation' و ref_id=id یعنی پولی؛ نبودنش
یعنی رایگان. charged و coin_cost برای هر دو ۱۰ است و قابل اعتماد نیستند.

برش زمانی (تصمیم ۲۰۲۶-۱۱-۱۱): فقط عکس‌های تکمیل‌شده‌ی بعد از
استقرار نسخه‌ی ۱۳ حذف می‌شوند؛ عکس‌های قدیمی‌تر هرگز.
برش از تنظیم app_settings با کلید cleanup_free_images_cutoff خوانده
می‌شود (ISO datetime با UTC؛ در مرحله‌ی استقرار روی زمان استقرار ست
می‌شود). اگر تنظیم نباشد/خالی/ناخوانا باشد، هیچ‌چیز حذف نمی‌شود
(fail-safe) تا حذف انبوه اشتباهی رخ ندهد.

نگارخانه (تصمیم ۲۰۲۶-۱۱-۱۱، فقط رایگان): عکس رایگانِ pending که
بیش از ۲۴ ساعت از ثبتش (created_at) گذشته، یا rejected که بیش از
۲۴ ساعت از رد شدنش (reviewed_at) گذشته → فایل حذف + output_url
خالی + ردیف نگارخانه حذف می‌شود. paidها و approvedها هرگز.
برش استقرار روی قانون نگارخانه اعمال نمی‌شود.

تولیدهای منتشرشده در نگارخانه (ردیف app_gallery) رد می‌شوند.
سلفی‌های ورودی دست نمی‌خورند. رکورد تولید می‌ماند، فقط فایل خروجی
حذف و output_url خالی می‌شود (اپ‌های قدیمی output_url خالی را فیلتر
می‌کنند و خطا نمی‌گیرند).
"""

import datetime
import os
from urllib.parse import urlparse

from flask import current_app
from sqlalchemy import and_, or_

from ..extensions import db
from ..models import AppCoinLedger, AppGallery, AppGeneration, AppSetting

CUTOFF_SETTING_KEY = "cleanup_free_images_cutoff"
FREE_TTL = datetime.timedelta(hours=24)

# هاست‌هایی که URL خروجی محلی با آن‌ها ساخته می‌شود (مثل حذف خروجی در
# admin_delete_generation_output)؛ بقیه‌ی URLها محلی نیستند.
_LOCAL_HOSTS = ("", "chehre.inourai.ir", "171.22.25.152:5001")


def _read_cutoff():
    """برش زمانی از app_settings؛ None یعنی تنظیم معتبر نیست -> حذف هیچ."""
    row = AppSetting.query.get(CUTOFF_SETTING_KEY)
    if row is None:
        return None
    raw = str(row.value or "").strip()
    if not raw:
        return None
    try:
        dt = datetime.datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None
    if dt.tzinfo is not None:
        dt = dt.astimezone(datetime.timezone.utc).replace(tzinfo=None)
    return dt


def _local_upload_path(output_url):
    """مسیر امن فایل محلی زیر static/uploads، یا None اگر URL محلی نباشد.

    فقط فایل‌هایی که واقعاً زیر static/uploads هستند قبول می‌شوند؛
    URLهای S3 یا خارجی هرگز لمس نمی‌شوند.
    """
    if not output_url:
        return None
    parsed = urlparse(output_url)
    if parsed.netloc not in _LOCAL_HOSTS:
        return None
    if not parsed.path.startswith("/static/uploads/"):
        return None
    filename = os.path.basename(parsed.path)
    if not filename or filename in (".", ".."):
        return None
    uploads_dir = os.path.realpath(
        os.path.join(current_app.static_folder, "uploads")
    )
    path = os.path.realpath(os.path.join(uploads_dir, filename))
    if os.path.commonpath([path, uploads_dir]) != uploads_dir:
        return None
    return path


def cleanup_expired_free_images():
    """یک دور پاکسازی؛ دیکشنری شمارنده‌ها برمی‌گرداند.

    دو فاز، هر دو فقط برای عکس‌های رایگان (بدون سطر spend):
    - فاز گالری: ردیف نگارخانه‌ی pending که بیش از ۲۴ ساعت از
      ثبتش (created_at) گذشته، یا rejected که بیش از ۲۴ ساعت از
      رد شدنش (reviewed_at) گذشته → فایل حذف + output_url خالی +
      ردیف نگارخانه حذف می‌شود. approvedها و paidها هرگز لمس نمی‌شوند.
    - فاز ۲۴ ساعته: تصویر done با خروجی، تکمیل‌شده بیش از ۲۴ ساعت
      پیش و بعد از برش → فایل حذف + output_url خالی؛ رکورد می‌ماند.
      ردیف نگارخانه‌دار رد می‌شود (فایلش هنوز لازم است).
    examined/deleted/skipped = فاز ۲۴ ساعته؛ gallery_* = فاز گالری.
    """
    cutoff = _read_cutoff()
    if cutoff is None:
        return {
            "examined": 0,
            "deleted": 0,
            "skipped": 0,
            "cutoff": None,
            "gallery_examined": 0,
            "gallery_deleted": 0,
            "gallery_skipped": 0,
        }
    now = datetime.datetime.utcnow()
    threshold = now - FREE_TTL
    # --- فاز ۱: نگارخانه (pending بیش از ۲۴ ساعت از ثبت / rejected
    # بیش از ۲۴ ساعت از رد). فقط رایگان؛ paid هرگز.
    gallery_rows = (
        db.session.query(AppGallery, AppGeneration)
        .join(AppGeneration, AppGallery.generation_id == AppGeneration.id)
        .filter(
            AppGeneration.kind == "image",
            AppGeneration.status == "done",
            or_(
                and_(
                    AppGallery.status == "pending",
                    AppGallery.created_at < threshold,
                ),
                and_(
                    AppGallery.status == "rejected",
                    AppGallery.reviewed_at.isnot(None),
                    AppGallery.reviewed_at < threshold,
                ),
            ),
        )
        .order_by(AppGallery.id)
        .all()
    )
    gallery_examined = len(gallery_rows)
    gallery_deleted = 0
    gallery_skipped = 0
    if gallery_rows:
        gallery_gen_ids = [gen.id for _, gen in gallery_rows]
        gallery_spent_ids = {
            row[0]
            for row in db.session.query(AppCoinLedger.ref_id)
            .filter(
                AppCoinLedger.ref_type == "generation",
                AppCoinLedger.ref_id.in_(gallery_gen_ids),
                AppCoinLedger.reason == "spend",
            )
            .all()
        }
        for grow, gen in gallery_rows:
            if gen.id in gallery_spent_ids:
                # پولی: هرگز لمس نمی‌شود.
                gallery_skipped += 1
                continue
            if gen.output_url is None:
                # فایل از قبل رفته؛ فقط ردیف نگارخانه حذف می‌شود.
                db.session.delete(grow)
                gallery_deleted += 1
                continue
            path = _local_upload_path(gen.output_url)
            if path is None:
                # فایل محلی نیست (مثلاً S3)؛ URL و ردیف دست نمی‌خورند.
                gallery_skipped += 1
                continue
            if os.path.exists(path):
                os.unlink(path)
            gen.output_url = None
            db.session.delete(grow)
            gallery_deleted += 1
        db.session.commit()
    # --- فاز ۲: قانون ۲۴ ساعته (مثل قبل) ---
    candidates = (
        AppGeneration.query.filter(
            AppGeneration.kind == "image",
            AppGeneration.status == "done",
            AppGeneration.output_url.isnot(None),
            AppGeneration.completed_at.isnot(None),
            AppGeneration.completed_at < threshold,
            AppGeneration.completed_at > cutoff,
        )
        .order_by(AppGeneration.id)
        .all()
    )
    examined = len(candidates)
    if not examined:
        return {
            "examined": 0,
            "deleted": 0,
            "skipped": 0,
            "cutoff": cutoff.isoformat(),
            "gallery_examined": gallery_examined,
            "gallery_deleted": gallery_deleted,
            "gallery_skipped": gallery_skipped,
        }
    gen_ids = [g.id for g in candidates]
    # پولی‌ها: یک پرس‌وجوی IN برای سطرهای spend (مثل admin_list_generations).
    spent_ids = {
        row[0]
        for row in db.session.query(AppCoinLedger.ref_id)
        .filter(
            AppCoinLedger.ref_type == "generation",
            AppCoinLedger.ref_id.in_(gen_ids),
            AppCoinLedger.reason == "spend",
        )
        .all()
    }
    # منتشرشده در نگارخانه: یک پرس‌وجوی IN برای کل دور.
    gallery_ids = {
        row[0]
        for row in db.session.query(AppGallery.generation_id)
        .filter(AppGallery.generation_id.in_(gen_ids))
        .all()
    }
    deleted = 0
    skipped = 0
    for gen in candidates:
        if gen.id in spent_ids:
            # پولی: هرگز لمس نمی‌شود.
            skipped += 1
            continue
        if gen.id in gallery_ids:
            # در نگارخانه است: فایلش هنوز لازم است.
            skipped += 1
            continue
        path = _local_upload_path(gen.output_url)
        if path is None:
            # فایل محلی نیست (مثلاً S3)؛ بدون حذف فیزیکی، URL را هم
            # خالی نکن چون فایل هنوز سر جایش است.
            skipped += 1
            continue
        if os.path.exists(path):
            os.unlink(path)
        # فایل اگر از قبل رفته باشد هم URL خالی می‌شود تا تصویر
        # منقضی از «تصاویر من» ناپدید شود؛ رکورد می‌ماند.
        gen.output_url = None
        deleted += 1
    db.session.commit()
    return {
        "examined": examined,
        "deleted": deleted,
        "skipped": skipped,
        "cutoff": cutoff.isoformat(),
        "gallery_examined": gallery_examined,
        "gallery_deleted": gallery_deleted,
        "gallery_skipped": gallery_skipped,
    }
