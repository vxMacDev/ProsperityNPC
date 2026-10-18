package com.prosperitysmp.npc;

import com.prosperitysmp.npc.economy.PurchasePrices;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PurchasePriceConfigTest {
    @Test void absentAndShippedPricesKeepDefaults() throws Exception {
        assertEquals(PurchasePrices.DEFAULT, ProsperityNpcPlugin.readPurchasePrices(new YamlConfiguration()));
        var config = YamlConfiguration.loadConfiguration(new java.io.File("src/main/resources/config.yml"));
        assertEquals(PurchasePrices.DEFAULT, ProsperityNpcPlugin.readPurchasePrices(config));
    }

    @Test void customPricesAreFrozenAndInvalidSettingsReject() throws Exception {
        var config = new YamlConfiguration();
        config.loadFromString("owner-prices:\n  maintenance-cents: '1750'\n");
        var loaded = ProsperityNpcPlugin.readPurchasePrices(config);
        config.set("owner-prices.maintenance-cents", "2500");
        assertEquals(1750, loaded.maintenanceCents());
        assertEquals(2500, ProsperityNpcPlugin.readPurchasePrices(config).maintenanceCents());
        config.set("owner-prices.maintenance-cents", 2500);
        assertThrows(IllegalArgumentException.class, () -> ProsperityNpcPlugin.readPurchasePrices(config));
        config.set("owner-prices", "1750");
        assertThrows(IllegalArgumentException.class, () -> ProsperityNpcPlugin.readPurchasePrices(config));
    }
}
