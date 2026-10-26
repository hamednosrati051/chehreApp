"""مدل‌های SQLAlchemy — آینه‌ی db/schema.sql. فقط جدول‌های app_*."""

from .extensions import db


class AppUser(db.Model):
    __tablename__ = "app_users"
    id = db.Column(db.BigInteger, primary_key=True, autoincrement=True)
    mobile = db.Column(db.String(20), unique=True, nullable=False)
    name = db.Column(db.String(100))
    free_image_credits = db.Column(db.Integer, default=1, nullable=False)
    free_voice_credits = db.Column(db.Integer, default=1, nullable=False)
    status = db.Column(
        db.Enum("active", "blocked", name="user_status"),
        default="active",
        nullable=False,
    )
    created_at = db.Column(db.DateTime, server_default=db.func.now(), nullable=False)
    last_login_at = db.Column(db.DateTime)


class AppOtpCode(db.Model):
    __tablename__ = "app_otp_codes"
    id = db.Column(db.BigInteger, primary_key=True, autoincrement=True)
    mobile = db.Column(db.String(20), nullable=False, index=True)
    code_hash = db.Column(db.String(255), nullable=False)
    expires_at = db.Column(db.DateTime, nullable=False)
    attempts = db.Column(db.Integer, default=0, nullable=False)
    created_at = db.Column(db.DateTime, server_default=db.func.now(), nullable=False)


class AppModel(db.Model):
    __tablename__ = "app_models"
    id = db.Column(db.BigInteger, primary_key=True, autoincrement=True)
    kind = db.Column(db.Enum("image", "voice", name="model_kind"), nullable=False)
    name = db.Column(db.String(100), nullable=False)
    # مثل نورا: slug یکتاست (خودکار از نام ساخته می‌شود اگر خالی باشد).
    # ستون name نقش display_name نورا را دارد؛ provider نقش provider_key.
    slug = db.Column(db.String(100), unique=True)
    provider = db.Column(db.String(50), nullable=False)
    provider_type = db.Column(db.String(32), default="openai_compat", nullable=False)
    provider_model_name = db.Column(db.String(128))
    base_url = db.Column(db.String(255))
    config_json = db.Column(db.JSON)
    capability = db.Column(db.String(50), nullable=False)
    pricing_type = db.Column(db.String(32), default="image_count", nullable=False)
    tokenizer_encoding = db.Column(db.String(64))
    description = db.Column(db.Text)
    default_max_output_tokens = db.Column(db.Integer)
    max_prompt_chars = db.Column(db.Integer)
    max_history_messages = db.Column(db.Integer)
    max_history_tokens = db.Column(db.Integer)
    is_active = db.Column(db.Boolean, default=True, nullable=False)
    created_at = db.Column(db.DateTime, server_default=db.func.now(), nullable=False)


class AppPricing(db.Model):
    __tablename__ = "app_pricing"
    id = db.Column(db.BigInteger, primary_key=True, autoincrement=True)
    model_id = db.Column(
        db.BigInteger, db.ForeignKey("app_models.id"), nullable=False
    )
    billing_unit = db.Column(db.String(50), nullable=False)
    unit_size = db.Column(db.Integer, default=1, nullable=False)
    unit_price_usd = db.Column(db.Numeric(10, 6), nullable=False)
    version = db.Column(db.Integer, default=1, nullable=False)
    # مثل نورا: تعرفه‌ی وابسته به بعد/کیفیت + سقف شارژ + حالت گردکردن.
    dimension_key = db.Column(db.String(64))
    quality_key = db.Column(db.String(64))
    minimum_charge_irr = db.Column(db.BigInteger)
    maximum_charge_irr = db.Column(db.BigInteger)
    rounding_mode = db.Column(db.String(16), default="up", nullable=False)
    valid_from = db.Column(db.DateTime)
    valid_until = db.Column(db.DateTime)
    is_active = db.Column(db.Boolean, default=True, nullable=False)
    created_by_admin_id = db.Column(db.BigInteger)
    created_at = db.Column(db.DateTime, server_default=db.func.now(), nullable=False)


class AppCategory(db.Model):
    __tablename__ = "app_categories"
    id = db.Column(db.BigInteger, primary_key=True, autoincrement=True)
    name = db.Column(db.String(100), nullable=False)
    slug = db.Column(db.String(100), unique=True, nullable=False)
    sort_order = db.Column(db.Integer, default=0, nullable=False)
    is_active = db.Column(db.Boolean, default=True, nullable=False)


class AppWorld(db.Model):
    __tablename__ = "app_worlds"
    id = db.Column(db.BigInteger, primary_key=True, autoincrement=True)
    name = db.Column(db.String(100), nullable=False)
    slug = db.Column(db.String(100), unique=True, nullable=False)
    category_id = db.Column(db.BigInteger, db.ForeignKey("app_categories.id"))
    sample_image_url = db.Column(db.String(500))
    hidden_prompt = db.Column(db.Text, nullable=False)
    model_id = db.Column(db.BigInteger, db.ForeignKey("app_models.id"), nullable=False)
    # تعرفه استاندارد: ۵ سکه به‌ازای هر تصویر (تصمیم ۲۰۲۶-۱۰-۲۶).
    coin_price = db.Column(db.Integer, default=5, nullable=False)
    is_featured = db.Column(db.Boolean, default=False, nullable=False)
    is_active = db.Column(db.Boolean, default=True, nullable=False)
    sort_order = db.Column(db.Integer, default=0, nullable=False)
    created_at = db.Column(db.DateTime, server_default=db.func.now(), nullable=False)


class AppSinger(db.Model):
    __tablename__ = "app_singers"
    id = db.Column(db.BigInteger, primary_key=True, autoincrement=True)
    name = db.Column(db.String(100), nullable=False)
    photo_url = db.Column(db.String(500))
    sample_audio_url = db.Column(db.String(500))
    voice_model_id = db.Column(db.BigInteger, db.ForeignKey("app_models.id"))
    coin_price = db.Column(db.Integer, default=0, nullable=False)
    is_active = db.Column(db.Boolean, default=True, nullable=False)
    sort_order = db.Column(db.Integer, default=0, nullable=False)
    created_at = db.Column(db.DateTime, server_default=db.func.now(), nullable=False)


class AppCoinBalance(db.Model):
    __tablename__ = "app_coin_balances"
    user_id = db.Column(
        db.BigInteger, db.ForeignKey("app_users.id"), primary_key=True
    )
    balance = db.Column(db.Integer, default=0, nullable=False)
    updated_at = db.Column(
        db.DateTime,
        server_default=db.func.now(),
        onupdate=db.func.now(),
        nullable=False,
    )


class AppCoinLedger(db.Model):
    __tablename__ = "app_coin_ledger"
    id = db.Column(db.BigInteger, primary_key=True, autoincrement=True)
    user_id = db.Column(db.BigInteger, db.ForeignKey("app_users.id"), nullable=False)
    delta = db.Column(db.Integer, nullable=False)
    reason = db.Column(
        db.Enum(
            "purchase", "spend", "gift", "refund", "admin", "test",
            name="ledger_reason",
        ),
        nullable=False,
    )
    ref_type = db.Column(db.String(50))
    ref_id = db.Column(db.BigInteger)
    created_at = db.Column(db.DateTime, server_default=db.func.now(), nullable=False)


class AppSetting(db.Model):
    __tablename__ = "app_settings"
    key = db.Column(db.String(100), primary_key=True)
    value = db.Column(db.String(500), nullable=False)


class AppCoinPack(db.Model):
    __tablename__ = "app_coin_packs"
    id = db.Column(db.BigInteger, primary_key=True, autoincrement=True)
    # قیمت per-pack است (هر پک قیمت خودش را دارد؛ پک بزرگ‌تر ممکن است
    # به‌ازای هر سکه ارزان‌تر باشد — تخفیف حجمی، مثل پک‌های مرجع).
    name = db.Column(db.String(100), nullable=False, default="")
    coins = db.Column(db.Integer, nullable=False)
    price_toman = db.Column(db.Integer, nullable=False)
    bazaar_sku = db.Column(db.String(100), unique=True, nullable=False)
    badge = db.Column(db.String(50))
    sort_order = db.Column(db.Integer, default=0, nullable=False)
    is_active = db.Column(db.Boolean, default=True, nullable=False)


class AppPurchase(db.Model):
    __tablename__ = "app_purchases"
    id = db.Column(db.BigInteger, primary_key=True, autoincrement=True)
    user_id = db.Column(db.BigInteger, db.ForeignKey("app_users.id"), nullable=False)
    pack_id = db.Column(db.BigInteger, db.ForeignKey("app_coin_packs.id"), nullable=False)
    bazaar_purchase_token = db.Column(db.String(500))
    amount_irr = db.Column(db.BigInteger, nullable=False)
    status = db.Column(
        db.Enum("pending", "verified", "consumed", name="purchase_status"),
        default="pending",
        nullable=False,
    )
    verified_at = db.Column(db.DateTime)
    created_at = db.Column(db.DateTime, server_default=db.func.now(), nullable=False)


class AppGeneration(db.Model):
    __tablename__ = "app_generations"
    id = db.Column(db.BigInteger, primary_key=True, autoincrement=True)
    user_id = db.Column(db.BigInteger, db.ForeignKey("app_users.id"), nullable=False)
    kind = db.Column(db.Enum("image", "voice", name="generation_kind"), nullable=False)
    world_id = db.Column(db.BigInteger, db.ForeignKey("app_worlds.id"))
    singer_id = db.Column(db.BigInteger, db.ForeignKey("app_singers.id"))
    model_id = db.Column(db.BigInteger, db.ForeignKey("app_models.id"), nullable=False)
    input_url = db.Column(db.String(500))
    output_url = db.Column(db.String(500))
    status = db.Column(
        db.Enum("queued", "processing", "done", "failed", name="generation_status"),
        default="queued",
        nullable=False,
    )
    coin_cost = db.Column(db.Integer, default=0, nullable=False)
    charged = db.Column(db.Boolean, default=False, nullable=False)
    # هزینه‌ی واقعی AvalAI برای دید حاشیه‌ی سود در ادمین؛
    # فقط وقتی provider مقدار usage برگرداند پر می‌شود، هرگز ساخته نمی‌شود.
    cost_usd = db.Column(db.Numeric(10, 6), nullable=True)
    usage_json = db.Column(db.JSON, nullable=True)
    error_message = db.Column(db.String(500))
    created_at = db.Column(db.DateTime, server_default=db.func.now(), nullable=False)
    completed_at = db.Column(db.DateTime)


class AppGallery(db.Model):
    __tablename__ = "app_gallery"
    id = db.Column(db.BigInteger, primary_key=True, autoincrement=True)
    user_id = db.Column(db.BigInteger, db.ForeignKey("app_users.id"), nullable=False)
    generation_id = db.Column(
        db.BigInteger, db.ForeignKey("app_generations.id"), nullable=False
    )
    image_url = db.Column(db.String(500), nullable=False)
    status = db.Column(
        db.Enum("pending", "approved", "rejected", name="gallery_status"),
        default="pending",
        nullable=False,
    )
    published_in_app = db.Column(db.Boolean, default=False, nullable=False)
    # مثل نورا: چه کسی، کی، و با چه دلیلی رد شد (دلیل فقط در ادمین است).
    reviewed_by_admin_id = db.Column(db.BigInteger)
    reviewed_at = db.Column(db.DateTime)
    rejection_reason = db.Column(db.Text)
    created_at = db.Column(db.DateTime, server_default=db.func.now(), nullable=False)


class AppAdminUser(db.Model):
    """کاربر ادمین خود اپ (ورود یوزر/پسورد به ادمین وب).

    توکن env همچنان راه ورود اصلی می‌ماند (ساخت اولین ادمین با همان
    توکن انجام می‌شود)؛ این جدول برای استفاده‌ی روزمره است.
    """

    __tablename__ = "app_admin_users"
    id = db.Column(db.BigInteger, primary_key=True, autoincrement=True)
    username = db.Column(db.String(100), unique=True, nullable=False)
    password_hash = db.Column(db.String(255), nullable=False)
    is_active = db.Column(db.Boolean, default=True, nullable=False)
    created_at = db.Column(db.DateTime, server_default=db.func.now(), nullable=False)
