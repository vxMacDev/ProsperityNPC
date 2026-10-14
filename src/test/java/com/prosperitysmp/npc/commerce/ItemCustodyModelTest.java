package com.prosperitysmp.npc.commerce;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemCustodyModelTest {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    @Test void serializationIsDefensivelyCopiedAndByteExact() {
        byte[] input = {0, 1, -1, 42};
        CustodyBytes bytes = new CustodyBytes(input);
        input[0] = 9;
        byte[] returned = bytes.bytes(); returned[1] = 9;
        assertArrayEquals(new byte[]{0, 1, -1, 42}, bytes.bytes());
        assertEquals(new CustodyBytes(new byte[]{0, 1, -1, 42}), bytes);
        assertEquals(new CustodyBytes(bytes.bytes()).hashCode(), bytes.hashCode());
        assertNotEquals(new CustodyBytes(new byte[]{0, 1, -1, 43}), bytes);
        assertEquals(64, bytes.sha256().length());
    }

    @Test void namedPayloadQuantityAndTimeBoundsRejectOversize() {
        assertThrows(IllegalArgumentException.class, () -> new CustodyBytes(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new CustodyBytes(new byte[CustodyBytes.MAX_BYTES + 1]));
        assertEquals(CustodyBytes.MAX_BYTES, new CustodyBytes(new byte[CustodyBytes.MAX_BYTES]).size());
        assertThrows(IllegalArgumentException.class, () -> request(0, NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> request(ItemCustody.MAX_QUANTITY + 1, NOW.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> request(1, NOW));
        assertThrows(IllegalArgumentException.class, () -> request(1, NOW.plusSeconds(ItemCustody.MAX_RESERVATION_SECONDS + 1)));
        assertEquals(ItemCustody.MAX_QUANTITY, request(ItemCustody.MAX_QUANTITY, NOW.plusSeconds(900)).quantity());
    }

    @Test void confirmedEvidenceRequiresExactRecipientPayloadDeltaAndNoLeftoversOrThrow() {
        ItemCustody request = request(4, NOW.plusSeconds(60));
        assertTrue(evidence(request, request.playerId(), request.payload().sha256(), 10, 14, 0, true).confirms(request));
        assertFalse(evidence(request, UUID.randomUUID(), request.payload().sha256(), 10, 14, 0, true).confirms(request));
        assertFalse(evidence(request, request.playerId(), "f".repeat(64), 10, 14, 0, true).confirms(request));
        assertFalse(evidence(request, request.playerId(), request.payload().sha256(), 10, 13, 1, true).confirms(request));
        assertFalse(evidence(request, request.playerId(), request.payload().sha256(), 10, 14, 0, false).confirms(request));
        assertFalse(new ItemDeliveryEvidence(request.playerId(), request.payload().sha256(), "a".repeat(64),
                "a".repeat(64), 10, 14, 0, true).confirms(request));
    }

    @Test void evidenceAndOperatorReferencesAreBounded() {
        ItemCustody request = request(1, NOW.plusSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> evidence(request, request.playerId(), "not-a-hash", 0, 1, 0, true));
        assertThrows(IllegalArgumentException.class, () -> evidence(request, request.playerId(), request.payload().sha256(), -1, 1, 0, true));
        assertThrows(IllegalArgumentException.class, () -> evidence(request, request.playerId(), request.payload().sha256(), 0, 65_537, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new ItemCustodyRepository.OperatorResolution(UUID.randomUUID(),
                "unsafe\noperator", ItemCustodyRepository.Decision.VERIFIED_NOT_DELIVERED, request.payload()));
    }

    private static ItemCustody request(int quantity, Instant expires) {
        return new ItemCustody(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ItemCustody.Source.WORKER_OUTPUT,
                quantity, new CustodyBytes(new byte[]{1, 2, 3}), NOW, expires);
    }
    private static ItemDeliveryEvidence evidence(ItemCustody request, UUID player, String hash, int before, int after,
                                                 int leftovers, boolean normal) {
        return new ItemDeliveryEvidence(player, hash, "a".repeat(64), "b".repeat(64), before, after, leftovers, normal);
    }
}
