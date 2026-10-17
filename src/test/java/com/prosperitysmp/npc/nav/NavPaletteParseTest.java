package com.prosperitysmp.npc.nav;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Palette parsing works on material names, so none of this needs a server. */
class NavPaletteParseTest {
    private static final Set<String> KNOWN = Set.of("STONE", "ANDESITE", "POLISHED_ANDESITE", "STONE_BRICKS",
            "CYAN_TERRACOTTA", "GRAY_CONCRETE_POWDER", "YELLOW_CONCRETE", "SMOOTH_QUARTZ", "SMOOTH_STONE",
            "GRASS_BLOCK", "WHITE_CONCRETE", "WHITE_CARPET", "OAK_SLAB");
    private static final Function<String, String> RESOLVE = text -> {
        String name = text.toUpperCase(Locale.ROOT);
        return KNOWN.contains(name) ? name : null;
    };
    // Carpets and slabs are the thin blocks a resident cannot stand on at the node height.
    private static final Predicate<String> FULL = name -> !name.endsWith("CARPET") && !name.endsWith("SLAB");

    private static ConfigurationSection nav(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader("nav:\n" + yaml)).getConfigurationSection("nav");
    }

    private static NavPalette.Parsed parse(String yaml, String name) {
        return NavPalette.parse(nav(yaml), name, RESOLVE, FULL);
    }

    private static final String CITY = """
              palettes:
                city:
                  sidewalk: [STONE, ANDESITE, POLISHED_ANDESITE, STONE_BRICKS]
                  road: [CYAN_TERRACOTTA, GRAY_CONCRETE_POWDER, YELLOW_CONCRETE]
                  crossing: [SMOOTH_QUARTZ]
                  access: []
            """;

    private static String message(Runnable action) {
        return assertThrows(IllegalArgumentException.class, action::run).getMessage();
    }

    @Test
    void listFormStaysAnUnclassifiedPaletteAndSkipsUnknownNames() {
        var parsed = parse("  palettes:\n    old: [STONE, NOT_A_BLOCK, grass_block]\n", "old");
        assertTrue(parsed.legacy());
        assertEquals(Map.of("STONE", NavSurface.UNKNOWN, "GRASS_BLOCK", NavSurface.UNKNOWN), parsed.materials());
        assertEquals(StreetRules.LEGACY, parsed.rules());
    }

    @Test
    void sectionFormParsesIntoTheFourClasses() {
        var parsed = parse(CITY + "  street:\n    crosswalk-reach: 8\n    crosswalk-min-share: 0.5\n", "city");
        assertFalse(parsed.legacy());
        assertEquals(NavSurface.SIDEWALK, parsed.materials().get("STONE"));
        assertEquals(NavSurface.SIDEWALK, parsed.materials().get("STONE_BRICKS"));
        assertEquals(NavSurface.ROAD, parsed.materials().get("CYAN_TERRACOTTA"));
        assertEquals(NavSurface.CROSSING, parsed.materials().get("SMOOTH_QUARTZ"));
        assertEquals(8, parsed.rules().crosswalkReach());
        assertEquals(16, parsed.rules().maxCrossingLength(), "unset street values take the shipped defaults");
        assertEquals(0.5, parsed.rules().minShare());
    }

    @Test
    void theDevCityPaletteKeepsCurbsOutAndPaintInTheCrossingClass() {
        var parsed = parse(CITY, "city");
        assertEquals(Map.of(NavSurface.SIDEWALK, 4, NavSurface.ROAD, 3, NavSurface.CROSSING, 1), parsed.classSizes());
        assertFalse(parsed.materials().containsKey("SMOOTH_STONE"), "curbs and plot outlines stay in no class");
        assertEquals(NavSurface.ROAD, parsed.materials().get("YELLOW_CONCRETE"));
        assertEquals(12, parsed.rules().crosswalkReach());
    }

    @Test
    void everySectionMistakeFailsClosedAndNamesTheProblem() {
        assertTrue(message(() -> parse(CITY.replace("CYAN_TERRACOTTA", "CYAN_TERRACOTA"), "city")).contains("CYAN_TERRACOTA"));
        assertTrue(message(() -> parse(CITY.replace("access: []", "access: [STONE]"), "city")).contains("both"));
        assertTrue(message(() -> parse(CITY.replace("[STONE, ANDESITE, POLISHED_ANDESITE, STONE_BRICKS]", "[]"), "city"))
                .contains("sidewalk"));
        assertTrue(message(() -> parse(CITY.replace("[CYAN_TERRACOTTA, GRAY_CONCRETE_POWDER, YELLOW_CONCRETE]", "[]"), "city"))
                .contains("road"));
        assertTrue(message(() -> parse(CITY.replace("crossing: [SMOOTH_QUARTZ]", "crossing: [WHITE_CARPET]"), "city"))
                .contains("full block"));
        assertTrue(message(() -> parse(CITY.replace("access: []", "acess: []"), "city")).contains("acess"));
        assertTrue(message(() -> parse(CITY.replace("sidewalk: [", "sidewalk: [OAK_SLAB, "), "city")).contains("OAK_SLAB"));
    }

    @Test
    void streetNumbersOutOfRangeOrNotNumbersAreErrors() {
        for (String bad : new String[]{"crosswalk-reach: 65", "crosswalk-reach: -1", "max-crossing-length: 33",
                "max-crossing-length: 0", "crosswalk-min-share: 0", "crosswalk-min-share: 1.5",
                "crosswalk-reach: lots", "crosswalk-reach: 2.5"}) {
            String problem = message(() -> parse(CITY + "  street:\n    " + bad + "\n", "city"));
            assertTrue(problem.contains(bad.substring(0, bad.indexOf(':'))), bad + " -> " + problem);
        }
        parse(CITY + "  street:\n    crosswalk-reach: 64\n    max-crossing-length: 32\n    crosswalk-min-share: 1\n", "city");
        parse(CITY + "  street:\n    crosswalk-reach: 0\n    max-crossing-length: 1\n    crosswalk-min-share: 0.01\n", "city");
    }

    @Test
    void onlyTheLiteralDefaultNameFallsBackToTheBuiltInList() {
        var builtIn = parse("  palettes: {}\n", "default");
        assertTrue(builtIn.legacy());
        assertTrue(builtIn.materials().containsKey("STONE"));
        assertTrue(message(() -> parse("  palettes: {}\n", "citty")).contains("citty"));
        assertTrue(message(() -> NavPalette.parse(null, "city", RESOLVE, FULL)).contains("city"));
        assertEquals(builtIn.materials(), NavPalette.parse(null, "default", RESOLVE, FULL).materials());
        // A near-miss on a configured name is an error, never a quiet switch to the default list.
        assertTrue(message(() -> parse(CITY, "Citty")).contains("Citty"));
        assertThrows(IllegalArgumentException.class, () -> parse(CITY, "../etc"));
    }

    @Test
    void anEmptyOrAllUnknownNamedListFallsBackToTheBuiltInSetAsItAlwaysDidAndSaysSo() {
        var builtIn = parse("  palettes: {}\n", "default");
        for (String yaml : new String[]{"  palettes:\n    old: []\n", "  palettes:\n    old: [NOPE, ALSO_NOPE]\n"}) {
            var parsed = parse(yaml, "old");
            assertTrue(parsed.legacy(), yaml);
            assertEquals(builtIn.materials(), parsed.materials(), yaml);
            assertEquals(builtIn.hash(), parsed.hash(), "it scans exactly like the default list");
            assertEquals("old", parsed.name());
            assertNotNull(parsed.warning(), yaml);
            assertTrue(parsed.warning().contains("'old'"), parsed.warning());
        }
        assertTrue(parse("  palettes:\n    default: [NOPE]\n", "default").materials().containsKey("STONE"));
        // A list with at least one real block is used as written, with nothing to warn about.
        var kept = parse("  palettes:\n    old: [NOPE, STONE]\n", "old");
        assertEquals(Set.of("STONE"), kept.materials().keySet());
        assertNull(kept.warning());
        assertNull(builtIn.warning());
        // The fallback is for the legacy list form only: an undefined name and an empty section still fail closed.
        assertTrue(message(() -> parse("  palettes:\n    other: [STONE]\n", "old")).contains("old"));
        assertTrue(message(() -> parse("  palettes:\n    city:\n      sidewalk: []\n      road: []\n", "city"))
                .contains("sidewalk"));
    }

    @Test
    void loadingAPaletteLogsTheListFallbackWarning() throws Exception {
        try (var bytes = NavPalette.class.getResourceAsStream("NavPalette.class")) {
            var model = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            long warnings = model.methods().stream().filter(m -> m.methodName().equalsString("load"))
                    .flatMap(m -> m.code().orElseThrow().elementStream())
                    .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                    .map(java.lang.classfile.instruction.InvokeInstruction.class::cast)
                    .filter(c -> c.name().equalsString("warning")
                            && c.owner().asInternalName().equals("java/util/logging/Logger")).count();
            assertEquals(1, warnings, "load writes the fallback warning to the plugin log");
            long guarded = model.methods().stream().filter(m -> m.methodName().equalsString("load"))
                    .flatMap(m -> m.code().orElseThrow().elementStream())
                    .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                    .map(java.lang.classfile.instruction.InvokeInstruction.class::cast)
                    .filter(c -> c.name().equalsString("first") && c.owner().asInternalName().endsWith("WarnOnce")).count();
            assertEquals(1, guarded, "the warning is only written the first time for a name");
        }
    }

    @Test
    void hashIgnoresOrderAndChangesWithAnyClassOrNumber() {
        String hash = parse(CITY, "city").hash();
        assertEquals(64, hash.length());
        String reordered = CITY.replace("[STONE, ANDESITE, POLISHED_ANDESITE, STONE_BRICKS]",
                "[STONE_BRICKS, POLISHED_ANDESITE, ANDESITE, STONE]");
        assertEquals(hash, parse(reordered, "city").hash());
        assertNotEquals(hash, parse(CITY.replace("YELLOW_CONCRETE", "WHITE_CONCRETE"), "city").hash());
        assertNotEquals(hash, parse(CITY.replace("access: []", "access: [GRASS_BLOCK]"), "city").hash());
        // The same block in another class is a different palette.
        assertNotEquals(hash, parse(CITY.replace("crossing: [SMOOTH_QUARTZ]", "crossing: []")
                .replace("YELLOW_CONCRETE]", "YELLOW_CONCRETE, SMOOTH_QUARTZ]"), "city").hash());
        for (String tuning : new String[]{"crosswalk-reach: 13", "max-crossing-length: 15", "crosswalk-min-share: 0.41"}) {
            assertNotEquals(hash, parse(CITY + "  street:\n    " + tuning + "\n", "city").hash(), tuning);
        }
        assertEquals(hash, parse(CITY + "  street:\n    crosswalk-reach: 12\n    max-crossing-length: 16\n"
                + "    crosswalk-min-share: 0.4\n", "city").hash(), "explicit defaults are the same palette");
    }

    @Test
    void aLiveAuditReadsTheWorldWithAClassifiedPaletteNeverTheListOneTheGraphWasScannedWith() {
        Predicate<String> classified = name -> name.equals("city") || name.equals("other");
        // A list-form graph palette cannot classify blocks, so the configured one is used instead.
        assertEquals("city", NavPalette.liveAuditPalette(null, "c001-road44", "city", classified));
        // A classified graph palette is its own best reading.
        assertEquals("other", NavPalette.liveAuditPalette(null, "other", "city", classified));
        // The command argument wins over both.
        assertEquals("other", NavPalette.liveAuditPalette("other", "c001-road44", "city", classified));
        assertEquals("city", NavPalette.liveAuditPalette("city", "other", null, classified));
        // Nothing classified: refuse rather than read every block as unknown.
        assertNull(NavPalette.liveAuditPalette(null, "c001-road44", null, classified));
        assertNull(NavPalette.liveAuditPalette(null, "c001-road44", "also-a-list", classified));
        assertNull(NavPalette.liveAuditPalette("c001-road44", "other", "city", classified),
                "an explicit list palette is refused, not silently replaced");
    }

    @Test
    void describeListsEveryClassAndTheHash() {
        var lines = parse(CITY, "city").describe();
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("sidewalk (4): ANDESITE, POLISHED_ANDESITE, STONE")));
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("crossing (1): SMOOTH_QUARTZ")));
        assertTrue(lines.getLast().startsWith("hash="));
    }

    private static NavPalette.Parsed parseExisting(String yaml, String name) {
        return NavPalette.parse(nav(yaml), name, RESOLVE, FULL, true);
    }

    @Test
    void aGraphScannedUnderAnUndefinedNameStillReadsAsTheBuiltInListWithAWarning() {
        var builtIn = parse("  palettes: {}\n", "default");
        for (String yaml : new String[]{"  palettes: {}\n", "  palettes:\n    other: [STONE]\n", CITY}) {
            var parsed = parseExisting(yaml, "c001-road44");
            assertTrue(parsed.legacy(), yaml);
            assertEquals(builtIn.materials(), parsed.materials(), yaml);
            assertEquals(builtIn.hash(), parsed.hash(), "it reads exactly like the default list");
            assertEquals("c001-road44", parsed.name());
            assertTrue(parsed.warning().contains("'c001-road44'"), parsed.warning());
        }
        // The same name is still an error when a new scan asks for it: that is the typo guard.
        assertTrue(message(() -> parse("  palettes: {}\n", "c001-road44")).contains("c001-road44"));
        assertTrue(message(() -> NavPalette.parse(null, "c001-road44", RESOLVE, FULL)).contains("c001-road44"));
        // A defined palette is read as written, with nothing to warn about, and the default needs no warning.
        assertNull(parseExisting(CITY, "city").warning());
        assertFalse(parseExisting(CITY, "city").legacy());
        assertNull(parseExisting("  palettes: {}\n", "default").warning());
    }

    @Test
    void aMalformedPaletteIsStillAConfigErrorForAnExistingGraph() {
        // A scalar or other non-list value is a mistake in the config, not a missing definition.
        assertTrue(message(() -> parseExisting("  palettes:\n    old: stone\n", "old")).contains("old"));
        assertTrue(message(() -> parseExisting("  palettes:\n    old: 7\n", "old")).contains("old"));
        // So are section-form mistakes and a name that cannot be a palette name at all.
        assertTrue(message(() -> parseExisting("  palettes:\n    city:\n      sidewalk: []\n      road: []\n", "city"))
                .contains("sidewalk"));
        assertThrows(IllegalArgumentException.class, () -> parseExisting(CITY, "../etc"));
    }

    @Test
    void aFallbackIsWarnedAboutOncePerNameForEachLoadedConfig() {
        var warned = new NavPalette.WarnOnce();
        Object config = new Object();
        assertTrue(warned.first(config, "old"));
        assertFalse(warned.first(config, "old"), "the same name is not repeated");
        assertFalse(warned.first(config, "old"));
        assertTrue(warned.first(config, "other"), "another name has its own warning");
        // A reloaded config is a new load: the warning comes back once.
        Object reloaded = new Object();
        assertTrue(warned.first(reloaded, "old"));
        assertFalse(warned.first(reloaded, "old"));
        assertTrue(warned.first(reloaded, "other"));
    }

    @Test
    void startingASweepReadsTheStoredPaletteLeniently() throws Exception {
        try (var bytes = com.prosperitysmp.npc.command.NpcNavCommand.class.getResourceAsStream("NpcNavCommand.class")) {
            var model = java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes());
            var start = model.methods().stream().filter(m -> m.methodName().equalsString("startSweep")).findFirst().orElseThrow();
            var calls = start.code().orElseThrow().elementStream()
                    .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                    .map(java.lang.classfile.instruction.InvokeInstruction.class::cast)
                    .filter(c -> c.owner().asInternalName().endsWith("NavPalette")).map(c -> c.name().stringValue()).toList();
            assertTrue(calls.contains("loadForExistingGraph"), calls.toString());
            assertFalse(calls.contains("load"), "a stored graph's palette is not held to the new-scan rule: " + calls);
        }
    }
}
