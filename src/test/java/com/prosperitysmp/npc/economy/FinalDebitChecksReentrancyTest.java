package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.vault.VaultMoneySource;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Rent/appreciation DEBIT: the appreciation live switch and the owner-away recheck must be the last reads before
 * the withdraw. The floor check's getBalance is an external Vault callback that may re-enter (a synchronous reload or
 * a vacation change) and flip either flag; the flags are reread after it. CREDIT is never blocked.
 *
 * Drives a real LegObservation over the Vault source against a fake provider, with a supplier shaped like payRent's observer.
 */
class FinalDebitChecksReentrancyTest {
    private static final UUID ID = new UUID(0, 42);
    private static final OfflinePlayer ACCOUNT = (OfflinePlayer) Proxy.newProxyInstance(
            OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class}, (p, m, a) -> {
                if (m.getName().equals("getUniqueId")) return ID;
                throw new AssertionError("unexpected player call: " + m.getName());
            });
    /** The other side of the payout: the owner who receives it. Only its balance is read during the debit. */
    private static final OfflinePlayer OWNER = (OfflinePlayer) Proxy.newProxyInstance(
            OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class}, (p, m, a) -> {
                if (m.getName().equals("getUniqueId")) return new UUID(0, 43);
                throw new AssertionError("unexpected player call: " + m.getName());
            });
    private static final long CENTS = 2_500;

    /** Fake two-account Vault provider. getBalance call number flipOnBalanceCall runs the re-entrant hook. */
    private static final class Provider {
        double balance = 1_000, ownerBalance = 50;
        int balanceCalls, withdraws, deposits, flipOnBalanceCall = -1;
        Runnable reentrantHook = () -> { };
        final Economy economy = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(),
                new Class<?>[]{Economy.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "isEnabled": return true;
                        case "getBalance": {
                            assertTrue(a[0] == ACCOUNT || a[0] == OWNER);
                            if (++balanceCalls == flipOnBalanceCall) reentrantHook.run();
                            return a[0] == OWNER ? ownerBalance : balance;
                        }
                        case "withdrawPlayer": case "depositPlayer": {
                            assertSame(ACCOUNT, a[0]);
                            double amount = (double) a[1];
                            if (m.getName().equals("withdrawPlayer")) { withdraws++; balance -= amount; }
                            else { deposits++; balance += amount; }
                            return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null);
                        }
                        default: throw new AssertionError("unexpected economy call: " + m.getName());
                    }
                });
    }

    /** The live flags as the runtime reads them: appreciation on, owner present. */
    private static final class Flags {
        final AtomicBoolean appreciationOn = new AtomicBoolean(true), ownerAway = new AtomicBoolean(false);
        final AtomicInteger gateReads = new AtomicInteger(), awayReads = new AtomicInteger();
        final BooleanSupplier debitGate = () -> { gateReads.incrementAndGet(); return appreciationOn.get(); };
        final BooleanSupplier awayHeld = () -> { awayReads.incrementAndGet(); return ownerAway.get(); };
    }

    private interface Checks {
        void run(TransferProof.Leg leg, Runnable debitOnlyChecks, BooleanSupplier debitGate, BooleanSupplier awayHeld);
    }

    /** The real implementation: it rereads the flags after the external floor read. */
    private static final Checks FIXED = UtilityPaymentRuntime::finalDebitChecks;

    /** An order with no reread (gates, then the external floor read), used to show the tests can fail. */
    private static final Checks LEGACY = (leg, debitOnlyChecks, gate, away) -> {
        UtilityPaymentRuntime.requireDebitGate(leg, gate);
        UtilityPaymentRuntime.requireAwayDebitGate(leg, away);
        if (leg == TransferProof.Leg.DEBIT) debitOnlyChecks.run();
    };

    /**
     * payRent's Vault observer shape: on DEBIT, the second provider check (after the pre-balance read) runs the
     * final checks, whose floor read is a real provider getBalance call.
     */
    private static TransferProof.Observation execute(Provider provider, Flags flags, TransferProof.Leg leg, Checks checks,
            List<String> trace) {
        int[] providerChecks = {0};
        var pin = VaultMoneySource.pinFor(provider.economy);
        var observer = new LegObservation(pin, () -> {
            if (leg == TransferProof.Leg.DEBIT && ++providerChecks[0] == 2) checks.run(leg, () -> {
                trace.add("floor");
                provider.economy.getBalance(ACCOUNT);
            }, flags.debitGate, flags.awayHeld);
            return pin;
        }, () -> true);
        return observer.execute(ID, ACCOUNT, OWNER, CENTS, leg);
    }

    @Test void positiveControlWithoutAFlipTheDebitWithdrawsOnce() {
        var provider = new Provider(); var flags = new Flags(); var trace = new ArrayList<String>();
        var observation = execute(provider, flags, TransferProof.Leg.DEBIT, FIXED, trace);
        assertEquals(1, provider.withdraws);
        assertEquals(List.of("floor"), trace);
        assertEquals(TransferProof.LegOutcome.CONFIRMED, TransferProof.evaluate(CENTS, TransferProof.Leg.DEBIT, observation));
        assertEquals(2, flags.gateReads.get(), "read before and after the floor callback");
        assertEquals(2, flags.awayReads.get(), "read before and after the floor callback");
    }

    @Test void appreciationTurnedOffInsideTheFloorReadBlocksTheWithdraw() {
        var provider = new Provider(); var flags = new Flags();
        // Call 1 is the owner's headroom read, call 2 LegObservation's pre-balance, and call 3 is
        // requireFloor's read inside the final checks.
        provider.flipOnBalanceCall = 3;
        provider.reentrantHook = () -> flags.appreciationOn.set(false);
        var observation = execute(provider, flags, TransferProof.Leg.DEBIT, FIXED, new ArrayList<>());
        assertFalse(flags.appreciationOn.get(), "the hook really flipped the flag");
        assertEquals(0, provider.withdraws, "no withdraw after the live switch turned off");
        assertEquals(1_000, provider.balance);
        assertTrue(observation.threw());
        assertFalse(observation.responsePresent());
        assertEquals(TransferProof.Refusal.PRECHECK_REFUSED, observation.refusedBeforeCall());
        assertEquals("java.lang.IllegalStateException", observation.refusalCause());
        assertEquals(TransferProof.LegOutcome.NO_MOVEMENT, TransferProof.evaluate(CENTS, TransferProof.Leg.DEBIT, observation));
    }

    @Test void ownerTurnedAwayInsideTheFloorReadBlocksTheWithdraw() {
        var provider = new Provider(); var flags = new Flags();
        provider.flipOnBalanceCall = 3;
        provider.reentrantHook = () -> flags.ownerAway.set(true);
        var observation = execute(provider, flags, TransferProof.Leg.DEBIT, FIXED, new ArrayList<>());
        assertTrue(flags.ownerAway.get(), "the hook really flipped the flag");
        assertEquals(0, provider.withdraws, "no withdraw after the owner went away");
        assertEquals(1_000, provider.balance);
        assertTrue(observation.threw());
        assertFalse(observation.responsePresent());
        assertEquals(TransferProof.Refusal.PRECHECK_REFUSED, observation.refusedBeforeCall());
        assertEquals(TransferProof.LegOutcome.NO_MOVEMENT, TransferProof.evaluate(CENTS, TransferProof.Leg.DEBIT, observation));
    }

    @Test void flipsInTheHeadroomAndPreBalanceReadsAlsoBlockTheWithdraw() {
        // Calls 1 (the owner's headroom) and 2 (the payer's pre-balance) are external too, and both come before the
        // final checks, so the checks see the flip.
        for (int call : new int[]{1, 2}) {
            for (boolean appreciation : new boolean[]{true, false}) {
                var provider = new Provider(); var flags = new Flags();
                provider.flipOnBalanceCall = call;
                provider.reentrantHook = appreciation ? () -> flags.appreciationOn.set(false) : () -> flags.ownerAway.set(true);
                var observation = execute(provider, flags, TransferProof.Leg.DEBIT, FIXED, new ArrayList<>());
                assertEquals(0, provider.withdraws, "call " + call);
                assertEquals(TransferProof.Refusal.PRECHECK_REFUSED, observation.refusedBeforeCall(), "call " + call);
                assertEquals(TransferProof.LegOutcome.NO_MOVEMENT, TransferProof.evaluate(CENTS, TransferProof.Leg.DEBIT, observation));
            }
        }
    }

    @Test void negativeControlTheOldOrderWithdrawsDespiteEitherFlip() {
        // Proves the two tests above can fail: without the reread, both flips go unnoticed and money moves.
        for (boolean appreciation : new boolean[]{true, false}) {
            var provider = new Provider(); var flags = new Flags();
            provider.flipOnBalanceCall = 3;
            provider.reentrantHook = appreciation ? () -> flags.appreciationOn.set(false) : () -> flags.ownerAway.set(true);
            var observation = execute(provider, flags, TransferProof.Leg.DEBIT, LEGACY, new ArrayList<>());
            assertEquals(1, provider.withdraws, appreciation ? "appreciation flip" : "away flip");
            assertEquals(TransferProof.LegOutcome.CONFIRMED,
                    TransferProof.evaluate(CENTS, TransferProof.Leg.DEBIT, observation));
        }
    }

    @Test void creditIsNeverBlockedEvenWithBothFlagsFlippedByTheProvider() {
        var provider = new Provider(); var flags = new Flags();
        provider.flipOnBalanceCall = 1;
        provider.reentrantHook = () -> { flags.appreciationOn.set(false); flags.ownerAway.set(true); };
        var trace = new ArrayList<String>();
        // CREDIT through the helper (as the lease authorize calls it for both legs) and through the observer.
        UtilityPaymentRuntime.finalDebitChecks(TransferProof.Leg.CREDIT, () -> trace.add("debit-only"),
                flags.debitGate, flags.awayHeld);
        int[] checks = {0};
        var pin = VaultMoneySource.pinFor(provider.economy);
        var observation = new LegObservation(pin, () -> {
            ++checks[0];
            UtilityPaymentRuntime.finalDebitChecks(TransferProof.Leg.CREDIT, () -> trace.add("debit-only"),
                    flags.debitGate, flags.awayHeld);
            return pin;
        }, () -> true).execute(ID, ACCOUNT, OWNER, CENTS, TransferProof.Leg.CREDIT);
        assertTrue(checks[0] >= 2 && !flags.appreciationOn.get() && flags.ownerAway.get(), "flipped before the deposit");
        assertEquals(1, provider.deposits);
        assertEquals(TransferProof.LegOutcome.CONFIRMED, TransferProof.evaluate(CENTS, TransferProof.Leg.CREDIT, observation));
        assertTrue(trace.isEmpty(), "CREDIT runs no DEBIT-only checks");
        assertEquals(0, flags.gateReads.get(), "CREDIT never reads the live switch");
        assertEquals(0, flags.awayReads.get(), "CREDIT never reads owner-away");
    }

    @Test void flagAlreadyOffFailsFastBeforeAnyExternalCallback() {
        for (boolean appreciation : new boolean[]{true, false}) {
            var flags = new Flags();
            if (appreciation) flags.appreciationOn.set(false); else flags.ownerAway.set(true);
            var trace = new ArrayList<String>();
            assertThrows(IllegalStateException.class, () -> UtilityPaymentRuntime.finalDebitChecks(TransferProof.Leg.DEBIT,
                    () -> trace.add("external"), flags.debitGate, flags.awayHeld));
            assertTrue(trace.isEmpty(), "no external callback once a gate already refuses");
        }
    }

    @Test void aFlipAtTheEndOfTheExternalChecksIsStillCaught() {
        // validate's plugin calls run after requireFloor; a flip there is caught by the same reread.
        for (boolean appreciation : new boolean[]{true, false}) {
            var flags = new Flags();
            assertThrows(IllegalStateException.class, () -> UtilityPaymentRuntime.finalDebitChecks(TransferProof.Leg.DEBIT,
                    () -> { if (appreciation) flags.appreciationOn.set(false); else flags.ownerAway.set(true); },
                    flags.debitGate, flags.awayHeld));
        }
        var flags = new Flags(); var ran = new AtomicBoolean();
        UtilityPaymentRuntime.finalDebitChecks(TransferProof.Leg.DEBIT, () -> ran.set(true), flags.debitGate, flags.awayHeld);
        assertTrue(ran.get(), "with no flip the external checks run");
    }

    @Test void payRentRoutesBothDebitCheckPointsThroughTheFinalChecks() throws Exception {
        String code = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/economy/UtilityPaymentRuntime.java"));
        String helper = section(code, "static void finalDebitChecks(", "private boolean payoutAwayHeld(");
        int run = helper.indexOf("debitOnlyChecks.run();");
        assertTrue(run > 0);
        assertTrue(helper.lastIndexOf("requireDebitGate(leg, debitGate);") > run, "live switch reread after the externals");
        assertTrue(helper.lastIndexOf("requireAwayDebitGate(leg, awayHeld);") > run, "owner-away reread after the externals");
        assertTrue(helper.indexOf("if (leg != TransferProof.Leg.DEBIT) return;") < run, "CREDIT never runs the externals");

        String rent = section(code, "Runnable floorDeferred, BooleanSupplier debitGate) {", "private CompletableFuture<Integer> payAppreciation(");
        assertEquals(2, count(rent, "finalDebitChecks(leg, () -> {"), "observer and lease authorize");
        assertEquals(2, count(rent, "}, payoutGate, awayHeld);"));
        assertEquals(2, count(rent, "requireFloor("), "every floor read sits inside the final checks");
        assertEquals(1, count(rent, "requireDebitGate("), "fail fast before dispatch");
        assertTrue(rent.contains("requireDebitGate(TransferProof.Leg.DEBIT, payoutGate);"));
        assertTrue(rent.contains("BooleanSupplier payoutGate = () -> debitGate.getAsBoolean() && plugin.propertyGuard().payoutsAllowed();"));
        assertEquals(0, count(rent, "requireAwayDebitGate("), "no gate outside the helper");
        // The observer's checks are the last thing the supplier does before returning the provider to withdraw.
        int observer = rent.indexOf("if (leg == TransferProof.Leg.DEBIT && ++providerChecks[0] == 2) finalDebitChecks(leg, () -> {");
        int end = rent.indexOf("}, payoutGate, awayHeld);", observer);
        assertTrue(observer > 0 && end > observer);
        String tail = rent.substring(end, rent.indexOf("}, Bukkit::isPrimaryThread);", end));
        assertEquals("}, payoutGate, awayHeld);\n                return pinned;\n            ",
                tail.replace("\r\n", "\n"));
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
