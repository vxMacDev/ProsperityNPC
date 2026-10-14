-- Independent chronological lookup cross-checks revision-based continuity without scanning all history.
ALTER TABLE npc_housing_moves
  ADD INDEX IF NOT EXISTS idx_hmove_resident_chronology (resident_id,economy_day,applied_at_millis,request_id);
