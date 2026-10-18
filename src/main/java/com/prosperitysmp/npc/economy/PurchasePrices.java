package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.formulas.PropertyFormulas;
import com.prosperitysmp.npc.formulas.UpgradeType;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Prices for new owner quotes. Receipts keep their own accepted price. */
public record PurchasePrices(long maintenanceCents, Map<UpgradeType, List<Long>> upgrades, long evictionCents, List<Long> insuranceCents) {
    public static final long MAX_CENTS = TransferProof.MAX_BALANCE_DOLLARS.movePointRight(2).longValueExact() - 1;
    public static final PurchasePrices DEFAULT = new PurchasePrices(PropertyFormulas.MAINTAIN_CENTS, Map.of(
            UpgradeType.ADVERTISING, List.of(10_000L, 25_000L, 50_000L),
            UpgradeType.FACILITIES, List.of(15_000L, 35_000L, 70_000L)));

    public PurchasePrices(long maintenanceCents, Map<UpgradeType, List<Long>> upgrades) {
        this(maintenanceCents, upgrades, PropertyFormulas.EVICTION_CENTS);
    }

    public PurchasePrices(long maintenanceCents, Map<UpgradeType, List<Long>> upgrades, long evictionCents) {
        this(maintenanceCents, upgrades, evictionCents, List.of(1400L, 1600L, 1800L, 2000L));
    }

    public PurchasePrices {
        requirePrice("maintenance-cents", maintenanceCents);
        requirePrice("eviction-cents", evictionCents);
        Objects.requireNonNull(insuranceCents);
        if (insuranceCents.size() != 4) throw new IllegalArgumentException("owner-prices.insurance-cents needs four capacity prices");
        for (Long price : insuranceCents) {
            if (price == null) throw new IllegalArgumentException("owner-prices.insurance-cents has no price");
            requirePrice("insurance-cents", price);
        }
        insuranceCents = List.copyOf(insuranceCents);
        Objects.requireNonNull(upgrades);
        if (upgrades.size() != UpgradeType.values().length)
            throw new IllegalArgumentException("owner-prices.upgrades must contain both upgrade types");
        var copy = new EnumMap<UpgradeType, List<Long>>(UpgradeType.class);
        for (UpgradeType type : UpgradeType.values()) {
            List<Long> levels = upgrades.get(type);
            if (levels == null || levels.size() != UpgradeType.MAX_LEVEL)
                throw new IllegalArgumentException("owner-prices.upgrades." + key(type) + " needs three level prices");
            for (Long price : levels) {
                if (price == null) throw new IllegalArgumentException("owner-prices.upgrades." + key(type) + " has no price");
                requirePrice("upgrades." + key(type), price);
            }
            copy.put(type, List.copyOf(levels));
        }
        upgrades = Map.copyOf(copy);
    }

    public long upgradeCents(UpgradeType type, int targetLevel) {
        Objects.requireNonNull(type);
        if (targetLevel < 1 || targetLevel > UpgradeType.MAX_LEVEL)
            throw new IllegalArgumentException("Upgrade level must be 1..3");
        return upgrades.get(type).get(targetLevel - 1);
    }

    public long insuranceCents(int capacity) {
        if (capacity < 1 || capacity > 4) throw new IllegalArgumentException("Insurance capacity must be 1..4");
        return insuranceCents.get(capacity - 1);
    }

    public PurchaseRequest quote(PurchaseRequest request) {
        Objects.requireNonNull(request);
        return switch (request.kind()) {
            case MAINTAIN -> request.withPriceCents(maintenanceCents);
            case EVICT -> request.withPriceCents(evictionCents);
            case INSURANCE -> request.withPriceCents(insuranceCents(request.insurance().capacity()));
            case UPGRADE -> request.withPriceCents(upgradeCents(request.upgrade(), request.targetLevel()));
            default -> request;
        };
    }

    /** Missing settings keep the shipped prices. Invalid settings reject the whole block. */
    public static PurchasePrices load(Map<String, ?> section) {
        Objects.requireNonNull(section);
        if (section.keySet().stream().anyMatch(key -> key == null || !Set.of("maintenance-cents", "upgrades", "eviction-cents", "insurance-cents").contains(key)))
            throw new IllegalArgumentException("owner-prices has an unknown setting");
        long maintenance = section.containsKey("maintenance-cents")
                ? price("maintenance-cents", section.get("maintenance-cents")) : DEFAULT.maintenanceCents();
        var upgrades = new EnumMap<UpgradeType, List<Long>>(DEFAULT.upgrades());
        if (section.containsKey("upgrades")) {
            if (!(section.get("upgrades") instanceof Map<?, ?> raw)
                    || raw.keySet().stream().anyMatch(key -> key == null || !Set.of("advertising", "facilities").contains(key)))
                throw new IllegalArgumentException("owner-prices.upgrades needs advertising or facilities prices");
            for (UpgradeType type : UpgradeType.values()) {
                String key = key(type);
                if (!raw.containsKey(key)) continue;
                if (!(raw.get(key) instanceof List<?> levels) || levels.size() != UpgradeType.MAX_LEVEL)
                    throw new IllegalArgumentException("owner-prices.upgrades." + key + " needs three level prices");
                var parsed = new java.util.ArrayList<Long>(UpgradeType.MAX_LEVEL);
                for (int i = 0; i < levels.size(); i++) parsed.add(price("upgrades." + key + "[" + (i + 1) + "]", levels.get(i)));
                upgrades.put(type, List.copyOf(parsed));
            }
        }
        long eviction = section.containsKey("eviction-cents")
                ? price("eviction-cents", section.get("eviction-cents")) : DEFAULT.evictionCents();
        var insurance = DEFAULT.insuranceCents();
        if (section.containsKey("insurance-cents")) {
            if (!(section.get("insurance-cents") instanceof List<?> values) || values.size() != 4)
                throw new IllegalArgumentException("owner-prices.insurance-cents needs four capacity prices");
            var parsed = new java.util.ArrayList<Long>(4);
            for (int i = 0; i < 4; i++) parsed.add(price("insurance-cents[" + (i + 1) + "]", values.get(i)));
            insurance = List.copyOf(parsed);
        }
        return new PurchasePrices(maintenance, upgrades, eviction, insurance);
    }

    private static long price(String key, Object raw) {
        if (!(raw instanceof String text) || !text.matches("[1-9][0-9]{0,10}"))
            throw new IllegalArgumentException("owner-prices." + key + " must be quoted positive integer cents");
        long cents = Long.parseLong(text);
        requirePrice(key, cents);
        return cents;
    }

    private static void requirePrice(String key, long cents) {
        if (cents <= 0 || cents > MAX_CENTS)
            throw new IllegalArgumentException("owner-prices." + key + " must be from 1 to " + MAX_CENTS + " cents");
        if (TransferProof.toCents(cents / 100.0).orElse(-1) != cents)
            throw new IllegalArgumentException("owner-prices." + key + " is too large for exact cent payments");
    }

    private static String key(UpgradeType type) { return type.name().toLowerCase(Locale.ROOT); }
}
