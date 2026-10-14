-- Shared across worker output and merchant goods. No wallet, Bukkit operation or automatic retry.
CREATE TABLE IF NOT EXISTS npc_item_custody_players (
  player_id BINARY(16) NOT NULL,
  PRIMARY KEY (player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS npc_item_custody (
  request_nonce BINARY(16) NOT NULL,
  player_id BINARY(16) NOT NULL,
  offer_id BINARY(16) NOT NULL,
  source VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  quantity INT NOT NULL,
  payload_bytes MEDIUMBLOB NOT NULL,
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reserved_at_millis BIGINT NOT NULL,
  expires_at_millis BIGINT NOT NULL,
  state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  delivery_run_id BINARY(16) NULL,
  outcome_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  delivery_player_id BINARY(16) GENERATED ALWAYS AS
    (CASE WHEN state IN ('DELIVERY_INTENT','AMBIGUOUS') THEN player_id ELSE NULL END) STORED,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (request_nonce),
  UNIQUE KEY uq_item_custody_offer (offer_id),
  UNIQUE KEY uq_item_custody_player_delivery (delivery_player_id),
  KEY idx_item_custody_player (player_id, state, request_nonce),
  KEY idx_item_custody_recovery (state, delivery_run_id, request_nonce),
  KEY idx_item_custody_expiry (state, expires_at_millis, request_nonce),
  CONSTRAINT fk_item_custody_player FOREIGN KEY (player_id)
    REFERENCES npc_item_custody_players (player_id) ON DELETE RESTRICT,
  CONSTRAINT ck_item_custody_source CHECK (source IN ('WORKER_OUTPUT','MERCHANT_GOODS')),
  CONSTRAINT ck_item_custody_quantity CHECK (quantity BETWEEN 1 AND 256),
  CONSTRAINT ck_item_custody_payload CHECK (OCTET_LENGTH(payload_bytes) BETWEEN 1 AND 65536
    AND BINARY payload_sha256 = BINARY SHA2(payload_bytes,256)),
  CONSTRAINT ck_item_custody_expiry CHECK (reserved_at_millis >= 0
    AND expires_at_millis > reserved_at_millis AND expires_at_millis <= reserved_at_millis + 900000),
  CONSTRAINT ck_item_custody_state CHECK (
    (state IN ('RESERVED','CANCELLED','EXPIRED') AND delivery_run_id IS NULL)
    OR (state IN ('DELIVERY_INTENT','DELIVERED','AMBIGUOUS','RECONCILED_NOT_DELIVERED') AND delivery_run_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS npc_item_delivery_evidence (
  request_nonce BINARY(16) NOT NULL,
  player_id BINARY(16) NOT NULL,
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  before_inventory_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  after_inventory_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  before_units INT NOT NULL,
  after_units INT NOT NULL,
  leftover_units INT NOT NULL,
  returned_normally BOOLEAN NOT NULL,
  recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (request_nonce),
  CONSTRAINT fk_item_delivery_request FOREIGN KEY (request_nonce)
    REFERENCES npc_item_custody (request_nonce) ON DELETE RESTRICT,
  CONSTRAINT ck_item_delivery_counts CHECK (before_units BETWEEN 0 AND 65536
    AND after_units BETWEEN 0 AND 65536 AND leftover_units BETWEEN 0 AND 256),
  CONSTRAINT ck_item_delivery_normal CHECK (returned_normally IN (0,1)),
  CONSTRAINT ck_item_delivery_hashes CHECK (
    payload_sha256 REGEXP BINARY '^[a-f0-9]{64}$'
    AND before_inventory_sha256 REGEXP BINARY '^[a-f0-9]{64}$'
    AND after_inventory_sha256 REGEXP BINARY '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Authenticated operator/offline evidence only. Even verified non-delivery burns the old offer/nonce.
CREATE TABLE IF NOT EXISTS npc_item_custody_resolutions (
  request_nonce BINARY(16) NOT NULL,
  resolution_id BINARY(16) NOT NULL,
  operator_ref VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  decision VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  evidence_bytes MEDIUMBLOB NOT NULL,
  evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  original_outcome VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (request_nonce),
  UNIQUE KEY uq_item_custody_resolution (resolution_id),
  CONSTRAINT fk_item_resolution_request FOREIGN KEY (request_nonce)
    REFERENCES npc_item_custody (request_nonce) ON DELETE RESTRICT,
  CONSTRAINT ck_item_resolution_decision CHECK (decision IN ('VERIFIED_DELIVERED','VERIFIED_NOT_DELIVERED')),
  CONSTRAINT ck_item_resolution_operator CHECK (operator_ref REGEXP BINARY '^[A-Za-z0-9_.:@-]{1,96}$'),
  CONSTRAINT ck_item_resolution_evidence CHECK (OCTET_LENGTH(evidence_bytes) BETWEEN 1 AND 65536
    AND BINARY evidence_sha256 = BINARY SHA2(evidence_bytes,256))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
