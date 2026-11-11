-- ChehreSaz v13 — Bazaar login (db chehreApp, MySQL).
-- Run on the server at deploy time. No data is touched; both changes
-- are additive/nullable-only, so old app versions keep working.
-- NOTE: the unique index on app_users.mobile is preserved by MODIFY
-- (MODIFY COLUMN does not drop indexes); mobile only becomes nullable.

-- 1) Bazaar account id (unique, nullable — a mobile user has none yet).
ALTER TABLE app_users
  ADD COLUMN bazaar_account_id VARCHAR(128) NULL UNIQUE AFTER mobile;

-- 2) Mobile becomes nullable (Bazaar users are created with mobile NULL).
ALTER TABLE app_users
  MODIFY COLUMN mobile VARCHAR(20) NULL;

-- 3) Deploy step (NOT part of this migration — run once at deploy time,
--    with the actual deploy timestamp in UTC, ISO format):
--    INSERT INTO app_settings (`key`, value)
--      VALUES ('cleanup_free_images_cutoff', '<UTC ISO timestamp e.g. 2026-11-11T06:30:00>')
--      ON DUPLICATE KEY UPDATE value = VALUES(value);
--    The 24h cleanup deletes NOTHING until this setting exists, and
--    only touches free images completed AFTER this timestamp.
