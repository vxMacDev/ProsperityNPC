package com.prosperitysmp.npc.clock;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EconomyClockTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-01-15T09:59:00Z"), ZoneOffset.UTC);
    private static final EconomyClock RESET_FOUR = new EconomyClock(FIXED, CHICAGO, 4);

    @Test
    void winter_midnightAnd0359BelongToPreviousDay_resetIsInclusive() {
        assertDay(RESET_FOUR, "2026-01-14",
                "2026-01-15T00:00:00-06:00",
                "2026-01-15T03:59:00-06:00",
                "2026-01-15T03:59:59.999999999-06:00");
        assertDay(RESET_FOUR, "2026-01-15",
                "2026-01-15T04:00:00-06:00",
                "2026-01-15T04:00:00.000000001-06:00",
                "2026-01-15T23:59:59.999999999-06:00");
    }

    @Test
    void summer_resetRemainsAtFourLocal() {
        assertDay(RESET_FOUR, "2026-07-14",
                "2026-07-15T00:00:00-05:00",
                "2026-07-15T03:59:00-05:00",
                "2026-07-15T03:59:59.999999999-05:00");
        assertDay(RESET_FOUR, "2026-07-15", "2026-07-15T04:00:00-05:00");
    }

    @Test
    void springGap_resetFourDoesNotSlipToFive() {
        // Chicago jumps directly from 01:59:59 CST to 03:00:00 CDT.
        assertDay(RESET_FOUR, "2026-03-07",
                "2026-03-08T00:00:00-06:00",
                "2026-03-08T01:59:59.999999999-06:00",
                "2026-03-08T03:00:00-05:00",
                "2026-03-08T03:59:00-05:00",
                "2026-03-08T03:59:59.999999999-05:00");
        assertDay(RESET_FOUR, "2026-03-08",
                "2026-03-08T04:00:00-05:00",
                "2026-03-08T04:59:59.999999999-05:00",
                "2026-03-08T05:00:00-05:00");
    }

    @Test
    void fallOverlap_bothRepeatedHoursPrecedeResetFour_notThree() {
        assertDay(RESET_FOUR, "2026-10-31",
                "2026-11-01T00:00:00-05:00",
                "2026-11-01T01:00:00-05:00",
                "2026-11-01T01:59:59.999999999-05:00",
                "2026-11-01T01:00:00-06:00",
                "2026-11-01T01:59:59.999999999-06:00",
                "2026-11-01T02:00:00-06:00",
                "2026-11-01T03:00:00-06:00",
                "2026-11-01T03:59:00-06:00",
                "2026-11-01T03:59:59.999999999-06:00");
        assertDay(RESET_FOUR, "2026-11-01", "2026-11-01T04:00:00-06:00");
    }

    @Test
    void nonexistentResetHour_shiftsForwardByGap() {
        EconomyClock clock = new EconomyClock(FIXED, CHICAGO, 2);
        assertDay(clock, "2026-03-07", "2026-03-08T01:59:59.999999999-06:00");
        assertDay(clock, "2026-03-08",
                "2026-03-08T03:00:00-05:00",
                "2026-03-08T03:59:59.999999999-05:00");
    }

    @Test
    void repeatedResetHour_usesFirstOccurrenceAndNeverRewinds() {
        EconomyClock clock = new EconomyClock(FIXED, CHICAGO, 1);
        assertDay(clock, "2026-10-31", "2026-11-01T00:59:59.999999999-05:00");
        assertDay(clock, "2026-11-01",
                "2026-11-01T01:00:00-05:00",
                "2026-11-01T01:59:59.999999999-05:00",
                "2026-11-01T01:00:00-06:00",
                "2026-11-01T01:59:59.999999999-06:00",
                "2026-11-01T02:00:00-06:00");
    }

    @Test
    void halfHourGap_shiftsResetByThirtyMinutesNotOneHour() {
        EconomyClock clock = new EconomyClock(FIXED, ZoneId.of("Australia/Lord_Howe"), 2);
        assertDay(clock, "2026-10-03", "2026-10-04T01:59:59.999999999+10:30");
        assertDay(clock, "2026-10-04", "2026-10-04T02:30:00+11:00");
    }

    @Test
    void differentZone_usesItsLocalDateAndReset() {
        EconomyClock clock = new EconomyClock(FIXED, ZoneId.of("Asia/Tokyo"), 4);
        assertDay(clock, "2026-07-14",
                "2026-07-15T00:00:00+09:00",
                "2026-07-15T03:59:59.999999999+09:00");
        assertDay(clock, "2026-07-15", "2026-07-15T04:00:00+09:00");
    }

    @Test
    void resetZero_isLocalMidnight() {
        EconomyClock clock = new EconomyClock(FIXED, CHICAGO, 0);
        assertDay(clock, "2026-03-07", "2026-03-07T23:59:59.999999999-06:00");
        assertDay(clock, "2026-03-08",
                "2026-03-08T00:00:00-06:00",
                "2026-03-08T03:00:00-05:00");
    }

    @Test
    void resetTwentyThree_isAcceptedAndInclusive() {
        EconomyClock clock = new EconomyClock(FIXED, CHICAGO, 23);
        assertDay(clock, "2026-07-14", "2026-07-15T22:59:59.999999999-05:00");
        assertDay(clock, "2026-07-15", "2026-07-15T23:00:00-05:00");
    }

    @Test
    void previousDay_handlesYearAndLeapDayBoundaries() {
        assertDay(RESET_FOUR, "2025-12-31", "2026-01-01T00:00:00-06:00");
        assertDay(RESET_FOUR, "2024-02-29", "2024-03-01T03:59:59.999999999-06:00");
        assertDay(RESET_FOUR, "2024-03-01", "2024-03-01T04:00:00-06:00");
    }

    @Test
    void today_usesInjectedInstantNotClockZone() {
        Clock tokyoClock = FIXED.withZone(ZoneId.of("Asia/Tokyo"));
        assertEquals(LocalDate.of(2026, 1, 14), new EconomyClock(tokyoClock, CHICAGO, 4).today());
        Clock atReset = Clock.offset(tokyoClock, java.time.Duration.ofMinutes(1));
        assertEquals(LocalDate.of(2026, 1, 15), new EconomyClock(atReset, CHICAGO, 4).today());
    }

    @Test
    void invalidResetHours_areRejectedNotClampedOrWrapped() {
        for (int hour : new int[]{Integer.MIN_VALUE, -1, 24, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new EconomyClock(FIXED, CHICAGO, hour));
        }
    }

    @Test
    void unknownConfiguredZone_isRejectedAtZoneParsing() {
        assertThrows(DateTimeException.class,
                () -> new EconomyClock(FIXED, ZoneId.of("Invalid/Zone"), 4));
    }

    @Test
    void nullClockZoneAndInstant_areRejected() {
        assertThrows(NullPointerException.class, () -> new EconomyClock(null, CHICAGO, 4));
        assertThrows(NullPointerException.class, () -> new EconomyClock(FIXED, null, 4));
        assertThrows(NullPointerException.class, () -> RESET_FOUR.dayAt(null));
    }

    @Test
    void startOfExposesTheSameGapAndOverlapResolution() {
        EconomyClock gap = new EconomyClock(FIXED, CHICAGO, 2);
        assertEquals(Instant.parse("2026-03-08T08:00:00Z"), gap.startOf(LocalDate.of(2026, 3, 8)));
        EconomyClock overlap = new EconomyClock(FIXED, CHICAGO, 1);
        assertEquals(Instant.parse("2026-11-01T06:00:00Z"), overlap.startOf(LocalDate.of(2026, 11, 1)));
        assertThrows(NullPointerException.class, () -> RESET_FOUR.startOf(null));
    }

    @Test
    void startOfUsesExplicitDateAndZoneNotTheInjectedClocksDateOrZone() {
        EconomyClock clock = new EconomyClock(FIXED.withZone(ZoneId.of("Asia/Tokyo")), CHICAGO, 4);
        assertEquals(Instant.parse("2026-07-15T09:00:00Z"), clock.startOf(LocalDate.of(2026, 7, 15)));
        assertEquals(Instant.parse("2026-01-15T10:00:00Z"), clock.startOf(LocalDate.of(2026, 1, 15)));
    }

    @Test
    void skippedDateResolvesForwardWithoutInventingAnElapsedDay() {
        EconomyClock clock = new EconomyClock(FIXED, ZoneId.of("Pacific/Apia"), 4);
        assertEquals(Instant.parse("2011-12-30T14:00:00Z"), clock.startOf(LocalDate.of(2011, 12, 30)));
        assertEquals(clock.startOf(LocalDate.of(2011, 12, 30)), clock.startOf(LocalDate.of(2011, 12, 31)));
    }

    private static void assertDay(EconomyClock clock, String expected, String... localInstants) {
        for (String localInstant : localInstants) {
            Instant instant = OffsetDateTime.parse(localInstant).toInstant();
            assertEquals(LocalDate.parse(expected), clock.dayAt(instant), localInstant);
        }
    }
}
