package com.prosperitysmp.npc.command;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UxBundleConfigTest {
    private static YamlConfiguration shipped() throws Exception {
        try (var in = UxBundleConfigTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test void navScanMarginsShipWithTheirBuiltInValues() throws Exception {
        var config = shipped();
        assertEquals(6, config.getInt("nav.scan-below", -1));
        assertEquals(4, config.getInt("nav.scan-above", -1));
    }

    @Test void everyUxFlagShipsOff() throws Exception {
        var config = shipped();
        for (String key : List.of("conversation-sliding-deadline", "reserve-train-slot", "hire-used-notice",
                "bedrock-typed-fallback", "desk-pause-controls", "insure-command")) {
            assertTrue(config.contains("ux." + key), key);
            assertFalse(config.getBoolean("ux." + key, true), key);
        }
    }

    @Test void pauseCapAndCooldownShipWithDefaults() throws Exception {
        var config = shipped();
        assertTrue(config.contains("ux.conversation-pause-cap-seconds"));
        assertTrue(config.contains("ux.conversation-pause-cooldown-seconds"));
        assertEquals(180, config.getInt("ux.conversation-pause-cap-seconds", -1));
        assertEquals(60, config.getInt("ux.conversation-pause-cooldown-seconds", -1));
    }

    @Test void residentLabHelpListsEveryTopicTalkAccepts() {
        for (var topic : com.prosperitysmp.npc.resident.ResidentDialogue.Topic.values())
            assertTrue(ResidentLabCommand.usageLine().contains(topic.name().toLowerCase(java.util.Locale.ROOT)), topic.name());
    }

    @Test void navStreetRevalidateAndAuditDefaultsShipAsSpecified() throws Exception {
        var config = shipped();
        assertEquals(2, config.getInt("nav.headroom", -1));
        assertEquals(1, config.getInt("nav.max-step", -1));
        assertEquals(12, config.getInt("nav.street.crosswalk-reach", -1));
        assertEquals(16, config.getInt("nav.street.max-crossing-length", -1));
        assertEquals(0.4, config.getDouble("nav.street.crosswalk-min-share", -1));
        assertEquals(512, config.getInt("nav.revalidate.reads-per-tick", -1));
        assertEquals(50000, config.getInt("nav.revalidate.max-nodes", -1));
        assertEquals(256, config.getInt("nav.revalidate.max-chunks", -1));
        assertEquals(4, config.getInt("nav.revalidate.load-concurrency", -1));
        assertEquals(20, config.getInt("nav.audit.max-seconds", -1));
        assertEquals(1024, config.getInt("nav.audit.max-starts", -1));
        assertEquals(50000, config.getInt("nav.audit.max-nodes", -1));
        assertTrue(config.contains("nav.audit.gate-publish") && config.getBoolean("nav.audit.gate-publish", false));
        assertEquals("nav-audit", config.getString("nav.audit.report-dir"));
    }

    @Test void noPaletteIsDefinedOutOfTheBoxSoCityIsAnError() throws Exception {
        var config = shipped();
        var palettes = config.getConfigurationSection("nav.palettes");
        assertNotNull(palettes);
        assertTrue(palettes.getKeys(false).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> com.prosperitysmp.npc.nav.NavPalette.parse(
                config.getConfigurationSection("nav"), "city", s -> s.toUpperCase(java.util.Locale.ROOT), s -> true));
    }

    /** The commented example in config.yml, uncommented exactly as the file tells staff to, must still parse. */
    @Test void theCommentedCityExampleParsesCleanly() throws Exception {
        String text;
        try (var in = UxBundleConfigTest.class.getResourceAsStream("/config.yml")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> lines = List.of(text.replace("\r\n", "\n").split("\n"));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("# Example (uncomment and copy under palettes: to use")) start = i;
        }
        assertTrue(start > 0, "the example marker moved; update this test with it");
        StringBuilder block = new StringBuilder();
        for (int i = start + 1; i < lines.size() && lines.get(i).startsWith("  #"); i++) {
            block.append(lines.get(i).replaceFirst("# ?", "")).append('\n');
        }
        assertTrue(block.toString().contains("    city:"), block.toString());
        String yaml = text.replace("\r\n", "\n").replace("  palettes: {}\n", "  palettes:\n" + block);
        var config = YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml));
        var parsed = com.prosperitysmp.npc.nav.NavPalette.parse(config.getConfigurationSection("nav"), "city",
                s -> s.toUpperCase(java.util.Locale.ROOT), s -> true);
        assertFalse(parsed.legacy());
        var sizes = parsed.classSizes();
        // The measured mapping, so an administrator who copies the example unchanged keeps every valid crossing.
        assertEquals(4, sizes.get(com.prosperitysmp.npc.nav.NavSurface.SIDEWALK));
        assertEquals(3, sizes.get(com.prosperitysmp.npc.nav.NavSurface.ROAD));
        assertEquals(1, sizes.get(com.prosperitysmp.npc.nav.NavSurface.CROSSING));
        assertNull(sizes.get(com.prosperitysmp.npc.nav.NavSurface.ACCESS));
        for (String name : List.of("STONE", "ANDESITE", "POLISHED_ANDESITE", "STONE_BRICKS")) {
            assertEquals(com.prosperitysmp.npc.nav.NavSurface.SIDEWALK, parsed.materials().get(name), name);
        }
        for (String name : List.of("CYAN_TERRACOTTA", "GRAY_CONCRETE_POWDER", "YELLOW_CONCRETE")) {
            assertEquals(com.prosperitysmp.npc.nav.NavSurface.ROAD, parsed.materials().get(name), name);
        }
        assertEquals(com.prosperitysmp.npc.nav.NavSurface.CROSSING, parsed.materials().get("SMOOTH_QUARTZ"));
        assertFalse(parsed.materials().containsKey("SMOOTH_STONE"), "curbs and plot outlines stay a server choice");
        assertTrue(block.toString().contains("SMOOTH_STONE"), "the example says curbs and plot outlines are a server choice");
        assertEquals(12, parsed.rules().crosswalkReach());
    }
    @Test void waypointAndImportLimitsShipWithTheBoundedDefaults() throws Exception {
        var config = shipped();
        assertEquals(600, config.getInt("nav.waypoints.max-points", -1));
        assertEquals(1200, config.getInt("nav.waypoints.max-links", -1));
        assertEquals(1024, config.getInt("nav.waypoints.max-nodes", -1));
        assertEquals("", config.getString("nav.waypoints.palette"));
        assertTrue(config.contains("nav.waypoints.require-palette") && config.getBoolean("nav.waypoints.require-palette"));
        assertEquals("nav-import", config.getString("nav.import.dir"));
        assertEquals(1_048_576L, config.getLong("nav.import.max-bytes", -1));
    }

}
