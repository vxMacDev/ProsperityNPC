-- The property backend each home was enrolled under (property.backend). A region id from one home plugin never
-- resolves in another, so at startup homes enrolled under a different backend are left out of every payment and
-- move while another backend is configured. Existing rows were all enrolled under ARM. Repeatable: safe to re-apply.
-- ALGORITHM=INSTANT changes only the table definition, never the rows, and fails outright if the
-- server cannot do it that way instead of quietly copying the table.
ALTER TABLE npc_properties ADD COLUMN IF NOT EXISTS backend_id VARCHAR(32) NOT NULL DEFAULT 'arm', ALGORITHM=INSTANT;
