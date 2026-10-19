package com.prosperitysmp.npc.adapter.griefprevention;

import com.prosperitysmp.npc.adapter.griefprevention.GriefPreventionView.Claim;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The claim as GriefPrevention hands it over: a frozen snapshot that cannot be changed after the read. */
class GriefPreventionClaimRecordTest {
    private static final UUID WORLD = new UUID(0, 1);
    private static final UUID OWNER = new UUID(0, 2);

    @Test void aClaimKeepsEveryFieldItWasGiven() {
        Object identity = new Object();
        var claim = new Claim(11, WORLD, "world_main", 10L, OWNER, identity, List.of(12L, 13L));
        assertEquals(11, claim.id());
        assertEquals(WORLD, claim.worldId());
        assertEquals("world_main", claim.worldName());
        assertEquals(10L, claim.parentId());
        assertEquals(OWNER, claim.ownerId());
        assertSame(identity, claim.identity());
        assertEquals(List.of(12L, 13L), claim.childIds());
    }

    @Test void aTopLevelClaimHasNoParentAndAnAdminClaimNoOwner() {
        var claim = new Claim(5, WORLD, "world_main", null, null, new Object(), List.of());
        assertNull(claim.parentId());
        assertNull(claim.ownerId());
        assertEquals(List.of(), claim.childIds());
    }

    @Test void changingTheListAfterwardsDoesNotChangeTheClaim() {
        var source = new ArrayList<>(List.of(1L, 2L));
        var claim = new Claim(5, WORLD, "world_main", null, OWNER, new Object(), source);
        source.add(3L);
        source.set(0, 99L);
        assertEquals(List.of(1L, 2L), claim.childIds());
    }

    @Test void theChildListOfAClaimCannotBeChanged() {
        var claim = new Claim(5, WORLD, "world_main", null, OWNER, new Object(), List.of(1L));
        assertThrows(UnsupportedOperationException.class, () -> claim.childIds().add(2L));
        assertThrows(UnsupportedOperationException.class, () -> claim.childIds().clear());
    }

    @Test void aClaimWithoutAChildListIsRefused() {
        assertThrows(NullPointerException.class, () -> new Claim(5, WORLD, "world_main", null, OWNER, new Object(), null));
    }

    @Test void aChildListWithAnAbsentIdIsRefused() {
        var withNull = new ArrayList<Long>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new Claim(5, WORLD, "world_main", null, OWNER, new Object(), withNull));
    }

    @Test void twoClaimsOfTheSameLiveObjectAreEqual() {
        Object identity = new Object();
        var a = new Claim(5, WORLD, "world_main", null, OWNER, identity, List.of(6L));
        var b = new Claim(5, WORLD, "world_main", null, OWNER, identity, List.of(6L));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test void aClaimWithAnotherLiveObjectIsNotEqual() {
        var a = new Claim(5, WORLD, "world_main", null, OWNER, new Object(), List.of());
        var b = new Claim(5, WORLD, "world_main", null, OWNER, new Object(), List.of());
        assertNotEquals(a, b);
    }
}
