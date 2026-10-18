package com.prosperitysmp.npc.sim;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed rolls: any change to the hash layout, the separator or the reduction shows up as a changed literal. */
class DailyRollTest {
    private static final UUID P = new UUID(0, 1);

    @ParameterizedTest(name = "seed {0}, {1}, {2}, bound {3} = {4}")
    @CsvSource(delimiter = '|', value = {
            "12345|2026-07-18|incident-occurs|10000|3562",
            "12345|2026-07-18|incident-occurs|100|62",
            "12345|2026-07-18|incident-occurs|1000003|525441",
            "12345|2026-07-18|incident-type|1000003|764074",
            "12345|2026-07-18|occupancy-fill|10000|9052",
            "12345|2026-07-18|occupancy-leave|10000|1431",
            "12345|2026-07-19|incident-occurs|10000|6988",
            "0|2026-07-18|incident-occurs|10000|3555",
            "-1|2026-07-18|occupancy-leave|10000|8113",
            "12345|2026-07-18|voluntary-leave-resident|3|0",
            "12345|2026-07-18|voluntary-leave-resident|7|3"})
    void goldenRolls(long seed, String day, String purpose, int bound, int expected) {
        assertEquals(expected, new DailyRoll(seed).roll(P, day, purpose, bound));
    }

    @Test void emptyDayAndPurposeStillHashTheSeedAndProperty() {
        assertEquals(335_523_183, new DailyRoll(12345L).roll(P, "", "", Integer.MAX_VALUE));
    }

    @Test void theSeparatorKeepsDayAndPurposeApart() {
        DailyRoll roll = new DailyRoll(1L);
        assertEquals(829_183_150, roll.roll(P, "ab", "c", Integer.MAX_VALUE));
        assertEquals(1_565_181_682, roll.roll(P, "a", "bc", Integer.MAX_VALUE));
    }

    @Test void aNegativeHashIsReducedWithFloorModNeverANegativeRoll() {
        assertEquals(209, new DailyRoll(12345L).roll(P, "d2", "x", 1_000));
    }

    @Test void aBoundOfOneIsAlwaysZero() {
        DailyRoll roll = new DailyRoll(77L);
        for (int i = 0; i < 1_000; i++) assertEquals(0, roll.roll(new UUID(i, -i), "day-" + i, "p" + i, 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void aBoundThatIsNotPositiveIsRefused(int bound) {
        var refusal = assertThrows(IllegalArgumentException.class, () -> new DailyRoll(1L).roll(P, "d", "p", bound));
        assertEquals("bound must be positive, got " + bound, refusal.getMessage());
    }

    @Test void nullInputsAreRefused() {
        DailyRoll roll = new DailyRoll(1L);
        assertThrows(NullPointerException.class, () -> roll.roll(null, "d", "p", 10));
        assertThrows(NullPointerException.class, () -> roll.roll(P, null, "p", 10));
        assertThrows(NullPointerException.class, () -> roll.roll(P, "d", null, 10));
    }

    @Test void everyRollOverTenThousandDaysIsInsideTheBound() {
        DailyRoll roll = new DailyRoll(-987_654_321L);
        var seen = new HashSet<Integer>();
        for (int day = 0; day < 10_000; day++) {
            int value = roll.roll(P, "day-" + day, "range", 100);
            assertTrue(value >= 0 && value < 100, "day " + day + " rolled " + value);
            seen.add(value);
        }
        assertEquals(100, seen.size(), "every value in [0, 100) turns up over 10,000 days");
    }

    @Test void bothHalvesOfThePropertyIdAreHashed() {
        DailyRoll roll = new DailyRoll(5L);
        int base = roll.roll(new UUID(1, 1), "d", "p", Integer.MAX_VALUE);
        assertNotEquals(base, roll.roll(new UUID(2, 1), "d", "p", Integer.MAX_VALUE));
        assertNotEquals(base, roll.roll(new UUID(1, 2), "d", "p", Integer.MAX_VALUE));
        assertNotEquals(roll.roll(new UUID(1, 2), "d", "p", Integer.MAX_VALUE),
                roll.roll(new UUID(2, 1), "d", "p", Integer.MAX_VALUE), "the halves are not interchangeable");
    }

    @Test void twoInstancesWithTheSameSeedAgree() {
        for (int i = 0; i < 100; i++)
            assertEquals(new DailyRoll(42L).roll(P, "d" + i, "p", 10_000), new DailyRoll(42L).roll(P, "d" + i, "p", 10_000));
    }
}
