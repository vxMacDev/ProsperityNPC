package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The surface codes are stored in the database and in captured terrain, so the numbers must never move. */
class NavSurfaceTest {

    @ParameterizedTest
    @CsvSource({"UNKNOWN,0", "SIDEWALK,1", "ACCESS,2", "ROAD,3", "CROSSING,4"})
    void everySurfaceKeepsItsStoredCode(NavSurface surface, int code) {
        assertEquals(code, surface.code());
        assertSame(surface, NavSurface.fromCode(code));
    }

    @Test
    void theFiveCodesAreDistinctAndFormTheRangeZeroToFour() {
        Set<Integer> codes = new HashSet<>();
        for (NavSurface surface : NavSurface.values()) {
            codes.add(surface.code());
        }
        assertEquals(Set.of(0, 1, 2, 3, 4), codes);
        assertEquals(5, NavSurface.values().length);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 5, 6, 7, 255, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void aCodeNoSurfaceUsesIsRefusedAndNamed(int code) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> NavSurface.fromCode(code));
        assertEquals("unknown nav surface code " + code, refused.getMessage());
    }

    @ParameterizedTest
    @CsvSource({"UNKNOWN,false", "SIDEWALK,false", "ACCESS,false", "ROAD,true", "CROSSING,true"})
    void onlyRoadAndCrossingCountAsStreet(NavSurface surface, boolean street) {
        assertEquals(street, surface.isStreet());
    }
}
