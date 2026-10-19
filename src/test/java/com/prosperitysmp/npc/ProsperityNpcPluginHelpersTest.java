package com.prosperitysmp.npc;

import com.prosperitysmp.npc.economy.PurchasePrices;
import com.prosperitysmp.npc.economy.RepairPrices;
import com.prosperitysmp.npc.economy.UtilityPrices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProsperityNpcPluginHelpersTest {
    @ParameterizedTest
    @CsvSource({"false,false,false,false,false", "false,true,false,false,false", "false,false,true,false,false",
            "false,false,false,true,false", "false,true,true,true,false", "true,false,false,false,false",
            "true,true,false,false,true", "true,false,true,false,true", "true,false,false,true,true",
            "true,true,true,false,true", "true,true,false,true,true", "true,false,true,true,true", "true,true,true,true,true"})
    void scheduledWorkNeedsTheSystemSwitchAndAtLeastOneModule(boolean system, boolean economy, boolean moves,
                                                              boolean incidents, boolean expected) {
        assertEquals(expected, ProsperityNpcPlugin.hasScheduledWork(system, economy, moves, incidents));
    }

    @ParameterizedTest
    @CsvSource({"4,false,true,true", "4,true,true,false", "4,false,false,false", "4,true,false,false", "3,false,true,false",
            "5,false,true,false", "0,false,true,false", "-1,false,true,false", "6,false,true,false", "40,false,true,false"})
    void thePayoutRetirementPassRunsOnlyBeforeTheFirstPassOfTheRentPhase(int phase, boolean retired, boolean enabled, boolean expected) {
        assertEquals(expected, ProsperityNpcPlugin.retireBeforeRent(phase, retired, enabled));
    }

    @Test
    void anAbsentBillingRuntimeMeansThePhaseDoesNotRun() {
        assertFalse(ProsperityNpcPlugin.billsPhaseRuns(null));
    }

    @Test
    void anOptionalPartThatIsNotWantedIsNeverBuilt() {
        AtomicInteger built = new AtomicInteger();
        List<String> warnings = new ArrayList<>();
        assertNull(ProsperityNpcPlugin.optionalFloodgate(false, true, () -> {
            built.incrementAndGet();
            return "part";
        }, warnings::add, "Link"));
        assertEquals(0, built.get());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void anOptionalPartWithoutFloodgateIsNeverBuiltAndNothingIsWarned() {
        AtomicInteger built = new AtomicInteger();
        List<String> warnings = new ArrayList<>();
        assertNull(ProsperityNpcPlugin.optionalFloodgate(true, false, () -> {
            built.incrementAndGet();
            return "part";
        }, warnings::add, "Link"));
        assertEquals(0, built.get());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void anOptionalPartThatIsWantedAndAvailableIsBuiltOnce() {
        AtomicInteger built = new AtomicInteger();
        Object part = new Object();
        Object result = ProsperityNpcPlugin.optionalFloodgate(true, true, () -> {
            built.incrementAndGet();
            return part;
        }, line -> { throw new AssertionError(line); }, "Link");
        assertSame(part, result);
        assertEquals(1, built.get());
    }

    @Test
    void aRuntimeFailureWhileBuildingGivesNullAndOneWarningNamingTheFailureType() {
        List<String> warnings = new ArrayList<>();
        assertNull(ProsperityNpcPlugin.optionalFloodgate(true, true, () -> {
            throw new IllegalStateException("secret detail");
        }, warnings::add, "Link discovery"));
        assertEquals(List.of("Link discovery unavailable: IllegalStateException"), warnings);
    }

    @Test
    void aLinkageErrorWhileBuildingGivesNullAndOneWarningNamingTheErrorType() {
        List<String> warnings = new ArrayList<>();
        assertNull(ProsperityNpcPlugin.optionalFloodgate(true, true, () -> {
            throw new NoSuchMethodError("api changed");
        }, warnings::add, "Link discovery"));
        assertEquals(List.of("Link discovery unavailable: NoSuchMethodError"), warnings);
    }

    @Test
    void anErrorThatIsNotALinkageErrorIsNotSwallowed() {
        List<String> warnings = new ArrayList<>();
        assertThrows(OutOfMemoryError.class, () -> ProsperityNpcPlugin.optionalFloodgate(true, true, () -> {
            throw new OutOfMemoryError();
        }, warnings::add, "Link"));
        assertTrue(warnings.isEmpty());
    }

    private ProsperityNpcPlugin plugin(Path data, String yaml) {
        ProsperityNpcPlugin plugin = CoreTestSupport.plugin(data, CoreTestSupport.yaml(yaml), new ArrayList<>());
        CoreTestSupport.set(ProsperityNpcPlugin.class, plugin, "purchasePrices", PurchasePrices.DEFAULT);
        CoreTestSupport.set(ProsperityNpcPlugin.class, plugin, "utilityPrices", UtilityPrices.DEFAULT);
        CoreTestSupport.set(ProsperityNpcPlugin.class, plugin, "repairPrices", RepairPrices.DEFAULT);
        return plugin;
    }

    @Test
    void unchangedPriceSettingsGiveNoReloadNotice(@TempDir Path data) {
        ProsperityNpcPlugin plugin = plugin(data, "other: 1\n");
        assertNull(plugin.purchasePriceReloadMessage());
        assertNull(plugin.utilityPriceReloadMessage());
        assertNull(plugin.repairPriceReloadMessage());
    }

    @Test
    void aChangedOwnerPriceNeedsARestartAndLeavesTheActivePricesAlone(@TempDir Path data) {
        ProsperityNpcPlugin plugin = plugin(data, "owner-prices:\n  maintenance-cents: '1750'\n");
        assertEquals("owner-prices changed; a full server restart is required. Active prices are unchanged.",
                plugin.purchasePriceReloadMessage());
        assertSame(PurchasePrices.DEFAULT, plugin.purchasePrices());
        assertNull(plugin.utilityPriceReloadMessage());
        assertNull(plugin.repairPriceReloadMessage());
    }

    @Test
    void aChangedUtilityPriceNeedsARestartAndLeavesTheActivePricesAlone(@TempDir Path data) {
        ProsperityNpcPlugin plugin = plugin(data, "utility-prices:\n  base-cents: '200'\n");
        assertEquals("utility-prices changed; a full server restart is required. Active prices are unchanged.",
                plugin.utilityPriceReloadMessage());
        assertSame(UtilityPrices.DEFAULT, plugin.utilityPrices());
        assertNull(plugin.purchasePriceReloadMessage());
    }

    @Test
    void aChangedRepairPriceNeedsARestartAndLeavesTheActivePricesAlone(@TempDir Path data) {
        ProsperityNpcPlugin plugin = plugin(data, "repair-prices:\n  pests-cents: '3500'\n");
        assertEquals("repair-prices changed; a full server restart is required. Active prices are unchanged.",
                plugin.repairPriceReloadMessage());
        assertSame(RepairPrices.DEFAULT, plugin.repairPrices());
        assertNull(plugin.utilityPriceReloadMessage());
    }

    @Test
    void aRejectedPriceBlockIsReportedAsRejectedNotAsChanged(@TempDir Path data) {
        ProsperityNpcPlugin plugin = plugin(data, "owner-prices:\n  maintenance-cents: 1750\nutility-prices: true\nrepair-prices:\n  pests-cents: '0'\n");
        assertEquals("owner-prices rejected; fix the settings before restarting. Active prices are unchanged.",
                plugin.purchasePriceReloadMessage());
        assertEquals("utility-prices rejected; fix the settings before restarting. Active prices are unchanged.",
                plugin.utilityPriceReloadMessage());
        assertEquals("repair-prices rejected; fix the settings before restarting. Active prices are unchanged.",
                plugin.repairPriceReloadMessage());
    }

    @Test
    void revertingAChangedPriceToTheActiveValueClearsTheNotice(@TempDir Path data) {
        var config = CoreTestSupport.yaml("utility-prices:\n  base-cents: '200'\n");
        ProsperityNpcPlugin plugin = CoreTestSupport.plugin(data, config, new ArrayList<>());
        CoreTestSupport.set(ProsperityNpcPlugin.class, plugin, "utilityPrices", UtilityPrices.DEFAULT);
        assertTrue(plugin.utilityPriceReloadMessage().startsWith("utility-prices changed"));
        config.set("utility-prices", null);
        assertNull(plugin.utilityPriceReloadMessage());
    }
}
