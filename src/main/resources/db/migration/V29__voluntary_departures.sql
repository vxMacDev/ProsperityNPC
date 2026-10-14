-- Voluntary departures: a resident leaves a neglected home by their own choice. No purchase, no money.
-- Only written when housing.voluntary-departures-enabled is on. Existing rows are not touched.
ALTER TABLE npc_tenancy_departures DROP CONSTRAINT IF EXISTS ck_departure_cause;
ALTER TABLE npc_tenancy_departures ADD CONSTRAINT ck_departure_cause CHECK (
    (cause='DEPARTURE' AND purchase_event_id IS NULL) OR
    (cause='VOLUNTARY' AND purchase_event_id IS NULL) OR
    (cause='PAID_EVICTION' AND purchase_event_id IS NOT NULL AND purchase_event_id>0));

-- Daily decision receipt. The primary key allows at most one voluntary departure per property per
-- economy day; the server cap counts rows for the day under the rent mutex. leave_chance_bp and
-- score are the recorded inputs of the deterministic roll, never backfilled.
CREATE TABLE IF NOT EXISTS npc_voluntary_departures (
  economy_day CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  property_id BINARY(16) NOT NULL,
  resident_id BINARY(16) NOT NULL,
  occupancy_revision_before BIGINT NOT NULL,
  score TINYINT NOT NULL,
  leave_chance_bp SMALLINT NOT NULL,
  cooldown_until CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (economy_day,property_id),
  UNIQUE KEY uq_voluntary_departure (resident_id,occupancy_revision_before),
  CONSTRAINT fk_voluntary_departure FOREIGN KEY (resident_id,occupancy_revision_before)
    REFERENCES npc_tenancy_departures(resident_id,occupancy_revision_before) ON DELETE RESTRICT,
  CONSTRAINT fk_voluntary_property FOREIGN KEY (property_id) REFERENCES npc_properties(property_id) ON DELETE RESTRICT,
  CONSTRAINT ck_voluntary_score CHECK (score BETWEEN 0 AND 100),
  CONSTRAINT ck_voluntary_chance CHECK (leave_chance_bp BETWEEN 1 AND 10000),
  CONSTRAINT ck_voluntary_day CHECK (economy_day REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'),
  CONSTRAINT ck_voluntary_cooldown CHECK (cooldown_until>economy_day)
) ENGINE=InnoDB;
