-- ChehreSaz v10: ترتیب دنیاها داخل ترند (drag-reorder ادمین).
-- افزایشی و بی‌خطر: یک ستون تازه + بک‌فیل.
-- بک‌فیل = id ردیف عضویت: چون idها یکتا هستند، مرتب‌سازی بر اساس
-- sort_order داخل هر ترند دقیقاً همان ترتیب امروز (id) را می‌دهد.
ALTER TABLE app_trend_worlds ADD COLUMN sort_order INT NOT NULL DEFAULT 0;
UPDATE app_trend_worlds SET sort_order = id;
