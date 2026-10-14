-- Nonfinancial resident identity is independent of occupancy and npc_tenants deletion.
CREATE TABLE IF NOT EXISTS npc_resident_profiles (
  resident_id       BINARY(16)   NOT NULL,
  catalogue_version INT          NOT NULL,
  name              VARCHAR(64)  NOT NULL,
  household         VARCHAR(64)  NOT NULL,
  generation        TINYINT      NOT NULL,
  occupation        VARCHAR(24)  NOT NULL,
  means             VARCHAR(24)  NOT NULL,
  life_stage        VARCHAR(24)  NOT NULL,
  temperament       VARCHAR(24)  NOT NULL,
  preference        VARCHAR(24)  NOT NULL,
  wardrobe_everyday VARCHAR(64)  NOT NULL,
  wardrobe_work     VARCHAR(64)  NOT NULL,
  wardrobe_leisure  VARCHAR(64)  NOT NULL,
  PRIMARY KEY (resident_id),
  CONSTRAINT ck_resident_catalogue CHECK (catalogue_version >= 1),
  CONSTRAINT ck_resident_generation CHECK (generation BETWEEN 1 AND 8),
  CONSTRAINT ck_resident_occupation CHECK (occupation IN ('RESTORER', 'SHOPKEEPER', 'GARDENER', 'COURIER', 'COOK', 'CARPENTER')),
  CONSTRAINT ck_resident_means CHECK (means IN ('STARTING_OUT', 'STEADY', 'ESTABLISHED')),
  CONSTRAINT ck_resident_life_stage CHECK (life_stage IN ('YOUNG_ADULT', 'ADULT', 'OLDER_ADULT')),
  CONSTRAINT ck_resident_temperament CHECK (temperament IN ('RESERVED', 'WARM', 'DIRECT', 'INQUISITIVE')),
  CONSTRAINT ck_resident_preference CHECK (preference IN ('WELL_KEPT_HOME', 'CLEAR_TERMS', 'QUIET_ROUTINE', 'NEIGHBORHOOD_LIFE')),
  CONSTRAINT ck_resident_everyday CHECK (wardrobe_everyday REGEXP BINARY '^[a-z0-9][a-z0-9_-]*(/[a-z0-9][a-z0-9_-]*)*$'),
  CONSTRAINT ck_resident_work CHECK (wardrobe_work REGEXP BINARY '^[a-z0-9][a-z0-9_-]*(/[a-z0-9][a-z0-9_-]*)*$'),
  CONSTRAINT ck_resident_leisure CHECK (wardrobe_leisure REGEXP BINARY '^[a-z0-9][a-z0-9_-]*(/[a-z0-9][a-z0-9_-]*)*$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Append-only evidence through ResidentRepository. No mutable score or opaque serialized profile.
-- occurred_at_millis is an epoch millisecond, not a database-local timestamp.
CREATE TABLE IF NOT EXISTS npc_resident_memory (
  resident_id        BINARY(16)    NOT NULL,
  player_id          BINARY(16)    NOT NULL,
  source_ref         VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  kind               VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  occurred_at_millis BIGINT        NOT NULL,
  economy_day        VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (resident_id, player_id, source_ref),
  KEY idx_memory_replay (resident_id, player_id, occurred_at_millis, source_ref),
  CONSTRAINT fk_memory_resident FOREIGN KEY (resident_id) REFERENCES npc_resident_profiles (resident_id) ON DELETE RESTRICT,
  CONSTRAINT ck_memory_source CHECK (source_ref REGEXP BINARY '^[a-z][a-z0-9:_./-]{0,159}$'),
  CONSTRAINT ck_memory_kind CHECK (kind IN ('INTRODUCTION', 'REPAIR_COMPLETED', 'OBLIGATION_KEPT', 'OBLIGATION_BROKEN')),
  CONSTRAINT ck_memory_introduction CHECK ((source_ref = 'introduction' AND kind = 'INTRODUCTION')
    OR (source_ref <> 'introduction' AND kind <> 'INTRODUCTION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
