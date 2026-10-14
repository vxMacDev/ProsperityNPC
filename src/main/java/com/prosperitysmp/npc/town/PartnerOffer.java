package com.prosperitysmp.npc.town;

import java.util.Objects;
import java.util.UUID;

/**
 * One resident's partnership offer from config: the weekly materials and the perks. Perks never move money: a fee
 * discount lowers the service fee of that resident's orders (never the material part), and rank points only reorder
 * vacancies in daily matching while advertising rank is on.
 */
public record PartnerOffer(UUID residentId, String material, int quantity, int feeDiscountPct, int rankBonusPoints,
                           String pitch) {
    public static final int MAX_FEE_DISCOUNT = 60;
    public static final int MAX_RANK_POINTS = 4;
    public static final int MAX_PITCH = 160;

    public PartnerOffer {
        Objects.requireNonNull(residentId);
        if (material == null || !material.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid offer material");
        if (quantity < 1 || quantity > ContractDefinition.MAX_QUANTITY) throw new IllegalArgumentException("Offer quantity must be 1..256");
        if (feeDiscountPct < 0 || feeDiscountPct > MAX_FEE_DISCOUNT) throw new IllegalArgumentException("Fee discount must be 0..60");
        if (rankBonusPoints < 0 || rankBonusPoints > MAX_RANK_POINTS) throw new IllegalArgumentException("Rank points must be 0..4");
        ContractDefinition.requireText(pitch, MAX_PITCH, "pitch");
    }

    public PartnerPerks perks() { return new PartnerPerks(feeDiscountPct, rankBonusPoints); }
}
