package com.prosperitysmp.npc.clock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whole-year properties of the economy day across zones with large, small and half-hour clock changes. */
class EconomyClockYearTest {
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);
    private static final String[] ZONES = {"America/Chicago", "UTC", "Asia/Kolkata", "Pacific/Auckland", "Australia/Lord_Howe",
            "Europe/London", "America/St_Johns"};

    @Test
    void theAccessorsReturnExactlyWhatWasConfigured() {
        ZoneId zone = ZoneId.of("Europe/London");
        EconomyClock clock = new EconomyClock(FIXED, zone, 7);
        assertSame(zone, clock.zone());
        assertEquals(7, clock.resetHour());
        assertEquals(Instant.parse("2026-06-01T12:00:00Z"), clock.instant());
    }

    @Test
    void todayFollowsTheInjectedClockAsItMoves() {
        Instant[] now = {Instant.parse("2026-03-10T03:59:59Z")};
        Clock moving = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        EconomyClock clock = new EconomyClock(moving, ZoneOffset.UTC, 4);
        assertEquals(LocalDate.of(2026, 3, 9), clock.today());
        now[0] = Instant.parse("2026-03-10T04:00:00Z");
        assertEquals(LocalDate.of(2026, 3, 10), clock.today());
        now[0] = Instant.parse("2026-03-11T03:59:59.999999999Z");
        assertEquals(LocalDate.of(2026, 3, 10), clock.today());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 12, 22, 23})
    void everyDayStartsAtItsResetAndTheInstantBeforeBelongsToTheDayBefore(int hour) {
        for (String zoneName : ZONES) {
            EconomyClock clock = new EconomyClock(FIXED, ZoneId.of(zoneName), hour);
            for (LocalDate day = LocalDate.of(2026, 1, 1); day.isBefore(LocalDate.of(2027, 1, 2)); day = day.plusDays(1)) {
                Instant start = clock.startOf(day);
                assertEquals(day, clock.dayAt(start), zoneName + " " + day + " reset " + hour);
                assertEquals(day.minusDays(1), clock.dayAt(start.minusNanos(1)), zoneName + " " + day + " reset " + hour);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 4, 23})
    void economyDaysNeverRunBackwardsAndNeverSkipAcrossAWholeYearOfHalfHourSamples(int hour) {
        for (String zoneName : ZONES) {
            EconomyClock clock = new EconomyClock(FIXED, ZoneId.of(zoneName), hour);
            LocalDate previous = clock.dayAt(Instant.parse("2025-12-31T00:00:00Z"));
            for (Instant at = Instant.parse("2025-12-31T00:00:00Z"); at.isBefore(Instant.parse("2027-01-02T00:00:00Z"));
                 at = at.plus(Duration.ofMinutes(30))) {
                LocalDate day = clock.dayAt(at);
                long step = day.toEpochDay() - previous.toEpochDay();
                assertTrue(step == 0 || step == 1, zoneName + " at " + at + " moved " + step + " days");
                previous = day;
            }
        }
    }

    @Test
    void consecutiveResetsAreBetweenTwentyTwoAndTwentyFiveHoursApart() {
        for (String zoneName : ZONES) {
            EconomyClock clock = new EconomyClock(FIXED, ZoneId.of(zoneName), 2);
            for (LocalDate day = LocalDate.of(2026, 1, 1); day.isBefore(LocalDate.of(2026, 12, 31)); day = day.plusDays(1)) {
                Duration length = Duration.between(clock.startOf(day), clock.startOf(day.plusDays(1)));
                assertTrue(length.compareTo(Duration.ofHours(22)) >= 0 && length.compareTo(Duration.ofHours(25)) <= 0,
                        zoneName + " " + day + " " + length);
            }
        }
    }

    @Test
    void aSpringForwardDayInChicagoLastsTwentyThreeHoursAndAFallBackDayTwentyFive() {
        EconomyClock clock = new EconomyClock(FIXED, ZoneId.of("America/Chicago"), 4);
        assertEquals(Duration.ofHours(23), Duration.between(clock.startOf(LocalDate.of(2026, 3, 7)), clock.startOf(LocalDate.of(2026, 3, 8))));
        assertEquals(Duration.ofHours(25), Duration.between(clock.startOf(LocalDate.of(2026, 10, 31)), clock.startOf(LocalDate.of(2026, 11, 1))));
        assertEquals(Duration.ofHours(24), Duration.between(clock.startOf(LocalDate.of(2026, 7, 1)), clock.startOf(LocalDate.of(2026, 7, 2))));
    }

    @Test
    void aZoneWithoutDaylightSavingHasUniformTwentyFourHourDays() {
        EconomyClock clock = new EconomyClock(FIXED, ZoneId.of("Asia/Kolkata"), 4);
        for (LocalDate day = LocalDate.of(2026, 1, 1); day.isBefore(LocalDate.of(2026, 12, 31)); day = day.plusDays(1)) {
            assertEquals(Duration.ofHours(24), Duration.between(clock.startOf(day), clock.startOf(day.plusDays(1))));
        }
        assertEquals(Instant.parse("2026-01-14T22:30:00Z"), clock.startOf(LocalDate.of(2026, 1, 15)));
    }

    @Test
    void theFirstAndLastInstantsOfOneDayAreConsistent() {
        EconomyClock clock = new EconomyClock(FIXED, ZoneOffset.UTC, 4);
        LocalDate day = LocalDate.of(2026, 5, 5);
        Instant start = clock.startOf(day);
        Instant end = clock.startOf(day.plusDays(1));
        assertEquals(Instant.parse("2026-05-05T04:00:00Z"), start);
        assertEquals(Instant.parse("2026-05-06T04:00:00Z"), end);
        assertEquals(day, clock.dayAt(end.minusNanos(1)));
        assertFalse(day.equals(clock.dayAt(end)));
    }

    @Test
    void theEpochAndAFarFutureDayResolveInUtc() {
        EconomyClock clock = new EconomyClock(FIXED, ZoneOffset.UTC, 0);
        assertEquals(LocalDate.of(2200, 1, 1), clock.dayAt(clock.startOf(LocalDate.of(2200, 1, 1))));
        assertEquals(LocalDate.of(1970, 1, 1), clock.dayAt(Instant.EPOCH));
        assertEquals(LocalDate.of(1969, 12, 31), clock.dayAt(Instant.EPOCH.minusNanos(1)));
    }
}
