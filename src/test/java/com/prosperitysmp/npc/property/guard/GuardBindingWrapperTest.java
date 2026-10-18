package com.prosperitysmp.npc.property.guard;

import com.prosperitysmp.npc.api.GuardBinding;
import com.prosperitysmp.npc.arm.OwnershipFence;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GuardBindingWrapperTest {
    static final class OwnerEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    static final class OtherEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    @Test void aThrowingHandlerMarksTheBackendUnavailableAndNothingEscapes() throws Exception {
        var failures = new ArrayList<String>();
        var guard = PropertyGuard.waiting("x");
        guard.installed(com.prosperitysmp.npc.api.GuardResult.installed(com.prosperitysmp.npc.api.GuardMode.VETO_EVENTS),
                PropertyGuard.Identity.STRONG, () -> true);
        var binding = GuardBinding.of(OwnerEvent.class, EventPriority.MONITOR, true,
                event -> { throw new NoSuchMethodError("getOwnerUID"); });
        var executor = GuardBindingWrapper.executor(binding, (what, failure) -> {
            failures.add(what + ": " + failure.getClass().getSimpleName());
            guard.fail(what);
        });
        assertDoesNotThrow(() -> executor.execute(new GuardBindingWrapper.GuardListener(), new OwnerEvent()));
        assertEquals(List.of(OwnerEvent.class.getName() + ": NoSuchMethodError"), failures);
        assertFalse(guard.ready(), "payments are held through the readiness gate");
    }

    @Test void aWorkingHandlerRunsOnlyForItsOwnEventType() throws Exception {
        int[] calls = {0};
        var binding = GuardBinding.of(OwnerEvent.class, EventPriority.HIGHEST, false, event -> calls[0]++);
        var executor = GuardBindingWrapper.executor(binding, (what, failure) -> fail("no failure expected"));
        executor.execute(new GuardBindingWrapper.GuardListener(), new OwnerEvent());
        executor.execute(new GuardBindingWrapper.GuardListener(), new OtherEvent());
        assertEquals(1, calls[0]);
        assertEquals(EventPriority.HIGHEST, binding.priority());
        assertFalse(binding.ignoreCancelled());
    }

    @Test void fenceHooksTurnAThrowIntoAFailureAndForwardTheRest() {
        var failures = new ArrayList<String>();
        var fence = new OwnershipFence(() -> true);
        var hooks = new FenceHooks(fence, (what, failure) -> failures.add(what));
        hooks.guarded("plot event", () -> { throw new IllegalStateException("bug"); });
        assertEquals(List.of("plot event"), failures);
        UUID world = new UUID(1, 1);
        assertFalse(hooks.isHeld(world, "r"));
        var lease = fence.acquireExclusive(world, "r");
        assertTrue(hooks.isHeld(world, "r"));
        hooks.externalChange(world, "r");
        lease.releaseReconciled();
        int[] ran = {0};
        hooks.guarded("ok", () -> ran[0]++);
        assertEquals(1, ran[0]);
    }
}
