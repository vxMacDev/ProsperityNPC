-- Last economy day a resident's mood was updated. Makes the daily mood step idempotent.
-- NULL = never updated. Only written when housing.moods-enabled is on.
ALTER TABLE npc_tenants
  ADD COLUMN IF NOT EXISTS mood_day CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL;
