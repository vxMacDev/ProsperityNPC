-- STANDARD's v1 payload stays byte-exact. PREMIUM gets an explicit tier and
-- original evidence in the same atomic row as occupancy/quota consumption.
ALTER TABLE npc_housing_moves
  ADD COLUMN IF NOT EXISTS tier VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'STANDARD',
  ADD COLUMN IF NOT EXISTS premium_evidence MEDIUMBLOB NULL,
  ADD COLUMN IF NOT EXISTS premium_evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN IF NOT EXISTS origin_property_id BINARY(16) NULL,
  ADD COLUMN IF NOT EXISTS origin_owner_uuid BINARY(16) NULL,
  ADD COLUMN IF NOT EXISTS origin_ownership_epoch INT NULL,
  ADD COLUMN IF NOT EXISTS origin_property_revision BIGINT NULL,
  ADD COLUMN IF NOT EXISTS origin_slot INT NULL,
  ADD COLUMN IF NOT EXISTS origin_admission_id BINARY(16) NULL;

ALTER TABLE npc_housing_moves
  ADD CONSTRAINT IF NOT EXISTS ck_housing_premium_evidence CHECK (
    (tier='STANDARD' AND premium_evidence IS NULL AND premium_evidence_sha256 IS NULL)
    OR (tier='PREMIUM' AND premium_evidence IS NOT NULL AND OCTET_LENGTH(premium_evidence) BETWEEN 1 AND 5242880
        AND premium_evidence_sha256 IS NOT NULL AND BINARY premium_evidence_sha256=BINARY SHA2(premium_evidence,256))
  );

ALTER TABLE npc_housing_moves
  ADD CONSTRAINT IF NOT EXISTS ck_housing_relocation CHECK (
    (origin_property_id IS NULL AND origin_owner_uuid IS NULL AND origin_ownership_epoch IS NULL
      AND origin_property_revision IS NULL AND origin_slot IS NULL AND origin_admission_id IS NULL
      AND tenancy_revision_after=tenancy_revision_before+1)
    OR (tier='PREMIUM' AND origin_property_id IS NOT NULL AND origin_property_id<>property_id
      AND origin_owner_uuid IS NOT NULL AND origin_ownership_epoch IS NOT NULL AND origin_ownership_epoch>=0
      AND origin_property_revision IS NOT NULL AND origin_property_revision>=0
      AND origin_slot IS NOT NULL AND origin_slot BETWEEN 0 AND 3 AND origin_admission_id IS NOT NULL
      AND tenancy_revision_before>=0 AND tenancy_revision_after=tenancy_revision_before+2)
  );

-- Replace only after the more specific shape constraint exists; reruns remain safe.
ALTER TABLE npc_housing_moves DROP CONSTRAINT IF EXISTS ck_hmove_after;
