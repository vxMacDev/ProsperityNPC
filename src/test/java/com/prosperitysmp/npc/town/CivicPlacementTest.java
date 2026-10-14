package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.db.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CivicPlacementTest {
    private static final UUID RESIDENT = new UUID(0, 1);
    private static final UUID WORLD = new UUID(0, 2);
    private final CivicPlacementRepository repository = new CivicPlacementRepository(new Database());

    @Test
    void recordHasStableValueSemanticsAndRetainsDisabledLocation() {
        var placement = new CivicPlacement(RESIDENT, WORLD, "Central_District-1", false, 7);
        assertEquals(new CivicPlacement(RESIDENT, WORLD, "Central_District-1", false, 7), placement);
        assertEquals(RESIDENT, placement.residentId());
        assertEquals(WORLD, placement.worldUuid());
        assertEquals("Central_District-1", placement.districtId());
        assertEquals(7, placement.revision());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " central", "central ", "a/b", "a.b", "a:b", "a\nb", "a'b", "caf\u00e9"})
    void invalidDistrictsFailBeforeDatabaseAccess(String district) {
        assertThrows(IllegalArgumentException.class,
                () -> new CivicPlacement(RESIDENT, WORLD, district, true, 1));
        assertThrows(IllegalArgumentException.class, () -> repository.place(RESIDENT, WORLD, district));
    }

    @Test
    void districtAndRevisionBoundsAreExact() {
        assertEquals("a".repeat(128), new CivicPlacement(RESIDENT, WORLD, "a".repeat(128), true, Long.MAX_VALUE).districtId());
        assertThrows(IllegalArgumentException.class,
                () -> new CivicPlacement(RESIDENT, WORLD, "a".repeat(129), true, 1));
        assertThrows(IllegalArgumentException.class,
                () -> repository.place(RESIDENT, WORLD, "a".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> new CivicPlacement(RESIDENT, WORLD, "central", true, 0));
        assertThrows(IllegalArgumentException.class, () -> new CivicPlacement(RESIDENT, WORLD, "central", true, -1));
    }

    @Test
    void nullIdentityWorldAndDatabaseAreRejected() {
        assertThrows(NullPointerException.class, () -> new CivicPlacement(null, WORLD, "central", true, 1));
        assertThrows(NullPointerException.class, () -> new CivicPlacement(RESIDENT, null, "central", true, 1));
        assertThrows(NullPointerException.class, () -> new CivicPlacementRepository(null));
        assertThrows(NullPointerException.class, () -> repository.place(null, WORLD, "central"));
        assertThrows(NullPointerException.class, () -> repository.place(RESIDENT, null, "central"));
        assertThrows(NullPointerException.class, () -> repository.disable(null));
        assertThrows(NullPointerException.class, () -> repository.listForDisplay(null, 1));
    }

    @Test
    void emptyOrNonpositiveDisplayRequestDoesNotOpenDatabase() throws Exception {
        assertTrue(repository.listForDisplay(Set.of(), 100).isEmpty());
        assertTrue(repository.listForDisplay(Set.of(WORLD), 0).isEmpty());
        assertTrue(repository.listForDisplay(Set.of(WORLD), Integer.MIN_VALUE).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> repository.listForDisplay(Set.of(), 1)
                .add(new CivicPlacement(RESIDENT, WORLD, "central", true, 1)));
    }

    @Test
    void malformedWorldBatchIsRejectedEvenWithZeroLimit() {
        Set<UUID> worlds = new HashSet<>();
        worlds.add(null);
        assertThrows(IllegalArgumentException.class, () -> repository.listForDisplay(worlds, 0));
        worlds.clear();
        for (int i = 0; i < 129; i++) worlds.add(new UUID(0, i));
        assertThrows(IllegalArgumentException.class, () -> repository.listForDisplay(worlds, 0));
    }
}
