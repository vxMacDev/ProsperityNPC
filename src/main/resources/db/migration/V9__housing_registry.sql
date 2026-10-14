-- Explicit finite admission, never a profile-per-vacancy generator.
CREATE TABLE IF NOT EXISTS npc_housing_registry_lock (
  singleton_id TINYINT NOT NULL PRIMARY KEY,
  CONSTRAINT ck_housing_singleton CHECK (singleton_id=1)
) ENGINE=InnoDB;

INSERT IGNORE INTO npc_housing_registry_lock(singleton_id) VALUES (1);

CREATE TABLE IF NOT EXISTS npc_housing_members (
  resident_id BINARY(16) NOT NULL PRIMARY KEY,
  world_uuid BINARY(16) NOT NULL,
  market_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  class_key VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tier VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  max_daily_rent_cents BIGINT NOT NULL,
  minimum_condition TINYINT NOT NULL,
  available_since CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  CONSTRAINT fk_housing_resident FOREIGN KEY (resident_id) REFERENCES npc_resident_profiles(resident_id) ON DELETE RESTRICT,
  CONSTRAINT ck_housing_revision CHECK (row_version BETWEEN 0 AND 9223372036854775806),
  CONSTRAINT ck_housing_rent CHECK (max_daily_rent_cents BETWEEN 1 AND 2147483647),
  CONSTRAINT ck_housing_condition CHECK (minimum_condition BETWEEN 0 AND 100),
  CONSTRAINT ck_housing_tier CHECK (tier IN ('STANDARD','PREMIUM')),
  CONSTRAINT ck_housing_market CHECK (market_id REGEXP '^[a-z][a-z0-9_-]{0,31}$'),
  CONSTRAINT ck_housing_class CHECK (class_key REGEXP '^[a-z][a-z0-9_-]{0,31}$'),
  CONSTRAINT ck_housing_enabled CHECK (enabled IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
