-- One transaction serializes all NPC rent reservations, including lifetime funding.
CREATE TABLE IF NOT EXISTS npc_rent_mutex (
  singleton TINYINT NOT NULL PRIMARY KEY,
  CONSTRAINT ck_rent_singleton CHECK (singleton=1)
) ENGINE=InnoDB;
INSERT IGNORE INTO npc_rent_mutex(singleton) VALUES (1);

CREATE TABLE IF NOT EXISTS npc_rent_reservations (
  property_id BINARY(16) NOT NULL,
  economy_day CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_id BIGINT NOT NULL,
  utility_event_id BIGINT NOT NULL,
  PRIMARY KEY(property_id,economy_day),
  UNIQUE KEY uq_rent_event(event_id),
  CONSTRAINT fk_rent_property FOREIGN KEY(property_id) REFERENCES npc_properties(property_id),
  CONSTRAINT fk_rent_event FOREIGN KEY(event_id) REFERENCES npc_economy_events(event_id),
  CONSTRAINT fk_rent_utility FOREIGN KEY(utility_event_id) REFERENCES npc_utility_payments(event_id)
) ENGINE=InnoDB;

-- Moving a resident cannot generate another daily rent entitlement.
CREATE TABLE IF NOT EXISTS npc_rent_cohort (
  resident_id BINARY(16) NOT NULL,
  economy_day CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  event_id BIGINT NOT NULL,
  PRIMARY KEY(resident_id,economy_day),
  CONSTRAINT fk_rent_resident FOREIGN KEY(resident_id) REFERENCES npc_resident_profiles(resident_id),
  CONSTRAINT fk_rent_cohort_event FOREIGN KEY(event_id) REFERENCES npc_economy_events(event_id)
) ENGINE=InnoDB;
