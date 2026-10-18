package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.formulas.IncidentType;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Prices for new incidents. Existing incidents keep their accepted amount. */
public record RepairPrices(long pestsCents, long plumbingCents, long breakInCents) {
    public static final RepairPrices DEFAULT = new RepairPrices(IncidentType.PESTS.cashRepairCents(),
            IncidentType.PLUMBING.cashRepairCents(), IncidentType.BREAK_IN.cashRepairCents());

    public RepairPrices {
        requirePrice("pests-cents", pestsCents);
        requirePrice("plumbing-cents", plumbingCents);
        requirePrice("break-in-cents", breakInCents);
    }

    public long cents(IncidentType type) {
        return switch (Objects.requireNonNull(type)) {
            case PESTS -> pestsCents;
            case PLUMBING -> plumbingCents;
            case BREAK_IN -> breakInCents;
        };
    }

    public static RepairPrices load(Map<String, ?> settings) {
        Objects.requireNonNull(settings);
        var keys = Set.of("pests-cents", "plumbing-cents", "break-in-cents");
        if (settings.keySet().stream().anyMatch(key -> key == null || !keys.contains(key)))
            throw new IllegalArgumentException("repair-prices has an unknown setting");
        return new RepairPrices(price(settings, "pests-cents", DEFAULT.pestsCents()),
                price(settings, "plumbing-cents", DEFAULT.plumbingCents()),
                price(settings, "break-in-cents", DEFAULT.breakInCents()));
    }

    private static long price(Map<String, ?> settings, String key, long fallback) {
        if (!settings.containsKey(key)) return fallback;
        if (!(settings.get(key) instanceof String value) || !value.matches("[1-9][0-9]{0,8}"))
            throw new IllegalArgumentException("repair-prices." + key + " must be quoted positive integer cents");
        return Long.parseLong(value);
    }

    private static void requirePrice(String key, long cents) {
        if (cents <= 0 || cents > ItemRepairRecipes.MAX_CASH_CENTS
                || TransferProof.toCents(cents / 100.0).orElse(-1) != cents)
            throw new IllegalArgumentException("repair-prices." + key + " must be from 1 to "
                    + ItemRepairRecipes.MAX_CASH_CENTS + " exact cents");
    }
}
