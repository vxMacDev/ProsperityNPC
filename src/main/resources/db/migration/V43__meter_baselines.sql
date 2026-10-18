-- The last reading of a cumulative meter for a home. The first reading only sets it; a lower reading is a reset.
CREATE TABLE IF NOT EXISTS npc_meter_baselines (
  property_id BINARY(16) NOT NULL,
  meter_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  last_value BIGINT NOT NULL,
  last_day CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (property_id,meter_id),
  CONSTRAINT fk_meter_baseline_property FOREIGN KEY (property_id) REFERENCES npc_properties(property_id) ON DELETE RESTRICT,
  CONSTRAINT ck_meter_baseline CHECK (last_value BETWEEN 0 AND 1000000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
