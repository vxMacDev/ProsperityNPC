package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.formulas.IncidentType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RepairPricesTest {
    @ParameterizedTest
    @EnumSource(IncidentType.class)
    void shippedPricesMatchExistingIncidentTypes(IncidentType type) {
        assertEquals(type.cashRepairCents(), RepairPrices.DEFAULT.cents(type));
        assertEquals(RepairPrices.DEFAULT, RepairPrices.load(Map.of()));
    }

    @Test void sparseSettingsKeepDefaultsAndDoNotRetainTheInputMap() {
        var settings = new HashMap<String, Object>();
        settings.put("pests-cents", "3500");
        var loaded = RepairPrices.load(settings);
        assertEquals(new RepairPrices(3500, 3500, 5000), loaded);
        settings.put("pests-cents", "2399");
        assertEquals(3500, loaded.cents(IncidentType.PESTS));
        assertEquals(2399, RepairPrices.load(settings).cents(IncidentType.PESTS));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2375, 100000000})
    void acceptedBoundsApplyToEveryType(long cents) {
        var prices = RepairPrices.load(Map.of("pests-cents", Long.toString(cents),
                "plumbing-cents", Long.toString(cents), "break-in-cents", Long.toString(cents)));
        for (IncidentType type : IncidentType.values()) assertEquals(cents, prices.cents(type));
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0, 100000001, Long.MAX_VALUE})
    void constructorRejectsAnInvalidPriceInAnyPosition(long cents) {
        assertThrows(IllegalArgumentException.class, () -> new RepairPrices(cents, 3500, 5000));
        assertThrows(IllegalArgumentException.class, () -> new RepairPrices(2000, cents, 5000));
        assertThrows(IllegalArgumentException.class, () -> new RepairPrices(2000, 3500, cents));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0", "-1", "01", "+1", " 2000", "2000 ", "20.00", "2e3", "100000001", "9223372036854775807"})
    void malformedOrExcessiveAmountsRejectTheWholeBlock(String text) {
        assertThrows(IllegalArgumentException.class, () -> RepairPrices.load(Map.of("pests-cents", "2375", "plumbing-cents", text)));
    }

    @Test void unknownKeysNullAndNumericValuesCannotBecomeFallbackPrices() {
        assertThrows(IllegalArgumentException.class, () -> RepairPrices.load(Map.of("pest-cents", "2375")));
        assertThrows(IllegalArgumentException.class, () -> RepairPrices.load(Map.of("pests-cents", 2375)));
        var settings = new HashMap<String, Object>();
        settings.put("pests-cents", null);
        assertThrows(IllegalArgumentException.class, () -> RepairPrices.load(settings));
        settings.clear(); settings.put(null, "2375");
        assertThrows(IllegalArgumentException.class, () -> RepairPrices.load(settings));
    }
}
