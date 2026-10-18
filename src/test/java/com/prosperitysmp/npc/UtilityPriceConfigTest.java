package com.prosperitysmp.npc;

import com.prosperitysmp.npc.economy.UtilityPrices;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class UtilityPriceConfigTest {
    @Test void absentAndShippedSettingsKeepExistingPrices() {
        assertEquals(UtilityPrices.DEFAULT, ProsperityNpcPlugin.readUtilityPrices(new YamlConfiguration()));
        var shipped = YamlConfiguration.loadConfiguration(new java.io.File("src/main/resources/config.yml"));
        assertEquals(UtilityPrices.DEFAULT, ProsperityNpcPlugin.readUtilityPrices(shipped));
    }

    @Test void customRatesAreImmutableAndMissingKeysKeepTheirDefaults() throws Exception {
        var config = new YamlConfiguration();
        config.loadFromString("utility-prices:\n  base-cents: '200'\n  per-tenant-cents: '0'\n");
        var loaded = ProsperityNpcPlugin.readUtilityPrices(config);
        assertEquals(new UtilityPrices(200, 0, 50), loaded);
        config.set("utility-prices.base-cents", "300");
        assertEquals(200, loaded.cents(4, 0));
        assertEquals(300, ProsperityNpcPlugin.readUtilityPrices(config).cents(4, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "'200'", "[]", "{base-cents: 200}", "{base-cents: '0'}",
            "{per-tenant-cents: '-1'}", "{base-cents: '1.5'}", "{base-cents: '01'}",
            "{base-cents: ' 100'}", "{base-cents: '100000000000'}", "{base-cents: '838860800'}",
            "{per-tenant-cents: '25000000000'}", "{typo: '100'}"})
    void invalidBlocksAreRejectedAsAWhole(String value) throws Exception {
        var config = new YamlConfiguration();
        config.loadFromString("utility-prices: " + value + "\n");
        assertThrows(IllegalArgumentException.class, () -> ProsperityNpcPlugin.readUtilityPrices(config));
    }
}
