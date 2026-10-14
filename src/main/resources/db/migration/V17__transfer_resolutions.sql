-- Explicit operator-adjudicated compensation; original provider observations stay intact.
CREATE TABLE IF NOT EXISTS npc_transfer_resolutions (
  attempt_id BINARY(16) NOT NULL PRIMARY KEY,
  resolution_id BINARY(16) NOT NULL UNIQUE,
  operator_ref VARCHAR(96) NOT NULL,
  evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  original_outcome VARCHAR(48) NOT NULL,
  refund_before_bits BIGINT NOT NULL,
  refund_after_bits BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT fk_resolution_attempt FOREIGN KEY (attempt_id) REFERENCES npc_transfer_attempts(attempt_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
ALTER TABLE npc_transfer_attempts DROP CONSTRAINT IF EXISTS ck_transfer_state;
ALTER TABLE npc_transfer_attempts ADD CONSTRAINT ck_transfer_state CHECK
  (state IN ('DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT','PAID','DECLINED','AMBIGUOUS','COMPENSATED'));
