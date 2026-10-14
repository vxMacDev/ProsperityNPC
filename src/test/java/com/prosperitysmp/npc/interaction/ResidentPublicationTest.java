package com.prosperitysmp.npc.interaction;

import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real session/publication primitives plus compiled wiring; not a Bukkit or client-render test. */
class ResidentPublicationTest {
    private static final UUID PLAYER = new UUID(0, 1), RESIDENT = new UUID(0, 2),
            AVATAR = new UUID(0, 3), WORLD = new UUID(0, 4);

    @Test void oldCloseDuringOpenPreservesRotatedTicketAndPublishes() {
        var sessions = new EncounterSessions();
        var pending = sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 0).orElseThrow();
        var old = sessions.complete(PLAYER, pending.token(), 1).orElseThrow();
        var next = sessions.complete(PLAYER, sessions.admit(PLAYER, old.token(), 2000).orElseThrow().token(), 2001).orElseThrow();
        var populated = new AtomicBoolean();
        assertTrue(OwnerDeskPublication.publish(() -> {
            sessions.close(PLAYER, old.token());
            return true;
        }, () -> sessions.current(PLAYER, 2001).orElseThrow().token().equals(next.token()),
                () -> populated.set(true), () -> fail("new ticket must survive old close")));
        assertTrue(populated.get());
    }

    @Test void reentrantReplacementNeverPublishesOldPrivateHistoryOrDiscardsNewSession() {
        var sessions = new EncounterSessions();
        var old = sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 0).orElseThrow();
        var ready = sessions.complete(PLAYER, old.token(), 1).orElseThrow();
        var now = new AtomicLong(2000);
        var discarded = new AtomicBoolean();
        assertFalse(OwnerDeskPublication.publish(() -> {
            sessions.close(PLAYER, ready.token());
            sessions.open(PLAYER, new UUID(0, 9), AVATAR, WORLD, now.get()).orElseThrow();
            return true;
        }, () -> sessions.current(PLAYER, now.get()).map(t -> t.token().equals(ready.token())).orElse(false),
                () -> fail("private history belongs to the old conversation"), () -> {
                    discarded.set(true);
                    sessions.close(PLAYER, ready.token());
                }));
        assertTrue(discarded.get());
        assertEquals(new UUID(0, 9), sessions.current(PLAYER, now.get()).orElseThrow().residentId());
    }

    @Test void currentViewCloseRevokesTicketRatherThanAuthorizingFallback() {
        var sessions = new EncounterSessions();
        var pending = sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 0).orElseThrow();
        var ready = sessions.complete(PLAYER, pending.token(), 1).orElseThrow();
        assertFalse(OwnerDeskPublication.publish(() -> true, () -> false,
                () -> fail("revoked"), () -> sessions.close(PLAYER, ready.token())));
        assertTrue(sessions.current(PLAYER, 1).isEmpty());
    }

    @Test void actualMenuAndControllerUseTestedPublicationBoundaries() throws Exception {
        var menu = calls(ResidentMenu.class, "show");
        assertTrue(menu.contains("publish"));
        assertFalse(menu.contains("setItem"), "private item writes belong inside the deferred populate callback");
        var controller = calls(ResidentEncounterController.class, "show");
        assertTrue(controller.contains("show"));
        try (var bytes = ResidentEncounterController.class.getResourceAsStream("ResidentEncounterController.class")) {
            assertNotNull(bytes);
            var invocations = ClassFile.of().parse(bytes.readAllBytes()).methods().stream()
                    .filter(m -> m.methodName().equalsString("show")).findFirst().orElseThrow()
                    .code().orElseThrow().elementStream().filter(InvokeInstruction.class::isInstance)
                    .map(InvokeInstruction.class::cast).toList();
            assertTrue(invocations.stream().anyMatch(i -> i.owner().asInternalName().equals(
                    "com/prosperitysmp/npc/interaction/ResidentPresentation") && i.name().equalsString("show")));
        }
    }

    private static List<String> calls(Class<?> type, String name) throws Exception {
        try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            assertNotNull(bytes);
            return ClassFile.of().parse(bytes.readAllBytes()).methods().stream()
                    .filter(m -> m.methodName().equalsString(name)).findFirst().orElseThrow()
                    .code().orElseThrow().elementStream().filter(InvokeInstruction.class::isInstance)
                    .map(InvokeInstruction.class::cast).map(i -> i.name().stringValue()).toList();
        }
    }
}
