-- Vacation protection (housing.vacation-days). The first away/present decision per property, owner and
-- economy day, so a restart or reload later the same day keeps it: a skipped home stays skipped until the
-- next day's cycle. Only written while housing.vacation-days is above 0. Existing rows are never changed:
-- a later write for the same key leaves the stored decision as it is. Old days are pruned in small batches.
-- No foreign key: these rows are a per-day record of a decision, not part of a property's state.
CREATE TABLE IF NOT EXISTS npc_owner_away_decisions (
  economy_day CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  property_id BINARY(16)  NOT NULL,
  owner_uuid  BINARY(16)  NOT NULL,
  away        BOOLEAN     NOT NULL,
  decided_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (economy_day, property_id, owner_uuid),
  CONSTRAINT ck_owner_away_day CHECK (economy_day REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$')
) ENGINE=InnoDB;
