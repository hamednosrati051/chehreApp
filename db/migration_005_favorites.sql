-- migration 005: favorites (قلب روی دنیاها؛ یک کاربر هر دنیا را
-- حداکثر یک بار نشان می‌کند)
-- توجه: ستون‌ها BIGINT علامت‌دارند، چون app_users.id در DB واقعی
-- signed است (نه UNSIGNED که در schema.sql آمده).
CREATE TABLE app_favorites (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  world_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_favorite_user_world (user_id, world_id),
  FOREIGN KEY (user_id) REFERENCES app_users(id),
  FOREIGN KEY (world_id) REFERENCES app_worlds(id)
) ENGINE=InnoDB;
