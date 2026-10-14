-- Payout retirement (economy.payout-retirement-enabled). One row per rent or appreciation payout whose
-- reservation can never be paid and was never attempted: its day has passed (EXPIRED) or the property's
-- owner or ownership epoch is no longer the one it was reserved for (OWNER_CHANGED), and no
-- npc_transfer_attempts row exists for the event. The same transaction moves the ledger event from
-- PENDING to FAILED with paid 0, the journal's existing no-movement end state, so the event can never be
-- debited (beginDebit needs PENDING). This row is the proof and the audit record: why, when, and which
-- binding was retired. An event with any transfer attempt is never written here. Only written while the
-- flag is on. Existing rows are never changed or deleted.
CREATE TABLE IF NOT EXISTS npc_payout_retirements (
  event_id BIGINT NOT NULL,
  kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  property_id BINARY(16) NOT NULL,
  economy_day CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  retired_owner BINARY(16) NOT NULL,
  retired_epoch INT NOT NULL,
  current_owner BINARY(16) NULL,
  current_epoch INT NOT NULL,
  retired_on CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  retired_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(event_id),
  KEY idx_payout_retirement_property(property_id,economy_day),
  CONSTRAINT fk_payout_retirement_event FOREIGN KEY(event_id) REFERENCES npc_economy_events(event_id),
  CONSTRAINT fk_payout_retirement_property FOREIGN KEY(property_id) REFERENCES npc_properties(property_id),
  CONSTRAINT ck_payout_retirement_kind CHECK (kind IN ('RENT','APPRECIATION')),
  CONSTRAINT ck_payout_retirement_reason CHECK (reason IN ('EXPIRED','OWNER_CHANGED')),
  CONSTRAINT ck_payout_retirement_day CHECK (economy_day REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'),
  CONSTRAINT ck_payout_retirement_on CHECK (retired_on REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$')
) ENGINE=InnoDB;
