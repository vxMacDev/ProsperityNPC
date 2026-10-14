-- Permanent issuance budgets count all accepted orders, even cancellation, decline and uncertainty.
CREATE TABLE IF NOT EXISTS npc_commerce_days (
  economy_day VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  max_orders INT NOT NULL,
  max_units INT NOT NULL,
  max_cost_cents BIGINT NOT NULL,
  issued_orders INT NOT NULL DEFAULT 0,
  issued_units INT NOT NULL DEFAULT 0,
  issued_cost_cents BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (economy_day),
  CONSTRAINT ck_commerce_day_budget CHECK (max_orders BETWEEN 1 AND 4096 AND max_units BETWEEN 1 AND 262144
    AND max_cost_cents BETWEEN 1 AND 409600000000 AND issued_orders BETWEEN 0 AND max_orders
    AND issued_units BETWEEN 0 AND max_units AND issued_cost_cents BETWEEN 0 AND max_cost_cents)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS npc_commerce_material_days (
  economy_day VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  material VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  max_units INT NOT NULL,
  issued_units INT NOT NULL DEFAULT 0,
  PRIMARY KEY (economy_day,material),
  CONSTRAINT fk_commerce_material_day FOREIGN KEY (economy_day) REFERENCES npc_commerce_days(economy_day) ON DELETE RESTRICT,
  CONSTRAINT ck_commerce_material_budget CHECK (max_units BETWEEN 1 AND 262144 AND issued_units BETWEEN 0 AND max_units)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS npc_commerce_players (
  player_id BINARY(16) NOT NULL,
  PRIMARY KEY (player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS npc_commerce_orders (
  order_id BINARY(16) NOT NULL,
  issuance_nonce BINARY(16) NOT NULL,
  player_id BINARY(16) NOT NULL,
  resident_id BINARY(16) NOT NULL,
  source VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  catalogue_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  material VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  quantity INT NOT NULL,
  cost_cents BIGINT NOT NULL,
  economy_day VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  issued_at_millis BIGINT NOT NULL,
  output_delay_seconds INT NOT NULL,
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  run_id BINARY(16) NULL,
  provider_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  payer_id BINARY(16) NULL,
  reserve_id BINARY(16) NULL,
  credit_started BOOLEAN NOT NULL DEFAULT 0,
  funded_at_millis BIGINT NULL,
  output_ready_at_millis BIGINT NULL,
  custody_nonce BINARY(16) NULL,
  outcome_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  worker_player_id BINARY(16) GENERATED ALWAYS AS (CASE WHEN source='WORKER_OUTPUT' THEN player_id ELSE NULL END) STORED,
  money_player_id BINARY(16) GENERATED ALWAYS AS
    (CASE WHEN state IN ('DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT','AMBIGUOUS') THEN player_id ELSE NULL END) STORED,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (order_id),
  UNIQUE KEY uq_commerce_issuance_nonce (issuance_nonce),
  UNIQUE KEY uq_commerce_worker_day (worker_player_id,economy_day),
  UNIQUE KEY uq_commerce_money_player (money_player_id),
  UNIQUE KEY uq_commerce_custody (custody_nonce),
  KEY idx_commerce_player (player_id,issued_at_millis,order_id),
  KEY idx_commerce_recovery (state,run_id,order_id),
  KEY idx_commerce_paid (state,funded_at_millis,order_id),
  CONSTRAINT fk_commerce_player FOREIGN KEY (player_id) REFERENCES npc_commerce_players(player_id) ON DELETE RESTRICT,
  CONSTRAINT fk_commerce_resident FOREIGN KEY (resident_id) REFERENCES npc_resident_profiles(resident_id) ON DELETE RESTRICT,
  CONSTRAINT fk_commerce_day FOREIGN KEY (economy_day) REFERENCES npc_commerce_days(economy_day) ON DELETE RESTRICT,
  CONSTRAINT fk_commerce_material FOREIGN KEY (economy_day,material)
    REFERENCES npc_commerce_material_days(economy_day,material) ON DELETE RESTRICT,
  CONSTRAINT fk_commerce_custody FOREIGN KEY (custody_nonce) REFERENCES npc_item_custody(request_nonce) ON DELETE RESTRICT,
  CONSTRAINT ck_commerce_source CHECK (source IN ('MERCHANT_GOODS','WORKER_OUTPUT')),
  CONSTRAINT ck_commerce_catalogue CHECK (catalogue_id REGEXP BINARY '^[a-z][a-z0-9_.:-]{0,63}$'),
  CONSTRAINT ck_commerce_material CHECK (material REGEXP BINARY '^[A-Z][A-Z0-9_]{0,63}$'),
  CONSTRAINT ck_commerce_request CHECK (quantity BETWEEN 1 AND 64 AND cost_cents BETWEEN 1 AND 100000000
    AND issued_at_millis>=0 AND request_sha256 REGEXP BINARY '^[a-f0-9]{64}$'
    AND output_delay_seconds BETWEEN 0 AND 86400
    AND ((source='MERCHANT_GOODS' AND output_delay_seconds=0) OR (source='WORKER_OUTPUT' AND output_delay_seconds>0))),
  CONSTRAINT ck_commerce_binding CHECK (
    (state IN ('RESERVED','CANCELLED') AND run_id IS NULL AND provider_sha256 IS NULL AND payer_id IS NULL AND reserve_id IS NULL AND credit_started=0)
    OR (state IN ('DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT','FUNDED','DECLINED','AMBIGUOUS')
      AND run_id IS NOT NULL AND provider_sha256 IS NOT NULL AND provider_sha256 REGEXP BINARY '^[a-f0-9]{64}$'
      AND payer_id IS NOT NULL AND payer_id=player_id AND reserve_id IS NOT NULL AND reserve_id<>payer_id)),
  CONSTRAINT ck_commerce_credit CHECK (credit_started IN (0,1)
    AND (state NOT IN ('CREDIT_INTENT','FUNDED') OR credit_started=1)
    AND (state NOT IN ('RESERVED','CANCELLED','DEBIT_INTENT','CREDIT_READY','DECLINED') OR credit_started=0)),
  CONSTRAINT ck_commerce_ready CHECK (
    (state='FUNDED' AND funded_at_millis IS NOT NULL AND funded_at_millis>=issued_at_millis
      AND output_ready_at_millis IS NOT NULL AND output_ready_at_millis=funded_at_millis+output_delay_seconds*1000)
    OR (state<>'FUNDED' AND funded_at_millis IS NULL AND output_ready_at_millis IS NULL AND custody_nonce IS NULL)),
  CONSTRAINT ck_commerce_custody_nonce CHECK (custody_nonce IS NULL OR custody_nonce=order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Raw IEEE-754 bits preserve exact repeated/contradictory provider evidence; no SQL balance arithmetic.
CREATE TABLE IF NOT EXISTS npc_commerce_money_evidence (
  order_id BINARY(16) NOT NULL,
  leg VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  response_present BOOLEAN NOT NULL,
  response_success BOOLEAN NOT NULL,
  reported_bits BIGINT NULL,
  before_bits BIGINT NULL,
  after_bits BIGINT NULL,
  threw BOOLEAN NOT NULL,
  recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (order_id,leg),
  CONSTRAINT fk_commerce_evidence FOREIGN KEY (order_id) REFERENCES npc_commerce_orders(order_id) ON DELETE RESTRICT,
  CONSTRAINT ck_commerce_evidence CHECK (leg IN ('DEBIT','CREDIT')
    AND response_present IN (0,1) AND response_success IN (0,1) AND threw IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
