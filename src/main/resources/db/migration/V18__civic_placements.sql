-- Staff-authored nonfinancial placement; never creates identity, occupancy, or a synthetic property.
CREATE TABLE IF NOT EXISTS npc_civic_placements (
  resident_id BINARY(16) NOT NULL,
  world_uuid  BINARY(16) NOT NULL,
  district_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  enabled     BOOLEAN NOT NULL DEFAULT TRUE,
  revision    BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY (resident_id),
  KEY idx_civic_display (world_uuid, enabled, resident_id),
  CONSTRAINT fk_civic_resident FOREIGN KEY (resident_id) REFERENCES npc_resident_profiles(resident_id) ON DELETE RESTRICT,
  CONSTRAINT ck_civic_district CHECK (district_id REGEXP BINARY '^[A-Za-z0-9_-]{1,128}$'),
  CONSTRAINT ck_civic_enabled CHECK (enabled IN (0,1)),
  CONSTRAINT ck_civic_revision CHECK (revision >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
