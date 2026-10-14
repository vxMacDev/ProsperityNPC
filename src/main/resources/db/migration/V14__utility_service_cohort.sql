-- Bound the read-only owner's calendar cohort without trusting editable due_at as its selector.
ALTER TABLE npc_utility_bills
  ADD INDEX IF NOT EXISTS idx_utility_owner_day (owner_uuid,economy_day,property_id);
