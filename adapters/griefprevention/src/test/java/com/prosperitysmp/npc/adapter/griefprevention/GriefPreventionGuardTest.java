package com.prosperitysmp.npc.adapter.griefprevention;

import com.prosperitysmp.npc.adapter.griefprevention.GriefPreventionView.Claim;
import com.prosperitysmp.npc.api.GuardBinding;
import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.contract.RecordingHooks;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static com.prosperitysmp.npc.adapter.griefprevention.ApiGpFakeView.OTHER_WORLD;
import static com.prosperitysmp.npc.adapter.griefprevention.ApiGpFakeView.OWNER;
import static com.prosperitysmp.npc.adapter.griefprevention.ApiGpFakeView.WORLD;
import static org.junit.jupiter.api.Assertions.*;

/** The guard: what it installs, which transfers it cancels, and which claims it moves the fence on for. */
class GriefPreventionGuardTest {
    private static final String HELD_LINE = "ProsperityNPC held a claim transfer on 10: an ownership operation was in progress.";

    private final ApiGpFakeView view = new ApiGpFakeView();
    private final List<String> logged = new ArrayList<>();
    private final GriefPreventionBackend backend = new GriefPreventionBackend(view, logged::add);
    private final RecordingHooks hooks = new RecordingHooks();

    /** Hooks that forward to the recorder but fail where told to, the way a changed vendor plugin would. */
    private static final class FailingHooks implements OwnershipHooks {
        final RecordingHooks inner = new RecordingHooks();
        Throwable bumpThrows, heldThrows;

        @SuppressWarnings("unchecked")
        private static <T extends Throwable> void sneakyThrow(Throwable failure) throws T { throw (T) failure; }

        @Override public Runnable beginWrite(UUID world, String regionId) { return inner.beginWrite(world, regionId); }
        @Override public void externalChange(UUID world, String regionId) {
            if (bumpThrows != null) sneakyThrow(bumpThrows);
            inner.externalChange(world, regionId);
        }
        @Override public void markDirty(UUID world, String regionId) { inner.markDirty(world, regionId); }
        @Override public boolean isHeld(UUID world, String regionId) {
            if (heldThrows != null) sneakyThrow(heldThrows);
            return inner.isHeld(world, regionId);
        }
        @Override public void guarded(String what, Runnable body) { inner.guarded(what, body); }
    }

    private GriefPreventionView.Events installAndGetEvents() {
        assertTrue(backend.installGuard(hooks).isInstalled());
        backend.guardBindings();
        return view.events;
    }

    private Claim parent() { return view.claims.get(10L); }

    private Claim child() { return view.claims.get(11L); }

    // ---- install and bindings ---------------------------------------------------------------------------------

    @Test void withoutRealEstateTheGuardIsVetoEvents() {
        assertEquals(GuardMode.VETO_EVENTS, backend.installGuard(hooks).mode().orElseThrow());
    }

    @Test void withRealEstateTheGuardIsNone() {
        view.realEstate = true;
        var result = backend.installGuard(hooks);
        assertTrue(result.isInstalled(), "a guard of none still counts as installed");
        assertEquals(GuardMode.NONE, result.mode().orElseThrow());
    }

    @Test void aFailingRealEstateProbeInstallsNoneBecauseNothingMustBeCancelledWhenInDoubt() {
        view.realEstateThrows = new NoClassDefFoundError("net/alex9849/RealEstate");
        assertEquals(GuardMode.NONE, backend.installGuard(hooks).mode().orElseThrow());
    }

    @Test void installingWhileGriefPreventionIsUnavailableFailsWithAReason() {
        view.available = false;
        var result = backend.installGuard(hooks);
        assertFalse(result.isInstalled());
        assertEquals("GriefPrevention is not available", result.reason());
        assertTrue(result.mode().isEmpty());
    }

    @Test void aFailedInstallLeavesTheBackendFreeToInstallWhenGriefPreventionReturns() {
        view.available = false;
        assertFalse(backend.installGuard(hooks).isInstalled());
        view.available = true;
        assertEquals(GuardMode.VETO_EVENTS, backend.installGuard(hooks).mode().orElseThrow());
        assertTrue(backend.available());
    }

    @Test void installingWithNoHooksIsRefused() {
        assertThrows(NullPointerException.class, () -> backend.installGuard(null));
    }

    @Test void thereAreNoBindingsBeforeTheGuardIsInstalled() {
        assertEquals(List.of(), backend.guardBindings());
        assertEquals(0, view.bindingsCalls);
    }

    @Test void theBindingsAreWhatTheViewRegistersForTheBackendsHandlers() {
        var binding = GuardBinding.of(Event.class, EventPriority.HIGHEST, true, e -> { });
        view.bindingList = List.of(binding);
        assertTrue(backend.installGuard(hooks).isInstalled());
        var bindings = backend.guardBindings();
        assertEquals(1, bindings.size());
        assertSame(binding, bindings.getFirst());
        assertEquals(1, view.bindingsCalls);
        assertNotNull(view.events);
    }

    @Test void aGuardOfNoneStillDeliversItsBindingsSoTransfersMoveTheFenceOn() {
        view.realEstate = true;
        view.bindingList = List.of(GuardBinding.of(Event.class, EventPriority.MONITOR, true, e -> { }));
        assertTrue(backend.installGuard(hooks).isInstalled());
        assertEquals(1, backend.guardBindings().size());
    }

    @Test void installingAgainSendsLaterEventsToTheNewHooks() {
        var second = new RecordingHooks();
        assertTrue(backend.installGuard(hooks).isInstalled());
        assertTrue(backend.installGuard(second).isInstalled());
        backend.guardBindings();
        view.events.deleted(parent());
        assertEquals(0, hooks.bumpsOf(WORLD, "10"));
        assertEquals(1, second.bumpsOf(WORLD, "10"));
    }

    // ---- which transfers are cancelled ------------------------------------------------------------------------

    @Test void aTransferOfAClaimNothingHoldsIsNotCancelledAndNothingIsLogged() {
        var events = installAndGetEvents();
        assertFalse(events.transferRequested(parent()));
        assertEquals(List.of(), logged);
    }

    @Test void aTransferOfAHeldClaimIsCancelledAndLoggedOnce() {
        var events = installAndGetEvents();
        hooks.hold(WORLD, "10");
        assertTrue(events.transferRequested(parent()));
        assertEquals(List.of(HELD_LINE), logged);
    }

    @Test void everyCancelledTransferIsLogged() {
        var events = installAndGetEvents();
        hooks.hold(WORLD, "10");
        events.transferRequested(parent());
        events.transferRequested(parent());
        assertEquals(List.of(HELD_LINE, HELD_LINE), logged);
    }

    @Test void aHeldSubdivisionCancelsTheTransferOfItsParent() {
        var events = installAndGetEvents();
        hooks.hold(WORLD, "11");
        assertTrue(events.transferRequested(parent()));
        assertEquals(List.of(HELD_LINE), logged);
    }

    @Test void aHeldParentDoesNotCancelTheTransferOfOneOfItsSubdivisions() {
        var events = installAndGetEvents();
        hooks.hold(WORLD, "10");
        assertFalse(events.transferRequested(child()));
        assertEquals(List.of(), logged);
    }

    @Test void aHoldOnAnUnrelatedClaimCancelsNothing() {
        var events = installAndGetEvents();
        hooks.hold(WORLD, "99");
        assertFalse(events.transferRequested(parent()));
    }

    @Test void aHoldOnTheSameIdInAnotherWorldCancelsNothing() {
        var events = installAndGetEvents();
        hooks.hold(OTHER_WORLD, "10");
        assertFalse(events.transferRequested(parent()));
        assertEquals(List.of(), logged);
    }

    @Test void aClaimInAnotherWorldIsHeldByThatWorldsHold() {
        var events = installAndGetEvents();
        var elsewhere = new Claim(50, OTHER_WORLD, "gp-api-other", null, OWNER, new Object(), List.of());
        hooks.hold(WORLD, "50");
        assertFalse(events.transferRequested(elsewhere));
        hooks.hold(OTHER_WORLD, "50");
        assertTrue(events.transferRequested(elsewhere));
        assertEquals(List.of("ProsperityNPC held a claim transfer on 50: an ownership operation was in progress."), logged);
    }

    @Test void withRealEstateInstalledAHeldTransferIsNeverCancelled() {
        view.realEstate = true;
        var events = installAndGetEvents();
        hooks.hold(WORLD, "10");
        assertFalse(events.transferRequested(parent()));
        assertEquals(List.of(), logged);
    }

    @Test void ifRealEstateAppearsAfterInstallATransferIsStillNeverCancelled() {
        var events = installAndGetEvents();
        view.realEstate = true;
        hooks.hold(WORLD, "10");
        assertFalse(events.transferRequested(parent()));
        assertEquals(List.of(), logged);
    }

    @Test void ifTheRealEstateProbeFailsATransferIsNeverCancelled() {
        var events = installAndGetEvents();
        view.realEstateThrows = new NoClassDefFoundError("net/alex9849/RealEstate");
        hooks.hold(WORLD, "10");
        assertFalse(events.transferRequested(parent()));
    }

    @Test void aFailingHeldCheckIsReportedToTheHooksAndDoesNotCancel() {
        var failing = new FailingHooks();
        failing.heldThrows = new NoClassDefFoundError("vendor/Claim");
        assertTrue(backend.installGuard(failing).isInstalled());
        backend.guardBindings();
        assertFalse(view.events.transferRequested(parent()));
        assertEquals(List.of("ClaimTransferEvent: NoClassDefFoundError"), failing.inner.failures());
        assertEquals(List.of(), logged);
    }

    // ---- which regions the fence moves on for -----------------------------------------------------------------

    static Stream<Arguments> bumpingEvents() {
        BiConsumer<GriefPreventionView.Events, Claim> transferred = GriefPreventionView.Events::transferred;
        BiConsumer<GriefPreventionView.Events, Claim> deleted = GriefPreventionView.Events::deleted;
        BiConsumer<GriefPreventionView.Events, Claim> changed = GriefPreventionView.Events::changed;
        return Stream.of(
                Arguments.of("transferred", transferred, "ClaimTransferEvent"),
                Arguments.of("deleted", deleted, "ClaimDeletedEvent"),
                Arguments.of("changed", changed, "ClaimChangeEvent"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bumpingEvents")
    void anAppliedEventMovesTheFenceOnForTheClaimAndEachSubdivisionInOrder(String name,
            BiConsumer<GriefPreventionView.Events, Claim> event, String label) {
        var events = installAndGetEvents();
        event.accept(events, parent());
        assertEquals(List.of(RecordingHooks.key(WORLD, "10"), RecordingHooks.key(WORLD, "11")), hooks.bumps());
        assertEquals(List.of(), hooks.failures());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bumpingEvents")
    void anEventOnASubdivisionMovesTheFenceOnForItAlone(String name, BiConsumer<GriefPreventionView.Events, Claim> event,
                                                        String label) {
        var events = installAndGetEvents();
        event.accept(events, child());
        assertEquals(List.of(RecordingHooks.key(WORLD, "11")), hooks.bumps());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bumpingEvents")
    void aFailingBumpIsReportedUnderTheNameOfTheEvent(String name, BiConsumer<GriefPreventionView.Events, Claim> event,
                                                      String label) {
        var failing = new FailingHooks();
        failing.bumpThrows = new NoClassDefFoundError("vendor/Claim");
        assertTrue(backend.installGuard(failing).isInstalled());
        backend.guardBindings();
        assertDoesNotThrow(() -> event.accept(view.events, parent()));
        assertEquals(List.of(label + ": NoClassDefFoundError"), failing.inner.failures());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bumpingEvents")
    void aBumpHappensEvenWhileTheRegionIsHeldAndWithRealEstate(String name,
            BiConsumer<GriefPreventionView.Events, Claim> event, String label) {
        view.realEstate = true;
        var events = installAndGetEvents();
        hooks.hold(WORLD, "10");
        event.accept(events, parent());
        assertEquals(1, hooks.bumpsOf(WORLD, "10"));
        assertEquals(1, hooks.bumpsOf(WORLD, "11"));
    }

    @Test void everySubdivisionOfAClaimIsBumpedInTheOrderGriefPreventionListsThem() {
        var events = installAndGetEvents();
        events.deleted(ApiGpFakeView.newClaim(60, null, OWNER, new Object(), 63L, 61L, 62L));
        assertEquals(List.of(RecordingHooks.key(WORLD, "60"), RecordingHooks.key(WORLD, "63"),
                RecordingHooks.key(WORLD, "61"), RecordingHooks.key(WORLD, "62")), hooks.bumps());
    }

    @Test void aClaimWithoutSubdivisionsBumpsOnlyItself() {
        var events = installAndGetEvents();
        events.changed(ApiGpFakeView.newClaim(70, null, OWNER, new Object()));
        assertEquals(List.of(RecordingHooks.key(WORLD, "70")), hooks.bumps());
    }

    @Test void theBumpUsesTheClaimsOwnWorld() {
        var events = installAndGetEvents();
        events.transferred(new Claim(80, OTHER_WORLD, "gp-api-other", null, OWNER, new Object(), List.of(81L)));
        assertEquals(List.of(RecordingHooks.key(OTHER_WORLD, "80"), RecordingHooks.key(OTHER_WORLD, "81")), hooks.bumps());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, Long.MAX_VALUE})
    void theRegionIdOfABumpIsThePlainDecimalClaimId(long id) {
        var events = installAndGetEvents();
        events.changed(ApiGpFakeView.newClaim(id, null, OWNER, new Object()));
        assertEquals(List.of(RecordingHooks.key(WORLD, Long.toString(id))), hooks.bumps());
    }
}
