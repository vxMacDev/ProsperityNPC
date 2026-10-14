-- Durable external-call intent and raw response evidence; never delete on rollback.
CREATE TABLE IF NOT EXISTS npc_transfer_attempts (
  attempt_id BINARY(16) NOT NULL,
  event_id BIGINT NOT NULL,
  run_id BINARY(16) NOT NULL,
  provider_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payer_uuid BINARY(16) NOT NULL,
  payee_uuid BINARY(16) NOT NULL,
  amount_cents BIGINT NOT NULL,
  state VARCHAR(24) NOT NULL,
  outcome_code VARCHAR(48) NULL,
  credit_intent_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (attempt_id),
  UNIQUE KEY uq_transfer_event (event_id),
  KEY idx_transfer_recovery (state,run_id,event_id),
  CONSTRAINT fk_transfer_event FOREIGN KEY (event_id) REFERENCES npc_economy_events(event_id) ON DELETE RESTRICT,
  CONSTRAINT ck_transfer_amount CHECK (amount_cents > 0),
  CONSTRAINT ck_transfer_parties CHECK (payer_uuid <> payee_uuid),
  CONSTRAINT ck_transfer_credit CHECK (state NOT IN ('CREDIT_INTENT','PAID') OR credit_intent_at IS NOT NULL),
  CONSTRAINT ck_transfer_unstarted CHECK (state NOT IN ('DEBIT_INTENT','CREDIT_READY','DECLINED') OR credit_intent_at IS NULL),
  CONSTRAINT ck_transfer_state CHECK (state IN ('DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT','PAID','DECLINED','AMBIGUOUS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- IEEE-754 raw bits retain unavailable/null, NaN, infinity and signed zero distinctly.
-- They are forensic evidence, not SQL floating-point money arithmetic.
CREATE TABLE IF NOT EXISTS npc_transfer_observations (
  attempt_id BINARY(16) NOT NULL,
  leg VARCHAR(6) NOT NULL,
  response_present BOOLEAN NOT NULL,
  response_success BOOLEAN NOT NULL,
  reported_bits BIGINT NULL,
  before_bits BIGINT NULL,
  after_bits BIGINT NULL,
  threw BOOLEAN NOT NULL,
  observed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (attempt_id,leg),
  CONSTRAINT fk_observation_attempt FOREIGN KEY (attempt_id) REFERENCES npc_transfer_attempts(attempt_id) ON DELETE RESTRICT,
  CONSTRAINT ck_observation_leg CHECK (leg IN ('DEBIT','CREDIT')),
  CONSTRAINT ck_observation_flags CHECK (response_present IN (0,1) AND response_success IN (0,1) AND threw IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
