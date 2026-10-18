package com.prosperitysmp.npc.api;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The small API types adapters build and read: what they accept, refuse and report. */
class ApiValueTypesTest {

    @Test void capabilitiesReadUnknownNamesAsFalseOrEmpty() {
        var caps = Capabilities.builder().flag("scale-exact", true).value("scale", "2").value("odd", "yes").build();
        assertTrue(caps.flag("scale-exact"));
        assertFalse(caps.flag("odd"), "only the exact text true is a set flag");
        assertFalse(caps.flag("missing"));
        assertEquals("2", caps.value("scale"));
        assertEquals("", caps.value("missing"));
        assertEquals(List.of("odd", "scale", "scale-exact"), new ArrayList<>(caps.all().keySet()));
    }

    @Test void capabilitiesAreFrozenWhenBuilt() {
        var builder = Capabilities.builder().value("token-identity", "object");
        var caps = builder.build();
        builder.value("token-identity", "weak");
        assertEquals("object", caps.value("token-identity"));
        assertThrows(UnsupportedOperationException.class, () -> caps.all().put("x", "y"));
    }

    @Test void capabilitiesRefuseBlankAndNullNames() {
        assertThrows(IllegalArgumentException.class, () -> Capabilities.builder().value(" ", "x"));
        assertThrows(NullPointerException.class, () -> Capabilities.builder().value(null, "x"));
        assertThrows(NullPointerException.class, () -> Capabilities.builder().value("x", null));
    }

    @Test void aGuardResultIsEitherInstalledWithAModeOrFailedWithAReason() {
        var installed = GuardResult.installed(GuardMode.VETO_EVENTS);
        assertTrue(installed.isInstalled());
        assertEquals(Optional.of(GuardMode.VETO_EVENTS), installed.mode());
        assertEquals("", installed.reason());

        var failed = GuardResult.failed("plugin missing");
        assertFalse(failed.isInstalled());
        assertEquals(Optional.empty(), failed.mode());
        assertEquals("plugin missing", failed.reason());

        assertThrows(NullPointerException.class, () -> GuardResult.installed(null));
        assertThrows(NullPointerException.class, () -> GuardResult.failed(null));
    }

    @Test void theApiVersionIsOnePointTwo() {
        assertEquals(1, ProsperityNpcApi.MAJOR);
        assertEquals(2, ProsperityNpcApi.MINOR);
    }

    @Test void aPieceHasNoLabelUnlessItSetsOne() {
        Piece piece = new Piece() {
            @Override public String id() { return "test-piece"; }
            @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
            @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
            @Override public Capabilities capabilities() { return Capabilities.builder().build(); }
        };
        assertEquals("", piece.label());
    }

    static class TestEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    static final class OtherEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    @Test void aGuardBindingKeepsItsSettingsAndPassesItsOwnEventType() {
        var seen = new ArrayList<TestEvent>();
        var binding = GuardBinding.of(TestEvent.class, EventPriority.MONITOR, true, seen::add);
        assertEquals(TestEvent.class, binding.type());
        assertEquals(EventPriority.MONITOR, binding.priority());
        assertTrue(binding.ignoreCancelled());
        assertEquals("TestEvent@MONITOR", binding.toString());

        var event = new TestEvent();
        binding.handle(event);
        assertEquals(List.of(event), seen);
        assertThrows(ClassCastException.class, () -> binding.handle(new OtherEvent()),
                "a handler never receives an event of another type");
        assertEquals(1, seen.size());
    }

    @Test void aGuardBindingRefusesMissingParts() {
        assertThrows(NullPointerException.class, () -> GuardBinding.of(null, EventPriority.HIGHEST, false, e -> { }));
        assertThrows(NullPointerException.class, () -> GuardBinding.of(TestEvent.class, null, false, e -> { }));
        assertThrows(NullPointerException.class, () -> GuardBinding.of(TestEvent.class, EventPriority.HIGHEST, false, null));
    }
}
