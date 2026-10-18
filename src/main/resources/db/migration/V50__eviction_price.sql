-- Install the replacement before removing the older check.
ALTER TABLE npc_purchase_intents
  ADD CONSTRAINT IF NOT EXISTS ck_purchase_configured_price_v50 CHECK (
    configured_price_cents IS NULL
    OR (kind IN ('MAINTAIN','UPGRADE','EVICT') AND configured_price_cents > 0
      AND configured_price_cents < 100000000000
      AND configured_price_cents=quoted_amount_cents
      AND (kind<>'EVICT' OR (evict_tenancy_revision IS NOT NULL AND evict_slot IS NOT NULL)))
  );

ALTER TABLE npc_purchase_intents
  DROP CONSTRAINT IF EXISTS ck_purchase_configured_price;
