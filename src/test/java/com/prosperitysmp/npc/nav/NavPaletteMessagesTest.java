package com.prosperitysmp.npc.nav;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Palette parsing with the exact words staff see, the street numbers read from config, and the description lines. */
class NavPaletteMessagesTest {
    private static final Set<String> KNOWN = Set.of("STONE", "ANDESITE", "STONE_BRICKS", "CYAN_TERRACOTTA", "YELLOW_CONCRETE",
            "SMOOTH_QUARTZ", "GRASS_BLOCK", "OAK_SLAB", "WHITE_CARPET");
    private static final Function<String, String> RESOLVE = text -> {
        String name = text.toUpperCase(Locale.ROOT);
        return KNOWN.contains(name) ? name : null;
    };
    private static final Predicate<String> FULL = name -> !name.endsWith("CARPET") && !name.endsWith("SLAB");

    private static ConfigurationSection nav(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader("nav:\n" + yaml)).getConfigurationSection("nav");
    }

    private static NavPalette.Parsed parse(String yaml, String name) {
        return NavPalette.parse(nav(yaml), name, RESOLVE, FULL);
    }

    private static String refusal(String yaml, String name) {
        return assertThrows(IllegalArgumentException.class, () -> parse(yaml, name)).getMessage();
    }

    private static final String CITY = """
              palettes:
                city:
                  sidewalk: [STONE, ANDESITE]
                  road: [CYAN_TERRACOTTA]
                  crossing: [SMOOTH_QUARTZ]
            """;

    // ---- names ----

    @ParameterizedTest
    @ValueSource(strings = {"", "a b", "../x", "a/b", "a.b", "é", "a\nb"})
    void aNameOutsideLettersDigitsUnderscoreAndDashIsInvalid(String name) {
        assertEquals("Invalid palette name.", refusal(CITY, name));
    }

    @Test
    void aNullNameAndASixtyFiveCharacterNameAreInvalidAndSixtyFourIsNot() {
        assertEquals("Invalid palette name.", refusal(CITY, null));
        assertEquals("Invalid palette name.", refusal(CITY, "a".repeat(65)));
        assertEquals("Unknown palette '" + "a".repeat(64) + "'. Define nav.palettes." + "a".repeat(64) + " in config.yml, or use 'default'.",
                refusal(CITY, "a".repeat(64)));
    }

    @Test
    void anUnknownNameSaysWhereToDefineIt() {
        assertEquals("Unknown palette 'park'. Define nav.palettes.park in config.yml, or use 'default'.", refusal(CITY, "park"));
    }

    @Test
    void aScalarPaletteEntryIsRefusedWithTheShapeItNeeds() {
        assertEquals("Palette 'old' must be a list of materials or a section with sidewalk and road lists.",
                refusal("  palettes:\n    old: stone\n", "old"));
        assertEquals("Palette 'old' must be a list of materials or a section with sidewalk and road lists.",
                refusal("  palettes:\n    old: 7\n", "old"));
    }

    @Test
    void paletteNamesAreCaseSensitive() {
        assertEquals("Unknown palette 'City'. Define nav.palettes.City in config.yml, or use 'default'.", refusal(CITY, "City"));
    }

    // ---- the list form ----

    @Test
    void aListFormPaletteTakesEveryKnownBlockAndSkipsTheRestAndStaysUnclassified() {
        NavPalette.Parsed parsed = parse("  palettes:\n    old: [stone, NOPE, Grass_Block, stone]\n", "old");
        assertTrue(parsed.legacy());
        assertEquals(Map.of("STONE", NavSurface.UNKNOWN, "GRASS_BLOCK", NavSurface.UNKNOWN), parsed.materials());
        assertNull(parsed.warning());
        assertEquals(StreetRules.LEGACY, parsed.rules());
    }

    @Test
    void anEmptyNamedListFallsBackWithTheExactWarning() {
        NavPalette.Parsed parsed = parse("  palettes:\n    old: []\n", "old");
        assertEquals("Palette 'old' lists no known materials, so the built-in 'default' list is used.", parsed.warning());
        assertTrue(parsed.legacy());
        assertEquals("old", parsed.name());
    }

    @Test
    void anUndefinedNameForAnExistingGraphWarnsWithTheExactWords() {
        NavPalette.Parsed parsed = NavPalette.parse(nav(CITY), "gone", RESOLVE, FULL, true);
        assertEquals("Palette 'gone' is not defined in config, so the built-in 'default' list is used for graphs scanned with it.",
                parsed.warning());
        assertTrue(parsed.legacy());
    }

    @Test
    void theBuiltInListHasNothingToResolveWhenTheResolverKnowsNoneOfItsNames() {
        NavPalette.Parsed parsed = NavPalette.parse(null, "default", text -> null, FULL);
        assertEquals(Map.of(), parsed.materials());
        assertTrue(parsed.legacy());
    }

    @Test
    void theBuiltInListResolvesEveryOneOfItsSixtyTwoNames() {
        NavPalette.Parsed parsed = NavPalette.parse(null, "default", text -> text.toUpperCase(Locale.ROOT), FULL);
        assertEquals(62, parsed.materials().size());
        assertEquals(NavSurface.UNKNOWN, parsed.materials().get("STONE"));
        assertEquals(NavSurface.UNKNOWN, parsed.materials().get("OXIDIZED_CUT_COPPER"));
    }

    // ---- the section form ----

    @Test
    void anUnknownSectionKeyNamesTheKeyAndTheAllowedOnes() {
        assertEquals("Palette 'city' has an unknown key 'asphalt' (use sidewalk, road, crossing, access).",
                refusal(CITY + "      asphalt: [STONE]\n", "city"));
    }

    @Test
    void aClassThatIsNotAListIsRefused() {
        assertEquals("Palette 'city' road must be a list.", refusal(CITY.replace("road: [CYAN_TERRACOTTA]", "road: CYAN_TERRACOTTA"), "city"));
    }

    @Test
    void aNameThatIsNotAMaterialIsQuotedBackWithItsClass() {
        assertEquals("Palette 'city' sidewalk: 'STON' is not a material.", refusal(CITY.replace("[STONE, ANDESITE]", "[STON, ANDESITE]"), "city"));
    }

    @Test
    void aMaterialInTwoClassesNamesBothInTheOrderTheyWereRead() {
        assertEquals("Palette 'city': STONE is in both sidewalk and crossing.",
                refusal(CITY.replace("crossing: [SMOOTH_QUARTZ]", "crossing: [STONE]"), "city"));
        assertEquals("Palette 'city': STONE is in both sidewalk and road.",
                refusal(CITY.replace("road: [CYAN_TERRACOTTA]", "road: [STONE]"), "city"));
    }

    @Test
    void theSameMaterialTwiceInOneClassIsFine() {
        NavPalette.Parsed parsed = parse(CITY.replace("[STONE, ANDESITE]", "[STONE, STONE, ANDESITE]"), "city");
        assertEquals(Map.of(NavSurface.SIDEWALK, 2, NavSurface.ROAD, 1, NavSurface.CROSSING, 1), parsed.classSizes());
    }

    @Test
    void aThinBlockIsRefusedWithItsClassAndTheReason() {
        assertEquals("Palette 'city' sidewalk: OAK_SLAB is not a full block, so a resident could not stand on it.",
                refusal(CITY.replace("[STONE, ANDESITE]", "[OAK_SLAB]"), "city"));
        assertEquals("Palette 'city' crossing: WHITE_CARPET is not a full block, so a resident could not stand on it.",
                refusal(CITY.replace("crossing: [SMOOTH_QUARTZ]", "crossing: [WHITE_CARPET]"), "city"));
    }

    @Test
    void aMissingSidewalkOrRoadClassIsNamed() {
        assertEquals("Palette 'city' needs at least one sidewalk material.", refusal(CITY.replace("sidewalk: [STONE, ANDESITE]", "sidewalk: []"), "city"));
        assertEquals("Palette 'city' needs at least one road material.", refusal(CITY.replace("road: [CYAN_TERRACOTTA]", "road: []"), "city"));
        assertEquals("Palette 'city' needs at least one sidewalk material.", refusal("  palettes:\n    city:\n      road: [CYAN_TERRACOTTA]\n", "city"));
    }

    @Test
    void accessAndCrossingAreOptional() {
        NavPalette.Parsed parsed = parse("  palettes:\n    city:\n      sidewalk: [STONE]\n      road: [CYAN_TERRACOTTA]\n", "city");
        assertEquals(Map.of(NavSurface.SIDEWALK, 1, NavSurface.ROAD, 1), parsed.classSizes());
        assertFalse(parsed.legacy());
        assertNull(parsed.warning());
    }

    @Test
    void anItemThatIsNullInAClassListReadsAsNotAMaterial() {
        assertEquals("Palette 'city' sidewalk: '' is not a material.", refusal(CITY.replace("[STONE, ANDESITE]", "[STONE, ~]"), "city"));
    }

    // ---- street numbers ----

    @Test
    void withNoStreetSectionTheShippedDefaultsApply() {
        StreetRules rules = parse(CITY, "city").rules();
        assertEquals(new StreetRules(12, 16, 0.4), rules);
    }

    @Test
    void aSectionOfStreetNumbersIsRead() {
        StreetRules rules = parse(CITY + "  street:\n    crosswalk-reach: 8\n    max-crossing-length: 20\n    crosswalk-min-share: 0.25\n", "city").rules();
        assertEquals(new StreetRules(8, 20, 0.25), rules);
    }

    @Test
    void aWholeNumberWrittenWithADecimalPointIsStillWhole() {
        StreetRules rules = NavPalette.streetRules(nav("  street:\n    crosswalk-reach: 8.0\n"));
        assertEquals(8, rules.crosswalkReach());
    }

    @Test
    void aShareWrittenAsAnIntegerIsRead() {
        assertEquals(1.0, NavPalette.streetRules(nav("  street:\n    crosswalk-min-share: 1\n")).minShare());
    }

    @Test
    void aFractionalReachIsRefusedWithTheValue() {
        assertEquals("nav.street.crosswalk-reach must be a whole number (was 12.5).",
                assertThrows(IllegalArgumentException.class, () -> NavPalette.streetRules(nav("  street:\n    crosswalk-reach: 12.5\n"))).getMessage());
    }

    @Test
    void aReachBeyondAMillionIsNotAWholeNumberWeTrust() {
        assertEquals("nav.street.max-crossing-length must be a whole number (was 2000000).",
                assertThrows(IllegalArgumentException.class, () -> NavPalette.streetRules(nav("  street:\n    max-crossing-length: 2000000\n"))).getMessage());
    }

    @Test
    void aValueThatIsNotANumberIsAnErrorNeverADefault() {
        assertEquals("nav.street.crosswalk-reach must be a number (was 'lots').",
                assertThrows(IllegalArgumentException.class, () -> NavPalette.streetRules(nav("  street:\n    crosswalk-reach: lots\n"))).getMessage());
        assertEquals("nav.street.crosswalk-min-share must be a number (was 'true').",
                assertThrows(IllegalArgumentException.class, () -> NavPalette.streetRules(nav("  street:\n    crosswalk-min-share: true\n"))).getMessage());
    }

    @Test
    void aMissingNavSectionGivesTheDefaults() {
        assertEquals(new StreetRules(12, 16, 0.4), NavPalette.streetRules(null));
    }

    @Test
    void anOutOfRangeNumberFailsInTheRulesWithTheirOwnMessage() {
        assertEquals("nav.street.crosswalk-reach must be 0..64 (was 65)",
                assertThrows(IllegalArgumentException.class, () -> NavPalette.streetRules(nav("  street:\n    crosswalk-reach: 65\n"))).getMessage());
    }

    // ---- description, sizes and hash ----

    @Test
    void aSectionPaletteIsDescribedClassByClassThenStreetNumbersThenTheHash() {
        NavPalette.Parsed parsed = parse(CITY, "city");
        assertEquals(List.of(
                "sidewalk (2): ANDESITE, STONE",
                "road (1): CYAN_TERRACOTTA",
                "crossing (1): SMOOTH_QUARTZ",
                "access (0): ",
                "street: crosswalk-reach=12 max-crossing-length=16 crosswalk-min-share=0.4",
                "hash=" + parsed.hash()), parsed.describe());
    }

    @Test
    void aListPaletteIsDescribedAsOneUnclassifiedLineThenTheHash() {
        NavPalette.Parsed parsed = parse("  palettes:\n    old: [STONE, GRASS_BLOCK]\n", "old");
        assertEquals(List.of("unclassified (2): GRASS_BLOCK, STONE", "hash=" + parsed.hash()), parsed.describe());
    }

    @Test
    void classSizesCountMaterialsPerClassAndOmitEmptyOnes() {
        assertEquals(Map.of(NavSurface.SIDEWALK, 2, NavSurface.ROAD, 1, NavSurface.CROSSING, 1), parse(CITY, "city").classSizes());
    }

    @Test
    void theMaterialMapOfAParsedPaletteCannotBeChanged() {
        NavPalette.Parsed parsed = parse(CITY, "city");
        assertThrows(UnsupportedOperationException.class, () -> parsed.materials().put("STONE", NavSurface.ROAD));
    }

    @Test
    void theHashIsSixtyFourLowercaseHexDigitsAndIgnoresTheNameOfThePalette() {
        String one = parse(CITY, "city").hash();
        String renamed = parse(CITY.replace("city:", "town:"), "town").hash();
        assertTrue(one.matches("[0-9a-f]{64}"), one);
        assertEquals(one, renamed);
    }

    @Test
    void movingABlockToAnotherClassChangesTheHashButMovingItWithinTheListDoesNot() {
        String hash = parse(CITY, "city").hash();
        assertNotEquals(hash, parse(CITY.replace("sidewalk: [STONE, ANDESITE]", "sidewalk: [STONE]").replace("crossing: [SMOOTH_QUARTZ]",
                "crossing: [SMOOTH_QUARTZ]\n      access: [ANDESITE]"), "city").hash());
        assertEquals(hash, parse(CITY.replace("[STONE, ANDESITE]", "[ANDESITE, STONE]"), "city").hash());
    }

    @Test
    void theHashOfAListPaletteDependsOnlyOnItsBlocks() {
        String a = parse("  palettes:\n    a: [STONE, GRASS_BLOCK]\n", "a").hash();
        String b = parse("  palettes:\n    b: [GRASS_BLOCK, STONE]\n", "b").hash();
        String c = parse("  palettes:\n    c: [STONE]\n", "c").hash();
        assertEquals(a, b);
        assertNotEquals(a, c);
    }
}
