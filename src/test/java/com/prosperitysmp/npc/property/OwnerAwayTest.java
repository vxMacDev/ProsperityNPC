package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.economy.UtilityPaymentRuntime;
import com.prosperitysmp.npc.market.HousingRuntime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Vacation protection: the pure away rule, the once-per-day decision cache and each skipped phase's selection. */
class OwnerAwayTest {
    private static final long DAY = 86_400_000L;
    private static final long NOW = 1_800_000_000_000L;
    private static final UUID WORLD = new UUID(0, 7);

    private static Property home(long id, UUID owner, boolean enabled) {
        return new Property(new UUID(1, id), WORLD, "r" + id, "m", "room", 2, 1000, 1000, owner, 0, 80, enabled);
    }

    @Test void awayRule() {
        // Off, online and unknown are never away.
        assertFalse(OwnerAway.away(new OwnerAway.Seen(false, NOW - 100 * DAY, 0), NOW, 0));
        assertFalse(OwnerAway.away(new OwnerAway.Seen(true, NOW - 100 * DAY, NOW - 100 * DAY), NOW, 7));
        assertFalse(OwnerAway.away(new OwnerAway.Seen(false, 0, 0), NOW, 7));
        assertFalse(OwnerAway.away(null, NOW, 7));
        assertFalse(OwnerAway.away(new OwnerAway.Seen(false, NOW + DAY, 0), NOW, 7), "a future sighting is not trusted as away");
        // Exactly N days is not yet away; one millisecond older is.
        assertFalse(OwnerAway.away(new OwnerAway.Seen(false, NOW - 7 * DAY, 0), NOW, 7));
        assertTrue(OwnerAway.away(new OwnerAway.Seen(false, NOW - 7 * DAY - 1, 0), NOW, 7));
        // Last-seen wins; last-played is only the fallback.
        assertFalse(OwnerAway.away(new OwnerAway.Seen(false, NOW - DAY, NOW - 30 * DAY), NOW, 7));
        assertTrue(OwnerAway.away(new OwnerAway.Seen(false, 0, NOW - 30 * DAY), NOW, 7));
        assertEquals(0, OwnerAway.days(-5));
        assertEquals(365, OwnerAway.days(100_000));
        assertEquals(14, OwnerAway.days(14));
    }

    /** Fixture: mutable day, flag and sightings; counts sighting reads to prove once-per-day evaluation. */
    private static final class Fixture {
        final AtomicReference<LocalDate> day = new AtomicReference<>(LocalDate.of(2026, 10, 13));
        final AtomicInteger days = new AtomicInteger(7);
        final AtomicLong now = new AtomicLong(NOW);
        final Map<UUID, OwnerAway.Seen> seen = new HashMap<>();
        final AtomicInteger reads = new AtomicInteger();
        final OwnerAway away = new OwnerAway(days::get, day::get, now::get, owner -> {
            reads.incrementAndGet();
            var s = seen.get(owner);
            if (s == null) throw new IllegalStateException("unreadable");
            return s;
        });
    }

    @Test void decidedOncePerDayAndResumesOnTheNextDayAfterTheOwnerIsSeen() {
        var f = new Fixture();
        UUID owner = new UUID(2, 1);
        Property p = home(1, owner, true);
        f.seen.put(owner, new OwnerAway.Seen(false, NOW - 10 * DAY, 0));
        assertTrue(f.away.isAway(p));
        assertEquals(Set.of(p.propertyId()), f.away.awayAmong(List.of(p)));
        // The owner logs in later the same day: today's decision holds, and Bukkit is not read again.
        f.seen.put(owner, new OwnerAway.Seen(true, NOW, NOW));
        assertTrue(f.away.isAway(p));
        assertEquals(1, f.reads.get(), "one sighting read per property per economy day");
        // Next economy day: the owner was seen, so the home resumes automatically.
        f.day.set(f.day.get().plusDays(1));
        f.now.addAndGet(DAY);
        f.seen.put(owner, new OwnerAway.Seen(false, NOW, NOW));
        assertFalse(f.away.isAway(p));
        assertEquals(Set.of(), f.away.awayAmong(List.of(p)));
        assertEquals(2, f.reads.get());
    }

    @Test void flagOffNeverReadsAndNewOwnerOrUnreadableOwnerIsDecidedFresh() {
        var f = new Fixture();
        UUID owner = new UUID(2, 1), buyer = new UUID(2, 2);
        Property p = home(1, owner, true);
        f.seen.put(owner, new OwnerAway.Seen(false, NOW - 10 * DAY, 0));
        f.days.set(0);
        assertFalse(f.away.isAway(p));
        assertEquals(Set.of(), f.away.awayAmong(List.of(p)));
        assertEquals(0, f.reads.get(), "off reads nothing and behaves as before");
        f.days.set(7);
        assertTrue(f.away.isAway(p));
        // Sold mid-day: the new owner is decided on their own sighting.
        f.seen.put(buyer, new OwnerAway.Seen(true, NOW, NOW));
        assertFalse(f.away.isAway(p.withOwner(buyer, 1)));
        // An unreadable owner keeps the normal behavior (not away), and ownerless homes are never away.
        assertFalse(f.away.isAway(home(2, new UUID(2, 3), true)));
        assertFalse(f.away.isAway(home(3, null, true)));
    }

    /** Stands in for npc_owner_away_decisions: insert-if-absent, then hand back what is stored. */
    private static final class Store {
        final Map<String, Boolean> rows = new HashMap<>();
        List<OwnerAway.Choice> recordFirst(LocalDate day, List<OwnerAway.Choice> choices) {
            var out = new ArrayList<OwnerAway.Choice>();
            for (var c : choices) {
                String key = day + "|" + c.propertyId() + "|" + c.ownerUuid();
                rows.putIfAbsent(key, c.away());
                out.add(new OwnerAway.Choice(c.propertyId(), c.ownerUuid(), rows.get(key)));
            }
            return out;
        }
        /** One save round as the plugin runs it. */
        boolean sync(Fixture f, List<Property> enrolled) {
            return f.away.confirm(f.day.get(), recordFirst(f.day.get(), f.away.unsaved(enrolled)));
        }
    }

    private static Fixture durable(Map<UUID, OwnerAway.Seen> seen) {
        var f = new Fixture();
        f.seen.putAll(seen);
        f.away.requireDurable();
        return f;
    }

    @Test void savedDecisionSurvivesARestartWithinTheDay() {
        UUID owner = new UUID(2, 1), other = new UUID(2, 2);
        Property p = home(1, owner, true), q = home(2, other, true);
        var store = new Store();
        // Process A: owner away 10 days, the other online. Both decisions are saved (present rows too).
        var a = durable(Map.of(owner, new OwnerAway.Seen(false, NOW - 10 * DAY, 0), other, new OwnerAway.Seen(true, NOW, NOW)));
        assertTrue(store.sync(a, List.of(p, q)));
        assertEquals(Set.of(p.propertyId()), a.away.held(List.of(p, q)).orElseThrow());
        assertEquals(2, store.rows.size(), "away and present decisions are both saved");
        // The owner logs in, then the server restarts the same day: process B starts empty.
        var b = durable(Map.of(owner, new OwnerAway.Seen(true, NOW, NOW), other, new OwnerAway.Seen(true, NOW, NOW)));
        assertTrue(b.away.held(List.of(p, q)).isEmpty(), "nothing saved yet in this process: phases must refuse");
        assertTrue(b.away.holds(p), "a recurring or manual bill is held until the decision is saved");
        assertFalse(b.away.isAway(p), "a view may answer from the live sighting before the save");
        assertTrue(store.sync(b, List.of(p, q)));
        assertEquals(Set.of(p.propertyId()), b.away.held(List.of(p, q)).orElseThrow(), "the saved away decision wins after restart");
        assertTrue(b.away.isAway(p), "views now show the saved decision too");
        assertTrue(b.away.holds(p));
        assertFalse(b.away.holds(q));
        assertEquals(List.of(q), UtilityPaymentRuntime.rentCandidates(List.of(p, q), b.away.held(List.of(p, q)).orElseThrow()));
        // Next economy day: not saved yet, so phases wait; after the save the returning owner resumes.
        b.day.set(b.day.get().plusDays(1));
        assertTrue(b.away.held(List.of(p, q)).isEmpty());
        assertTrue(store.sync(b, List.of(p, q)));
        assertEquals(Set.of(), b.away.held(List.of(p, q)).orElseThrow());
    }

    @Test void firstSavedDecisionWinsAndAFailedSaveNeverReleases() {
        UUID owner = new UUID(2, 1);
        Property p = home(1, owner, true);
        var store = new Store();
        var a = durable(Map.of(owner, new OwnerAway.Seen(false, NOW - 10 * DAY, 0)));
        // The save fails (no confirm): the day is never settled, so money paths keep refusing.
        assertEquals(1, a.away.unsaved(List.of(p)).size());
        assertTrue(a.away.held(List.of(p)).isEmpty());
        assertTrue(a.away.holds(p));
        // A present row stored first by another process beats this process's own away decision.
        store.rows.put(a.day.get() + "|" + p.propertyId() + "|" + owner, false);
        assertTrue(store.sync(a, List.of(p)));
        assertFalse(a.away.isAway(p));
        assertEquals(Set.of(), a.away.held(List.of(p)).orElseThrow());
        assertTrue(a.away.unsaved(List.of(p)).isEmpty(), "nothing left to save");
        // A save that lands after the economy day changed adopts nothing and settles nothing.
        var saved = store.recordFirst(a.day.get(), List.of(new OwnerAway.Choice(p.propertyId(), owner, true)));
        a.day.set(a.day.get().plusDays(1));
        assertFalse(a.away.confirm(a.day.get().minusDays(1), saved));
        assertTrue(a.away.held(List.of(p)).isEmpty());
    }

    @Test void newOwnerOrNewHomeMidDayIsHeldUntilSaved() {
        UUID owner = new UUID(2, 1), buyer = new UUID(2, 2);
        Property p = home(1, owner, true);
        var store = new Store();
        var f = durable(Map.of(owner, new OwnerAway.Seen(true, NOW, NOW), buyer, new OwnerAway.Seen(true, NOW, NOW)));
        assertTrue(store.sync(f, List.of(p)));
        assertEquals(Set.of(), f.away.held(List.of(p)).orElseThrow());
        Property sold = p.withOwner(buyer, 1), added = home(2, buyer, true);
        assertEquals(Set.of(sold.propertyId(), added.propertyId()), f.away.held(List.of(sold, added)).orElseThrow(),
                "decided now but not saved yet: left out, no money moves");
        assertTrue(store.sync(f, List.of(sold, added)));
        assertEquals(Set.of(), f.away.held(List.of(sold, added)).orElseThrow());
        assertEquals(3, store.rows.size(), "the new owner has their own row; the old owner's row is kept");
    }

    @Test void flagOffSavesNothingAndHoldsNothing() {
        UUID owner = new UUID(2, 1);
        Property p = home(1, owner, true);
        var f = durable(Map.of(owner, new OwnerAway.Seen(false, NOW - 10 * DAY, 0)));
        f.days.set(0);
        assertTrue(f.away.settled());
        assertEquals(java.util.Optional.of(Set.of()), f.away.held(List.of(p)));
        assertFalse(f.away.holds(p));
        assertEquals(List.of(), f.away.unsaved(List.of(p)));
        assertFalse(f.away.confirm(f.day.get(), List.of()));
        assertEquals(0, f.reads.get());
        // Positive control: the same fixture with the flag on does have a decision to save.
        f.days.set(7);
        assertEquals(List.of(new OwnerAway.Choice(p.propertyId(), owner, true)), f.away.unsaved(List.of(p)));
        // Not durable (no database): held is exactly awayAmong, as before.
        var plain = new Fixture();
        plain.seen.put(owner, new OwnerAway.Seen(false, NOW - 10 * DAY, 0));
        assertEquals(java.util.Optional.of(plain.away.awayAmong(List.of(p))), plain.away.held(List.of(p)));
        assertTrue(plain.away.holds(p));
    }

    @Test void aFullCacheNeverWipesTodaysDecisions() {
        UUID owner = new UUID(2, 1), returning = new UUID(2, 9);
        var f = new Fixture();
        f.seen.put(owner, new OwnerAway.Seen(true, NOW, NOW));
        f.seen.put(returning, new OwnerAway.Seen(false, NOW - 10 * DAY, 0));
        Property first = home(0, returning, true);
        assertTrue(f.away.isAway(first));
        for (int i = 1; i <= OwnerAway.MAX_CACHED + 10; i++) f.away.isAway(home(i, owner, true));
        f.seen.put(returning, new OwnerAway.Seen(true, NOW, NOW));
        assertTrue(f.away.isAway(first), "the decision made before the cache filled still holds");
        var d = durable(Map.of(owner, new OwnerAway.Seen(true, NOW, NOW)));
        var many = new ArrayList<Property>();
        for (int i = 0; i <= OwnerAway.MAX_CACHED; i++) many.add(home(i, owner, true));
        assertThrows(IllegalStateException.class, () -> d.away.unsaved(many), "a save above the cap refuses");
    }

    @Test void incidentAndDepartureTargetsSkipAwayHomes() {
        UUID owner = new UUID(2, 1);
        Property a = home(1, owner, true), b = home(2, owner, true), off = home(3, owner, false), ownerless = home(4, null, true);
        var enrolled = List.of(b, off, a, ownerless);
        assertEquals(List.of(a, b), HousingRuntime.dailyTargets(enrolled, Set.of()), "flag off: same selection as before");
        assertEquals(List.of(b), HousingRuntime.dailyTargets(enrolled, Set.of(a.propertyId())));
        assertEquals(List.of(), HousingRuntime.dailyTargets(enrolled, Set.of(a.propertyId(), b.propertyId())));
    }

    @Test void rentAndAppreciationCandidatesSkipAwayHomes() {
        Property a = home(1, new UUID(2, 1), true), b = home(2, new UUID(2, 2), true), off = home(3, new UUID(2, 3), false);
        var cached = List.of(b, off, a);
        assertEquals(List.of(a, b), UtilityPaymentRuntime.rentCandidates(cached, Set.of()));
        assertEquals(List.of(a), UtilityPaymentRuntime.rentCandidates(cached, Set.of(b.propertyId())));
        assertTrue(UtilityPaymentRuntime.rentCandidates(cached, Set.of(a.propertyId(), b.propertyId())).isEmpty());
    }

    @Test void billsAndAutoCareSkipAwayHomesButOwnerChoicesStay() {
        // away, recurring, incidentId, purchase
        assertTrue(UtilityPaymentRuntime.awayBlocks(true, false, 0, false), "manual utility bill");
        assertTrue(UtilityPaymentRuntime.awayBlocks(true, true, 0, false), "auto-care utility bill");
        assertTrue(UtilityPaymentRuntime.awayBlocks(true, true, 5, false), "auto-care repair");
        assertTrue(UtilityPaymentRuntime.awayBlocks(true, true, 0, true), "auto-care maintenance");
        assertFalse(UtilityPaymentRuntime.awayBlocks(true, false, 5, false), "owner-chosen repair");
        assertFalse(UtilityPaymentRuntime.awayBlocks(true, false, 0, true), "owner-chosen upgrade or insurance");
        assertFalse(UtilityPaymentRuntime.awayBlocks(false, true, 0, false), "not away: unchanged");
        Property a = home(1, new UUID(2, 1), true), b = home(2, new UUID(2, 2), true);
        var enrolled = new ArrayList<>(List.of(a, b));
        assertSame(enrolled, AutoCareRuntime.careTargets(enrolled, Set.of()), "flag off: the same list");
        assertEquals(List.of(b), AutoCareRuntime.careTargets(enrolled, Set.of(a.propertyId())));
    }

    @Test void publicRatingShowsOwnerAwayOnlyWhenAway() {
        var rating = new PublicRating(java.util.OptionalInt.of(7), 1, 2, 80);
        assertEquals(rating.lines(), rating.lines(false));
        assertEquals(3, rating.lines().size());
        var away = rating.lines(true);
        assertEquals(4, away.size());
        assertEquals("Owner away", away.getLast());
        for (String line : List.of(OwnerAway.LABEL, OwnerAway.DESK_NOTE, OwnerAway.PAY_NOTE))
            assertFalse(line.contains("—") || line.contains("–"), "no dashes in player copy: " + line);
    }
}
