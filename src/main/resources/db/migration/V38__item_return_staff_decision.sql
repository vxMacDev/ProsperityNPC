-- Item repair returns: staff can close a return held because its stored items could not be read (outcome
-- PAYLOAD_UNREADABLE) after giving the items back by hand. The decision is RETURNED_BY_STAFF. It issues nothing again.
-- Only npc_item_custody_resolutions changes. Widened the V35 way: add the broader check first, then drop the old one.
-- A partial run only ever leaves both checks in place, which refuses RETURNED_BY_STAFF rather than accepting a bad
-- decision. No money moves. Every statement can run again after a partial migration.

ALTER TABLE npc_item_custody_resolutions
  ADD CONSTRAINT IF NOT EXISTS ck_item_resolution_decision_v38
    CHECK (decision IN ('VERIFIED_DELIVERED','VERIFIED_NOT_DELIVERED','RETURNED_BY_STAFF'));

ALTER TABLE npc_item_custody_resolutions DROP CONSTRAINT IF EXISTS ck_item_resolution_decision;
