-- ChehreSaz v16: ستون store روی کاربران + لاگ تلاش‌های وریفای + گزارش‌های عکس.
-- افزایشی و بی‌خطر: ستون تازه nullable است (اپ‌های قدیمی store نمی‌فرستند)؛
-- دو جدول تازه‌اند و هیچ جدول موجودی دست نمی‌خورد.
-- اجرا روی سرور (MySQL) پیش از استقرار نسخه‌ی ۱۶؛ روی SQLite تست‌ها
-- create_all جدول‌ها را می‌سازد و این فایل اجرا نمی‌شود.

ALTER TABLE app_users ADD COLUMN store VARCHAR(16) NULL;

CREATE TABLE app_verify_attempts (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  pack_id BIGINT NULL,
  purchase_token VARCHAR(500) NULL,
  store VARCHAR(16) NULL,
  success TINYINT(1) NOT NULL,
  error VARCHAR(500) NULL,
  pending_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_verify_attempts_user (user_id),
  INDEX idx_verify_attempts_created (created_at)
);

CREATE TABLE app_image_reports (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  generation_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  reason VARCHAR(500) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'pending',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_report_user_generation (user_id, generation_id),
  INDEX idx_image_reports_status (status),
  INDEX idx_image_reports_created (created_at)
);
