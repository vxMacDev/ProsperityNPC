package com.prosperitysmp.npc.config;

import com.prosperitysmp.npc.CoreTestSupport;
import com.prosperitysmp.npc.ProsperityNpcPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyLimitsTest {
    private static final List<String> KEYS = List.of("server-daily-cap-cents", "benefit-unit-daily-cap-cents",
            "property-daily-cap-cents", "tenant-daily-cap-cents", "appreciation-daily-cap-cents",
            "centralbank-floor-cents", "property-seed-cents", "rollout-seed-pool-cents");

    private final List<String> log = new ArrayList<>();

    private static String block(String omit, String key, String value) {
        StringBuilder text = new StringBuilder("economy:\n");
        int number = 1;
        for (String each : KEYS) {
            if (!each.equals(omit)) text.append("  ").append(each).append(": ").append(each.equals(key) ? value : String.valueOf(number)).append('\n');
            number++;
        }
        if (!"lifetime-sink-funding-ratio".equals(omit)) {
            text.append("  lifetime-sink-funding-ratio: ").append("lifetime-sink-funding-ratio".equals(key) ? value : "0.5").append('\n');
        }
        return text.toString();
    }

    private ProsperityNpcPlugin plugin(Path data, String yaml) {
        return CoreTestSupport.plugin(data, CoreTestSupport.yaml(yaml), log);
    }

    @Test
    void disabledDefaultsHoldEveryCapAtZeroAndTheReserveFloorAtItsMaximum() {
        EconomyLimits limits = EconomyLimits.disabledDefaults();
        assertEquals(0L, limits.serverDailyCapCents());
        assertEquals(0L, limits.benefitUnitDailyCapCents());
        assertEquals(0L, limits.propertyDailyCapCents());
        assertEquals(0L, limits.tenantDailyCapCents());
        assertEquals(0L, limits.appreciationDailyCapCents());
        assertEquals(Long.MAX_VALUE, limits.centralBankFloorCents());
        assertEquals(0L, limits.propertySeedCents());
        assertEquals(0L, limits.rolloutSeedPoolCents());
        assertEquals(0.0, limits.lifetimeSinkFundingRatio());
    }

    @Test
    void eachSettingIsReadFromItsOwnKey(@TempDir Path data) {
        EconomyLimits limits = EconomyLimits.load(plugin(data, block(null, null, null)));
        assertEquals(1L, limits.serverDailyCapCents());
        assertEquals(2L, limits.benefitUnitDailyCapCents());
        assertEquals(3L, limits.propertyDailyCapCents());
        assertEquals(4L, limits.tenantDailyCapCents());
        assertEquals(5L, limits.appreciationDailyCapCents());
        assertEquals(6L, limits.centralBankFloorCents());
        assertEquals(7L, limits.propertySeedCents());
        assertEquals(8L, limits.rolloutSeedPoolCents());
        assertEquals(0.5, limits.lifetimeSinkFundingRatio());
    }

    @Test
    void theShippedConfigLoadsWithTheDocumentedEnvelope(@TempDir Path data) {
        var shipped = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        EconomyLimits limits = EconomyLimits.load(CoreTestSupport.plugin(data, shipped, log));
        assertEquals(15000L, limits.serverDailyCapCents());
        assertEquals(4500L, limits.benefitUnitDailyCapCents());
        assertEquals(1500L, limits.propertyDailyCapCents());
        assertEquals(600L, limits.tenantDailyCapCents());
        assertEquals(300L, limits.appreciationDailyCapCents());
        assertEquals(3_500_000L, limits.centralBankFloorCents());
        assertEquals(5000L, limits.propertySeedCents());
        assertEquals(50_000L, limits.rolloutSeedPoolCents());
        assertEquals(0.90, limits.lifetimeSinkFundingRatio());
    }

    @Test
    void aMissingEconomySectionIsRefused(@TempDir Path data) {
        var failure = assertThrows(IllegalStateException.class, () -> EconomyLimits.load(plugin(data, "switches:\n  system-enabled: true\n")));
        assertEquals("missing 'economy' section in config.yml", failure.getMessage());
    }

    @Test
    void anEconomyValueThatIsNotASectionIsRefused(@TempDir Path data) {
        assertThrows(IllegalStateException.class, () -> EconomyLimits.load(plugin(data, "economy: 15000\n")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"server-daily-cap-cents", "benefit-unit-daily-cap-cents", "property-daily-cap-cents",
            "tenant-daily-cap-cents", "appreciation-daily-cap-cents", "centralbank-floor-cents", "property-seed-cents",
            "rollout-seed-pool-cents"})
    void aMissingCapIsRefusedByName(String key, @TempDir Path data) {
        var failure = assertThrows(IllegalStateException.class, () -> EconomyLimits.load(plugin(data, block(key, null, null))));
        assertEquals(key + " must be a non-negative integer (cents), got -1", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"server-daily-cap-cents", "benefit-unit-daily-cap-cents", "property-daily-cap-cents",
            "tenant-daily-cap-cents", "appreciation-daily-cap-cents", "centralbank-floor-cents", "property-seed-cents",
            "rollout-seed-pool-cents"})
    void aNegativeCapIsRefusedByNameAndValue(String key, @TempDir Path data) {
        var failure = assertThrows(IllegalStateException.class, () -> EconomyLimits.load(plugin(data, block(null, key, "-5"))));
        assertEquals(key + " must be a non-negative integer (cents), got -5", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"server-daily-cap-cents", "benefit-unit-daily-cap-cents", "property-daily-cap-cents",
            "tenant-daily-cap-cents", "appreciation-daily-cap-cents", "centralbank-floor-cents", "property-seed-cents",
            "rollout-seed-pool-cents"})
    void zeroIsAValidCap(String key, @TempDir Path data) {
        EconomyLimits limits = EconomyLimits.load(plugin(data, block(null, key, "0")));
        long total = limits.serverDailyCapCents() + limits.benefitUnitDailyCapCents() + limits.propertyDailyCapCents()
                + limits.tenantDailyCapCents() + limits.appreciationDailyCapCents() + limits.centralBankFloorCents()
                + limits.propertySeedCents() + limits.rolloutSeedPoolCents();
        assertEquals(36L - (KEYS.indexOf(key) + 1), total);
    }

    @Test
    void aLargeFloorIsKeptExactly(@TempDir Path data) {
        EconomyLimits limits = EconomyLimits.load(plugin(data, block(null, "centralbank-floor-cents", "9007199254740993")));
        assertEquals(9_007_199_254_740_993L, limits.centralBankFloorCents());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1.01", "-1", "2", "1.0000001"})
    void aRatioOutsideZeroToOneIsRefused(String ratio, @TempDir Path data) {
        var failure = assertThrows(IllegalStateException.class,
                () -> EconomyLimits.load(plugin(data, block(null, "lifetime-sink-funding-ratio", ratio))));
        assertTrue(failure.getMessage().startsWith("lifetime-sink-funding-ratio must be within [0,1], got "), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0", "1.0", "0.9", "0.0001", "0.9999"})
    void aRatioFromZeroToOneInclusiveIsAccepted(String ratio, @TempDir Path data) {
        EconomyLimits limits = EconomyLimits.load(plugin(data, block(null, "lifetime-sink-funding-ratio", ratio)));
        assertEquals(Double.parseDouble(ratio), limits.lifetimeSinkFundingRatio());
    }

    @Test
    void aMissingRatioIsRefused(@TempDir Path data) {
        var failure = assertThrows(IllegalStateException.class,
                () -> EconomyLimits.load(plugin(data, block("lifetime-sink-funding-ratio", null, null))));
        assertEquals("lifetime-sink-funding-ratio must be within [0,1], got -1.0", failure.getMessage());
    }

    @Test
    void anInfiniteRatioIsRefused(@TempDir Path data) {
        assertThrows(IllegalStateException.class,
                () -> EconomyLimits.load(plugin(data, block(null, "lifetime-sink-funding-ratio", ".inf"))));
    }

    @Test
    void aNaNRatioIsRefused(@TempDir Path data) {
        assertThrows(IllegalStateException.class,
                () -> EconomyLimits.load(plugin(data, block(null, "lifetime-sink-funding-ratio", ".nan"))));
    }

    @Test
    void aBadReloadFallsBackToTheFailClosedDefaultsAndLogsWhy(@TempDir Path data) {
        ProsperityNpcPlugin plugin = plugin(data, block(null, "tenant-daily-cap-cents", "-5"));
        plugin.reloadEconomyLimits();
        EconomyLimits limits = plugin.economyLimits();
        assertEquals(0L, limits.serverDailyCapCents());
        assertEquals(Long.MAX_VALUE, limits.centralBankFloorCents());
        assertEquals(List.of("SEVERE: Invalid economy limits in config.yml: tenant-daily-cap-cents must be a non-negative integer"
                + " (cents), got -5: using STANDBY defaults (all caps 0)."), log);
    }

    @Test
    void aGoodReloadReplacesTheFailClosedDefaultsAfterTheConfigIsFixed(@TempDir Path data) {
        var config = CoreTestSupport.yaml(block(null, "server-daily-cap-cents", "-1"));
        ProsperityNpcPlugin plugin = CoreTestSupport.plugin(data, config, log);
        plugin.reloadEconomyLimits();
        EconomyLimits failed = plugin.economyLimits();
        assertEquals(0L, failed.serverDailyCapCents());
        config.set("economy.server-daily-cap-cents", 12345);
        plugin.reloadEconomyLimits();
        assertEquals(12345L, plugin.economyLimits().serverDailyCapCents());
        assertEquals(1, log.size());
    }

    @Test
    void aGoodReloadIsAFreshSnapshotAndTheEarlierOneIsUnchanged(@TempDir Path data) {
        var config = CoreTestSupport.yaml(block(null, null, null));
        ProsperityNpcPlugin plugin = CoreTestSupport.plugin(data, config, log);
        plugin.reloadEconomyLimits();
        EconomyLimits first = plugin.economyLimits();
        config.set("economy.property-daily-cap-cents", 999);
        plugin.reloadEconomyLimits();
        assertEquals(3L, first.propertyDailyCapCents());
        assertEquals(999L, plugin.economyLimits().propertyDailyCapCents());
        assertTrue(log.isEmpty());
        assertSame(plugin.economyLimits(), plugin.economyLimits());
    }
}
