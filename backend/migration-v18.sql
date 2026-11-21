-- ChehreSaz v18: تیکت‌های پشتیبانی + قیمت اصلی نمایشی پک‌ها.
-- افزایشی و بی‌خطر: دو جدول تازه‌اند؛ ستون تازه nullable است و
-- هیچ جدول موجودی دست نمی‌خورد. کلیدهای مودال پرومو
-- (promo_modal_title/body/show_number/enabled) ردیف app_settings
-- هستند و DDL نمی‌خواهند — از PATCH /admin/settings ساخته می‌شوند.
-- اجرا روی سرور (MySQL) پیش از استقرار نسخه‌ی ۱۸؛ روی SQLite تست‌ها
-- create_all جدول‌ها را می‌سازد و این فایل اجرا نمی‌شود.

CREATE TABLE app_tickets (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  subject VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'open',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_tickets_user (user_id),
  INDEX idx_tickets_status (status)
);

CREATE TABLE app_ticket_messages (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  ticket_id BIGINT NOT NULL,
  sender VARCHAR(16) NOT NULL,
  body TEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_ticket_messages_ticket (ticket_id)
);

ALTER TABLE app_coin_packs ADD COLUMN original_price_toman INT NULL;
