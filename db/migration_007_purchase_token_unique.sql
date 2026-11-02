-- 007: یکتایی توکن خرید بازار؛ جلوی دوبار شارژ یک توکن در وریفای هم‌زمان.
-- روی سرور: mysql chehreApp < migration_007_purchase_token_unique.sql
-- (جدول فعلاً ردیفی ندارد؛ ALTER بی‌خطر است.)
ALTER TABLE app_purchases ADD UNIQUE KEY uq_bazaar_purchase_token (bazaar_purchase_token);
