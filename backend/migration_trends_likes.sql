-- ChehreSaz: image likes + standalone trends (with sort_order/display_type).
-- IDEMPOTENT (safe to re-run). Matches app/models.py exactly.
-- Handles BOTH states: tables missing (fresh CREATE with new columns)
-- and tables already created by the earlier revision (ALTER if column missing).
-- No DROP, no column/table removal.

CREATE TABLE IF NOT EXISTS app_image_likes (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  generation_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_like_user_generation (user_id, generation_id),
  KEY ix_app_image_likes_user_id (user_id)
);

CREATE TABLE IF NOT EXISTS app_trends (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL,
  `key` VARCHAR(100) NULL,
  sort_order INT NOT NULL DEFAULT 0,
  display_type VARCHAR(16) NOT NULL DEFAULT 'card',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_app_trends_key (`key`)
);

CREATE TABLE IF NOT EXISTS app_trend_worlds (
  id BIGINT NOT NULL AUTO_INCREMENT,
  trend_id BIGINT NOT NULL,
  world_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_trend_world (trend_id, world_id),
  KEY ix_app_trend_worlds_trend_id (trend_id)
);

-- Add the new columns only if missing (pre-existing table from earlier revision).
SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_trends'
    AND COLUMN_NAME = 'sort_order');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE app_trends ADD COLUMN sort_order INT NOT NULL DEFAULT 0',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'app_trends'
    AND COLUMN_NAME = 'display_type');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE app_trends ADD COLUMN display_type VARCHAR(16) NOT NULL DEFAULT ''card''',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Seed the 4 trends with order+type; if a keyed row already exists,
-- update its name/order/type (upsert on the UNIQUE key). Other trends untouched.
INSERT INTO app_trends (name, `key`, sort_order, display_type) VALUES
  ('ترندهای اینستاگرام', 'instagram', 1, 'card'),
  ('ترندهای ایرانی', 'iranian', 2, 'round'),
  ('ترند ورزشی', 'sport', 3, 'round'),
  ('ترند فصلی', 'seasonal', 4, 'round')
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  sort_order = VALUES(sort_order),
  display_type = VALUES(display_type);

-- Seed memberships from current world flags, only for the seeded trends,
-- inserting only memberships not already present (UNIQUE + INSERT IGNORE).
INSERT IGNORE INTO app_trend_worlds (trend_id, world_id)
SELECT t.id, w.id FROM app_trends t JOIN app_worlds w
  ON t.`key` = 'instagram' AND w.is_instagram_trend = 1;
INSERT IGNORE INTO app_trend_worlds (trend_id, world_id)
SELECT t.id, w.id FROM app_trends t JOIN app_worlds w
  ON t.`key` = 'iranian' AND w.is_iranian_trend = 1;
INSERT IGNORE INTO app_trend_worlds (trend_id, world_id)
SELECT t.id, w.id FROM app_trends t JOIN app_worlds w
  ON t.`key` = 'sport' AND w.is_sport_trend = 1;
INSERT IGNORE INTO app_trend_worlds (trend_id, world_id)
SELECT t.id, w.id FROM app_trends t JOIN app_worlds w
  ON t.`key` = 'seasonal' AND w.is_seasonal_trend = 1;
