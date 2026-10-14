package com.prosperitysmp.npc.property;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoluntaryDepartureConfigTest {
    @Test void shippedConfigKeepsVoluntaryDeparturesOffWithCapsInsideTheRules() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(Boolean.FALSE, config.get("housing.voluntary-departures-enabled"));
            int limit = config.getInt("housing.voluntary-departure-server-daily-limit", -1);
            assertTrue(limit >= 0 && limit <= VoluntaryDepartureRepository.MAX_SERVER_DAILY);
            assertEquals(7, config.getInt("housing.cooldown-days"));
            assertDoesNotThrow(() -> new VoluntaryDepartureRepository.Policy(config.getBoolean("housing.moods-enabled"), limit,
                    config.getInt("housing.cooldown-days"), config.getInt("housing.voluntary-departure-satisfaction-below"),
                    config.getInt("housing.voluntary-departure-condition-below")));
        }
    }
}
