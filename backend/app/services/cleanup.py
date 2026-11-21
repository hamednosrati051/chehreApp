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
import logging
import os
from urllib.parse import urlparse

from flask import current_app
from sqlalchemy import and_, or_

from ..extensions import db
from ..models import (
    AppCoinLedger,
    AppCoinPack,
    AppGallery,
    AppGeneration,
    AppPurchase,
    AppSetting,
    AppUser,
)
from . import zibal as zibal_svc

log = logging.getLogger(__name__)

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


STALE_PENDING_MINUTES = 30


def expire_stale_pending_purchases():
    """حذف خریدهای pendingِ قدیمیِ بدون توکن (نسخه‌ی ۱۶؛ نسخه‌ی ۱۷: gateway-aware).

    بازار/مایکت: فقط ردیف‌های pending با bazaar_purchase_token IS NULL و
    قدیمی‌تر از ۳۰ دقیقه — یعنی پرداخت‌هایی که کاربر لغو کرد و اپ
    هیچ‌وقت وریفای نکرد. ردیفی که توکن دارد هرگز لمس نمی‌شود.
    زیبال (v17 — G3؛ قانون ۱ ساعته ۲۰۲۶-۱۱-۱۹): ردیف زیبال توکن بازار
    ندارد ولی trackId دارد؛ کرونِ ۳۰ دقیقه‌ای نباید آن را بگیرد. ردیف
    زیبال (pending یا failed) فقط وقتی حذف می‌شود که قدیمی‌تر از
    ۱ ساعت باشد — جارو تا آن موقع دست‌کم دوبار وریفایش کرده؛ پرداختِ
    انجام‌شده تا آن موقع با کالبک یا recheck تسویه شده است.
    برمی‌گرداند: تعداد ردیف‌های حذف‌شده.
    """
    cutoff = datetime.datetime.utcnow() - datetime.timedelta(
        minutes=STALE_PENDING_MINUTES
    )
    stale = (
        AppPurchase.query.filter(
            AppPurchase.status == "pending",
            AppPurchase.bazaar_purchase_token.is_(None),
            AppPurchase.created_at < cutoff,
            AppPurchase.gateway.in_(("bazaar", "myket")),
        ).all()
    )
    count = len(stale)
    for row in stale:
        db.session.delete(row)
    # زیبالِ رهاشده: فقط قدیمی‌تر از ۱ ساعت (قانون ۱ ساعته، ۲۰۲۶-۱۱-۱۹).
    # جارو تا آن موقع دست‌کم دوبار وریفایش کرده؛ اگر هنوز پرداخت نشده
    # بود (pending یا failed)، حذف می‌شود.
    zibal_cutoff = datetime.datetime.utcnow() - datetime.timedelta(hours=1)
    zibal_stale = (
        AppPurchase.query.filter(
            AppPurchase.status.in_(("pending", "failed")),
            AppPurchase.gateway == "zibal",
            AppPurchase.created_at < zibal_cutoff,
        ).all()
    )
    count += len(zibal_stale)
    for row in zibal_stale:
        db.session.delete(row)
    db.session.commit()
    return count


RECONCILE_MIN_AGE_MINUTES = 10
RECONCILE_MAX_AGE_MINUTES = 60
RECONCILE_BATCH = 50


def reconcile_zibal_purchases():
    """وریفای مجدد خریدهای زیبالِ pending/failed با trackId (جاروی ایمنی).

    کالبک گمشده یا ریچکِ زودهنگام نباید پولِ پرداخت‌شده را بی‌سکه
    بگذارد: هر ۲۰ دقیقه (کرون) خریدهای زیبالِ قدیمی‌تر از ۱۰ دقیقه
    و تازه‌تر از ۱ ساعت دوباره وریفای می‌شوند — یعنی هر خرید درانتظار
    تو اون ۱ ساعت دست‌کم دوبار چک می‌شه؛ اگر پرداخت شده بود
    تسویه می‌شوند (settle idempotent است؛ دوبار شارژ نمی‌شود).
    خطای ارتباط ردیف را دست نمی‌زند. مرچنت موقع هر وریفای از
    کانفیگ خوانده می‌شود، پس عوض‌کردن مرچنت جارو را خراب نمی‌کند
    (فقط خریدهای مرچنت قبلی دیگر وریفای نمی‌شوند).
    برمی‌گرداند (checked, settled).
    """
    now = datetime.datetime.utcnow()
    rows = (
        AppPurchase.query.filter(
            AppPurchase.gateway == "zibal",
            AppPurchase.status.in_(("pending", "failed")),
            AppPurchase.zibal_track_id.isnot(None),
            AppPurchase.created_at
            < now - datetime.timedelta(minutes=RECONCILE_MIN_AGE_MINUTES),
            AppPurchase.created_at
            > now - datetime.timedelta(minutes=RECONCILE_MAX_AGE_MINUTES),
        )
        .order_by(AppPurchase.id)
        .limit(RECONCILE_BATCH)
        .all()
    )
    checked = 0
    settled = 0
    for row in rows:
        checked += 1
        try:
            result = zibal_svc.verify_payment(row.zibal_track_id, row.amount_irr)
        except Exception as exc:  # noqa: BLE001 - خطای ارتباط؛ ردیف می‌ماند
            log.warning("reconcile zibal %s: verify error: %s", row.id, exc)
            continue
        if not result.get("paid"):
            continue
        user = AppUser.query.get(row.user_id)
        pack = AppCoinPack.query.get(row.pack_id)
        if user is None or pack is None:
            log.warning("reconcile zibal %s: user/pack missing", row.id)
            continue
        zibal_svc.settle_purchase(user, pack, row)
        settled += 1
        log.info("reconcile zibal %s: settled", row.id)
    return checked, settled


STALE_GENERATION_SECONDS = 120

STALE_GENERATION_ERROR = "generation timed out (stale for more than 120s)"


def fail_stale_generations(older_than_s=STALE_GENERATION_SECONDS):
    """ناموفق‌کردن نسل‌های گیرکرده (دستور حامد ۲۰۲۶-۱۱-۱۸).

    هر نسل جدا با ساعت شروع خودش سنجیده می‌شود: ردیف‌های processing یا
    queued که از created_atشان بیشتر از older_than_s گذشته باشد —
    یعنی تسک Celeryشان مرده یا گیر کرده و هیچ‌وقت تمام نمی‌شوند
    (مثل نسل ۱۱۲۵ که ری‌استارت ورکر وسط دیپلوی کشتش). هر ردیف
    failed می‌شود + completed_at می‌خورد + اگر شارژ شده بود رزرو
    برمی‌گردد (refund_reservation؛ سکه یا اعتبار هدیه) تا کاربر
    بتواند دوباره بزند؛ اپ با دیدن failed حلقه‌ی نظرسنجی را می‌شکند
    و «ناموفق تولید عکس» نشان می‌دهد.
    برمی‌گرداند: تعداد ردیف‌های ناموفق‌شده.
    """
    from app.services import coins as coins_svc

    cutoff = datetime.datetime.utcnow() - datetime.timedelta(seconds=older_than_s)
    stale = (
        AppGeneration.query.filter(
            AppGeneration.status.in_(("queued", "processing")),
            AppGeneration.created_at < cutoff,
        ).all()
    )
    failed = 0
    for gen in stale:
        try:
            user = AppUser.query.get(gen.user_id)
            gen.status = "failed"
            gen.error_message = STALE_GENERATION_ERROR
            if gen.charged and user is not None:
                try:
                    coins_svc.refund_reservation(user, gen)
                except Exception:
                    gen.error_message = (
                        gen.error_message + " | refund failed"
                    )[:500]
            gen.completed_at = db.func.now()
            db.session.add(gen)
            db.session.commit()
            failed += 1
        except Exception:
            db.session.rollback()
            continue
    return failed


ORPHAN_UPLOAD_GRACE_S = 3600


def cleanup_orphan_uploads(older_than_s=ORPHAN_UPLOAD_GRACE_S):
    """حذف فایل‌های یتیم static/uploads (نسخه‌ی ۱۸؛ کرون ساعتی).

    همان قانون ادمین (GET/DELETE /admin/inputs/orphans): فایل‌های
    مستقیم زیر uploads که به هیچ input_url/output_url نسل یا image_url
    نگارخانه ارجاع نشده‌اند و mtimeشان قدیمی‌تر از آستانه است (پیش‌فرض
    ۱ ساعت). زیردایرکتوری هرگز لمس نمی‌شود. دیسک-لوکال فقط: اگر
    S3_ENDPOINT ست باشد، لاگ می‌کند و هیچ‌چیز حذف نمی‌شود.
    برمی‌گرداند {"ok", "skipped_s3", "deleted", "bytes"}.
    """
    # import دیرهنگام: این helperها در routes.py هستند و routes خدمات
    # را import می‌کند؛ import در سطح ماژول چرخه می‌سازد.
    from ..routes import (
        _orphan_upload_files,
        _referenced_upload_basenames,
        _uploads_dir,
    )

    if current_app.config.get("S3_ENDPOINT"):
        log.info("cleanup_orphan_uploads: S3-backed storage, skipping")
        return {"ok": True, "skipped_s3": True, "deleted": 0, "bytes": 0}
    uploads_dir = _uploads_dir()
    files = _orphan_upload_files(
        uploads_dir, _referenced_upload_basenames(), older_than_s=older_than_s
    )
    deleted = 0
    freed = 0
    for name, size, _mtime in files:
        path = os.path.realpath(os.path.join(uploads_dir, name))
        if os.path.commonpath([path, uploads_dir]) != uploads_dir:
            continue
        try:
            os.unlink(path)
        except FileNotFoundError:
            continue
        except OSError as exc:
            log.warning("cleanup_orphan_uploads: cannot delete %s: %s", name, exc)
            continue
        deleted += 1
        freed += size
    log.info(
        "cleanup_orphan_uploads: deleted=%s bytes=%s", deleted, freed
    )
    return {"ok": True, "skipped_s3": False, "deleted": deleted, "bytes": freed}
