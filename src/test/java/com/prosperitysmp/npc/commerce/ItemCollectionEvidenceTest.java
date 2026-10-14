package com.prosperitysmp.npc.commerce;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Evidence confirms a take only when every condition holds; each one is flipped on its own. */
class ItemCollectionEvidenceTest {
    private static final UUID PLAYER = new UUID(2, 1), COLLECTION = new UUID(2, 2);
    private static final List<Integer> BEFORE = List.of(20, 8), QUANTITIES = List.of(16, 8);

    private static ItemCollectionEvidence evidence(UUID player, List<Integer> before, List<Integer> after, String afterHash,
                                                   boolean normal, boolean durable) {
        return new ItemCollectionEvidence(player, COLLECTION, "a".repeat(64), afterHash, before, after, normal, durable);
    }
    private static ItemCollectionEvidence good() { return evidence(PLAYER, BEFORE, List.of(4, 0), "b".repeat(64), true, true); }

    @Test void confirmsOnlyWhenEveryConditionHolds() {
        assertTrue(good().confirms(PLAYER, BEFORE, QUANTITIES), "positive control");
        assertFalse(evidence(new UUID(9, 9), BEFORE, List.of(4, 0), "b".repeat(64), true, true).confirms(PLAYER, BEFORE, QUANTITIES), "player");
        assertFalse(evidence(PLAYER, List.of(21, 8), List.of(5, 0), "b".repeat(64), true, true).confirms(PLAYER, BEFORE, QUANTITIES), "before units");
        assertFalse(evidence(PLAYER, BEFORE, List.of(5, 0), "b".repeat(64), true, true).confirms(PLAYER, BEFORE, QUANTITIES), "one line short");
        assertFalse(evidence(PLAYER, BEFORE, List.of(4, 1), "b".repeat(64), true, true).confirms(PLAYER, BEFORE, QUANTITIES), "other line short");
        assertFalse(evidence(PLAYER, BEFORE, List.of(3, 0), "b".repeat(64), true, true).confirms(PLAYER, BEFORE, QUANTITIES), "one too many");
        assertFalse(evidence(PLAYER, BEFORE, List.of(4, 0), "a".repeat(64), true, true).confirms(PLAYER, BEFORE, QUANTITIES), "hashes equal");
        assertFalse(evidence(PLAYER, BEFORE, List.of(4, 0), "b".repeat(64), false, true).confirms(PLAYER, BEFORE, QUANTITIES), "not normal");
        assertFalse(evidence(PLAYER, BEFORE, List.of(4, 0), "b".repeat(64), true, false).confirms(PLAYER, BEFORE, QUANTITIES), "not durable");
        assertFalse(good().confirms(PLAYER, List.of(20), List.of(16)), "line count");
        assertFalse(good().confirms(PLAYER, BEFORE, List.of(16)), "quantity count");
    }

    @Test void durableIsOnlyClearedNeverGranted() {
        assertTrue(good().withDurable(true).durable());
        assertFalse(good().withDurable(false).durable());
        assertFalse(evidence(PLAYER, BEFORE, List.of(4, 0), "b".repeat(64), true, false).withDurable(true).durable());
    }

    @Test void boundsAndShapeAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> evidence(PLAYER, List.of(), List.of(), "b".repeat(64), true, true));
        assertThrows(IllegalArgumentException.class, () -> evidence(PLAYER, List.of(1, 2), List.of(1), "b".repeat(64), true, true));
        assertThrows(IllegalArgumentException.class, () -> evidence(PLAYER, List.of(1, 1, 1, 1, 1), List.of(0, 0, 0, 0, 0), "b".repeat(64), true, true));
        assertThrows(IllegalArgumentException.class, () -> evidence(PLAYER, List.of(-1), List.of(0), "b".repeat(64), true, true));
        assertThrows(IllegalArgumentException.class, () -> evidence(PLAYER, List.of(65_537), List.of(0), "b".repeat(64), true, true));
        assertThrows(IllegalArgumentException.class, () -> evidence(PLAYER, List.of(1), List.of(0), "B".repeat(64), true, true));
    }

    @Test void unitListRoundTrips() {
        assertEquals("[20,8]", ItemCollectionEvidence.json(List.of(20, 8)));
        assertEquals(List.of(20, 8), ItemCollectionEvidence.parseJson("[20,8]"));
        assertEquals(List.of(0), ItemCollectionEvidence.parseJson("[0]"));
        assertThrows(IllegalArgumentException.class, () -> ItemCollectionEvidence.parseJson("[1,2,3,4,5]"));
        assertThrows(IllegalArgumentException.class, () -> ItemCollectionEvidence.parseJson("[-1]"));
    }
}
