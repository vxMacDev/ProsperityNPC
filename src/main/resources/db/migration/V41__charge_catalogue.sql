-- Configured bills and taxes (dev.65). Append-only record of every policy version a server has used, so a
-- version can never be reused for a different definition and every invoice line names an exact definition.
CREATE TABLE IF NOT EXISTS npc_charge_policies (
  policy_id VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version INT NOT NULL,
  definition_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  effective_day CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  retired_day CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (policy_id,version),
  CONSTRAINT ck_charge_policy CHECK (version>=1 AND definition_sha256 REGEXP BINARY '^[a-f0-9]{64}$'
    AND policy_id REGEXP BINARY '^[a-z][a-z0-9-]{0,23}$'
    AND (retired_day IS NULL OR retired_day>=effective_day))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Paid bills are kept apart from the sinks that fund rent: this total is audit only and no headroom reads it.
ALTER TABLE npc_property_funding
  ADD COLUMN IF NOT EXISTS successful_cash_unfunded_cents BIGINT NOT NULL DEFAULT 0;

ALTER TABLE npc_property_funding
  ADD CONSTRAINT IF NOT EXISTS ck_fund_unfunded CHECK (successful_cash_unfunded_cents>=0);
