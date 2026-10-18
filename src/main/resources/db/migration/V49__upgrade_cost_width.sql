-- Upgrade costs accumulate across all three levels.
ALTER TABLE npc_upgrades
  MODIFY COLUMN purchase_cost_cents BIGINT NOT NULL DEFAULT 0;
