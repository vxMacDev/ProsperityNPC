package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Owner groups with several components, repeated edges, chains and the order of the edge list. */
class OwnerUnitsGroupsTest {
    private static UUID id(long high) { return new UUID(high, 0); }

    @Test
    void twoSeparateGroupsKeepSeparateKeys() {
        var units = OwnerUnits.of(List.of(new OwnerUnits.Edge(id(5), id(2)), new OwnerUnits.Edge(id(9), id(7))));
        assertEquals(id(2), units.unitOf(id(5)));
        assertEquals(id(7), units.unitOf(id(9)));
        assertEquals(List.of(id(2), id(5)), units.members(id(5)));
        assertEquals(List.of(id(7), id(9)), units.members(id(7)));
    }

    @Test
    void aBridgingEdgeJoinsTwoGroupsUnderTheSmallestMember() {
        var units = OwnerUnits.of(List.of(new OwnerUnits.Edge(id(5), id(2)), new OwnerUnits.Edge(id(9), id(7)),
                new OwnerUnits.Edge(id(9), id(5))));
        for (long member : new long[]{2, 5, 7, 9}) {
            assertEquals(id(2), units.unitOf(id(member)));
            assertEquals(List.of(id(2), id(5), id(7), id(9)), units.members(id(member)));
        }
    }

    @Test
    void repeatedAndReversedEdgesChangeNothing() {
        var once = OwnerUnits.of(List.of(new OwnerUnits.Edge(id(3), id(4))));
        var many = OwnerUnits.of(List.of(new OwnerUnits.Edge(id(3), id(4)), new OwnerUnits.Edge(id(4), id(3)),
                new OwnerUnits.Edge(id(3), id(4))));
        assertEquals(once.unitOf(id(4)), many.unitOf(id(4)));
        assertEquals(once.members(id(3)), many.members(id(3)));
        assertEquals(List.of(id(3), id(4)), many.members(id(4)));
    }

    @Test
    void aLongChainInAnyOrderHasOneKeyAndEveryMemberSorted() {
        List<OwnerUnits.Edge> edges = new ArrayList<>();
        for (long i = 1; i < 50; i++) edges.add(new OwnerUnits.Edge(id(i + 1), id(i)));
        List<UUID> expected = new ArrayList<>();
        for (long i = 1; i <= 50; i++) expected.add(id(i));
        Random random = new Random(68L);
        for (int round = 0; round < 5; round++) {
            Collections.shuffle(edges, random);
            var units = OwnerUnits.of(edges);
            assertEquals(id(1), units.unitOf(id(50)));
            assertEquals(id(1), units.unitOf(id(27)));
            assertEquals(expected, units.members(id(33)));
        }
    }

    @Test
    void theNegativeFirstHalfSortsLastInByteOrder() {
        UUID negative = new UUID(-1, 0), positive = new UUID(Long.MAX_VALUE, 0);
        var units = OwnerUnits.of(List.of(new OwnerUnits.Edge(negative, positive)));
        assertEquals(positive, units.unitOf(negative));
        assertEquals(List.of(positive, negative), units.members(negative));
        assertTrue(OwnerUnits.BYTE_ORDER.compare(negative, positive) > 0);
    }

    @Test
    void theSecondHalfBreaksATieOnTheFirstHalfUnsigned() {
        UUID small = new UUID(4, 1), large = new UUID(4, -1);
        assertTrue(OwnerUnits.BYTE_ORDER.compare(small, large) < 0);
        assertEquals(0, OwnerUnits.BYTE_ORDER.compare(small, new UUID(4, 1)));
        assertEquals(small, OwnerUnits.of(List.of(new OwnerUnits.Edge(large, small))).unitOf(large));
    }

    @Test
    void membersAreImmutableAndAnOwnerOutsideEveryEdgeIsAlone() {
        var units = OwnerUnits.of(List.of(new OwnerUnits.Edge(id(1), id(2))));
        assertThrows(UnsupportedOperationException.class, () -> units.members(id(1)).add(id(3)));
        assertEquals(List.of(id(8)), units.members(id(8)));
        assertEquals(id(8), units.unitOf(id(8)));
    }

    @Test
    void lookupsRefuseANullOwnerAndOfRefusesANullList() {
        var units = OwnerUnits.of(List.of(new OwnerUnits.Edge(id(1), id(2))));
        assertThrows(NullPointerException.class, () -> units.unitOf(null));
        assertThrows(NullPointerException.class, () -> units.members(null));
        assertThrows(NullPointerException.class, () -> OwnerUnits.of(null));
        assertThrows(NullPointerException.class, () -> OwnerUnits.identity().unitOf(null));
    }
}
