package com.prosperitysmp.npc.interaction;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewSupersessionTest {
    @Test void supersededProfileCannotDeliverWhileReplacementIsStillLoading() throws Exception {
        var pending = new ConcurrentHashMap<UUID, UUID>();
        UUID player = new UUID(0, 1), old = new UUID(0, 2), current = new UUID(0, 3);
        var entered = new CountDownLatch(1);
        var releaseOld = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var oldDeliveries = new AtomicInteger();
        pending.put(player, old);
        Thread worker = Thread.ofPlatform().daemon().start(() -> {
            entered.countDown();
            try {
                if (!releaseOld.await(3, TimeUnit.SECONDS)) return;
                if (ResidentEncounterController.ownsOperation(pending, player, old)) oldDeliveries.incrementAndGet();
                pending.remove(player, old);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { finished.countDown(); }
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            pending.remove(player);
            assertNull(pending.putIfAbsent(player, current));
            releaseOld.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertEquals(0, oldDeliveries.get());
            assertEquals(current, pending.get(player));
            assertTrue(ResidentEncounterController.ownsOperation(pending, player, current));
        } finally { releaseOld.countDown(); worker.join(3000); }
    }

    @Test void dismissedOrDepartedPlayerCannotReceiveAnOldProfile() {
        var pending = new ConcurrentHashMap<UUID, UUID>();
        UUID player = new UUID(0, 1), token = new UUID(0, 2);
        pending.put(player, token); pending.remove(player);
        assertFalse(ResidentEncounterController.ownsOperation(pending, player, token));
    }

    @Test void commandSupersedesBothStagesBeforeSubmittingReplacement() throws IOException {
        List<String> command = calls("onCommand");
        assertTrue(command.indexOf("supersedePreview") >= 0);
        assertTrue(command.indexOf("supersedePreview") < command.indexOf("submit"));
        List<String> supersede = calls("supersedePreview");
        assertTrue(supersede.contains("remove"));
        assertTrue(supersede.contains("cancelPendingConversation"));
        assertTrue(supersede.contains("cancelPendingPreview"));
        assertFalse(supersede.contains("dismissPreviews"), "do not remove already-active visits");
    }

    @Test void supersedingPendingGreetingCannotStrandTheSession() {
        var sessions = new EncounterSessions();
        UUID player = new UUID(0, 1), resident = new UUID(0, 2), avatar = new UUID(0, 3), world = new UUID(0, 4);
        var old = sessions.open(player, resident, avatar, world, 1000).orElseThrow();
        assertTrue(sessions.isPending(player, old.token()));
        ResidentEncounterController.cancelPendingConversation(sessions, player, 1200);
        assertTrue(sessions.complete(player, old.token(), 1300).isEmpty());
        assertTrue(sessions.open(player, resident, avatar, world, 1999).isEmpty(), "cooldown is preserved");
        assertTrue(sessions.open(player, resident, avatar, world, 2000).isPresent());
    }

    @Test void readyConversationIsNotClosedByAnUnrelatedPreviewRequest() {
        var sessions = new EncounterSessions();
        UUID player = new UUID(0, 1), resident = new UUID(0, 2), avatar = new UUID(0, 3), world = new UUID(0, 4);
        var pending = sessions.open(player, resident, avatar, world, 1000).orElseThrow();
        var ready = sessions.complete(player, pending.token(), 1100).orElseThrow();
        ResidentEncounterController.cancelPendingConversation(sessions, player, 1200);
        assertEquals(ready, sessions.current(player, 1200).orElseThrow());
    }

    @Test void everyAsyncResidentCallbackChecksTheOperationToken() throws IOException {
        try (var bytes = ResidentEncounterController.class.getResourceAsStream("ResidentEncounterController.class")) {
            assertNotNull(bytes);
            var methods = ClassFile.of().parse(bytes.readAllBytes()).methods().stream()
                    .filter(method -> method.code().isPresent()).toList();
            long gates = methods.stream().flatMap(method -> method.code().orElseThrow().elementStream())
                    .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast)
                    .filter(call -> call.name().equalsString("ownsOperation")).count();
            // contracts-partnerships adds two: the hand-over authority and its result callback.
            assertEquals(6, gates, "async admission, main-thread delivery, the commerce order/collect authority, "
                    + "the training result callback and the contract hand-over authority and result must each use the tested token gate");
        }
    }

    private static List<String> calls(String name) throws IOException {
        try (var bytes = ResidentEncounterController.class.getResourceAsStream("ResidentEncounterController.class")) {
            assertNotNull(bytes);
            var method = ClassFile.of().parse(bytes.readAllBytes()).methods().stream()
                    .filter(m -> m.methodName().equalsString(name)).findFirst().orElseThrow();
            return method.code().orElseThrow().elementStream().filter(InvokeInstruction.class::isInstance)
                    .map(InvokeInstruction.class::cast).map(call -> call.name().stringValue()).toList();
        }
    }
}
