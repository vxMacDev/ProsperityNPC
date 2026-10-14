package com.prosperitysmp.npc.property;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.prosperitysmp.npc.property.TenancyDeparture.Cause;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenancyDepartureTest {
    private static final UUID RESIDENT = new UUID(0, 1);
    private static final UUID PROPERTY = new UUID(0, 2);
    private static final UUID OWNER = new UUID(0, 3);
    private static final Instant DEPARTED = Instant.parse("2026-09-07T12:34:56.789Z");

    @Test
    void validBoundsRetainEveryComponentAndAdvanceRevisionWithoutOverflow() {
        for (UUID owner : new UUID[]{null, OWNER, new UUID(1, 3)}) {
            for (int epoch : new int[]{0, 1, Integer.MAX_VALUE}) {
                for (long revision : new long[]{0, 1, Long.MAX_VALUE - 1}) {
                    for (int slot = 0; slot < 4; slot++) {
                        for (Cause cause : Cause.values()) {
                            Long event = cause == Cause.PAID_EVICTION ? Long.valueOf(1) : null;
                            TenancyDeparture departure = new TenancyDeparture(RESIDENT, PROPERTY, owner,
                                    epoch, revision, slot, DEPARTED, cause, event);
                            assertEquals(RESIDENT, departure.residentId());
                            assertEquals(PROPERTY, departure.propertyId());
                            assertEquals(owner, departure.ownerAtDeparture());
                            assertEquals(epoch, departure.ownershipEpoch());
                            assertEquals(revision, departure.occupancyRevisionBefore());
                            assertEquals(revision + 1, departure.occupancyRevisionAfter());
                            assertEquals(slot, departure.slot());
                            assertEquals(DEPARTED, departure.departedAt());
                            assertEquals(cause, departure.cause());
                            assertEquals(event, departure.purchaseEventId());
                            assertTrue(departure.fingerprint().matches("[a-f0-9]{64}"));
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"residentId", "propertyId", "departedAt", "cause"})
    void requiredFieldsCannotBeNull(String field) {
        for (Cause cause : Cause.values()) {
            Long event = cause == Cause.PAID_EVICTION ? Long.valueOf(1) : null;
            assertThrows(NullPointerException.class, () -> new TenancyDeparture(
                    field.equals("residentId") ? null : RESIDENT,
                    field.equals("propertyId") ? null : PROPERTY, OWNER, 2, 17, 1,
                    field.equals("departedAt") ? null : DEPARTED,
                    field.equals("cause") ? null : cause, event));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1})
    void negativeOwnershipEpochIsRejected(int epoch) {
        assertThrows(IllegalArgumentException.class, () -> new TenancyDeparture(
                RESIDENT, PROPERTY, OWNER, epoch, 17, 1, DEPARTED, Cause.DEPARTURE, null));
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, Long.MAX_VALUE})
    void revisionMustLeaveRoomForExactlyOneIncrement(long revision) {
        assertThrows(IllegalArgumentException.class, () -> new TenancyDeparture(
                RESIDENT, PROPERTY, OWNER, 2, revision, 1, DEPARTED, Cause.DEPARTURE, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 4, Integer.MAX_VALUE})
    void slotIsRejectedNotClampedOrWrapped(int slot) {
        assertThrows(IllegalArgumentException.class, () -> new TenancyDeparture(
                RESIDENT, PROPERTY, OWNER, 2, 17, slot, DEPARTED, Cause.DEPARTURE, null));
    }

    @ParameterizedTest
    @CsvSource({"0001-01-01T00:00:00Z,-62135596800000", "1969-12-31T23:59:59.999Z,-1",
            "1970-01-01T00:00:00Z,0", "2026-09-07T12:34:56.789Z,1788784496789",
            "9999-12-31T23:59:59.999Z,253402300799999"})
    void exactMillisecondUtcEndpointsAndPreEpochTimesAreRetained(String time, long epochMillis) {
        Instant departed = Instant.parse(time);
        TenancyDeparture departure = timed(departed);
        assertEquals(departed, departure.departedAt());
        assertEquals(epochMillis, departure.departedAt().toEpochMilli());
        assertEquals(departure, timed(Instant.ofEpochMilli(epochMillis)));
        assertEquals(departure.fingerprint(), timed(Instant.ofEpochMilli(epochMillis)).fingerprint());
    }

    @Test
    void subMillisecondTimesAreRejectedWithoutTruncationIncludingBeforeEpoch() {
        for (Instant time : List.of(DEPARTED.plusNanos(1), DEPARTED.minusNanos(1),
                DEPARTED.plusNanos(999_999), Instant.EPOCH.plusNanos(1), Instant.EPOCH.minusNanos(1),
                Instant.parse("1969-12-31T23:59:59.998999999Z"),
                Instant.parse("0001-01-01T00:00:00.000000001Z"),
                Instant.parse("9999-12-31T23:59:59.999999999Z"))) {
            assertThrows(IllegalArgumentException.class, () -> timed(time), time.toString());
        }
    }

    @Test
    void departureMustFitUtcYearsOneThrough9999EvenForInstantExtremes() {
        for (Instant time : List.of(Instant.MIN, Instant.MAX,
                Instant.parse("0000-12-31T23:59:59.999Z"),
                Instant.parse("+10000-01-01T00:00:00Z"))) {
            assertThrows(IllegalArgumentException.class, () -> timed(time), time.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0, 1, Long.MAX_VALUE})
    void ordinaryDepartureRejectsEveryNonNullPurchaseEvent(long event) {
        assertThrows(IllegalArgumentException.class, () -> new TenancyDeparture(
                RESIDENT, PROPERTY, OWNER, 2, 17, 1, DEPARTED, Cause.DEPARTURE, event));
    }

    @Test
    void paidEvictionRejectsMissingOrNonPositivePurchaseEvent() {
        for (Long event : new Long[]{null, Long.MIN_VALUE, -1L, 0L}) {
            assertThrows(IllegalArgumentException.class, () -> new TenancyDeparture(
                    RESIDENT, PROPERTY, OWNER, 2, 17, 1, DEPARTED, Cause.PAID_EVICTION, event));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, Long.MAX_VALUE})
    void paidEvictionAcceptsPositiveEventBoundsWithoutInferringAnOwner(long event) {
        TenancyDeparture departure = new TenancyDeparture(RESIDENT, PROPERTY, null,
                Integer.MAX_VALUE, Long.MAX_VALUE - 1, 3, DEPARTED, Cause.PAID_EVICTION, event);
        assertNull(departure.ownerAtDeparture());
        assertEquals(Long.valueOf(event), departure.purchaseEventId());
        assertEquals(Long.MAX_VALUE, departure.occupancyRevisionAfter());
        assertTrue(departure.fingerprint().matches("[a-f0-9]{64}"));
    }

    @ParameterizedTest
    @CsvSource({
            "false,DEPARTURE,399bd5e100ae07b3063ee6ccde49cd94f1149d3523be1def3903bf827480861d",
            "false,PAID_EVICTION,41b54138f7adf4db851b5a9f651880643883bf3fb925f0ec6b7e47c12e3d1d43",
            "true,DEPARTURE,1e624c91e386b3ae011c9a910bc033b9ca0180b0993349653b323cbb5a1a1ea7",
            "true,PAID_EVICTION,b163ad5ee1dead9a0f0f97cdc243cf0a7e680d6065a5d61bedb1fe2012e70351"
    })
    void fingerprintPinsVersionAllFieldsUtf8AndExplicitNullMarkers(
            boolean ownerIsNull, Cause cause, String goldenHash) throws Exception {
        UUID owner = ownerIsNull ? null : OWNER;
        Long event = cause == Cause.PAID_EVICTION ? Long.valueOf(1) : null;
        TenancyDeparture departure = new TenancyDeparture(RESIDENT, PROPERTY, owner,
                2, 17, 1, DEPARTED, cause, event);
        String payload = "tenancy-departure-v1|00000000-0000-0000-0000-000000000001|"
                + "00000000-0000-0000-0000-000000000002|"
                + (ownerIsNull ? "null" : "00000000-0000-0000-0000-000000000003")
                + "|2|17|1|1788784496789|"
                + (cause == Cause.DEPARTURE ? "DEPARTURE|null" : "PAID_EVICTION|1");
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
        assertEquals("tenancy-departure-v1", TenancyDeparture.POLICY_VERSION);
        assertEquals(goldenHash, expected);
        assertEquals(goldenHash, departure.fingerprint());
        TenancyDeparture reconstructed = new TenancyDeparture(
                UUID.fromString(departure.residentId().toString()),
                UUID.fromString(departure.propertyId().toString()),
                owner == null ? null : UUID.fromString(owner.toString()), departure.ownershipEpoch(),
                departure.occupancyRevisionBefore(), departure.slot(),
                Instant.ofEpochMilli(departure.departedAt().toEpochMilli()), departure.cause(),
                departure.purchaseEventId());
        for (int i = 0; i < 10; i++) {
            assertEquals(18, departure.occupancyRevisionAfter());
            assertEquals(departure, reconstructed);
            assertEquals(departure.hashCode(), reconstructed.hashCode());
            assertEquals(goldenHash, departure.fingerprint());
            assertEquals(departure.fingerprint(), reconstructed.fingerprint());
        }
    }

    @Test
    void fingerprintChangesWithEveryComponentIncludingDepartureOwnerAndPurchaseEvent() {
        TenancyDeparture original = new TenancyDeparture(RESIDENT, PROPERTY, OWNER,
                2, 17, 1, DEPARTED, Cause.PAID_EVICTION, 1L);
        List<TenancyDeparture> mutations = List.of(
                new TenancyDeparture(new UUID(1, 1), PROPERTY, OWNER,
                        2, 17, 1, DEPARTED, Cause.PAID_EVICTION, 1L),
                new TenancyDeparture(RESIDENT, new UUID(1, 2), OWNER,
                        2, 17, 1, DEPARTED, Cause.PAID_EVICTION, 1L),
                new TenancyDeparture(RESIDENT, PROPERTY, new UUID(1, 3),
                        2, 17, 1, DEPARTED, Cause.PAID_EVICTION, 1L),
                new TenancyDeparture(RESIDENT, PROPERTY, null,
                        2, 17, 1, DEPARTED, Cause.PAID_EVICTION, 1L),
                new TenancyDeparture(RESIDENT, PROPERTY, new UUID(0, 0),
                        2, 17, 1, DEPARTED, Cause.PAID_EVICTION, 1L),
                new TenancyDeparture(RESIDENT, PROPERTY, OWNER,
                        3, 17, 1, DEPARTED, Cause.PAID_EVICTION, 1L),
                new TenancyDeparture(RESIDENT, PROPERTY, OWNER,
                        2, 18, 1, DEPARTED, Cause.PAID_EVICTION, 1L),
                new TenancyDeparture(RESIDENT, PROPERTY, OWNER,
                        2, 17, 2, DEPARTED, Cause.PAID_EVICTION, 1L),
                new TenancyDeparture(RESIDENT, PROPERTY, OWNER,
                        2, 17, 1, DEPARTED.plusMillis(1), Cause.PAID_EVICTION, 1L),
                new TenancyDeparture(RESIDENT, PROPERTY, OWNER,
                        2, 17, 1, DEPARTED.minusMillis(1), Cause.PAID_EVICTION, 1L),
                new TenancyDeparture(RESIDENT, PROPERTY, OWNER,
                        2, 17, 1, DEPARTED, Cause.DEPARTURE, null),
                new TenancyDeparture(RESIDENT, PROPERTY, OWNER,
                        2, 17, 1, DEPARTED, Cause.PAID_EVICTION, 2L),
                new TenancyDeparture(RESIDENT, PROPERTY, OWNER,
                        2, 17, 1, DEPARTED, Cause.PAID_EVICTION, Long.MAX_VALUE));
        Set<String> fingerprints = new HashSet<>();
        fingerprints.add(original.fingerprint());
        for (TenancyDeparture changed : mutations) {
            assertNotEquals(original, changed);
            assertTrue(fingerprints.add(changed.fingerprint()), changed.toString());
        }
    }

    private static TenancyDeparture timed(Instant departed) {
        return new TenancyDeparture(RESIDENT, PROPERTY, OWNER,
                2, 17, 1, departed, Cause.DEPARTURE, null);
    }
}
