-- Jobs training authorizations only: no wallet, property journal, instant XP or permission grant.
CREATE TABLE IF NOT EXISTS npc_training_players (
  player_id BINARY(16) NOT NULL,
  last_accepted_day VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  last_accepted_at_millis BIGINT NULL,
  PRIMARY KEY (player_id),
  CONSTRAINT ck_training_guard CHECK (
    (last_accepted_day IS NULL AND last_accepted_at_millis IS NULL)
    OR (last_accepted_day IS NOT NULL AND last_accepted_at_millis IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Immutable sessions; unique player/day survives expiry, logout, restart and retries.
CREATE TABLE IF NOT EXISTS npc_training_grants (
  grant_id BINARY(16) NOT NULL,
  player_id BINARY(16) NOT NULL,
  trainer_id BINARY(16) NOT NULL,
  job_name VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  session_id BINARY(16) NOT NULL,
  accepted_at_millis BIGINT NOT NULL,
  economy_day VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expires_at_millis BIGINT NOT NULL,
  money_basis_points SMALLINT NOT NULL,
  xp_basis_points SMALLINT NOT NULL,
  PRIMARY KEY (grant_id),
  UNIQUE KEY uq_training_player_day (player_id, economy_day),
  UNIQUE KEY uq_training_session (session_id),
  KEY idx_training_current (player_id, accepted_at_millis, grant_id),
  CONSTRAINT fk_training_player FOREIGN KEY (player_id) REFERENCES npc_training_players (player_id) ON DELETE RESTRICT,
  CONSTRAINT fk_training_trainer FOREIGN KEY (trainer_id) REFERENCES npc_resident_profiles (resident_id) ON DELETE RESTRICT,
  CONSTRAINT ck_training_job CHECK (job_name REGEXP BINARY '^[a-z][a-z0-9_-]{0,47}$'),
  CONSTRAINT ck_training_duration CHECK (expires_at_millis > accepted_at_millis
    AND expires_at_millis <= accepted_at_millis + 1800000),
  CONSTRAINT ck_training_money CHECK (money_basis_points BETWEEN 0 AND 500),
  CONSTRAINT ck_training_xp CHECK (xp_basis_points BETWEEN 0 AND 1000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
