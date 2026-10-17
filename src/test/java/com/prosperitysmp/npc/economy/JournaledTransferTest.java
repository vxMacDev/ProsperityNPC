package com.prosperitysmp.npc.economy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.prosperitysmp.npc.economy.TransferJournal.*;
import static com.prosperitysmp.npc.economy.TransferProof.*;

class JournaledTransferTest {
    private static final Handle HANDLE = new Handle(new UUID(0, 1), 1, new UUID(0, 2), "a".repeat(64));
    private static Receipt receipt(State state) {
        return new Receipt(HANDLE, "b".repeat(64), new UUID(0, 3), new UUID(0, 4), 100,
                state, null, state == State.CREDIT_INTENT || state == State.PAID);
    }
    private static final class Harness implements JournaledTransfer.Journal, JournaledTransfer.Lease {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        final List<Observation> recorded = new ArrayList<>(), heard = new ArrayList<>();
        String thread;
        boolean replay, lostDebitAck, rejectCredit, moneyThrows, listenerThrows;
        Throwable rejectDebit;
        State debitState = State.CREDIT_READY;
        Executor executor(String name) { return r -> pending.add(() -> { thread = name; r.run(); }); }
        final JournaledTransfer driver = new JournaledTransfer(this, (r, leg) -> {
            assertEquals("main", thread); calls.add("money-" + leg);
            if (moneyThrows) throw new IllegalStateException("provider threw inside the money call");
            return new Observation(true, true, 1.0, 10.0, leg == Leg.DEBIT ? 9.0 : 11.0, false);
        }, executor("worker"), executor("main"), (leg, o) -> {
            assertEquals("main", thread); calls.add("heard-" + leg); heard.add(o);
            if (listenerThrows) throw new IllegalStateException("listener broke");
        });
        void drain() { while (!pending.isEmpty()) pending.remove().run(); }
        public Start beginDebit(Handle h) { assertEquals("worker", thread); calls.add("intent-debit"); return new Start(!replay, receipt(State.DEBIT_INTENT)); }
        public Receipt recordDebit(Handle h, Observation o) {
            assertEquals("worker", thread); calls.add("ack-debit"); recorded.add(o);
            if (lostDebitAck) throw new IllegalStateException("SQL acknowledgement lost");
            // As the real journal: a debit refused before its money call is a decline.
            return receipt(o.refusedBeforeCall() != null ? State.DECLINED : debitState);
        }
        public Start beginCredit(Handle h) { assertEquals("worker", thread); calls.add("intent-credit"); return new Start(true, receipt(State.CREDIT_INTENT)); }
        public Receipt recordCredit(Handle h, Observation o) {
            assertEquals("worker", thread); calls.add("ack-credit"); recorded.add(o);
            return receipt(o.refusedBeforeCall() != null ? State.AMBIGUOUS : State.PAID);
        }
        public void authorize(Receipt r, Leg leg) {
            assertEquals("main", thread); calls.add("authorize-" + leg);
            if (rejectDebit != null && leg == Leg.DEBIT) {
                if (rejectDebit instanceof RuntimeException runtime) throw runtime;
                throw (Error) rejectDebit;
            }
            if (rejectCredit && leg == Leg.CREDIT) throw new IllegalStateException("ownership invalidated");
        }
        public void releaseVerified(Receipt r) { assertEquals("main", thread); calls.add("release"); }
    }

    @Test void intentMoneyAcknowledgementOrderingAndExecutorPlacement() {
        Harness h = new Harness(); var result = h.driver.submit(HANDLE, h); h.drain();
        assertEquals(State.PAID, result.toCompletableFuture().join().state());
        assertEquals(List.of("intent-debit", "authorize-DEBIT", "money-DEBIT", "ack-debit",
                "intent-credit", "authorize-CREDIT", "money-CREDIT", "ack-credit", "release"), h.calls);
    }
    @Test void replayedIntentNeverDispatchesAndStaysHeld() {
        Harness h = new Harness(); h.replay = true;
        var result = h.driver.submit(HANDLE, h); h.drain();
        assertEquals(State.DEBIT_INTENT, result.toCompletableFuture().join().state());
        assertEquals(List.of("intent-debit"), h.calls);
        assertTrue(h.driver.submit(HANDLE, h).toCompletableFuture().isCompletedExceptionally());
    }
    @Test void lostDebitAcknowledgementCannotDispatchCreditOrRelease() {
        Harness h = new Harness(); h.lostDebitAck = true;
        var result = h.driver.submit(HANDLE, h); h.drain();
        assertTrue(result.toCompletableFuture().isCompletedExceptionally());
        assertEquals(List.of("intent-debit", "authorize-DEBIT", "money-DEBIT", "ack-debit"), h.calls);
        assertTrue(h.driver.submit(HANDLE, h).toCompletableFuture().isCompletedExceptionally());
    }
    @Test void authorityIsCheckedAgainAtCreditAndFailureRetainsLease() {
        Harness h = new Harness(); h.rejectCredit = true;
        var result = h.driver.submit(HANDLE, h); h.drain();
        // The refused credit is journaled as evidence; the transfer stays uncertain and holds for staff.
        assertEquals(State.AMBIGUOUS, result.toCompletableFuture().join().state());
        assertFalse(h.calls.contains("money-CREDIT")); assertFalse(h.calls.contains("release"));
        Observation credit = h.recorded.getLast();
        assertEquals(Refusal.LEASE_REFUSED, credit.refusedBeforeCall());
        assertEquals("java.lang.IllegalStateException", credit.refusalCause());
        assertEquals(List.of(credit), h.heard);
        assertTrue(h.driver.submit(HANDLE, h).toCompletableFuture().isCompletedExceptionally(), "still latched");
    }
    @Test void refusedDebitAuthorityIsADeclineThatReleasesAndReopens() {
        Harness h = new Harness(); h.rejectDebit = new IllegalStateException("owner away");
        var result = h.driver.submit(HANDLE, h); h.drain();
        assertEquals(State.DECLINED, result.toCompletableFuture().join().state());
        assertEquals(List.of("intent-debit", "authorize-DEBIT", "heard-DEBIT", "ack-debit", "release"), h.calls,
                "the money call never runs");
        Observation debit = h.recorded.getFirst();
        assertEquals(Refusal.LEASE_REFUSED, debit.refusedBeforeCall());
        assertEquals(LegOutcome.NO_MOVEMENT, TransferProof.evaluate(100, Leg.DEBIT, debit));
        assertEquals(1, h.heard.size());
        h.rejectDebit = null; h.calls.clear();
        var next = h.driver.submit(HANDLE, h); h.drain();
        assertEquals(State.PAID, next.toCompletableFuture().join().state(), "the next submit is accepted");
    }
    @Test void aLinkageErrorInDebitAuthorityIsAFaultRefusal() {
        Harness h = new Harness(); h.rejectDebit = new NoSuchMethodError("missing");
        var result = h.driver.submit(HANDLE, h); h.drain();
        assertEquals(State.DECLINED, result.toCompletableFuture().join().state());
        Observation debit = h.recorded.getFirst();
        assertEquals(Refusal.LEASE_FAILED, debit.refusedBeforeCall());
        assertTrue(debit.refusedBeforeCall().unexpected());
        assertEquals("java.lang.NoSuchMethodError", debit.refusalCause());
        assertFalse(h.calls.contains("money-DEBIT"));
    }
    @Test void aWrongBindingIsAFaultThatKeepsTheLatchNotADecline() {
        // On either leg a receipt bound to another payment fails the chain: nothing is recorded as a refusal,
        // the money call never runs and the dispatcher stays latched for staff.
        for (Leg leg : Leg.values()) {
            Harness h = new Harness();
            var driver = new JournaledTransfer(h, (r, l) -> {
                h.calls.add("money-" + l);
                return new Observation(true, true, 1.0, 10.0, l == Leg.DEBIT ? 9.0 : 11.0, false);
            }, h.executor("worker"), h.executor("main"), (l, o) -> h.heard.add(o));
            var lease = new JournaledTransfer.Lease() {
                public void authorize(Receipt r, Leg l) {
                    h.calls.add("authorize-" + l);
                    if (l == leg) throw new JournaledTransfer.BindingFailed("Utility transfer binding failed");
                }
                public void releaseVerified(Receipt r) { h.calls.add("release"); }
            };
            var result = driver.submit(HANDLE, lease); h.drain();
            assertTrue(result.toCompletableFuture().isCompletedExceptionally(), leg.name());
            assertFalse(h.calls.contains("money-" + leg), leg.name());
            assertFalse(h.calls.contains("release"), leg.name());
            assertTrue(h.heard.isEmpty(), "never heard as a refusal: " + leg);
            assertTrue(h.recorded.stream().noneMatch(o -> o.refusedBeforeCall() != null), "never recorded as a refusal: " + leg);
            assertTrue(driver.submit(HANDLE, lease).toCompletableFuture().isCompletedExceptionally(), "latched: " + leg);
        }
    }
    @Test void aFailureInsideTheMoneyCallIsNeverARefusal() {
        Harness h = new Harness(); h.moneyThrows = true;
        var result = h.driver.submit(HANDLE, h); h.drain();
        assertTrue(result.toCompletableFuture().isCompletedExceptionally());
        assertEquals(List.of("intent-debit", "authorize-DEBIT", "money-DEBIT"), h.calls, "no record, no release");
        assertTrue(h.recorded.isEmpty());
        assertTrue(h.driver.submit(HANDLE, h).toCompletableFuture().isCompletedExceptionally(), "latched");
    }
    @Test void aLostAcknowledgementAfterARefusalStillLatches() {
        Harness h = new Harness(); h.rejectDebit = new IllegalStateException("owner away"); h.lostDebitAck = true;
        var result = h.driver.submit(HANDLE, h); h.drain();
        assertTrue(result.toCompletableFuture().isCompletedExceptionally());
        assertFalse(h.calls.contains("release"));
        assertTrue(h.driver.submit(HANDLE, h).toCompletableFuture().isCompletedExceptionally(), "latched");
    }
    @Test void aBrokenListenerChangesNothing() {
        Harness h = new Harness(); h.rejectDebit = new IllegalStateException("owner away"); h.listenerThrows = true;
        var result = h.driver.submit(HANDLE, h); h.drain();
        assertEquals(State.DECLINED, result.toCompletableFuture().join().state());
        assertEquals(Refusal.LEASE_REFUSED, h.recorded.getFirst().refusedBeforeCall());
        assertEquals("release", h.calls.getLast());
    }
    @Test void aBrokenListenerAndABrokenLoggerStillRecordTheRefusal() {
        // The listener throws, and so does every attempt to log that it threw.
        Handler broken = new Handler() {
            @Override public void publish(LogRecord record) { throw new NoClassDefFoundError("logging"); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        List<Logger> loggers = List.of(Logger.getLogger(JournaledTransfer.class.getName()),
                Logger.getLogger(RefusalMonitor.class.getName()));
        loggers.forEach(logger -> logger.addHandler(broken));
        try {
            Harness h = new Harness(); h.rejectDebit = new IllegalStateException("owner away"); h.listenerThrows = true;
            var result = h.driver.submit(HANDLE, h); h.drain();
            assertEquals(State.DECLINED, result.toCompletableFuture().join().state());
            assertEquals(Refusal.LEASE_REFUSED, h.recorded.getFirst().refusedBeforeCall());
            assertEquals(List.of("intent-debit", "authorize-DEBIT", "heard-DEBIT", "ack-debit", "release"), h.calls);
            h.rejectDebit = null;
            assertEquals(State.PAID, submitAndDrain(h).state(), "the lease was released and the dispatcher reopened");
        } finally {
            loggers.forEach(logger -> logger.removeHandler(broken));
        }
    }
    private static Receipt submitAndDrain(Harness h) {
        var result = h.driver.submit(HANDLE, h); h.drain();
        return result.toCompletableFuture().join();
    }
    @Test void aRefusalReturnedByTheMoneyStepIsHeardToo() {
        Harness h = new Harness();
        var driver = new JournaledTransfer(h, (r, leg) -> Observation.refused(Refusal.INSUFFICIENT_FUNDS, null, 1.0, 1.0),
                h.executor("worker"), h.executor("main"), (leg, o) -> h.heard.add(o));
        var result = driver.submit(HANDLE, h); h.drain();
        assertEquals(State.DECLINED, result.toCompletableFuture().join().state());
        assertEquals(1, h.heard.size());
        assertEquals(Refusal.INSUFFICIENT_FUNDS, h.heard.getFirst().refusedBeforeCall());
    }
    @Test void concurrentRejectionAndCallerCancellationDoNotCancelTransfer() {
        Harness h = new Harness(); CompletionStage<Receipt> result = h.driver.submit(HANDLE, h);
        assertTrue(h.driver.submit(HANDLE, h).toCompletableFuture().isCompletedExceptionally());
        result.toCompletableFuture().cancel(true); h.drain();
        assertTrue(h.calls.contains("ack-credit")); assertEquals("release", h.calls.getLast());
        assertEquals(State.PAID, result.toCompletableFuture().join().state());
    }
    @Test void provedDeclineReleasesWithoutCredit() {
        Harness h = new Harness(); h.debitState = State.DECLINED;
        var result = h.driver.submit(HANDLE, h); h.drain();
        assertEquals(State.DECLINED, result.toCompletableFuture().join().state());
        assertFalse(h.calls.contains("intent-credit")); assertEquals("release", h.calls.getLast());
    }
}
