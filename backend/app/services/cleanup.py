"""حذف دوره‌ای عکس‌های رایگان (هدیه) قدیمی‌تر از ۲۴ ساعت — نسخه‌ی ۱۳.

قانون (تصمیم حامد، ۲۰۲۶-۱۱-۰۹):
- عکس‌های پولی برای همیشه می‌مانند؛ عکس‌های رایگان حداکثر ۲۴ ساعت
  پس از تکمیل روی سرور می‌مانند، بعد فقط کپی سرور حذف می‌شود
  (کپی دانلودشده روی گوشی کاربر می‌ماند؛ سلفی ورودی دست نمی‌خورد).
- عکس‌های منتشرشده در گالری مستثنی‌اند.

تشخیص پولی/رایگان فقط از روی دفتر سکه است (مثل refund_reservation
در services/coins.py): ردیف `spend` در app_coin_ledger با
ref_type='generation' و ref_id=generation.id یعنی پولی؛ نبودنش یعنی
رایگان. charged و coin_cost و model_id قابل اعتماد نیستند.

اجرا: دستور Flask CLI ‏`cleanup-expired-free-images` (ثبت‌شده در
app/__init__.py) که قرار است ساعتی یک بار از cron سرور صدا زده شود:

    0 * * * * cd /opt/new-app/backend && /opt/new-app/venv/bin/flask --app run.py cleanup-expired-free-images >> /var/log/chehre-cleanup.log 2>&1

(مسیر venv پایتون سرور را با مسیر واقعی آن جایگزین کنید؛
دستور باید با همان venvای اجرا شود که سرویس API را می‌چرخاند.)
هر اجرا idempotent است و فقط ردیف‌های واجد شرایط را لمس می‌کند.
"""

import datetime
import logging
import os
from urllib.parse import urlparse

from flask import current_app

from ..extensions import db
from ..models import AppCoinLedger, AppGallery, AppGeneration

log = logging.getLogger(__name__)

# نگهداری عکس رایگان پس از تکمیل (ساعت).
RETENTION_HOURS = 24


def _cutoff(now=None):
    """مرز انقضا (naive UTC؛ completed_at هم naive UTC ذخیره می‌شود)."""
    if now is None:
        now = datetime.datetime.now(datetime.timezone.utc)
    if now.tzinfo is not None:
        now = now.replace(tzinfo=None)
    return now - datetime.timedelta(hours=RETENTION_HOURS)


def has_spend_ledger_row(generation_id):
    """آیا برای این generation ردیف spend در دفتر سکه هست؟ (= پولی).

    همان پرس‌وجوی refund_reservation در services/coins.py
    (بدون فیلتر user_id؛ ref_id همان id نسل است و یکتاست).
    """
    return (
        AppCoinLedger.query.filter_by(
            ref_type="generation",
            ref_id=generation_id,
            reason="spend",
        ).first()
        is not None
    )


def paid_generation_ids(generation_ids):
    """مجموعه‌ی idهای پولی از میان idهای داده‌شده — یک پرس‌وجو (IN)."""
    ids = list(generation_ids)
    if not ids:
        return set()
    rows = (
        db.session.query(AppCoinLedger.ref_id)
        .filter(
            AppCoinLedger.ref_type == "generation",
            AppCoinLedger.reason == "spend",
            AppCoinLedger.ref_id.in_(ids),
        )
        .all()
    )
    return {r[0] for r in rows}


def gallery_generation_ids(generation_ids):
    """مجموعه‌ی idهایی که ردیف app_gallery دارند — یک پرس‌وجو (IN)."""
    ids = list(generation_ids)
    if not ids:
        return set()
    rows = (
        db.session.query(AppGallery.generation_id)
        .filter(AppGallery.generation_id.in_(ids))
        .all()
    )
    return {r[0] for r in rows}


def expires_at_for(gen, is_free):
    """زمان انقضای یک نسل (naive UTC) یا None.

    فقط برای عکس رایگانِ done که هنوز output_url دارد؛ بقیه null.
    """
    if (
        not is_free
        or gen.kind != "image"
        or gen.status != "done"
        or not gen.output_url
        or not gen.completed_at
    ):
        return None
    return gen.completed_at + datetime.timedelta(hours=RETENTION_HOURS)


def is_expired(gen):
    """آیا خروجی این نسل منقضی شده؟ عکس done که output_url ندارد."""
    return gen.kind == "image" and gen.status == "done" and not gen.output_url


def generation_extra_fields(gens):
    """فیلدهای افزایشی نسخه‌ی ۱۳ برای یک یا چند نسل — حداکثر یک پرس‌وجو.

    برمی‌گرداند: {generation_id: {"is_free": bool, "expired": bool,
    "expires_at": str|None}}؛ expires_at رشته‌ی ISO-8601 با پسوند Z
    (UTC) است. اپ‌های قدیمی این فیلدها را نمی‌شناسند و نادیده می‌گیرند.
    """
    gens = list(gens)
    paid = paid_generation_ids([g.id for g in gens])
    out = {}
    for gen in gens:
        is_free = gen.id not in paid
        expires = expires_at_for(gen, is_free)
        out[gen.id] = {
            "is_free": is_free,
            "expired": is_expired(gen),
            "expires_at": expires.isoformat() + "Z" if expires else None,
        }
    return out


def _local_uploads_path(output_url):
    """مسیر فایل لوکال برای یک output_url، یا None اگر لوکال نیست.

    فقط URLهایی که pathشان با ‎/static/uploads/‎ شروع می‌شود لوکال‌اند؛
    بقیه (مثلاً S3) هیچ‌وقت حذف نمی‌شوند. basename guard + realpath
    تضمین می‌کند حذف هرگز بیرون از uploads نرود.
    """
    parsed = urlparse(output_url or "")
    path = parsed.path or ""
    if not path.startswith("/static/uploads/"):
        return None
    filename = os.path.basename(path)
    if not filename or filename in (".", "..") or "/" in filename or "\\" in filename:
        return None
    uploads_dir = os.path.realpath(
        os.path.join(current_app.static_folder, "uploads")
    )
    full = os.path.realpath(os.path.join(uploads_dir, filename))
    if os.path.commonpath([full, uploads_dir]) != uploads_dir:
        return None
    return full


def cleanup_expired_free_images(now=None):
    """یک اجرای حذف عکس‌های رایگان منقضی‌شده.

    برمی‌گرداند dict با شمارنده‌ها:
    scanned (کاندیدهای done قدیمی با output_url)، deleted (فایل حذف +
    output_url خالی شد)، skipped (پولی / گالری / غیرلوکال / خطای حذف)،
    files_missing (فایل روی دیسک نبود ولی output_url خالی شد).
    رکورد نسل همیشه می‌ماند؛ فقط output_url خالی می‌شود.
    """
    cutoff = _cutoff(now)
    candidates = (
        AppGeneration.query.filter(
            AppGeneration.kind == "image",
            AppGeneration.status == "done",
            AppGeneration.output_url.isnot(None),
            AppGeneration.completed_at < cutoff,
        )
        .order_by(AppGeneration.id)
        .all()
    )
    result = {
        "scanned": len(candidates),
        "deleted": 0,
        "skipped": 0,
        "files_missing": 0,
    }
    if not candidates:
        log.info("cleanup-expired-free-images: scanned=0 (nothing expired)")
        return result

    ids = [g.id for g in candidates]
    paid = paid_generation_ids(ids)
    in_gallery = gallery_generation_ids(ids)
    s3_enabled = bool(current_app.config.get("S3_ENDPOINT"))

    for gen in candidates:
        if gen.id in paid:
            log.info("cleanup: skip paid generation id=%s", gen.id)
            result["skipped"] += 1
            continue
        if gen.id in in_gallery:
            log.info("cleanup: skip gallery generation id=%s", gen.id)
            result["skipped"] += 1
            continue
        if s3_enabled:
            # وقتی S3 ست است فایلی روی دیسک لوکال نیست؛ چیزی حذف نمی‌شود.
            log.warning(
                "cleanup: skip generation id=%s (S3 storage enabled)", gen.id
            )
            result["skipped"] += 1
            continue
        path = _local_uploads_path(gen.output_url)
        if path is None:
            log.warning(
                "cleanup: skip generation id=%s (non-local output_url=%r)",
                gen.id,
                gen.output_url,
            )
            result["skipped"] += 1
            continue
        try:
            os.unlink(path)
            result["deleted"] += 1
        except FileNotFoundError:
            log.warning(
                "cleanup: file missing for generation id=%s path=%s",
                gen.id,
                path,
            )
            result["files_missing"] += 1
        except OSError as exc:
            log.warning(
                "cleanup: could not delete file for generation id=%s path=%s: %s",
                gen.id,
                path,
                exc,
            )
            result["skipped"] += 1
            continue
        # فقط وقتی فایل حذف شد (یا از قبل نبود) output_url خالی می‌شود؛
        # input_url و بقیه‌ی فیلدها دست نمی‌خورند.
        gen.output_url = None
        db.session.add(gen)

    db.session.commit()
    log.info(
        "cleanup-expired-free-images: scanned=%d deleted=%d skipped=%d files_missing=%d",
        result["scanned"],
        result["deleted"],
        result["skipped"],
        result["files_missing"],
    )
    return result
