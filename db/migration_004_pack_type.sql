-- migration 004: pack_type for AppCoinPack (nullable; admin sets it, e.g. اقتصادی)
ALTER TABLE app_coin_packs ADD COLUMN pack_type VARCHAR(50) NULL;
