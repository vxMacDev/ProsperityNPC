-- Delayed asking-rent changes (housing.rent-change-delay-days). Only written when that is above 0,
-- or when an immediate change replaces a change that was still pending. Existing rows are untouched.
-- asking_rent_cents itself only changes on the effective day, so matching and rent read the old
-- value until then. A property holds at most one pending change (the three columns below).

-- One row per announced change. from/to rent and the owner snapshot are the recorded inputs,
-- never backfilled. state moves PENDING -> APPLIED | REPLACED | CANCELLED exactly once.
CREATE TABLE IF NOT EXISTS npc_rent_changes (
  change_id       BIGINT       NOT NULL AUTO_INCREMENT,
  property_id     BINARY(16)   NOT NULL,
  owner_uuid      BINARY(16)   NOT NULL,
  ownership_epoch INT          NOT NULL,
  from_rent_cents INT          NOT NULL,
  to_rent_cents   INT          NOT NULL,
  requested_day   CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  effective_day   CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state           VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  resolved_at     DATETIME(3)  NULL,
  PRIMARY KEY (change_id),
  KEY idx_rent_change_property (property_id, change_id),
  CONSTRAINT fk_rent_change_property FOREIGN KEY (property_id) REFERENCES npc_properties (property_id) ON DELETE RESTRICT,
  CONSTRAINT ck_rent_change_state CHECK (state IN ('PENDING', 'APPLIED', 'REPLACED', 'CANCELLED')),
  CONSTRAINT ck_rent_change_resolved CHECK ((state = 'PENDING') = (resolved_at IS NULL)),
  CONSTRAINT ck_rent_change_cents CHECK (from_rent_cents > 0 AND to_rent_cents > 0 AND to_rent_cents <> from_rent_cents),
  CONSTRAINT ck_rent_change_epoch CHECK (ownership_epoch >= 0),
  CONSTRAINT ck_rent_change_days CHECK (requested_day REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
    AND effective_day REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' AND effective_day > requested_day)
) ENGINE=InnoDB;

-- One notice per resident who lived there when the change was announced. The primary key makes a
-- replay unable to add a second notice; tenancy_revision is the occupancy row it was read from.
CREATE TABLE IF NOT EXISTS npc_rent_change_notices (
  change_id        BIGINT     NOT NULL,
  resident_id      BINARY(16) NOT NULL,
  property_id      BINARY(16) NOT NULL,
  tenancy_revision BIGINT     NOT NULL,
  economy_day      CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (change_id, resident_id),
  KEY idx_rent_notice_resident (resident_id, change_id),
  CONSTRAINT fk_rent_notice_change FOREIGN KEY (change_id) REFERENCES npc_rent_changes (change_id) ON DELETE RESTRICT,
  CONSTRAINT fk_rent_notice_resident FOREIGN KEY (resident_id) REFERENCES npc_tenants (tenant_id) ON DELETE RESTRICT,
  CONSTRAINT fk_rent_notice_property FOREIGN KEY (property_id) REFERENCES npc_properties (property_id) ON DELETE RESTRICT,
  CONSTRAINT ck_rent_notice_revision CHECK (tenancy_revision >= 0),
  CONSTRAINT ck_rent_notice_day CHECK (economy_day REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$')
) ENGINE=InnoDB;

ALTER TABLE npc_properties ADD COLUMN IF NOT EXISTS pending_rent_change_id BIGINT NULL;
ALTER TABLE npc_properties ADD COLUMN IF NOT EXISTS pending_asking_rent_cents INT NULL;
ALTER TABLE npc_properties ADD COLUMN IF NOT EXISTS pending_rent_effective_day CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NULL;
ALTER TABLE npc_properties DROP CONSTRAINT IF EXISTS ck_prop_pending_rent;
ALTER TABLE npc_properties ADD CONSTRAINT ck_prop_pending_rent CHECK (
    (pending_rent_change_id IS NULL AND pending_asking_rent_cents IS NULL AND pending_rent_effective_day IS NULL)
    OR (pending_rent_change_id IS NOT NULL AND pending_asking_rent_cents > 0
        AND pending_rent_effective_day REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'));
