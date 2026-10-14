-- Additive: structured benefit intents outlive player sessions and money acknowledgements.
-- V1/V2 remain immutable. No migration invents intents for historical generic events.
CREATE TABLE IF NOT EXISTS npc_purchase_intents (
  event_id              BIGINT       NOT NULL,
  request_id            BINARY(16)   NOT NULL,
  property_id           BINARY(16)   NOT NULL,
  payer_uuid            BINARY(16)   NOT NULL,
  ownership_epoch       INT          NOT NULL,
  kind                  VARCHAR(16)  NOT NULL,
  upgrade_type          VARCHAR(32)  NULL,
  target_level          TINYINT      NULL,
  tenant_id             BINARY(16)   NULL,
  economy_day           VARCHAR(10)  NOT NULL,
  quoted_amount_cents   BIGINT       NOT NULL,
  action_key            VARCHAR(96)  NOT NULL,
  state                 VARCHAR(24)  NOT NULL DEFAULT 'WAITING',
  outcome_code          VARCHAR(48)  NULL,
  created_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  completed_at          DATETIME(3)  NULL,
  PRIMARY KEY (event_id),
  UNIQUE KEY uq_purchase_request (request_id),
  KEY idx_purchase_action (property_id, ownership_epoch, action_key, event_id),
  KEY idx_purchase_ready (state, event_id),
  CONSTRAINT fk_purchase_event FOREIGN KEY (event_id) REFERENCES npc_economy_events(event_id) ON DELETE RESTRICT,
  CONSTRAINT ck_purchase_epoch CHECK (ownership_epoch >= 0),
  CONSTRAINT ck_purchase_amount CHECK (quoted_amount_cents > 0),
  CONSTRAINT ck_purchase_state CHECK (state IN ('WAITING','APPLIED','DECLINED','REVIEW_REQUIRED')),
  CONSTRAINT ck_purchase_shape CHECK (
    (kind='UPGRADE' AND upgrade_type IN ('ADVERTISING','FACILITIES') AND upgrade_type IS NOT NULL
      AND target_level BETWEEN 1 AND 3 AND target_level IS NOT NULL AND tenant_id IS NULL)
    OR (kind='MAINTAIN' AND upgrade_type IS NULL AND target_level IS NULL AND tenant_id IS NULL)
    OR (kind='EVICT' AND upgrade_type IS NULL AND target_level IS NULL AND tenant_id IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
