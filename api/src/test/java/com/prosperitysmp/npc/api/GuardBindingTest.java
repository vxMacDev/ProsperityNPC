package com.prosperitysmp.npc.api;

import com.prosperitysmp.npc.api.ApiTestSupport.BaseEvent;
import com.prosperitysmp.npc.api.ApiTestSupport.DerivedEvent;
import com.prosperitysmp.npc.api.ApiTestSupport.UnrelatedEvent;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** A guard binding hands its handler only events of its type, and lets whatever the handler throws reach the core. */
class GuardBindingTest {

    @Test void aHandlerForTheBaseTypeReceivesASubclassEvent() {
        var seen = new ArrayList<BaseEvent>();
        var binding = GuardBinding.of(BaseEvent.class, EventPriority.HIGHEST, false, seen::add);
        var derived = new DerivedEvent();
        binding.handle(derived);
        assertEquals(1, seen.size());
        assertSame(derived, seen.getFirst());
    }

    @Test void theTypeIsTheDeclaredTypeEvenWhenASubclassIsHandled() {
        var binding = GuardBinding.of(BaseEvent.class, EventPriority.NORMAL, false, e -> { });
        binding.handle(new DerivedEvent());
        assertEquals(BaseEvent.class, binding.type());
    }

    @Test void aHandlerForASubclassRefusesTheBaseEvent() {
        var seen = new AtomicInteger();
        var binding = GuardBinding.of(DerivedEvent.class, EventPriority.NORMAL, false, e -> seen.incrementAndGet());
        assertThrows(ClassCastException.class, () -> binding.handle(new BaseEvent()));
        assertEquals(0, seen.get());
    }

    @Test void aHandlerForAnUnrelatedTypeRefusesTheEventWithoutRunning() {
        var seen = new AtomicInteger();
        var binding = GuardBinding.of(UnrelatedEvent.class, EventPriority.LOW, true, e -> seen.incrementAndGet());
        assertThrows(ClassCastException.class, () -> binding.handle(new BaseEvent()));
        assertEquals(0, seen.get());
    }

    @Test void aHandlerOfAnyEventTypeAcceptsEveryEvent() {
        var seen = new ArrayList<Event>();
        Consumer<Event> any = seen::add;
        var binding = GuardBinding.of(Event.class, EventPriority.MONITOR, true, any);
        var a = new BaseEvent();
        var b = new UnrelatedEvent();
        binding.handle(a);
        binding.handle(b);
        assertEquals(List.of(a, b), seen);
    }

    @Test void theHandlerRunsOncePerCall() {
        var count = new AtomicInteger();
        var binding = GuardBinding.of(BaseEvent.class, EventPriority.NORMAL, false, e -> count.incrementAndGet());
        assertEquals(0, count.get(), "registering a binding runs nothing");
        binding.handle(new BaseEvent());
        binding.handle(new BaseEvent());
        assertEquals(2, count.get());
    }

    @Test void whatTheHandlerThrowsReachesTheCallerUnchanged() {
        var thrown = new IllegalStateException("vendor says no");
        var binding = GuardBinding.of(BaseEvent.class, EventPriority.NORMAL, false, e -> { throw thrown; });
        assertSame(thrown, assertThrows(IllegalStateException.class, () -> binding.handle(new BaseEvent())));
    }

    @Test void aLinkageErrorFromTheHandlerIsNotWrapped() {
        var thrown = new NoClassDefFoundError("vendor/Claim");
        var binding = GuardBinding.of(BaseEvent.class, EventPriority.NORMAL, false, e -> { throw thrown; });
        assertSame(thrown, assertThrows(NoClassDefFoundError.class, () -> binding.handle(new BaseEvent())));
    }

    @Test void ignoreCancelledFalseIsKept() {
        assertFalse(GuardBinding.of(BaseEvent.class, EventPriority.NORMAL, false, e -> { }).ignoreCancelled());
    }

    @Test void ignoreCancelledTrueIsKept() {
        assertTrue(GuardBinding.of(BaseEvent.class, EventPriority.NORMAL, true, e -> { }).ignoreCancelled());
    }

    @ParameterizedTest
    @EnumSource(EventPriority.class)
    void thePriorityIsKeptAndNamedAfterTheTypeInToString(EventPriority priority) {
        var binding = GuardBinding.of(BaseEvent.class, priority, false, e -> { });
        assertSame(priority, binding.priority());
        assertEquals("BaseEvent@" + priority.name(), binding.toString());
    }

    @Test void toStringUsesTheSimpleNameOfTheDeclaredType() {
        assertEquals("DerivedEvent@HIGHEST",
                GuardBinding.of(DerivedEvent.class, EventPriority.HIGHEST, false, e -> { }).toString());
    }

    @Test void twoBindingsOfTheSameTypeKeepTheirOwnSettings() {
        var a = GuardBinding.of(BaseEvent.class, EventPriority.HIGHEST, false, e -> { });
        var b = GuardBinding.of(BaseEvent.class, EventPriority.MONITOR, true, e -> { });
        assertEquals(EventPriority.HIGHEST, a.priority());
        assertFalse(a.ignoreCancelled());
        assertEquals(EventPriority.MONITOR, b.priority());
        assertTrue(b.ignoreCancelled());
    }

    @Test void aRefusedBindingNeverRunsItsHandler() {
        var count = new AtomicInteger();
        assertThrows(NullPointerException.class,
                () -> GuardBinding.of(BaseEvent.class, null, false, e -> count.incrementAndGet()));
        assertEquals(0, count.get());
    }
}
