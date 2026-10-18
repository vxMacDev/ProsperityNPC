ALTER TABLE npc_incidents
  ADD COLUMN IF NOT EXISTS configured_repair_cents BIGINT NULL;

ALTER TABLE npc_incidents
  ADD CONSTRAINT IF NOT EXISTS ck_incident_price_v54 CHECK (
    configured_repair_cents IS NULL
    OR (incident_type IN ('PESTS','PLUMBING','BREAK_IN') AND configured_repair_cents IS NOT NULL
        AND configured_repair_cents BETWEEN 1 AND 100000000
        AND cash_repair_cents=configured_repair_cents AND configured_repair_cents<>CASE incident_type
          WHEN 'PESTS' THEN 2000 WHEN 'PLUMBING' THEN 3500 WHEN 'BREAK_IN' THEN 5000 ELSE -1 END)
  );

ALTER TABLE npc_purchase_intents
  ADD CONSTRAINT IF NOT EXISTS ck_purchase_configured_price_v54 CHECK (
    configured_price_cents IS NULL
    OR (kind IN ('MAINTAIN','UPGRADE','EVICT','INSURANCE','REPAIR') AND configured_price_cents > 0
      AND configured_price_cents < 100000000000 AND configured_price_cents=quoted_amount_cents
      AND (kind<>'EVICT' OR (evict_tenancy_revision IS NOT NULL AND evict_slot IS NOT NULL))
      AND (kind<>'REPAIR' OR (configured_price_cents<=100000000 AND configured_price_cents<>CASE repair_type
        WHEN 'PESTS' THEN 2000 WHEN 'PLUMBING' THEN 3500 WHEN 'BREAK_IN' THEN 5000 ELSE -1 END)))
  );

ALTER TABLE npc_purchase_intents DROP CONSTRAINT IF EXISTS ck_purchase_configured_price_v51;
