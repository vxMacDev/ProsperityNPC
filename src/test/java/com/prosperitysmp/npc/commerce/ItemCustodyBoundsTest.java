package com.prosperitysmp.npc.commerce;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** The custody records an item delivery is proven against: exact hashes, millisecond times and hard limits. */
class ItemCustodyBoundsTest {
    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final UUID PLAYER = new UUID(1, 1);
    private static final CustodyBytes PAYLOAD = new CustodyBytes(new byte[] {1, 2, 3});

    private static ItemCustody custody(int quantity, Instant reserved, Instant expires) {
        return new ItemCustody(new UUID(9, 1), PLAYER, new UUID(9, 2), ItemCustody.Source.MERCHANT_GOODS, quantity,
                PAYLOAD, reserved, expires);
    }

    @Test void custodyBytesHashIsTheSha256OfTheExactBytes() {
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", PAYLOAD.sha256());
        assertEquals("6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d", new CustodyBytes(new byte[] {0}).sha256());
        assertEquals("CustodyBytes[size=3, sha256=039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81]",
                PAYLOAD.toString());
        assertEquals(3, PAYLOAD.size());
        assertNotEquals(PAYLOAD, "not bytes");
        assertThrows(NullPointerException.class, () -> new CustodyBytes(null));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4_096, 65_535, 65_536})
    void payloadsFromOneByteToSixtyFourKibibytesRoundTrip(int size) {
        byte[] input = new byte[size];
        for (int i = 0; i < size; i++) input[i] = (byte) (i * 31 + 7);
        var bytes = new CustodyBytes(input);
        assertArrayEquals(input, bytes.bytes());
        assertEquals(size, bytes.size());
        assertEquals(64, bytes.sha256().length());
        assertTrue(bytes.sha256().matches("[0-9a-f]{64}"));
    }

    @Test void custodyTimesAreTruncatedToTheMillisecond() {
        var held = custody(1, Instant.parse("2026-10-18T12:00:00.123456789Z"), Instant.parse("2026-10-18T12:15:00.123999Z"));
        assertEquals(Instant.parse("2026-10-18T12:00:00.123Z"), held.reservedAt());
        assertEquals(Instant.parse("2026-10-18T12:15:00.123Z"), held.expiresAt());
        assertEquals(Instant.parse("2026-10-18T12:00:00.123Z"), ItemCustody.normalize(Instant.parse("2026-10-18T12:00:00.123999Z")));
        assertThrows(NullPointerException.class, () -> ItemCustody.normalize(null));
    }

    @Test void theReservationIsAtMostFifteenMinutesMeasuredAfterTruncation() {
        assertEquals(NOW.plusSeconds(900), custody(1, NOW, NOW.plusSeconds(900)).expiresAt());
        assertThrows(IllegalArgumentException.class, () -> custody(1, NOW, NOW.plusSeconds(900).plusMillis(1)));
        assertEquals(NOW.plusSeconds(900), custody(1, NOW, NOW.plusSeconds(900).plusNanos(999_999)).expiresAt(),
                "sub-millisecond excess is truncated away first");
        assertEquals(NOW.plusMillis(1), custody(1, NOW, NOW.plusMillis(1)).expiresAt());
        assertThrows(IllegalArgumentException.class, () -> custody(1, NOW, NOW.plusNanos(999_999)),
                "truncated to the same millisecond, so not after the reservation");
        assertThrows(IllegalArgumentException.class, () -> custody(1, NOW, NOW.minusSeconds(1)));
    }

    @Test void reservationsBeforeTheEpochAreRefused() {
        assertEquals(Instant.EPOCH, custody(1, Instant.EPOCH, Instant.EPOCH.plusSeconds(60)).reservedAt());
        assertThrows(IllegalArgumentException.class, () -> custody(1, Instant.EPOCH.minusMillis(1), Instant.EPOCH.plusSeconds(60)));
    }

    @ParameterizedTest
    @CsvSource({"1, true", "256, true", "0, false", "257, false", "-1, false"})
    void custodyQuantityIsOneToTwoHundredFiftySix(int quantity, boolean accepted) {
        if (accepted) assertEquals(quantity, custody(quantity, NOW, NOW.plusSeconds(60)).quantity());
        else assertEquals("Item custody exceeds bounded policy",
                assertThrows(IllegalArgumentException.class, () -> custody(quantity, NOW, NOW.plusSeconds(60))).getMessage());
    }

    @Test void custodyIdentitiesAndTimesAreRequired() {
        assertThrows(NullPointerException.class, () -> new ItemCustody(null, PLAYER, new UUID(9, 2),
                ItemCustody.Source.MERCHANT_GOODS, 1, PAYLOAD, NOW, NOW.plusSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ItemCustody(new UUID(9, 1), null, new UUID(9, 2),
                ItemCustody.Source.MERCHANT_GOODS, 1, PAYLOAD, NOW, NOW.plusSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ItemCustody(new UUID(9, 1), PLAYER, null,
                ItemCustody.Source.MERCHANT_GOODS, 1, PAYLOAD, NOW, NOW.plusSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ItemCustody(new UUID(9, 1), PLAYER, new UUID(9, 2),
                null, 1, PAYLOAD, NOW, NOW.plusSeconds(1)));
        assertThrows(NullPointerException.class, () -> new ItemCustody(new UUID(9, 1), PLAYER, new UUID(9, 2),
                ItemCustody.Source.MERCHANT_GOODS, 1, null, NOW, NOW.plusSeconds(1)));
        assertThrows(NullPointerException.class, () -> custody(1, null, NOW));
        assertThrows(NullPointerException.class, () -> custody(1, NOW, null));
    }

    @Test void theSourceNamesAreThePersistedValues() {
        assertEquals(List.of("WORKER_OUTPUT", "MERCHANT_GOODS", "REPAIR_RETURN"),
                java.util.Arrays.stream(ItemCustody.Source.values()).map(Enum::name).toList());
        assertEquals(256, ItemCustody.MAX_QUANTITY);
        assertEquals(900, ItemCustody.MAX_RESERVATION_SECONDS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0",
            "g123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            " 123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde\n"})
    void evidenceHashesMustBeSixtyFourLowercaseHexCharacters(String hash) {
        var refusal = assertThrows(IllegalArgumentException.class, () -> ItemDeliveryEvidence.requireHash(hash));
        assertEquals("Invalid SHA-256 evidence", refusal.getMessage());
    }

    @Test void aLowercaseSixtyFourCharacterHashIsAccepted() {
        assertDoesNotThrow(() -> ItemDeliveryEvidence.requireHash("0123456789abcdef".repeat(4)));
        assertDoesNotThrow(() -> ItemDeliveryEvidence.requireHash(PAYLOAD.sha256()));
        assertThrows(IllegalArgumentException.class, () -> ItemDeliveryEvidence.requireHash(null));
    }

    private static ItemDeliveryEvidence evidence(int before, int after, int leftover) {
        return new ItemDeliveryEvidence(PLAYER, PAYLOAD.sha256(), "a".repeat(64), "b".repeat(64), before, after, leftover, true);
    }

    @Test void evidenceCountsAcceptTheirExactLimits() {
        assertEquals(65_536, evidence(65_536, 65_536, 256).beforeUnits());
        assertEquals(0, evidence(0, 0, 0).afterUnits());
        assertThrows(IllegalArgumentException.class, () -> evidence(65_537, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> evidence(0, 65_537, 0));
        assertThrows(IllegalArgumentException.class, () -> evidence(0, 0, 257));
        assertThrows(IllegalArgumentException.class, () -> evidence(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> evidence(0, -1, 0));
        assertEquals(65_536, ItemDeliveryEvidence.MAX_OBSERVED_UNITS);
    }

    @Test void deliveryConfirmsOnlyTheExactQuantityDelta() {
        var request = custody(3, NOW, NOW.plusSeconds(60));
        assertTrue(evidence(10, 13, 0).confirms(request));
        assertFalse(evidence(10, 14, 0).confirms(request), "one more than the batch");
        assertFalse(evidence(10, 12, 0).confirms(request), "one fewer than the batch");
        assertFalse(evidence(13, 10, 0).confirms(request), "a fall is never a delivery");
        assertTrue(evidence(65_533, 65_536, 0).confirms(request), "at the observation ceiling");
        assertFalse(evidence(10, 13, 1).confirms(request), "any leftover");
        assertFalse(new ItemDeliveryEvidence(PLAYER, new CustodyBytes(new byte[] {1, 2, 4}).sha256(), "a".repeat(64),
                "b".repeat(64), 10, 13, 0, true).confirms(request), "another payload");
    }

    @Test void evidenceIdentitiesAreRequired() {
        assertThrows(NullPointerException.class, () -> new ItemDeliveryEvidence(null, PAYLOAD.sha256(), "a".repeat(64),
                "b".repeat(64), 0, 1, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new ItemDeliveryEvidence(PLAYER, PAYLOAD.sha256(), "A".repeat(64),
                "b".repeat(64), 0, 1, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new ItemDeliveryEvidence(PLAYER, PAYLOAD.sha256(), "a".repeat(64),
                null, 0, 1, 0, true));
    }

    @ParameterizedTest
    @CsvSource({"1, true", "64, true", "256, true", "0, false", "257, false", "-5, false"})
    void aTakeIsOneToTwoHundredFiftySixUnits(int quantity, boolean accepted) {
        if (accepted) assertEquals(quantity, new ItemInventoryCustody.Take(Material.PAPER, quantity).quantity());
        else assertEquals("Invalid take quantity", assertThrows(IllegalArgumentException.class,
                () -> new ItemInventoryCustody.Take(Material.PAPER, quantity)).getMessage());
    }

    @Test void aTakeNeedsAMaterial() {
        assertThrows(NullPointerException.class, () -> new ItemInventoryCustody.Take(null, 1));
        assertEquals(new ItemInventoryCustody.Take(Material.STONE, 4), new ItemInventoryCustody.Take(Material.STONE, 4));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[ 1]", "[1, 2]", "[01234567]", "[123456]", "1,2", "[1,2", "1]", "[1,,2]", "[1,2,]",
            "[a]", "[+1]", "[1.0]", "[1,2,3,4,5]", "", "[١]"})
    void malformedUnitListsAreRefused(String text) {
        assertEquals("Invalid unit list",
                assertThrows(IllegalArgumentException.class, () -> ItemCollectionEvidence.parseJson(text)).getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"[0]|0", "[99999]|99999", "[00012]|12", "[1,2,3,4]|1;2;3;4", "[65536,0]|65536;0"})
    void wellFormedUnitListsParse(String text, String expected) {
        var values = java.util.Arrays.stream(expected.split(";")).map(Integer::valueOf).toList();
        assertEquals(values, ItemCollectionEvidence.parseJson(text));
    }

    @Test void parseJsonRefusesNull() {
        assertThrows(IllegalArgumentException.class, () -> ItemCollectionEvidence.parseJson(null));
        assertEquals("[]", ItemCollectionEvidence.json(List.of()));
        assertEquals("[0,65536,7]", ItemCollectionEvidence.json(List.of(0, 65_536, 7)));
    }
}
