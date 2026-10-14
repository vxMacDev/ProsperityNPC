-- Existing receipt fingerprints and V1-V5 resources are immutable.
-- New columns are nullable for old actions. Each DDL step is repeatable after a partial migration.
ALTER TABLE npc_purchase_intents
  ADD COLUMN IF NOT EXISTS repair_incident_id BIGINT NULL,
  ADD COLUMN IF NOT EXISTS repair_type VARCHAR(24) NULL,
  ADD COLUMN IF NOT EXISTS repair_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL;

-- Add the broader, stricter replacement BEFORE removing the old constraint. A partial
-- migration temporarily rejects REPAIR inserts instead of accepting malformed actions.
ALTER TABLE npc_purchase_intents
  ADD CONSTRAINT IF NOT EXISTS ck_purchase_shape_v6 CHECK (
    (repair_incident_id IS NULL AND repair_type IS NULL AND repair_snapshot_sha256 IS NULL AND (
      (kind='UPGRADE' AND upgrade_type IN ('ADVERTISING','FACILITIES') AND upgrade_type IS NOT NULL
        AND target_level BETWEEN 1 AND 3 AND target_level IS NOT NULL AND tenant_id IS NULL)
      OR (kind='MAINTAIN' AND upgrade_type IS NULL AND target_level IS NULL AND tenant_id IS NULL)
      OR (kind='EVICT' AND upgrade_type IS NULL AND target_level IS NULL AND tenant_id IS NOT NULL)
    ))
    OR (kind='REPAIR' AND upgrade_type IS NULL AND target_level IS NULL AND tenant_id IS NULL
      AND repair_incident_id IS NOT NULL AND repair_incident_id > 0
      AND repair_type IS NOT NULL AND repair_type IN ('PESTS','PLUMBING','BREAK_IN')
      AND repair_snapshot_sha256 IS NOT NULL AND repair_snapshot_sha256 REGEXP '^[a-f0-9]{64}$')
  );

ALTER TABLE npc_purchase_intents DROP CONSTRAINT IF EXISTS ck_purchase_shape;
