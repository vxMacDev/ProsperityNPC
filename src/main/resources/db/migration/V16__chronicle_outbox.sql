-- Staged migration: not registered until the full outbox lifecycle is reviewed.
-- HELD is not delivery approval. Existing admissions are not backfilled by DDL.
CREATE INDEX IF NOT EXISTS idx_hmove_chronicle_scan ON npc_housing_moves(property_id,request_id);

CREATE TABLE IF NOT EXISTS npc_chronicle_outbox (
  request_id BINARY(16) NOT NULL,
  event_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  authorization_generation BINARY(16) NOT NULL,
  public_payload TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  captured_at DATETIME(3) NOT NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'HELD',
  PRIMARY KEY (request_id),
  UNIQUE KEY uq_chronicle_event (event_key),
  KEY idx_chronicle_state (state,request_id),
  CONSTRAINT fk_chronicle_move FOREIGN KEY (request_id) REFERENCES npc_housing_moves(request_id) ON DELETE RESTRICT,
  CONSTRAINT ck_chronicle_event CHECK (event_key REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT ck_chronicle_source CHECK (source_sha256 REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT ck_chronicle_payload_hash CHECK (payload_sha256 REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT ck_chronicle_payload CHECK (OCTET_LENGTH(public_payload) BETWEEN 1 AND 4096 AND JSON_VALID(public_payload)),
  CONSTRAINT ck_chronicle_state CHECK (state='HELD')
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS npc_chronicle_recovery_lock (
  singleton_id TINYINT PRIMARY KEY,
  CONSTRAINT ck_chronicle_recovery_singleton CHECK (singleton_id=1)
) ENGINE=InnoDB;
INSERT IGNORE INTO npc_chronicle_recovery_lock(singleton_id) VALUES (1);

-- Deliberately not deleted with source/draft rows: send history must survive their retirement.
CREATE TABLE IF NOT EXISTS npc_chronicle_send_intents (
  event_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  intent_id BINARY(16) NOT NULL UNIQUE,
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  generation BINARY(16) NOT NULL,
  webhook_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  channel_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(3) NOT NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  message_id VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
  CONSTRAINT ck_send_event CHECK (event_key REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT ck_send_payload CHECK (payload_sha256 REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT ck_send_webhook CHECK (webhook_id REGEXP '^[1-9][0-9]{0,19}$' AND CAST(webhook_id AS DECIMAL(20,0))<=18446744073709551615),
  CONSTRAINT ck_send_channel CHECK (channel_id REGEXP '^[1-9][0-9]{0,19}$' AND CAST(channel_id AS DECIMAL(20,0))<=18446744073709551615),
  CONSTRAINT ck_send_state CHECK (
    (state IN ('INTENT','UNCERTAIN') AND message_id IS NULL) OR
    (state='ACKNOWLEDGED' AND message_id IS NOT NULL AND message_id REGEXP '^[1-9][0-9]{0,19}$'
      AND CAST(message_id AS DECIMAL(20,0))<=18446744073709551615))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS npc_chronicle_progress (
  property_id BINARY(16) PRIMARY KEY,
  generation BINARY(16) NOT NULL,
  scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  cursor_after BINARY(16) NULL,
  cursor_through BINARY(16) NULL,
  last_commit_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  CONSTRAINT ck_chronicle_progress_revision CHECK (revision>=0),
  CONSTRAINT ck_chronicle_progress_scope CHECK (scope_sha256 REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT ck_chronicle_progress_cursor CHECK (
    (cursor_after IS NULL AND cursor_through IS NULL) OR
    (cursor_after IS NOT NULL AND cursor_through IS NOT NULL AND cursor_after<cursor_through)),
  CONSTRAINT ck_chronicle_progress_commit CHECK (
    (revision=0 AND last_commit_sha256 IS NULL AND cursor_after IS NULL AND cursor_through IS NULL) OR
    (revision>0 AND last_commit_sha256 IS NOT NULL AND last_commit_sha256 REGEXP '^[0-9a-f]{64}$'))
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS npc_chronicle_attempts (
  property_id BINARY(16) NOT NULL,
  request_id BINARY(16) NOT NULL,
  outcome VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ever_unresolved TINYINT NOT NULL,
  PRIMARY KEY(property_id,request_id),
  CONSTRAINT fk_chronicle_attempt_progress FOREIGN KEY(property_id) REFERENCES npc_chronicle_progress(property_id) ON DELETE RESTRICT,
  CONSTRAINT ck_chronicle_attempt_seen CHECK (ever_unresolved IN (0,1)),
  CONSTRAINT ck_chronicle_attempt_outcome CHECK (
    (outcome='UNRESOLVED' AND event_key IS NULL AND payload_sha256 IS NULL AND ever_unresolved=1) OR
    (outcome='STAGED' AND event_key IS NOT NULL AND payload_sha256 IS NOT NULL
      AND event_key REGEXP '^[0-9a-f]{64}$' AND payload_sha256 REGEXP '^[0-9a-f]{64}$'))
) ENGINE=InnoDB;
