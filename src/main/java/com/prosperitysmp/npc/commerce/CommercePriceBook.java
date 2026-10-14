package com.prosperitysmp.npc.commerce;

import org.bukkit.Bukkit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Short-lived main-thread full-price feed, never a getWorth discount or an unknown-as-zero fallback. */
public final class CommercePriceBook {
    public static final Set<String> MATERIALS = Set.of("STONE", "COBBLESTONE", "OAK_LOG", "COAL", "RAW_IRON", "IRON_INGOT", "WHEAT",
            "BREAD");
    private static final long LIFETIME_NANOS = 10_000_000_000L;
    private final BooleanSupplier main;
    private final LongSupplier ticks;
    private final Map<String, Long> building = new HashMap<>();
    // Main thread. Exactly MATERIALS unless item-repairs adds its recipe materials (configureMaterials).
    private Set<String> allowed = MATERIALS;
    private Map<String, Long> current = Map.of();
    private UUID token;
    private long published;
    private String version;

    public CommercePriceBook() { this(Bukkit::isPrimaryThread, System::nanoTime); }
    CommercePriceBook(BooleanSupplier main, LongSupplier ticks) {
        this.main = main;
        this.ticks = ticks;
    }

    public UUID begin() {
        requireMain();
        invalidate();
        token = UUID.randomUUID();
        return token;
    }

    public void put(UUID expected, String material, double dollars) {
        requireMain();
        if (token == null || !token.equals(expected) || !allowed.contains(material)
                || !Double.isFinite(dollars) || dollars <= 0 || dollars > 1_000_000)
            throw new IllegalArgumentException("Invalid full-price publication");
        long cents = BigDecimal.valueOf(dollars).movePointRight(2).setScale(0, RoundingMode.CEILING).longValueExact();
        if (building.putIfAbsent(material, cents) != null) throw new IllegalArgumentException("Duplicate price row");
    }

    public void complete(UUID expected, String sourceVersion) {
        requireMain();
        if (token == null || !token.equals(expected) || sourceVersion == null || sourceVersion.isBlank()
                || sourceVersion.length() > 96 || sourceVersion.codePoints().anyMatch(Character::isISOControl)
                || building.isEmpty()) throw new IllegalArgumentException("Invalid price publication acknowledgement");
        current = Map.copyOf(building);
        version = sourceVersion;
        published = ticks.getAsLong();
        token = null;
        building.clear();
    }

    public OptionalLong unitCents(String material) {
        requireMain();
        long age = ticks.getAsLong() - published;
        Long value = current.get(material);
        return version != null && age >= 0 && age < LIFETIME_NANOS && value != null
                ? OptionalLong.of(value) : OptionalLong.empty();
    }

    /**
     * Main only, on enable and reload: the commerce materials plus extra (the item-repair recipe materials while
     * that feature is on). Empty extra means exactly MATERIALS. Prices already published stay until
     * their 10 second lifetime ends.
     */
    public void configureMaterials(Set<String> extra) {
        requireMain();
        var all = new TreeSet<>(MATERIALS);
        for (String material : extra) {
            if (material == null || !material.matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid price material");
            all.add(material);
        }
        if (all.size() > 64) throw new IllegalArgumentException("Too many price materials");
        allowed = Set.copyOf(all);
    }

    /** Sorted list of materials a price publication may include; the price skript loops this. */
    public List<String> materials() {
        requireMain();
        return allowed.stream().sorted().toList();
    }

    public String version() {
        requireMain();
        return version;
    }
    public Map<String, Long> snapshot() {
        requireMain();
        long age = ticks.getAsLong() - published;
        return version != null && age >= 0 && age < LIFETIME_NANOS ? current : Map.of();
    }
    public void invalidate() {
        requireMain();
        current = Map.of();
        building.clear();
        token = null;
        version = null;
    }
    private void requireMain() { if (!main.getAsBoolean()) throw new IllegalStateException("Price publication requires main thread"); }
}
