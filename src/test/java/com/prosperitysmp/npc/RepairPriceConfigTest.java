package com.prosperitysmp.npc;

import com.prosperitysmp.npc.economy.RepairPrices;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RepairPriceConfigTest {
    @Test void absentAndShippedSettingsKeepExistingPrices() {
        assertEquals(RepairPrices.DEFAULT, ProsperityNpcPlugin.readRepairPrices(new YamlConfiguration()));
        var shipped = YamlConfiguration.loadConfiguration(new java.io.File("src/main/resources/config.yml"));
        assertEquals(RepairPrices.DEFAULT, ProsperityNpcPlugin.readRepairPrices(shipped));
    }

    @Test void changedYamlDoesNotMutateAnAcceptedSnapshot() throws Exception {
        var config = new YamlConfiguration();
        config.loadFromString("repair-prices:\n  pests-cents: '3500'\n  plumbing-cents: '2399'\n");
        var accepted = ProsperityNpcPlugin.readRepairPrices(config);
        assertEquals(new RepairPrices(3500, 2399, 5000), accepted);
        config.set("repair-prices.pests-cents", "9000");
        assertEquals(3500, accepted.pestsCents());
        assertEquals(9000, ProsperityNpcPlugin.readRepairPrices(config).pestsCents());
        config.set("repair-prices.plumbing-cents", "0");
        assertThrows(IllegalArgumentException.class, () -> ProsperityNpcPlugin.readRepairPrices(config));
        assertEquals(new RepairPrices(3500, 2399, 5000), accepted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "'2000'", "[]", "{pests-cents: 2000}", "{pests-cents: '0'}",
            "{pests-cents: '-1'}", "{pests-cents: '01'}", "{pests-cents: ' 2000'}", "{pests-cents: '20.00'}",
            "{pests-cents: '100000001'}", "{pests-cents: '1000000000'}",
            "{pests-cents: '2375', plumbing-cents: 'bad'}", "{pest-cents: '2375'}"})
    void invalidSectionsAreRejectedAsAWhole(String value) throws Exception {
        var config = new YamlConfiguration();
        config.loadFromString("repair-prices: " + value + "\n");
        assertThrows(IllegalArgumentException.class, () -> ProsperityNpcPlugin.readRepairPrices(config));
    }

    @Test void bukkitTreatsNullYamlEntriesAsOmittedSettings() throws Exception {
        var config = new YamlConfiguration();
        config.loadFromString("repair-prices: {pests-cents: null, plumbing-cents: '2399'}\n");
        assertFalse(config.getConfigurationSection("repair-prices").contains("pests-cents"));
        assertEquals(new RepairPrices(2000, 2399, 5000), ProsperityNpcPlugin.readRepairPrices(config));
    }
}
