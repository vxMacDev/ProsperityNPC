-- Older experimental policies keep their hashes; missing terms are never guessed.
ALTER TABLE npc_charge_policies
  ADD COLUMN IF NOT EXISTS definition_json MEDIUMTEXT NULL, ALGORITHM=INSTANT;

ALTER TABLE npc_charge_policies
  ADD CONSTRAINT IF NOT EXISTS ck_charge_definition_json CHECK (definition_json IS NULL
    OR (JSON_VALID(definition_json)=1 AND OCTET_LENGTH(definition_json) BETWEEN 2 AND 1048576));

ALTER TABLE npc_economy_events
  ADD INDEX IF NOT EXISTS idx_evt_source_state_finalized (source,state,finalized_at),
  ALGORITHM=INPLACE, LOCK=NONE;
