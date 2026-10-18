package com.prosperitysmp.npc.api.contract;

import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.RegionRef;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What every property backend must do. API 1.0 cases read regions and owners; the API 1.2 guard cases (PB-03 to
 * PB-16) drive the backend's guard through {@link GuardControls} over a fake vendor, and are skipped for a backend
 * without one. An adapter's test extends this class and builds a {@link Subject} over its own fake region store.
 */
public abstract class PropertyBackendContract {

    /** Reserved for fixtures that need setup; API 1.0 cases all use the default. */
    public static final class Scenario {
        private Scenario() { }
        public static Scenario standard() { return new Scenario(); }
    }

    /** The backend under test plus controls over its fake region store. */
    public interface Subject {
        PropertyBackend backend();

        /** False for a backend that never lists a region; region cases are then skipped. */
        boolean knowsRegions();

        UUID world();

        /** A region in {@link #world()} owned by {@link #owner()}. */
        String region();

        /** The documented paying principal of {@link #region()}. */
        UUID owner();

        /** Every block location inside the region (or a fair sample of them). */
        List<Location> cellsOf(String region);

        /** A location in the same world inside no region. */
        Location outside();

        /** The paying principal changes. */
        void setOwner(String region, UUID owner);

        /** Someone is added who may use the region but does not pay for it (a member, a trusted player). */
        void addMember(String region, UUID member);

        /** An edit that does not change who pays, for example a flag. */
        void unrelatedEdit(String region);

        void removeRegion(String region);

        /** The backend can no longer read its store. */
        void makeUnreadable();

        /** Owners that can never be paid: at least the zero UUID; also server or temporary owners if the plugin has them. */
        List<UUID> unpayableOwners();

        /** API 1.2: controls over the fake vendor's owner-change events. Null when there are none; guard cases skip. */
        default GuardControls guard() { return null; }
    }

    /**
     * The fake vendor's event flow, so the contract can watch the backend's guard react. Every change runs the
     * vendor's whole sequence (the pre-change event, ProsperityNPC's handlers, the change itself and any post event)
     * the way the recorded vendor does.
     */
    public interface GuardControls {
        /** Installs the backend's guard with these hooks and connects its handlers to the fake vendor. */
        GuardResult install(OwnershipHooks hooks);

        /** Disconnects the handlers, as the adapter's plugin disabling does. */
        void uninstall();

        /**
         * A vendor owner change of {@code region} to {@code owner} for a vendor reason ({@code "default"} is an
         * ordinary player or staff command). True when the change applied.
         */
        boolean changeOwner(String region, UUID owner, String reason);

        /** The same ordinary change, but another plugin cancels it before ProsperityNPC's monitor handler. */
        void changeOwnerCancelledElsewhere(String region, UUID owner);

        /** Vendor reasons ProsperityNPC must never cancel, even while it holds the region. */
        List<String> neverCancelled();

        /** True when owner-change events can arrive off the main thread; the contract then fires them off main. */
        default boolean eventsOffMain() { return false; }

        /** True when a later subscriber can overwrite ProsperityNPC's veto (last writer wins). */
        default boolean supportsOverride() { return false; }

        /** An ordinary change where a later subscriber overwrites ProsperityNPC's veto, so it applies. */
        default void changeOwnerOverridden(String region, UUID owner) {
            throw new UnsupportedOperationException("no override flow");
        }

        /** Other enrolled regions that one change of {@code region} also changes (a merged group, a parent). */
        List<String> affectedWith(String region);

        /** Deletes the region and creates a new one with the same id and owner. */
        void recreate(String region, UUID owner);

        /** From now on every vendor call and handler throws a LinkageError, as after an incompatible update. */
        void breakLinkage();
    }

    protected abstract Subject subject(Scenario s);

    private Subject build() {
        return ContractMeta.wrap(subject(Scenario.standard()));
    }

    @Test @CaseId("PB-01")
    void everyCellResolvesToTheSameRegionAndOutsideIsEmpty() {
        var subject = build();
        assertTrue(subject.backend().regionAt(subject.outside()).isEmpty(), "outside every region");
        assumeTrue(subject.knowsRegions());
        for (Location cell : subject.cellsOf(subject.region())) {
            RegionRef ref = subject.backend().regionAt(cell).orElseThrow(() -> new AssertionError("cell " + cell));
            assertEquals(subject.region(), ref.regionId());
            assertEquals(subject.world(), ref.worldId());
        }
    }

    @Test @CaseId(value = "PB-02", critical = true)
    void theTokenChangesWithThePrincipalAndNotWithAnUnrelatedEdit() {
        var subject = build();
        assumeTrue(subject.knowsRegions());
        var backend = subject.backend();
        OwnershipToken first = backend.ownerReadRegion(subject.world(), subject.region(), subject.owner()).orElseThrow();
        subject.unrelatedEdit(subject.region());
        OwnershipToken afterEdit = backend.ownerReadRegion(subject.world(), subject.region(), subject.owner()).orElseThrow();
        assertTrue(first.sameIncarnation(afterEdit), "an unrelated edit keeps the incarnation");
        UUID next = new UUID(7, 7);
        subject.setOwner(subject.region(), next);
        OwnershipToken changed = backend.ownerReadRegion(subject.world(), subject.region(), next).orElseThrow();
        assertFalse(first.sameIncarnation(changed), "a new principal is a new incarnation");
        subject.setOwner(subject.region(), subject.owner());
        OwnershipToken back = backend.ownerReadRegion(subject.world(), subject.region(), subject.owner()).orElseThrow();
        // Only a backend whose vendor keeps a version per owner change can see a round trip (A to B and back to A)
        // that fired no event; most edit the owner in place. It says so with the flag sees-owner-round-trip.
        if (backend.capabilities().flag("sees-owner-round-trip"))
            assertFalse(first.sameIncarnation(back), "selling and buying back is still a new incarnation");
    }

    /** A subject whose guard is installed with fresh recording hooks, or a skipped case. */
    private record Guarded(Subject subject, GuardControls controls, RecordingHooks hooks, GuardMode mode) {
        UUID world() { return subject.world(); }
        String region() { return subject.region(); }

        /** Runs a vendor change on the thread its events arrive on. */
        boolean change(String region, UUID owner, String reason) {
            if (!controls.eventsOffMain()) return controls.changeOwner(region, owner, reason);
            boolean[] applied = new boolean[1];
            Throwable[] thrown = new Throwable[1];
            Thread vendor = new Thread(() -> {
                try { applied[0] = controls.changeOwner(region, owner, reason); }
                catch (Throwable failure) { thrown[0] = failure; }
            }, "fake-vendor-events");
            vendor.start();
            try { vendor.join(10_000); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            if (thrown[0] != null) throw new AssertionError("the vendor change threw", thrown[0]);
            return applied[0];
        }
    }

    private Guarded guarded() {
        var subject = build();
        assumeTrue(subject.knowsRegions());
        GuardControls controls = subject.guard();
        assumeTrue(controls != null, "no fake vendor events");
        var hooks = new RecordingHooks();
        GuardResult result = controls.install(hooks);
        assertTrue(result.isInstalled(), "guard install: " + result.reason());
        return new Guarded(subject, controls, hooks, result.mode().orElseThrow());
    }

    @Test @CaseId(value = "PB-03", critical = true)
    void aVetoBackendCancelsOnlyWhileTheRegionIsHeld() {
        var g = guarded();
        assumeTrue(g.mode() == GuardMode.VETO_EVENTS);
        UUID next = new UUID(3, 3);
        g.hooks().hold(g.world(), g.region());
        assertFalse(g.change(g.region(), next, "default"), "held: the ordinary change is vetoed");
        assertTrue(g.subject().backend().ownerReadRegion(g.world(), g.region(), g.subject().owner()).isPresent(),
                "the owner did not change");
        assertEquals(0, g.hooks().bumpsOf(g.world(), g.region()), "a vetoed change is no change");
        g.hooks().release(g.world(), g.region());
        assertTrue(g.change(g.region(), next, "default"), "not held: the change goes through");
    }

    @Test @CaseId(value = "PB-04", critical = true)
    void eachAppliedChangeBumpsOnceAndACancelledOneNever() {
        var g = guarded();
        assertTrue(g.change(g.region(), new UUID(4, 4), "default"));
        assertEquals(1, g.hooks().bumpsOf(g.world(), g.region()), "one bump per applied change");
        g.hooks().clearBumps();
        g.controls().changeOwnerCancelledElsewhere(g.region(), new UUID(4, 5));
        assertEquals(0, g.hooks().bumpsOf(g.world(), g.region()), "cancelled by another plugin: no bump");
    }

    @Test @CaseId(value = "PB-05", critical = true)
    void theTokenChangesAfterAnOwnerChangeThatFiredNoEvent() {
        var subject = build();
        assumeTrue(subject.knowsRegions());
        Location cell = subject.cellsOf(subject.region()).getFirst();
        OwnershipToken before = subject.backend().ownerReadAt(cell, subject.owner()).orElseThrow();
        UUID next = new UUID(5, 5);
        subject.setOwner(subject.region(), next);
        OwnershipToken after = subject.backend().ownerReadAt(cell, next).orElseThrow();
        assertFalse(before.sameIncarnation(after), "a silent owner change is still a new incarnation");
        assertTrue(subject.backend().ownerReadAt(cell, subject.owner()).isEmpty(), "the old owner reads nothing");
    }

    @Test @CaseId(value = "PB-06", critical = true)
    void reasonsThatMustNotBeCancelledGoThroughAndBump() {
        var g = guarded();
        int i = 0;
        for (String reason : g.controls().neverCancelled()) {
            g.hooks().clearBumps();
            g.hooks().hold(g.world(), g.region());
            assertTrue(g.change(g.region(), new UUID(6, ++i), reason), reason + " is never cancelled");
            assertTrue(g.hooks().bumpsOf(g.world(), g.region()) >= 1, reason + " bumps the region");
            g.hooks().release(g.world(), g.region());
        }
    }

    @Test @CaseId(value = "PB-10", critical = true)
    void offMainEventsUseOnlyIsHeldAndMarkDirty() {
        var g = guarded();
        assumeTrue(g.controls().eventsOffMain());
        g.hooks().hold(g.world(), g.region());
        boolean applied = g.change(g.region(), new UUID(10, 1), "default");
        if (g.mode() == GuardMode.VETO_EVENTS) assertFalse(applied, "held: vetoed off main too");
        g.hooks().release(g.world(), g.region());
        assertTrue(g.change(g.region(), new UUID(10, 2), "default"));
        assertEquals(List.of(), g.hooks().offMainMisuse());
    }

    @Test @CaseId(value = "PB-11", critical = true)
    void aLinkageErrorMakesTheBackendUnavailableWithAReason() {
        var g = guarded();
        var backend = g.subject().backend();
        g.controls().breakLinkage();
        assertDoesNotThrow(() -> g.change(g.region(), new UUID(11, 1), "default"));
        assertFalse(g.hooks().failures().isEmpty(), "the handler failure reached the hooks");
        assertFalse(g.hooks().failures().getFirst().isBlank());
        assertFalse(assertDoesNotThrow(backend::available), "unavailable after a LinkageError");
        assertNotEquals(Presence.PRESENT, assertDoesNotThrow(() -> backend.observe(g.world(), g.region())));
        assertTrue(assertDoesNotThrow(() -> backend.ownerReadRegion(g.world(), g.region(), g.subject().owner())).isEmpty());
    }

    @Test @CaseId(value = "PB-13", critical = true)
    void aVetoOverwrittenByALaterSubscriberIsStillBumped() {
        var g = guarded();
        assumeTrue(g.controls().supportsOverride());
        g.hooks().hold(g.world(), g.region());
        g.controls().changeOwnerOverridden(g.region(), new UUID(13, 1));
        assertTrue(g.hooks().bumpsOf(g.world(), g.region()) >= 1, "the post event bumps even when the veto lost");
    }

    @Test @CaseId("PB-14")
    void enableDisableEnableNeverDoubleRegisters() {
        var g = guarded();
        g.controls().uninstall();
        assertTrue(g.controls().install(g.hooks()).isInstalled());
        assertTrue(g.change(g.region(), new UUID(14, 1), "default"));
        assertEquals(1, g.hooks().bumpsOf(g.world(), g.region()), "one handler, one bump");
    }

    @Test @CaseId(value = "PB-15", critical = true)
    void oneEventBumpsEveryAffectedRegion() {
        var g = guarded();
        List<String> others = g.controls().affectedWith(g.region());
        assumeTrue(!others.isEmpty(), "the vendor has no grouped regions");
        assertTrue(g.change(g.region(), new UUID(15, 1), "default"));
        assertEquals(1, g.hooks().bumpsOf(g.world(), g.region()));
        for (String other : others) assertTrue(g.hooks().bumpsOf(g.world(), other) >= 1, other + " is bumped too");
    }

    @Test @CaseId(value = "PB-16", critical = true)
    void aRecreatedRegionWithTheSameIdIsANewIncarnation() {
        var subject = build();
        assumeTrue(subject.knowsRegions());
        // As in the plugin: only object or generation is strong; weak, missing or unknown is weak.
        String identity = subject.backend().capabilities().value("token-identity");
        assumeTrue("object".equals(identity) || "generation".equals(identity), "weak identity is declared");
        GuardControls controls = subject.guard();
        assumeTrue(controls != null, "no fake vendor to recreate a region");
        var backend = subject.backend();
        OwnershipToken before = backend.ownerReadRegion(subject.world(), subject.region(), subject.owner()).orElseThrow();
        controls.recreate(subject.region(), subject.owner());
        OwnershipToken after = backend.ownerReadRegion(subject.world(), subject.region(), subject.owner()).orElseThrow();
        assertFalse(before.sameIncarnation(after), "same id, same owner, new region: a new incarnation");
    }

    @Test @CaseId("PB-08")
    void onlyThePayingPrincipalIsReturned() {
        var subject = build();
        assumeTrue(subject.knowsRegions());
        UUID member = new UUID(8, 8);
        subject.addMember(subject.region(), member);
        var token = subject.backend().ownerReadRegion(subject.world(), subject.region(), subject.owner()).orElseThrow();
        assertEquals(subject.owner(), token.ownerId());
        assertEquals(subject.region(), token.regionId());
        assertEquals(subject.world(), token.worldId());
        assertTrue(subject.backend().ownerReadRegion(subject.world(), subject.region(), member).isEmpty(), "a member never pays");
        var view = subject.backend().findRegion("ignored-by-contract", subject.region());
        view.ifPresent(v -> assertEquals(subject.owner(), v.ownerUuid().orElse(null)));
    }

    @Test @CaseId("PB-09")
    void unpayableOwnersAreRefused() {
        var subject = build();
        assumeTrue(subject.knowsRegions());
        assertTrue(subject.unpayableOwners().contains(new UUID(0, 0)), "the zero UUID is always unpayable");
        for (UUID owner : subject.unpayableOwners()) {
            subject.setOwner(subject.region(), owner);
            assertTrue(subject.backend().ownerReadRegion(subject.world(), subject.region(), owner).isEmpty(), owner.toString());
        }
    }

    @Test @CaseId(value = "PB-12", critical = true)
    void observeTellsPresentMissingAndUnreadableWithoutThrowing() {
        var subject = build();
        var backend = subject.backend();
        assertNotEquals(Presence.PRESENT, backend.observe(subject.world(), "no-such-region-" + UUID.randomUUID()));
        assumeTrue(subject.knowsRegions());
        assertEquals(Presence.PRESENT, backend.observe(subject.world(), subject.region()));
        subject.removeRegion(subject.region());
        Presence gone = backend.observe(subject.world(), subject.region());
        assertNotEquals(Presence.PRESENT, gone, "a removed region is never PRESENT");
        if (backend.capabilities().flag("distinguishes-missing")) assertEquals(Presence.MISSING, gone);
        subject.makeUnreadable();
        assertNotEquals(Presence.PRESENT, backend.observe(subject.world(), subject.region()));
    }
}
