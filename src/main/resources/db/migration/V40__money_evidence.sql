-- Refusal proof: a non-null reason means the plugin proved the provider was never asked to move money.
-- Rows written before this migration keep NULL, which means "called, or unknown". Each branch tests for NULL
-- itself: a CHECK that comes out unknown passes, so a cause without a reason must come out false, not unknown.
ALTER TABLE npc_transfer_observations
  ADD COLUMN IF NOT EXISTS refused_before_call VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER threw,
  ADD COLUMN IF NOT EXISTS refusal_cause VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER refused_before_call;
ALTER TABLE npc_transfer_observations ADD CONSTRAINT IF NOT EXISTS ck_observation_refusal CHECK (
  (refused_before_call IS NULL AND refusal_cause IS NULL)
  OR (refused_before_call IS NOT NULL AND refused_before_call IN ('WRONG_THREAD','INVALID_REQUEST','PROVIDER_CHANGED','LEASE_REFUSED','LEASE_FAILED',
        'PRECHECK_REFUSED','PRECHECK_FAILED','COUNTERPARTY_UNVERIFIABLE','BALANCE_UNVERIFIABLE','INSUFFICIENT_FUNDS')
      AND refusal_cause IS NOT NULL AND refusal_cause REGEXP BINARY '^[A-Za-z0-9_$.]{1,64}$'
      AND response_present=0 AND response_success=0 AND reported_bits IS NULL AND threw=1));

ALTER TABLE npc_commerce_money_evidence
  ADD COLUMN IF NOT EXISTS refused_before_call VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER threw,
  ADD COLUMN IF NOT EXISTS refusal_cause VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER refused_before_call;
ALTER TABLE npc_commerce_money_evidence ADD CONSTRAINT IF NOT EXISTS ck_commerce_evidence_refusal CHECK (
  (refused_before_call IS NULL AND refusal_cause IS NULL)
  OR (refused_before_call IS NOT NULL AND refused_before_call IN ('WRONG_THREAD','INVALID_REQUEST','PROVIDER_CHANGED','LEASE_REFUSED','LEASE_FAILED',
        'PRECHECK_REFUSED','PRECHECK_FAILED','COUNTERPARTY_UNVERIFIABLE','BALANCE_UNVERIFIABLE','INSUFFICIENT_FUNDS')
      AND refusal_cause IS NOT NULL AND refusal_cause REGEXP BINARY '^[A-Za-z0-9_$.]{1,64}$'
      AND response_present=0 AND response_success=0 AND reported_bits IS NULL AND threw=1));

-- Resolution kinds. Every existing row is an operator refund; backfilled explicitly, not only by DEFAULT.
-- Only rows without a kind are backfilled, so a rerun never turns a legacy refusal into a refund.
ALTER TABLE npc_transfer_resolutions
  ADD COLUMN IF NOT EXISTS kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REFUND',
  ADD COLUMN IF NOT EXISTS credit_proof VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
  MODIFY refund_before_bits BIGINT NULL,
  MODIFY refund_after_bits BIGINT NULL;
UPDATE npc_transfer_resolutions SET kind='REFUND' WHERE kind IS NULL;
ALTER TABLE npc_transfer_resolutions ADD CONSTRAINT IF NOT EXISTS ck_resolution_kind CHECK (
  (kind='REFUND' AND refund_before_bits IS NOT NULL AND refund_after_bits IS NOT NULL
     AND (credit_proof IS NULL OR credit_proof IN ('REFUSED','LEGACY')))
  OR (kind='LEGACY_REFUSAL' AND refund_before_bits IS NULL AND refund_after_bits IS NULL AND credit_proof IS NULL));

-- Commerce: install the replacement checks before removing the V25 versions they replace.
ALTER TABLE npc_commerce_payment_resolutions ADD CONSTRAINT IF NOT EXISTS ck_commerce_resolution_decision_v40 CHECK (
  (decision IN ('VERIFIED_PAID','VERIFIED_NO_MOVEMENT','VERIFIED_LEGACY_REFUSAL')
     AND refund_before_bits IS NULL AND refund_after_bits IS NULL)
  OR (decision='VERIFIED_REFUNDED_DEBIT' AND refund_before_bits IS NOT NULL AND refund_after_bits IS NOT NULL));
ALTER TABLE npc_commerce_payment_resolutions DROP CONSTRAINT IF EXISTS ck_commerce_resolution_decision;
-- A refund may now follow a credit that was refused before its call, so COMPENSATED allows a started credit.
ALTER TABLE npc_commerce_orders ADD CONSTRAINT IF NOT EXISTS ck_commerce_credit_v40 CHECK (
  credit_started IN (0,1) AND (state NOT IN ('CREDIT_INTENT','FUNDED') OR credit_started=1)
  AND (state NOT IN ('RESERVED','CANCELLED','DEBIT_INTENT','CREDIT_READY','DECLINED') OR credit_started=0));
ALTER TABLE npc_commerce_orders DROP CONSTRAINT IF EXISTS ck_commerce_credit_v25;

-- One staff refund closes one held payment. A refund is known by its payer and its before and after balances, and
-- each refund resolution in either table claims them here in its own transaction. A refund can only reverse a debit
-- made before it, so a claim recorded after a payment's debit began may be that payment's own refund: a resolution
-- for that payment with the same payer and the same before or the same after balance is refused. A claim recorded
-- before the debit began is a different refund and does not block it. Resolution ids are unique only within their
-- own table, so a claim is keyed by its table and its resolution id. evidence_sha256 is the SHA-256 of the
-- refund-observed.json a claim was resolved with, and one file closes one payment in either table. Refunds claimed
-- by the backfill below have none: their files name no payment, and the tools no longer accept such files.
-- distinct_override is set when staff asserted that a refund is a second, separate refund for its payment, which
-- skips only the matching balances rule above for that one resolution.
CREATE TABLE IF NOT EXISTS npc_refund_claims (
  source VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  resolution_id BINARY(16) NOT NULL,
  payer_id BINARY(16) NOT NULL,
  refund_before_bits BIGINT NOT NULL,
  refund_after_bits BIGINT NOT NULL,
  evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  distinct_override BOOLEAN NOT NULL DEFAULT FALSE,
  recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (source,resolution_id),
  UNIQUE KEY uq_refund_claim_evidence (evidence_sha256),
  KEY idx_refund_claim_before (payer_id,refund_before_bits,recorded_at),
  KEY idx_refund_claim_after (payer_id,refund_after_bits,recorded_at),
  CONSTRAINT ck_refund_claim_source CHECK (source IN ('TRANSFER','COMMERCE')),
  CONSTRAINT ck_refund_claim_evidence CHECK (evidence_sha256 IS NULL OR evidence_sha256 REGEXP BINARY '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
-- One row per payer, locked by each claim for that payer, so two claims for one payer check and write in turn.
CREATE TABLE IF NOT EXISTS npc_refund_claim_payers (
  payer_id BINARY(16) NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
-- Refunds recorded before this table existed are claimed too, each at the time it was recorded. A rerun adds
-- only the refunds not yet claimed, and every old refund is claimed: none is dropped as a duplicate.
INSERT INTO npc_refund_claims(source,resolution_id,payer_id,refund_before_bits,refund_after_bits,recorded_at)
  SELECT 'TRANSFER',r.resolution_id,a.payer_uuid,r.refund_before_bits,r.refund_after_bits,r.created_at
  FROM npc_transfer_resolutions r JOIN npc_transfer_attempts a ON a.attempt_id=r.attempt_id
  WHERE r.kind='REFUND' AND NOT EXISTS (SELECT 1 FROM npc_refund_claims k
    WHERE k.source='TRANSFER' AND k.resolution_id=r.resolution_id);
INSERT INTO npc_refund_claims(source,resolution_id,payer_id,refund_before_bits,refund_after_bits,recorded_at)
  SELECT 'COMMERCE',r.resolution_id,r.payer_id,r.refund_before_bits,r.refund_after_bits,r.recorded_at
  FROM npc_commerce_payment_resolutions r
  WHERE r.decision='VERIFIED_REFUNDED_DEBIT' AND NOT EXISTS (SELECT 1 FROM npc_refund_claims k
    WHERE k.source='COMMERCE' AND k.resolution_id=r.resolution_id);
