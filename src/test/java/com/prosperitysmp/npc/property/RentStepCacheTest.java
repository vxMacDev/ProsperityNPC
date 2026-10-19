package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.property.PropertyRepository.DueRent;
import com.prosperitysmp.npc.property.PropertyRepository.DueRentResult;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTransientConnectionException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The cache side of the daily rent step: the unguarded rebase and the guarded install of held rows. */
class RentStepCacheTest {
    private static final UUID WORLD = new UUID(0, 81);

    private static Property home(int n, int asking) {
        return new Property(new UUID(3, n), WORLD, "home-" + n, "town", "small", 2, 10_000, asking, new UUID(4, n), 1, 90, true);
    }

    private static Map<UUID, Property> cacheOf(Property... homes) {
        var cache = new HashMap<UUID, Property>();
        for (Property p : homes) cache.put(p.propertyId(), p);
        return cache;
    }

    private static DueRent change(Property before, Property after, DueRent.Resolution resolution) {
        return new DueRent(before, after, 7, resolution);
    }

    private static DueRentResult result(DueRent... changes) {
        return new DueRentResult(List.of(changes), 0);
    }

    @Test void rebaseInstallsTheAfterRowWhenTheCacheStillHoldsTheBeforeRow() {
        var before = home(1, 10_000);
        var after = before.withAskingRent(12_000);
        var cache = cacheOf(before);
        var out = PropertyService.rebaseDueRent(cache, result(change(before, after, DueRent.Resolution.APPLIED)), List.of(), id -> true);
        assertEquals(new PropertyService.DueRentCacheOutcome(1, 0, 0, false), out);
        assertSame(after, cache.get(before.propertyId()));
    }

    @Test void rebaseCountsADifferentCachedRecordAsDriftAndLeavesIt() {
        var before = home(1, 10_000);
        var cached = before.withAskingRent(11_000);
        var cache = cacheOf(cached);
        var out = PropertyService.rebaseDueRent(cache,
                result(change(before, before.withAskingRent(12_000), DueRent.Resolution.APPLIED)), List.of(), id -> true);
        assertTrue(out.drift());
        assertSame(cached, cache.get(before.propertyId()));
    }

    @Test void rebaseLeavesAHeldEntryAndCountsAChangedAfterRowAsDrift() {
        var before = home(1, 10_000);
        var cache = cacheOf(before);
        var out = PropertyService.rebaseDueRent(cache,
                result(change(before, before.withAskingRent(12_000), DueRent.Resolution.APPLIED)), List.of(), id -> false);
        assertTrue(out.drift());
        assertSame(before, cache.get(before.propertyId()));
    }

    @Test void rebaseAcceptsAHeldEntryWhoseRowDidNotChange() {
        var before = home(1, 10_000);
        var cache = cacheOf(before);
        var out = PropertyService.rebaseDueRent(cache, result(change(before, home(1, 10_000), DueRent.Resolution.CANCELLED)),
                List.of(), id -> false);
        assertEquals(new PropertyService.DueRentCacheOutcome(0, 1, 0, false), out);
    }

    @Test void rebaseCountsEveryResolutionIncludingUncachedHomes() {
        var a = home(1, 10_000);
        var b = home(2, 10_000);
        var c = home(3, 10_000);
        var out = PropertyService.rebaseDueRent(cacheOf(a), result(
                change(a, a, DueRent.Resolution.REPLACED),
                change(b, b.withAskingRent(9_000), DueRent.Resolution.APPLIED),
                change(c, c, DueRent.Resolution.REPLACED),
                change(home(4, 10_000), home(4, 10_000), DueRent.Resolution.CANCELLED)), List.of(), id -> true);
        assertEquals(new PropertyService.DueRentCacheOutcome(1, 1, 2, false), out);
    }

    @Test void rebaseIgnoresAReReadWithTheSameAskingRentEvenIfOtherFieldsDiffer() {
        var cached = home(1, 10_000);
        var fresh = new Property(cached.propertyId(), WORLD, "home-1", "town", "small", 2, 10_000, 10_000, cached.ownerUuid(), 1, 40, true);
        var cache = cacheOf(cached);
        var out = PropertyService.rebaseDueRent(cache, result(), List.of(fresh), id -> true);
        assertFalse(out.drift());
        assertSame(cached, cache.get(cached.propertyId()), "only the asking rent is reconciled here");
    }

    @Test void rebaseInstallsAReReadThatDiffersOnlyInAskingRent() {
        var cached = home(1, 10_000);
        var fresh = cached.withAskingRent(13_000);
        var cache = cacheOf(cached);
        var out = PropertyService.rebaseDueRent(cache, result(), List.of(fresh), id -> true);
        assertFalse(out.drift());
        assertSame(fresh, cache.get(cached.propertyId()));
    }

    @Test void rebaseCountsAReReadThatDiffersInMoreThanAskingRentAsDrift() {
        var cached = home(1, 10_000);
        var fresh = cached.withAskingRent(13_000).withOwner(new UUID(4, 99), 2);
        var cache = cacheOf(cached);
        assertTrue(PropertyService.rebaseDueRent(cache, result(), List.of(fresh), id -> true).drift());
        assertSame(cached, cache.get(cached.propertyId()));
    }

    @Test void rebaseCountsAHeldReReadWithANewAskingRentAsDrift() {
        var cached = home(1, 10_000);
        var cache = cacheOf(cached);
        assertTrue(PropertyService.rebaseDueRent(cache, result(), List.of(cached.withAskingRent(13_000)), id -> false).drift());
        assertSame(cached, cache.get(cached.propertyId()));
    }

    @Test void rebaseSkipsAReReadOfAnUncachedHome() {
        var cache = cacheOf();
        assertFalse(PropertyService.rebaseDueRent(cache, result(), List.of(home(1, 10_000)), id -> true).drift());
        assertTrue(cache.isEmpty());
    }

    @Test void rebaseRefusesNullArguments() {
        assertThrows(NullPointerException.class, () -> PropertyService.rebaseDueRent(null, result(), List.of(), id -> true));
        assertThrows(NullPointerException.class, () -> PropertyService.rebaseDueRent(cacheOf(), null, List.of(), id -> true));
        assertThrows(NullPointerException.class, () -> PropertyService.rebaseDueRent(cacheOf(), result(), null, id -> true));
        assertThrows(NullPointerException.class, () -> PropertyService.rebaseDueRent(cacheOf(), result(), List.of(), null));
    }

    @Test void installHeldReplacesAHeldEntryWithTheFullAfterRow() {
        var cached = home(1, 10_000);
        var after = new Property(cached.propertyId(), WORLD, "home-1", "town", "small", 2, 10_000, 12_000, cached.ownerUuid(), 1, 55, true);
        var cache = cacheOf(cached);
        var installed = GuardedRentStep.installHeld(cache, result(change(cached, after, DueRent.Resolution.APPLIED)), List.of(),
                id -> true);
        assertEquals(new PropertyService.DueRentCacheOutcome(1, 0, 0, false), installed.outcome());
        assertTrue(installed.heldMatch());
        assertSame(after, cache.get(cached.propertyId()), "every field is installed, not only the asking rent");
    }

    @Test void installHeldPrefersTheReReadOverTheAfterRow() {
        var cached = home(1, 10_000);
        var after = cached.withAskingRent(12_000);
        var reread = after.withOwner(new UUID(4, 50), 2);
        var cache = cacheOf(cached);
        var installed = GuardedRentStep.installHeld(cache, result(change(cached, after, DueRent.Resolution.APPLIED)),
                List.of(reread), id -> true);
        assertTrue(installed.heldMatch());
        assertSame(reread, cache.get(cached.propertyId()));
    }

    @Test void installHeldLeavesAnUnheldEntryAndCountsItsChangeAsDrift() {
        var cached = home(1, 10_000);
        var cache = cacheOf(cached);
        var installed = GuardedRentStep.installHeld(cache,
                result(change(cached, cached.withAskingRent(12_000), DueRent.Resolution.APPLIED)), List.of(), id -> false);
        assertTrue(installed.outcome().drift());
        assertTrue(installed.heldMatch(), "nothing held, so nothing held can mismatch");
        assertSame(cached, cache.get(cached.propertyId()));
    }

    @Test void installHeldIgnoresAnUnheldReReadWithTheSameAskingRent() {
        var cached = home(1, 10_000);
        var reread = new Property(cached.propertyId(), WORLD, "home-1", "town", "small", 2, 10_000, 10_000, cached.ownerUuid(), 1, 20, true);
        var installed = GuardedRentStep.installHeld(cacheOf(cached), result(), List.of(reread), id -> false);
        assertFalse(installed.outcome().drift());
    }

    @Test void installHeldCountsAnUnheldReReadWithANewAskingRentAsDrift() {
        var cached = home(1, 10_000);
        var installed = GuardedRentStep.installHeld(cacheOf(cached), result(), List.of(cached.withAskingRent(9_000)), id -> false);
        assertTrue(installed.outcome().drift());
    }

    @Test void installHeldSkipsUncachedAndUnchangedEntries() {
        var cached = home(1, 10_000);
        var cache = cacheOf(cached);
        var installed = GuardedRentStep.installHeld(cache, result(change(home(2, 10_000), home(2, 11_000), DueRent.Resolution.APPLIED)),
                List.of(home(1, 10_000)), id -> true);
        assertEquals(new PropertyService.DueRentCacheOutcome(1, 0, 0, false), installed.outcome());
        assertTrue(installed.heldMatch());
        assertSame(cached, cache.get(cached.propertyId()));
        assertFalse(cache.containsKey(home(2, 10_000).propertyId()), "an uncached home is never added");
    }

    @Test void installHeldReportsAMismatchWhenTheCacheRefusesTheReplacement() {
        var cached = home(1, 10_000);
        Map<UUID, Property> stubborn = new HashMap<>(cacheOf(cached)) {
            @Override public boolean replace(UUID key, Property oldValue, Property newValue) { return false; }
        };
        var installed = GuardedRentStep.installHeld(stubborn,
                result(change(cached, cached.withAskingRent(12_000), DueRent.Resolution.APPLIED)), List.of(), id -> true);
        assertFalse(installed.heldMatch(), "a held home whose cached record still differs from SQL must not be released");
        assertSame(cached, stubborn.get(cached.propertyId()));
    }

    @Test void installHeldRefusesNullArguments() {
        assertThrows(NullPointerException.class, () -> GuardedRentStep.installHeld(null, result(), List.of(), id -> true));
        assertThrows(NullPointerException.class, () -> GuardedRentStep.installHeld(cacheOf(), null, List.of(), id -> true));
        assertThrows(NullPointerException.class, () -> GuardedRentStep.installHeld(cacheOf(), result(), null, id -> true));
        assertThrows(NullPointerException.class, () -> GuardedRentStep.installHeld(cacheOf(), result(), List.of(), null));
    }

    @Test void onlyAnIntegrityViolationCertainlyDidNotCommit() {
        assertTrue(PropertyService.certainlyNotCommitted(new SQLIntegrityConstraintViolationException("Duplicate entry")));
        assertFalse(PropertyService.certainlyNotCommitted(new SQLException("Communications link failure")));
        assertFalse(PropertyService.certainlyNotCommitted(new SQLTransientConnectionException("timeout")));
        assertFalse(PropertyService.certainlyNotCommitted(new RuntimeException(new SQLIntegrityConstraintViolationException())));
        assertFalse(PropertyService.certainlyNotCommitted(null));
    }

    @Test void anUnreadableEnrollmentLookupRefusesWithoutEnrolling() {
        assertEquals("Property lookup unavailable; nothing enrolled.",
                PropertyService.enrollmentRefusal(new PropertyRepository(null), WORLD, "home-1"));
    }

    @Test void theWaitingOnBusySuffixAppearsOnlyAboveZero() {
        assertEquals("", PropertyService.waitingOnBusy(0));
        assertEquals("", PropertyService.waitingOnBusy(-3));
        assertEquals("; 1 waiting on a busy home", PropertyService.waitingOnBusy(1));
        assertEquals("; 12 waiting on a busy home", PropertyService.waitingOnBusy(12));
    }

    @Test void aLeasedInstallKeepsImmutableCopies() {
        var installed = new HashSet<UUID>(Set.of(new UUID(3, 1)));
        var outcome = new PropertyService.LeasedInstall(installed, Set.of());
        installed.add(new UUID(3, 2));
        assertEquals(Set.of(new UUID(3, 1)), outcome.installed());
        assertThrows(UnsupportedOperationException.class, () -> outcome.unsettled().add(new UUID(3, 3)));
    }

    @Test void aDueRentResultKeepsImmutableCopies() {
        var changes = new java.util.ArrayList<DueRent>();
        var deferred = new java.util.ArrayList<UUID>(List.of(new UUID(3, 1)));
        var r = new DueRentResult(changes, 2, deferred);
        changes.add(change(home(1, 10_000), home(1, 10_000), DueRent.Resolution.CANCELLED));
        deferred.clear();
        assertEquals(0, r.changes().size());
        assertEquals(List.of(new UUID(3, 1)), r.deferred());
        assertEquals(List.of(), new DueRentResult(List.of(), 0).deferred());
    }

    @Test void aDueRentNeedsAResolution() {
        assertThrows(NullPointerException.class, () -> new DueRent(home(1, 1), home(1, 1), 1, null));
        assertTrue(change(home(1, 1), home(1, 1), DueRent.Resolution.APPLIED).applied());
        assertFalse(change(home(1, 1), home(1, 1), DueRent.Resolution.REPLACED).applied());
        assertFalse(change(home(1, 1), home(1, 1), DueRent.Resolution.CANCELLED).applied());
    }
}
