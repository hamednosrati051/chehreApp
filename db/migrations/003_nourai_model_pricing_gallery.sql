-- 003: پورت تعریف مدل/تعرفه/گالری نورا به اپ (فقط تصویر).
-- روی سرور: mysql chehreApp < 003_nourai_model_pricing_gallery.sql
ALTER TABLE app_models
  ADD COLUMN slug VARCHAR(100) NULL,
  ADD COLUMN provider_type VARCHAR(32) NOT NULL DEFAULT 'openai_compat',
  ADD COLUMN provider_model_name VARCHAR(128) NULL,
  ADD COLUMN pricing_type VARCHAR(32) NOT NULL DEFAULT 'image_count',
  ADD COLUMN tokenizer_encoding VARCHAR(64) NULL,
  ADD COLUMN description TEXT NULL,
  ADD COLUMN default_max_output_tokens INT NULL,
  ADD COLUMN max_prompt_chars INT NULL,
  ADD COLUMN max_history_messages INT NULL,
  ADD COLUMN max_history_tokens INT NULL;
UPDATE app_models SET slug = CONCAT('model-', id) WHERE slug IS NULL;
ALTER TABLE app_models MODIFY COLUMN slug VARCHAR(100) NOT NULL;
ALTER TABLE app_models ADD UNIQUE KEY uq_app_models_slug (slug);
ALTER TABLE app_pricing
  ADD COLUMN dimension_key VARCHAR(64) NULL,
  ADD COLUMN quality_key VARCHAR(64) NULL,
  ADD COLUMN minimum_charge_irr BIGINT NULL,
  ADD COLUMN maximum_charge_irr BIGINT NULL,
  ADD COLUMN rounding_mode VARCHAR(16) NOT NULL DEFAULT 'up',
  ADD COLUMN created_by_admin_id BIGINT NULL;
ALTER TABLE app_gallery
  ADD COLUMN reviewed_by_admin_id BIGINT NULL,
  ADD COLUMN reviewed_at DATETIME NULL,
  ADD COLUMN rejection_reason TEXT NULL;
