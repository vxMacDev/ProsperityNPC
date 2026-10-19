package com.prosperitysmp.npc.charge;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChargeVariablesTest {
    @Test void theHomeFactsAndTheirRangesAreFixedAndOrdered() {
        assertEquals(List.of("capacity", "tenants", "upgrade_levels", "advertising_level", "facilities_level", "condition",
                "asking_rent_cents", "reference_rent_cents", "rent_paid_cents", "owner_homes"),
                List.copyOf(ChargeVariables.HOME.keySet()));
        assertEquals(new ChargeVariables.Range(1, 4), ChargeVariables.HOME.get("capacity"));
        assertEquals(new ChargeVariables.Range(0, 4), ChargeVariables.HOME.get("tenants"));
        assertEquals(new ChargeVariables.Range(0, 6), ChargeVariables.HOME.get("upgrade_levels"));
        assertEquals(new ChargeVariables.Range(0, 3), ChargeVariables.HOME.get("advertising_level"));
        assertEquals(new ChargeVariables.Range(0, 3), ChargeVariables.HOME.get("facilities_level"));
        assertEquals(new ChargeVariables.Range(0, 100), ChargeVariables.HOME.get("condition"));
        assertEquals(new ChargeVariables.Range(0, 1_000_000), ChargeVariables.HOME.get("asking_rent_cents"));
        assertEquals(new ChargeVariables.Range(0, 1_000_000), ChargeVariables.HOME.get("reference_rent_cents"));
        assertEquals(new ChargeVariables.Range(0, 4_000_000), ChargeVariables.HOME.get("rent_paid_cents"));
        assertEquals(new ChargeVariables.Range(0, 10_000), ChargeVariables.HOME.get("owner_homes"));
    }

    @Test void theHomeMapCannotBeChanged() {
        assertThrows(UnsupportedOperationException.class, () -> ChargeVariables.HOME.put("units", new ChargeVariables.Range(0, 1)));
        assertThrows(UnsupportedOperationException.class, () -> ChargeVariables.HOME.remove("tenants"));
    }

    @Test void theDerivableFactsAreTheFiveCounts() {
        assertEquals(Set.of("tenants", "upgrade_levels", "advertising_level", "facilities_level", "capacity"), ChargeVariables.DERIVABLE);
        assertTrue(ChargeVariables.HOME.keySet().containsAll(ChargeVariables.DERIVABLE));
    }

    @Test void theMeterNamesAndCeilingsAreFixed() {
        assertEquals("units", ChargeVariables.UNITS);
        assertEquals("powered", ChargeVariables.POWERED);
        assertEquals(1_000_000L, ChargeVariables.MAX_RENT_CENTS);
        assertEquals(1_000_000_000L, ChargeVariables.MAX_METER_UNITS);
        assertEquals(10_000L, ChargeVariables.MAX_OWNER_HOMES);
        assertFalse(ChargeVariables.HOME.containsKey(ChargeVariables.UNITS));
        assertFalse(ChargeVariables.HOME.containsKey(ChargeVariables.POWERED));
    }

    @Test void aRangeIncludesBothEnds() {
        var range = new ChargeVariables.Range(2, 5);
        assertFalse(range.contains(1));
        assertTrue(range.contains(2));
        assertTrue(range.contains(5));
        assertFalse(range.contains(6));
        assertTrue(new ChargeVariables.Range(4, 4).contains(4));
        assertTrue(new ChargeVariables.Range(Long.MIN_VALUE, Long.MAX_VALUE).contains(Long.MIN_VALUE));
    }

    @Test void aRangeWithItsLowAboveItsHighIsRefused() {
        var failure = assertThrows(IllegalArgumentException.class, () -> new ChargeVariables.Range(5, 4));
        assertEquals("range low above high", failure.getMessage());
    }
}
