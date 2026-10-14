-- A repair-service fact is committed WITH the benefit after verified two-leg payment.
-- Never backfill this table from resolved_at, PAID, or legacy operator observations.
CREATE TABLE IF NOT EXISTS npc_incident_service_receipts (
  incident_id BIGINT NOT NULL,
  event_id BIGINT NOT NULL,
  property_id BINARY(16) NOT NULL,
  payer_uuid BINARY(16) NOT NULL,
  ownership_epoch INT NOT NULL,
  incident_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  cash_cents BIGINT NOT NULL,
  repair_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  incident_created_at DATETIME(3) NOT NULL,
  incident_due_at DATETIME(3) NOT NULL,
  repaired_at DATETIME(3) NOT NULL,
  PRIMARY KEY (incident_id),
  UNIQUE KEY uq_service_event (event_id),
  CONSTRAINT fk_service_incident FOREIGN KEY (incident_id) REFERENCES npc_incidents(incident_id) ON DELETE RESTRICT,
  CONSTRAINT fk_service_purchase FOREIGN KEY (event_id) REFERENCES npc_purchase_intents(event_id) ON DELETE RESTRICT,
  CONSTRAINT fk_service_property FOREIGN KEY (property_id) REFERENCES npc_properties(property_id) ON DELETE RESTRICT,
  CONSTRAINT ck_service_epoch CHECK (ownership_epoch >= 0),
  CONSTRAINT ck_service_cash CHECK (cash_cents > 0),
  CONSTRAINT ck_service_time CHECK (incident_due_at >= incident_created_at AND repaired_at >= incident_created_at),
  CONSTRAINT ck_service_kind CHECK (incident_type IN ('PESTS','PLUMBING','BREAK_IN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Owner at incident creation is immutable; current ownership must not rewrite history.
ALTER TABLE npc_incident_rolls
  ADD INDEX IF NOT EXISTS idx_roll_service_owner (owner_uuid,outcome,recorded_at,property_id);
