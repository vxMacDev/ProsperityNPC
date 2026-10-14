package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.market.HousingDecisionPlan;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * What any visitor may see about an enrolled property: half-stars (or unrated), rooms let and a condition band.
 * Deliberately carries no owner, tenant, money, receipt or service-record detail.
 */
public record PublicRating(OptionalInt halfStars, int occupied, int capacity, int condition) {
    public PublicRating {
        Objects.requireNonNull(halfStars);
        if (capacity < 1 || capacity > 4 || occupied < 0 || occupied > capacity || condition < 0 || condition > 100
                || halfStars.isPresent() && (halfStars.getAsInt() < 0 || halfStars.getAsInt() > 10))
            throw new IllegalArgumentException("Invalid public rating");
    }

    /** Property score 0-100 to half-star units 0-10 (score / 20 stars, nearest half, halves round up). */
    public static int halfStarUnits(double score) {
        if (!Double.isFinite(score)) throw new IllegalArgumentException("score");
        return (int) Math.max(0, Math.min(10, Math.floor(score / 10.0 + 0.5)));
    }

    /** Only a fully rated landlord shows stars; held, provisional or under-review ratings are Unrated. */
    public static PublicRating of(HousingDecisionPlan.HomeRank rank, int occupied, int capacity, int condition) {
        OptionalInt units = rank.status() == HousingDecisionPlan.RankStatus.RATED && rank.rankingScore().isPresent()
                ? OptionalInt.of(halfStarUnits(rank.rankingScore().getAsDouble())) : OptionalInt.empty();
        return new PublicRating(units, occupied, capacity, condition);
    }

    public static PublicRating from(OwnerDeskData data) {
        var rank = data.housing().map(h -> h.rating()).orElse(null);
        var units = rank == null ? OptionalInt.empty() : of(rank, 0, 1, 0).halfStars();
        return new PublicRating(units, data.residents().size(), data.property().capacity(),
                data.property().conditionScore());
    }

    public static String conditionBand(int condition) {
        return condition >= 80 ? "Excellent" : condition >= 60 ? "Good" : condition >= 40 ? "Fair" : "Poor";
    }

    public String stars() {
        if (halfStars.isEmpty()) return "Unrated";
        int units = halfStars.getAsInt();
        return "★".repeat(units / 2) + (units % 2 == 1 ? "½" : "") + "☆".repeat(5 - (units + 1) / 2)
                + " (" + (units / 2) + (units % 2 == 1 ? ".5" : "") + " of 5)";
    }

    public List<String> lines() { return lines(false); }

    /** Owner away (vacation protection) adds one plain line; nothing else changes. */
    public List<String> lines(boolean ownerAway) {
        var base = List.of("Property rating: " + stars(),
                occupied + " of " + capacity + " " + (capacity == 1 ? "room" : "rooms") + " let",
                "Condition: " + conditionBand(condition));
        if (!ownerAway) return base;
        var all = new java.util.ArrayList<>(base); all.add(OwnerAway.LABEL);
        return List.copyOf(all);
    }
}
