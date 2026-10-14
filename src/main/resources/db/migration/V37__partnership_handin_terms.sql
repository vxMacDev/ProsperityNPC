-- Partnership hand-in terms. A partnership start or renewal row (kind PARTNERSHIP) already keeps the material and
-- quantity it asked for. It now also keeps the perks and the term it was confirmed for, so settlement can give the
-- lower of these and the current config, and hold for staff when the material changed or the quantity went up.
-- Only npc_town_contracts (added in V36) changes. Rows written before this keep NULL here and settle to a hold,
-- never a grant. No money moves. Every statement can run again after a partial migration.

ALTER TABLE npc_town_contracts ADD COLUMN IF NOT EXISTS partner_fee_discount_pct TINYINT NULL;

ALTER TABLE npc_town_contracts ADD COLUMN IF NOT EXISTS partner_rank_bonus_points TINYINT NULL;

ALTER TABLE npc_town_contracts ADD COLUMN IF NOT EXISTS partner_term_days TINYINT NULL;

-- All three or none, only on a PARTNERSHIP row, in the same ranges as an offer and a term. The IS NOT NULL tests
-- matter: without them a row with only some NULLs makes the check unknown, and an unknown check passes.
ALTER TABLE npc_town_contracts
  ADD CONSTRAINT IF NOT EXISTS ck_contract_partner_terms CHECK (
    (partner_fee_discount_pct IS NULL AND partner_rank_bonus_points IS NULL AND partner_term_days IS NULL)
    OR (kind='PARTNERSHIP' AND partner_fee_discount_pct IS NOT NULL AND partner_rank_bonus_points IS NOT NULL
      AND partner_term_days IS NOT NULL AND partner_fee_discount_pct BETWEEN 0 AND 60 AND partner_rank_bonus_points BETWEEN 0 AND 4
      AND (partner_fee_discount_pct>0 OR partner_rank_bonus_points>0) AND partner_term_days BETWEEN 1 AND 30));
