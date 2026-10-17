package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.TransferProof.Leg;
import com.prosperitysmp.npc.economy.TransferProof.LegOutcome;
import com.prosperitysmp.npc.economy.TransferProof.Refusal;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class VaultLegObservationTest {
    private static final UUID ID = new UUID(0, 7), OTHER_ID = new UUID(0, 8);
    private static final OfflinePlayer ACCOUNT = player(ID), COUNTERPARTY = player(OTHER_ID);

    private static OfflinePlayer player(UUID id) {
        return (OfflinePlayer) Proxy.newProxyInstance(
                OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class}, (p,m,a) -> {
                    if (m.getName().equals("getUniqueId")) return id;
                    throw new AssertionError("unexpected player call: " + m.getName());
                });
    }

    /** Two-account fake provider that records every call in order. */
    private static final class Provider {
        double balance = 100, counterpartyBalance = 50;
        int calls, balanceReads, supplierCalls;
        boolean failAfterMovement, failBeforeMovement, missingResponse, stale, staleOnSecondCheck;
        RuntimeException supplierThrows;
        Error supplierError;
        final List<String> trace = new ArrayList<>();
        final Economy economy = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(),
                new Class<?>[]{Economy.class}, (p,m,a) -> {
                    if (m.getName().equals("isEnabled")) return true;
                    if (m.getName().equals("getBalance")) {
                        balanceReads++;
                        if (a[0] == COUNTERPARTY) { trace.add("balance-counterparty"); return counterpartyBalance; }
                        assertSame(ACCOUNT,a[0]); trace.add("balance-account"); return balance;
                    }
                    if (m.getName().equals("withdrawPlayer") || m.getName().equals("depositPlayer")) {
                        assertSame(ACCOUNT,a[0]); calls++;
                        trace.add(m.getName().equals("withdrawPlayer") ? "withdraw" : "deposit");
                        if (failBeforeMovement) throw new IllegalStateException("provider refused inside the call");
                        double amount = (double) a[1];
                        balance += m.getName().equals("withdrawPlayer") ? -amount : amount;
                        if (failAfterMovement) throw new IllegalStateException("private provider diagnostic");
                        return missingResponse ? null : new EconomyResponse(amount,balance,EconomyResponse.ResponseType.SUCCESS,null);
                    }
                    throw new AssertionError("unexpected economy call: " + m.getName());
                });
        final Supplier<Economy> current = () -> {
            supplierCalls++;
            trace.add("supplier-" + supplierCalls);
            if (supplierThrows != null) throw supplierThrows;
            if (supplierError != null) throw supplierError;
            if (stale || (staleOnSecondCheck && supplierCalls == 2)) return null;
            return economy;
        };
        VaultLegObservation adapter(boolean main) { return new VaultLegObservation(economy, current, () -> main); }
        TransferProof.Observation run(long cents, Leg leg) { return adapter(true).execute(ID, ACCOUNT, COUNTERPARTY, cents, leg); }
    }

    @Test void exactDebitAndCreditHaveOneCallEach() {
        for (var leg : Leg.values()) {
            var provider = new Provider();
            var observation = provider.run(1234, leg);
            assertEquals(1, provider.calls);
            assertNull(observation.refusedBeforeCall());
            assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(1234, leg, observation));
        }
    }

    @Test void debitCallOrderIsPinned() {
        var provider = new Provider();
        provider.run(500, Leg.DEBIT);
        assertEquals(List.of("supplier-1", "balance-counterparty", "balance-account", "supplier-2", "withdraw",
                "supplier-3", "balance-account", "supplier-4"), provider.trace);
        var credit = new Provider();
        credit.run(500, Leg.CREDIT);
        assertEquals(List.of("supplier-1", "balance-account", "supplier-2", "deposit",
                "supplier-3", "balance-account", "supplier-4"), credit.trace, "a credit never reads the counterparty");
    }

    @Test void movementThenExceptionRetainsEvidenceButNeverRetries() {
        var provider = new Provider(); provider.failAfterMovement = true;
        var observation = provider.run(500, Leg.DEBIT);
        assertEquals(1,provider.calls);
        assertEquals(95.0,observation.afterDollars());
        assertTrue(observation.threw());
        assertNull(observation.refusedBeforeCall(), "a throw from the money call is never a refusal");
        assertEquals(LegOutcome.AMBIGUOUS,TransferProof.evaluate(500,Leg.DEBIT,observation));
    }

    @Test void providerThrowingBeforeMovingMoneyIsStillUncertain() {
        // The balance is unchanged, but the money call was made: nothing proves it did not move money.
        var provider = new Provider(); provider.failBeforeMovement = true;
        var observation = provider.run(500, Leg.DEBIT);
        assertEquals(1, provider.calls);
        assertEquals(observation.beforeDollars(), observation.afterDollars());
        assertNull(observation.refusedBeforeCall());
        assertEquals(LegOutcome.AMBIGUOUS, TransferProof.evaluate(500, Leg.DEBIT, observation));
    }

    @Test void overdraftCapableProviderIsNeverCalledWithoutAvailableFunds() {
        var provider = new Provider(); provider.balance = 18.50;
        var observation = provider.run(15000, Leg.DEBIT);
        assertEquals(0, provider.calls);
        assertEquals(18.50, provider.balance);
        assertEquals(18.50, observation.beforeDollars());
        assertEquals(18.50, observation.afterDollars());
        // Do not invent a provider failure response for a call that never happened.
        assertFalse(observation.responsePresent());
        assertTrue(observation.threw());
        assertEquals(Refusal.INSUFFICIENT_FUNDS, observation.refusedBeforeCall());
        assertEquals(TransferProof.TransferOutcome.DECLINED, TransferProof.evaluateTransfer(15000, observation, null));
    }

    @Test void aBalanceOfExactlyTheRequestIsEnough() {
        var provider = new Provider(); provider.balance = 5.0;
        var observation = provider.run(500, Leg.DEBIT);
        assertEquals(1, provider.calls);
        assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(500, Leg.DEBIT, observation));
        // Just under the request, beyond the whole-cent noise: rounded down, so not enough.
        var fractional = new Provider(); fractional.balance = 4.9999999;
        assertEquals(Refusal.INSUFFICIENT_FUNDS, fractional.run(500, Leg.DEBIT).refusedBeforeCall());
        assertEquals(0, fractional.calls);
    }

    @Test void missingResponseCannotBeConfirmedFromBalanceAlone() {
        var provider = new Provider(); provider.missingResponse = true;
        var observation = provider.run(500, Leg.CREDIT);
        assertEquals(1,provider.calls);
        assertEquals(105.0,observation.afterDollars());
        assertEquals(LegOutcome.AMBIGUOUS,TransferProof.evaluate(500,Leg.CREDIT,observation));
    }

    @Test void wrongThreadAndInvalidRequestMakeNoProviderCallAtAll() {
        var provider = new Provider();
        var wrongThread = provider.adapter(false).execute(ID, ACCOUNT, COUNTERPARTY, 500, Leg.DEBIT);
        assertEquals(Refusal.WRONG_THREAD, wrongThread.refusedBeforeCall());
        assertTrue(wrongThread.refusedBeforeCall().unexpected());
        for (var invalid : List.<TransferProof.Observation>of(
                provider.adapter(true).execute(new UUID(0, 9), ACCOUNT, COUNTERPARTY, 500, Leg.DEBIT),
                provider.adapter(true).execute(ID, ACCOUNT, null, 500, Leg.DEBIT),
                provider.adapter(true).execute(null, ACCOUNT, COUNTERPARTY, 500, Leg.DEBIT),
                provider.adapter(true).execute(ID, null, COUNTERPARTY, 500, Leg.DEBIT),
                provider.adapter(true).execute(ID, ACCOUNT, COUNTERPARTY, 500, null),
                provider.adapter(true).execute(ID, ACCOUNT, COUNTERPARTY, 0, Leg.DEBIT),
                provider.adapter(true).execute(ID, ACCOUNT, COUNTERPARTY, 838_860_800L, Leg.DEBIT))) {
            assertEquals(Refusal.INVALID_REQUEST, invalid.refusedBeforeCall());
            assertEquals(LegOutcome.NO_MOVEMENT, TransferProof.evaluate(500, Leg.DEBIT, invalid));
        }
        assertEquals(0, provider.calls);
        assertEquals(0, provider.balanceReads, "not even a balance read");
        assertEquals(0, provider.supplierCalls);
    }

    @Test void providerDriftAndUnverifiableBalancePreventTheCall() {
        var provider = new Provider(); provider.stale = true;
        var drift = provider.run(500, Leg.DEBIT);
        assertTrue(drift.threw());
        assertEquals(Refusal.PROVIDER_CHANGED, drift.refusedBeforeCall());
        assertEquals(LegOutcome.NO_MOVEMENT, TransferProof.evaluate(500, Leg.DEBIT, drift));
        provider.stale = false; provider.balance = Double.NaN;
        var unverifiable = provider.run(500, Leg.DEBIT);
        assertTrue(unverifiable.threw());
        assertEquals(Refusal.BALANCE_UNVERIFIABLE, unverifiable.refusedBeforeCall());
        assertEquals(LegOutcome.NO_MOVEMENT, TransferProof.evaluate(500, Leg.DEBIT, unverifiable));
        provider.balance = -5.0;
        assertEquals(Refusal.BALANCE_UNVERIFIABLE, provider.run(500, Leg.DEBIT).refusedBeforeCall());
        assertEquals(0,provider.calls);
    }

    @Test void staleProviderOnTheSecondCheckAfterTheCounterpartyReadPreventsTheCall() {
        var provider = new Provider(); provider.staleOnSecondCheck = true;
        var observation = provider.run(500, Leg.DEBIT);
        assertEquals(Refusal.PROVIDER_CHANGED, observation.refusedBeforeCall());
        assertEquals(0, provider.calls);
        assertTrue(provider.trace.indexOf("balance-counterparty") < provider.trace.indexOf("supplier-2"));
    }

    @Test void fractionalPlayerDebitAndFractionalReserveCreditAreConfirmed() {
        var player = new Provider(); player.balance = 1234.5678901;
        var debit = player.run(1234, Leg.DEBIT);
        assertNull(debit.refusedBeforeCall());
        assertEquals(1, player.calls);
        assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(1234, Leg.DEBIT, debit));
        var reserve = new Provider(); reserve.balance = 57492.488404927248686;
        var credit = reserve.run(1234, Leg.CREDIT);
        assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(1234, Leg.CREDIT, credit));
        var reserveDebit = new Provider(); reserveDebit.balance = 57492.488404927248686;
        assertEquals(LegOutcome.CONFIRMED, TransferProof.evaluate(1234, Leg.DEBIT, reserveDebit.run(1234, Leg.DEBIT)));
    }

    @Test void counterpartyHeadroomIsCheckedDuringTheDebit() {
        var atLimit = new Provider(); atLimit.counterpartyBalance = 900_000_000.00 - 5.00;
        assertNull(atLimit.run(500, Leg.DEBIT).refusedBeforeCall());
        assertEquals(1, atLimit.calls, "exactly at the receiver limit proceeds");
        var over = new Provider(); over.counterpartyBalance = 900_000_000.00 - 4.99;
        var refused = over.run(500, Leg.DEBIT);
        assertEquals(Refusal.COUNTERPARTY_UNVERIFIABLE, refused.refusedBeforeCall());
        assertEquals(0, over.calls);
        var boundary = new Provider(); boundary.counterpartyBalance = 999_999_999.93;
        assertEquals(Refusal.COUNTERPARTY_UNVERIFIABLE, boundary.run(7, Leg.DEBIT).refusedBeforeCall());
        assertEquals(0, boundary.calls);
        var unreadable = new Provider(); unreadable.counterpartyBalance = Double.NaN;
        assertEquals(Refusal.COUNTERPARTY_UNVERIFIABLE, unreadable.run(500, Leg.DEBIT).refusedBeforeCall());
    }

    @Test void creditStaysOneCentBelowTheCeiling() {
        var atMargin = new Provider(); atMargin.balance = Math.nextDown(999_999_994.99);
        assertNull(atMargin.run(500, Leg.CREDIT).refusedBeforeCall());
        assertEquals(1, atMargin.calls, "up to $999,999,999.99 after the credit proceeds");
        var over = new Provider(); over.balance = 999_999_995.00;
        var refused = over.run(500, Leg.CREDIT);
        assertEquals(Refusal.BALANCE_UNVERIFIABLE, refused.refusedBeforeCall());
        assertEquals(0, over.calls);
        // The counterparty is never read for a credit, whatever its balance.
        var credit = new Provider(); credit.counterpartyBalance = Double.NaN;
        assertNull(credit.run(500, Leg.CREDIT).refusedBeforeCall());
    }

    @Test void supplierRefusalsAreSplitIntoExpectedAndFault() {
        var refused = new Provider(); refused.supplierThrows = new IllegalStateException("authority changed: secret detail");
        var expected = refused.run(500, Leg.DEBIT);
        assertEquals(Refusal.PRECHECK_REFUSED, expected.refusedBeforeCall());
        assertEquals("java.lang.IllegalStateException", expected.refusalCause());
        assertFalse(expected.refusedBeforeCall().unexpected());
        assertEquals(0, refused.calls);
        var broken = new Provider(); broken.supplierError = new NoSuchMethodError("x");
        var fault = broken.run(500, Leg.DEBIT);
        assertEquals(Refusal.PRECHECK_FAILED, fault.refusedBeforeCall());
        assertTrue(fault.refusedBeforeCall().unexpected());
        assertEquals("java.lang.NoSuchMethodError", fault.refusalCause());
        assertEquals(0, broken.calls);
    }
}
