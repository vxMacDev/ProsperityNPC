package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.formulas.PropertyFormulas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.time.zone.ZoneRulesException;
import java.time.zone.ZoneRulesProvider;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilityBillTest {
    private static final UUID PROPERTY = new UUID(0, 1);
    private static final UUID OWNER = new UUID(0, 2);
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final Instant ISSUED = Instant.parse("2026-09-07T12:34:56.789Z");

    @Test
    void defaultTextStaysCompatibleWithStoredPremiumEvidence() {
        assertEquals("UtilityBill[propertyId=00000000-0000-0000-0000-000000000001, "
                + "ownerId=00000000-0000-0000-0000-000000000002, ownershipEpoch=0, propertyRevision=0, "
                + "day=2026-09-07, capacity=4, occupiedTenants=2, installedUpgradeLevels=3, "
                + "issuedAt=2026-09-07T12:34:56.789Z, zoneId=UTC, resetHour=4]", counts(4, 2, 3).toString());
    }

    @Test
    void everyLegalCountCombinationUsesFrozenV1IntegerCents() {
        assertEquals("utility-bill-v1", UtilityBill.POLICY_VERSION);
        for (int capacity = 1; capacity <= 4; capacity++) {
            for (int occupied = 0; occupied <= capacity; occupied++) {
                for (int levels = 0; levels <= 6; levels++) {
                    UtilityBill bill = counts(capacity, occupied, levels);
                    assertEquals(100L + 75L * occupied + 50L * levels, bill.cents());
                    assertEquals(PropertyFormulas.dailyUtilitiesCents(occupied, levels), bill.cents());
                    assertTrue(bill.cents() >= 100 && bill.cents() <= 700);
                    assertEquals(capacity, bill.capacity());
                    assertEquals(occupied, bill.occupiedTenants());
                    assertEquals(levels, bill.installedUpgradeLevels());
                }
            }
        }
        assertEquals(100L, counts(1, 0, 0).cents());
        assertEquals(700L, counts(4, 4, 6).cents());
    }

    @ParameterizedTest
    @ValueSource(strings = {"property", "owner", "day", "issuedAt", "zoneId"})
    void requiredFieldsCannotBeNull(String field) {
        assertThrows(NullPointerException.class, () -> new UtilityBill(
                field.equals("property") ? null : PROPERTY, field.equals("owner") ? null : OWNER,
                0, 0, field.equals("day") ? null : DAY, 4, 2, 3,
                field.equals("issuedAt") ? null : ISSUED, field.equals("zoneId") ? null : "UTC", 4));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 5, Integer.MAX_VALUE})
    void capacityIsStrictlyOneThroughFour(int capacity) {
        assertThrows(IllegalArgumentException.class, () -> counts(capacity, 0, 0));
    }

    @Test
    void occupancyCannotBeNegativeOrExceedActualCapacity() {
        for (int capacity = 1; capacity <= 4; capacity++) {
            final int slots = capacity;
            for (int occupied : new int[]{Integer.MIN_VALUE, -1, capacity + 1, Integer.MAX_VALUE})
                assertThrows(IllegalArgumentException.class, () -> counts(slots, occupied, 0));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 7, Integer.MAX_VALUE})
    void upgradeLevelsAreRejectedNotClamped(int levels) {
        assertThrows(IllegalArgumentException.class, () -> counts(4, 0, levels));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1})
    void negativeOwnershipEpochIsRejected(int epoch) {
        assertThrows(IllegalArgumentException.class,
                () -> new UtilityBill(PROPERTY, OWNER, epoch, 0, DAY, 4, 2, 3, ISSUED, "UTC", 4));
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, Long.MAX_VALUE})
    void revisionReservesTheOverflowSentinel(long revision) {
        assertThrows(IllegalArgumentException.class,
                () -> new UtilityBill(PROPERTY, OWNER, 0, revision, DAY, 4, 2, 3, ISSUED, "UTC", 4));
    }

    @Test
    void epochAndRevisionEndpointsAreRetained() {
        for (int epoch : new int[]{0, Integer.MAX_VALUE}) {
            for (long revision : new long[]{0, Long.MAX_VALUE - 1}) {
                UtilityBill bill = new UtilityBill(PROPERTY, OWNER, epoch, revision, DAY, 4, 2, 3,
                        ISSUED, "UTC", 4);
                assertEquals(epoch, bill.ownershipEpoch());
                assertEquals(revision, bill.propertyRevision());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-999999999, -1, 0, 9999, 10000, 999999999})
    void dayReservesRoomForTheNextReset(int year) {
        assertThrows(IllegalArgumentException.class, () -> new UtilityBill(PROPERTY, OWNER, 0, 0,
                LocalDate.of(year, 1, 1), 4, 2, 3, ISSUED, "UTC", 4));
    }

    @ParameterizedTest
    @CsvSource({"0001-01-01,0001-01-01T00:00:00Z,0001-01-02T00:00:00Z",
            "9998-12-31,9998-12-31T23:59:59.999Z,9999-01-01T00:00:00Z",
            "2024-02-29,2024-02-29T12:00:00Z,2024-03-01T00:00:00Z"})
    void validDateEndpointsLeapDayAndYearRollover(String day, String issued, String due) {
        UtilityBill bill = timed(day, "UTC", 0, issued);
        assertEquals(LocalDate.parse(day), bill.day());
        assertEquals(Instant.parse(issued), bill.issuedAt());
        assertEquals(Instant.parse(due), bill.dueAt());
    }

    @ParameterizedTest
    @CsvSource({
            "2026-03-07,America/Chicago,4,2026-03-07T10:00:00Z,2026-03-08T09:00:00Z,1380",
            "2026-03-08,America/Chicago,0,2026-03-08T06:00:00Z,2026-03-09T05:00:00Z,1380",
            "2026-10-31,America/Chicago,4,2026-10-31T09:00:00Z,2026-11-01T10:00:00Z,1500",
            "2026-11-01,America/Chicago,0,2026-11-01T05:00:00Z,2026-11-02T06:00:00Z,1500",
            "2026-03-08,America/Chicago,2,2026-03-08T08:00:00Z,2026-03-09T07:00:00Z,1380",
            "2026-11-01,America/Chicago,1,2026-11-01T06:00:00Z,2026-11-02T07:00:00Z,1500",
            "2026-10-04,Australia/Lord_Howe,2,2026-10-03T15:30:00Z,2026-10-04T15:00:00Z,1410",
            "2026-04-05,Australia/Lord_Howe,1,2026-04-04T14:00:00Z,2026-04-05T14:30:00Z,1470",
            "2026-09-07,UTC,23,2026-09-07T23:00:00Z,2026-09-08T23:00:00Z,1440"
    })
    void periodsUseLocalResetsAndExactAdjoiningBoundaries(String day, String zone, int reset,
                                                         String start, String due, long minutes) {
        UtilityBill bill = timed(day, zone, reset, start);
        assertEquals(Instant.parse(start), bill.periodStart());
        assertEquals(Instant.parse(due), bill.dueAt());
        assertEquals(Duration.ofMinutes(minutes), Duration.between(bill.periodStart(), bill.dueAt()));
        assertEquals(400L, bill.cents(), "DST does not prorate the daily tariff");
        EconomyClock clock = new EconomyClock(Clock.fixed(ISSUED, ZoneOffset.UTC), ZoneId.of(zone), reset);
        assertEquals(clock.startOf(bill.day()), bill.periodStart());
        assertEquals(clock.startOf(bill.day().plusDays(1)), bill.dueAt());
        assertEquals(bill.day(), clock.dayAt(bill.periodStart()));
        assertEquals(bill.day(), clock.dayAt(bill.dueAt().minusMillis(1)));
        UtilityBill lastMillisecond = timed(day, zone, reset, bill.dueAt().minusMillis(1).toString());
        assertEquals(bill.dueAt(), lastMillisecond.dueAt(), "late issuance cannot extend the deadline");
        assertThrows(IllegalArgumentException.class,
                () -> timed(day, zone, reset, bill.periodStart().minusMillis(1).toString()));
        assertThrows(IllegalArgumentException.class, () -> timed(day, zone, reset, due));
        assertThrows(IllegalArgumentException.class,
                () -> timed(day, zone, reset, bill.dueAt().plusMillis(1).toString()));
        UtilityBill next = timed(bill.day().plusDays(1).toString(), zone, reset, due);
        assertEquals(bill.dueAt(), next.periodStart());
    }

    @Test
    void secondOccurrenceOfOverlapIsInTheSameBillPeriod() {
        UtilityBill first = timed("2026-11-01", "America/Chicago", 1, "2026-11-01T06:00:00Z");
        UtilityBill second = timed("2026-11-01", "America/Chicago", 1, "2026-11-01T07:00:00Z");
        assertEquals(first.periodStart(), second.periodStart());
        assertEquals(first.dueAt(), second.dueAt());
        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void skippedApiaDayHasNoBillForAnyResetHour() {
        for (int hour = 0; hour <= 23; hour++) {
            final int reset = hour;
            EconomyClock clock = new EconomyClock(Clock.fixed(ISSUED, ZoneOffset.UTC),
                    ZoneId.of("Pacific/Apia"), reset);
            Instant start = clock.startOf(LocalDate.of(2011, 12, 30));
            assertEquals(start, clock.startOf(LocalDate.of(2011, 12, 31)));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> timed("2011-12-30", "Pacific/Apia", reset, start.toString()));
            assertTrue(failure.getMessage().contains("positive duration"));
            UtilityBill previous = timed("2011-12-29", "Pacific/Apia", reset, start.minusMillis(1).toString());
            UtilityBill following = timed("2011-12-31", "Pacific/Apia", reset, start.toString());
            assertEquals(previous.dueAt(), following.periodStart());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 24, Integer.MAX_VALUE})
    void resetHourIsRejectedNotDefaultedOrWrapped(int reset) {
        assertThrows(IllegalArgumentException.class, () -> timed(DAY.toString(), "UTC", reset, ISSUED.toString()));
    }

    @Test
    void issuanceMustBeExactMillisecondsWithoutTruncation() {
        for (Instant issued : List.of(ISSUED.plusNanos(1), ISSUED.minusNanos(1),
                ISSUED.plusNanos(999_999), Instant.parse("2026-09-08T03:59:59.999999999Z")))
            assertThrows(IllegalArgumentException.class,
                    () -> timed(DAY.toString(), "UTC", 4, issued.toString()));
        UtilityBill preEpoch = timed("1969-12-31", "UTC", 0, "1969-12-31T23:59:59.999Z");
        assertEquals(-1L, preEpoch.issuedAt().toEpochMilli());
        assertThrows(IllegalArgumentException.class,
                () -> timed("1969-12-31", "UTC", 0, "1969-12-31T23:59:59.999999999Z"));
    }

    @Test
    void issuanceMustFitUtcYearsEvenWhenTheLocalPeriodExtendsEarlier() {
        for (Instant issued : List.of(Instant.MIN, Instant.MAX,
                Instant.parse("0000-12-31T23:59:59.999Z"), Instant.parse("+10000-01-01T00:00:00Z")))
            assertThrows(IllegalArgumentException.class,
                    () -> timed(DAY.toString(), "UTC", 4, issued.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> timed("0001-01-01", "+18:00", 0, "0000-12-31T06:00:00Z"));
        assertThrows(IllegalArgumentException.class,
                () -> timed("0001-01-01", "+18:00", 0, "0001-01-01T00:00:00Z"));
        assertEquals(Instant.parse("0001-01-01T00:00:00Z"),
                timed("0001-01-01", "+18:00", 18, "0001-01-01T00:00:00Z").periodStart());
        UtilityBill upper = timed("9998-12-31", "-18:00", 23, "9999-01-02T16:59:59.999Z");
        assertEquals(Instant.parse("9999-01-02T16:59:59.999Z"), upper.issuedAt());
        assertEquals(Instant.parse("9999-01-02T17:00:00Z"), upper.dueAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "UTC ", " UTC", "Invalid/Zone", "America/Chicago|0", "UTC\n", "CST", "+19:00"})
    void invalidZonesAreRejectedWithoutTrimmingOrFallback(String zone) {
        assertThrows(IllegalArgumentException.class, () -> timed(DAY.toString(), zone, 4, ISSUED.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Z", "GMT", "UT", "America/Chicago", "US/Central", "Etc/UTC", "+01", "+01:00", "UTC+01"})
    void validZoneStringsAreRetainedExactlyIncludingAliases(String zone) {
        UtilityBill bill = timed(DAY.toString(), zone, 4, ISSUED.toString());
        assertEquals(zone, bill.zoneId());
    }

    @Test
    void zoneLengthLimitIsEnforcedEvenForRegisteredValidZoneIds() {
        assertEquals(64, LongZoneProvider.LIMIT.length());
        assertEquals(65, LongZoneProvider.OVER_LIMIT.length());
        assertEquals(LongZoneProvider.OVER_LIMIT, ZoneId.of(LongZoneProvider.OVER_LIMIT).getId());
        UtilityBill accepted = timed(DAY.toString(), LongZoneProvider.LIMIT, 4, ISSUED.toString());
        assertEquals(LongZoneProvider.LIMIT, accepted.zoneId());
        assertThrows(IllegalArgumentException.class,
                () -> timed(DAY.toString(), LongZoneProvider.OVER_LIMIT, 4, ISSUED.toString()));
    }

    @Test
    void fingerprintPinsTheVersionFullPayloadEncodingAndPrice() throws Exception {
        UtilityBill bill = counts(4, 2, 3);
        String payload = "utility-bill-v1|00000000-0000-0000-0000-000000000001|"
                + "00000000-0000-0000-0000-000000000002|0|0|2026-09-07|4|2|3|1788784496789|UTC|4|400";
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
        assertEquals("fc5834feebde04bb524f43d4aa9bfa75792c1d247671cfa501e9ea17d93657cd", expected);
        assertEquals(expected, bill.fingerprint());
        assertTrue(bill.fingerprint().matches("[a-f0-9]{64}"));
        UtilityBill reconstructed = new UtilityBill(bill.propertyId(), bill.ownerId(), bill.ownershipEpoch(),
                bill.propertyRevision(), bill.day(), bill.capacity(), bill.occupiedTenants(),
                bill.installedUpgradeLevels(), bill.issuedAt(), bill.zoneId(), bill.resetHour());
        for (int i = 0; i < 10; i++) {
            bill.periodStart();
            bill.dueAt();
            bill.cents();
            assertEquals(reconstructed, bill);
            assertEquals(reconstructed.hashCode(), bill.hashCode());
            assertEquals(reconstructed.fingerprint(), bill.fingerprint());
        }
    }

    @Test
    void fingerprintBindsEveryImmutableComponentNotOnlyThePriceOrDay() {
        UtilityBill bill = counts(4, 2, 3);
        List<UtilityBill> mutations = List.of(
                new UtilityBill(new UUID(1, 1), OWNER, 0, 0, DAY, 4, 2, 3, ISSUED, "UTC", 4),
                new UtilityBill(PROPERTY, new UUID(1, 2), 0, 0, DAY, 4, 2, 3, ISSUED, "UTC", 4),
                new UtilityBill(PROPERTY, OWNER, 1, 0, DAY, 4, 2, 3, ISSUED, "UTC", 4),
                new UtilityBill(PROPERTY, OWNER, 0, 1, DAY, 4, 2, 3, ISSUED, "UTC", 4),
                new UtilityBill(PROPERTY, OWNER, 0, 0, DAY.plusDays(1), 4, 2, 3,
                        ISSUED.plus(Duration.ofDays(1)), "UTC", 4),
                new UtilityBill(PROPERTY, OWNER, 0, 0, DAY, 3, 2, 3, ISSUED, "UTC", 4),
                new UtilityBill(PROPERTY, OWNER, 0, 0, DAY, 4, 1, 3, ISSUED, "UTC", 4),
                new UtilityBill(PROPERTY, OWNER, 0, 0, DAY, 4, 2, 4, ISSUED, "UTC", 4),
                new UtilityBill(PROPERTY, OWNER, 0, 0, DAY, 4, 2, 3, ISSUED.plusMillis(1), "UTC", 4),
                new UtilityBill(PROPERTY, OWNER, 0, 0, DAY, 4, 2, 3, ISSUED, "Etc/UTC", 4),
                new UtilityBill(PROPERTY, OWNER, 0, 0, DAY, 4, 2, 3, ISSUED, "UTC", 5));
        Set<String> fingerprints = new HashSet<>();
        fingerprints.add(bill.fingerprint());
        for (UtilityBill changed : mutations) {
            assertNotEquals(bill, changed);
            assertTrue(fingerprints.add(changed.fingerprint()), changed.toString());
        }
        UtilityBill samePrice = counts(4, 4, 0);
        assertEquals(bill.cents(), samePrice.cents());
        assertNotEquals(bill.fingerprint(), samePrice.fingerprint());
        UtilityBill shortOffset = timed(DAY.toString(), "+01", 4, ISSUED.toString());
        UtilityBill canonicalOffset = timed(DAY.toString(), "+01:00", 4, ISSUED.toString());
        assertEquals(shortOffset.periodStart(), canonicalOffset.periodStart());
        assertEquals(shortOffset.dueAt(), canonicalOffset.dueAt());
        assertNotEquals(shortOffset.fingerprint(), canonicalOffset.fingerprint());
    }

    private static final class LongZoneProvider extends ZoneRulesProvider {
        static final String LIMIT = "UtilityBillTest/" + "U".repeat(48);
        static final String OVER_LIMIT = LIMIT + "U";
        static { ZoneRulesProvider.registerProvider(new LongZoneProvider()); }

        @Override
        protected Set<String> provideZoneIds() { return Set.of(LIMIT, OVER_LIMIT); }

        @Override
        protected ZoneRules provideRules(String zoneId, boolean forCaching) {
            if (!provideZoneIds().contains(zoneId)) throw new ZoneRulesException("Unknown test zone");
            return ZoneOffset.UTC.getRules();
        }

        @Override
        protected NavigableMap<String, ZoneRules> provideVersions(String zoneId) {
            return new TreeMap<>(Map.of("test-v1", provideRules(zoneId, false)));
        }
    }

    private static UtilityBill counts(int capacity, int occupied, int levels) {
        return new UtilityBill(PROPERTY, OWNER, 0, 0, DAY, capacity, occupied, levels, ISSUED, "UTC", 4);
    }

    private static UtilityBill timed(String day, String zone, int reset, String issued) {
        return new UtilityBill(PROPERTY, OWNER, 0, 0, LocalDate.parse(day), 4, 2, 3,
                Instant.parse(issued), zone, reset);
    }
}
