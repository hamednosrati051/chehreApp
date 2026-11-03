"""روت‌های API اپ: auth / محتوا / تولید / سکه / گالری."""

import datetime
import os
import random
from functools import wraps

import jwt
from flask import Blueprint, current_app, g, jsonify, request
from sqlalchemy import func, or_
from werkzeug.security import check_password_hash, generate_password_hash
from werkzeug.utils import secure_filename

from .extensions import db
from .models import (
    AppAdminUser,
    AppAnnouncement,
    AppCategory,
    AppDeviceToken,
    AppCoinLedger,
    AppCoinPack,
    AppFavorite,
    AppGallery,
    AppGeneration,
    AppImageLike,
    AppModel,
    AppOtpCode,
    AppPricing,
    AppPurchase,
    AppSetting,
    AppSinger,
    AppTrend,
    AppTrendWorld,
    AppUser,
    AppWorld,
)
from .services import bazaar as bazaar_svc
from .services import coins as coins_svc
from .services import fcm as fcm_svc
from .services import sms as sms_svc

auth_bp = Blueprint("auth", __name__)
content_bp = Blueprint("content", __name__)
generations_bp = Blueprint("generations", __name__)
coins_bp = Blueprint("coins", __name__)
gallery_bp = Blueprint("gallery", __name__)
uploads_bp = Blueprint("uploads", __name__)
admin_bp = Blueprint("admin", __name__)

OTP_TTL_MINUTES = 2
OTP_MAX_PER_HOUR = 5
TOKEN_DAYS = 30
DEFAULT_IMAGE_COINS = 10

_FA_DIGITS = str.maketrans("0123456789", "۰۱۲۳۴۵۶۷۸۹")


def _fa_dt(dt):
    """UTC naive -> شمسی + ساعت تهران (HH:MM)، ارقام فارسی.

    فقط برای نمایش است؛ مقدار ذخیره‌شده همان UTC می‌ماند. اگر jdatetime
    نصب نباشد یا تبدیل خطا بدهد، همان ISO برمی‌گردد تا endpoint نخوابد.
    """
    if not dt:
        return None
    try:
        import jdatetime

        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=datetime.timezone.utc)
        tehran = dt.astimezone(
            datetime.timezone(datetime.timedelta(hours=3, minutes=30))
        )
        jd = jdatetime.datetime.fromgregorian(datetime=tehran.replace(tzinfo=None))
        return jd.strftime("%Y/%m/%d %H:%M").translate(_FA_DIGITS)
    except Exception:
        return dt.isoformat() if hasattr(dt, "isoformat") else None


def _default_image_coins():
    """تعرفه‌ی پیش‌فرض هر تصویر (سکه)؛ از app_settings وگرنه DEFAULT_IMAGE_COINS."""
    row = AppSetting.query.get("image_coin_price")
    try:
        return int(row.value) if row else DEFAULT_IMAGE_COINS
    except (TypeError, ValueError):
        return DEFAULT_IMAGE_COINS


def _entry_gift_enabled():
    """هدیه‌ی ورود برای کاربران جدید؛ از app_settings وگرنه روشن (رفتار فعلی)."""
    row = AppSetting.query.get("entry_gift_enabled")
    if row is None:
        return True
    return str(row.value).strip().lower() not in ("0", "false", "no", "off")


def _token_for(user):
    payload = {
        # PyJWT 2.8+ «sub» را فقط رشته می‌پذیرد؛ موقع خواندن به int برمی‌گردد.
        "sub": str(user.id),
        "exp": datetime.datetime.utcnow() + datetime.timedelta(days=TOKEN_DAYS),
    }
    return jwt.encode(payload, current_app.config["SECRET_KEY"], algorithm="HS256")


def require_auth(f):
    @wraps(f)
    def wrapper(*args, **kwargs):
        auth = request.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            return jsonify({"error": "unauthorized"}), 401
        try:
            data = jwt.decode(
                auth[7:], current_app.config["SECRET_KEY"], algorithms=["HS256"]
            )
            user_id = int(data["sub"])
        except Exception:
            return jsonify({"error": "unauthorized"}), 401
        user = AppUser.query.get(user_id)
        if not user or user.status != "active":
            return jsonify({"error": "unauthorized"}), 401
        g.user = user
        return f(*args, **kwargs)

    return wrapper


def _admin_jwt_ok(tok):
    """توکن JWT ادمین (صادرشده از لاگین یوزر/پسورد) معتبر است؟"""
    try:
        payload = jwt.decode(
            tok, current_app.config["SECRET_KEY"], algorithms=["HS256"]
        )
        return bool(payload.get("admin"))
    except Exception:
        return False


def require_admin(f):
    """ادمین خود اپ با توکن env (X-Admin-Token).

    تا وقتی APP_ADMIN_TOKEN ست نشده، همه‌ی ‎/admin بسته‌اند (fail-closed).
    """
    @wraps(f)
    def wrapper(*args, **kwargs):
        token = current_app.config.get("ADMIN_TOKEN") or ""
        if not token:
            return jsonify({"error": "admin not configured"}), 503
        tok = request.headers.get("X-Admin-Token", "")
        if tok and _admin_jwt_ok(tok):
            return f(*args, **kwargs)
        if tok != token:
            return jsonify({"error": "forbidden"}), 403
        return f(*args, **kwargs)

    return wrapper


# ---------------- auth ----------------


@auth_bp.post("/auth/otp/request")
def otp_request():
    mobile = (request.json or {}).get("mobile", "").strip()
    if not mobile:
        return jsonify({"error": "mobile required"}), 400
    since = datetime.datetime.utcnow() - datetime.timedelta(hours=1)
    recent = (
        AppOtpCode.query.filter(
            AppOtpCode.mobile == mobile, AppOtpCode.created_at >= since
        ).count()
    )
    if recent >= OTP_MAX_PER_HOUR:
        return jsonify({"error": "too many requests"}), 429
    code = f"{random.randint(0, 999999):06d}"
    db.session.add(
        AppOtpCode(
            mobile=mobile,
            code_hash=generate_password_hash(code),
            expires_at=datetime.datetime.utcnow()
            + datetime.timedelta(minutes=OTP_TTL_MINUTES),
        )
    )
    db.session.commit()
    try:
        sms_svc.send_otp_sms(mobile, code)
    except Exception:
        return jsonify({"error": "sms failed"}), 502
    return jsonify({"ok": True})


@auth_bp.post("/auth/otp/verify")
def otp_verify():
    body = request.json or {}
    mobile, code = body.get("mobile", "").strip(), body.get("code", "")
    row = (
        AppOtpCode.query.filter_by(mobile=mobile)
        .order_by(AppOtpCode.id.desc())
        .first()
    )
    if (
        not row
        or row.attempts >= 5
        or row.expires_at < datetime.datetime.utcnow()
        or not check_password_hash(row.code_hash, code)
    ):
        if row:
            row.attempts += 1
            db.session.commit()
        return jsonify({"error": "invalid code"}), 401
    user = AppUser.query.filter_by(mobile=mobile).first()
    is_new = user is None
    if user is None:
        # هدیه‌ی ورود (۱ عکس + ۱ صدا) فقط وقتی تنظیم روشن است؛
        # خاموش یعنی کاربر جدید هیچ هدیه‌ای نمی‌گیرد. کاربران قبلی دست نمی‌خورند.
        gift = _entry_gift_enabled()
        user = AppUser(
            mobile=mobile,
            free_image_credits=1 if gift else 0,
            free_voice_credits=1 if gift else 0,
        )
        db.session.add(user)
    user.last_login_at = datetime.datetime.utcnow()
    db.session.commit()
    return jsonify({"token": _token_for(user), "is_new": is_new})


@auth_bp.get("/auth/me")
@require_auth
def me():
    # فقط موبایل ماسک‌شده (مثل «0905 *** *197» رقیب) — خود شماره
    # هیچ‌وقت به اپ برنمی‌گردد.
    m = g.user.mobile or ""
    if len(m) >= 7:
        masked = f"{m[:4]} *** *{m[-3:]}"
    elif len(m) >= 3:
        masked = f"{m[:1]}***{m[-2:]}"
    else:
        masked = "***"
    return jsonify({"mobile_masked": masked})


# ---------------- content ----------------


@content_bp.get("/categories")
def list_categories():
    cats = (
        AppCategory.query.filter_by(is_active=True)
        .order_by(AppCategory.sort_order)
        .all()
    )
    return jsonify(
        [{"id": c.id, "name": c.name, "slug": c.slug} for c in cats]
    )


@content_bp.get("/worlds")
def list_worlds():
    worlds = (
        AppWorld.query.filter_by(is_active=True)
        .order_by(AppWorld.is_featured.desc(), AppWorld.sort_order)
        .all()
    )
    return jsonify(
        [
            {
                "id": w.id,
                "name": w.name,
                "slug": w.slug,
                "category_id": w.category_id,
                "sample_image_url": w.sample_image_url,
                "coin_price": w.coin_price,
                "is_featured": bool(w.is_featured),
                "is_instagram_trend": bool(w.is_instagram_trend),
                "is_iranian_trend": bool(w.is_iranian_trend),
                "is_sport_trend": bool(w.is_sport_trend),
                "is_seasonal_trend": bool(w.is_seasonal_trend),
                "created_at": w.created_at.isoformat() if w.created_at else None,
            }
            for w in worlds
        ]
    )


# ---------------- device tokens + announcements ----------------
# ثبت توکن FCM دستگاه (برای پوش) و اطلاعیه‌های درون‌برنامه‌ای
# (عمومی، مثل /worlds: کاربر لاگین‌نکرده هم آن‌ها را می‌بیند).


@content_bp.post("/device-tokens/register")
@require_auth
def register_device_token():
    body = request.json or {}
    token = body.get("token") or ""
    if not isinstance(token, str) or not token.strip():
        return jsonify({"error": "token required"}), 400
    token = token.strip()
    platform = body.get("platform") or "android"
    if not isinstance(platform, str):
        platform = "android"
    row = AppDeviceToken.query.filter_by(token=token).first()
    if row is None:
        db.session.add(
            AppDeviceToken(
                user_id=g.user.id, token=token, platform=platform
            )
        )
    else:
        # upsert بر اساس توکن: مالک و زمان به‌روزرسانی تازه می‌شود
        # (حتی اگر همان کاربر دوباره ثبت کند).
        row.user_id = g.user.id
        row.platform = platform
        row.updated_at = datetime.datetime.utcnow()
    db.session.commit()
    return jsonify({"ok": True})


@content_bp.get("/announcements")
def list_announcements_public():
    rows = (
        AppAnnouncement.query.filter_by(active=True)
        .order_by(AppAnnouncement.id.desc())
        .all()
    )
    return jsonify([_announcement_public(a) for a in rows])


def _announcement_public(a):
    return {
        "id": a.id,
        "title": a.title,
        "body": a.body,
        "created_at": a.created_at.isoformat() if a.created_at else None,
    }


def _announcement_admin(a):
    return {**_announcement_public(a), "active": bool(a.active)}


# ---------------- favorites ----------------
# قلب روی دنیاها (مثل رقیب): لیست، افزودن (idempotent)، حذف.


@content_bp.get("/favorites")
@require_auth
def list_favorites():
    rows = (
        AppFavorite.query.filter_by(user_id=g.user.id)
        .order_by(AppFavorite.id.desc())
        .all()
    )
    if not rows:
        return jsonify([])
    worlds = {
        w.id: w
        for w in AppWorld.query.filter(
            AppWorld.id.in_([r.world_id for r in rows]), AppWorld.is_active.is_(True)
        ).all()
    }
    return jsonify(
        [
            {
                "id": w.id,
                "name": w.name,
                "slug": w.slug,
                "category_id": w.category_id,
                "sample_image_url": w.sample_image_url,
                "coin_price": w.coin_price,
                "is_featured": bool(w.is_featured),
                "is_instagram_trend": bool(w.is_instagram_trend),
                "is_iranian_trend": bool(w.is_iranian_trend),
                "is_sport_trend": bool(w.is_sport_trend),
                "is_seasonal_trend": bool(w.is_seasonal_trend),
                "created_at": w.created_at.isoformat() if w.created_at else None,
            }
            for r in rows
            if (w := worlds.get(r.world_id)) is not None
        ]
    )


@content_bp.post("/favorites")
@require_auth
def add_favorite():
    world = AppWorld.query.get((request.json or {}).get("world_id"))
    if not world or not world.is_active:
        return jsonify({"error": "world not found"}), 404
    fav = AppFavorite.query.filter_by(
        user_id=g.user.id, world_id=world.id
    ).first()
    if fav is None:
        db.session.add(AppFavorite(user_id=g.user.id, world_id=world.id))
        db.session.commit()
    return jsonify({"ok": True, "world_id": world.id}), 201


@content_bp.delete("/favorites/<int:world_id>")
@require_auth
def remove_favorite(world_id):
    fav = AppFavorite.query.filter_by(
        user_id=g.user.id, world_id=world_id
    ).first()
    if fav is not None:
        db.session.delete(fav)
        db.session.commit()
    return jsonify({"ok": True})


# ---------------- image likes ----------------
# قلب روی خروجی‌های تصویر (مثل favorites ولی روی generation):
# لیست، افزودن (idempotent)، حذف (idempotent). مالکیت generation
# در افزودن/حذف بررسی می‌شود، وگرنه 404.


@content_bp.get("/image-likes")
@require_auth
def list_image_likes():
    rows = (
        AppImageLike.query.filter_by(user_id=g.user.id)
        .order_by(AppImageLike.id.desc())
        .all()
    )
    if not rows:
        return jsonify([])
    gens = {
        gen.id: gen
        for gen in AppGeneration.query.filter(
            AppGeneration.id.in_([r.generation_id for r in rows]),
            AppGeneration.output_url.isnot(None),
        ).all()
    }
    worlds = {
        w.id: w
        for w in AppWorld.query.filter(
            AppWorld.id.in_(
                [gen.world_id for gen in gens.values() if gen.world_id]
            )
        ).all()
    }
    out = []
    for r in rows:
        gen = gens.get(r.generation_id)
        if gen is None:
            continue
        out.append(
            {
                "id": gen.id,
                "output_url": gen.output_url,
                "world_name": (
                    worlds[gen.world_id].name
                    if gen.world_id in worlds
                    else None
                ),
                "world_id": gen.world_id,
                "created_at": (
                    gen.created_at.isoformat() if gen.created_at else None
                ),
                "status": gen.status,
            }
        )
    return jsonify(out[:50])


@content_bp.post("/image-likes")
@require_auth
def add_image_like():
    gen = AppGeneration.query.get((request.json or {}).get("generation_id"))
    if not gen or gen.user_id != g.user.id:
        return jsonify({"error": "generation not found"}), 404
    like = AppImageLike.query.filter_by(
        user_id=g.user.id, generation_id=gen.id
    ).first()
    if like is None:
        db.session.add(AppImageLike(user_id=g.user.id, generation_id=gen.id))
        db.session.commit()
    return jsonify({"ok": True, "generation_id": gen.id})


@content_bp.delete("/image-likes/<int:generation_id>")
@require_auth
def remove_image_like(generation_id):
    gen = AppGeneration.query.get(generation_id)
    if not gen or gen.user_id != g.user.id:
        return jsonify({"error": "generation not found"}), 404
    like = AppImageLike.query.filter_by(
        user_id=g.user.id, generation_id=generation_id
    ).first()
    if like is not None:
        db.session.delete(like)
        db.session.commit()
    return jsonify({"ok": True})


@content_bp.get("/singers")
def list_singers():
    singers = (
        AppSinger.query.filter_by(is_active=True)
        .order_by(AppSinger.sort_order)
        .all()
    )
    return jsonify(
        [
            {
                "id": s.id,
                "name": s.name,
                "photo_url": s.photo_url,
                "sample_audio_url": s.sample_audio_url,
                "coin_price": s.coin_price,
            }
            for s in singers
        ]
    )


# ---------------- generations ----------------


@generations_bp.post("/generations")
@require_auth
def create_generation():
    body = request.json or {}
    kind = body.get("kind")
    if kind not in ("image", "voice"):
        return jsonify({"error": "kind must be image or voice"}), 400
    world = singer = model = None
    if kind == "image":
        world = AppWorld.query.get(body.get("world_id"))
        if not world or not world.is_active:
            return jsonify({"error": "world not found"}), 404
        if not body.get("input_url"):
            return jsonify({"error": "input_url required"}), 400
        # تک‌مدل فعال به‌ازای هر دسته: دنیا دیگر مدل خودش را ندارد؛
        # همان یک مدل ویرایش فعال استفاده می‌شود (تصمیم مالک، 2026-10-27).
        model = (
            AppModel.query.filter_by(
                kind="image", capability="edit_image", is_active=True
            )
            .order_by(AppModel.id)
            .first()
        )
        if model is not None and model.kind != "image":
            return jsonify({"error": "model kind mismatch"}), 409
        # تعرفه‌ی دنیا اگر ست شده، وگرنه تعرفه‌ی پیش‌فرض (۵ سکه).
        cost = (
            world.coin_price
            if world.coin_price and world.coin_price > 0
            else _default_image_coins()
        )
    else:
        singer = AppSinger.query.get(body.get("singer_id"))
        if not singer or not singer.is_active:
            return jsonify({"error": "singer not found"}), 404
        model = AppModel.query.get(singer.voice_model_id)
        cost = singer.coin_price
    if not model or not model.is_active:
        return jsonify({"error": "model not active"}), 409
    gen = AppGeneration(
        user_id=g.user.id,
        kind=kind,
        world_id=world.id if world else None,
        singer_id=singer.id if singer else None,
        model_id=model.id,
        input_url=body.get("input_url"),
        coin_cost=cost,
    )
    db.session.add(gen)
    db.session.commit()
    # رزرو اتمیک موقع ثبت (به‌جای چک خالی can_afford): بدون رزرو، دو job
    # هم‌زمان با یک اعتبار/موجودی قبول می‌شدند و هر دو تحویل می‌گرفتند.
    # charged=True یعنی پرداخت رزرو شده است، نه تحویل‌شده.
    source = coins_svc.reserve_for_generation(g.user, kind, cost, gen.id)
    if source is None:
        db.session.delete(gen)
        db.session.commit()
        return jsonify({"error": "insufficient coins"}), 402
    gen.charged = True
    db.session.commit()
    # صف: worker با send_task برمی‌دارد (نام تسک در worker.py).
    from .worker import celery_app

    task = (
        "app.worker.process_image_job"
        if kind == "image"
        else "app.worker.process_voice_job"
    )
    celery_app.send_task(task, args=[gen.id])
    return jsonify({"id": gen.id, "status": gen.status}), 201


@generations_bp.get("/generations/<int:gen_id>")
@require_auth
def get_generation(gen_id):
    gen = AppGeneration.query.get(gen_id)
    if not gen or gen.user_id != g.user.id:
        return jsonify({"error": "not found"}), 404
    world = AppWorld.query.get(gen.world_id) if gen.world_id else None
    return jsonify(
        {
            "id": gen.id,
            "kind": gen.kind,
            "status": gen.status,
            "output_url": gen.output_url,
            "charged": bool(gen.charged),
            "error": gen.error_message,
            "world_id": gen.world_id,
            "world_name": world.name if world else None,
        }
    )


@generations_bp.get("/generations/mine")
@require_auth
def my_generations():
    gens = (
        AppGeneration.query.filter_by(user_id=g.user.id)
        .filter(AppGeneration.status != "failed")
        .order_by(AppGeneration.id.desc())
        .limit(50)
        .all()
    )
    world_ids = [gen.world_id for gen in gens if gen.world_id]
    worlds = (
        {w.id: w for w in AppWorld.query.filter(AppWorld.id.in_(world_ids)).all()}
        if world_ids
        else {}
    )
    return jsonify(
        [
            {
                "id": gen.id,
                "kind": gen.kind,
                "status": gen.status,
                "output_url": gen.output_url,
                "world_id": gen.world_id,
                "world_name": (
                    worlds[gen.world_id].name if gen.world_id in worlds else None
                ),
            }
            for gen in gens
        ]
    )


# ---------------- coins ----------------


@coins_bp.get("/coins/balance")
@require_auth
def coin_balance():
    return jsonify(
        {
            "coins": coins_svc.get_balance(g.user),
            "free_image": g.user.free_image_credits,
            "free_voice": g.user.free_voice_credits,
        }
    )


@coins_bp.get("/coins/packs")
def coin_packs():
    packs = (
        AppCoinPack.query.filter_by(is_active=True)
        .order_by(AppCoinPack.sort_order, AppCoinPack.id)
        .all()
    )
    return jsonify(
        [
            {
                "id": p.id,
                "name": p.name,
                "coins": p.coins,
                "price_toman": p.price_toman,
                "bazaar_sku": p.bazaar_sku,
                "badge": p.badge,
                "pack_type": p.pack_type,
                "sort_order": p.sort_order,
            }
            for p in packs
        ]
    )


@coins_bp.post("/coins/purchases")
@require_auth
def coin_purchase():
    body = request.json or {}
    pack = AppCoinPack.query.get(body.get("pack_id"))
    if not pack or not pack.is_active:
        return jsonify({"error": "pack not found"}), 404
    token = body.get("purchase_token")
    if token:
        # همان توکن نباید دو ردیف pending بسازد؛ ردیف قبلی برگردد.
        existing = (
            AppPurchase.query.filter_by(bazaar_purchase_token=token)
            .order_by(AppPurchase.id)
            .first()
        )
        if existing is not None:
            if existing.user_id != g.user.id:
                return jsonify(
                    {"error": "purchase token belongs to another user"}
                ), 400
            return jsonify({"id": existing.id, "status": existing.status}), 201
    purchase = AppPurchase(
        user_id=g.user.id,
        pack_id=pack.id,
        bazaar_purchase_token=token,
        amount_irr=pack.price_toman * 10,
        status="pending",
    )
    db.session.add(purchase)
    db.session.commit()
    # TODO: تایید خرید بازار با کلید RSA خود اپ، بعد credit_coins
    # و status=consumed. تا آن موقع خرید pending می‌ماند.
    return jsonify({"id": purchase.id, "status": purchase.status}), 201


# ---------------- uploads ----------------

_HEIF_BRANDS = {b"heic", b"heix", b"hevc", b"hevx", b"mif1", b"msf1"}
MAX_UPLOAD_BYTES = 20 * 1024 * 1024


def _is_heif(raw: bytes) -> bool:
    # HEIF/HEIC را از روی محتوا تشخیص بده، نه اسم فایل (اسم‌ها دروغ می‌گویند:
    # فایل HEIC با اسم jpg هم دیده شده). ساختار: «ftyp» در آفست ۴ + برند در آفست ۸.
    return len(raw) >= 12 and raw[4:8] == b"ftyp" and raw[8:12] in _HEIF_BRANDS


@uploads_bp.post("/uploads")
@require_auth
def upload():
    f = request.files.get("file")
    if not f:
        return jsonify({"error": "file required"}), 400
    from .services import storage as storage_svc

    raw = f.read()
    if len(raw) > MAX_UPLOAD_BYTES:
        return jsonify({"error": "file too large"}), 400
    filename = f.filename or "upload.bin"
    if _is_heif(raw):
        # آیفون HEIC می‌فرستد و ارائه‌دهنده قبولش نمی‌کند؛ همان‌جا به JPEG
        # تبدیلش کن تا نسل بعداً با Invalid image data نگیرد. بقیه‌ی نوع‌ها
        # دست‌نخورده عبور می‌کنند و داور نهایی ارائه‌دهنده است.
        try:
            import io as _io

            from PIL import Image, ImageOps
            import pillow_heif

            pillow_heif.register_heif_opener()
            img = ImageOps.exif_transpose(Image.open(_io.BytesIO(raw))).convert("RGB")
            buf = _io.BytesIO()
            img.save(buf, format="JPEG", quality=90)
            raw = buf.getvalue()
            filename = os.path.splitext(filename)[0] + ".jpg"
        except Exception:
            return jsonify({"error": "heic conversion failed"}), 400
    url = storage_svc.upload_bytes(raw, filename)
    return jsonify({"url": url})


# ---------------- gallery ----------------


@gallery_bp.get("/gallery/mine")
@require_auth
def my_gallery():
    rows = (
        AppGallery.query.filter_by(user_id=g.user.id)
        .order_by(AppGallery.id.desc())
        .limit(50)
        .all()
    )
    return jsonify(
        [
            {
                "id": r.id,
                "generation_id": r.generation_id,
                "image_url": r.image_url,
                "status": r.status,
                "published_in_app": bool(r.published_in_app),
            }
            for r in rows
        ]
    )


@gallery_bp.get("/gallery/public")
def public_gallery():
    rows = (
        AppGallery.query.filter_by(status="approved", published_in_app=True)
        .order_by(AppGallery.id.desc())
        .limit(50)
        .all()
    )
    return jsonify([{"id": r.id, "image_url": r.image_url} for r in rows])


# ---------------- world detail ----------------


PUBLIC_SETTINGS = ("about_us", "support_info", "bug_report_info", "bazaar_url", "rules_privacy", "entry_gift_enabled")


@content_bp.get("/app/settings")
def public_app_settings():
    """تنظیمات عمومی اپ (درباره ما، پشتیبانی، لینک‌ها) — بدون احراز هویت،
    فقط کلیدهای سفید؛ هیچ رازی از app_settings بیرون نمی‌آید."""
    rows = AppSetting.query.filter(AppSetting.key.in_(PUBLIC_SETTINGS)).all()
    out = {r.key: r.value for r in rows}
    return jsonify({k: out.get(k, "") for k in PUBLIC_SETTINGS})


@content_bp.get("/worlds/<int:world_id>")
def world_detail(world_id):
    w = AppWorld.query.get(world_id)
    if not w or not w.is_active:
        return jsonify({"error": "world not found"}), 404
    # hidden_prompt هرگز به اپ فرستاده نمی‌شود.
    return jsonify(
        {
            "id": w.id,
            "name": w.name,
            "slug": w.slug,
            "category_id": w.category_id,
            "sample_image_url": w.sample_image_url,
            "coin_price": w.coin_price,
            "is_featured": bool(w.is_featured),
            "is_instagram_trend": bool(w.is_instagram_trend),
            "is_iranian_trend": bool(w.is_iranian_trend),
            "is_sport_trend": bool(w.is_sport_trend),
            "is_seasonal_trend": bool(w.is_seasonal_trend),
            "created_at": w.created_at.isoformat() if w.created_at else None,
        }
    )


# ---------------- gallery submit ----------------


@gallery_bp.post("/gallery/submit")
@require_auth
def submit_gallery():
    gen_id = (request.json or {}).get("generation_id")
    gen = AppGeneration.query.get(gen_id)
    if (
        not gen
        or gen.user_id != g.user.id
        or gen.kind != "image"
        or gen.status != "done"
        or not gen.output_url
    ):
        return jsonify({"error": "generation not submittable"}), 400
    existing = AppGallery.query.filter_by(generation_id=gen.id).first()
    if existing:
        return jsonify(
            {"id": existing.id, "status": existing.status, "duplicate": True}
        )
    row = AppGallery(
        user_id=g.user.id,
        generation_id=gen.id,
        image_url=gen.output_url,
        status="pending",
    )
    db.session.add(row)
    db.session.commit()
    return jsonify({"id": row.id, "status": row.status}), 201


# ---------------- purchases: verify + pending + ledger ----------------


@coins_bp.post("/coins/purchases/verify")
@require_auth
def verify_purchase_route():
    """وریفای خرید بازار و شارژ سکه (idempotent).

    ورودی: pack_id، purchase_token، purchase_data (JSON خام بازار)،
    signature. بعد از وریفای موفق، اپ باید خرید را در بازار consume
    کند تا همان پک بلافاصله دوباره قابل خرید باشد.
    """
    body = request.json or {}
    pack = AppCoinPack.query.get(body.get("pack_id"))
    if pack is not None and not pack.is_active:
        return jsonify({"error": "pack not found"}), 404
    try:
        purchase, already = bazaar_svc.verify_purchase(
            g.user,
            pack,
            body.get("purchase_token"),
            body.get("purchase_data") or "",
            body.get("signature") or "",
        )
    except ValueError as exc:
        return jsonify({"error": str(exc)}), 400
    except RuntimeError as exc:
        return jsonify({"error": str(exc)}), 503
    resp_pack = AppCoinPack.query.get(purchase.pack_id)
    return jsonify(
        {
            "id": purchase.id,
            "status": purchase.status,
            "coins": resp_pack.coins if resp_pack else 0,
            "already_credited": already,
            "balance": coins_svc.get_balance(g.user),
        }
    )


@coins_bp.get("/coins/purchases/pending")
@require_auth
def pending_purchases():
    rows = (
        AppPurchase.query.filter_by(user_id=g.user.id, status="pending")
        .order_by(AppPurchase.id.desc())
        .all()
    )
    return jsonify(
        [
            {
                "id": r.id,
                "pack_id": r.pack_id,
                "amount_irr": r.amount_irr,
                "purchase_token": r.bazaar_purchase_token,
            }
            for r in rows
        ]
    )


@coins_bp.get("/coins/purchases/mine")
@require_auth
def my_purchases():
    """سابقه‌ی پرداخت‌های خود کاربر (برای بخش سابقه پرداخت پروفایل)."""
    rows = (
        AppPurchase.query.filter_by(user_id=g.user.id)
        .order_by(AppPurchase.id.desc())
        .limit(50)
        .all()
    )
    packs = {pk.id: pk for pk in AppCoinPack.query.all()}
    return jsonify(
        [
            {
                "id": r.id,
                "pack_name": packs[r.pack_id].name if r.pack_id in packs else "",
                "coins": packs[r.pack_id].coins if r.pack_id in packs else 0,
                "amount_irr": r.amount_irr,
                "status": r.status,
                "created_at": r.created_at.isoformat() if r.created_at else None,
                "created_at_fa": _fa_dt(r.created_at),
            }
            for r in rows
        ]
    )


@coins_bp.get("/coins/ledger")
@require_auth
def coin_ledger():
    rows = (
        AppCoinLedger.query.filter_by(user_id=g.user.id)
        .order_by(AppCoinLedger.id.desc())
        .limit(50)
        .all()
    )
    return jsonify(
        [
            {
                "id": r.id,
                "delta": r.delta,
                "reason": r.reason,
                "ref_type": r.ref_type,
                "ref_id": r.ref_id,
            }
            for r in rows
        ]
    )


# ---------------- admin: models ----------------


def _model_public(m):
    cfg = m.config_json or {}
    prov = cfg.get("__provider__") or {}
    return {
        "id": m.id,
        "kind": m.kind,
        "name": m.name,
        "slug": m.slug,
        "provider": m.provider,
        "provider_type": m.provider_type,
        "provider_model_name": m.provider_model_name,
        # base_url مثل نورا از __provider__ می‌آید؛ ستون fallback است.
        "base_url": prov.get("base_url") or m.base_url,
        "capability": m.capability,
        "pricing_type": m.pricing_type,
        "tokenizer_encoding": m.tokenizer_encoding,
        "description": m.description,
        "default_max_output_tokens": m.default_max_output_tokens,
        "max_prompt_chars": m.max_prompt_chars,
        "max_history_messages": m.max_history_messages,
        "max_history_tokens": m.max_history_tokens,
        "is_active": bool(m.is_active),
        # کلید هرگز برگردانده نمی‌شود؛ فقط بودنش اعلام می‌شود.
        "has_credentials": bool(prov.get("api_key") or cfg.get("api_key")),
    }


# Reserved config_json key holding form-provided provider credentials (Nourai).
_PROVIDER_CREDS_KEY = "__provider__"


def _extract_provider_creds(config_json):
    """(base_url, api_key) stored on the model via the admin form."""
    creds = (config_json or {}).get(_PROVIDER_CREDS_KEY) or {}
    return (creds.get("base_url") or "", creds.get("api_key") or "")


def _store_provider_creds(config_json, base_url, api_key):
    """Merge form-provided credentials into config_json (write path only).

    The reserved __provider__ key can only be written through the dedicated
    base_url/api_key fields, never via raw config_json (Nourai's rule).
    """
    cfg = dict(config_json or {})
    creds = dict(cfg.get(_PROVIDER_CREDS_KEY) or {})
    if base_url is not None:
        creds["base_url"] = base_url
    if api_key:
        creds["api_key"] = api_key
    cfg[_PROVIDER_CREDS_KEY] = creds
    return cfg


def _slugify(text):
    out = []
    for ch in (text or "").lower():
        out.append(ch if ch.isalnum() else "-")
    return "".join(out).strip("-") or "model"


def _current_admin_id():
    """id کاربر ادمین فعلی از روی JWT لاگین؛ برای لاگین توکنی None."""
    tok = request.headers.get("X-Admin-Token", "")
    try:
        payload = jwt.decode(
            tok, current_app.config["SECRET_KEY"], algorithms=["HS256"]
        )
        if payload.get("admin") and payload.get("admin_user"):
            u = AppAdminUser.query.filter_by(username=payload["admin_user"]).first()
            return u.id if u else None
    except Exception:
        pass
    return None


def _paginate(query, default_size=50):
    """صفحه‌بندی مثل نورا: ?page=&page_size= (سقف ۲۰۰)."""
    try:
        page = max(int(request.args.get("page", 1)), 1)
    except ValueError:
        page = 1
    try:
        page_size = min(max(int(request.args.get("page_size", default_size)), 1), 200)
    except ValueError:
        page_size = default_size
    total = query.count()
    items = query.offset((page - 1) * page_size).limit(page_size).all()
    return items, {"total": total, "page": page, "page_size": page_size}


@admin_bp.get("/admin/models")
@require_admin
def admin_list_models():
    models = AppModel.query.order_by(AppModel.id).all()
    return jsonify([_model_public(m) for m in models])


@admin_bp.patch("/admin/models/<int:model_id>")
@require_admin
def admin_update_model(model_id):
    """فرم مدل ادمین خود اپ، مثل نورا: کلید write-only است؛ اگر api_key
    فرستاده نشود، مقدار قبلی می‌ماند. __provider__ فقط از راه فیلدهای
    base_url/api_key نوشته می‌شود، هرگز از راه config_json خام."""
    m = AppModel.query.get(model_id)
    if not m:
        return jsonify({"error": "model not found"}), 404
    body = request.json or {}
    for field in (
        "name",
        "provider",
        "provider_type",
        "provider_model_name",
        "pricing_type",
        "tokenizer_encoding",
        "description",
        "default_max_output_tokens",
        "max_prompt_chars",
        "max_history_messages",
        "max_history_tokens",
    ):
        if field in body:
            setattr(m, field, body[field])
    if "capability" in body and body["capability"] not in (
        "generate_image",
        "edit_image",
        "image",
    ):
        return jsonify({"error": "invalid capability"}), 400
    if body.get("capability") in ("generate_image", "edit_image", "image"):
        m.capability = body["capability"]
    if "is_active" in body:
        m.is_active = bool(body["is_active"])
        # تک‌مدل فعال به‌ازای هر دسته: فعال‌کردن یک مدل، بقیه‌ی همان
        # kind و capability را غیرفعال می‌کند (تصمیم مالک، 2026-10-27).
        if m.is_active:
            AppModel.query.filter(
                AppModel.id != m.id,
                AppModel.kind == m.kind,
                AppModel.capability == m.capability,
                AppModel.is_active.is_(True),
            ).update({"is_active": False})
    if "config_json" in body and isinstance(body["config_json"], dict):
        incoming = {
            k: v for k, v in body["config_json"].items() if k != _PROVIDER_CREDS_KEY
        }
        cfg = dict(m.config_json or {})
        cfg.update(incoming)
        m.config_json = cfg
    if body.get("base_url") is not None or body.get("api_key"):
        m.config_json = _store_provider_creds(
            m.config_json, body.get("base_url"), body.get("api_key")
        )
        if body.get("base_url") is not None:
            m.base_url = body["base_url"]
    db.session.commit()
    return jsonify(_model_public(m))


# ---------------- public trends (home, no auth) ----------------


@content_bp.get("/trends")
def list_trends_public():
    """ترندها برای خانه‌ی اپ؛ بدون احراز هویت (کاربر لاگین‌نکرده هم
    خانه را می‌بیند). مرتب بر اساس sort_order؛ world_ids به ترتیب
    پایدار عضویت (id ردیف عضویت)."""
    trends = AppTrend.query.order_by(AppTrend.sort_order, AppTrend.id).all()
    out = []
    for t in trends:
        rows = (
            AppTrendWorld.query.filter_by(trend_id=t.id)
            .order_by(AppTrendWorld.id)
            .all()
        )
        out.append(
            {
                "id": t.id,
                "name": t.name,
                "key": t.key,
                "sort_order": t.sort_order,
                "display_type": t.display_type,
                "world_ids": [r.world_id for r in rows],
            }
        )
    return jsonify(out)


# ---------------- admin: trends ----------------
# ترند مستقل + همگام‌سازی پرچم‌های دنیا: ۴ ترند seeded با key به
# پرچم‌های is_*_trend دنیا وصل‌اند. بخش‌های خانه از روی پرچم‌ها
# خوانده می‌شوند، پس attach/detach/delete ترند seeded و ذخیره‌ی
# دنیا از راه فرم، هر دو طرف (پرچم و عضویت) را همگام نگه می‌دارند.

SEEDED_TREND_FLAGS = {
    "instagram": "is_instagram_trend",
    "iranian": "is_iranian_trend",
    "sport": "is_sport_trend",
    "seasonal": "is_seasonal_trend",
}


def _trend_admin(t):
    rows = AppTrendWorld.query.filter_by(trend_id=t.id).all()
    worlds = (
        {w.id: w for w in AppWorld.query.filter(
            AppWorld.id.in_([r.world_id for r in rows])
        ).all()}
        if rows
        else {}
    )
    members = [
        {"id": r.world_id, "name": worlds[r.world_id].name}
        for r in rows
        if r.world_id in worlds
    ]
    return {
        "id": t.id,
        "name": t.name,
        "key": t.key,
        "sort_order": t.sort_order,
        "display_type": t.display_type,
        "created_at": t.created_at.isoformat() if t.created_at else None,
        "world_ids": [m["id"] for m in members],
        "worlds": members,
        "member_count": len(members),
    }


def _valid_display_type(value):
    return value in ("card", "round")


def _sync_world_trend_memberships(world):
    """عضویت‌های ۴ ترند seeded را با پرچم‌های فعلی دنیا همگام می‌کند.

    فقط وقتی ردیف ترند seeded وجود دارد کاری می‌کند؛ نبودنش (پیش از
    مهاجرت/seed) یعنی هیچ عضویتی ساخته/حذف نمی‌شود.
    """
    for key, flag in SEEDED_TREND_FLAGS.items():
        trend = AppTrend.query.filter_by(key=key).first()
        if not trend:
            continue
        member = AppTrendWorld.query.filter_by(
            trend_id=trend.id, world_id=world.id
        ).first()
        if getattr(world, flag):
            if member is None:
                db.session.add(
                    AppTrendWorld(trend_id=trend.id, world_id=world.id)
                )
        elif member is not None:
            db.session.delete(member)


def _set_world_trend_flag(world, trend_key, value):
    flag = SEEDED_TREND_FLAGS.get(trend_key)
    if flag:
        setattr(world, flag, bool(value))


@admin_bp.get("/admin/trends")
@require_admin
def admin_list_trends():
    # ترتیب بخش‌ها: sort_order صعودی، بعد id (تعیین‌کننده برای تساوی‌ها).
    # این همان ترتیبی است که GET /trends عمومی هم برمی‌گرداند.
    trends = AppTrend.query.order_by(AppTrend.sort_order, AppTrend.id).all()
    return jsonify([_trend_admin(t) for t in trends])


@admin_bp.post("/admin/trends")
@require_admin
def admin_create_trend():
    body = request.json or {}
    if not (body.get("name") or "").strip():
        return jsonify({"error": "name required"}), 400
    display_type = body.get("display_type", "card")
    if not _valid_display_type(display_type):
        return jsonify({"error": "display_type must be card or round"}), 400
    if body.get("sort_order") is None:
        max_order = db.session.query(db.func.max(AppTrend.sort_order)).scalar()
        sort_order = (max_order or 0) + 1
    else:
        try:
            sort_order = int(body["sort_order"])
        except (TypeError, ValueError):
            return jsonify({"error": "sort_order must be int"}), 400
    t = AppTrend(
        name=body["name"].strip(),
        key=None,
        sort_order=sort_order,
        display_type=display_type,
    )
    db.session.add(t)
    db.session.commit()
    return jsonify(_trend_admin(t)), 201


@admin_bp.put("/admin/trends/<int:trend_id>")
@require_admin
def admin_update_trend(trend_id):
    t = AppTrend.query.get(trend_id)
    if not t:
        return jsonify({"error": "trend not found"}), 404
    body = request.json or {}
    if "name" in body:
        if not (body["name"] or "").strip():
            return jsonify({"error": "name required"}), 400
        t.name = body["name"].strip()
    if "display_type" in body:
        if not _valid_display_type(body["display_type"]):
            return jsonify({"error": "display_type must be card or round"}), 400
        t.display_type = body["display_type"]
    if "sort_order" in body:
        try:
            t.sort_order = int(body["sort_order"])
        except (TypeError, ValueError):
            return jsonify({"error": "sort_order must be int"}), 400
    db.session.commit()
    return jsonify(_trend_admin(t))


@admin_bp.post("/admin/trends/<int:trend_id>/worlds")
@require_admin
def admin_trend_attach_world(trend_id):
    t = AppTrend.query.get(trend_id)
    if not t:
        return jsonify({"error": "trend not found"}), 404
    world = AppWorld.query.get((request.json or {}).get("world_id"))
    if not world:
        return jsonify({"error": "world not found"}), 404
    if AppTrendWorld.query.filter_by(
        trend_id=t.id, world_id=world.id
    ).first() is None:
        db.session.add(AppTrendWorld(trend_id=t.id, world_id=world.id))
    _set_world_trend_flag(world, t.key, True)
    db.session.commit()
    return jsonify(_trend_admin(t))


@admin_bp.delete("/admin/trends/<int:trend_id>/worlds/<int:world_id>")
@require_admin
def admin_trend_detach_world(trend_id, world_id):
    t = AppTrend.query.get(trend_id)
    if not t:
        return jsonify({"error": "trend not found"}), 404
    world = AppWorld.query.get(world_id)
    if not world:
        return jsonify({"error": "world not found"}), 404
    member = AppTrendWorld.query.filter_by(
        trend_id=t.id, world_id=world.id
    ).first()
    if member is not None:
        db.session.delete(member)
    _set_world_trend_flag(world, t.key, False)
    db.session.commit()
    return jsonify(_trend_admin(t))


@admin_bp.delete("/admin/trends/<int:trend_id>")
@require_admin
def admin_delete_trend(trend_id):
    t = AppTrend.query.get(trend_id)
    if not t:
        return jsonify({"error": "trend not found"}), 404
    members = AppTrendWorld.query.filter_by(trend_id=t.id).all()
    if t.key in SEEDED_TREND_FLAGS:
        for m in members:
            world = AppWorld.query.get(m.world_id)
            if world:
                _set_world_trend_flag(world, t.key, False)
    for m in members:
        db.session.delete(m)
    db.session.delete(t)
    db.session.commit()
    return jsonify({"ok": True})


# ---------------- admin: worlds ----------------


def _world_admin(w):
    return {
        "id": w.id,
        "name": w.name,
        "slug": w.slug,
        "category_id": w.category_id,
        "sample_image_url": w.sample_image_url,
        "hidden_prompt": w.hidden_prompt,
        "model_id": w.model_id,
        "coin_price": w.coin_price,
        "is_featured": bool(w.is_featured),
        "is_instagram_trend": bool(w.is_instagram_trend),
        "is_iranian_trend": bool(w.is_iranian_trend),
        "is_sport_trend": bool(w.is_sport_trend),
        "is_seasonal_trend": bool(w.is_seasonal_trend),
        "is_active": bool(w.is_active),
        "sort_order": w.sort_order,
    }


@admin_bp.get("/admin/worlds")
@require_admin
def admin_list_worlds():
    worlds = AppWorld.query.order_by(AppWorld.sort_order, AppWorld.id).all()
    return jsonify([_world_admin(w) for w in worlds])


@admin_bp.post("/admin/worlds")
@require_admin
def admin_create_world():
    body = request.json or {}
    for field in ("name", "slug", "hidden_prompt", "model_id"):
        if not body.get(field):
            return jsonify({"error": f"{field} required"}), 400
    model = AppModel.query.get(body["model_id"])
    if not model or model.kind != "image":
        return jsonify({"error": "image model not found"}), 400
    if AppWorld.query.filter_by(slug=body["slug"]).first():
        return jsonify({"error": "slug exists"}), 409
    w = AppWorld(
        name=body["name"],
        slug=body["slug"],
        category_id=body.get("category_id"),
        sample_image_url=body.get("sample_image_url"),
        hidden_prompt=body["hidden_prompt"],
        model_id=body["model_id"],
        coin_price=body.get("coin_price", DEFAULT_IMAGE_COINS),
        is_featured=bool(body.get("is_featured", False)),
        is_instagram_trend=bool(body.get("is_instagram_trend", False)),
        is_iranian_trend=bool(body.get("is_iranian_trend", False)),
        is_sport_trend=bool(body.get("is_sport_trend", False)),
        is_seasonal_trend=bool(body.get("is_seasonal_trend", False)),
        is_active=bool(body.get("is_active", True)),
        sort_order=body.get("sort_order", 0),
    )
    db.session.add(w)
    db.session.flush()
    _sync_world_trend_memberships(w)
    db.session.commit()
    return jsonify(_world_admin(w)), 201


@admin_bp.patch("/admin/worlds/<int:world_id>")
@require_admin
def admin_update_world(world_id):
    w = AppWorld.query.get(world_id)
    if not w:
        return jsonify({"error": "world not found"}), 404
    body = request.json or {}
    if "model_id" in body:
        model = AppModel.query.get(body["model_id"])
        if not model or model.kind != "image":
            return jsonify({"error": "image model not found"}), 400
    if body.get("slug"):
        clash = AppWorld.query.filter_by(slug=body["slug"]).first()
        if clash and clash.id != world_id:
            return jsonify({"error": "slug exists"}), 409
    for field in (
        "name",
        "slug",
        "category_id",
        "sample_image_url",
        "hidden_prompt",
        "model_id",
        "coin_price",
        "sort_order",
    ):
        if field in body:
            setattr(w, field, body[field])
    for field in ("is_featured", "is_instagram_trend", "is_iranian_trend", "is_sport_trend", "is_seasonal_trend", "is_active"):
        if field in body:
            setattr(w, field, bool(body[field]))
    _sync_world_trend_memberships(w)
    db.session.commit()
    return jsonify(_world_admin(w))


@admin_bp.delete("/admin/worlds/<int:world_id>")
@require_admin
def admin_delete_world(world_id):
    w = AppWorld.query.get(world_id)
    if not w:
        return jsonify({"error": "world not found"}), 404
    w.is_active = False
    db.session.commit()
    return jsonify({"ok": True})


# ---------------- admin: packs ----------------


def _pack_admin(p):
    return {
        "id": p.id,
        "name": p.name,
        "coins": p.coins,
        "price_toman": p.price_toman,
        "bazaar_sku": p.bazaar_sku,
        "badge": p.badge,
        "pack_type": p.pack_type,
        "sort_order": p.sort_order,
        "is_active": bool(p.is_active),
    }


@admin_bp.get("/admin/packs")
@require_admin
def admin_list_packs():
    packs = AppCoinPack.query.order_by(AppCoinPack.sort_order, AppCoinPack.id).all()
    return jsonify([_pack_admin(p) for p in packs])


@admin_bp.post("/admin/packs")
@require_admin
def admin_create_pack():
    body = request.json or {}
    for field in ("coins", "price_toman", "bazaar_sku"):
        if body.get(field) is None:
            return jsonify({"error": f"{field} required"}), 400
    if AppCoinPack.query.filter_by(bazaar_sku=body["bazaar_sku"]).first():
        return jsonify({"error": "sku exists"}), 409
    p = AppCoinPack(
        name=body.get("name", ""),
        coins=body["coins"],
        price_toman=body["price_toman"],
        bazaar_sku=body["bazaar_sku"],
        badge=body.get("badge"),
        pack_type=body.get("pack_type"),
        sort_order=body.get("sort_order", 0),
        is_active=bool(body.get("is_active", True)),
    )
    db.session.add(p)
    db.session.commit()
    return jsonify(_pack_admin(p)), 201


@admin_bp.patch("/admin/packs/<int:pack_id>")
@require_admin
def admin_update_pack(pack_id):
    p = AppCoinPack.query.get(pack_id)
    if not p:
        return jsonify({"error": "pack not found"}), 404
    body = request.json or {}
    if body.get("bazaar_sku"):
        clash = AppCoinPack.query.filter_by(bazaar_sku=body["bazaar_sku"]).first()
        if clash and clash.id != pack_id:
            return jsonify({"error": "sku exists"}), 409
    for field in ("name", "coins", "price_toman", "bazaar_sku", "badge", "pack_type", "sort_order"):
        if field in body:
            setattr(p, field, body[field])
    if "is_active" in body:
        p.is_active = bool(body["is_active"])
    db.session.commit()
    return jsonify(_pack_admin(p))


@admin_bp.delete("/admin/packs/<int:pack_id>")
@require_admin
def admin_delete_pack(pack_id):
    p = AppCoinPack.query.get(pack_id)
    if not p:
        return jsonify({"error": "pack not found"}), 404
    p.is_active = False
    db.session.commit()
    return jsonify({"ok": True})


# ---------------- admin: gallery (fully separate) ----------------


@admin_bp.get("/admin/gallery/pending")
@require_admin
def admin_gallery_pending():
    rows = (
        AppGallery.query.filter_by(status="pending")
        .order_by(AppGallery.id)
        .all()
    )
    out = []
    for r in rows:
        user = AppUser.query.get(r.user_id)
        out.append(
            {
                "id": r.id,
                "user_id": r.user_id,
                "mobile": user.mobile if user else None,
                "generation_id": r.generation_id,
                "image_url": r.image_url,
                "status": r.status,
            }
        )
    return jsonify(out)


@admin_bp.post("/admin/gallery/<int:row_id>/approve")
@require_admin
def admin_gallery_approve(row_id):
    r = AppGallery.query.get(row_id)
    if not r:
        return jsonify({"error": "not found"}), 404
    if r.status != "pending":
        return jsonify({"error": "not pending"}), 409
    r.status = "approved"
    r.published_in_app = True
    # گالری کاملاً جداست: هیچ کپی‌ای در نورا نوشته نمی‌شود.
    r.reviewed_by_admin_id = _current_admin_id()
    r.reviewed_at = datetime.datetime.utcnow()
    r.rejection_reason = None
    db.session.commit()
    return jsonify({"id": r.id, "status": r.status, "published_in_app": True})


@admin_bp.post("/admin/gallery/<int:row_id>/reject")
@require_admin
def admin_gallery_reject(row_id):
    r = AppGallery.query.get(row_id)
    if not r:
        return jsonify({"error": "not found"}), 404
    if r.status != "pending":
        return jsonify({"error": "not pending"}), 409
    body = request.json or {}
    r.status = "rejected"
    r.published_in_app = False
    r.reviewed_by_admin_id = _current_admin_id()
    r.reviewed_at = datetime.datetime.utcnow()
    r.rejection_reason = (body.get("reason") or "").strip() or None
    db.session.commit()
    return jsonify({"id": r.id, "status": r.status})


@admin_bp.post("/admin/gallery/<int:row_id>/requeue")
@require_admin
def admin_gallery_requeue(row_id):
    """بازگردانی به صف انتظار (لغو تصمیم قبلی)."""
    r = AppGallery.query.get(row_id)
    if not r:
        return jsonify({"error": "not found"}), 404
    if r.status == "pending":
        return jsonify({"error": "already pending"}), 409
    r.status = "pending"
    r.published_in_app = False
    r.reviewed_by_admin_id = None
    r.reviewed_at = None
    r.rejection_reason = None
    db.session.commit()
    return jsonify({"id": r.id, "status": r.status})


@admin_bp.delete("/admin/gallery/<int:row_id>")
@require_admin
def admin_gallery_delete(row_id):
    r = AppGallery.query.get(row_id)
    if not r:
        return jsonify({"error": "not found"}), 404
    db.session.delete(r)
    db.session.commit()
    return jsonify({"ok": True})


@admin_bp.get("/admin/generations")
@require_admin
def admin_list_generations():
    """همه‌ی تولیدهای کاربران، جدیدترین اول (برای تب «همه‌ی تولیدها»ی ادمین)."""
    query = AppGeneration.query.filter(AppGeneration.status != "failed").order_by(AppGeneration.id.desc())
    rows, meta = _paginate(query)
    users = {u.id: u.mobile for u in AppUser.query.all()}
    worlds = {w.id: w.name for w in AppWorld.query.all()}
    models = {m.id: m.name for m in AppModel.query.all()}
    out = []
    for r in rows:
        out.append(
            {
                "id": r.id,
                "user_id": r.user_id,
                "mobile": users.get(r.user_id),
                "world": worlds.get(r.world_id),
                "model": models.get(r.model_id),
                "status": r.status,
                "coin_cost": r.coin_cost,
                "output_url": r.output_url,
                "created_at": r.created_at.isoformat() if r.created_at else None,
            }
        )
    return jsonify({"items": out, **meta})


@admin_bp.get("/admin/inputs")
@require_admin
def admin_list_inputs():
    """عکس‌های ورودی کاربران (سلفی‌ها)، جدیدترین اول — برای تب «ورودی‌ها»ی ادمین.
    هر ردیف یک آدرس ورودی متمایز است با شمار استفاده (تعداد نسل‌هایی که از آن
    استفاده کرده‌اند). فقط آدرس است؛ فایل روی دیسک خود سرور است."""
    grouped = (
        db.session.query(
            AppGeneration.input_url.label("input_url"),
            AppGeneration.user_id.label("user_id"),
            func.count(AppGeneration.id).label("uses"),
            func.min(AppGeneration.created_at).label("first_used"),
            func.max(AppGeneration.created_at).label("last_used"),
        )
        .filter(AppGeneration.input_url.isnot(None))
        .group_by(AppGeneration.input_url, AppGeneration.user_id)
        .order_by(func.max(AppGeneration.created_at).desc())
    )
    try:
        page = max(int(request.args.get("page", 1)), 1)
    except ValueError:
        page = 1
    try:
        page_size = min(max(int(request.args.get("page_size", 50)), 1), 200)
    except ValueError:
        page_size = 50
    total = db.session.query(func.count()).select_from(grouped.subquery()).scalar()
    rows = grouped.offset((page - 1) * page_size).limit(page_size).all()
    users = {u.id: u.mobile for u in AppUser.query.all()}
    items = [
        {
            "input_url": r.input_url,
            "user_id": r.user_id,
            "mobile": users.get(r.user_id),
            "uses": r.uses,
            "first_used": r.first_used.isoformat() if r.first_used else None,
            "last_used": r.last_used.isoformat() if r.last_used else None,
        }
        for r in rows
    ]
    usages = (
        db.session.query(func.count(AppGeneration.id))
        .filter(AppGeneration.input_url.isnot(None))
        .scalar()
    )
    users_count = (
        db.session.query(func.count(func.distinct(AppGeneration.user_id)))
        .filter(AppGeneration.input_url.isnot(None))
        .scalar()
    )
    return jsonify(
        {
            "items": items,
            "total": total,
            "page": page,
            "page_size": page_size,
            "totals": {
                "distinct_inputs": total,
                "usages": usages,
                "users": users_count,
            },
        }
    )


@admin_bp.delete("/admin/inputs")
@require_admin
def admin_delete_input():
    """حذف فیزیکی یک عکس ورودی از روی سرور + خالی‌کردن آدرس آن در نسل‌ها.
    خروجی‌ها، تاریخچه و سکه دست نمی‌خورند. قانون عدم حذف فقط شامل عکس‌های
    تولیدشده است، نه ورودی‌های کاربر."""
    from urllib.parse import urlparse

    data = request.get_json(silent=True) or {}
    input_url = (data.get("input_url") or "").strip()
    if not input_url:
        return jsonify({"error": "input_url required"}), 400
    if current_app.config.get("S3_ENDPOINT"):
        return jsonify({"error": "S3-backed storage: file delete not supported here"}), 400
    parsed = urlparse(input_url)
    if parsed.netloc not in ("", "chehre.inourai.ir", "171.22.25.152:5001") or not parsed.path.startswith(
        "/static/uploads/"
    ):
        return jsonify({"error": "only local upload files can be deleted"}), 400
    filename = os.path.basename(parsed.path)
    if not filename or filename in (".", "..") or "/" in filename or "\\" in filename:
        return jsonify({"error": "bad filename"}), 400
    busy = AppGeneration.query.filter(
        AppGeneration.input_url == input_url,
        AppGeneration.status.in_(["processing", "queued"]),
    ).first()
    if busy:
        return jsonify({"error": "input in use by an in-flight generation"}), 409
    uploads_dir = os.path.realpath(os.path.join(current_app.static_folder, "uploads"))
    path = os.path.realpath(os.path.join(uploads_dir, filename))
    if os.path.commonpath([path, uploads_dir]) != uploads_dir:
        return jsonify({"error": "bad path"}), 400
    deleted_file = False
    if os.path.exists(path):
        os.unlink(path)
        deleted_file = True
    nulled = AppGeneration.query.filter(AppGeneration.input_url == input_url).update(
        {"input_url": None}
    )
    db.session.commit()
    return jsonify({"ok": True, "deleted_file": deleted_file, "nulled": nulled})


# ---------------- admin: categories ----------------


def _category_admin(c):
    return {
        "id": c.id,
        "name": c.name,
        "slug": c.slug,
        "sort_order": c.sort_order,
        "is_active": bool(c.is_active),
    }


@admin_bp.get("/admin/categories")
@require_admin
def admin_list_categories():
    cats = AppCategory.query.order_by(AppCategory.sort_order, AppCategory.id).all()
    return jsonify([_category_admin(c) for c in cats])


@admin_bp.post("/admin/categories")
@require_admin
def admin_create_category():
    body = request.json or {}
    if not body.get("name") or not body.get("slug"):
        return jsonify({"error": "name and slug required"}), 400
    if AppCategory.query.filter_by(slug=body["slug"]).first():
        return jsonify({"error": "slug exists"}), 409
    c = AppCategory(
        name=body["name"],
        slug=body["slug"],
        sort_order=body.get("sort_order", 0),
        is_active=bool(body.get("is_active", True)),
    )
    db.session.add(c)
    db.session.commit()
    return jsonify(_category_admin(c)), 201


@admin_bp.patch("/admin/categories/<int:category_id>")
@require_admin
def admin_update_category(category_id):
    c = AppCategory.query.get(category_id)
    if not c:
        return jsonify({"error": "category not found"}), 404
    body = request.json or {}
    if body.get("slug"):
        clash = AppCategory.query.filter_by(slug=body["slug"]).first()
        if clash and clash.id != category_id:
            return jsonify({"error": "slug exists"}), 409
    for field in ("name", "slug", "sort_order"):
        if field in body:
            setattr(c, field, body[field])
    if "is_active" in body:
        c.is_active = bool(body["is_active"])
    db.session.commit()
    return jsonify(_category_admin(c))


@admin_bp.delete("/admin/categories/<int:category_id>")
@require_admin
def admin_delete_category(category_id):
    c = AppCategory.query.get(category_id)
    if not c:
        return jsonify({"error": "category not found"}), 404
    if AppWorld.query.filter_by(category_id=category_id).first():
        return jsonify({"error": "category in use by worlds"}), 409
    db.session.delete(c)
    db.session.commit()
    return jsonify({"ok": True})


# ---------------- admin: settings ----------------


@admin_bp.get("/admin/settings")
@require_admin
def admin_list_settings():
    rows = AppSetting.query.order_by(AppSetting.key).all()
    return jsonify([{"key": r.key, "value": r.value} for r in rows])


@admin_bp.patch("/admin/settings")
@require_admin
def admin_update_settings():
    body = request.json or {}
    if not body.get("key") or body.get("value") is None:
        return jsonify({"error": "key and value required"}), 400
    row = AppSetting.query.get(body["key"])
    if row:
        row.value = str(body["value"])
    else:
        row = AppSetting(key=body["key"], value=str(body["value"]))
        db.session.add(row)
    db.session.commit()
    return jsonify({"key": row.key, "value": row.value})


# ---------------- admin: users ----------------


def _user_admin(u):
    return {
        "id": u.id,
        "mobile": u.mobile,
        "name": u.name,
        "status": u.status,
        "free_image_credits": u.free_image_credits,
        "free_voice_credits": u.free_voice_credits,
        "balance": coins_svc.get_balance(u),
        "last_login_at": u.last_login_at.isoformat() if u.last_login_at else None,
        "created_at": u.created_at.isoformat() if u.created_at else None,
    }


@admin_bp.get("/admin/users")
@require_admin
def admin_list_users():
    query = AppUser.query
    search = (request.args.get("search") or "").strip()
    if search:
        if search.isdigit():
            query = query.filter(
                or_(
                    AppUser.mobile.like(f"%{search}%"),
                    AppUser.id == int(search),
                )
            )
        else:
            query = query.filter(AppUser.mobile.like(f"%{search}%"))
    is_active = request.args.get("is_active")
    if is_active == "true":
        query = query.filter_by(status="active")
    elif is_active == "false":
        query = query.filter_by(status="blocked")
    query = query.order_by(AppUser.id.desc())
    users, meta = _paginate(query)
    return jsonify({"items": [_user_admin(u) for u in users], **meta})


@admin_bp.get("/admin/users/<int:user_id>")
@require_admin
def admin_get_user(user_id):
    """جزئیات کاربر، مثل نورا: تولیدها + دفتر سکه + گالری."""
    u = AppUser.query.get(user_id)
    if not u:
        return jsonify({"error": "user not found"}), 404
    worlds = {w.id: w.name for w in AppWorld.query.all()}
    models = {m.id: m.name for m in AppModel.query.all()}
    gens = (
        AppGeneration.query.filter_by(user_id=u.id)
        .filter(AppGeneration.status != "failed")
        .order_by(AppGeneration.id.desc())
        .limit(20)
        .all()
    )
    failed_gens = (
        AppGeneration.query.filter_by(user_id=u.id, status="failed")
        .order_by(AppGeneration.id.desc())
        .limit(20)
        .all()
    )
    ledger = (
        AppCoinLedger.query.filter_by(user_id=u.id)
        .order_by(AppCoinLedger.id.desc())
        .limit(20)
        .all()
    )
    gallery = (
        AppGallery.query.filter_by(user_id=u.id)
        .order_by(AppGallery.id.desc())
        .limit(20)
        .all()
    )
    return jsonify(
        {
            **_user_admin(u),
            "generations": [
                {
                    "id": g.id,
                    "kind": g.kind,
                    "world": worlds.get(g.world_id),
                    "model": models.get(g.model_id),
                    "status": g.status,
                    "coin_cost": g.coin_cost,
                    "charged": bool(g.charged),
                    "output_url": g.output_url,
                    "created_at": g.created_at.isoformat() if g.created_at else None,
                }
                for g in gens
            ],
            "failed_generations": [
                {
                    "id": g.id,
                    "world": worlds.get(g.world_id),
                    "created_at": g.created_at.isoformat() if g.created_at else None,
                }
                for g in failed_gens
            ],
            "ledger": [
                {
                    "id": r.id,
                    "delta": r.delta,
                    "reason": r.reason,
                    "ref_type": r.ref_type,
                    "ref_id": r.ref_id,
                    "created_at": r.created_at.isoformat() if r.created_at else None,
                }
                for r in ledger
            ],
            "gallery": [
                {
                    "id": r.id,
                    "image_url": r.image_url,
                    "status": r.status,
                    "published_in_app": bool(r.published_in_app),
                }
                for r in gallery
            ],
        }
    )


@admin_bp.post("/admin/users/<int:user_id>/coin-adjust")
@require_admin
def admin_user_coin_adjust(user_id):
    """تعدیل دستی سکه، مثل wallet-adjustment نورا."""
    u = AppUser.query.get(user_id)
    if not u:
        return jsonify({"error": "user not found"}), 404
    body = request.json or {}
    try:
        delta = int(body.get("delta"))
    except (TypeError, ValueError):
        return jsonify({"error": "delta (int) required"}), 400
    if delta == 0 or abs(delta) > 100000:
        return jsonify({"error": "delta must be non-zero and within 100000"}), 400
    try:
        balance = coins_svc.adjust_coins(
            u, delta, reason=(str(body.get("reason") or "admin")[:200])
        )
    except ValueError:
        return jsonify({"error": "insufficient balance"}), 400
    return jsonify({"id": u.id, "balance": balance})


@admin_bp.patch("/admin/users/<int:user_id>")
@require_admin
def admin_update_user(user_id):
    """مسدود/رفع مسدود کاربر."""
    u = AppUser.query.get(user_id)
    if not u:
        return jsonify({"error": "user not found"}), 404
    body = request.json or {}
    if "status" in body:
        if body["status"] not in ("active", "blocked"):
            return jsonify({"error": "status must be active or blocked"}), 400
        u.status = body["status"]
    db.session.commit()
    return jsonify({"id": u.id, "status": u.status})


# ---------------- admin: auth (ورود یوزر/پسورد) ----------------
# توکن env همچنان راه ورود اصلی می‌ماند (ساخت اولین ادمین با همان توکن
# انجام می‌شود)؛ لاگین یوزر/پسورد برای استفاده‌ی روزمره است.

ADMIN_JWT_DAYS = 7


def _admin_jwt_for(admin_user):
    payload = {
        "admin": True,
        "admin_user": admin_user.username,
        "exp": datetime.datetime.utcnow() + datetime.timedelta(days=ADMIN_JWT_DAYS),
    }
    return jwt.encode(payload, current_app.config["SECRET_KEY"], algorithm="HS256")


@admin_bp.post("/admin/auth/login")
def admin_login():
    body = request.json or {}
    username = (body.get("username") or "").strip()
    password = body.get("password") or ""
    if not username or not password:
        return jsonify({"error": "username and password required"}), 400
    user = AppAdminUser.query.filter_by(username=username).first()
    if (
        not user
        or not user.is_active
        or not check_password_hash(user.password_hash, password)
    ):
        return jsonify({"error": "invalid credentials"}), 401
    return jsonify({"token": _admin_jwt_for(user), "username": user.username})


def _admin_user_admin(u):
    return {
        "id": u.id,
        "username": u.username,
        "is_active": bool(u.is_active),
        "created_at": u.created_at.isoformat() if u.created_at else None,
    }


@admin_bp.get("/admin/admin-users")
@require_admin
def admin_list_admin_users():
    rows = AppAdminUser.query.order_by(AppAdminUser.id).all()
    return jsonify([_admin_user_admin(u) for u in rows])


@admin_bp.post("/admin/admin-users")
@require_admin
def admin_create_admin_user():
    body = request.json or {}
    username = (body.get("username") or "").strip()
    password = body.get("password") or ""
    if not username or len(password) < 6:
        return jsonify({"error": "username required and password min 6 chars"}), 400
    if AppAdminUser.query.filter_by(username=username).first():
        return jsonify({"error": "username exists"}), 409
    u = AppAdminUser(
        username=username,
        password_hash=generate_password_hash(password),
        is_active=bool(body.get("is_active", True)),
    )
    db.session.add(u)
    db.session.commit()
    return jsonify(_admin_user_admin(u)), 201


@admin_bp.patch("/admin/admin-users/<int:admin_user_id>")
@require_admin
def admin_update_admin_user(admin_user_id):
    u = AppAdminUser.query.get(admin_user_id)
    if not u:
        return jsonify({"error": "admin user not found"}), 404
    body = request.json or {}
    if body.get("password"):
        if len(body["password"]) < 6:
            return jsonify({"error": "password min 6 chars"}), 400
        u.password_hash = generate_password_hash(body["password"])
    if "is_active" in body:
        u.is_active = bool(body["is_active"])
    db.session.commit()
    return jsonify(_admin_user_admin(u))


@admin_bp.delete("/admin/admin-users/<int:admin_user_id>")
@require_admin
def admin_delete_admin_user(admin_user_id):
    u = AppAdminUser.query.get(admin_user_id)
    if not u:
        return jsonify({"error": "admin user not found"}), 404
    if AppAdminUser.query.count() <= 1:
        return jsonify({"error": "cannot delete the last admin user"}), 409
    db.session.delete(u)
    db.session.commit()
    return jsonify({"ok": True})


# ---------------- admin: purchases (پرداخت‌های بازار) ----------------


def _purchase_admin(r, mobile, pack_name, pack_coins):
    tok = r.bazaar_purchase_token or ""
    return {
        "id": r.id,
        "user_id": r.user_id,
        "mobile": mobile,
        "pack_name": pack_name,
        "pack_coins": pack_coins,
        "amount_irr": r.amount_irr,
        "status": r.status,
        # توکن کامل خرید هرگز در ادمین نمایش داده نمی‌شود.
        "token_tail": ("…" + tok[-6:]) if len(tok) > 6 else tok,
        "verified_at": r.verified_at.isoformat() if r.verified_at else None,
        "created_at": r.created_at.isoformat() if r.created_at else None,
    }


@admin_bp.get("/admin/purchases")
@require_admin
def admin_list_purchases():
    query = AppPurchase.query
    status = request.args.get("status")
    if status:
        query = query.filter_by(status=status)
    mobile = (request.args.get("mobile") or "").strip()
    if mobile:
        digits = "".join(c for c in mobile if c.isdigit())
        if digits:
            query = query.join(
                AppUser, AppPurchase.user_id == AppUser.id
            ).filter(AppUser.mobile.like(f"%{digits}%"))
        else:
            query = query.filter(False)
    for arg, col in (("date_from", None), ("date_to", None)):
        val = request.args.get(arg)
        if val:
            try:
                dt = datetime.datetime.strptime(val, "%Y-%m-%d")
            except ValueError:
                continue
            if arg == "date_from":
                query = query.filter(AppPurchase.created_at >= dt)
            else:
                query = query.filter(
                    AppPurchase.created_at < dt + datetime.timedelta(days=1)
                )
    query = query.order_by(AppPurchase.id.desc())
    rows, meta = _paginate(query)
    users = {u.id: u.mobile for u in AppUser.query.all()}
    packs = {p.id: (p.name, p.coins) for p in AppCoinPack.query.all()}
    out = []
    for r in rows:
        name, coins = packs.get(r.pack_id, (None, None))
        out.append(_purchase_admin(r, users.get(r.user_id), name, coins))
    return jsonify({"items": out, **meta})


@admin_bp.get("/admin/purchases/<int:purchase_id>")
@require_admin
def admin_get_purchase(purchase_id):
    r = AppPurchase.query.get(purchase_id)
    if not r:
        return jsonify({"error": "purchase not found"}), 404
    user = AppUser.query.get(r.user_id)
    pack = AppCoinPack.query.get(r.pack_id)
    return jsonify(
        _purchase_admin(
            r,
            user.mobile if user else None,
            pack.name if pack else None,
            pack.coins if pack else None,
        )
    )


# ---------------- admin: models (ساخت مدل جدید) ----------------
# ویرایش مدل از قبل بود؛ ساخت مدل جدید اضافه شد.


@admin_bp.post("/admin/models")
@require_admin
def admin_create_model():
    body = request.json or {}
    for field in ("name", "capability"):
        if not body.get(field):
            return jsonify({"error": f"{field} required"}), 400
    if body["capability"] not in ("generate_image", "edit_image", "image"):
        return jsonify({"error": "app models are image-only"}), 400
    if body.get("kind") not in (None, "image"):
        return jsonify({"error": "app models are image-only"}), 400
    slug = (body.get("slug") or "").strip() or _slugify(body["name"])
    if AppModel.query.filter_by(slug=slug).first():
        return jsonify({"error": "slug exists"}), 409
    m = AppModel(
        kind="image",
        name=body["name"],
        slug=slug,
        provider=body.get("provider", ""),
        provider_type=body.get("provider_type", "openai_compat"),
        provider_model_name=body.get("provider_model_name") or body["name"],
        base_url=body.get("base_url"),
        config_json=_store_provider_creds(
            None, body.get("base_url"), body.get("api_key")
        ),
        capability=body["capability"],
        pricing_type=body.get("pricing_type", "image_count"),
        tokenizer_encoding=body.get("tokenizer_encoding"),
        description=body.get("description"),
        default_max_output_tokens=body.get("default_max_output_tokens"),
        max_prompt_chars=body.get("max_prompt_chars"),
        max_history_messages=body.get("max_history_messages"),
        max_history_tokens=body.get("max_history_tokens"),
        is_active=bool(body.get("is_active", True)),
    )
    db.session.add(m)
    db.session.commit()
    return jsonify(_model_public(m)), 201


# ---------------- admin: gallery (همه، برای تاریخچه) ----------------


@admin_bp.get("/admin/gallery")
@require_admin
def admin_gallery_all():
    query = AppGallery.query
    status = request.args.get("status")
    if status:
        query = query.filter_by(status=status)
    query = query.order_by(AppGallery.id.desc())
    rows, meta = _paginate(query)
    users = {u.id: u.mobile for u in AppUser.query.all()}
    admins = {a.id: a.username for a in AppAdminUser.query.all()}
    out = []
    for r in rows:
        out.append(
            {
                "id": r.id,
                "user_id": r.user_id,
                "mobile": users.get(r.user_id),
                "generation_id": r.generation_id,
                "image_url": r.image_url,
                "status": r.status,
                "published_in_app": bool(r.published_in_app),
                "reviewed_by": admins.get(r.reviewed_by_admin_id),
                "reviewed_at": r.reviewed_at.isoformat() if r.reviewed_at else None,
                "rejection_reason": r.rejection_reason,
                "created_at": r.created_at.isoformat() if r.created_at else None,
            }
        )
    return jsonify({"items": out, **meta})


# ---------------- admin: world sample upload ----------------

SAMPLE_EXTS = {"jpg", "jpeg", "png", "webp"}
SAMPLE_MAX_BYTES = 5 * 1024 * 1024


@admin_bp.post("/admin/worlds/<int:world_id>/sample")
@require_admin
def admin_upload_world_sample(world_id):
    """فایل نمونه را ذخیره می‌کند و URL را برمی‌گرداند؛ خود دنیا با PATCH
    جداگانه به‌روز می‌شود (مثل ویرایش دستی لینک)."""
    w = AppWorld.query.get(world_id)
    if not w:
        return jsonify({"error": "world not found"}), 404
    f = request.files.get("file")
    if not f or not f.filename:
        return jsonify({"error": "file required"}), 400
    ext = f.filename.rsplit(".", 1)[-1].lower() if "." in f.filename else ""
    if ext not in SAMPLE_EXTS:
        return jsonify({"error": "only jpg/png/webp allowed"}), 400
    data = f.read()
    if len(data) > SAMPLE_MAX_BYTES:
        return jsonify({"error": "file too large (max 5MB)"}), 413
    filename = secure_filename(f"{w.slug}.{ext}")
    dest_dir = os.path.join(current_app.static_folder, "worlds")
    os.makedirs(dest_dir, exist_ok=True)
    with open(os.path.join(dest_dir, filename), "wb") as fh:
        fh.write(data)
    url = request.host_url.rstrip("/") + "/static/worlds/" + filename
    return jsonify({"sample_image_url": url})


# ---------------- admin: announcements + push notifications ----------------


@admin_bp.get("/admin/announcements")
@require_admin
def admin_list_announcements():
    rows = (
        AppAnnouncement.query.order_by(AppAnnouncement.id.desc()).all()
    )
    return jsonify([_announcement_admin(a) for a in rows])


@admin_bp.post("/admin/announcements")
@require_admin
def admin_create_announcement():
    body = request.json or {}
    title = (body.get("title") or "").strip()
    text = (body.get("body") or "").strip()
    if not title or not text:
        return jsonify({"error": "title and body required"}), 400
    a = AppAnnouncement(title=title, body=text, active=True)
    db.session.add(a)
    db.session.commit()
    return jsonify(_announcement_admin(a)), 201


@admin_bp.patch("/admin/announcements/<int:ann_id>")
@require_admin
def admin_update_announcement(ann_id):
    a = AppAnnouncement.query.get(ann_id)
    if not a:
        return jsonify({"error": "announcement not found"}), 404
    body = request.json or {}
    if "active" not in body:
        return jsonify({"error": "active required"}), 400
    a.active = bool(body["active"])
    db.session.commit()
    return jsonify(_announcement_admin(a))


@admin_bp.delete("/admin/announcements/<int:ann_id>")
@require_admin
def admin_delete_announcement(ann_id):
    a = AppAnnouncement.query.get(ann_id)
    if not a:
        return jsonify({"error": "announcement not found"}), 404
    db.session.delete(a)
    db.session.commit()
    return jsonify({"ok": True})


@admin_bp.post("/admin/notifications/send")
@require_admin
def admin_send_notification():
    body = request.json or {}
    title = (body.get("title") or "").strip()
    text = (body.get("body") or "").strip()
    if not title or not text:
        return jsonify({"error": "title and body required"}), 400
    tokens = [r.token for r in AppDeviceToken.query.all()]
    try:
        result = fcm_svc.send_push_to_tokens(tokens, title, text)
    except fcm_svc.FcmNotConfigured:
        return jsonify({"error": "fcm_not_configured"}), 503
    except Exception:
        return jsonify({"error": "fcm send failed"}), 502
    stale = result.get("stale_tokens") or []
    if stale:
        AppDeviceToken.query.filter(
            AppDeviceToken.token.in_(stale)
        ).delete(synchronize_session=False)
    db.session.commit()
    return jsonify(
        {
            "sent": result["sent"],
            "failed": result["failed"],
            "total": result["total"],
        }
    )
