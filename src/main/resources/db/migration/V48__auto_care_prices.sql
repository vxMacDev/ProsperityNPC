-- Existing claims keep their fixed tariff; configured maintenance keeps its accepted quote.
ALTER TABLE npc_auto_care_claims
  ADD COLUMN IF NOT EXISTS configured_price_cents BIGINT NULL;

ALTER TABLE npc_auto_care_claims
  ADD CONSTRAINT IF NOT EXISTS ck_auto_care_claim_action_v48 CHECK (
    (configured_price_cents IS NULL AND (
      (action='UTILITY' AND incident_id IS NULL AND quoted_cents BETWEEN 100 AND 700)
      OR (action='MAINTAIN' AND incident_id IS NULL AND quoted_cents=1000)
      OR (action='CASH_REPAIR' AND incident_id IS NOT NULL AND incident_id>0 AND quoted_cents IN (2000,3500,5000))))
    OR (action='MAINTAIN' AND incident_id IS NULL AND configured_price_cents IS NOT NULL
      AND configured_price_cents=quoted_cents AND configured_price_cents>0
      AND configured_price_cents<100000000000 AND configured_price_cents<>1000)
  );

ALTER TABLE npc_auto_care_claims
  DROP CONSTRAINT IF EXISTS ck_auto_care_claim_action;
