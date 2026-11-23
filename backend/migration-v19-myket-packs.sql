-- ChehreSaz v19 (item 0): فلِیور مایکت — سه پرچم جدا برای پک‌ها.
-- افزایشی و بی‌خطر: ستون‌های تازه با پیش‌فرض‌اند؛ هیچ ستون/ردیف
-- موجودی دست نمی‌خورد و رفتار نسخه‌ی بازار عوض نمی‌شود.
-- اجرا روی سرور (MySQL) پیش از استقرار نسخه‌ی ۱۹؛ روی SQLite تست‌ها
-- create_all جدول‌ها را می‌سازد و این فایل اجرا نمی‌شود.

ALTER TABLE app_coin_packs ADD COLUMN myket_enabled TINYINT(1) NOT NULL DEFAULT 1;
ALTER TABLE app_coin_packs ADD COLUMN myket_zibal_enabled TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE app_coin_packs ADD COLUMN show_fallback_button_myket TINYINT(1) NOT NULL DEFAULT 0;
