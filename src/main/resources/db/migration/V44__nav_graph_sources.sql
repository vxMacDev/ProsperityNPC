-- How each graph was made: 'scan' (the block scan, every graph before this migration), 'waypoints' (staff points)
-- or 'file' (an imported route file). origin_sha is the document a waypoint or file graph was built from, and
-- origin_ref the file name it came from, for display only. Repeatable: safe to re-apply.
ALTER TABLE npc_nav_graphs ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'scan',
                           ADD COLUMN IF NOT EXISTS origin_sha CHAR(64) NULL,
                           ADD COLUMN IF NOT EXISTS origin_ref VARCHAR(64) NULL, ALGORITHM=INSTANT;

-- The current editable waypoint set, one per world and region. It points at its document by sha.
CREATE TABLE IF NOT EXISTS npc_nav_waypoint_sets (
  world_uuid BINARY(16) NOT NULL,
  region_id VARCHAR(128) NOT NULL,
  revision INT NOT NULL DEFAULT 1,
  doc_sha256 CHAR(64) NOT NULL,
  updated_by VARCHAR(32) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (world_uuid, region_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Every waypoint or route document ever built or imported, keyed by the sha of its canonical text. A graph is
-- always checked against its own document, so an older graph can still be restored and published as built.
CREATE TABLE IF NOT EXISTS npc_nav_route_docs (
  doc_sha256 CHAR(64) NOT NULL PRIMARY KEY,
  kind VARCHAR(16) NOT NULL,
  document MEDIUMTEXT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT ck_route_doc_kind CHECK (kind IN ('waypoints','file'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
