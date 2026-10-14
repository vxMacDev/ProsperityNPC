-- Linked accounts (linked-accounts.enabled). One row per pair of accounts that belong to the same person,
-- saved from Floodgate's own link state when a linked Bedrock player joins, or from the optional owner
-- lookup. The pair is stored ordered (account_low < account_high, byte order, as MariaDB compares
-- BINARY), so (a,b) and (b,a) are one row. Rows are never deleted: unlinking in Floodgate does not remove
-- a pair. Only a staff split ends it, and the split starts on the next economy day
-- (split_effective_day > first_seen_day). A pair is active for day D while split_effective_day is NULL or
-- after D. The STAFF source and the nullable java/bedrock columns are kept for a later staff add; this
-- release writes only Floodgate rows. npc_economy_events.benefit_unit_id is not changed by this feature.
CREATE TABLE IF NOT EXISTS npc_account_links (
  link_id BIGINT NOT NULL AUTO_INCREMENT,
  account_low BINARY(16) NOT NULL,
  account_high BINARY(16) NOT NULL,
  java_uuid BINARY(16) NULL,
  bedrock_uuid BINARY(16) NULL,
  source VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  first_seen_at DATETIME(3) NOT NULL,
  first_seen_day DATE NOT NULL,
  last_seen_at DATETIME(3) NOT NULL,
  added_by VARCHAR(64) NULL,
  note VARCHAR(160) NULL,
  split_effective_day DATE NULL,
  split_at DATETIME(3) NULL,
  split_by VARCHAR(64) NULL,
  split_reason VARCHAR(160) NULL,
  PRIMARY KEY (link_id),
  UNIQUE KEY uq_account_link_pair (account_low, account_high),
  KEY idx_account_link_high (account_high),
  CONSTRAINT ck_account_link_order CHECK (account_low < account_high),
  CONSTRAINT ck_account_link_source CHECK (source IN ('FLOODGATE_LOGIN','FLOODGATE_LOOKUP','STAFF')),
  CONSTRAINT ck_account_link_floodgate CHECK (source='STAFF' OR (java_uuid IS NOT NULL AND bedrock_uuid IS NOT NULL AND java_uuid<>bedrock_uuid)),
  CONSTRAINT ck_account_link_split CHECK ((split_effective_day IS NULL AND split_at IS NULL AND split_by IS NULL)
    OR (split_effective_day IS NOT NULL AND split_at IS NOT NULL AND split_by IS NOT NULL AND split_effective_day > first_seen_day))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- A row means that economy day's rent and appreciation caps are merged across linked accounts. It is
-- written only while linked-accounts.enabled is on, inside the rent or appreciation transaction under
-- npc_rent_mutex, and never deleted. No row for a day means caps per account, the old behavior. So
-- turning the flag off cannot reopen headroom on a day that already started merged.
CREATE TABLE IF NOT EXISTS npc_linked_account_days (
  economy_day DATE NOT NULL,
  pinned_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (economy_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
