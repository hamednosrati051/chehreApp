-- ChehreSaz: انتشار گالری در کانال بله — ستون‌های وضعیت انتشار.
-- افزایشی و بی‌خطر: دو ستون nullable تازه؛ هیچ ستون/ردیف موجودی دست نمی‌خورد.
ALTER TABLE app_gallery
  ADD COLUMN bale_published_at DATETIME NULL,
  ADD COLUMN bale_message_id VARCHAR(64) NULL;
