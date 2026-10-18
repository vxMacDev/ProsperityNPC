package com.prosperitysmp.npc.adapter.realty;

import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.contract.RecordingHooks;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static com.prosperitysmp.npc.adapter.realty.RealtySubject.*;
import static org.junit.jupiter.api.Assertions.*;

class RealtyBackendTest {
    final RealtySubject subject = new RealtySubject();
    final RealtyPropertyBackend backend = subject.backend;
    final RealtyModel model = subject.model;
    final ModelView view = subject.view;

    OwnershipToken read() {
        return backend.ownerReadRegion(WORLD, PLOT, OWNER).orElseThrow();
    }

    /** A backend on the same model that has not loaded anything yet. */
    RealtyPropertyBackend fresh() {
        return new RealtyPropertyBackend(view, subject.logged::add);
    }

    @Test void capabilitiesAreExactlyTheDocumentedValues() {
        var c = backend.capabilities();
        assertEquals("none", c.value("guard"));
        assertEquals("title holder or tenant; one player UUID", c.value("owner-model"));
        assertTrue(c.flag("location-lookup"));
        assertTrue(c.flag("region-by-id"));
        assertTrue(c.flag("distinguishes-missing"));
        assertEquals("object", c.value("token-identity"));
        assertFalse(c.flag("sees-owner-round-trip"));
        assertTrue(c.flag("needs-worldguard"));
        assertEquals("128", c.value("region-id-max-length"));
        assertTrue(c.flag("main-thread-only"));
        assertEquals(10, c.all().size());
    }

    @Test void identityIsDeclaredInTheShapeTheCoreReads() {
        assertEquals("realty", backend.id());
        assertEquals("Realty", backend.displayName());
        assertEquals("unverified", backend.label());
        assertTrue(backend.capabilities().value("owner-model").length() <= 80);
    }

    @Test void theGuardIsNoneAndDoesNotNeedALoadedSnapshot() {
        var result = fresh().installGuard(new RecordingHooks());
        assertEquals(GuardMode.NONE, result.mode().orElseThrow());
    }

    @Test void theGuardFailsWithTheReasonWhenRealtyIsMissing() {
        model.installed = false;
        var result = fresh().installGuard(new RecordingHooks());
        assertFalse(result.isInstalled());
        assertTrue(result.reason().contains("Realty is not enabled"), result.reason());
    }

    @Test void bindingsComeOnlyAfterASuccessfulInstall() {
        var other = fresh();
        assertEquals(List.of(), other.guardBindings());
        model.installed = false;
        other.installGuard(new RecordingHooks());
        assertEquals(List.of(), other.guardBindings());
        assertEquals(0, view.bindingCalls);
        model.installed = true;
        other.installGuard(new RecordingHooks());
        other.guardBindings();
        assertEquals(1, view.bindingCalls);
    }

    @Test void oneEventBumpsTheRegionAndEveryRegionBelowItOnce() {
        model.defineGuard(WORLD, "room", UNIT, 6, 6, 7, 7);
        var hooks = new RecordingHooks();
        subject.install(hooks);
        model.transfer(WORLD, PLOT, new UUID(0, 9)).runAll();
        assertEquals(1, hooks.bumpsOf(WORLD, PLOT));
        assertEquals(1, hooks.bumpsOf(WORLD, UNIT));
        assertEquals(1, hooks.bumpsOf(WORLD, "room"));
        assertEquals(3, hooks.bumps().size());
    }

    @Test void theCanonicalAndTheEventSpellingAreEachBumpedOnce() {
        model.addFreehold(WORLD, "MyPlot", OWNER, null, 30, 30, 35, 35);
        subject.settle();
        var hooks = new RecordingHooks();
        subject.install(hooks);
        model.fire("TitleTransferredEvent", WORLD, "myplot");
        assertEquals(1, hooks.bumpsOf(WORLD, "MyPlot"));
        assertEquals(1, hooks.bumpsOf(WORLD, "myplot"));
        assertEquals(2, hooks.bumps().size());
    }

    @Test void anEventOffTheMainThreadMarksTheRegionDirtyAndFinishesOnMain() {
        var hooks = new RecordingHooks();
        subject.install(hooks);
        view.runAsync(() -> model.fire("TitleTransferredEvent", WORLD, PLOT));
        view.pumpAsyncOnly();
        assertEquals(List.of(WORLD + "/" + PLOT), hooks.bumps());
        view.pumpMainOnly();
        assertEquals(1, hooks.bumpsOf(WORLD, PLOT));
        assertEquals(1, hooks.bumpsOf(WORLD, UNIT));
        assertEquals(List.of(), hooks.offMainMisuse());
        assertEquals(List.of(), hooks.failures());
    }

    @Test void aRegionAboveTheEventIsBumpedToo() {
        var hooks = new RecordingHooks();
        subject.install(hooks);
        model.fire("TitleTransferredEvent", WORLD, UNIT);
        assertEquals(1, hooks.bumpsOf(WORLD, UNIT));
        assertEquals(1, hooks.bumpsOf(WORLD, PLOT));
        assertEquals(2, hooks.bumps().size());
    }

    @Test void aWorldGuardObjectChangeReadsAsCannotReadAndWritesNothing() {
        assertEquals(Optional.of(OWNER), backend.findRegion(WORLD_NAME, PLOT).orElseThrow().ownerUuid());
        model.guard(WORLD, PLOT).identity = new Object();
        assertTrue(backend.findRegion(WORLD_NAME, PLOT).isEmpty(), "the first read after a WorldGuard reload");
        view.pump();
        assertEquals(Optional.of(OWNER), backend.findRegion(WORLD_NAME, PLOT).orElseThrow().ownerUuid());
    }

    @Test void aWorldWithoutAChildIndexReadsAsCannotReadAndWritesNothing() {
        view.childIndexFails = true;
        var other = fresh();
        other.start();
        view.pump();
        assertTrue(other.findRegion(WORLD_NAME, PLOT).isEmpty());
    }

    @Test void aRegionWhoseWorldGuardOwnerListDisagreesStillReadsWithNoOwner() {
        model.staffAddOwner(WORLD, PLOT, new UUID(0, 9)).runAll();
        assertTrue(backend.findRegion(WORLD_NAME, PLOT).orElseThrow().ownerUuid().isEmpty());
    }

    @Test void aLinkageErrorOnTheReaderThreadLatchesTheBackend() {
        assertTrue(backend.available());
        model.sourceLinkageFails = true;
        subject.settle();
        assertFalse(backend.available());
        assertTrue(backend.describe().getLast().contains("restart required"), backend.describe().toString());
    }

    @Test void aResetClearsTheViewToo() {
        backend.reset();
        assertEquals(1, view.resets);
    }

    @Test void aHandlerThatFailsIsReportedThroughTheHooks() {
        var hooks = new RecordingHooks();
        subject.install(hooks);
        view.descendantsFail = true;
        model.fire("TitleTransferredEvent", WORLD, PLOT);
        assertEquals(List.of("Realty event: IllegalStateException"), hooks.failures());
    }

    @Test void eventsThatDoNotChangeAHolderAreNotListened() {
        var hooks = new RecordingHooks();
        subject.install(hooks);
        model.setLandlord(WORLD, UNIT, RealtyModel.PartyKind.GROUP).runAll();
        assertEquals(List.of(), hooks.bumps());
    }

    @Test void theBackendIsUnavailableWhenRealtyIsDisabledOrTheVersionIsNotSupported() {
        assertTrue(backend.available());
        model.installed = false;
        assertFalse(backend.available());
        model.installed = true;
        assertTrue(backend.available());
        model.version = "2.1.0";
        assertFalse(backend.available());
        assertTrue(backend.describe().getLast().contains("not supported"), backend.describe().toString());
    }

    @Test void aLinkageErrorLatchesUntilANewBackendIsBuilt() {
        view.broken = true;
        assertFalse(backend.available());
        view.broken = false;
        assertFalse(backend.available());
        assertTrue(backend.describe().getLast().contains("restart"));
        var other = fresh();
        other.start();
        assertTrue(other.available());
    }

    @Test void theBackendIsUnavailableUntilAFullLoadAndAgainWhenTheLastOneIsTooOld() {
        var other = fresh();
        assertFalse(other.available());
        other.start();
        assertTrue(other.available());
        model.sourceFails = true;
        model.advance(Duration.ofMillis(RegionCache.Tuning.TTL + 1000));
        assertFalse(other.available());
    }

    @Test void startLoadsBeforeTheCoreBinds() {
        var other = fresh();
        other.start();
        assertTrue(other.available(), "no tick or pump was needed");
        assertTrue(startupPass(other, PLOT, OWNER));
    }

    @Test void aFirstLoadThatFailsLeavesThePayoutsHeldUntilALaterLoadSucceeds() {
        view.firstLoadFails = true;
        var other = fresh();
        other.start();
        assertFalse(other.available());
        assertEquals(1, subject.logged.size());
        assertFalse(startupPass(other, PLOT, OWNER), "the core never schedules its startup pass for this run");
        view.firstLoadFails = false;
        other.tick();
        view.pump();
        assertTrue(other.available());
        assertEquals(1, subject.logged.size());
    }

    @Test void aFirstLoadThatTimesOutIsLoggedOnceAndTheReaderKeepsWorking() {
        view.firstLoadTimesOut = true;
        var other = fresh();
        other.start();
        assertFalse(other.available());
        assertEquals(1, subject.logged.size());
        other.tick();
        view.pump();
        assertTrue(other.available());
    }

    /** What the core does: it runs its startup ownership pass only when the backend is ready as it binds it. */
    private static boolean startupPass(RealtyPropertyBackend bound, String region, UUID owner) {
        if (!bound.available()) return false;
        return bound.ownerReadRegion(WORLD, region, owner).isPresent();
    }

    @Test void findRegionIsEmptyWhileTheSlotIsStale() {
        assertTrue(backend.findRegion(WORLD_NAME, PLOT).isPresent());
        subject.install(new RecordingHooks());
        model.fire("TitleTransferredEvent", WORLD, PLOT);
        assertTrue(backend.findRegion(WORLD_NAME, PLOT).isEmpty());
        view.pump();
        assertTrue(backend.findRegion(WORLD_NAME, PLOT).isPresent());
    }

    @Test void findRegionDescribesAnOwnedRegionAndAgreesWithTheOwnerReads() {
        model.rent(WORLD, UNIT, TENANT, Duration.ofDays(7)).runAll();
        model.addFreehold(WORLD, "mine", OWNER, null, 30, 30, 35, 35);
        subject.settle();
        for (String id : List.of(PLOT, UNIT, "mine")) {
            var found = backend.findRegion(WORLD_NAME, id).orElseThrow();
            assertEquals("realty", found.market());
            assertEquals("owned", found.kind());
            assertEquals(WORLD, found.worldId());
            assertEquals(id, found.regionId());
            assertTrue(found.matches(WORLD_NAME, id.toUpperCase(Locale.ROOT)));
            assertFalse(found.matches("elsewhere", id));
            for (UUID candidate : List.of(OWNER, TENANT, new UUID(0, 99))) {
                boolean pays = backend.ownerReadRegion(WORLD, id, candidate).isPresent();
                assertEquals(pays, found.ownerUuid().equals(Optional.of(candidate)), id + " " + candidate);
            }
        }
        assertTrue(backend.findRegion("elsewhere", PLOT).isEmpty());
    }

    @Test void observeTellsPresentMissingAndUnreadable() {
        assertEquals(Presence.PRESENT, backend.observe(WORLD, PLOT));
        assertEquals(Presence.MISSING, backend.observe(WORLD, "nowhere"));
        assertEquals(Presence.MISSING, backend.observe(WORLD, ""));
        assertEquals(Presence.UNREADABLE, backend.observe(null, PLOT));
        model.guard(WORLD, PLOT).owners.clear();
        model.wg.remove(WORLD + "/" + PLOT);
        assertEquals(Presence.MISSING, backend.observe(WORLD, PLOT), "Realty knows it, WorldGuard readably does not");
        model.wgReadable = false;
        assertEquals(Presence.UNREADABLE, backend.observe(WORLD, PLOT));
    }

    @Test void observeIsUnreadableForAStaleOrUnloadedSlot() {
        subject.install(new RecordingHooks());
        model.fire("TitleTransferredEvent", WORLD, PLOT);
        assertEquals(Presence.UNREADABLE, backend.observe(WORLD, PLOT));
        view.pump();
        assertEquals(Presence.PRESENT, backend.observe(WORLD, PLOT));
        assertEquals(Presence.UNREADABLE, fresh().observe(WORLD, PLOT));
    }

    @Test void describeHasTwoLinesAndEndsWithTheVerificationText() {
        var lines = backend.describe();
        assertEquals(2, lines.size());
        assertTrue(lines.getLast().endsWith("; " + RealtyPropertyBackend.VERIFICATION), lines.getLast());
        assertTrue(lines.getLast().contains("clock skew bound unknown until a rent is seen"), lines.getLast());
        for (String line : lines) assertFalse(line.toLowerCase(Locale.ROOT).matches(".*(password|secret|token=).*"), line);
    }

    @Test void describeReportsTheCountsAndTheHeldPlots() {
        assertTrue(backend.describe().getLast().contains("plots cached 2"), backend.describe().toString());
        assertTrue(backend.describe().getLast().contains("held 0"));
    }

    @Test void whenRealtyGoesAwayAndComesBackOldTokensDoNotMatchNewOnes() {
        var before = read();
        backend.reset();
        assertTrue(backend.ownerReadRegion(WORLD, PLOT, OWNER).isEmpty());
        assertEquals(Presence.UNREADABLE, backend.observe(WORLD, PLOT));
        backend.tick();
        view.pump();
        var after = read();
        assertFalse(before.sameIncarnation(after));
    }

    @Test void aWorldGuardReloadEndsTheToken() {
        var before = read();
        model.guard(WORLD, PLOT).identity = new Object();
        assertTrue(backend.ownerReadRegion(WORLD, PLOT, OWNER).isEmpty(), "the first read after a reload reloads the plot");
        view.pump();
        var after = read();
        assertFalse(before.sameIncarnation(after));
        assertTrue(after.sameIncarnation(read()));
    }

    @Test void aReaderThatFailsKeepsServingUntilTheLimitsRunOut() {
        read();
        model.sourceFails = true;
        subject.model.advance(Duration.ofMillis(RegionCache.Tuning.HOT_TTL - 1000));
        backend.tick();
        view.pump();
        assertTrue(backend.ownerReadRegion(WORLD, PLOT, OWNER).isPresent());
        model.advance(Duration.ofMillis(2000));
        assertTrue(backend.ownerReadRegion(WORLD, PLOT, OWNER).isEmpty(), "a hot plot that was not confirmed in time");
        assertTrue(backend.available());
        model.advance(Duration.ofMillis(RegionCache.Tuning.TTL));
        assertFalse(backend.available());
    }

    @Test void idsAreCheckedBeforeAnythingIsAsked() {
        String longest = "p".repeat(128);
        model.addFreehold(WORLD, longest, OWNER, null, 40, 40, 41, 41);
        subject.settle();
        int calls = view.guardCalls;
        assertTrue(backend.ownerReadRegion(WORLD, longest, OWNER).isPresent(), "128 characters fit");
        assertTrue(view.guardCalls > calls);
        calls = view.guardCalls;
        for (String id : List.of("p".repeat(129), "", "   ", "__global__", "__GLOBAL__")) {
            assertTrue(backend.ownerReadRegion(WORLD, id, OWNER).isEmpty(), id);
            assertTrue(backend.findRegion(WORLD_NAME, id).isEmpty(), id);
            assertEquals(Presence.MISSING, backend.observe(WORLD, id), id);
        }
        assertEquals(calls, view.guardCalls);
    }

    @Test void unknownAndZeroOwnersNeverPay() {
        assertTrue(backend.ownerReadRegion(null, PLOT, OWNER).isEmpty());
        assertTrue(backend.ownerReadRegion(WORLD, PLOT, null).isEmpty());
        assertTrue(backend.ownerReadRegion(WORLD, PLOT, new UUID(0, 0)).isEmpty());
        assertTrue(backend.ownerReadRegion(new UUID(0, 1), PLOT, OWNER).isEmpty());
    }

    @Test void theStatusTextClaimsOnlyModelTests() throws Exception {
        assertEquals("unverified", backend.label());
        assertEquals("compiled against Realty 2.0.0 and booted on a test server; not tested with real players", RealtyPropertyBackend.VERIFICATION);
        assertFalse(RealtyPropertyBackend.VERIFICATION.isBlank());
    }

    @Test void heldPlotsCanBeListedAndReleasedByWorldName() {
        subject.install(new RecordingHooks());
        model.deleteRegion(WORLD, PLOT, false);
        model.addFreehold(WORLD, PLOT, new UUID(0, 77), null, 0, 0, 20, 20);
        model.fire("RegionDeletedEvent", WORLD, PLOT);
        model.fire("RegionCreatedEvent", WORLD, PLOT);
        view.pump();
        assertEquals(List.of(WORLD_NAME + " " + PLOT), backend.held());
        assertTrue(backend.findRegion(WORLD_NAME, PLOT).isEmpty());
        assertFalse(backend.release("no-such-world", PLOT));
        assertTrue(backend.release(WORLD_NAME, PLOT));
        assertEquals(List.of(), backend.held());
        assertFalse(backend.release(WORLD_NAME, PLOT));
    }
}
