-- One frozen invoice per home per economy day. Amounts never change after issue; the id is derived from the
-- home, owner, ownership epoch and day, so a replay finds the same invoice and never issues a second one.
CREATE TABLE IF NOT EXISTS npc_charge_invoices (
  invoice_id BINARY(16) NOT NULL,
  property_id BINARY(16) NOT NULL,
  economy_day CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  owner_uuid BINARY(16) NOT NULL,
  benefit_unit_id BINARY(16) NOT NULL,
  ownership_epoch INT NOT NULL,
  property_revision BIGINT NOT NULL,
  issued_at DATETIME(3) NOT NULL,
  payable_until_day CHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  total_cents BIGINT NOT NULL,
  state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ISSUED',
  payload_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (invoice_id),
  UNIQUE KEY uq_charge_invoice_day (property_id,economy_day),
  KEY idx_charge_invoice_unit_day (benefit_unit_id,economy_day,state),
  KEY idx_charge_invoice_day_state (economy_day,state),
  KEY idx_charge_invoice_state (state,payable_until_day),
  CONSTRAINT fk_charge_invoice_property FOREIGN KEY (property_id) REFERENCES npc_properties(property_id) ON DELETE RESTRICT,
  CONSTRAINT ck_charge_invoice CHECK (ownership_epoch>=0 AND property_revision>=0
    AND total_cents BETWEEN 1 AND 100000 AND payable_until_day>=economy_day
    AND state IN ('ISSUED','PAID','EXPIRED') AND payload_sha256 REGEXP BINARY '^[a-f0-9]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The frozen lines. The meter reading and every input are stored, so each amount can be worked out again.
CREATE TABLE IF NOT EXISTS npc_charge_invoice_lines (
  invoice_id BINARY(16) NOT NULL,
  line_no TINYINT NOT NULL,
  policy_id VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version INT NOT NULL,
  policy_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  meter_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  meter_units BIGINT NULL,
  meter_powered TINYINT NULL,
  meter_detail_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  inputs_json VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  raw_cents BIGINT NOT NULL,
  final_cents BIGINT NOT NULL,
  capped TINYINT NOT NULL,
  PRIMARY KEY (invoice_id,line_no),
  UNIQUE KEY uq_charge_line_policy (invoice_id,policy_id),
  CONSTRAINT fk_charge_line_invoice FOREIGN KEY (invoice_id) REFERENCES npc_charge_invoices(invoice_id) ON DELETE RESTRICT,
  CONSTRAINT fk_charge_line_policy FOREIGN KEY (policy_id,version) REFERENCES npc_charge_policies(policy_id,version)
    ON DELETE RESTRICT,
  CONSTRAINT ck_charge_line CHECK (line_no BETWEEN 1 AND 16 AND final_cents BETWEEN 1 AND 20000
    AND raw_cents>=final_cents AND capped IN (0,1)
    AND ((capped=0 AND final_cents=raw_cents) OR (capped=1 AND final_cents<raw_cents))
    AND (meter_units IS NULL OR meter_units BETWEEN 0 AND 1000000000)
    AND (meter_powered IS NULL OR meter_powered IN (0,1))
    AND (meter_detail_sha256 IS NULL OR meter_detail_sha256 REGEXP BINARY '^[a-f0-9]{64}$'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Mirrors npc_utility_payments: a new attempt only after a proved no-movement decline; ambiguity freezes.
CREATE TABLE IF NOT EXISTS npc_charge_payments (
  request_id BINARY(16) NOT NULL,
  invoice_id BINARY(16) NOT NULL,
  attempt_number INT NOT NULL,
  event_id BIGINT NOT NULL,
  state VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  outcome_code VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
  paid_at DATETIME(3) NULL,
  PRIMARY KEY (request_id),
  UNIQUE KEY uq_charge_payment_event (event_id),
  UNIQUE KEY uq_charge_payment_attempt (invoice_id,attempt_number),
  CONSTRAINT fk_charge_payment_invoice FOREIGN KEY (invoice_id) REFERENCES npc_charge_invoices(invoice_id) ON DELETE RESTRICT,
  CONSTRAINT fk_charge_payment_event FOREIGN KEY (event_id) REFERENCES npc_economy_events(event_id) ON DELETE RESTRICT,
  CONSTRAINT ck_charge_payment_attempt CHECK (attempt_number BETWEEN 1 AND 16),
  CONSTRAINT ck_charge_payment_state CHECK (
    (state='WAITING' AND paid_at IS NULL AND outcome_code IS NULL)
    OR (state='PAID' AND paid_at IS NOT NULL AND outcome_code IS NOT NULL AND outcome_code='TRANSFER_CONFIRMED')
    OR (state='DECLINED' AND paid_at IS NULL AND outcome_code IS NOT NULL AND outcome_code='PAYMENT_DECLINED')
    OR (state='REVIEW_REQUIRED' AND paid_at IS NULL AND outcome_code IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
