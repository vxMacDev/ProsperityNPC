-- ProsperityNPC schema V1 — Living Property + Tenant Reputation / Move-In Market.
-- Conventions: money is INTEGER CENTS; UUIDs are BINARY(16); timestamps are UTC
-- DATETIME(3); economy-day is an explicit 'YYYY-MM-DD' string (never inferred).

CREATE TABLE IF NOT EXISTS npc_properties (
  property_id            BINARY(16)   NOT NULL,
  world_uuid             BINARY(16)   NOT NULL,
  region_id              VARCHAR(128) NOT NULL,
  market_id              VARCHAR(64)  NOT NULL,
  class_key              VARCHAR(64)  NOT NULL,
  capacity               TINYINT      NOT NULL,
  reference_rent_cents   INT          NOT NULL,
  asking_rent_cents      INT          NOT NULL,
  owner_uuid             BINARY(16)   NULL,
  ownership_epoch        INT          NOT NULL DEFAULT 0,
  condition_score        TINYINT      NOT NULL DEFAULT 80,
  enabled                BOOLEAN      NOT NULL DEFAULT FALSE,
  last_arm_reconcile_at  DATETIME(3)  NULL,
  row_version            BIGINT       NOT NULL DEFAULT 0,
  created_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (property_id),
  UNIQUE KEY uq_world_region (world_uuid, region_id),
  CONSTRAINT ck_prop_capacity  CHECK (capacity BETWEEN 1 AND 4),
  CONSTRAINT ck_prop_condition CHECK (condition_score BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_property_ownership (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  property_id     BINARY(16)   NOT NULL,
  old_owner_uuid  BINARY(16)   NULL,
  new_owner_uuid  BINARY(16)   NULL,
  observed_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  source_snapshot VARCHAR(255) NULL,
  ownership_epoch INT          NOT NULL,
  PRIMARY KEY (id),
  KEY idx_own_property (property_id, observed_at),
  CONSTRAINT fk_own_property FOREIGN KEY (property_id) REFERENCES npc_properties (property_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_upgrades (
  id                   BIGINT      NOT NULL AUTO_INCREMENT,
  property_id          BINARY(16)  NOT NULL,
  upgrade_type         VARCHAR(32) NOT NULL,
  level                TINYINT     NOT NULL DEFAULT 0,
  purchase_cost_cents  INT         NOT NULL DEFAULT 0,
  installed_at         DATETIME(3) NULL,
  disabled_incident_id BIGINT      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_property_upgrade (property_id, upgrade_type),
  CONSTRAINT ck_upgrade_level CHECK (level BETWEEN 0 AND 3),
  CONSTRAINT fk_upg_property FOREIGN KEY (property_id) REFERENCES npc_properties (property_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_tenants (
  tenant_id               BINARY(16)  NOT NULL,
  display_name            VARCHAR(64) NOT NULL,
  property_id             BINARY(16)  NULL,
  slot                    TINYINT     NULL,
  tier                    VARCHAR(16) NOT NULL DEFAULT 'STANDARD',
  archetype               VARCHAR(32) NOT NULL,
  mood                    VARCHAR(16) NOT NULL DEFAULT 'NEUTRAL',
  satisfaction            TINYINT     NOT NULL DEFAULT 50,
  move_cooldown_until_day VARCHAR(10) NULL,
  schedule_template       VARCHAR(32) NOT NULL DEFAULT 'DEFAULT',
  state                   VARCHAR(16) NOT NULL DEFAULT 'IDLE',
  row_version             BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id),
  KEY idx_tenant_property (property_id),
  CONSTRAINT ck_tenant_satisfaction CHECK (satisfaction BETWEEN 0 AND 100),
  CONSTRAINT fk_tenant_property FOREIGN KEY (property_id) REFERENCES npc_properties (property_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_incidents (
  incident_id           BIGINT       NOT NULL AUTO_INCREMENT,
  property_id           BINARY(16)   NOT NULL,
  incident_type         VARCHAR(24)  NOT NULL,
  cash_repair_cents     INT          NOT NULL,
  item_repair_spec      VARCHAR(255) NOT NULL,
  condition_damage      TINYINT      NOT NULL,
  affected_upgrade_type VARCHAR(32)  NULL,
  economy_day           VARCHAR(10)  NOT NULL,
  created_at            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  due_at                DATETIME(3)  NOT NULL,
  resolved_at           DATETIME(3)  NULL,
  resolver_uuid         BINARY(16)   NULL,
  prevented             BOOLEAN      NOT NULL DEFAULT FALSE,
  PRIMARY KEY (incident_id),
  UNIQUE KEY uq_incident_property_day (property_id, economy_day),
  KEY idx_incident_open (property_id, resolved_at),
  CONSTRAINT fk_inc_property FOREIGN KEY (property_id) REFERENCES npc_properties (property_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_benefit_units (
  benefit_unit_id BINARY(16)   NOT NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  note            VARCHAR(255) NULL,
  PRIMARY KEY (benefit_unit_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_benefit_unit_members (
  member_uuid     BINARY(16)  NOT NULL,
  benefit_unit_id BINARY(16)  NOT NULL,
  source          VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
  added_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (member_uuid),
  KEY idx_bum_unit (benefit_unit_id),
  CONSTRAINT fk_bum_unit FOREIGN KEY (benefit_unit_id) REFERENCES npc_benefit_units (benefit_unit_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_operator_daily (
  id                BIGINT      NOT NULL AUTO_INCREMENT,
  benefit_unit_id   BINARY(16)  NOT NULL,
  economy_day       VARCHAR(10) NOT NULL,
  utilities_on_time BOOLEAN     NOT NULL DEFAULT FALSE,
  incidents_total   INT         NOT NULL DEFAULT 0,
  incidents_in_sla  INT         NOT NULL DEFAULT 0,
  tenants_total     INT         NOT NULL DEFAULT 0,
  tenants_retained  INT         NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uq_operdaily (benefit_unit_id, economy_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_operator_reputation (
  benefit_unit_id BINARY(16)  NOT NULL,
  operator_score  TINYINT     NOT NULL DEFAULT 50,
  observations    INT         NOT NULL DEFAULT 0,
  updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (benefit_unit_id),
  CONSTRAINT ck_oper_score CHECK (operator_score BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_economy_events (
  event_id               BIGINT       NOT NULL AUTO_INCREMENT,
  idempotency_key        VARCHAR(160) NOT NULL,
  requested_amount_cents BIGINT       NOT NULL,
  paid_amount_cents      BIGINT       NULL,
  source                 VARCHAR(48)  NOT NULL,
  direction              VARCHAR(24)  NOT NULL,
  property_id            BINARY(16)   NULL,
  counterparty_uuid      BINARY(16)   NULL,
  ownership_epoch        INT          NULL,
  benefit_unit_id        BINARY(16)   NULL,
  economy_day            VARCHAR(10)  NOT NULL,
  state                  VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  created_at             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  claimed_at             DATETIME(3)  NULL,
  finalized_at           DATETIME(3)  NULL,
  PRIMARY KEY (event_id),
  UNIQUE KEY uq_idempotency (idempotency_key),
  KEY idx_evt_state (state),
  KEY idx_evt_day (economy_day),
  KEY idx_evt_property (property_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_property_funding (
  property_id                   BINARY(16)  NOT NULL,
  one_time_seed_cents           INT         NOT NULL DEFAULT 0,
  successful_cash_sinks_cents   BIGINT      NOT NULL DEFAULT 0,
  successful_cash_payouts_cents BIGINT      NOT NULL DEFAULT 0,
  updated_at                    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (property_id),
  CONSTRAINT fk_fund_property FOREIGN KEY (property_id) REFERENCES npc_properties (property_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_nav_graphs (
  graph_id     BINARY(16)   NOT NULL,
  world_uuid   BINARY(16)   NOT NULL,
  region_id    VARCHAR(128) NOT NULL,
  palette      VARCHAR(64)  NOT NULL,
  version      INT          NOT NULL DEFAULT 1,
  status       VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  published_at DATETIME(3)  NULL,
  PRIMARY KEY (graph_id),
  KEY idx_graph_region (world_uuid, region_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_nav_nodes (
  node_id  BIGINT     NOT NULL AUTO_INCREMENT,
  graph_id BINARY(16) NOT NULL,
  x        INT        NOT NULL,
  y        INT        NOT NULL,
  z        INT        NOT NULL,
  PRIMARY KEY (node_id),
  KEY idx_node_graph (graph_id),
  CONSTRAINT fk_node_graph FOREIGN KEY (graph_id) REFERENCES npc_nav_graphs (graph_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_nav_edges (
  edge_id      BIGINT     NOT NULL AUTO_INCREMENT,
  graph_id     BINARY(16) NOT NULL,
  from_node_id BIGINT     NOT NULL,
  to_node_id   BIGINT     NOT NULL,
  cost         INT        NOT NULL DEFAULT 10,
  PRIMARY KEY (edge_id),
  KEY idx_edge_graph (graph_id),
  KEY idx_edge_from (from_node_id),
  CONSTRAINT fk_edge_graph FOREIGN KEY (graph_id) REFERENCES npc_nav_graphs (graph_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_nav_tags (
  node_id BIGINT      NOT NULL,
  tag     VARCHAR(32) NOT NULL,
  PRIMARY KEY (node_id, tag),
  CONSTRAINT fk_tag_node FOREIGN KEY (node_id) REFERENCES npc_nav_nodes (node_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS npc_audit_log (
  id     BIGINT      NOT NULL AUTO_INCREMENT,
  at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  actor  VARCHAR(64) NOT NULL,
  action VARCHAR(64) NOT NULL,
  detail TEXT        NULL,
  PRIMARY KEY (id),
  KEY idx_audit_at (at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
