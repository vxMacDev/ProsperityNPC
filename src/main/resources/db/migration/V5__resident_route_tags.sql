-- Canonical resident-specific tags fit without a private codec; retain all existing tags.
ALTER TABLE npc_nav_tags MODIFY COLUMN tag VARCHAR(64) NOT NULL;
