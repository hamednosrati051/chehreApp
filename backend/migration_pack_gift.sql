-- ChehreSaz v11: هدیه‌ی خرید per-pack.
-- افزایشی و بی‌خطر: دو ستون تازه با مقدار پیش‌فرض؛ ردیف‌های موجود هدیه ۰ می‌گیرند.
ALTER TABLE app_coin_packs ADD COLUMN gift_coins INT NOT NULL DEFAULT 0;
ALTER TABLE app_coin_packs ADD COLUMN gift_scope VARCHAR(10) NOT NULL DEFAULT 'every';
ALTER TABLE app_coin_packs ADD CONSTRAINT chk_pack_gift_scope CHECK (gift_scope IN ('first', 'every'));
