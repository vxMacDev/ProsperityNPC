-- Historical unbound EVICT rows remain readable, never silently assigned a current tenancy.
ALTER TABLE npc_purchase_intents
  ADD COLUMN IF NOT EXISTS evict_tenancy_revision BIGINT NULL,
  ADD COLUMN IF NOT EXISTS evict_slot TINYINT NULL;

ALTER TABLE npc_purchase_intents
  ADD CONSTRAINT IF NOT EXISTS ck_purchase_eviction_v8 CHECK (
    (evict_tenancy_revision IS NULL AND evict_slot IS NULL)
    OR (kind='EVICT' AND evict_tenancy_revision IS NOT NULL
      AND evict_tenancy_revision BETWEEN 0 AND 9223372036854775806
      AND evict_slot IS NOT NULL AND evict_slot BETWEEN 0 AND 3)
  );

CREATE INDEX IF NOT EXISTS idx_purchase_eviction_history
  ON npc_purchase_intents (property_id,tenant_id,kind,state,event_id);
