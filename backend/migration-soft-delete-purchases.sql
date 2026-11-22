-- ChehreSaz: حذف منطقی پرداخت‌ها (۲۰۲۶-۱۱-۲۲).
-- افزایشی و بی‌خطر: یک ستون nullable تازه؛ هیچ ردیفی دست نمی‌خورد و
-- همه‌ی ردیف‌های موجود deleted_at=NULL می‌گیرند (یعنی حذف‌نشده).
-- بعد از این مهاجرت، انقضا و حذف گروهی ادمین به‌جای DELETE واقعی
-- deleted_at را پر می‌کنند؛ وریفای ردیف حذف‌شده را برمی‌گرداند.
-- اجرا روی سرور (MySQL) پیش از استقرار؛ روی SQLite تست‌ها
-- create_all جدول‌ها را می‌سازد و این فایل اجرا نمی‌شود.

ALTER TABLE app_purchases ADD COLUMN deleted_at DATETIME NULL;
CREATE INDEX idx_purchases_deleted_at ON app_purchases (deleted_at);
