-- Old bills keep v1 terms. New tariffs are stored with the bill.
ALTER TABLE npc_utility_bills
  ADD COLUMN IF NOT EXISTS configured_base_cents BIGINT NULL,
  ADD COLUMN IF NOT EXISTS configured_tenant_cents BIGINT NULL,
  ADD COLUMN IF NOT EXISTS configured_upgrade_level_cents BIGINT NULL;

ALTER TABLE npc_utility_bills
  ADD CONSTRAINT IF NOT EXISTS ck_utility_amount_v52 CHECK (
    (configured_base_cents IS NULL AND configured_tenant_cents IS NULL AND configured_upgrade_level_cents IS NULL
      AND amount_cents=100+75*occupied_tenants+50*installed_upgrade_levels)
    OR (configured_base_cents IS NOT NULL AND configured_tenant_cents IS NOT NULL AND configured_upgrade_level_cents IS NOT NULL
      AND configured_base_cents>0 AND configured_tenant_cents>=0 AND configured_upgrade_level_cents>=0
      AND configured_base_cents+4*configured_tenant_cents+6*configured_upgrade_level_cents<100000000000
      AND amount_cents=configured_base_cents+configured_tenant_cents*occupied_tenants
        +configured_upgrade_level_cents*installed_upgrade_levels)
  );

ALTER TABLE npc_utility_bills
  DROP CONSTRAINT IF EXISTS ck_utility_amount;
