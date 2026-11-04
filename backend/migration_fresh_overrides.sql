-- ChehreSaz v10: «تازه‌های هفته» — جدول دخالت ادمین در فهرست خودکار.
-- افزایشی و بی‌خطر: جدول تازه است، هیچ ستون/ردیف موجودی دست نمی‌خورد.
-- world_id کلید اصلی است (هر دنیا حداکثر یک دخالت: pin یا hide).
CREATE TABLE IF NOT EXISTS app_fresh_overrides (
  world_id BIGINT NOT NULL PRIMARY KEY,
  action VARCHAR(10) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT chk_fresh_action CHECK (action IN ('pin', 'hide'))
);
