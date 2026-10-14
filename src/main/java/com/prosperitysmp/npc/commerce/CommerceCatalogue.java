package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentProfile;
import java.util.List;
import java.util.Optional;

/** Original finite service catalogue. Costs include full material value; production tuning is opt-in. */
public final class CommerceCatalogue {
    public record Entry(String id, ItemCustody.Source source, String material, int quantity,
                        RelationshipMemory.Stage minimum, int delaySeconds, int serviceBasisPoints) {
        public long cost(long fullUnitCents) {
            return cost(fullUnitCents, 0);
        }

        /**
         * Partner rate (town.partnerships): feeDiscountPct (0-60) comes off the SERVICE FEE only. The material part
         * stays at the full verified price times quantity and the fee never drops below $1, so a partner always pays
         * at least the full material price plus $1. With 0 this is exactly the legacy price:
         * ceil(m * bps * 100 / 1,000,000) equals ceil(m * bps / 10,000).
         */
        public long cost(long fullUnitCents, int feeDiscountPct) {
            if (fullUnitCents <= 0) throw new IllegalArgumentException("A verified full price is required");
            if (feeDiscountPct < 0 || feeDiscountPct > 60) throw new IllegalArgumentException("Fee discount must be 0..60");
            long materials = Math.multiplyExact(fullUnitCents, quantity);
            long fee = Math.multiplyExact(materials, serviceBasisPoints);
            // pct 0 keeps the legacy expression itself, so even its overflow bound is unchanged.
            long service = feeDiscountPct == 0 ? Math.floorDiv(Math.addExact(fee, 9999L), 10000L)
                    : Math.floorDiv(Math.addExact(Math.multiplyExact(fee, 100L - feeDiscountPct), 999_999L), 1_000_000L);
            return Math.addExact(materials, Math.max(100L, service));
        }
    }

    private CommerceCatalogue() { }

    public static List<Entry> entries(ResidentProfile.Occupation occupation) {
        String material = switch (occupation) {
            case RESTORER -> "STONE";
            case CARPENTER -> "OAK_LOG";
            case GARDENER -> "WHEAT";
            case COOK -> "BREAD";
            case SHOPKEEPER -> "IRON_INGOT";
            case COURIER -> "COAL";
        };
        int quantity = switch (occupation) { case RESTORER -> 32; case CARPENTER -> 8; default -> 4; };
        return List.of(
                new Entry("supplies-" + material.toLowerCase(java.util.Locale.ROOT), ItemCustody.Source.MERCHANT_GOODS,
                        material, quantity, RelationshipMemory.Stage.ACQUAINTANCE, 0, 500),
                new Entry("work-" + material.toLowerCase(java.util.Locale.ROOT), ItemCustody.Source.WORKER_OUTPUT,
                        material, quantity, RelationshipMemory.Stage.ACQUAINTANCE, 300, 3500),
                new Entry("specialist-" + material.toLowerCase(java.util.Locale.ROOT), ItemCustody.Source.WORKER_OUTPUT,
                        material, Math.min(64, quantity * 2), RelationshipMemory.Stage.TRUSTED, 300, 3500));
    }

    public static Optional<Entry> find(ResidentProfile.Occupation occupation, String id) {
        return entries(occupation).stream().filter(entry -> entry.id().equals(id)).findFirst();
    }
}
