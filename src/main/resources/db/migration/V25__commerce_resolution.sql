-- Install stronger replacement constraints before removing their earlier versions.
ALTER TABLE npc_commerce_orders ADD CONSTRAINT IF NOT EXISTS ck_commerce_binding_v25 CHECK (
  (state IN ('RESERVED','CANCELLED') AND run_id IS NULL AND provider_sha256 IS NULL
    AND payer_id IS NULL AND reserve_id IS NULL AND credit_started=0)
  OR (state IN ('DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT','FUNDED','DECLINED','AMBIGUOUS','COMPENSATED')
    AND run_id IS NOT NULL AND provider_sha256 IS NOT NULL AND provider_sha256 REGEXP BINARY '^[a-f0-9]{64}$'
    AND payer_id IS NOT NULL AND payer_id=player_id AND reserve_id IS NOT NULL AND reserve_id<>payer_id));
ALTER TABLE npc_commerce_orders ADD CONSTRAINT IF NOT EXISTS ck_commerce_credit_v25 CHECK (
  credit_started IN (0,1) AND (state NOT IN ('CREDIT_INTENT','FUNDED') OR credit_started=1)
  AND (state NOT IN ('RESERVED','CANCELLED','DEBIT_INTENT','CREDIT_READY','DECLINED','COMPENSATED') OR credit_started=0));
ALTER TABLE npc_commerce_orders DROP CONSTRAINT IF EXISTS ck_commerce_binding;
ALTER TABLE npc_commerce_orders DROP CONSTRAINT IF EXISTS ck_commerce_credit;
ALTER TABLE npc_commerce_orders ADD INDEX IF NOT EXISTS idx_commerce_unstarted (state,issued_at_millis,order_id);

-- One immutable adjudication per held payment. No money dispatch and no replacement item grant.
CREATE TABLE IF NOT EXISTS npc_commerce_payment_resolutions (
  order_id BINARY(16) NOT NULL,
  resolution_id BINARY(16) NOT NULL,
  run_id BINARY(16) NOT NULL,
  payer_id BINARY(16) NOT NULL,
  reserve_id BINARY(16) NOT NULL,
  provider_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  cost_cents BIGINT NOT NULL,
  operator_ref VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  case_ref VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  notes VARCHAR(2048) NOT NULL,
  decision VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  evidence_bytes MEDIUMBLOB NOT NULL,
  evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  refund_before_bits BIGINT NULL,
  refund_after_bits BIGINT NULL,
  resolved_at_millis BIGINT NOT NULL,
  previous_outcome VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (order_id),
  UNIQUE KEY uq_commerce_resolution (resolution_id),
  CONSTRAINT fk_commerce_resolution FOREIGN KEY (order_id) REFERENCES npc_commerce_orders(order_id) ON DELETE RESTRICT,
  CONSTRAINT ck_commerce_resolution_binding CHECK (payer_id<>reserve_id AND cost_cents BETWEEN 1 AND 100000000
    AND provider_sha256 REGEXP BINARY '^[a-f0-9]{64}$' AND request_sha256 REGEXP BINARY '^[a-f0-9]{64}$'),
  CONSTRAINT ck_commerce_resolution_case CHECK (operator_ref REGEXP BINARY '^[A-Za-z0-9_.:@-]{1,96}$'
    AND case_ref REGEXP BINARY '^[A-Za-z0-9_.:@-]{1,96}$' AND CHAR_LENGTH(TRIM(notes)) BETWEEN 1 AND 2048
    AND OCTET_LENGTH(evidence_bytes) BETWEEN 1 AND 65536 AND evidence_sha256 REGEXP BINARY '^[a-f0-9]{64}$'
    AND resolved_at_millis>=0),
  CONSTRAINT ck_commerce_resolution_decision CHECK (
    (decision IN ('VERIFIED_PAID','VERIFIED_NO_MOVEMENT') AND refund_before_bits IS NULL AND refund_after_bits IS NULL)
    OR (decision='VERIFIED_REFUNDED_DEBIT' AND refund_before_bits IS NOT NULL AND refund_after_bits IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
