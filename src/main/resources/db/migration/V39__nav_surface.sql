-- Per-node surface (0 UNKNOWN legacy, 1 SIDEWALK, 2 ACCESS, 3 ROAD, 4 CROSSING), the road width a scan
-- measured beside each crossing node (NULL where none was recorded), and the effective palette hash a
-- graph was scanned with. Repeatable: safe to re-apply.
-- ALGORITHM=INSTANT changes only the table definition, never the rows, and fails outright if the
-- server cannot do it that way instead of quietly copying the table.
ALTER TABLE npc_nav_nodes  ADD COLUMN IF NOT EXISTS surface TINYINT NOT NULL DEFAULT 0,
                           ADD COLUMN IF NOT EXISTS road_width SMALLINT UNSIGNED NULL, ALGORITHM=INSTANT;
ALTER TABLE npc_nav_graphs ADD COLUMN IF NOT EXISTS palette_hash CHAR(64) NULL, ALGORITHM=INSTANT;
