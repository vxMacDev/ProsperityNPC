package com.prosperitysmp.npc.town;

/**
 * A partnership's perks: percent off the service fee (0-60) and match rank points (0-4). Neither moves money. The
 * effective perk of a running partnership is the lower of its start snapshot and the current config, so staff can
 * lower a perk at once and can never raise a running term.
 */
public record PartnerPerks(int feeDiscountPct, int rankBonusPoints) {
    public static final PartnerPerks NONE = new PartnerPerks(0, 0);

    public PartnerPerks {
        if (feeDiscountPct < 0 || feeDiscountPct > PartnerOffer.MAX_FEE_DISCOUNT
                || rankBonusPoints < 0 || rankBonusPoints > PartnerOffer.MAX_RANK_POINTS)
            throw new IllegalArgumentException("Invalid partner perks");
    }

    /** min(snapshot, config) per perk. A resident with no offer in config gives no perk. */
    public static PartnerPerks effective(PartnerPerks snapshot, PartnerPerks config) {
        if (snapshot == null || config == null) return NONE;
        return new PartnerPerks(Math.min(snapshot.feeDiscountPct, config.feeDiscountPct),
                Math.min(snapshot.rankBonusPoints, config.rankBonusPoints));
    }

    /** True when at least one perk does something on this server. The rank perk needs rankLive. */
    public boolean live(boolean rankLive) { return feeDiscountPct > 0 || rankBonusPoints > 0 && rankLive; }

    public boolean any() { return feeDiscountPct > 0 || rankBonusPoints > 0; }
}
