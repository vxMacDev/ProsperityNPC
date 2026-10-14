-- Item repairs (item-repairs.enabled). An owner fixes one open incident by handing in plain items instead of
-- paying cash. No money moves: no economy event, purchase intent, transfer or cap row is ever written here.
-- Rows exist only after the feature has run, so with it never turned on every table stays empty.
-- Every statement can run again after a partial migration.

-- One hand-in. INTENT is written before any item is taken. It ends APPLIED (repair done), RETURN_ISSUED
-- (items proven taken but the home changed, so they come back as REPAIR_RETURN custody), CANCELLED
-- (nothing taken), AMBIGUOUS (held for the offline tool) or RECONCILED_NOT_COLLECTED (the tool proved
-- nothing was taken). One open hand-in per player and per incident.
CREATE TABLE IF NOT EXISTS npc_item_repair_collections (
  collection_id BINARY(16) NOT NULL,
  player_id BINARY(16) NOT NULL,
  property_id BINARY(16) NOT NULL,
  incident_id BIGINT NOT NULL,
  ownership_epoch INT NOT NULL,
  incident_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  repair_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  quote_day VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  cash_cents BIGINT NOT NULL,
  value_ratio_bps INT NOT NULL,
  required_value_cents BIGINT NOT NULL,
  recipe_value_cents BIGINT NOT NULL,
  price_version VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  quote_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  run_id BINARY(16) NOT NULL,
  outcome_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  open_player_id BINARY(16) GENERATED ALWAYS AS
    (CASE WHEN state IN ('INTENT','AMBIGUOUS') THEN player_id ELSE NULL END) STORED,
  open_incident_id BIGINT GENERATED ALWAYS AS
    (CASE WHEN state IN ('INTENT','AMBIGUOUS') THEN incident_id ELSE NULL END) STORED,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (collection_id),
  UNIQUE KEY uq_irc_open_player (open_player_id),
  UNIQUE KEY uq_irc_open_incident (open_incident_id),
  KEY idx_irc_recovery (state, run_id, collection_id),
  KEY idx_irc_player (player_id, state),
  KEY idx_irc_incident (incident_id, state),
  CONSTRAINT fk_irc_player FOREIGN KEY (player_id)
    REFERENCES npc_item_custody_players (player_id) ON DELETE RESTRICT,
  CONSTRAINT fk_irc_property FOREIGN KEY (property_id)
    REFERENCES npc_properties (property_id) ON DELETE RESTRICT,
  CONSTRAINT fk_irc_incident FOREIGN KEY (incident_id)
    REFERENCES npc_incidents (incident_id) ON DELETE RESTRICT,
  CONSTRAINT ck_irc_epoch CHECK (ownership_epoch >= 0),
  CONSTRAINT ck_irc_type CHECK (incident_type IN ('PESTS','PLUMBING','BREAK_IN')),
  CONSTRAINT ck_irc_hashes CHECK (repair_snapshot_sha256 REGEXP BINARY '^[a-f0-9]{64}$'
    AND quote_sha256 REGEXP BINARY '^[a-f0-9]{64}$'),
  CONSTRAINT ck_irc_day CHECK (quote_day REGEXP BINARY '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'),
  CONSTRAINT ck_irc_price_version CHECK (CHAR_LENGTH(price_version) BETWEEN 1 AND 96),
  -- Never cheaper than cash: the items are worth at least ratio x cash at full price-book value.
  CONSTRAINT ck_irc_value CHECK (cash_cents BETWEEN 1 AND 100000000
    AND value_ratio_bps BETWEEN 10000 AND 100000
    AND required_value_cents >= cash_cents
    AND required_value_cents = (cash_cents * value_ratio_bps + 9999) DIV 10000
    AND recipe_value_cents >= required_value_cents),
  CONSTRAINT ck_irc_state CHECK (state IN ('INTENT','CANCELLED','APPLIED','RETURN_ISSUED','AMBIGUOUS',
    'RECONCILED_NOT_COLLECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- The frozen item list. prototype_bytes is one plain item (count 1); before_units is what the player held
-- (inventory storage plus ender chest) when the hand-in started.
CREATE TABLE IF NOT EXISTS npc_item_repair_lines (
  collection_id BINARY(16) NOT NULL,
  line_no TINYINT NOT NULL,
  material VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  quantity INT NOT NULL,
  unit_full_cents BIGINT NOT NULL,
  prototype_bytes MEDIUMBLOB NOT NULL,
  prototype_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  before_units INT NOT NULL,
  PRIMARY KEY (collection_id, line_no),
  UNIQUE KEY uq_irl_material (collection_id, material),
  CONSTRAINT fk_irl_collection FOREIGN KEY (collection_id)
    REFERENCES npc_item_repair_collections (collection_id) ON DELETE RESTRICT,
  CONSTRAINT ck_irl_line CHECK (line_no BETWEEN 0 AND 3),
  CONSTRAINT ck_irl_material CHECK (material REGEXP BINARY '^[A-Z][A-Z0-9_]{0,63}$'),
  CONSTRAINT ck_irl_quantity CHECK (quantity BETWEEN 1 AND 256),
  CONSTRAINT ck_irl_unit CHECK (unit_full_cents BETWEEN 1 AND 100000000),
  CONSTRAINT ck_irl_prototype CHECK (OCTET_LENGTH(prototype_bytes) BETWEEN 1 AND 65536
    AND BINARY prototype_sha256 = BINARY SHA2(prototype_bytes,256)),
  CONSTRAINT ck_irl_before CHECK (before_units BETWEEN quantity AND 65536)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- The exact stacks to give back if the items are proven taken but the home changed. Built when the hand-in
-- starts (one stack never holds more than the item's stack size), so a return never needs the game to run.
CREATE TABLE IF NOT EXISTS npc_item_repair_return_plans (
  collection_id BINARY(16) NOT NULL,
  stack_no TINYINT NOT NULL,
  line_no TINYINT NOT NULL,
  quantity INT NOT NULL,
  payload_bytes MEDIUMBLOB NOT NULL,
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (collection_id, stack_no),
  CONSTRAINT fk_irp_line FOREIGN KEY (collection_id, line_no)
    REFERENCES npc_item_repair_lines (collection_id, line_no) ON DELETE RESTRICT,
  CONSTRAINT ck_irp_stack CHECK (stack_no BETWEEN 0 AND 3),
  CONSTRAINT ck_irp_quantity CHECK (quantity BETWEEN 1 AND 256),
  CONSTRAINT ck_irp_payload CHECK (OCTET_LENGTH(payload_bytes) BETWEEN 1 AND 65536
    AND BINARY payload_sha256 = BINARY SHA2(payload_bytes,256))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Immutable evidence from the take. A replay must match exactly.
CREATE TABLE IF NOT EXISTS npc_item_repair_evidence (
  collection_id BINARY(16) NOT NULL,
  player_id BINARY(16) NOT NULL,
  before_inventory_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  after_inventory_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  before_units_json VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  after_units_json VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  returned_normally BOOLEAN NOT NULL,
  durable BOOLEAN NOT NULL,
  recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (collection_id),
  CONSTRAINT fk_ire_collection FOREIGN KEY (collection_id)
    REFERENCES npc_item_repair_collections (collection_id) ON DELETE RESTRICT,
  CONSTRAINT ck_ire_hashes CHECK (before_inventory_sha256 REGEXP BINARY '^[a-f0-9]{64}$'
    AND after_inventory_sha256 REGEXP BINARY '^[a-f0-9]{64}$'),
  CONSTRAINT ck_ire_units CHECK (before_units_json REGEXP BINARY '^\\[[0-9]{1,5}(,[0-9]{1,5}){0,3}\\]$'
    AND after_units_json REGEXP BINARY '^\\[[0-9]{1,5}(,[0-9]{1,5}){0,3}\\]$'),
  CONSTRAINT ck_ire_flags CHECK (returned_normally IN (0,1) AND durable IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- The service fact for a repair paid with items, written only in the transaction that applies it. The cash
-- npc_incident_service_receipts table is untouched; both resolve through resolved_at IS NULL, so one incident
-- never gets both.
CREATE TABLE IF NOT EXISTS npc_incident_item_repair_receipts (
  incident_id BIGINT NOT NULL,
  collection_id BINARY(16) NOT NULL,
  property_id BINARY(16) NOT NULL,
  payer_uuid BINARY(16) NOT NULL,
  ownership_epoch INT NOT NULL,
  incident_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  cash_equivalent_cents BIGINT NOT NULL,
  recipe_value_cents BIGINT NOT NULL,
  repair_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  incident_created_at DATETIME(3) NOT NULL,
  incident_due_at DATETIME(3) NOT NULL,
  repaired_at DATETIME(3) NOT NULL,
  PRIMARY KEY (incident_id),
  UNIQUE KEY uq_item_receipt_collection (collection_id),
  CONSTRAINT fk_iir_incident FOREIGN KEY (incident_id) REFERENCES npc_incidents (incident_id) ON DELETE RESTRICT,
  CONSTRAINT fk_iir_collection FOREIGN KEY (collection_id)
    REFERENCES npc_item_repair_collections (collection_id) ON DELETE RESTRICT,
  CONSTRAINT fk_iir_property FOREIGN KEY (property_id) REFERENCES npc_properties (property_id) ON DELETE RESTRICT,
  CONSTRAINT ck_iir_epoch CHECK (ownership_epoch >= 0),
  CONSTRAINT ck_iir_type CHECK (incident_type IN ('PESTS','PLUMBING','BREAK_IN')),
  CONSTRAINT ck_iir_value CHECK (cash_equivalent_cents > 0 AND recipe_value_cents >= cash_equivalent_cents),
  CONSTRAINT ck_iir_hash CHECK (repair_snapshot_sha256 REGEXP BINARY '^[a-f0-9]{64}$'),
  CONSTRAINT ck_iir_time CHECK (incident_due_at >= incident_created_at AND repaired_at >= incident_created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Binds each REPAIR_RETURN custody row to the hand-in that proved the items were taken.
CREATE TABLE IF NOT EXISTS npc_item_repair_returns (
  collection_id BINARY(16) NOT NULL,
  stack_no TINYINT NOT NULL,
  request_nonce BINARY(16) NOT NULL,
  PRIMARY KEY (collection_id, stack_no),
  UNIQUE KEY uq_item_return_nonce (request_nonce),
  CONSTRAINT fk_irr_plan FOREIGN KEY (collection_id, stack_no)
    REFERENCES npc_item_repair_return_plans (collection_id, stack_no) ON DELETE RESTRICT,
  CONSTRAINT fk_irr_custody FOREIGN KEY (request_nonce)
    REFERENCES npc_item_custody (request_nonce) ON DELETE RESTRICT,
  CONSTRAINT ck_irr_stack CHECK (stack_no BETWEEN 0 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Offline tool decisions only (ItemRepairRecoveryTool, server stopped). Mirrors npc_item_custody_resolutions.
CREATE TABLE IF NOT EXISTS npc_item_repair_resolutions (
  collection_id BINARY(16) NOT NULL,
  resolution_id BINARY(16) NOT NULL,
  operator_ref VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  decision VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  evidence_bytes MEDIUMBLOB NOT NULL,
  evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  original_outcome VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (collection_id),
  UNIQUE KEY uq_item_repair_resolution (resolution_id),
  CONSTRAINT fk_irs_collection FOREIGN KEY (collection_id)
    REFERENCES npc_item_repair_collections (collection_id) ON DELETE RESTRICT,
  CONSTRAINT ck_irs_decision CHECK (decision IN ('VERIFIED_COLLECTED','VERIFIED_NOT_COLLECTED')),
  CONSTRAINT ck_irs_operator CHECK (operator_ref REGEXP BINARY '^[A-Za-z0-9_.:@-]{1,96}$'),
  CONSTRAINT ck_irs_evidence CHECK (OCTET_LENGTH(evidence_bytes) BETWEEN 1 AND 65536
    AND BINARY evidence_sha256 = BINARY SHA2(evidence_bytes,256))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Widen the custody source the V6 way: add the broader check first, then drop the old one. A partial run
-- only ever leaves both checks in place, which refuses REPAIR_RETURN rather than accepting a bad source.
ALTER TABLE npc_item_custody
  ADD CONSTRAINT IF NOT EXISTS ck_item_custody_source_v35
    CHECK (source IN ('WORKER_OUTPUT','MERCHANT_GOODS','REPAIR_RETURN'));

ALTER TABLE npc_item_custody DROP CONSTRAINT IF EXISTS ck_item_custody_source;
