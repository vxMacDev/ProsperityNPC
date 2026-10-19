package com.prosperitysmp.npc.economy.accounting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Choosing the accounting sink by its config value, and what each sink reports. */
class AccountingSinkTest {

    @Test
    void theSkriptValueSelectsTheSkriptSink() {
        AccountingSink sink = AccountingSink.forId("skript", () -> true);
        assertInstanceOf(SkriptSink.class, sink);
        assertEquals("skript", sink.id());
        assertFalse(sink.selfAcknowledges());
    }

    @Test
    void theInternalValueSelectsTheInternalSinkWithoutReadingTheBridge() {
        AtomicInteger reads = new AtomicInteger();
        AccountingSink sink = AccountingSink.forId("internal", () -> { reads.incrementAndGet(); return false; });
        assertInstanceOf(InternalSink.class, sink);
        assertEquals("internal", sink.id());
        assertTrue(sink.selfAcknowledges());
        assertTrue(sink.consumerPresent());
        assertEquals(0, reads.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Skript", "INTERNAL", " skript", "skript ", "vault", "none"})
    void anyOtherValueSelectsNoSink(String value) {
        assertNull(AccountingSink.forId(value, () -> true));
    }

    @Test
    void aMissingValueIsRefused() {
        assertThrows(NullPointerException.class, () -> AccountingSink.forId(null, () -> true));
    }

    @Test
    void theSkriptSinksConsumerIsTheBridgeReadLiveOnEveryCall() {
        AtomicBoolean connected = new AtomicBoolean(false);
        SkriptSink sink = new SkriptSink(connected::get);
        assertFalse(sink.consumerPresent());
        connected.set(true);
        assertTrue(sink.consumerPresent());
        connected.set(false);
        assertFalse(sink.consumerPresent());
    }

    @Test
    void theSkriptSinkNeedsABridgeCheck() {
        assertThrows(NullPointerException.class, () -> new SkriptSink(null));
    }

    @Test
    void eachSinkHasItsOwnReadyAndWaitingHealthLines() {
        SkriptSink skript = new SkriptSink(() -> true);
        assertEquals("Accounting projection: acknowledged", skript.healthLine(true));
        assertEquals("Accounting projection: waiting for verified snapshot and Skript acknowledgement", skript.healthLine(false));
        InternalSink internal = new InternalSink();
        assertEquals("Accounting projection: acknowledged by the plugin (accounting.sink=internal; no Skript totals are published)",
                internal.healthLine(true));
        assertEquals("Accounting projection: waiting for verified snapshot (accounting.sink=internal)", internal.healthLine(false));
    }
}
