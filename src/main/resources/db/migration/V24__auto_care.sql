-- Consent is explicit and incarnation-bound. No defaults enroll an owner or move money.
CREATE TABLE IF NOT EXISTS npc_auto_care_consents (
  property_id BINARY(16) NOT NULL,
  ownership_epoch INT NOT NULL,
  owner_uuid BINARY(16) NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  daily_ceiling_cents BIGINT NOT NULL DEFAULT 0,
  action_mask TINYINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (property_id,ownership_epoch),
  KEY idx_auto_care_enabled (enabled,property_id),
  CONSTRAINT fk_auto_care_property FOREIGN KEY (property_id) REFERENCES npc_properties(property_id) ON DELETE RESTRICT,
  CONSTRAINT ck_auto_care_consent CHECK (ownership_epoch>=0 AND revision>=0 AND enabled IN (0,1)
    AND daily_ceiling_cents>=0 AND action_mask BETWEEN 0 AND 7
    AND (enabled=FALSE OR (daily_ceiling_cents>0 AND action_mask>0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- These amounts are consent-budget reservations, NOT balances or economic events.
-- A day/action slot survives declines and disablement; replay never starts another payment.
CREATE TABLE IF NOT EXISTS npc_auto_care_claims (
  claim_id BINARY(16) NOT NULL,
  property_id BINARY(16) NOT NULL,
  owner_uuid BINARY(16) NOT NULL,
  ownership_epoch INT NOT NULL,
  consent_revision BIGINT NOT NULL,
  economy_day DATE NOT NULL,
  action VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  incident_id BIGINT NULL,
  quoted_cents BIGINT NOT NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'RESERVED',
  event_id BIGINT NULL,
  outcome_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (claim_id),
  UNIQUE KEY uq_auto_care_action (property_id,economy_day,action),
  UNIQUE KEY uq_auto_care_event (event_id),
  KEY idx_auto_care_budget (property_id,ownership_epoch,economy_day),
  KEY idx_auto_care_pending (state,claim_id),
  CONSTRAINT fk_auto_care_consent FOREIGN KEY (property_id,ownership_epoch)
    REFERENCES npc_auto_care_consents(property_id,ownership_epoch) ON DELETE RESTRICT,
  CONSTRAINT fk_auto_care_event FOREIGN KEY (event_id) REFERENCES npc_economy_events(event_id) ON DELETE RESTRICT,
  CONSTRAINT fk_auto_care_incident FOREIGN KEY (incident_id) REFERENCES npc_incidents(incident_id) ON DELETE RESTRICT,
  CONSTRAINT ck_auto_care_claim_epoch CHECK (ownership_epoch>=0 AND consent_revision>=0),
  CONSTRAINT ck_auto_care_claim_action CHECK (
    (action='UTILITY' AND incident_id IS NULL AND quoted_cents BETWEEN 100 AND 700)
    OR (action='MAINTAIN' AND incident_id IS NULL AND quoted_cents=1000)
    OR (action='CASH_REPAIR' AND incident_id IS NOT NULL AND incident_id>0 AND quoted_cents IN (2000,3500,5000))),
  CONSTRAINT ck_auto_care_claim_state CHECK (
    (state='RESERVED' AND event_id IS NULL AND outcome_code IS NULL)
    OR (state='BOUND' AND event_id IS NOT NULL AND outcome_code IS NULL)
    OR (state='SPENT' AND event_id IS NOT NULL AND outcome_code='PAYMENT_CONFIRMED' AND outcome_code IS NOT NULL)
    OR (state='DECLINED' AND event_id IS NOT NULL AND outcome_code='NO_MOVEMENT_CONFIRMED' AND outcome_code IS NOT NULL)
    OR (state='HELD' AND outcome_code IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
