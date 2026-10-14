-- Persist quiet/blocked days too: changing condition or restarting cannot reroll them.
CREATE TABLE IF NOT EXISTS npc_incident_rolls (
  property_id BINARY(16) NOT NULL,
  economy_day CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  ownership_epoch INT NOT NULL,
  owner_uuid BINARY(16) NOT NULL,
  outcome VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  incident_id BIGINT NULL,
  incident_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL,
  condition_before TINYINT NOT NULL,
  condition_after TINYINT NOT NULL,
  recorded_at DATETIME(3) NOT NULL,
  PRIMARY KEY (property_id,economy_day),
  CONSTRAINT fk_roll_property FOREIGN KEY (property_id) REFERENCES npc_properties(property_id) ON DELETE RESTRICT,
  CONSTRAINT fk_roll_incident FOREIGN KEY (incident_id) REFERENCES npc_incidents(incident_id) ON DELETE RESTRICT,
  CONSTRAINT ck_roll_epoch CHECK (ownership_epoch >= 0),
  CONSTRAINT ck_roll_condition CHECK (condition_before BETWEEN 0 AND 100 AND condition_after BETWEEN 0 AND 100),
  CONSTRAINT ck_roll_shape CHECK (
    (outcome='NONE' AND incident_id IS NULL AND incident_type IS NULL AND condition_before=condition_after)
    OR (outcome='BLOCKED_OPEN' AND incident_id IS NOT NULL AND incident_type IS NULL AND condition_before=condition_after)
    OR (outcome='INCIDENT' AND incident_id IS NOT NULL AND incident_type IS NOT NULL AND (
      (incident_type='PESTS' AND condition_after=GREATEST(0,condition_before-5))
      OR (incident_type='PLUMBING' AND condition_after=GREATEST(0,condition_before-8))
      OR (incident_type='BREAK_IN' AND condition_after=GREATEST(0,condition_before-12))
    ))
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
