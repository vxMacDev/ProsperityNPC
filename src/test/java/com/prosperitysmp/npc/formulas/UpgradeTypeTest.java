package com.prosperitysmp.npc.formulas;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class UpgradeTypeTest {
    @ParameterizedTest(name = "{0} level {1}")
    @CsvSource({
            "ADVERTISING, 1, 10000", "ADVERTISING, 2, 25000", "ADVERTISING, 3, 50000",
            "FACILITIES, 1, 15000", "FACILITIES, 2, 35000", "FACILITIES, 3, 70000"})
    void eachLevelHasItsFixedCost(UpgradeType type, int level, long cents) {
        assertEquals(cents, type.costToLevelCents(level));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, -1, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void aLevelOutsideOneToThreeIsRefused(int level) {
        for (UpgradeType type : UpgradeType.values()) {
            var failure = assertThrows(IllegalArgumentException.class, () -> type.costToLevelCents(level));
            assertEquals("target level must be 1..3, got " + level, failure.getMessage());
        }
    }

    @ParameterizedTest
    @CsvSource({"advertising, ADVERTISING", "ADVERTISING, ADVERTISING", "Facilities, FACILITIES", "fAcIlItIeS, FACILITIES"})
    void keysResolveIgnoringCase(String key, UpgradeType expected) {
        assertEquals(expected, UpgradeType.fromKey(key));
    }

    @Test void unknownKeysResolveToNothing() {
        assertNull(UpgradeType.fromKey(null));
        assertNull(UpgradeType.fromKey(""));
        assertNull(UpgradeType.fromKey("x"));
        assertNull(UpgradeType.fromKey("advertising "));
        assertNull(UpgradeType.fromKey("advert"));
    }

    @Test void theUpgradeListAndTopLevelAreFixed() {
        assertEquals(List.of(UpgradeType.ADVERTISING, UpgradeType.FACILITIES), List.of(UpgradeType.values()));
        assertEquals(3, UpgradeType.MAX_LEVEL);
    }
}
