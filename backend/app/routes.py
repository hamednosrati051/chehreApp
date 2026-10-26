"""روت‌های API اپ: auth / محتوا / تولید / سکه / گالری."""

import datetime
import os
import random
from functools import wraps

import jwt
from flask import Blueprint, current_app, g, jsonify, request
from werkzeug.security import check_password_hash, generate_password_hash
from werkzeug.utils import secure_filename

from .extensions import db
from .models import (
    AppAdminUser,
    AppCategory,
    AppCoinLedger,
    AppCoinPack,
    AppGallery,
    AppGeneration,
    AppModel,
    AppOtpCode,
    AppPricing,
    AppPurchase,
    AppSetting,
    AppSinger,
    AppUser,
    AppWorld,
)
from .services import bazaar as bazaar_svc
from .services import coins as coins_svc
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
DEFAULT_IMAGE_COINS = 5


def _default_image_coins():
    """تعرفه‌ی پیش‌فرض هر تصویر (سکه)؛ از app_settings وگرنه ۵."""
    row = AppSetting.query.get("image_coin_price")
    try:
        return int(row.value) if row else DEFAULT_IMAGE_COINS
    except (TypeError, ValueError):
        return DEFAULT_IMAGE_COINS


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
    if user is None:
        # هدیه‌ی ورود (۱ عکس + ۱ صدا) از defaultهای مدل می‌آید.
        user = AppUser(mobile=mobile)
        db.session.add(user)
    user.last_login_at = datetime.datetime.utcnow()
    db.session.commit()
    return jsonify({"token": _token_for(user)})


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
            }
            for w in worlds
        ]
    )


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
        model = AppModel.query.get(world.model_id)
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
    if not coins_svc.can_afford(g.user, kind, cost):
        return jsonify({"error": "insufficient coins"}), 402
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
    return jsonify(
        {
            "id": gen.id,
            "kind": gen.kind,
            "status": gen.status,
            "output_url": gen.output_url,
            "charged": bool(gen.charged),
            "error": gen.error_message,
        }
    )


@generations_bp.get("/generations/mine")
@require_auth
def my_generations():
    gens = (
        AppGeneration.query.filter_by(user_id=g.user.id)
        .order_by(AppGeneration.id.desc())
        .limit(50)
        .all()
    )
    return jsonify(
        [
            {
                "id": gen.id,
                "kind": gen.kind,
                "status": gen.status,
                "output_url": gen.output_url,
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
    purchase = AppPurchase(
        user_id=g.user.id,
        pack_id=pack.id,
        bazaar_purchase_token=body.get("purchase_token"),
        amount_irr=pack.price_toman * 10,
        status="pending",
    )
    db.session.add(purchase)
    db.session.commit()
    # TODO: تایید خرید بازار با کلید RSA خود اپ، بعد credit_coins
    # و status=consumed. تا آن موقع خرید pending می‌ماند.
    return jsonify({"id": purchase.id, "status": purchase.status}), 201


# ---------------- uploads ----------------


@uploads_bp.post("/uploads")
@require_auth
def upload():
    f = request.files.get("file")
    if not f:
        return jsonify({"error": "file required"}), 400
    from .services import storage as storage_svc

    url = storage_svc.upload_bytes(f.read(), f.filename or "upload.bin")
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
    return {
        "id": m.id,
        "kind": m.kind,
        "name": m.name,
        "provider": m.provider,
        "base_url": m.base_url,
        "capability": m.capability,
        "is_active": bool(m.is_active),
        # کلید هرگز برگردانده نمی‌شود؛ فقط بودنش اعلام می‌شود.
        "has_credentials": bool(cfg.get("api_key")),
    }


@admin_bp.get("/admin/models")
@require_admin
def admin_list_models():
    models = AppModel.query.order_by(AppModel.id).all()
    return jsonify([_model_public(m) for m in models])


@admin_bp.patch("/admin/models/<int:model_id>")
@require_admin
def admin_update_model(model_id):
    """فرم مدل ادمین خود اپ: کلید write-only است؛ اگر api_key فرستاده
    نشود، مقدار قبلی می‌ماند (الگوی نورا)."""
    m = AppModel.query.get(model_id)
    if not m:
        return jsonify({"error": "model not found"}), 404
    body = request.json or {}
    for field in ("name", "provider", "base_url", "capability"):
        if field in body:
            setattr(m, field, body[field])
    if "is_active" in body:
        m.is_active = bool(body["is_active"])
    if "config_json" in body and isinstance(body["config_json"], dict):
        cfg = dict(m.config_json or {})
        cfg.update(body["config_json"])
        m.config_json = cfg
    db.session.commit()
    return jsonify(_model_public(m))


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
        is_active=bool(body.get("is_active", True)),
        sort_order=body.get("sort_order", 0),
    )
    db.session.add(w)
    db.session.commit()
    return jsonify(_world_admin(w)), 201


@admin_bp.patch("/admin/worlds/<int:world_id>")
@require_admin
def admin_update_world(world_id):
    w = AppWorld.query.get(world_id)
    if not w:
        return jsonify({"error": "world not found"}), 404
    body = request.json or {}
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
    for field in ("is_featured", "is_active"):
        if field in body:
            setattr(w, field, bool(body[field]))
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
    for field in ("name", "coins", "price_toman", "bazaar_sku", "badge", "sort_order"):
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
    r.status = "rejected"
    r.published_in_app = False
    db.session.commit()
    return jsonify({"id": r.id, "status": r.status})


# ---------------------------------------------------------------------------
# حالت تست (پیش از انتشار در بازار) — فقط با توکن ادمین.
# سکه‌ی تستی بدون هیچ پرداختی شارژ می‌کند تا چرخه‌ی کامل
# سکه ← تولید ← گالری قبل از انتشار بازار قابل وریفای باشد.
# در دفتر سکه با reason=test مشخص است و با پرداخت واقعی (بازار)
# قاطی نمی‌شود. پرداخت واقعی همیشه فقط Bazaar IAB است؛ هیچ مسیر
# زیبالی وجود ندارد. با APP_TEST_GRANTS_ENABLED=false بسته می‌شود.
# ---------------------------------------------------------------------------
TEST_GRANT_MAX_COINS = 1000


@admin_bp.post("/admin/test/grant-coins")
@require_admin
def admin_test_grant_coins():
    if not current_app.config.get("TEST_GRANTS_ENABLED", True):
        return jsonify({"error": "test grants disabled"}), 403
    data = request.get_json(silent=True) or {}
    user_id = data.get("user_id")
    coins = data.get("coins")
    if not isinstance(user_id, int) or not isinstance(coins, int):
        return jsonify({"error": "user_id and coins (int) required"}), 400
    if coins < 1 or coins > TEST_GRANT_MAX_COINS:
        return jsonify({"error": "coins must be 1..1000"}), 400
    user = AppUser.query.get(user_id)
    if not user:
        return jsonify({"error": "user not found"}), 404
    coins_svc.credit_coins(user, coins, reason="test", ref_type="test_grant")
    return jsonify(
        {
            "test_mode": True,
            "user_id": user.id,
            "granted": coins,
            "balance": coins_svc.get_balance(user),
        }
    )


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


@admin_bp.get("/admin/users")
@require_admin
def admin_list_users():
    users = AppUser.query.order_by(AppUser.id.desc()).limit(200).all()
    out = []
    for u in users:
        out.append(
            {
                "id": u.id,
                "mobile": u.mobile,
                "name": u.name,
                "status": u.status,
                "free_image_credits": u.free_image_credits,
                "free_voice_credits": u.free_voice_credits,
                "balance": coins_svc.get_balance(u),
                "created_at": u.created_at.isoformat() if u.created_at else None,
            }
        )
    return jsonify(out)


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


# ---------------- admin: pricing (تعرفه؛ قیمت immutable مثل نورا) ----------------


def _pricing_admin(r, model_name):
    return {
        "id": r.id,
        "model_id": r.model_id,
        "model_name": model_name,
        "billing_unit": r.billing_unit,
        "unit_size": r.unit_size,
        "unit_price_usd": float(r.unit_price_usd),
        "version": r.version,
        "valid_from": r.valid_from.isoformat() if r.valid_from else None,
        "valid_until": r.valid_until.isoformat() if r.valid_until else None,
        "is_active": bool(r.is_active),
        "created_at": r.created_at.isoformat() if r.created_at else None,
    }


@admin_bp.get("/admin/pricing")
@require_admin
def admin_list_pricing():
    rows = AppPricing.query.order_by(
        AppPricing.model_id, AppPricing.version.desc()
    ).all()
    models = {m.id: m.name for m in AppModel.query.all()}
    return jsonify([_pricing_admin(r, models.get(r.model_id)) for r in rows])


@admin_bp.post("/admin/pricing")
@require_admin
def admin_create_pricing():
    body = request.json or {}
    model = AppModel.query.get(body.get("model_id"))
    if not model:
        return jsonify({"error": "model not found"}), 400
    if not body.get("billing_unit") or body.get("unit_price_usd") is None:
        return jsonify({"error": "billing_unit and unit_price_usd required"}), 400
    try:
        price = float(body["unit_price_usd"])
        unit_size = int(body.get("unit_size", 1))
    except (TypeError, ValueError):
        return jsonify({"error": "invalid numbers"}), 400
    if price <= 0 or unit_size < 1:
        return jsonify({"error": "price must be > 0 and unit_size >= 1"}), 400
    last = (
        AppPricing.query.filter_by(
            model_id=model.id, billing_unit=body["billing_unit"]
        )
        .order_by(AppPricing.version.desc())
        .first()
    )
    r = AppPricing(
        model_id=model.id,
        billing_unit=body["billing_unit"],
        unit_size=unit_size,
        unit_price_usd=price,
        version=(last.version + 1) if last else 1,
        is_active=True,
    )
    for field in ("valid_from", "valid_until"):
        if body.get(field):
            try:
                setattr(r, field, datetime.datetime.fromisoformat(body[field]))
            except ValueError:
                return jsonify({"error": f"invalid {field}"}), 400
    db.session.add(r)
    db.session.commit()
    return jsonify(_pricing_admin(r, model.name)), 201


@admin_bp.patch("/admin/pricing/<int:pricing_id>")
@require_admin
def admin_update_pricing(pricing_id):
    """فقط فعال/غیرفعال قابل تغییر است؛ قیمت immutable است (مثل نورا)."""
    r = AppPricing.query.get(pricing_id)
    if not r:
        return jsonify({"error": "pricing not found"}), 404
    body = request.json or {}
    if "is_active" in body:
        r.is_active = bool(body["is_active"])
    db.session.commit()
    model = AppModel.query.get(r.model_id)
    return jsonify(_pricing_admin(r, model.name if model else None))


# ---------------- admin: purchases (پرداخت‌های بازار) ----------------


@admin_bp.get("/admin/purchases")
@require_admin
def admin_list_purchases():
    rows = AppPurchase.query.order_by(AppPurchase.id.desc()).limit(200).all()
    users = {u.id: u.mobile for u in AppUser.query.all()}
    packs = {p.id: p.name for p in AppCoinPack.query.all()}
    out = []
    for r in rows:
        tok = r.bazaar_purchase_token or ""
        out.append(
            {
                "id": r.id,
                "user_id": r.user_id,
                "mobile": users.get(r.user_id),
                "pack_name": packs.get(r.pack_id),
                "amount_irr": r.amount_irr,
                "status": r.status,
                # توکن کامل خرید هرگز در ادمین نمایش داده نمی‌شود.
                "token_tail": ("…" + tok[-6:]) if len(tok) > 6 else tok,
                "verified_at": r.verified_at.isoformat() if r.verified_at else None,
                "created_at": r.created_at.isoformat() if r.created_at else None,
            }
        )
    return jsonify(out)


# ---------------- admin: models (ساخت مدل جدید) ----------------
# ویرایش مدل از قبل بود؛ ساخت مدل جدید اضافه شد.


@admin_bp.post("/admin/models")
@require_admin
def admin_create_model():
    body = request.json or {}
    for field in ("name", "provider", "capability"):
        if not body.get(field):
            return jsonify({"error": f"{field} required"}), 400
    if body.get("kind") not in (None, "image", "voice"):
        return jsonify({"error": "kind must be image or voice"}), 400
    m = AppModel(
        kind=body.get("kind", "image"),
        name=body["name"],
        provider=body["provider"],
        base_url=body.get("base_url"),
        config_json={"api_key": body["api_key"]} if body.get("api_key") else None,
        capability=body["capability"],
        is_active=bool(body.get("is_active", True)),
    )
    db.session.add(m)
    db.session.commit()
    return jsonify(_model_public(m)), 201


# ---------------- admin: gallery (همه، برای تاریخچه) ----------------


@admin_bp.get("/admin/gallery")
@require_admin
def admin_gallery_all():
    rows = AppGallery.query.order_by(AppGallery.id.desc()).limit(200).all()
    users = {u.id: u.mobile for u in AppUser.query.all()}
    return jsonify(
        [
            {
                "id": r.id,
                "user_id": r.user_id,
                "mobile": users.get(r.user_id),
                "generation_id": r.generation_id,
                "image_url": r.image_url,
                "status": r.status,
                "published_in_app": bool(r.published_in_app),
                "created_at": r.created_at.isoformat() if r.created_at else None,
            }
            for r in rows
        ]
    )


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
