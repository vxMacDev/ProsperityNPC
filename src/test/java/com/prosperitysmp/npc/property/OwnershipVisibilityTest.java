package com.prosperitysmp.npc.property;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnershipVisibilityTest {
    final AtomicLong clock = new AtomicLong(100);
    final OwnershipVisibility visibility = new OwnershipVisibility(clock::get);
    final UUID id = new UUID(0,1);
    @Test void unknownAndExactExpirationHide() {
        assertTrue(visibility.unavailable(id));visibility.verified(id);
        clock.addAndGet(OwnershipVisibility.LEASE_NANOS-1);assertFalse(visibility.unavailable(id));
        clock.incrementAndGet();assertTrue(visibility.unavailable(id));
        clock.set(100);assertTrue(visibility.unavailable(id));
    }
    @Test void backwardEvenAfterVerificationHidesPermanentlyUntilNewEvidence() {
        visibility.verified(id);clock.set(200);assertFalse(visibility.unavailable(id));
        clock.set(199);assertTrue(visibility.unavailable(id));
        clock.set(201);assertTrue(visibility.unavailable(id));
        visibility.verified(id);assertFalse(visibility.unavailable(id));
    }
    @Test void invalidateAndClearRemoveLeases() {
        UUID other=new UUID(0,2);visibility.verified(id);visibility.verified(other);
        visibility.invalidate(id);assertTrue(visibility.unavailable(id));assertFalse(visibility.unavailable(other));
        visibility.clear();assertTrue(visibility.unavailable(other));
    }
    @Test void leaseMapRejectsOverflowButCanRefreshExistingAndReuseRemovedSlot() {
        for(int i=0;i<512;i++)visibility.verified(new UUID(0,i));
        assertThrows(IllegalStateException.class,()->visibility.verified(new UUID(0,512)));
        visibility.verified(id);visibility.invalidate(id);visibility.verified(new UUID(0,512));
    }
    @Test void worldFilteringDoesNotRetireOtherWorldLeases() {
        UUID otherId=new UUID(0,2), world=new UUID(1,1), otherWorld=new UUID(1,2);
        List<Property> rows=List.of(property(id,world),property(otherId,otherWorld));
        visibility.verified(id);visibility.verified(otherId);
        assertEquals(Set.of(),OwnershipVisibility.affected(rows,world,true));
        assertEquals(Set.of(),OwnershipVisibility.affected(rows,new UUID(1,3),false));
        Set<UUID> affected=OwnershipVisibility.affected(rows,world,false);
        assertEquals(Set.of(id),affected);affected.forEach(visibility::invalidate);
        assertTrue(visibility.unavailable(id));assertFalse(visibility.unavailable(otherId));
    }
    private static Property property(UUID id,UUID world) {
        return new Property(id,world,"c001","central","standard",1,100,100,null,0,90,true);
    }
}
