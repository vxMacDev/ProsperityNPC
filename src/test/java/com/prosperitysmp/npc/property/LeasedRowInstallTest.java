package com.prosperitysmp.npc.property;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A step that changed SQL rows under its leases installs the re-read rows before it releases them
 * (PropertyService.installLeasedRows), plus the reconcile bookkeeping helpers (Codex round 4: F3, F5).
 */
class LeasedRowInstallTest {
    private static final UUID OWNER = new UUID(2, 1);
    private static Property home(int n, int condition) {
        return new Property(new UUID(0, n), new UUID(1, 1), "r" + n, "central", "standard", 2, 1000, 1000,
                OWNER, 3, condition, true);
    }
    private static Map<UUID, Property> cacheOf(Property... homes) {
        Map<UUID, Property> cache = new HashMap<>();
        for (Property p : homes) cache.put(p.propertyId(), p);
        return cache;
    }

    @Test void lostConditionIsInstalledOverTheLeasedRecord() {
        Property leased = home(1, 80);
        var cache = cacheOf(leased);
        var out = PropertyService.installLeasedRows(cache, List.of(leased), List.of(home(1, 65)));
        assertEquals(65, cache.get(leased.propertyId()).conditionScore());
        assertEquals(Set.of(leased.propertyId()), out.installed()); assertTrue(out.unsettled().isEmpty());
    }

    @Test void unchangedRowKeepsTheVeryRecordTheStepHeld() {
        Property leased = home(1, 80);
        var cache = cacheOf(leased);
        var out = PropertyService.installLeasedRows(cache, List.of(leased), List.of(home(1, 80)));
        assertSame(leased, cache.get(leased.propertyId()), "no needless swap, so an ownership pass in progress keeps it");
        assertTrue(out.installed().isEmpty()); assertTrue(out.unsettled().isEmpty());
    }

    @Test void recordReplacedByAnotherWriterIsNotOverwritten() {
        Property leased = home(1, 80), replaced = home(1, 80); // equal, but not the record the step held
        var cache = cacheOf(replaced);
        var out = PropertyService.installLeasedRows(cache, List.of(leased), List.of(home(1, 65)));
        assertSame(replaced, cache.get(leased.propertyId()));
        assertEquals(Set.of(leased.propertyId()), out.unsettled()); assertTrue(out.installed().isEmpty());
    }

    @Test void ownershipChangeInSqlIsLeftToTheOwnershipCheck() {
        Property leased = home(1, 80);
        var cache = cacheOf(leased);
        var out = PropertyService.installLeasedRows(cache, List.of(leased), List.of(home(1, 65).withOwner(new UUID(2, 9), 4)));
        assertSame(leased, cache.get(leased.propertyId()), "an owner the step did not verify never enters the cache here");
        assertEquals(Set.of(leased.propertyId()), out.unsettled());
    }

    @Test void epochOnlyChangeIsLeftToTheOwnershipCheck() {
        Property leased = home(1, 80);
        var cache = cacheOf(leased);
        var out = PropertyService.installLeasedRows(cache, List.of(leased), List.of(home(1, 65).withOwner(OWNER, 4)));
        assertSame(leased, cache.get(leased.propertyId()));
        assertEquals(Set.of(leased.propertyId()), out.unsettled());
    }

    @Test void ownerOnlyChangeWithTheSameEpochIsLeftToTheOwnershipCheck() {
        Property leased = home(1, 80);
        var cache = cacheOf(leased);
        var out = PropertyService.installLeasedRows(cache, List.of(leased), List.of(home(1, 65).withOwner(new UUID(2, 9), 3)));
        assertSame(leased, cache.get(leased.propertyId()), "a different owner never enters the cache here, even at the same epoch");
        assertEquals(Set.of(leased.propertyId()), out.unsettled()); assertTrue(out.installed().isEmpty());
    }

    @Test void disabledRowIsLeftToTheOwnershipCheck() {
        Property leased = home(1, 80);
        var cache = cacheOf(leased);
        Property disabled = new Property(leased.propertyId(), leased.worldUuid(), leased.regionId(), leased.marketId(),
                leased.classKey(), leased.capacity(), leased.referenceRentCents(), leased.askingRentCents(),
                OWNER, 3, 65, false);
        var out = PropertyService.installLeasedRows(cache, List.of(leased), List.of(disabled));
        assertSame(leased, cache.get(leased.propertyId()), "a paused row is not installed by a step that did not pause it");
        assertEquals(Set.of(leased.propertyId()), out.unsettled()); assertTrue(out.installed().isEmpty());
    }

    @Test void missingRowIsUnsettledAndUncachedHomeIsIgnored() {
        Property leased = home(1, 80), gone = home(2, 80);
        var cache = cacheOf(leased);
        var out = PropertyService.installLeasedRows(cache, List.of(leased, gone), List.of());
        assertSame(leased, cache.get(leased.propertyId())); assertFalse(cache.containsKey(gone.propertyId()));
        assertEquals(Set.of(leased.propertyId()), out.unsettled());
    }

    // ---- F5 helpers

    private static OwnershipReconcilePump.Result result(int checked, int failed, boolean cancelled, int deferred) {
        return new OwnershipReconcilePump.Result(checked, 0, 0, 0, failed, cancelled, deferred);
    }

    @Test void aPassThatOnlyDeferredIsNotAReconcile() {
        assertFalse(PropertyService.reconciled(result(0, 0, false, 2)), "only deferred");
        assertTrue(PropertyService.reconciled(result(1, 0, false, 2)), "verified one home");
        assertTrue(PropertyService.reconciled(result(0, 0, false, 0)), "no deferrals (as before)");
        assertTrue(PropertyService.reconciled(result(0, 1, false, 0)), "failures alone still count, as before");
        assertFalse(PropertyService.reconciled(result(3, 0, true, 0)), "cancelled, as before");
    }

    @Test void deferredHomesMakeTheScheduledPassWarnAndAreCounted() {
        var deferredOnly = result(4, 0, false, 2);
        assertTrue(PropertyService.reconcileIncomplete(deferredOnly));
        assertEquals("ARM reconcile incomplete: verified=4 skipped=0 missing=0 unverified=0 waiting-on-busy-home=2",
                PropertyService.reconcileIncompleteText(deferredOnly));
        assertFalse(PropertyService.reconcileIncomplete(result(4, 0, false, 0)));
        assertEquals("ARM reconcile incomplete: verified=4 skipped=0 missing=0 unverified=1",
                PropertyService.reconcileIncompleteText(result(4, 1, false, 0)), "unchanged wording without deferrals");
    }

    @Test void healthLineNamesHomesWaitingOnABusyHome() {
        assertEquals("; 3 waiting on a busy home", PropertyService.waitingOnBusy(3));
        assertEquals("", PropertyService.waitingOnBusy(0));
    }
}
