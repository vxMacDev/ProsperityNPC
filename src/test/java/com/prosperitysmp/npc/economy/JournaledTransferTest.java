package com.prosperitysmp.npc.economy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
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
        String thread;
        boolean replay, lostDebitAck, rejectCredit;
        State debitState = State.CREDIT_READY;
        Executor executor(String name) { return r -> pending.add(() -> { thread = name; r.run(); }); }
        final JournaledTransfer driver = new JournaledTransfer(this, (r, leg) -> {
            assertEquals("main", thread); calls.add("money-" + leg);
            return new Observation(true, true, 1.0, 10.0, leg == Leg.DEBIT ? 9.0 : 11.0, false);
        }, executor("worker"), executor("main"));
        void drain() { while (!pending.isEmpty()) pending.remove().run(); }
        public Start beginDebit(Handle h) { assertEquals("worker", thread); calls.add("intent-debit"); return new Start(!replay, receipt(State.DEBIT_INTENT)); }
        public Receipt recordDebit(Handle h, Observation o) {
            assertEquals("worker", thread); calls.add("ack-debit");
            if (lostDebitAck) throw new IllegalStateException("SQL acknowledgement lost");
            return receipt(debitState);
        }
        public Start beginCredit(Handle h) { assertEquals("worker", thread); calls.add("intent-credit"); return new Start(true, receipt(State.CREDIT_INTENT)); }
        public Receipt recordCredit(Handle h, Observation o) { assertEquals("worker", thread); calls.add("ack-credit"); return receipt(State.PAID); }
        public void authorize(Receipt r, Leg leg) {
            assertEquals("main", thread); calls.add("authorize-" + leg);
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
        assertTrue(result.toCompletableFuture().isCompletedExceptionally());
        assertFalse(h.calls.contains("money-CREDIT")); assertFalse(h.calls.contains("release"));
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
