-- migration 006: notifications (توکن‌های FCM دستگاه‌ها + اطلاعیه‌های درون‌برنامه‌ای)
-- آینه‌ی AppDeviceToken و AppAnnouncement در models.py؛ ستون‌ها BIGINT
-- علامت‌دارند، مثل بقیه‌ی جدول‌ها در DB واقعی (نه UNSIGNED).
CREATE TABLE IF NOT EXISTS app_device_tokens (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  token VARCHAR(255) NOT NULL,
  platform VARCHAR(16) NOT NULL DEFAULT 'android',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_device_token (token),
  INDEX idx_device_token_user (user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS app_announcements (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  body TEXT NOT NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
