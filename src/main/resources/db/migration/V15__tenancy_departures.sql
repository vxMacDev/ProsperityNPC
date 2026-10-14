-- Actual departures only; never backfill timing from current vacancy/occupancy.
CREATE TABLE IF NOT EXISTS npc_tenancy_departures (
  resident_id BINARY(16) NOT NULL,
  occupancy_revision_before BIGINT NOT NULL,
  occupancy_revision_after BIGINT NOT NULL,
  property_id BINARY(16) NOT NULL,
  owner_at_departure BINARY(16) NULL,
  ownership_epoch INT NOT NULL,
  slot TINYINT NOT NULL,
  departed_at DATETIME(3) NOT NULL,
  cause VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  purchase_event_id BIGINT NULL,
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (resident_id,occupancy_revision_before),
  UNIQUE KEY uq_departure_purchase (purchase_event_id),
  KEY idx_departure_property_time (property_id,departed_at,resident_id),
  CONSTRAINT fk_departure_resident FOREIGN KEY (resident_id) REFERENCES npc_tenants(tenant_id) ON DELETE RESTRICT,
  CONSTRAINT fk_departure_property FOREIGN KEY (property_id) REFERENCES npc_properties(property_id) ON DELETE RESTRICT,
  CONSTRAINT fk_departure_purchase FOREIGN KEY (purchase_event_id) REFERENCES npc_economy_events(event_id) ON DELETE RESTRICT,
  CONSTRAINT ck_departure_before CHECK (occupancy_revision_before BETWEEN 0 AND 9223372036854775806),
  CONSTRAINT ck_departure_after CHECK (occupancy_revision_after=occupancy_revision_before+1),
  CONSTRAINT ck_departure_epoch CHECK (ownership_epoch>=0),
  CONSTRAINT ck_departure_slot CHECK (slot BETWEEN 0 AND 3),
  CONSTRAINT ck_departure_cause CHECK (
    (cause='DEPARTURE' AND purchase_event_id IS NULL) OR
    (cause='PAID_EVICTION' AND purchase_event_id IS NOT NULL AND purchase_event_id>0)),
  CONSTRAINT ck_departure_hash CHECK (payload_sha256 REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB;
