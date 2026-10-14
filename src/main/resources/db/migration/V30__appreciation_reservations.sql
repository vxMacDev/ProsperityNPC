-- Cash appreciation: at most one reservation per property per economy day, reserved under the rent
-- mutex only after that property's own rent for the day is journal-proved PAID. Only written when
-- economy.appreciation-enabled is on. score_tenths and entitlement_cents are the recorded inputs
-- (rated score x10 and the formula amount before caps), never backfilled. Existing rows are untouched.
CREATE TABLE IF NOT EXISTS npc_appreciation_reservations (
  property_id BINARY(16) NOT NULL,
  economy_day CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_id BIGINT NOT NULL,
  rent_event_id BIGINT NOT NULL,
  score_tenths SMALLINT NOT NULL,
  entitlement_cents BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(property_id,economy_day),
  UNIQUE KEY uq_appreciation_event(event_id),
  UNIQUE KEY uq_appreciation_rent(rent_event_id),
  CONSTRAINT fk_appreciation_property FOREIGN KEY(property_id) REFERENCES npc_properties(property_id),
  CONSTRAINT fk_appreciation_event FOREIGN KEY(event_id) REFERENCES npc_economy_events(event_id),
  CONSTRAINT fk_appreciation_rent FOREIGN KEY(rent_event_id) REFERENCES npc_rent_reservations(event_id),
  CONSTRAINT ck_appreciation_score CHECK (score_tenths BETWEEN 0 AND 1000),
  CONSTRAINT ck_appreciation_entitlement CHECK (entitlement_cents > 0),
  CONSTRAINT ck_appreciation_day CHECK (economy_day REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$')
) ENGINE=InnoDB;
