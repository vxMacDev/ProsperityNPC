package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The blocks a scan may stand on, and what each one means. Palettes are named in config under
 * {@code nav.palettes.<name>} in one of two forms:
 * <ul>
 *   <li>a plain list of materials (legacy): every block is ordinary walkable ground, stored as
 *       {@link NavSurface#UNKNOWN};</li>
 *   <li>a section with {@code sidewalk}, {@code road}, {@code crossing} and {@code access} lists:
 *       roads are crossed in straight lines only, and every mistake is an error.</li>
 * </ul>
 * Only the literal name {@code default} falls back to the built-in list when a palette is chosen for
 * a scan. Any other unknown name is an error, so a typo can never quietly scan with the wrong blocks.
 * A graph that already exists is different: it was scanned under a name that config may never have
 * defined (the built-in list stood in for it then), so {@link #loadForExistingGraph} keeps reading it
 * that way, with a warning. A list-form palette that names no usable block keeps its old meaning, the
 * built-in list, and logs a warning; the section form never falls back.
 */
public final class NavPalette {

    public static final String DEFAULT_NAME = "default";

    /**
     * Which palette a live audit reads the world with. A graph scanned with a list-form palette stores
     * no classes, and reading the world through that same palette would call every block unknown, so
     * the audit could never fail. An explicit request wins; otherwise the graph's own palette is used
     * when it is classified, then the configured audit palette. Null when none of them is classified.
     *
     * @param requested the palette named on the command, or null
     * @param stored the palette the graph was scanned with
     * @param configured {@code nav.audit.palette}, or null
     * @param classified whether a palette name resolves to a classified palette
     */
    public static String liveAuditPalette(String requested, String stored, String configured,
                                          Predicate<String> classified) {
        if (requested != null) {
            return classified.test(requested) ? requested : null;
        }
        if (stored != null && classified.test(stored)) {
            return stored;
        }
        return configured != null && classified.test(configured) ? configured : null;
    }
    private static final String[] CLASS_KEYS = {"sidewalk", "road", "crossing", "access"};
    private static final NavSurface[] CLASS_SURFACES =
            {NavSurface.SIDEWALK, NavSurface.ROAD, NavSurface.CROSSING, NavSurface.ACCESS};

    private static final String[] DEFAULT_NAMES = {
            "STONE", "STONE_BRICKS", "SMOOTH_STONE", "COBBLESTONE", "GRAVEL", "DIRT_PATH",
            "GRASS_BLOCK", "DIRT", "COARSE_DIRT", "PODZOL", "SAND", "RED_SAND", "SANDSTONE",
            "SMOOTH_SANDSTONE", "BRICKS", "DEEPSLATE_BRICKS", "DEEPSLATE_TILES", "COBBLED_DEEPSLATE",
            "POLISHED_ANDESITE", "POLISHED_GRANITE", "POLISHED_DIORITE", "ANDESITE", "GRANITE", "DIORITE",
            "QUARTZ_BLOCK", "SMOOTH_QUARTZ", "QUARTZ_BRICKS", "BLACKSTONE", "POLISHED_BLACKSTONE",
            "POLISHED_BLACKSTONE_BRICKS", "GILDED_BLACKSTONE", "MUD_BRICKS", "PACKED_MUD",
            "OAK_PLANKS", "SPRUCE_PLANKS", "BIRCH_PLANKS", "DARK_OAK_PLANKS", "ACACIA_PLANKS",
            "JUNGLE_PLANKS", "MANGROVE_PLANKS", "CHERRY_PLANKS", "BAMBOO_PLANKS", "CRIMSON_PLANKS",
            "WARPED_PLANKS", "WHITE_CONCRETE", "GRAY_CONCRETE", "LIGHT_GRAY_CONCRETE", "BLACK_CONCRETE",
            "BROWN_CONCRETE", "PRISMARINE", "PRISMARINE_BRICKS", "DARK_PRISMARINE", "END_STONE_BRICKS",
            "PURPUR_BLOCK", "NETHER_BRICKS", "RED_NETHER_BRICKS", "TERRACOTTA", "WHITE_TERRACOTTA",
            "GRAY_TERRACOTTA", "LIGHT_GRAY_TERRACOTTA", "CUT_COPPER", "OXIDIZED_CUT_COPPER"
    };

    /**
     * A palette read from config, by material NAME so it needs no server. {@code materials} maps each
     * name to its class; {@code hash} identifies the effective lists and street numbers. {@code warning}
     * is set when the built-in list stood in for a list-form palette with no usable block, or for a
     * name that config does not define (an existing graph only).
     */
    public record Parsed(String name, Map<String, NavSurface> materials, StreetRules rules,
                         boolean legacy, String hash, String warning) {
        public Parsed {
            materials = Collections.unmodifiableMap(new TreeMap<>(materials));
        }

        public Map<NavSurface, Integer> classSizes() {
            Map<NavSurface, Integer> sizes = new EnumMap<>(NavSurface.class);
            for (NavSurface surface : materials.values()) {
                sizes.merge(surface, 1, Integer::sum);
            }
            return sizes;
        }

        /** Human-readable lines for {@code /npcnav palette} and the scan log. */
        public List<String> describe() {
            List<String> lines = new ArrayList<>();
            if (legacy) {
                lines.add("unclassified (" + materials.size() + "): " + String.join(", ", materials.keySet()));
            } else {
                for (int i = 0; i < CLASS_KEYS.length; i++) {
                    NavSurface surface = CLASS_SURFACES[i];
                    List<String> names = new ArrayList<>();
                    materials.forEach((material, s) -> {
                        if (s == surface) {
                            names.add(material);
                        }
                    });
                    lines.add(CLASS_KEYS[i] + " (" + names.size() + "): " + String.join(", ", names));
                }
                lines.add("street: crosswalk-reach=" + rules.crosswalkReach() + " max-crossing-length="
                        + rules.maxCrossingLength() + " crosswalk-min-share=" + rules.minShare());
            }
            lines.add("hash=" + hash);
            return lines;
        }
    }

    private final Parsed parsed;
    private final Map<Material, NavSurface> surfaces;

    private NavPalette(Parsed parsed, Map<Material, NavSurface> surfaces) {
        this.parsed = parsed;
        this.surfaces = surfaces;
    }

    public String name() {
        return parsed.name();
    }

    public Parsed parsed() {
        return parsed;
    }

    public boolean isWalkable(Material material) {
        return surfaces.containsKey(material);
    }

    /** The class of a palette block; {@link NavSurface#UNKNOWN} for a block outside the palette. */
    public NavSurface surfaceOf(Material material) {
        return surfaces.getOrDefault(material, NavSurface.UNKNOWN);
    }

    public int size() {
        return surfaces.size();
    }

    public Map<NavSurface, Integer> classSizes() {
        return parsed.classSizes();
    }

    public StreetRules rules() {
        return parsed.rules();
    }

    public boolean legacy() {
        return parsed.legacy();
    }

    public String hash() {
        return parsed.hash();
    }

    /** Which fallback warnings were already logged for the config that is loaded now. */
    private static final WarnOnce FALLBACK_WARNINGS = new WarnOnce();

    /**
     * Remembers the palette names already warned about, per loaded config: a reload starts the count again,
     * but the same fallback is not repeated on every scan, audit and hash lookup in between.
     */
    static final class WarnOnce {
        private java.lang.ref.WeakReference<Object> config = new java.lang.ref.WeakReference<>(null);
        private final Set<String> seen = new HashSet<>();

        /** True the first time {@code name} is asked about under this loaded config. */
        synchronized boolean first(Object loadedConfig, String name) {
            if (config.get() != loadedConfig) {
                config = new java.lang.ref.WeakReference<>(loadedConfig);
                seen.clear();
            }
            return seen.add(name);
        }
    }

    /**
     * The palette for a new scan or a palette check. Throws {@link IllegalArgumentException} with the
     * exact problem for staff to read, including for a name that config does not define.
     */
    public static NavPalette load(ProsperityNpcPlugin plugin, String name) {
        return load(plugin, name, false);
    }

    /**
     * The palette a graph that is already stored was scanned with. A name that config never defined
     * reads as the built-in list with one warning, as it always did; a malformed palette still throws.
     */
    public static NavPalette loadForExistingGraph(ProsperityNpcPlugin plugin, String name) {
        return load(plugin, name, true);
    }

    private static NavPalette load(ProsperityNpcPlugin plugin, String name, boolean existingGraph) {
        var config = plugin.getConfig();
        Parsed parsed = parse(config.getConfigurationSection("nav"), name,
                NavPalette::resolveMaterial, NavPalette::isFullBlock, existingGraph);
        Map<Material, NavSurface> surfaces = new EnumMap<>(Material.class);
        parsed.materials().forEach((material, surface) -> surfaces.put(Material.valueOf(material), surface));
        if (parsed.warning() != null && FALLBACK_WARNINGS.first(config, parsed.name())) {
            plugin.getLogger().warning("NPCNAV-PALETTE name=" + parsed.name() + " warning=" + parsed.warning());
        }
        return new NavPalette(parsed, surfaces);
    }

    private static String resolveMaterial(String text) {
        Material material = Material.matchMaterial(text);
        return material == null || material.isLegacy() ? null : material.name();
    }

    private static boolean isFullBlock(String name) {
        Material material = Material.valueOf(name);
        return material.isBlock() && material.isOccluding();
    }

    /**
     * Pure palette parsing. {@code resolve} turns config text into a canonical material name (null if
     * it is not a material); {@code fullBlock} says whether a canonical name is a full occluding block.
     * Nodes stand on the block top plus one, so a carpet, slab or plate in a class would float the
     * node or take away headroom: such blocks are refused in the section form.
     */
    public static Parsed parse(ConfigurationSection nav, String name,
                               Function<String, String> resolve, Predicate<String> fullBlock) {
        return parse(nav, name, resolve, fullBlock, false);
    }

    /**
     * As above. With {@code existingGraph}, a name that config does not define reads as the built-in list with
     * a warning instead of being an error; a scalar or malformed value is an error either way.
     */
    public static Parsed parse(ConfigurationSection nav, String name, Function<String, String> resolve,
                               Predicate<String> fullBlock, boolean existingGraph) {
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid palette name.");
        }
        ConfigurationSection palettes = nav == null ? null : nav.getConfigurationSection("palettes");
        Object entry = palettes == null ? null : palettes.get(name);
        if (entry instanceof List<?> list) {
            return parseList(name, list, resolve);
        }
        if (entry instanceof ConfigurationSection section) {
            return parseSections(nav, name, section, resolve, fullBlock);
        }
        if (entry != null) {
            throw new IllegalArgumentException("Palette '" + name + "' must be a list of materials or a section "
                    + "with sidewalk and road lists.");
        }
        if (DEFAULT_NAME.equals(name)) {
            return legacy(name, builtIn(resolve), null);
        }
        if (existingGraph) {
            return legacy(name, builtIn(resolve), "Palette '" + name + "' is not defined in config, so the built-in '"
                    + DEFAULT_NAME + "' list is used for graphs scanned with it.");
        }
        throw new IllegalArgumentException("Unknown palette '" + name + "'. Define nav.palettes." + name
                + " in config.yml, or use '" + DEFAULT_NAME + "'.");
    }

    private static Parsed parseList(String name, List<?> list, Function<String, String> resolve) {
        Set<String> materials = new HashSet<>();
        for (Object item : list) {
            String canonical = item == null ? null : resolve.apply(item.toString());
            if (canonical != null) {
                materials.add(canonical);
            }
        }
        if (materials.isEmpty()) {
            // A list with no usable block has always meant the built-in list, so existing configs keep working.
            String warning = DEFAULT_NAME.equals(name) ? null : "Palette '" + name
                    + "' lists no known materials, so the built-in '" + DEFAULT_NAME + "' list is used.";
            return legacy(name, builtIn(resolve), warning);
        }
        return legacy(name, materials, null);
    }

    private static Set<String> builtIn(Function<String, String> resolve) {
        Set<String> materials = new HashSet<>();
        for (String text : DEFAULT_NAMES) {
            String canonical = resolve.apply(text);
            if (canonical != null) {
                materials.add(canonical);
            }
        }
        return materials;
    }

    private static Parsed legacy(String name, Set<String> materials, String warning) {
        Map<String, NavSurface> classes = new LinkedHashMap<>();
        for (String material : materials) {
            classes.put(material, NavSurface.UNKNOWN);
        }
        return new Parsed(name, classes, StreetRules.LEGACY, true, hash(classes, StreetRules.LEGACY), warning);
    }

    private static Parsed parseSections(ConfigurationSection nav, String name, ConfigurationSection section,
                                        Function<String, String> resolve, Predicate<String> fullBlock) {
        for (String key : section.getKeys(false)) {
            if (!List.of(CLASS_KEYS).contains(key)) {
                throw new IllegalArgumentException("Palette '" + name + "' has an unknown key '" + key
                        + "' (use sidewalk, road, crossing, access).");
            }
        }
        Map<String, NavSurface> classes = new LinkedHashMap<>();
        for (int i = 0; i < CLASS_KEYS.length; i++) {
            String key = CLASS_KEYS[i];
            Object raw = section.get(key);
            if (raw != null && !(raw instanceof List<?>)) {
                throw new IllegalArgumentException("Palette '" + name + "' " + key + " must be a list.");
            }
            List<?> items = raw == null ? List.of() : (List<?>) raw;
            for (Object item : items) {
                String text = item == null ? "" : item.toString();
                String canonical = resolve.apply(text);
                if (canonical == null) {
                    throw new IllegalArgumentException("Palette '" + name + "' " + key + ": '" + text
                            + "' is not a material.");
                }
                NavSurface existing = classes.get(canonical);
                if (existing != null && existing != CLASS_SURFACES[i]) {
                    throw new IllegalArgumentException("Palette '" + name + "': " + canonical + " is in both "
                            + existing.name().toLowerCase(Locale.ROOT) + " and " + key + ".");
                }
                if (!fullBlock.test(canonical)) {
                    throw new IllegalArgumentException("Palette '" + name + "' " + key + ": " + canonical
                            + " is not a full block, so a resident could not stand on it.");
                }
                classes.put(canonical, CLASS_SURFACES[i]);
            }
        }
        if (!classes.containsValue(NavSurface.SIDEWALK)) {
            throw new IllegalArgumentException("Palette '" + name + "' needs at least one sidewalk material.");
        }
        if (!classes.containsValue(NavSurface.ROAD)) {
            throw new IllegalArgumentException("Palette '" + name + "' needs at least one road material.");
        }
        StreetRules rules = streetRules(nav);
        return new Parsed(name, classes, rules, false, hash(classes, rules), null);
    }

    /** Reads {@code nav.street.*}; a value that is present but not a number is an error, never a default. */
    public static StreetRules streetRules(ConfigurationSection nav) {
        return new StreetRules(wholeNumber(nav, "crosswalk-reach", 12), wholeNumber(nav, "max-crossing-length", 16),
                number(nav, "crosswalk-min-share", 0.4).doubleValue());
    }

    private static int wholeNumber(ConfigurationSection nav, String key, int fallback) {
        Number value = number(nav, key, fallback);
        if (value.doubleValue() != Math.rint(value.doubleValue()) || Math.abs(value.doubleValue()) > 1_000_000) {
            throw new IllegalArgumentException("nav.street." + key + " must be a whole number (was " + value + ").");
        }
        return value.intValue();
    }

    private static Number number(ConfigurationSection nav, String key, Number fallback) {
        Object value = nav == null ? null : nav.get("street." + key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number;
        }
        throw new IllegalArgumentException("nav.street." + key + " must be a number (was '" + value + "').");
    }

    /** SHA-256 over the sorted {@code class:MATERIAL} lines plus the street numbers: order never matters. */
    static String hash(Map<String, NavSurface> classes, StreetRules rules) {
        List<String> lines = new ArrayList<>();
        classes.forEach((material, surface) -> lines.add(surface.name() + ":" + material));
        Collections.sort(lines);
        lines.add("crosswalk-reach=" + rules.crosswalkReach());
        lines.add("max-crossing-length=" + rules.maxCrossingLength());
        lines.add("crosswalk-min-share=" + rules.minShare());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
