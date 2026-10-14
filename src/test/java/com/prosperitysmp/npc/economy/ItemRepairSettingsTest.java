package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.formulas.IncidentType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import static org.junit.jupiter.api.Assertions.*;

/** item-repairs.* ships off; a bad value holds only this feature; admission says "off" while it is off. */
class ItemRepairSettingsTest {
    private static final ToIntFunction<String> STACKS = name -> switch (name) {
        case "DIAMOND_SWORD" -> 1;
        case "NOT_AN_ITEM" -> 0;
        default -> 64;
    };

    private static YamlConfiguration shipped() throws Exception {
        try (var input = ItemRepairSettingsTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            var config = new YamlConfiguration();
            config.loadFromString(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            return config;
        }
    }

    @Test void shippedConfigIsOffWithTheDefaultRecipes() throws Exception {
        var config = shipped();
        var settings = ItemRepairSettings.load(config::get, STACKS);
        assertFalse(settings.enabled());
        assertFalse(settings.active());
        assertFalse(settings.held());
        assertEquals(15_000, settings.ratioBps());
        assertEquals(256, settings.maxUnits());
        assertEquals(4, settings.maxStacks());
        assertTrue(settings.requireSurvival());
        assertTrue(settings.blockCashWhileItemsHeld());
        assertEquals(ItemRepairSettings.DEFAULT_RECIPES, settings.recipes());
        assertTrue(settings.warnings().isEmpty(), settings.warnings().toString());
        assertEquals(Set.of(), settings.priceMaterials(), "off: the price book keeps exactly its own list");
        // The shipped recipes match the legacy incident text they replace.
        assertEquals("16 paper + 8 baked_potato", IncidentType.PESTS.itemRepairSpec());
        // Positive control: turning it on publishes the recipe materials.
        config.set(ItemRepairSettings.ENABLED, true);
        var on = ItemRepairSettings.load(config::get, STACKS);
        assertTrue(on.active());
        assertEquals(Set.of("PAPER", "BAKED_POTATO", "COPPER_INGOT", "IRON_INGOT", "GLASS"), on.priceMaterials());
    }

    @Test void badRatioOrBoundsHoldOnlyThisFeature() throws Exception {
        for (Object bad : List.of(9_999, 100_001, 15_000.0, "15000")) {
            var config = shipped();
            config.set(ItemRepairSettings.ENABLED, true);
            config.set(ItemRepairSettings.RATIO, bad);
            var settings = ItemRepairSettings.load(config::get, STACKS);
            assertTrue(settings.held(), "ratio " + bad);
            assertFalse(settings.active());
            assertFalse(settings.warnings().isEmpty());
            assertEquals(Set.of(), settings.priceMaterials());
        }
        for (String key : List.of(ItemRepairSettings.MAX_UNITS, ItemRepairSettings.MAX_STACKS)) {
            var config = shipped();
            config.set(ItemRepairSettings.ENABLED, true);
            config.set(key, 0);
            assertTrue(ItemRepairSettings.load(config::get, STACKS).held(), key);
        }
        var config = shipped();
        config.set(ItemRepairSettings.ENABLED, true);
        config.set(ItemRepairSettings.MAX_STACKS, 5);
        assertTrue(ItemRepairSettings.load(config::get, STACKS).held());
    }

    @Test void badRecipeMakesOnlyThatProblemCashOnly() throws Exception {
        var config = shipped();
        config.set(ItemRepairSettings.ENABLED, true);
        config.set(ItemRepairSettings.RECIPES + "PESTS", List.of(Map.of("material", "SPAWNER", "quantity", 1)));
        config.set(ItemRepairSettings.RECIPES + "PLUMBING", List.of(Map.of("material", "DIAMOND_SWORD", "quantity", 1)));
        var settings = ItemRepairSettings.load(config::get, STACKS);
        assertTrue(settings.active(), "the feature stays on");
        assertTrue(settings.recipe(IncidentType.PESTS).isEmpty());
        assertTrue(settings.recipe(IncidentType.PLUMBING).isEmpty());
        assertEquals(ItemRepairSettings.DEFAULT_RECIPES.get(IncidentType.BREAK_IN), settings.recipe(IncidentType.BREAK_IN).orElseThrow());
        assertEquals(2, settings.warnings().size());
        for (Object bad : List.of("PAPER", List.of(), List.of(Map.of("material", "PAPER", "quantity", 0)),
                List.of(Map.of("material", "PAPER", "quantity", 257)), List.of(Map.of("material", "PAPER")),
                List.of(Map.of("material", "PAPER", "quantity", 1), Map.of("material", "PAPER", "quantity", 2)),
                List.of(Map.of("material", "NOT_AN_ITEM", "quantity", 1)),
                List.of(Map.of("material", "A", "quantity", 1), Map.of("material", "B", "quantity", 1),
                        Map.of("material", "C", "quantity", 1), Map.of("material", "D", "quantity", 1), Map.of("material", "E", "quantity", 1))))
            assertTrue(ItemRepairSettings.parse(bad, STACKS).isEmpty(), String.valueOf(bad));
    }

    @Test void safetyChecksFailTowardOn() throws Exception {
        var config = shipped();
        config.set(ItemRepairSettings.REQUIRE_SURVIVAL, "maybe");
        config.set(ItemRepairSettings.BLOCK_CASH, 1);
        var settings = ItemRepairSettings.load(config::get, STACKS);
        assertTrue(settings.requireSurvival());
        assertTrue(settings.blockCashWhileItemsHeld());
        config.set(ItemRepairSettings.REQUIRE_SURVIVAL, false);
        config.set(ItemRepairSettings.BLOCK_CASH, false);
        settings = ItemRepairSettings.load(config::get, STACKS);
        assertFalse(settings.requireSurvival());
        assertFalse(settings.blockCashWhileItemsHeld());
    }

    @Test void admissionSaysOffWhileOffAndPassesWhenOnAndReady() throws Exception {
        var config = shipped();
        var off = ItemRepairSettings.load(config::get, STACKS);
        assertEquals(ItemRepairCopy.OFF, ItemRepairRuntime.admissionRefusal(off, true, true, true));
        config.set(ItemRepairSettings.ENABLED, true);
        var on = ItemRepairSettings.load(config::get, STACKS);
        assertNull(ItemRepairRuntime.admissionRefusal(on, true, true, true), "positive control: on and ready goes on");
        assertEquals(ItemRepairCopy.UNAVAILABLE, ItemRepairRuntime.admissionRefusal(on, false, true, true));
        assertEquals(ItemRepairCopy.UNAVAILABLE, ItemRepairRuntime.admissionRefusal(on, true, false, true));
        assertEquals(ItemRepairCopy.UNAVAILABLE, ItemRepairRuntime.admissionRefusal(on, true, true, false));
        config.set(ItemRepairSettings.RATIO, 1);
        assertEquals(ItemRepairCopy.UNAVAILABLE, ItemRepairRuntime.admissionRefusal(ItemRepairSettings.load(config::get, STACKS), true, true, true));
    }
}
