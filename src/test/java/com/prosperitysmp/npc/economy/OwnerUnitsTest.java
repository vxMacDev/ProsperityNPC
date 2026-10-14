package com.prosperitysmp.npc.economy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** linked-accounts owner groups: union-find components keyed by the smallest member in unsigned byte order. */
class OwnerUnitsTest {
    private static final UUID JAVA = UUID.fromString("7a1b2c3d-0000-4000-8000-000000000001");
    private static final UUID BEDROCK = new UUID(0, 0x0009_0001_0002L);
    /** Most significant bit set: negative as a signed long, the largest in byte order. */
    private static final UUID HIGH_BIT = UUID.fromString("f0000000-0000-4000-8000-000000000002");
    private static final UUID LOW = UUID.fromString("10000000-0000-4000-8000-000000000003");

    @Test void identityWithNoEdges() {
        var units = OwnerUnits.of(List.of());
        assertTrue(units.isIdentity());
        assertSame(OwnerUnits.identity(), units);
        assertEquals(JAVA, units.unitOf(JAVA));
        assertEquals(List.of(JAVA), units.members(JAVA));
        assertEquals(BEDROCK, OwnerUnits.identity().unitOf(BEDROCK));
    }

    @Test void twoMembersMapToTheSmallerByUnsignedByteOrder() {
        // Signed UUID.compareTo puts HIGH_BIT first; byte order (MariaDB BINARY) puts LOW first.
        assertTrue(HIGH_BIT.compareTo(LOW) < 0, "precondition: signed order disagrees");
        for (var edge : List.of(new OwnerUnits.Edge(HIGH_BIT, LOW), new OwnerUnits.Edge(LOW, HIGH_BIT))) {
            var units = OwnerUnits.of(List.of(edge));
            assertEquals(LOW, units.unitOf(HIGH_BIT));
            assertEquals(LOW, units.unitOf(LOW));
            assertEquals(List.of(LOW, HIGH_BIT), units.members(HIGH_BIT));
            assertFalse(units.isIdentity());
        }
        // A Floodgate ID (first half zero) is always the key of its Java partner.
        assertEquals(BEDROCK, OwnerUnits.of(List.of(new OwnerUnits.Edge(JAVA, BEDROCK))).unitOf(JAVA));
        // An owner outside every edge is still its own group.
        assertEquals(JAVA, OwnerUnits.of(List.of(new OwnerUnits.Edge(HIGH_BIT, LOW))).unitOf(JAVA));
    }

    @Test void threeAccountsThroughTwoEdgesFormOneGroupInAnyOrder() {
        var edges = new ArrayList<>(List.of(new OwnerUnits.Edge(JAVA, BEDROCK), new OwnerUnits.Edge(HIGH_BIT, JAVA)));
        for (int round = 0; round < 2; round++) {
            var units = OwnerUnits.of(edges);
            for (UUID member : List.of(JAVA, BEDROCK, HIGH_BIT)) {
                assertEquals(BEDROCK, units.unitOf(member));
                assertEquals(List.of(BEDROCK, JAVA, HIGH_BIT), units.members(member));
            }
            Collections.reverse(edges);
        }
    }

    @Test void selfOrNullEdgeThrows() {
        assertThrows(IllegalArgumentException.class, () -> new OwnerUnits.Edge(JAVA, JAVA));
        assertThrows(IllegalArgumentException.class, () -> new OwnerUnits.Edge(null, JAVA));
        assertThrows(IllegalArgumentException.class, () -> new OwnerUnits.Edge(JAVA, null));
        var withNull = new ArrayList<OwnerUnits.Edge>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> OwnerUnits.of(withNull));
    }

    @Test void byteOrderMatchesTheStoredBinaryForm() {
        var ids = List.of(HIGH_BIT, LOW, JAVA, BEDROCK, new UUID(-1, -1), new UUID(0, -1), new UUID(1, 0));
        for (UUID a : ids) for (UUID b : ids) {
            int bytes = java.util.Arrays.compareUnsigned(com.prosperitysmp.npc.db.Uuids.toBytes(a), com.prosperitysmp.npc.db.Uuids.toBytes(b));
            assertEquals(Integer.signum(bytes), Integer.signum(OwnerUnits.BYTE_ORDER.compare(a, b)), a + " vs " + b);
        }
    }
}
