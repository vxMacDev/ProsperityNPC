package com.prosperitysmp.npc.interaction;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the controller's production branch helper, not a Bukkit UI implementation. */
class ResidentPresentationTest {
    @Test void initiallyDeniedDoesNotInvokeAnyPresentationAndCleansOwnedView() {
        var calls = new ArrayList<String>();
        ResidentPresentation.show(() -> false, () -> {fail("no UI call");return true;},
                () -> calls.add("revoke"), () -> calls.add("close"), () -> fail("no text"), e -> fail(e));
        assertEquals(List.of("revoke", "close"), calls);
    }

    @Test void successfulPresentationDoesNotCloseOrDuplicateIntoChat() {
        ResidentPresentation.show(() -> true, () -> true, () -> fail("not revoked"),
                () -> fail("not closed"), () -> fail("not duplicated"), e -> fail(e));
    }

    @Test void unavailableUiFallsBackOnlyAfterCleanupAndReauthorization() {
        var calls = new ArrayList<String>();
        ResidentPresentation.show(() -> {calls.add("authorize");return true;}, () -> {calls.add("render");return false;},
                () -> fail("not revoked"), () -> calls.add("close"), () -> calls.add("text"), e -> fail(e));
        assertEquals(List.of("authorize", "render", "authorize", "close", "authorize", "text"), calls);
    }

    @Test void renderRevocationSuppressesFallbackForEitherShownResult() {
        for (boolean shown : List.of(false, true)) {
            var allowed = new AtomicBoolean(true);
            var calls = new ArrayList<String>();
            ResidentPresentation.show(allowed::get, () -> {allowed.set(false);return shown;},
                    () -> calls.add("revoke"), () -> calls.add("close"), () -> fail("revoked private text"), e -> fail(e));
            assertEquals(List.of("revoke", "close"), calls);
        }
    }

    @Test void cleanupReplacementPreservesNewRealSessionAndDoesNotSendOldText() {
        var sessions = new EncounterSessions();
        var player = new UUID(0, 1);var resident = new UUID(0, 2);var avatar = new UUID(0, 3);var world = new UUID(0, 4);
        var old = sessions.open(player, resident, avatar, world, 0).orElseThrow();
        var ready = sessions.complete(player, old.token(), 1).orElseThrow();
        ResidentPresentation.show(() -> sessions.current(player, 2000).map(t -> t.token().equals(ready.token())).orElse(false),
                () -> false, () -> sessions.close(player, ready.token()), () -> {
                    sessions.close(player, ready.token());
                    sessions.open(player, new UUID(0, 9), avatar, world, 2000).orElseThrow();
                }, () -> fail("private history must not follow a replacement"), e -> fail(e));
        assertEquals(new UUID(0, 9), sessions.current(player, 2000).orElseThrow().residentId());
    }

    @Test void adapterExceptionOnlyFallsBackWhileStillAuthorized() {
        for (boolean revoke : List.of(false, true)) for (boolean linkage : List.of(false, true)) {
            var allowed = new AtomicBoolean(true);var calls = new ArrayList<String>();
            ResidentPresentation.show(allowed::get, () -> {
                allowed.set(!revoke);
                if (linkage) throw new NoClassDefFoundError("fixture only");
                throw new IllegalStateException("fixture only");
            }, () -> calls.add("revoke"), () -> calls.add("close"), () -> calls.add("text"), e -> calls.add("report"));
            assertEquals(revoke ? List.of("report", "revoke", "close") : List.of("report", "close", "text"), calls);
        }
    }
}
