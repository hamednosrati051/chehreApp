-- ============================================================
-- دیتابیس جدای اپ جدید (عکس و صدا) — فقط فایل طراحی
-- هیچ دیتابیسی با این فایل ساخته نشده؛ نورا دست نخورده.
-- اصول: دیتابیس کاملاً جدا از نورا، بدون هیچ ارجاع به جدول‌های نورا.
-- پیامک OTP با سرویس ملی‌پیامک نورا فرستاده می‌شود (فقط سرویس، نه دیتا).
-- ============================================================

CREATE DATABASE IF NOT EXISTS new_app
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE new_app;

-- کاربران خود اپ (حساب جدا از نورا؛ لاگین با موبایل + OTP)
CREATE TABLE app_users (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  mobile VARCHAR(20) NOT NULL UNIQUE,
  name VARCHAR(100) NULL,
  free_image_credits INT NOT NULL DEFAULT 1,  -- هدیه ورود: ۱ عکس رایگان
  free_voice_credits INT NOT NULL DEFAULT 1,  -- هدیه ورود: ۱ تبدیل صدا رایگان
  status ENUM('active','blocked') NOT NULL DEFAULT 'active',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_login_at TIMESTAMP NULL
) ENGINE=InnoDB;

-- کدهای OTP لاگین (هش‌شده، مثل الگوی نورا)
CREATE TABLE app_otp_codes (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  mobile VARCHAR(20) NOT NULL,
  code_hash VARCHAR(255) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_otp_mobile (mobile)
) ENGINE=InnoDB;

-- مدل‌های خود اپ (کاملاً جدا از مدل‌های نورا)
-- kind: image | voice
-- config_json: آدرس + کلید + تنظیمات provider (طبق الگوی نورا)
CREATE TABLE app_models (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  kind ENUM('image','voice') NOT NULL,
  name VARCHAR(100) NOT NULL,
  slug VARCHAR(100) NOT NULL UNIQUE,
  provider VARCHAR(50) NOT NULL,              -- مثل avalai برای تصویر؛ نقش provider_key نورا
  provider_type VARCHAR(32) NOT NULL DEFAULT 'openai_compat',
  provider_model_name VARCHAR(128) NULL,
  base_url VARCHAR(255) NULL,
  config_json JSON NULL,
  capability VARCHAR(50) NOT NULL,            -- generate_image | edit_image | voice_convert
  pricing_type VARCHAR(32) NOT NULL DEFAULT 'image_count',
  tokenizer_encoding VARCHAR(64) NULL,
  description TEXT NULL,
  default_max_output_tokens INT NULL,
  max_prompt_chars INT NULL,
  max_history_messages INT NULL,
  max_history_tokens INT NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- تعرفه‌های مدل‌های خود اپ (نسخه‌بندی‌شده، قیمت immutable مثل نورا)
CREATE TABLE app_pricing (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  model_id BIGINT UNSIGNED NOT NULL,
  billing_unit VARCHAR(50) NOT NULL,          -- image_count | voice_second
  unit_size INT NOT NULL DEFAULT 1,
  unit_price_usd DECIMAL(10,6) NOT NULL,
  version INT NOT NULL DEFAULT 1,
  dimension_key VARCHAR(64) NULL,
  quality_key VARCHAR(64) NULL,
  minimum_charge_irr BIGINT NULL,
  maximum_charge_irr BIGINT NULL,
  rounding_mode VARCHAR(16) NOT NULL DEFAULT 'up',
  valid_from TIMESTAMP NULL,
  valid_until TIMESTAMP NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  created_by_admin_id BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (model_id) REFERENCES app_models(id)
) ENGINE=InnoDB;

-- دسته‌بندی دنیاها (ایرانی، انیمه، کودک، سینمایی و...)
CREATE TABLE app_categories (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  slug VARCHAR(100) NOT NULL UNIQUE,
  sort_order INT NOT NULL DEFAULT 0,
  is_active TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB;

-- دنیاهای عکس (پرامپت مخفی + عکس نمونه + دسته + مدل تصویر خود اپ)
CREATE TABLE app_worlds (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  slug VARCHAR(100) NOT NULL UNIQUE,
  category_id BIGINT UNSIGNED NULL,
  sample_image_url VARCHAR(500) NULL,
  hidden_prompt TEXT NOT NULL,                -- پرامپت مخفی دنیا (کاربر نمی‌بیند)
  model_id BIGINT UNSIGNED NOT NULL,          -- ارجاع به مدل تصویر خود اپ
  coin_price INT NOT NULL DEFAULT 5,          -- تعرفه استاندارد: ۵ سکه به‌ازای هر تصویر
  is_featured TINYINT(1) NOT NULL DEFAULT 0,  -- برچسب «پیشنهادی» (دسته واقعی نیست)
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (category_id) REFERENCES app_categories(id),
  FOREIGN KEY (model_id) REFERENCES app_models(id)
) ENGINE=InnoDB;

-- خواننده‌ها (ارجاع به مدل صدای خود اپ + نمونه صوتی پیش‌نمایش)
CREATE TABLE app_singers (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  photo_url VARCHAR(500) NULL,
  sample_audio_url VARCHAR(500) NULL,         -- پیش‌نمایش برای کاربر (و مرجع zero-shot اگر لازم شد)
  voice_model_id BIGINT UNSIGNED NULL,        -- بعد از انتخاب route صدا پر می‌شود
  coin_price INT NOT NULL DEFAULT 0,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (voice_model_id) REFERENCES app_models(id)
) ENGINE=InnoDB;

-- موجودی سکه کاربر (جدا از کیف پول ریالی نورا)
CREATE TABLE app_coin_balances (
  user_id BIGINT UNSIGNED PRIMARY KEY,
  balance INT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES app_users(id)
) ENGINE=InnoDB;

-- تراکنش‌های سکه (خرید، مصرف، هدیه، بازگشت)
CREATE TABLE app_coin_ledger (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  delta INT NOT NULL,                         -- مثبت = واریز، منفی = مصرف
  reason ENUM('purchase','spend','gift','refund','admin','test') NOT NULL,  -- test = شارژ تستیِ پیش از انتشار (بدون پرداخت)
  ref_type VARCHAR(50) NULL,                  -- generation | purchase | null
  ref_id BIGINT UNSIGNED NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ledger_user (user_id),
  FOREIGN KEY (user_id) REFERENCES app_users(id)
) ENGINE=InnoDB;

-- تنظیمات اپ (ارزش هر سکه به تومان و...)
CREATE TABLE app_settings (
  `key` VARCHAR(100) PRIMARY KEY,
  `value` VARCHAR(500) NOT NULL
) ENGINE=InnoDB;
-- نمونه: coin_value_toman (ارزش هر سکه به تومان، تعیین در ادمین)

-- پک‌های سکه (قیمت per-pack است؛ هر پک قیمت خودش را دارد و پک بزرگ‌تر
-- ممکن است به‌ازای هر سکه ارزان‌تر باشد — تخفیف حجمی، مثل پک‌های مرجع)
CREATE TABLE app_coin_packs (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL DEFAULT '',
  coins INT NOT NULL,
  price_toman INT NOT NULL,
  bazaar_sku VARCHAR(100) NOT NULL UNIQUE,
  badge VARCHAR(50) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  is_active TINYINT(1) NOT NULL DEFAULT 1
) ENGINE=InnoDB;

-- خریدهای بازار اپ
CREATE TABLE app_purchases (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  pack_id BIGINT UNSIGNED NOT NULL,
  bazaar_purchase_token VARCHAR(500) NULL,
  amount_irr BIGINT NOT NULL,
  status ENUM('pending','verified','consumed') NOT NULL DEFAULT 'pending',
  verified_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES app_users(id),
  FOREIGN KEY (pack_id) REFERENCES app_coin_packs(id)
) ENGINE=InnoDB;

-- صف تولید (عکس و صدا) — سکه فقط موقع تحویل موفق کم می‌شود
CREATE TABLE app_generations (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  kind ENUM('image','voice') NOT NULL,
  world_id BIGINT UNSIGNED NULL,
  singer_id BIGINT UNSIGNED NULL,
  model_id BIGINT UNSIGNED NOT NULL,
  input_url VARCHAR(500) NULL,                -- سلفی یا صدای ضبط‌شده کاربر
  output_url VARCHAR(500) NULL,
  status ENUM('queued','processing','done','failed') NOT NULL DEFAULT 'queued',
  coin_cost INT NOT NULL DEFAULT 0,
  charged TINYINT(1) NOT NULL DEFAULT 0,      -- 1 = سکه کم شده (فقط بعد از تحویل موفق)
  cost_usd DECIMAL(10,6) NULL,               -- هزینه‌ی واقعی AvalAI (فقط با usage گزارش‌شده)
  usage_json JSON NULL,                      -- usage خام برگشتی از provider
  error_message VARCHAR(500) NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMP NULL,
  FOREIGN KEY (user_id) REFERENCES app_users(id),
  FOREIGN KEY (world_id) REFERENCES app_worlds(id),
  FOREIGN KEY (singer_id) REFERENCES app_singers(id),
  FOREIGN KEY (model_id) REFERENCES app_models(id)
) ENGINE=InnoDB;

-- گالری خود اپ: صف تایید + انتشار فقط داخل خود اپ.
-- تایید در ادمین خود اپ انجام می‌شود؛ عکس‌های اپ هیچ‌وقت به گالری
-- نورا نمی‌روند و هیچ کپی‌ای در نورا نوشته نمی‌شود (تماس حذف‌شده).
-- عکس‌های خود نورا هم هیچ‌وقت به اپ نمی‌آیند.
CREATE TABLE app_gallery (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  generation_id BIGINT UNSIGNED NOT NULL,
  image_url VARCHAR(500) NOT NULL,
  status ENUM('pending','approved','rejected') NOT NULL DEFAULT 'pending',
  published_in_app TINYINT(1) NOT NULL DEFAULT 0,
  reviewed_by_admin_id BIGINT NULL,
  reviewed_at TIMESTAMP NULL,
  rejection_reason TEXT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES app_users(id),
  FOREIGN KEY (generation_id) REFERENCES app_generations(id)
) ENGINE=InnoDB;

-- کاربران ادمین خود اپ (ورود یوزر/پسورد به ادمین وب).
CREATE TABLE app_admin_users (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(100) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
