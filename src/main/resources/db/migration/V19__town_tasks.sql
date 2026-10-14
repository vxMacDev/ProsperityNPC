-- Nonfinancial sidecar. No tenant, property, wallet, item, permission, or XP linkage.
-- This dedicated row is the first lock for every operation on a player's errands.
CREATE TABLE IF NOT EXISTS npc_town_task_players (
  player_id               BINARY(16) NOT NULL,
  last_accepted_day       VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  last_accepted_at_millis BIGINT NULL,
  PRIMARY KEY (player_id),
  CONSTRAINT ck_town_player_acceptance CHECK (
    (last_accepted_day IS NULL AND last_accepted_at_millis IS NULL)
    OR (last_accepted_day IS NOT NULL AND last_accepted_at_millis IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Every accepted definition field is an immutable snapshot. Terminal rows retain the daily slot.
CREATE TABLE IF NOT EXISTS npc_town_tasks (
  task_id                 BINARY(16) NOT NULL,
  player_id               BINARY(16) NOT NULL,
  definition_id           VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  title                   VARCHAR(80) NOT NULL,
  description             VARCHAR(512) NOT NULL,
  giver_id                BINARY(16) NOT NULL,
  recipient_id            BINARY(16) NOT NULL,
  minimum_stage           VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  duration_minutes        SMALLINT NOT NULL,
  accepted_at_millis      BIGINT NOT NULL,
  accepted_economy_day    VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at_millis       BIGINT NOT NULL,
  status                  VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  closed_at_millis        BIGINT NULL,
  completed_economy_day   VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  active_player_id        BINARY(16) GENERATED ALWAYS AS
    (CASE WHEN status='ACTIVE' THEN player_id ELSE NULL END) STORED,
  PRIMARY KEY (task_id),
  UNIQUE KEY uq_town_active_player (active_player_id),
  UNIQUE KEY uq_town_player_day (player_id, accepted_economy_day),
  KEY idx_town_player_history (player_id, accepted_at_millis, task_id),
  CONSTRAINT fk_town_player FOREIGN KEY (player_id) REFERENCES npc_town_task_players (player_id) ON DELETE RESTRICT,
  CONSTRAINT fk_town_giver FOREIGN KEY (giver_id) REFERENCES npc_resident_profiles (resident_id) ON DELETE RESTRICT,
  CONSTRAINT fk_town_recipient FOREIGN KEY (recipient_id) REFERENCES npc_resident_profiles (resident_id) ON DELETE RESTRICT,
  CONSTRAINT ck_town_definition CHECK (definition_id REGEXP BINARY '^[a-z0-9_-]{1,48}$'),
  CONSTRAINT ck_town_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 80),
  CONSTRAINT ck_town_description CHECK (CHAR_LENGTH(TRIM(description)) BETWEEN 1 AND 512),
  CONSTRAINT ck_town_residents CHECK (giver_id <> recipient_id),
  CONSTRAINT ck_town_stage CHECK (minimum_stage IN ('STRANGER', 'ACQUAINTANCE', 'TRUSTED', 'ESTABLISHED')),
  CONSTRAINT ck_town_duration CHECK (duration_minutes BETWEEN 5 AND 1440),
  CONSTRAINT ck_town_expiry CHECK (expires_at_millis = accepted_at_millis + duration_minutes * 60000),
  CONSTRAINT ck_town_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
  CONSTRAINT ck_town_terminal CHECK (
    (status='ACTIVE' AND closed_at_millis IS NULL AND completed_economy_day IS NULL)
    OR (status='COMPLETED' AND closed_at_millis IS NOT NULL AND completed_economy_day IS NOT NULL
      AND closed_at_millis >= accepted_at_millis AND closed_at_millis < expires_at_millis)
    OR (status='CANCELLED' AND closed_at_millis IS NOT NULL AND completed_economy_day IS NULL
      AND closed_at_millis >= accepted_at_millis AND closed_at_millis < expires_at_millis)
    OR (status='EXPIRED' AND closed_at_millis IS NOT NULL AND completed_economy_day IS NULL
      AND closed_at_millis = expires_at_millis))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
