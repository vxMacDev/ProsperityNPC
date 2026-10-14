-- Legacy purchase fingerprints remain unchanged; only INSURANCE binds capacity.
ALTER TABLE npc_purchase_intents
  ADD COLUMN IF NOT EXISTS insurance_capacity INT NULL;

ALTER TABLE npc_purchase_intents
  ADD CONSTRAINT IF NOT EXISTS ck_purchase_shape_v23 CHECK (
    (insurance_capacity IS NULL AND repair_incident_id IS NULL AND repair_type IS NULL
      AND repair_snapshot_sha256 IS NULL AND (
        (kind='UPGRADE' AND upgrade_type IN ('ADVERTISING','FACILITIES') AND upgrade_type IS NOT NULL
          AND target_level BETWEEN 1 AND 3 AND target_level IS NOT NULL AND tenant_id IS NULL)
        OR (kind='MAINTAIN' AND upgrade_type IS NULL AND target_level IS NULL AND tenant_id IS NULL)
        OR (kind='EVICT' AND upgrade_type IS NULL AND target_level IS NULL AND tenant_id IS NOT NULL)
      ))
    OR (kind='REPAIR' AND insurance_capacity IS NULL AND upgrade_type IS NULL AND target_level IS NULL
      AND tenant_id IS NULL AND repair_incident_id IS NOT NULL AND repair_incident_id > 0
      AND repair_type IS NOT NULL AND repair_type IN ('PESTS','PLUMBING','BREAK_IN')
      AND repair_snapshot_sha256 IS NOT NULL AND repair_snapshot_sha256 REGEXP '^[a-f0-9]{64}$')
    OR (kind='INSURANCE' AND insurance_capacity IS NOT NULL AND insurance_capacity BETWEEN 1 AND 4
      AND quoted_amount_cents=1200+200*insurance_capacity
      AND upgrade_type IS NULL AND target_level IS NULL AND tenant_id IS NULL
      AND repair_incident_id IS NULL AND repair_type IS NULL AND repair_snapshot_sha256 IS NULL
      AND evict_tenancy_revision IS NULL AND evict_slot IS NULL)
  );
ALTER TABLE npc_purchase_intents DROP CONSTRAINT IF EXISTS ck_purchase_shape_v6;

ALTER TABLE npc_incident_rolls
  ADD CONSTRAINT IF NOT EXISTS ck_roll_shape_v23 CHECK (
    (outcome='NONE' AND incident_id IS NULL AND incident_type IS NULL AND condition_before=condition_after)
    OR (outcome='BLOCKED_OPEN' AND incident_id IS NOT NULL AND incident_type IS NULL AND condition_before=condition_after)
    OR (outcome='PREVENTED' AND incident_id IS NOT NULL AND incident_type IN ('PESTS','PLUMBING','BREAK_IN')
      AND incident_type IS NOT NULL AND condition_before=condition_after)
    OR (outcome='INCIDENT' AND incident_id IS NOT NULL AND incident_type IS NOT NULL AND (
      (incident_type='PESTS' AND condition_after=GREATEST(0,condition_before-5))
      OR (incident_type='PLUMBING' AND condition_after=GREATEST(0,condition_before-8))
      OR (incident_type='BREAK_IN' AND condition_after=GREATEST(0,condition_before-12))
    ))
  );
ALTER TABLE npc_incident_rolls DROP CONSTRAINT IF EXISTS ck_roll_shape;

-- Append-only terms: no refund, renewal, ownership transfer, or retrospective backfill.
CREATE TABLE IF NOT EXISTS npc_property_insurance (
  event_id BIGINT NOT NULL,
  property_id BINARY(16) NOT NULL,
  owner_uuid BINARY(16) NOT NULL,
  ownership_epoch INT NOT NULL,
  capacity INT NOT NULL,
  price_cents BIGINT NOT NULL,
  paid_at DATETIME(3) NOT NULL,
  applied_at DATETIME(3) NOT NULL,
  applied_day DATE NOT NULL,
  start_day DATE NOT NULL,
  end_day_exclusive DATE NOT NULL,
  PRIMARY KEY (event_id),
  KEY idx_insurance_property_term (property_id,end_day_exclusive,start_day),
  CONSTRAINT fk_insurance_purchase FOREIGN KEY (event_id) REFERENCES npc_purchase_intents(event_id) ON DELETE RESTRICT,
  CONSTRAINT fk_insurance_property FOREIGN KEY (property_id) REFERENCES npc_properties(property_id) ON DELETE RESTRICT,
  CONSTRAINT ck_insurance_epoch CHECK (ownership_epoch >= 0),
  CONSTRAINT ck_insurance_price CHECK (capacity BETWEEN 1 AND 4 AND price_cents=1200+200*capacity),
  CONSTRAINT ck_insurance_time CHECK (applied_at >= paid_at),
  CONSTRAINT ck_insurance_term CHECK (start_day=DATE_ADD(applied_day,INTERVAL 1 DAY)
    AND end_day_exclusive=DATE_ADD(start_day,INTERVAL 7 DAY))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One prevention consumes the entire term, even when days remain.
CREATE TABLE IF NOT EXISTS npc_insurance_preventions (
  event_id BIGINT NOT NULL,
  incident_id BIGINT NOT NULL,
  economy_day DATE NOT NULL,
  prevented_at DATETIME(3) NOT NULL,
  PRIMARY KEY (event_id),
  UNIQUE KEY uq_insurance_prevented_incident (incident_id),
  CONSTRAINT fk_prevention_insurance FOREIGN KEY (event_id) REFERENCES npc_property_insurance(event_id) ON DELETE RESTRICT,
  CONSTRAINT fk_prevention_incident FOREIGN KEY (incident_id) REFERENCES npc_incidents(incident_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
