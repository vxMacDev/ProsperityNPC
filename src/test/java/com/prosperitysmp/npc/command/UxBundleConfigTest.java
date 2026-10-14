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
}
