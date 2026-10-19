package com.prosperitysmp.npc.charge.meter;

import com.prosperitysmp.npc.charge.FormulaException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DerivedMeterTest {
    private static String refusal(String variable, Map<String, Long> facts) {
        return assertThrows(FormulaException.class, () -> DerivedMeter.units(variable, facts)).getMessage();
    }

    @ParameterizedTest(name = "{0} at {1}")
    @CsvSource({
            "tenants, 0", "tenants, 4",
            "upgrade_levels, 0", "upgrade_levels, 6",
            "advertising_level, 0", "advertising_level, 3",
            "facilities_level, 0", "facilities_level, 3",
            "capacity, 1", "capacity, 4"})
    void aDerivableFactInsideItsRangeIsTheUnitCount(String variable, long value) {
        assertEquals(value, DerivedMeter.units(variable, Map.of(variable, value)));
    }

    @ParameterizedTest(name = "{0} at {1}")
    @CsvSource({
            "tenants, -1", "tenants, 5",
            "upgrade_levels, 7",
            "advertising_level, 4",
            "facilities_level, -1",
            "capacity, 0", "capacity, 5"})
    void aFactOutsideItsRangeIsRefused(String variable, long value) {
        assertEquals("'" + variable + "' is outside its range", refusal(variable, Map.of(variable, value)));
    }

    @Test void aMissingFactIsRefused() {
        assertEquals("no value for 'tenants'", refusal("tenants", Map.of("capacity", 2L)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"condition", "asking_rent_cents", "owner_homes", "units", "powered", "TENANTS", ""})
    void onlyTheFiveCountableFactsCanBeDerived(String variable) {
        assertEquals("cannot derive units from '" + variable + "'", refusal(variable, Map.of(variable, 1L)));
    }

    @Test void theMeterIdPrefixesAndTheFlatMeterAreFixed() {
        assertEquals("derived:", DerivedMeter.PREFIX);
        assertEquals("flat", FlatMeter.ID);
        assertEquals(1L, FlatMeter.units());
    }
}
