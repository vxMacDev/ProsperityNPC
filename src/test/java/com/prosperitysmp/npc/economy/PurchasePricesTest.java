package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.charge.ChargeCatalogue;
import com.prosperitysmp.npc.formulas.PropertyFormulas;
import com.prosperitysmp.npc.formulas.UpgradeType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PurchasePricesTest {
    @Test void absentPricesKeepEveryExistingMaintenanceAndUpgradePrice() {
        var prices = PurchasePrices.load(Map.of());
        assertEquals(PurchasePrices.DEFAULT, prices);
        assertEquals(PropertyFormulas.MAINTAIN_CENTS, prices.maintenanceCents());
        for (UpgradeType type : UpgradeType.values())
            for (int level = 1; level <= UpgradeType.MAX_LEVEL; level++)
                assertEquals(type.costToLevelCents(level), prices.upgradeCents(type, level));
    }

    @Test void insurancePricesAreStrictCapacityPricesWithUnchangedDefaults() throws Exception {
        for (int capacity = 1; capacity <= 4; capacity++)
            assertEquals(PropertyFormulas.insurancePriceCents(capacity), PurchasePrices.DEFAULT.insuranceCents(capacity));
        var yaml = new YamlConfiguration();
        yaml.loadFromString("owner-prices:\n  insurance-cents: [\"1300\", \"1777\", \"1900\", \"2500\"]\n");
        var prices = PurchasePrices.load(ChargeCatalogue.plain(yaml.getConfigurationSection("owner-prices")));
        assertEquals(List.of(1300L, 1777L, 1900L, 2500L), prices.insuranceCents());
        assertEquals(1777, prices.insuranceCents(2));
        assertEquals(PurchasePrices.DEFAULT.maintenanceCents(), prices.maintenanceCents());
        assertThrows(IllegalArgumentException.class, () -> prices.insuranceCents(0));
        assertThrows(IllegalArgumentException.class, () -> prices.insuranceCents(5));
        for (Object bad : List.of("1777", List.of("1400", "1600", "1800"), List.of("1400", 1600, "1800", "2000"),
                List.of("1400", "0", "1800", "2000"), List.of("1400", "838860800", "1800", "2000")))
            assertThrows(IllegalArgumentException.class, () -> PurchasePrices.load(Map.of("insurance-cents", bad)));
    }

    @Test void insurancePriceSnapshotsDoNotKeepCallerLists() {
        var values = new ArrayList<>(List.of(1400L, 1777L, 1800L, 2000L));
        var prices = new PurchasePrices(1000, PurchasePrices.DEFAULT.upgrades(), 1000, values);
        values.set(1, 1L);
        assertEquals(1777, prices.insuranceCents(2));
        assertThrows(UnsupportedOperationException.class, () -> prices.insuranceCents().set(1, 1L));
    }

    @Test void evictionHasAnIndependentStrictPriceAndKeepsExistingDefaults() {
        var prices = PurchasePrices.load(Map.of("eviction-cents", "2375"));
        assertEquals(2375, prices.evictionCents());
        assertEquals(PropertyFormulas.EVICTION_CENTS, PurchasePrices.DEFAULT.evictionCents());
        assertEquals(PurchasePrices.DEFAULT.maintenanceCents(), prices.maintenanceCents());
        assertEquals(PurchasePrices.DEFAULT.upgrades(), prices.upgrades());
        for (Object bad : List.of("0", "050", "1.5", "838860800", "100000000000", 2375, true))
            assertThrows(IllegalArgumentException.class, () -> PurchasePrices.load(Map.of("eviction-cents", bad)));
        assertThrows(IllegalArgumentException.class,
                () -> new PurchasePrices(1000, prices.upgrades(), -1));
    }

    @Test void aPartialOverrideLeavesOtherPricesAlone() {
        var prices = PurchasePrices.load(Map.of("maintenance-cents", "1750", "upgrades",
                Map.of("advertising", List.of("13700", "27000", "52000"))));
        assertEquals(1750, prices.maintenanceCents());
        assertEquals(13700, prices.upgradeCents(UpgradeType.ADVERTISING, 1));
        assertEquals(27000, prices.upgradeCents(UpgradeType.ADVERTISING, 2));
        assertEquals(52000, prices.upgradeCents(UpgradeType.ADVERTISING, 3));
        assertEquals(35000, prices.upgradeCents(UpgradeType.FACILITIES, 2));
        assertEquals(1000, PurchasePrices.DEFAULT.maintenanceCents());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "050", "1.0", "1e3", "0x10", "+10", " 10", "10 ", "NaN",
            "100000000000", "9223372036854775807", "１０００"})
    void malformedOrUnsupportedAmountsRejectTheBlock(String value) {
        var bad = assertThrows(IllegalArgumentException.class,
                () -> PurchasePrices.load(Map.of("maintenance-cents", value)));
        assertTrue(bad.getMessage().contains("owner-prices.maintenance-cents"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"050", "0x10", "1:30", "0.02", "1000", "true"})
    void yamlNumbersAndOtherTypesCannotSilentlyChangeThePrice(String value) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("owner-prices:\n  maintenance-cents: " + value + "\n");
        var section = ChargeCatalogue.plain(yaml.getConfigurationSection("owner-prices"));
        assertThrows(IllegalArgumentException.class, () -> PurchasePrices.load(section));
    }

    @Test void bukkitTreatsANullSettingAsAbsent() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("owner-prices:\n  maintenance-cents: null\n");
        var section = ChargeCatalogue.plain(yaml.getConfigurationSection("owner-prices"));
        assertFalse(section.containsKey("maintenance-cents"));
        assertEquals(PurchasePrices.DEFAULT, PurchasePrices.load(section));
    }

    @Test void quotedYamlAndNestedListsLoadWithoutAServer() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.loadFromString("""
                owner-prices:
                  maintenance-cents: "1750"
                  upgrades:
                    advertising: ["13700", "27000", "52000"]
                """);
        var prices = PurchasePrices.load(ChargeCatalogue.plain(yaml.getConfigurationSection("owner-prices")));
        assertEquals(1750, prices.maintenanceCents());
        assertEquals(13700, prices.upgradeCents(UpgradeType.ADVERTISING, 1));
    }

    @Test void unknownKeysMissingPricesAndBadUpgradeListsAreRejected() {
        for (Map<String, ?> block : List.<Map<String, ?>>of(
                Map.of("maintain-cents", "1750"),
                Map.of("upgrades", "13700"),
                Map.of("upgrades", Map.of("advertizing", List.of("1", "2", "3"))),
                Map.of("upgrades", Map.of("advertising", List.of("1", "2"))),
                Map.of("upgrades", Map.of("advertising", List.of("1", "2", "3", "4"))),
                Map.of("upgrades", Map.of("advertising", List.of("1", 2, "3")))))
            assertThrows(IllegalArgumentException.class, () -> PurchasePrices.load(block));
        var missing = new HashMap<String, Object>();
        missing.put("maintenance-cents", null);
        assertThrows(IllegalArgumentException.class, () -> PurchasePrices.load(missing));
        missing.clear(); missing.put(null, "1000");
        assertThrows(IllegalArgumentException.class, () -> PurchasePrices.load(missing));
    }

    @Test void callerMutationCannotRepriceASnapshot() {
        var costs = new ArrayList<>(List.of(10000L, 25000L, 50000L));
        var input = new EnumMap<UpgradeType, List<Long>>(PurchasePrices.DEFAULT.upgrades());
        input.put(UpgradeType.ADVERTISING, costs);
        var prices = new PurchasePrices(1000, input);
        costs.set(0, 1L);
        input.clear();
        assertEquals(10000, prices.upgradeCents(UpgradeType.ADVERTISING, 1));
        assertThrows(UnsupportedOperationException.class, () -> prices.upgrades().clear());
        assertThrows(UnsupportedOperationException.class, () -> prices.upgrades().get(UpgradeType.ADVERTISING).set(0, 1L));
    }

    @ParameterizedTest
    @ValueSource(longs = {838_860_800L, 2_200_000_000L, 99_999_999_999L})
    void configRejectsAmountsTheTransferGuardCannotRepresent(long cents) {
        assertTrue(TransferProof.toCents(cents / 100.0).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> PurchasePrices.load(Map.of("maintenance-cents", Long.toString(cents))));
        assertThrows(IllegalArgumentException.class, () -> PurchasePrices.load(Map.of("upgrades", Map.of(
                "advertising", List.of(Long.toString(cents), "25000", "50000")))));
    }

    @Test void directSnapshotsAndLevelsHaveTheSameBoundsAsConfig() {
        long largestPayment = 838_860_799L;
        assertEquals(largestPayment, PurchasePrices.load(Map.of("maintenance-cents",
                Long.toString(largestPayment))).maintenanceCents());
        for (long cents : new long[]{0, -1, Long.MAX_VALUE, PurchasePrices.MAX_CENTS + 1})
            assertThrows(IllegalArgumentException.class, () -> new PurchasePrices(cents, PurchasePrices.DEFAULT.upgrades()));
        assertThrows(IllegalArgumentException.class, () -> new PurchasePrices(1000, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> PurchasePrices.DEFAULT.upgradeCents(UpgradeType.ADVERTISING, 0));
        assertThrows(IllegalArgumentException.class, () -> PurchasePrices.DEFAULT.upgradeCents(UpgradeType.ADVERTISING, 4));
    }
}
