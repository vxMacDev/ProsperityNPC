-- Legacy rows keep their fixed formula. Configured quotes store their accepted price separately.
ALTER TABLE npc_purchase_intents
  ADD COLUMN IF NOT EXISTS configured_price_cents BIGINT NULL;

ALTER TABLE npc_purchase_intents
  ADD CONSTRAINT IF NOT EXISTS ck_purchase_configured_price CHECK (
    configured_price_cents IS NULL
    OR (kind IN ('MAINTAIN','UPGRADE') AND configured_price_cents > 0
      AND configured_price_cents < 100000000000
      AND configured_price_cents=quoted_amount_cents)
  );
