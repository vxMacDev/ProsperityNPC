-- Immutable billing inputs: retries and owner changes cannot create a second daily bill.
CREATE TABLE IF NOT EXISTS npc_utility_bills (
  property_id BINARY(16) NOT NULL,
  economy_day CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner_uuid BINARY(16) NOT NULL,
  ownership_epoch INT NOT NULL,
  property_revision BIGINT NOT NULL,
  capacity TINYINT NOT NULL,
  occupied_tenants TINYINT NOT NULL,
  installed_upgrade_levels TINYINT NOT NULL,
  issued_at DATETIME(3) NOT NULL,
  period_start DATETIME(3) NOT NULL,
  due_at DATETIME(3) NOT NULL,
  clock_zone VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reset_hour TINYINT NOT NULL,
  amount_cents BIGINT NOT NULL,
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (property_id,economy_day),
  KEY idx_utility_owner_due (owner_uuid,due_at,property_id),
  CONSTRAINT fk_utility_property FOREIGN KEY (property_id) REFERENCES npc_properties(property_id) ON DELETE RESTRICT,
  CONSTRAINT ck_utility_epoch CHECK (ownership_epoch >= 0 AND property_revision >= 0),
  CONSTRAINT ck_utility_inputs CHECK (capacity BETWEEN 1 AND 4 AND occupied_tenants BETWEEN 0 AND capacity
    AND installed_upgrade_levels BETWEEN 0 AND 6 AND reset_hour BETWEEN 0 AND 23),
  CONSTRAINT ck_utility_amount CHECK (amount_cents=100+75*occupied_tenants+50*installed_upgrade_levels),
  CONSTRAINT ck_utility_time CHECK (period_start<=issued_at AND issued_at<due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- An explicit new request may retry only a proved no-movement decline. Ambiguity freezes.
CREATE TABLE IF NOT EXISTS npc_utility_payments (
  request_id BINARY(16) NOT NULL,
  property_id BINARY(16) NOT NULL,
  economy_day CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  attempt_number INT NOT NULL,
  event_id BIGINT NOT NULL,
  state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  outcome_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
  paid_at DATETIME(3) NULL,
  PRIMARY KEY (request_id),
  UNIQUE KEY uq_utility_event (event_id),
  UNIQUE KEY uq_utility_attempt (property_id,economy_day,attempt_number),
  CONSTRAINT fk_utility_bill FOREIGN KEY (property_id,economy_day)
    REFERENCES npc_utility_bills(property_id,economy_day) ON DELETE RESTRICT,
  CONSTRAINT fk_utility_event FOREIGN KEY (event_id) REFERENCES npc_economy_events(event_id) ON DELETE RESTRICT,
  CONSTRAINT ck_utility_attempt CHECK (attempt_number BETWEEN 1 AND 16),
  CONSTRAINT ck_utility_state CHECK (
    (state='WAITING' AND paid_at IS NULL AND outcome_code IS NULL)
    OR (state='PAID' AND paid_at IS NOT NULL AND outcome_code IS NOT NULL AND outcome_code='TRANSFER_CONFIRMED')
    OR (state='DECLINED' AND paid_at IS NULL AND outcome_code IS NOT NULL AND outcome_code='PAYMENT_DECLINED')
    OR (state='REVIEW_REQUIRED' AND paid_at IS NULL AND outcome_code IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE npc_economy_events
  ADD INDEX IF NOT EXISTS idx_evt_utility_scope (property_id,source,economy_day,event_id);
