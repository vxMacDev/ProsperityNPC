ALTER TABLE npc_item_repair_collections
  ADD COLUMN IF NOT EXISTS configured_item_repair_cents BIGINT NULL;

ALTER TABLE npc_item_repair_collections
  ADD CONSTRAINT IF NOT EXISTS ck_item_repair_price_v55 CHECK (
    configured_item_repair_cents IS NULL
    OR (incident_type IN ('PESTS','PLUMBING','BREAK_IN') AND configured_item_repair_cents BETWEEN 1 AND 100000000
      AND cash_cents=configured_item_repair_cents AND configured_item_repair_cents<>CASE incident_type
        WHEN 'PESTS' THEN 2000 WHEN 'PLUMBING' THEN 3500 WHEN 'BREAK_IN' THEN 5000 ELSE -1 END)
  );
