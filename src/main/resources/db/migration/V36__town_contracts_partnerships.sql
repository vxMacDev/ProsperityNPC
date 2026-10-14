-- Contracts and partnerships (town.contracts.enabled, town.partnerships.enabled). A contract asks a player to hand
-- over plain items to a resident. The reward is familiarity (the existing OBLIGATION_KEPT memory), never money,
-- items or XP. A partnership is paid for the same way, in items, and gives a resident's perk for a set number of
-- economy days. No money moves here: no economy event, purchase intent, transfer or cap row is ever written.
-- Rows exist only after the features have run, so with them never turned on every table stays empty.
-- All new tables; no existing table is altered. Every statement can run again after a partial migration.

-- First lock for every contract and partnership hand-over of one player.
CREATE TABLE IF NOT EXISTS npc_town_contract_players (
  player_id BINARY(16) NOT NULL,
  PRIMARY KEY (player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- One contract, or one partnership start or renewal (kind PARTNERSHIP, day_slot 0). Immutable snapshot of what
-- was asked. HANDOVER_INTENT is written before any item is taken. Anything unclear is AMBIGUOUS, held for staff,
-- never retried. One open row (ACTIVE, HANDOVER_INTENT or AMBIGUOUS) per player.
CREATE TABLE IF NOT EXISTS npc_town_contracts (
  contract_id BINARY(16) NOT NULL,
  player_id BINARY(16) NOT NULL,
  kind VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  definition_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title VARCHAR(80) NOT NULL,
  description VARCHAR(512) NOT NULL,
  giver_id BINARY(16) NOT NULL,
  recipient_id BINARY(16) NOT NULL,
  material VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  quantity SMALLINT NOT NULL,
  minimum_stage VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  duration_minutes SMALLINT NOT NULL,
  accepted_at_millis BIGINT NOT NULL,
  accepted_economy_day VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  day_slot TINYINT NOT NULL,
  expires_at_millis BIGINT NOT NULL,
  status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  handover_run_id BINARY(16) NULL,
  closed_at_millis BIGINT NULL,
  completed_economy_day VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
  open_player_id BINARY(16) GENERATED ALWAYS AS
    (CASE WHEN status IN ('ACTIVE','HANDOVER_INTENT','AMBIGUOUS') THEN player_id ELSE NULL END) STORED,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (contract_id),
  UNIQUE KEY uq_contract_open_player (open_player_id),
  UNIQUE KEY uq_contract_player_day_slot (player_id, accepted_economy_day, day_slot),
  KEY idx_contract_giver_done (giver_id, player_id, status, completed_economy_day),
  KEY idx_contract_recovery (status, handover_run_id, contract_id),
  CONSTRAINT fk_contract_player FOREIGN KEY (player_id)
    REFERENCES npc_town_contract_players (player_id) ON DELETE RESTRICT,
  CONSTRAINT fk_contract_giver FOREIGN KEY (giver_id)
    REFERENCES npc_resident_profiles (resident_id) ON DELETE RESTRICT,
  CONSTRAINT fk_contract_recipient FOREIGN KEY (recipient_id)
    REFERENCES npc_resident_profiles (resident_id) ON DELETE RESTRICT,
  CONSTRAINT ck_contract_kind CHECK (kind IN ('SUPPLY','DELIVER','PARTNERSHIP')
    AND ((kind='SUPPLY' AND giver_id=recipient_id) OR (kind='DELIVER' AND giver_id<>recipient_id)
      OR (kind='PARTNERSHIP' AND giver_id=recipient_id AND day_slot=0))),
  CONSTRAINT ck_contract_slot CHECK (day_slot BETWEEN 0 AND 3 AND (kind='PARTNERSHIP' OR day_slot>=1)),
  CONSTRAINT ck_contract_definition CHECK (definition_id REGEXP BINARY '^[a-z0-9_-]{1,48}$'),
  CONSTRAINT ck_contract_material CHECK (material REGEXP BINARY '^[A-Z][A-Z0-9_]{0,63}$' AND quantity BETWEEN 1 AND 256),
  CONSTRAINT ck_contract_stage CHECK (minimum_stage IN ('STRANGER','ACQUAINTANCE','TRUSTED','ESTABLISHED')),
  CONSTRAINT ck_contract_days CHECK (accepted_economy_day REGEXP BINARY '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
    AND (completed_economy_day IS NULL OR completed_economy_day REGEXP BINARY '^[0-9]{4}-[0-9]{2}-[0-9]{2}$')),
  CONSTRAINT ck_contract_duration CHECK (duration_minutes BETWEEN 10 AND 1440
    AND expires_at_millis = accepted_at_millis + duration_minutes * 60000),
  CONSTRAINT ck_contract_status CHECK (status IN ('ACTIVE','HANDOVER_INTENT','COMPLETED','CANCELLED','EXPIRED',
    'AMBIGUOUS','RESOLVED_CONSUMED','RESOLVED_NOT_CONSUMED')),
  CONSTRAINT ck_contract_run CHECK ((status IN ('ACTIVE','CANCELLED','EXPIRED') AND handover_run_id IS NULL)
    OR (status IN ('HANDOVER_INTENT','COMPLETED','AMBIGUOUS','RESOLVED_CONSUMED','RESOLVED_NOT_CONSUMED')
      AND handover_run_id IS NOT NULL)),
  CONSTRAINT ck_contract_terminal CHECK (
    (status IN ('ACTIVE','HANDOVER_INTENT','AMBIGUOUS') AND closed_at_millis IS NULL AND completed_economy_day IS NULL)
    OR (status IN ('COMPLETED','RESOLVED_CONSUMED') AND closed_at_millis IS NOT NULL AND completed_economy_day IS NOT NULL)
    OR (status IN ('CANCELLED','RESOLVED_NOT_CONSUMED') AND closed_at_millis IS NOT NULL AND completed_economy_day IS NULL)
    OR (status='EXPIRED' AND closed_at_millis = expires_at_millis AND completed_economy_day IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Immutable evidence from the item take. A replay must match exactly.
CREATE TABLE IF NOT EXISTS npc_contract_handover_evidence (
  contract_id BINARY(16) NOT NULL,
  run_id BINARY(16) NOT NULL,
  material VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expected_units SMALLINT NOT NULL,
  before_units INT NOT NULL,
  after_units INT NOT NULL,
  before_inventory_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  after_inventory_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  returned_normally BOOLEAN NOT NULL,
  disk_proof BOOLEAN NOT NULL,
  recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (contract_id),
  CONSTRAINT fk_handover_contract FOREIGN KEY (contract_id)
    REFERENCES npc_town_contracts (contract_id) ON DELETE RESTRICT,
  CONSTRAINT ck_handover_counts CHECK (expected_units BETWEEN 1 AND 256 AND before_units BETWEEN 0 AND 65536
    AND after_units BETWEEN 0 AND 65536 AND returned_normally IN (0,1) AND disk_proof IN (0,1)),
  CONSTRAINT ck_handover_hashes CHECK (before_inventory_sha256 REGEXP BINARY '^[a-f0-9]{64}$'
    AND after_inventory_sha256 REGEXP BINARY '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- In-game staff decisions on held hand-overs (/npctown contract-resolve). Never returns items or money.
CREATE TABLE IF NOT EXISTS npc_contract_handover_resolutions (
  contract_id BINARY(16) NOT NULL,
  resolution_id BINARY(16) NOT NULL,
  operator_ref VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  case_ref VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  decision VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  partnership_started BOOLEAN NOT NULL,
  recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (contract_id),
  UNIQUE KEY uq_handover_resolution (resolution_id),
  CONSTRAINT fk_handover_resolution FOREIGN KEY (contract_id)
    REFERENCES npc_town_contracts (contract_id) ON DELETE RESTRICT,
  CONSTRAINT ck_handover_resolution CHECK (decision IN ('VERIFIED_CONSUMED','VERIFIED_NOT_CONSUMED')
    AND operator_ref REGEXP BINARY '^[A-Za-z0-9_.:@-]{1,96}$' AND case_ref REGEXP BINARY '^[A-Za-z0-9_.:@-]{1,96}$'
    AND partnership_started IN (0,1) AND (decision='VERIFIED_CONSUMED' OR partnership_started=0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- One partnership between a property owner and a resident. Perks are a snapshot from the start; the effective
-- perk is the lower of this snapshot and the current config. One ACTIVE partnership per owner.
CREATE TABLE IF NOT EXISTS npc_partnerships (
  partnership_id BINARY(16) NOT NULL,
  owner_id BINARY(16) NOT NULL,
  resident_id BINARY(16) NOT NULL,
  started_economy_day VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  last_active_day VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  fee_discount_pct TINYINT NOT NULL,
  rank_bonus_points TINYINT NOT NULL,
  status VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  end_reason VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ended_at_millis BIGINT NULL,
  start_contract_id BINARY(16) NOT NULL,
  revision BIGINT NOT NULL,
  active_owner_id BINARY(16) GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN owner_id ELSE NULL END) STORED,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (partnership_id),
  UNIQUE KEY uq_partnership_active_owner (active_owner_id),
  UNIQUE KEY uq_partnership_start_contract (start_contract_id),
  KEY idx_partnership_resident (resident_id, status, last_active_day),
  CONSTRAINT fk_partnership_resident FOREIGN KEY (resident_id)
    REFERENCES npc_resident_profiles (resident_id) ON DELETE RESTRICT,
  CONSTRAINT fk_partnership_contract FOREIGN KEY (start_contract_id)
    REFERENCES npc_town_contracts (contract_id) ON DELETE RESTRICT,
  CONSTRAINT ck_partnership_perks CHECK (fee_discount_pct BETWEEN 0 AND 60 AND rank_bonus_points BETWEEN 0 AND 4
    AND (fee_discount_pct>0 OR rank_bonus_points>0)),
  CONSTRAINT ck_partnership_days CHECK (started_economy_day REGEXP BINARY '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
    AND last_active_day REGEXP BINARY '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' AND last_active_day >= started_economy_day),
  CONSTRAINT ck_partnership_state CHECK ((status='ACTIVE' AND end_reason IS NULL AND ended_at_millis IS NULL)
    OR (status='ENDED' AND end_reason IN ('LAPSED','OWNER_ENDED','STAFF_ENDED') AND ended_at_millis IS NOT NULL)),
  CONSTRAINT ck_partnership_revision CHECK (revision >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- One row per paid renewal: which hand-over extended which partnership, from and to which last day.
CREATE TABLE IF NOT EXISTS npc_partnership_renewals (
  contract_id BINARY(16) NOT NULL,
  partnership_id BINARY(16) NOT NULL,
  previous_last_day VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  new_last_day VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (contract_id),
  KEY idx_renewal_partnership (partnership_id),
  CONSTRAINT fk_renewal_contract FOREIGN KEY (contract_id)
    REFERENCES npc_town_contracts (contract_id) ON DELETE RESTRICT,
  CONSTRAINT fk_renewal_partnership FOREIGN KEY (partnership_id)
    REFERENCES npc_partnerships (partnership_id) ON DELETE RESTRICT,
  CONSTRAINT ck_renewal_days CHECK (new_last_day > previous_last_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
