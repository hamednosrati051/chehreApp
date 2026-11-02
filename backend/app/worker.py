"""صف تولید (Celery). تسک‌ها Flask context را داخل خودشان می‌سازند
تا routes بدون چرخه‌ی import بتوانند با send_task صف بگذارند."""

import os
import tempfile

import requests
from celery import Celery

from .config import Config

celery_app = Celery(
    "new_app",
    broker=Config.CELERY_BROKER_URL,
    backend=Config.CELERY_RESULT_BACKEND,
)


def _download_to_temp(url):
    resp = requests.get(url, timeout=60)
    resp.raise_for_status()
    # پسوند واقعی فایل را نگه دار (قبلاً .bin بود و AvalAI نام/فرمت
    # فایل را نمی‌دید)؛ پیش‌فرض jpg است چون سلفی‌های اپ jpg هستند.
    from urllib.parse import urlparse

    ext = os.path.splitext(urlparse(url).path)[1] or ".jpg"
    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=ext)
    tmp.write(resp.content)
    tmp.close()
    return tmp.name


# نرخ‌های توکنی تأییدشده‌ی AvalAI برای Sunburst/Flare (دلار به‌ازای ۱M توکن).
# فقط برای محاسبه‌ی هزینه از usage گزارش‌شده؛ قیمت‌گذاری کاربر با سکه است.
_AVALAI_RATES = {"text_input": 5.0, "image_input": 8.0, "image_output": 30.0}


def _cost_usd_from_usage(usage):
    """هزینه‌ی دلاری از usage؛ None یعنی provider چیزی گزارش نکرد."""
    if not isinstance(usage, dict):
        return None
    try:
        details_in = usage.get("input_tokens_details") or {}
        details_out = usage.get("output_tokens_details") or {}
        text_in = details_in.get("text_tokens")
        image_in = details_in.get("image_tokens")
        if text_in is None and image_in is None:
            # بدون تفکیک: همه‌ی ورودی را متن حساب کن (محافظه‌کارانه نیست،
            # ولی فقط وقتی جزئیات نیست).
            text_in = usage.get("input_tokens", 0)
            image_in = 0
        out = usage.get("output_tokens", 0)
        cost = (
            (text_in or 0) * _AVALAI_RATES["text_input"]
            + (image_in or 0) * _AVALAI_RATES["image_input"]
            + (out or 0) * _AVALAI_RATES["image_output"]
        ) / 1_000_000
        return round(cost, 6)
    except (TypeError, ValueError):
        return None


@celery_app.task(name="app.worker.process_image_job")
def process_image_job(generation_id):
    from app import create_app

    app = create_app()
    with app.app_context():
        from .extensions import db
        from .models import AppGeneration, AppModel, AppUser, AppWorld
        from .services import coins as coins_svc
        from .services import image as image_svc

        gen = AppGeneration.query.get(generation_id)
        if not gen:
            return
        gen.status = "processing"
        db.session.commit()
        try:
            world = AppWorld.query.get(gen.world_id)
            model = AppModel.query.get(gen.model_id)
            local = _download_to_temp(gen.input_url)
            try:
                out = image_svc.edit_image(model, local, world.hidden_prompt)
            finally:
                os.unlink(local)
            if out.get("url"):
                gen.output_url = out["url"]
            elif out.get("b64_json"):
                import base64

                from .services import storage as storage_svc

                gen.output_url = storage_svc.upload_bytes(
                    base64.b64decode(out["b64_json"]), "result.png"
                )
            else:
                raise RuntimeError("unexpected image result")
            # لاگ هزینه‌ی واقعی (فقط با usage گزارش‌شده؛ وگرنه خالی می‌ماند).
            if out.get("usage"):
                gen.usage_json = out["usage"]
                gen.cost_usd = _cost_usd_from_usage(out["usage"])
            gen.status = "done"
            if not gen.charged:
                # Jobهای ثبت‌شده قبل از تغییر رزرو: شارژ موقع تحویل مثل قبل.
                user = AppUser.query.get(gen.user_id)
                gen.charged = coins_svc.spend_for_generation(user, gen)
        except Exception as exc:  # noqa: BLE001 — خطا در ردیف ذخیره می‌شود
            gen.status = "failed"
            gen.error_message = str(exc)[:500]
            if gen.charged:
                # رزرو موقع ثبت برگردد تا خروجی ناموفق پولی نگیرد.
                try:
                    user = AppUser.query.get(gen.user_id)
                    coins_svc.refund_reservation(user, gen)
                except Exception:  # noqa: BLE001 — خطای refund نباید خطای اصلی را بپوشاند
                    gen.error_message = (gen.error_message + " | refund failed")[:500]
        gen.completed_at = db.func.now()
        db.session.commit()


@celery_app.task(name="app.worker.process_voice_job")
def process_voice_job(generation_id):
    from app import create_app

    app = create_app()
    with app.app_context():
        from .extensions import db
        from .models import AppGeneration, AppUser
        from .services import coins as coins_svc
        from .services import voice as voice_svc

        gen = AppGeneration.query.get(generation_id)
        if not gen:
            return
        gen.status = "processing"
        db.session.commit()
        try:
            converter = voice_svc.get_converter("pending-route-choice")
            gen.output_url = converter.convert(gen.input_url, {})
            gen.status = "done"
            if not gen.charged:
                # Jobهای ثبت‌شده قبل از تغییر رزرو: شارژ موقع تحویل مثل قبل.
                # (قبل از این تغییر، مسیر صدا موقع تحویل اصلاً کسر نمی‌کرد.)
                user = AppUser.query.get(gen.user_id)
                gen.charged = coins_svc.spend_for_generation(user, gen)
        except Exception as exc:  # noqa: BLE001
            gen.status = "failed"
            gen.error_message = str(exc)[:500]
            if gen.charged:
                # رزرو موقع ثبت برگردد تا خروجی ناموفق پولی نگیرد.
                try:
                    user = AppUser.query.get(gen.user_id)
                    coins_svc.refund_reservation(user, gen)
                except Exception:  # noqa: BLE001
                    gen.error_message = (gen.error_message + " | refund failed")[:500]
        gen.completed_at = db.func.now()
        db.session.commit()
