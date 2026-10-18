package com.prosperitysmp.npc.economy;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Rates for new utility bills. Each bill keeps its accepted tariff. */
public record UtilityPrices(long baseCents, long tenantCents, long upgradeLevelCents) {
    public static final UtilityPrices DEFAULT = new UtilityPrices(100, 75, 50);

    public UtilityPrices {
        if (baseCents <= 0 || baseCents > PurchasePrices.MAX_CENTS
                || tenantCents < 0 || tenantCents > PurchasePrices.MAX_CENTS
                || upgradeLevelCents < 0 || upgradeLevelCents > PurchasePrices.MAX_CENTS)
            throw new IllegalArgumentException("Invalid utility tariff");
        if (baseCents + 4 * tenantCents + 6 * upgradeLevelCents > PurchasePrices.MAX_CENTS)
            throw new IllegalArgumentException("Utility total exceeds the payment limit");
    }

    /** Check all possible new bills once when installing server prices. */
    public void requireExactTotals() {
        for (int tenants = 0; tenants <= 4; tenants++) {
            for (int upgrades = 0; upgrades <= 6; upgrades++) {
                long cents = cents(tenants, upgrades);
                if (TransferProof.toCents(cents / 100.0).orElse(-1) != cents)
                    throw new IllegalArgumentException("Utility totals must support exact cent payments");
            }
        }
    }

    public long cents(int occupiedTenants, int installedUpgradeLevels) {
        if (occupiedTenants < 0 || occupiedTenants > 4 || installedUpgradeLevels < 0 || installedUpgradeLevels > 6)
            throw new IllegalArgumentException("Invalid utility counts");
        return baseCents + tenantCents * occupiedTenants + upgradeLevelCents * installedUpgradeLevels;
    }

    public static UtilityPrices load(Map<String, ?> settings) {
        Objects.requireNonNull(settings);
        var keys = Set.of("base-cents", "per-tenant-cents", "per-upgrade-level-cents");
        if (settings.keySet().stream().anyMatch(key -> key == null || !keys.contains(key)))
            throw new IllegalArgumentException("utility-prices has an unknown setting");
        var prices = new UtilityPrices(rate(settings, "base-cents", DEFAULT.baseCents()),
                rate(settings, "per-tenant-cents", DEFAULT.tenantCents()),
                rate(settings, "per-upgrade-level-cents", DEFAULT.upgradeLevelCents()));
        prices.requireExactTotals();
        return prices;
    }

    private static long rate(Map<String, ?> settings, String key, long fallback) {
        if (!settings.containsKey(key)) return fallback;
        if (!(settings.get(key) instanceof String value) || !value.matches("0|[1-9][0-9]{0,10}"))
            throw new IllegalArgumentException("utility-prices." + key + " must be quoted integer cents");
        return Long.parseLong(value);
    }
}
