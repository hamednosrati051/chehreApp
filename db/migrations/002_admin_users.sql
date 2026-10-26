-- 002: کاربران ادمین خود اپ (ورود یوزر/پسورد به ادمین وب).
-- روی سرور: mysql chehreApp < 002_admin_users.sql
CREATE TABLE IF NOT EXISTS app_admin_users (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(100) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;
