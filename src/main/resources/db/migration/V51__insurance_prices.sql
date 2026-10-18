-- Keep the older guards until all replacements and the paid-price binding exist.
ALTER TABLE npc_property_insurance
  ADD COLUMN IF NOT EXISTS configured_price_cents BIGINT NULL;

ALTER TABLE npc_property_insurance
  ADD CONSTRAINT IF NOT EXISTS ck_insurance_price_v51 CHECK (
    capacity BETWEEN 1 AND 4 AND (
      (configured_price_cents IS NULL AND price_cents=1200+200*capacity)
      OR (configured_price_cents IS NOT NULL AND configured_price_cents > 0
        AND configured_price_cents < 100000000000 AND price_cents=configured_price_cents)
    )
  );

ALTER TABLE npc_purchase_intents
  ADD CONSTRAINT IF NOT EXISTS ck_purchase_configured_price_v51 CHECK (
    configured_price_cents IS NULL
    OR (kind IN ('MAINTAIN','UPGRADE','EVICT','INSURANCE') AND configured_price_cents > 0
      AND configured_price_cents < 100000000000 AND configured_price_cents=quoted_amount_cents
      AND (kind<>'EVICT' OR (evict_tenancy_revision IS NOT NULL AND evict_slot IS NOT NULL)))
  );

ALTER TABLE npc_purchase_intents
  ADD CONSTRAINT IF NOT EXISTS ck_purchase_shape_v51 CHECK (
    (insurance_capacity IS NULL AND repair_incident_id IS NULL AND repair_type IS NULL
      AND repair_snapshot_sha256 IS NULL AND (
        (kind='UPGRADE' AND upgrade_type IN ('ADVERTISING','FACILITIES') AND upgrade_type IS NOT NULL
          AND target_level BETWEEN 1 AND 3 AND target_level IS NOT NULL AND tenant_id IS NULL)
        OR (kind='MAINTAIN' AND upgrade_type IS NULL AND target_level IS NULL AND tenant_id IS NULL)
        OR (kind='EVICT' AND upgrade_type IS NULL AND target_level IS NULL AND tenant_id IS NOT NULL)
      ))
    OR (kind='REPAIR' AND insurance_capacity IS NULL AND upgrade_type IS NULL AND target_level IS NULL
      AND tenant_id IS NULL AND repair_incident_id IS NOT NULL AND repair_incident_id > 0
      AND repair_type IS NOT NULL AND repair_type IN ('PESTS','PLUMBING','BREAK_IN')
      AND repair_snapshot_sha256 IS NOT NULL AND repair_snapshot_sha256 REGEXP '^[a-f0-9]{64}$')
    OR (kind='INSURANCE' AND insurance_capacity IS NOT NULL AND insurance_capacity BETWEEN 1 AND 4
      AND quoted_amount_cents=COALESCE(configured_price_cents,1200+200*insurance_capacity)
      AND upgrade_type IS NULL AND target_level IS NULL AND tenant_id IS NULL
      AND repair_incident_id IS NULL AND repair_type IS NULL AND repair_snapshot_sha256 IS NULL
      AND evict_tenancy_revision IS NULL AND evict_slot IS NULL)
  );

CREATE UNIQUE INDEX IF NOT EXISTS uq_purchase_event_quote_v51
  ON npc_purchase_intents (event_id,quoted_amount_cents);

ALTER TABLE npc_property_insurance
  ADD CONSTRAINT fk_insurance_quoted_price_v51
  FOREIGN KEY IF NOT EXISTS fk_insurance_quoted_price_v51 (event_id,price_cents) REFERENCES npc_purchase_intents(event_id,quoted_amount_cents)
  ON DELETE RESTRICT;

ALTER TABLE npc_purchase_intents DROP CONSTRAINT IF EXISTS ck_purchase_configured_price_v50;
ALTER TABLE npc_purchase_intents DROP CONSTRAINT IF EXISTS ck_purchase_shape_v23;
ALTER TABLE npc_property_insurance DROP CONSTRAINT IF EXISTS ck_insurance_price;
