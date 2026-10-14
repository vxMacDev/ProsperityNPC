package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.property.OwnerAway;
import com.prosperitysmp.npc.property.Property;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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

import static com.prosperitysmp.npc.economy.TransferJournal.*;
import static com.prosperitysmp.npc.economy.TransferProof.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * economy.away-debit-recheck-enabled. The reachable case: /npcadmin reload turns housing.vacation-days from 0 to
 * above 0 while a utility charge, auto-care charge or rent batch is between async hops, so the home is now held by
 * owner-away but was admitted before. Drives the runtime's own gate helpers and a real OwnerAway (durable mode, as
 * the plugin runs it) through a real JournaledTransfer with a fake journal, main and worker queued separately.
 */
class OwnerAwayDebitRecheckTest {
    private static final UUID RESERVE = new UUID(0, 3), OWNER = new UUID(0, 4), RUN = new UUID(0, 2);
    private static final UUID WORLD = new UUID(0, 7);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    private static final long DAY = 86_400_000L, NOW = 1_800_000_000_000L;

    private static Property home(long id) {
        return new Property(new UUID(1, id), WORLD, "r" + id, "m", "room", 2, 1000, 1000, OWNER, 0, 80, true);
    }

    /** One rig per test: queues, the live config values, a durable OwnerAway, a journal and the held leases. */
    private static final class Rig implements JournaledTransfer.Journal {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        String thread = "main";
        final Executor main = r -> queue.add(() -> { thread = "main"; r.run(); });
        final Executor worker = r -> queue.add(() -> { thread = "worker"; r.run(); });
        Object flag = Boolean.TRUE;                        // economy.away-debit-recheck-enabled
        final AtomicInteger vacationDays = new AtomicInteger(0); // housing.vacation-days, read live
        final AtomicInteger sightings = new AtomicInteger();
        // The owner was last seen 30 days ago: away under any vacation-days setting once it is decided.
        final OwnerAway away = new OwnerAway(vacationDays::get, () -> TODAY, () -> NOW, owner -> {
            sightings.incrementAndGet();
            return new OwnerAway.Seen(false, NOW - 30 * DAY, 0);
        });
        final List<String> calls = new ArrayList<>();
        final Map<Long, State> journal = new LinkedHashMap<>();
        final Set<Long> leases = new HashSet<>();
        final List<Long> heldRows = new ArrayList<>();
        Runnable onDebitIntent = () -> { }, onDebitAck = () -> { }, onPaid = () -> { };

        Rig(long... rows) { away.requireDurable(); for (long row : rows) leases.add(row); }

        void reloadOnMain(int days) { main.execute(() -> vacationDays.set(days)); }
        void drain() { while (!queue.isEmpty()) queue.remove().run(); }

        /** requestCore's supplier: the admission predicate, behind the flag. */
        BooleanSupplier utilityHeld(Property p, boolean recurring, long incidentId, boolean purchase) {
            return () -> {
                assertEquals("main", thread, "owner-away is read on main only");
                return UtilityPaymentRuntime.awayHeldNow(flag,
                        () -> UtilityPaymentRuntime.awayBlocks(away.holds(p), recurring, incidentId, purchase));
            };
        }

        /** payoutAwayHeld: rent and appreciation. */
        BooleanSupplier payoutHeld(Property p) {
            return () -> {
                assertEquals("main", thread, "owner-away is read on main only");
                return UtilityPaymentRuntime.awayHeldNow(flag, () -> away.holds(p));
            };
        }

        private static Handle handle(long id) { return new Handle(new UUID(1, id), id, RUN, "a".repeat(64)); }
        private static Receipt receipt(Handle h, State state) {
            return new Receipt(h, "b".repeat(64), OWNER, RESERVE, 100, state, null,
                    state == State.CREDIT_INTENT || state == State.PAID);
        }

        public Start beginDebit(Handle h) {
            assertEquals("worker", thread); calls.add("intent-debit-" + h.eventId());
            journal.put(h.eventId(), State.DEBIT_INTENT); onDebitIntent.run();
            return new Start(true, receipt(h, State.DEBIT_INTENT));
        }
        public Receipt recordDebit(Handle h, Observation o) {
            assertEquals("worker", thread); calls.add("ack-debit-" + h.eventId());
            journal.put(h.eventId(), State.CREDIT_READY); onDebitAck.run();
            return receipt(h, State.CREDIT_READY);
        }
        public Start beginCredit(Handle h) {
            assertEquals("worker", thread); calls.add("intent-credit-" + h.eventId());
            journal.put(h.eventId(), State.CREDIT_INTENT);
            return new Start(true, receipt(h, State.CREDIT_INTENT));
        }
        public Receipt recordCredit(Handle h, Observation o) {
            assertEquals("worker", thread); calls.add("ack-credit-" + h.eventId());
            journal.put(h.eventId(), State.PAID);
            return receipt(h, State.PAID);
        }

        /** The transfer tail of requestCore and payRent: the lease's authorize carries the away DEBIT gate. */
        CompletableFuture<Boolean> pay(long id, BooleanSupplier awayHeld) {
            assertEquals("main", thread);
            var transfer = new JournaledTransfer(this, (receipt, leg) -> {
                assertEquals("main", thread); calls.add("money-" + leg + "-" + id);
                return new Observation(true, true, 1.0, 10.0, leg == Leg.DEBIT ? 9.0 : 11.0, false);
            }, worker, main);
            return transfer.submit(handle(id), new JournaledTransfer.Lease() {
                public void authorize(Receipt r, Leg leg) {
                    assertEquals("main", thread); calls.add("authorize-" + leg + "-" + id);
                    UtilityPaymentRuntime.requireAwayDebitGate(leg, awayHeld);
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

        /** payRent's pre-dispatch step: held means no journal row, the lease reopens, the reservation stays PENDING. */
        CompletableFuture<Boolean> payRent(long id, BooleanSupplier awayHeld) {
            assertEquals("main", thread);
            if (awayHeld.getAsBoolean()) { leases.remove(id); heldRows.add(id); return CompletableFuture.completedFuture(false); }
            return pay(id, awayHeld);
        }

        /** requestRentBatch's serial rent loop. */
        CompletableFuture<Integer> rentBatch(List<Long> rows, java.util.function.LongFunction<BooleanSupplier> held) {
            CompletableFuture<Integer> sequence = CompletableFuture.completedFuture(0);
            for (long row : rows)
                sequence = sequence.thenComposeAsync(paid -> payRent(row, held.apply(row))
                        .thenApply(s -> paid + (s ? 1 : 0)), main);
            return sequence;
        }
    }

    // (a) The gate helpers.

    @Test void creditNeverReadsOwnerAwayAndDebitRefusesWhenHeld() {
        assertTrue(UtilityPaymentRuntime.awayDebitAllows(Leg.CREDIT, () -> { fail("CREDIT must not read owner-away"); return true; }));
        assertDoesNotThrow(() -> UtilityPaymentRuntime.requireAwayDebitGate(Leg.CREDIT, () -> true));
        assertFalse(UtilityPaymentRuntime.awayDebitAllows(Leg.DEBIT, () -> true));
        assertTrue(UtilityPaymentRuntime.awayDebitAllows(Leg.DEBIT, () -> false));
        var refused = assertThrows(IllegalStateException.class, () -> UtilityPaymentRuntime.requireAwayDebitGate(Leg.DEBIT, () -> true));
        assertEquals("Owner away before debit", refused.getMessage());
    }

    @Test void flagOffNeverAsksOwnerAway() {
        for (Object off : new Object[]{null, Boolean.FALSE, "true", 1})
            assertFalse(UtilityPaymentRuntime.awayHeldNow(off, () -> { fail("off must not read or decide owner-away"); return true; }), String.valueOf(off));
        assertTrue(UtilityPaymentRuntime.awayHeldNow(Boolean.TRUE, () -> true));
        assertFalse(UtilityPaymentRuntime.awayHeldNow(Boolean.TRUE, () -> false));
        assertFalse(UtilityPaymentRuntime.awayDebitRecheckEnabled("true"));
    }

    // (b) The reachable case: vacation-days 0 -> 7 makes an admitted home held at once (unsettled day).

    @Test void reloadTurningVacationDaysOnMakesAnAdmittedHomeHeldImmediately() {
        Rig rig = new Rig();
        Property p = home(1);
        assertFalse(rig.away.holds(p), "0 days: never held, so admission passes");
        assertEquals(0, rig.sightings.get(), "off reads nothing");
        rig.vacationDays.set(7);
        assertTrue(rig.away.holds(p), "unsettled day: held until today's decisions are saved");
        // Positive control for the skeptic's narrowing: a saved not-away decision is fixed for the day.
        Rig settled = new Rig();
        settled.vacationDays.set(7);
        assertTrue(settled.away.confirm(TODAY, List.of(new OwnerAway.Choice(p.propertyId(), OWNER, false))));
        assertFalse(settled.away.holds(p));
        settled.vacationDays.set(14);
        assertFalse(settled.away.holds(p), "changing one non-zero value to another does not re-decide");
    }

    // (c) Utility / auto-care: reload lands inside the beginDebit worker hop.

    @Test void utilityDebitIsRefusedWhenOwnerBecameAwayDuringTheHop() {
        Rig rig = new Rig(1);
        Property p = home(1);
        BooleanSupplier held = rig.utilityHeld(p, false, 0, false); // manual utility bill
        assertFalse(held.getAsBoolean(), "admitted at vacation-days 0");
        rig.onDebitIntent = () -> rig.reloadOnMain(7);
        var result = rig.pay(1, held);
        rig.drain();
        assertTrue(result.isCompletedExceptionally(), "fails closed into recovery");
        assertEquals(Map.of(1L, State.DEBIT_INTENT), rig.journal, "the intent is kept for review, never erased");
        assertTrue(rig.calls.contains("authorize-DEBIT-1"));
        assertTrue(rig.calls.stream().noneMatch(c -> c.startsWith("money-")), rig.calls.toString());
        assertEquals(Set.of(1L), rig.leases, "recovery retains the lease");
    }

    @Test void recurringAutoCareRepairIsRefusedToo() {
        Rig rig = new Rig(1);
        var result = rig.pay(1, rig.utilityHeld(home(1), true, 42, false));
        rig.vacationDays.set(7); // reload before the queued beginDebit runs
        rig.drain();
        assertTrue(result.isCompletedExceptionally());
        assertTrue(rig.calls.stream().noneMatch(c -> c.startsWith("money-")), rig.calls.toString());
    }

    // (e2) Negative control: with the flag off the same reload still charges the held home (the defect).

    @Test void flagOffKeepsTheOldAdmissionOnlyBehavior() {
        Rig rig = new Rig(1);
        rig.flag = Boolean.FALSE;
        rig.onDebitIntent = () -> rig.reloadOnMain(7);
        var result = rig.pay(1, rig.utilityHeld(home(1), false, 0, false));
        rig.drain();
        assertTrue(result.join());
        assertTrue(rig.away.holds(home(1)), "the home really was held before the debit");
        assertEquals(State.PAID, rig.journal.get(1L), "old behavior: charged anyway");
        assertTrue(rig.calls.contains("money-DEBIT-1"));
    }

    // (d) An acknowledged DEBIT always completes its CREDIT.

    @Test void acknowledgedDebitStillReceivesItsCredit() {
        Rig rig = new Rig(1);
        rig.onDebitAck = () -> rig.reloadOnMain(7);
        var result = rig.pay(1, rig.utilityHeld(home(1), false, 0, false));
        rig.drain();
        assertTrue(result.join());
        assertEquals(State.PAID, rig.journal.get(1L));
        assertTrue(rig.calls.containsAll(List.of("authorize-CREDIT-1", "money-CREDIT-1", "ack-credit-1", "release-1")));
        assertTrue(rig.leases.isEmpty());
    }

    // (f) Owner-chosen repairs and purchases keep the admission exemption.

    @Test void manualRepairAndPurchaseAreNotHeldAfterTheFlip() {
        for (var held : List.<java.util.function.Function<Rig, BooleanSupplier>>of(
                r -> r.utilityHeld(home(1), false, 42, false),   // manual repair
                r -> r.utilityHeld(home(1), false, 0, true))) {  // upgrade, maintain, insurance or eviction
            Rig rig = new Rig(1);
            rig.onDebitIntent = () -> rig.reloadOnMain(7);
            var result = rig.pay(1, held.apply(rig));
            rig.drain();
            assertTrue(result.join());
            assertEquals(State.PAID, rig.journal.get(1L));
        }
    }

    // Provider pre-withdraw check: a reentrant balance hook flips vacation-days after authorize passed.

    private static final UUID ACCOUNT_ID = new UUID(0, 9);
    private static final OfflinePlayer ACCOUNT = (OfflinePlayer) Proxy.newProxyInstance(
            OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class}, (p, m, a) -> {
                if (m.getName().equals("getUniqueId")) return ACCOUNT_ID;
                throw new AssertionError("unexpected player call: " + m.getName());
            });

    private static int[] observe(Leg leg, Rig rig, BooleanSupplier held, Observation[] out) {
        int[] moves = {0};
        Economy economy = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[]{Economy.class}, (p, m, a) -> {
            if (m.getName().equals("isEnabled")) return true;
            if (m.getName().equals("getBalance")) { rig.vacationDays.set(7); return 100.0; } // reentrant reload
            if (m.getName().equals("withdrawPlayer") || m.getName().equals("depositPlayer")) {
                moves[0]++;
                return new EconomyResponse((double) a[1], 100.0, EconomyResponse.ResponseType.SUCCESS, null);
            }
            throw new AssertionError("unexpected economy call: " + m.getName());
        });
        int[] providerChecks = {0};
        var observer = new VaultLegObservation(economy, () -> {
            // The runtime's supplier: second check, after the pre-balance read and immediately before the withdraw.
            if (leg == Leg.DEBIT && ++providerChecks[0] == 2) UtilityPaymentRuntime.requireAwayDebitGate(leg, held);
            return economy;
        }, () -> true);
        out[0] = observer.execute(ACCOUNT_ID, ACCOUNT, 500, leg);
        return moves;
    }

    @Test void providerCheckRefusesTheWithdrawAfterAReentrantFlip() {
        Rig rig = new Rig();
        BooleanSupplier held = rig.payoutHeld(home(1));
        assertFalse(held.getAsBoolean());
        Observation[] out = new Observation[1];
        assertEquals(0, observe(Leg.DEBIT, rig, held, out)[0], "no withdraw once the home is held");
        assertTrue(out[0].threw());
        assertFalse(out[0].responsePresent(), "no provider response: nothing moved");
        // Credit is never refused by owner-away.
        Rig credit = new Rig();
        assertEquals(1, observe(Leg.CREDIT, credit, credit.payoutHeld(home(1)), out)[0]);
        assertFalse(out[0].threw());
        // Flag off: the withdraw happens as before.
        Rig off = new Rig();
        off.flag = Boolean.FALSE;
        assertEquals(1, observe(Leg.DEBIT, off, off.payoutHeld(home(1)), out)[0]);
    }

    // (e) Rent: reload lands after row 1 is paid; row 2 is held before dispatch.

    @Test void rentRowHeldAfterTheFlipHasNoJournalRowAndReopensItsLease() {
        Rig rig = new Rig(1, 2);
        rig.onPaid = () -> rig.reloadOnMain(7);
        var result = rig.rentBatch(List.of(1L, 2L), id -> rig.payoutHeld(home(id)));
        rig.drain();
        assertEquals(1, result.join(), "row 1 was paid before the reload");
        assertEquals(Map.of(1L, State.PAID), rig.journal, "row 2 has no journal row: its reservation stays PENDING");
        assertTrue(rig.calls.stream().noneMatch(c -> c.endsWith("-2")), rig.calls.toString());
        assertEquals(List.of(2L), rig.heldRows);
        assertTrue(rig.leases.isEmpty(), "the held row's lease is released, nothing was dispatched");
    }

    @Test void rentReloadInsideTheIntentHopFailsClosedWithoutMovingMoney() {
        Rig rig = new Rig(1, 2);
        rig.onDebitIntent = () -> rig.reloadOnMain(7);
        var result = rig.rentBatch(List.of(1L, 2L), id -> rig.payoutHeld(home(id)));
        rig.drain();
        assertTrue(result.isCompletedExceptionally());
        assertEquals(Map.of(1L, State.DEBIT_INTENT), rig.journal);
        assertTrue(rig.calls.stream().noneMatch(c -> c.startsWith("money-")), rig.calls.toString());
    }

    @Test void rentFlagOffPaysEveryRowAsBefore() {
        Rig rig = new Rig(1, 2);
        rig.flag = Boolean.FALSE;
        rig.onPaid = () -> rig.reloadOnMain(7);
        var result = rig.rentBatch(List.of(1L, 2L), id -> rig.payoutHeld(home(id)));
        rig.drain();
        assertEquals(2, result.join());
        assertEquals(0, rig.sightings.get(), "off never decides owner-away");
        assertTrue(rig.heldRows.isEmpty());
    }

    // Shipped default and wiring: the runtime calls these gates where the tests above assume it does.

    @Test void shippedOn() throws Exception {
        assertEquals("economy.away-debit-recheck-enabled", UtilityPaymentRuntime.AWAY_DEBIT_RECHECK);
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
            assertEquals(Boolean.TRUE, config.get(UtilityPaymentRuntime.AWAY_DEBIT_RECHECK));
        }
    }

    @Test void runtimeWiresTheRecheckBeforeEveryDebitAndNeverBeforeCredit() throws Exception {
        String code = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/economy/UtilityPaymentRuntime.java"));
        assertEquals(6, count(code, "requireAwayDebitGate("), "definition, three utility call sites, two in finalDebitChecks");

        // Utility / repair / purchase / auto-care: the admission predicate, reread before submit, in authorize and at the withdraw.
        String core = section(code, "com.prosperitysmp.npc.property.AutoCareRepository.Claim recurring, Consumer<Boolean> finished) {",
                "public boolean requestRentBatch(");
        int admission = core.indexOf("if (awayBlocks(plugin.ownerAway().holds(property), recurring != null, incidentId, target != null)) return false;");
        int supplier = core.indexOf("BooleanSupplier awayHeld = () -> awayHeldNow(plugin.getConfig().get(AWAY_DEBIT_RECHECK),\n"
                + "                () -> awayBlocks(plugin.ownerAway().holds(property), recurring != null, incidentId, target != null));");
        int terminal = core.indexOf("if (prepared.terminal() != null) return CompletableFuture.completedFuture(prepared.terminal());");
        int beforeSubmit = core.indexOf("requireAwayDebitGate(TransferProof.Leg.DEBIT, awayHeld);");
        int provider = core.indexOf("if (leg == TransferProof.Leg.DEBIT && ++providerChecks[0] == 2) requireAwayDebitGate(leg, awayHeld);");
        int binding = core.indexOf("throw new IllegalStateException(\"Utility transfer binding failed\");");
        int authorize = core.indexOf("requireAwayDebitGate(leg, awayHeld);", binding);
        int submit = core.indexOf("return transfer.submit(handle,");
        assertTrue(admission > 0 && supplier > admission && terminal > supplier && beforeSubmit > terminal
                && provider > beforeSubmit && submit > provider && binding > submit && authorize > binding,
                admission + " " + supplier + " " + terminal + " " + beforeSubmit + " " + provider + " " + submit + " " + binding + " " + authorize);

        // Rent and appreciation payouts: clean pre-dispatch hold before the floor check, then both DEBIT gates.
        String rent = section(code, "Runnable floorDeferred, BooleanSupplier debitGate) {", "private CompletableFuture<Integer> payAppreciation(");
        int read = rent.indexOf("BooleanSupplier awayHeld = () -> payoutAwayHeld(property);");
        int hold = rent.indexOf("if (awayHeld.getAsBoolean()) {");
        int floor = rent.indexOf("if(!floorAllows(");
        assertTrue(read > 0 && hold > read && floor > hold);
        String held = rent.substring(hold, floor);
        assertTrue(held.contains("lease.releaseReconciled(); rentLeases.remove(property.propertyId());"));
        assertTrue(held.contains("return CompletableFuture.completedFuture(false);"));
        assertFalse(held.contains("floorDeferred.run()"), "an away hold is not a floor deferral");
        // Observer and lease authorize both go through finalDebitChecks, which rereads owner-away after the floor's
        // getBalance and validate (external callbacks that may re-enter), as the last read before the withdraw.
        int checks = rent.indexOf("if (leg == TransferProof.Leg.DEBIT && ++providerChecks[0] == 2) finalDebitChecks(leg, () -> {");
        int providerFloor = rent.indexOf("requireFloor(", checks);
        int providerGates = rent.indexOf("}, debitGate, awayHeld);", checks);
        assertTrue(checks > 0 && providerFloor > checks && providerGates > rent.indexOf("validate(", providerFloor));
        int rentAuthorize = rent.indexOf("public void authorize(TransferJournal.Receipt receipt, TransferProof.Leg leg) {");
        int authorizeChecks = rent.indexOf("finalDebitChecks(leg, () -> {", rentAuthorize);
        assertTrue(rentAuthorize > 0 && authorizeChecks > rent.indexOf("throw new IllegalStateException(\"Rent transfer binding failed\");", rentAuthorize)
                && rent.indexOf("}, debitGate, awayHeld);", authorizeChecks) > rent.indexOf("requireFloor(", authorizeChecks));
        String helper = code.substring(code.indexOf("static void finalDebitChecks("), code.indexOf("private boolean payoutAwayHeld("));
        assertTrue(helper.lastIndexOf("requireAwayDebitGate(leg, awayHeld);") > helper.indexOf("debitOnlyChecks.run();"));

        // Appreciation: a home held since admission is not even reserved.
        assertTrue(code.contains("if (property.equals(now) && now.enabled() && !payoutAwayHeld(property)) unchanged.add(property);"));
        assertTrue(code.contains("return awayHeldNow(plugin.getConfig().get(AWAY_DEBIT_RECHECK), () -> plugin.ownerAway().holds(property));"));
    }

    private static String section(String code, String start, String end) {
        int from = code.indexOf(start), to = code.indexOf(end, from);
        assertTrue(from > 0 && to > from, start);
        return code.substring(from, to);
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
