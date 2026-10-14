package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.formulas.IncidentType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * item-repairs.* read on start and /npcadmin reload. A bad ratio or bound holds the whole feature off with a
 * warning. A bad recipe makes only that incident type cash only. Nothing here moves money or items.
 */
public record ItemRepairSettings(boolean enabled, boolean held, int ratioBps, int maxUnits, int maxStacks,
                                 boolean requireSurvival, boolean blockCashWhileItemsHeld,
                                 Map<IncidentType, List<ItemRepairRecipes.Line>> recipes, List<String> warnings) {
    public static final String ENABLED = "item-repairs.enabled";
    public static final String RATIO = "item-repairs.min-value-ratio-bps";
    public static final String MAX_UNITS = "item-repairs.max-units-per-repair";
    public static final String MAX_STACKS = "item-repairs.max-return-stacks";
    public static final String REQUIRE_SURVIVAL = "item-repairs.require-survival";
    public static final String BLOCK_CASH = "item-repairs.block-cash-while-items-held";
    public static final String RECIPES = "item-repairs.recipes.";
    public static final int DEFAULT_RATIO_BPS = 15_000, DEFAULT_MAX_UNITS = 256, DEFAULT_MAX_STACKS = 4;
    /** Same lists as IncidentType's item repair text, which stays untouched in the database. */
    public static final Map<IncidentType, List<ItemRepairRecipes.Line>> DEFAULT_RECIPES = Map.of(
            IncidentType.PESTS, List.of(new ItemRepairRecipes.Line("PAPER", 16), new ItemRepairRecipes.Line("BAKED_POTATO", 8)),
            IncidentType.PLUMBING, List.of(new ItemRepairRecipes.Line("COPPER_INGOT", 16), new ItemRepairRecipes.Line("IRON_INGOT", 4)),
            IncidentType.BREAK_IN, List.of(new ItemRepairRecipes.Line("IRON_INGOT", 32), new ItemRepairRecipes.Line("GLASS", 8)));
    public static final ItemRepairSettings OFF = new ItemRepairSettings(false, false, DEFAULT_RATIO_BPS, DEFAULT_MAX_UNITS,
            DEFAULT_MAX_STACKS, true, true, Map.of(), List.of());

    public ItemRepairSettings {
        recipes = Map.copyOf(recipes);
        warnings = List.copyOf(warnings);
    }

    /** On and valid. Admission also needs the system and economy switches. */
    public boolean active() { return enabled && !held; }

    public Optional<List<ItemRepairRecipes.Line>> recipe(IncidentType type) {
        return Optional.ofNullable(recipes.get(type));
    }

    /** Materials the price book must publish. Empty while the feature is off, so the price book keeps its own list. */
    public Set<String> priceMaterials() {
        if (!active()) return Set.of();
        var out = new TreeSet<String>();
        recipes.values().forEach(lines -> lines.forEach(line -> out.add(line.material())));
        return Set.copyOf(out);
    }

    /**
     * get reads one config path (null when absent, which means the shipped default). maxStack gives the stack size
     * for a material name, 0 when it is not an item.
     */
    public static ItemRepairSettings load(Function<String, Object> get, ToIntFunction<String> maxStack) {
        Objects.requireNonNull(get);
        Objects.requireNonNull(maxStack);
        var warnings = new ArrayList<String>();
        Object rawEnabled = get.apply(ENABLED);
        boolean enabled = Boolean.TRUE.equals(rawEnabled);
        if (rawEnabled != null && !(rawEnabled instanceof Boolean)) warnings.add(ENABLED + " is not true or false; item repairs stay off.");
        boolean held = false;
        int ratio = integer(get.apply(RATIO), DEFAULT_RATIO_BPS);
        if (ratio < ItemRepairRecipes.MIN_RATIO_BPS || ratio > ItemRepairRecipes.MAX_RATIO_BPS) {
            warnings.add(RATIO + " must be a whole number from 10000 to 100000; item repairs are held off.");
            held = true;
            ratio = DEFAULT_RATIO_BPS;
        }
        int units = integer(get.apply(MAX_UNITS), DEFAULT_MAX_UNITS);
        if (units < 1 || units > ItemRepairRecipes.MAX_LINE_QUANTITY) {
            warnings.add(MAX_UNITS + " must be a whole number from 1 to 256; item repairs are held off.");
            held = true;
            units = DEFAULT_MAX_UNITS;
        }
        int stacks = integer(get.apply(MAX_STACKS), DEFAULT_MAX_STACKS);
        if (stacks < 1 || stacks > ItemRepairRecipes.MAX_LINES) {
            warnings.add(MAX_STACKS + " must be a whole number from 1 to 4; item repairs are held off.");
            held = true;
            stacks = DEFAULT_MAX_STACKS;
        }
        boolean survival = safety(get.apply(REQUIRE_SURVIVAL), REQUIRE_SURVIVAL, warnings);
        boolean block = safety(get.apply(BLOCK_CASH), BLOCK_CASH, warnings);
        var recipes = new EnumMap<IncidentType, List<ItemRepairRecipes.Line>>(IncidentType.class);
        for (IncidentType type : IncidentType.values()) {
            Object raw = get.apply(RECIPES + type.name());
            if (raw == null) {
                recipes.put(type, DEFAULT_RECIPES.get(type));
                continue;
            }
            var parsed = parse(raw, maxStack);
            if (parsed.isPresent()) recipes.put(type, parsed.get());
            else warnings.add(RECIPES + type.name() + " is not a valid item list; that problem is cash only.");
        }
        return new ItemRepairSettings(enabled, held, ratio, units, stacks, survival, block, recipes, warnings);
    }

    /** 1 to 4 lines of {material, quantity}, distinct allowed materials, quantity 1 to 256. */
    static Optional<List<ItemRepairRecipes.Line>> parse(Object raw, ToIntFunction<String> maxStack) {
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > ItemRepairRecipes.MAX_LINES) return Optional.empty();
        var lines = new ArrayList<ItemRepairRecipes.Line>();
        var seen = new HashSet<String>();
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map) || map.size() != 2) return Optional.empty();
            Object material = map.get("material"), quantity = map.get("quantity");
            if (!(material instanceof String name) || !(quantity instanceof Integer count)) return Optional.empty();
            if (count < 1 || count > ItemRepairRecipes.MAX_LINE_QUANTITY || !seen.add(name)) return Optional.empty();
            int stack;
            try {
                stack = maxStack.applyAsInt(name);
            } catch (RuntimeException unknown) {
                return Optional.empty();
            }
            if (!ItemRepairRecipes.allowedMaterial(name, stack)) return Optional.empty();
            lines.add(new ItemRepairRecipes.Line(name, count));
        }
        return Optional.of(List.copyOf(lines));
    }

    /** Whole numbers only: 15000, not 15000.0 or "15000". Absent means the default. */
    private static int integer(Object raw, int fallback) {
        if (raw == null) return fallback;
        return raw instanceof Integer value ? value : Integer.MIN_VALUE;
    }

    /** Safety checks fail toward on: anything but false keeps the check. */
    private static boolean safety(Object raw, String key, List<String> warnings) {
        if (raw == null || Boolean.TRUE.equals(raw)) return true;
        if (Boolean.FALSE.equals(raw)) return false;
        warnings.add(key + " is not true or false; the check stays on.");
        return true;
    }
}
