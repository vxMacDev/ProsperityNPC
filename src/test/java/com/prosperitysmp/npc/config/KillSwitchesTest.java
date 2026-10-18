package com.prosperitysmp.npc.config;

import com.prosperitysmp.npc.CoreTestSupport;
import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.config.KillSwitches.Switch;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KillSwitchesTest {
    private final List<String> log = new ArrayList<>();

    private ProsperityNpcPlugin plugin(Path data, String yaml) {
        return CoreTestSupport.plugin(data, CoreTestSupport.yaml(yaml), log);
    }

    @Test
    void thereAreExactlyFiveSwitchesInDeclaredOrder() {
        assertEquals(List.of(Switch.SYSTEM, Switch.ECONOMY, Switch.INCIDENTS, Switch.MOVES, Switch.RENDERING),
                List.of(Switch.values()));
    }

    @ParameterizedTest
    @CsvSource({"SYSTEM,system-enabled", "ECONOMY,economy-enabled", "INCIDENTS,incidents-enabled", "MOVES,moves-enabled",
            "RENDERING,rendering-enabled"})
    void eachSwitchHasItsConfigKeyAndPath(Switch sw, String key) {
        assertEquals(key, sw.key());
        assertEquals("switches." + key, sw.path());
    }

    @ParameterizedTest
    @CsvSource({"economy-enabled,ECONOMY", "ECONOMY,ECONOMY", "economy,ECONOMY", "Economy-Enabled,ECONOMY", "eCoNoMy,ECONOMY",
            "system,SYSTEM", "SYSTEM-ENABLED,SYSTEM", "incidents,INCIDENTS", "moves,MOVES", "moves-enabled,MOVES",
            "rendering,RENDERING"})
    void aSwitchIsFoundByKeyOrNameInAnyCase(String text, Switch expected) {
        assertSame(expected, Switch.fromKey(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bogus", "", " economy", "economy ", "economy_enabled", "economy-enable", "all", "switches.economy-enabled"})
    void anythingElseFindsNoSwitch(String text) {
        assertNull(Switch.fromKey(text));
    }

    @Test
    void aNullKeyFindsNoSwitch() {
        assertNull(Switch.fromKey(null));
    }

    @Test
    void everySwitchIsOffWhenTheConfigHasNoSwitchesSection(@TempDir Path data) {
        KillSwitches switches = KillSwitches.load(plugin(data, "other: 1\n"));
        for (Switch sw : Switch.values()) assertFalse(switches.isEnabled(sw), sw.name());
        assertEquals("system=false economy=false incidents=false moves=false rendering=false", switches.summary());
    }

    @ParameterizedTest
    @EnumSource(Switch.class)
    void aSwitchTurnedOnInTheConfigIsTheOnlyOneOn(Switch on, @TempDir Path data) {
        KillSwitches switches = KillSwitches.load(plugin(data, "switches:\n  " + on.key() + ": true\n"));
        for (Switch sw : Switch.values()) assertEquals(sw == on, switches.isEnabled(sw), sw.name());
    }

    @Test
    void theNamedGettersMatchTheEnumLookup(@TempDir Path data) {
        KillSwitches switches = KillSwitches.load(plugin(data,
                "switches:\n  system-enabled: true\n  economy-enabled: false\n  incidents-enabled: true\n  moves-enabled: false\n  rendering-enabled: true\n"));
        assertTrue(switches.isSystemEnabled());
        assertFalse(switches.isEconomyEnabled());
        assertTrue(switches.isIncidentsEnabled());
        assertFalse(switches.isMovesEnabled());
        assertTrue(switches.isRenderingEnabled());
        assertEquals("system=true economy=false incidents=true moves=false rendering=true", switches.summary());
    }

    @Test
    void aValueThatIsNotABooleanLeavesTheSwitchOff(@TempDir Path data) {
        KillSwitches switches = KillSwitches.load(plugin(data, "switches:\n  system-enabled: 'true'\n  economy-enabled: 1\n  moves-enabled: maybe\n"));
        assertFalse(switches.isSystemEnabled());
        assertFalse(switches.isEconomyEnabled());
        assertFalse(switches.isMovesEnabled());
    }

    @Test
    void reloadPicksUpAConfigChangeWithoutAnythingBeingWritten(@TempDir Path data) {
        var config = CoreTestSupport.yaml("switches:\n  economy-enabled: false\n");
        KillSwitches switches = KillSwitches.load(CoreTestSupport.plugin(data, config, log));
        assertFalse(switches.isEconomyEnabled());
        config.set("switches.economy-enabled", true);
        assertFalse(switches.isEconomyEnabled(), "the snapshot only changes on reload");
        switches.reload();
        assertTrue(switches.isEconomyEnabled());
        config.set("switches.economy-enabled", false);
        switches.reload();
        assertFalse(switches.isEconomyEnabled());
        assertFalse(Files.exists(data.resolve("config.yml")));
    }

    @Test
    void setPersistsTheSwitchToConfigYmlAndUpdatesTheSnapshot(@TempDir Path data) throws IOException {
        KillSwitches switches = KillSwitches.load(plugin(data, "switches:\n  system-enabled: true\n"));
        switches.set(Switch.ECONOMY, true);
        assertTrue(switches.isEconomyEnabled());
        assertTrue(switches.isSystemEnabled());
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(new File(data.toFile(), "config.yml"));
        assertTrue(saved.getBoolean("switches.economy-enabled"));
        assertTrue(saved.getBoolean("switches.system-enabled"));
        assertFalse(saved.getBoolean("switches.moves-enabled", false));
        assertFalse(saved.contains("switches.moves-enabled"));
    }

    @Test
    void setBackToFalseIsPersistedToo(@TempDir Path data) {
        KillSwitches switches = KillSwitches.load(plugin(data, "switches:\n  moves-enabled: true\n"));
        switches.set(Switch.MOVES, false);
        assertFalse(switches.isMovesEnabled());
        YamlConfiguration saved = YamlConfiguration.loadConfiguration(new File(data.toFile(), "config.yml"));
        assertTrue(saved.contains("switches.moves-enabled"));
        assertFalse(saved.getBoolean("switches.moves-enabled", true));
    }

    @Test
    void settingOneSwitchNeverMovesAnother(@TempDir Path data) {
        KillSwitches switches = KillSwitches.load(plugin(data, "switches:\n  system-enabled: true\n  incidents-enabled: true\n"));
        Map<Switch, Boolean> before = new EnumMap<>(Switch.class);
        for (Switch sw : Switch.values()) before.put(sw, switches.isEnabled(sw));
        switches.set(Switch.RENDERING, true);
        for (Switch sw : Switch.values()) {
            assertEquals(sw == Switch.RENDERING || before.get(sw), switches.isEnabled(sw), sw.name());
        }
    }

    @Test
    void aSavedSwitchSurvivesALaterReloadFromTheSameConfig(@TempDir Path data) {
        KillSwitches switches = KillSwitches.load(plugin(data, ""));
        switches.set(Switch.INCIDENTS, true);
        switches.reload();
        assertTrue(switches.isIncidentsEnabled());
    }

    @Test
    void aSaveThatCannotBeWrittenStillAppliesTheSwitchInMemoryAndLogsSevere(@TempDir Path data) throws IOException {
        Path blocked = data.resolve("blocked");
        Files.writeString(blocked, "a file, not a folder");
        var config = CoreTestSupport.yaml("");
        ProsperityNpcPlugin plugin = CoreTestSupport.plugin(blocked, config, log);
        KillSwitches switches = KillSwitches.load(plugin);
        switches.set(Switch.SYSTEM, true);
        assertTrue(switches.isSystemEnabled(), "the in-memory config is what reload reads");
        assertEquals(1, log.size());
        assertTrue(log.get(0).startsWith("SEVERE: Could not save config to "), log.get(0));
    }
}
