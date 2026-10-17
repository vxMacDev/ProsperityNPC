package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static com.prosperitysmp.npc.economy.TransferJournal.*;
import static com.prosperitysmp.npc.economy.TransferProof.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * economy.appreciation-enabled turned off by /npcadmin reload while an appreciation phase is in flight.
 * Drives the runtime's own gatedSequence and requireDebitGate through a real JournaledTransfer with a fake
 * journal, main and worker queued separately so a reload can land between any two async hops.
 */
class AppreciationLiveSwitchTest {
    private static final UUID RESERVE = new UUID(0, 3), OWNER = new UUID(0, 4), RUN = new UUID(0, 2);

    /** One rig per test: a main queue, a worker queue, the live flag, a journal and the held leases. */
    private static final class Rig implements JournaledTransfer.Journal {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        String thread = "main";
        final Executor main = r -> queue.add(() -> { thread = "main"; r.run(); });
        final Executor worker = r -> queue.add(() -> { thread = "worker"; r.run(); });
        boolean flag = true; // economy.appreciation-enabled, as plugin.getConfig() returns it on main.
        int flagReads;
        final List<String> calls = new ArrayList<>();
        final Map<Long, State> journal = new LinkedHashMap<>(); // No key: no journal row exists for that event.
        final Set<Long> leases = new HashSet<>();
        final List<Long> skipped = new ArrayList<>();
        Runnable onDebitIntent = () -> { }, onDebitAck = () -> { }, onPaid = () -> { };
        final BooleanSupplier live = () -> {
            assertEquals("main", thread, "the flag is reread on main only");
            flagReads++;
            return UtilityPaymentRuntime.appreciationEnabled(flag);
        };

        Rig(long... rows) { for (long row : rows) leases.add(row); }

        void reloadOnMain(boolean value) { main.execute(() -> flag = value); }
        void drain() { while (!queue.isEmpty()) queue.remove().run(); }

        private static Handle handle(long id) { return new Handle(new UUID(1, id), id, RUN, "a".repeat(64)); }
        private static Receipt receipt(Handle h, State state) {
            return new Receipt(h, "b".repeat(64), RESERVE, OWNER, 100, state, null,
                    state == State.CREDIT_INTENT || state == State.PAID);
        }

        public Start beginDebit(Handle h) {
            assertEquals("worker", thread); calls.add("intent-debit-" + h.eventId());
            journal.put(h.eventId(), State.DEBIT_INTENT); onDebitIntent.run();
            return new Start(true, receipt(h, State.DEBIT_INTENT));
        }
        public Receipt recordDebit(Handle h, Observation o) {
            assertEquals("worker", thread); calls.add("ack-debit-" + h.eventId());
            // As the real journal: a debit refused before its money call is a decline.
            State next = o.refusedBeforeCall() != null ? State.DECLINED : State.CREDIT_READY;
            journal.put(h.eventId(), next); onDebitAck.run();
            return receipt(h, next);
        }
        public Start beginCredit(Handle h) {
            assertEquals("worker", thread); calls.add("intent-credit-" + h.eventId());
            journal.put(h.eventId(), State.CREDIT_INTENT);
            return new Start(true, receipt(h, State.CREDIT_INTENT));
        }
        public Receipt recordCredit(Handle h, Observation o) {
            assertEquals("worker", thread); calls.add("ack-credit-" + h.eventId());
            State next = o.refusedBeforeCall() != null ? State.AMBIGUOUS : State.PAID;
            journal.put(h.eventId(), next);
            return receipt(h, next);
        }

        /** The tail of payRent: one dispatcher per row, the lease's DEBIT gate, the same terminal-state rule. */
        CompletableFuture<Boolean> pay(long id, BooleanSupplier debitGate) {
            assertEquals("main", thread);
            var transfer = new JournaledTransfer(this, (receipt, leg) -> {
                assertEquals("main", thread); calls.add("money-" + leg + "-" + id);
                return new Observation(true, true, 1.0, 10.0, leg == Leg.DEBIT ? 9.0 : 11.0, false);
            }, worker, main);
            return transfer.submit(handle(id), new JournaledTransfer.Lease() {
                public void authorize(Receipt r, Leg leg) {
                    assertEquals("main", thread); calls.add("authorize-" + leg + "-" + id);
                    UtilityPaymentRuntime.requireDebitGate(leg, debitGate);
                }
                public void releaseVerified(Receipt r) {
                    leases.remove(id); calls.add("release-" + id);
                    if (r.state() == State.PAID) onPaid.run();
                }
            }).thenApply(r -> {
                if (r.state() != State.PAID && r.state() != State.DECLINED) throw new IllegalStateException("recovery");
                return r.state() == State.PAID;
            }).toCompletableFuture();
        }

        /** The wiring under test: the sequence and every DEBIT leg both reread the live flag. */
        CompletableFuture<Integer> appreciation(List<Long> rows) {
            return UtilityPaymentRuntime.gatedSequence(rows, live, id -> pay(id, live), this::skip, main);
        }

        void skip(long id) {
            assertEquals("main", thread);
            assertFalse(journal.containsKey(id), "a skipped row was never dispatched");
            leases.remove(id); skipped.add(id);
        }

        /** A loop with no recheck between rows, used to show the recheck is needed. */
        CompletableFuture<Integer> ungated(List<Long> rows, Function<Long, CompletableFuture<Boolean>> pay) {
            CompletableFuture<Integer> sequence = CompletableFuture.completedFuture(0);
            for (long row : rows)
                sequence = sequence.thenComposeAsync(paid -> pay.apply(row).thenApply(s -> paid + (s ? 1 : 0)), main);
            return sequence;
        }
    }

    // (a) Reload lands while the reservation SQL is on the worker.

    @Test void switchedOffDuringReservationDispatchesNothingAndReportsHeld() {
        Rig rig = new Rig(1, 2, 3);
        var counted = new AtomicInteger(99);
        var batch = UtilityPaymentRuntime.thenAppreciation(CompletableFuture.completedFuture(4), true, () -> false,
                () -> CompletableFuture.supplyAsync(() -> {
                    rig.reloadOnMain(false); // operator runs /npcadmin reload while SQL reserves rows
                    return List.of(1L, 2L, 3L);
                }, rig.worker).thenComposeAsync(rig::appreciation, rig.main),
                counted::set, rig.main);
        rig.drain();
        assertEquals(4, batch.join(), "rent payouts still reported");
        assertEquals(-1, counted.get(), "the phase reports held");
        assertTrue(rig.calls.isEmpty(), "no journal intent, no authorize, no money: " + rig.calls);
        assertTrue(rig.journal.isEmpty());
        assertEquals(List.of(1L, 2L, 3L), rig.skipped, "every reserved row stays PENDING for retirement");
        assertTrue(rig.leases.isEmpty(), "undispatched leases are released");
        assertTrue(UtilityPaymentRuntime.rentBatchReply(4, 0, true, counted.get())
                .endsWith(" Yesterday's appreciation held this batch; none paid."));
    }

    @Test void switchedOffBetweenReservationAndFirstDispatchStillReportsHeld() {
        Rig rig = new Rig(1, 2);
        var result = rig.appreciation(List.of(1L, 2L)); // upfront check passes on main
        rig.flag = false;                                 // reload runs before the first queued dispatch
        rig.drain();
        assertEquals(-1, result.join());
        assertTrue(rig.journal.isEmpty());
        assertEquals(List.of(1L, 2L), rig.skipped);
        assertTrue(rig.leases.isEmpty());
    }

    // (b) Reload lands after row 1 is PAID.

    @Test void switchedOffAfterFirstPayoutLeavesTheSecondPendingWithNoJournalRow() {
        Rig rig = new Rig(1, 2);
        rig.onPaid = () -> rig.reloadOnMain(false);
        var result = rig.appreciation(List.of(1L, 2L));
        rig.drain();
        assertEquals(1, result.join(), "one verified payout, not -1: money did move");
        assertEquals(Map.of(1L, State.PAID), rig.journal, "row 2 has no journal row at all");
        assertTrue(rig.calls.stream().noneMatch(c -> c.endsWith("-2")), rig.calls.toString());
        assertEquals(List.of(2L), rig.skipped);
        assertTrue(rig.leases.isEmpty());
    }

    @Test void onceStoppedItStaysStoppedEvenIfTheSwitchComesBack() {
        Rig rig = new Rig(1, 2, 3);
        rig.onPaid = () -> rig.reloadOnMain(false);
        var result = UtilityPaymentRuntime.gatedSequence(List.of(1L, 2L, 3L), rig.live, id -> rig.pay(id, rig.live),
                id -> { rig.skip(id); rig.flag = true; }, rig.main); // operator turns it back on immediately
        rig.drain();
        assertEquals(1, result.join());
        assertEquals(Map.of(1L, State.PAID), rig.journal);
        assertEquals(List.of(2L, 3L), rig.skipped);
    }

    // (c) Reload lands between the DEBIT acknowledgement and the CREDIT leg.

    @Test void acknowledgedDebitStillReceivesItsCredit() {
        Rig rig = new Rig(1, 2);
        rig.onDebitAck = () -> rig.reloadOnMain(false);
        var result = rig.appreciation(List.of(1L, 2L));
        rig.drain();
        assertEquals(1, result.join());
        assertEquals(State.PAID, rig.journal.get(1L), "money left the reserve, so the owner is paid");
        assertTrue(rig.calls.containsAll(List.of("authorize-CREDIT-1", "money-CREDIT-1", "ack-credit-1", "release-1")));
        assertFalse(rig.journal.containsKey(2L), "but no new debit starts");
        assertEquals(List.of(2L), rig.skipped);
    }

    @Test void creditNeverConsultsTheSwitch() {
        assertTrue(UtilityPaymentRuntime.debitGateAllows(Leg.CREDIT, () -> { fail("CREDIT must not read the flag"); return false; }));
        assertDoesNotThrow(() -> UtilityPaymentRuntime.requireDebitGate(Leg.CREDIT, () -> false));
        assertFalse(UtilityPaymentRuntime.debitGateAllows(Leg.DEBIT, () -> false));
        assertThrows(IllegalStateException.class, () -> UtilityPaymentRuntime.requireDebitGate(Leg.DEBIT, () -> false));
    }

    // Defence in depth: reload lands inside the beginDebit worker hop, after the pre-dispatch check.

    @Test void switchedOffInsideTheIntentHopDeclinesWithoutMovingMoney() {
        Rig rig = new Rig(1, 2);
        rig.onDebitIntent = () -> rig.reloadOnMain(false);
        var result = rig.appreciation(List.of(1L, 2L));
        rig.drain();
        // The gate refuses in authorize, before the money call: the intent is declined, never erased, and the batch
        // is not held. Row 2 then sees the switch off and is never dispatched.
        assertEquals(0, result.join());
        assertEquals(Map.of(1L, State.DECLINED), rig.journal);
        assertTrue(rig.calls.contains("authorize-DEBIT-1"));
        assertTrue(rig.calls.stream().noneMatch(c -> c.startsWith("money-")), rig.calls.toString());
        assertTrue(rig.leases.isEmpty(), "a proved decline releases its lease");
        assertEquals(List.of(2L), rig.skipped);
    }

    // (d) Rent is not gated by the appreciation switch.

    @Test void rentGateNeverRefusesWhateverTheAppreciationSwitchSays() {
        Rig rig = new Rig(1, 2, 3);
        rig.flag = false;
        rig.onDebitIntent = () -> rig.reloadOnMain(!rig.flag);
        rig.onPaid = () -> rig.reloadOnMain(!rig.flag);
        var result = UtilityPaymentRuntime.gatedSequence(List.of(1L, 2L, 3L), UtilityPaymentRuntime.RENT_DEBIT_GATE,
                id -> rig.pay(id, UtilityPaymentRuntime.RENT_DEBIT_GATE), rig::skip, rig.main);
        rig.drain();
        assertEquals(3, result.join());
        assertEquals(Map.of(1L, State.PAID, 2L, State.PAID, 3L, State.PAID), rig.journal);
        assertTrue(rig.skipped.isEmpty());
        assertEquals(0, rig.flagReads, "rent never reads the appreciation flag");
        assertTrue(UtilityPaymentRuntime.debitGateAllows(Leg.DEBIT, UtilityPaymentRuntime.RENT_DEBIT_GATE));
    }

    @Test void switchLeftOnPaysEveryRowAndDeclinesStillCount() {
        Rig rig = new Rig(1, 2);
        var paid = UtilityPaymentRuntime.gatedSequence(List.of(1L, 2L), rig.live,
                id -> id == 1 ? CompletableFuture.completedFuture(false) : rig.pay(id, rig.live), rig::skip, rig.main);
        rig.drain();
        assertEquals(1, paid.join(), "row 1 declined or floor-deferred, row 2 paid");
        assertTrue(rig.skipped.isEmpty());
        assertEquals(0, UtilityPaymentRuntime.gatedSequence(List.<Long>of(), () -> false, id -> fail(), id -> fail(),
                Runnable::run).join(), "nothing pending is 0 payouts, not held");
    }

    // (e) Without the recheck the (b) cases fail, so they do catch a missing recheck.

    @Test void withoutAnyRecheckTheSecondRowIsPaidAfterTheSwitchWentOff() {
        Rig rig = new Rig(1, 2);
        rig.onPaid = () -> rig.reloadOnMain(false);
        var result = rig.ungated(List.of(1L, 2L), id -> rig.pay(id, UtilityPaymentRuntime.RENT_DEBIT_GATE));
        rig.drain();
        assertEquals(2, result.join());
        assertFalse(rig.flag, "the switch really was off before row 2");
        assertEquals(State.PAID, rig.journal.get(2L), "the reserve was paid out with appreciation off");
    }

    @Test void withoutTheSequenceRecheckTheDebitGateAloneLeavesAJournaledIntent() {
        Rig rig = new Rig(1, 2);
        rig.onPaid = () -> rig.reloadOnMain(false);
        var result = rig.ungated(List.of(1L, 2L), id -> rig.pay(id, rig.live));
        rig.drain();
        assertEquals(1, result.join());
        assertEquals(State.DECLINED, rig.journal.get(2L), "no money moves, but (b) requires no journal row at all");
        assertTrue(rig.calls.stream().noneMatch(c -> c.equals("money-DEBIT-2")));
    }

    // Wiring: the runtime passes these gates where the tests above assume it does.

    @Test void runtimeWiresTheLiveSwitchIntoAppreciationOnlyAndTheDebitLegOfEveryReservePayout() throws Exception {
        String code = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/economy/UtilityPaymentRuntime.java"));
        assertTrue(code.contains("static final BooleanSupplier RENT_DEBIT_GATE = () -> true;"));
        assertTrue(code.contains("BooleanSupplier stillOn = () -> appreciationEnabled(plugin.getConfig().get(APPRECIATION));"));
        assertEquals(2, count(code, "payRent(row, sha, limits, day, pinned, authorized,"), "exactly rent and appreciation");
        assertTrue(code.contains("() -> rentDeferred[0] = true, RENT_DEBIT_GATE)"), "rent: never refused");
        assertTrue(code.contains("row -> payRent(row, sha, limits, day, pinned, authorized, () -> { }, stillOn)"));
        assertTrue(code.contains("return gatedSequence(rows, stillOn,"));
        assertEquals(1, count(code, "gatedSequence(rows,"), "only appreciation uses gatedSequence; rent keeps its plain loop");
        // Lease authorize and Vault observer: both through finalDebitChecks, which reads the gate before the
        // DEBIT-only checks (fail fast) and again after them, as the last read before the withdraw.
        int authorize = code.indexOf("public void authorize(TransferJournal.Receipt receipt, TransferProof.Leg leg) {",
                code.indexOf("Runnable floorDeferred, BooleanSupplier debitGate)"));
        int authorizeChecks = code.indexOf("finalDebitChecks(leg, () -> {", authorize);
        assertTrue(authorize > 0 && authorizeChecks > authorize
                && code.indexOf("}, debitGate, awayHeld);", authorizeChecks) > code.indexOf("requireFloor(", authorizeChecks));
        int provider = code.indexOf("if (leg == TransferProof.Leg.DEBIT && ++providerChecks[0] == 2) finalDebitChecks(leg, () -> {");
        assertTrue(provider > 0 && code.indexOf("}, debitGate, awayHeld);", provider) > code.indexOf("requireFloor(", provider));
        String helper = code.substring(code.indexOf("static void finalDebitChecks("), code.indexOf("private boolean payoutAwayHeld("));
        int run = helper.indexOf("debitOnlyChecks.run();");
        assertTrue(helper.indexOf("requireDebitGate(leg, debitGate);") < run && helper.lastIndexOf("requireDebitGate(leg, debitGate);") > run);
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
