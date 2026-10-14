package com.prosperitysmp.npc.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class VaultLegObservationTest {
    private static final UUID ID = new UUID(0, 7);
    private static final OfflinePlayer ACCOUNT = (OfflinePlayer) Proxy.newProxyInstance(
            OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class}, (p,m,a) -> {
                if (m.getName().equals("getUniqueId")) return ID;
                throw new AssertionError("unexpected player call: " + m.getName());
            });

    private static final class Provider {
        double balance = 100;
        int calls;
        boolean failAfterMovement, missingResponse, stale;
        final Economy economy = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(),
                new Class<?>[]{Economy.class}, (p,m,a) -> {
                    if (m.getName().equals("isEnabled")) return true;
                    if (m.getName().equals("getBalance")) { assertSame(ACCOUNT,a[0]); return balance; }
                    if (m.getName().equals("withdrawPlayer") || m.getName().equals("depositPlayer")) {
                        assertSame(ACCOUNT,a[0]); calls++;
                        double amount = (double) a[1];
                        balance += m.getName().equals("withdrawPlayer") ? -amount : amount;
                        if (failAfterMovement) throw new IllegalStateException("private provider diagnostic");
                        return missingResponse ? null : new EconomyResponse(amount,balance,EconomyResponse.ResponseType.SUCCESS,null);
                    }
                    throw new AssertionError("unexpected economy call: " + m.getName());
                });
        VaultLegObservation adapter(boolean main) { return new VaultLegObservation(economy, () -> stale ? null : economy, () -> main); }
    }

    @Test void exactDebitAndCreditHaveOneCallEach() {
        for (var leg : TransferProof.Leg.values()) {
            var provider = new Provider();
            var observation = provider.adapter(true).execute(ID, ACCOUNT, 1234, leg);
            assertEquals(1, provider.calls);
            assertEquals(TransferProof.LegOutcome.CONFIRMED, TransferProof.evaluate(1234, leg, observation));
        }
    }

    @Test void movementThenExceptionRetainsEvidenceButNeverRetries() {
        var provider = new Provider(); provider.failAfterMovement = true;
        var observation = provider.adapter(true).execute(ID, ACCOUNT, 500, TransferProof.Leg.DEBIT);
        assertEquals(1,provider.calls);
        assertEquals(95.0,observation.afterDollars());
        assertTrue(observation.threw());
        assertEquals(TransferProof.LegOutcome.AMBIGUOUS,TransferProof.evaluate(500,TransferProof.Leg.DEBIT,observation));
    }

    @Test void overdraftCapableProviderIsNeverCalledWithoutAvailableFunds() {
        var provider = new Provider(); provider.balance = 18.50;
        var observation = provider.adapter(true).execute(ID, ACCOUNT, 15000, TransferProof.Leg.DEBIT);
        assertEquals(0, provider.calls);
        assertEquals(18.50, provider.balance);
        assertEquals(18.50, observation.beforeDollars());
        assertEquals(18.50, observation.afterDollars());
        // Do not invent a provider failure response for a call that never happened.
        assertFalse(observation.responsePresent());
        assertTrue(observation.threw());
    }

    @Test void missingResponseCannotBeConfirmedFromBalanceAlone() {
        var provider = new Provider(); provider.missingResponse = true;
        var observation = provider.adapter(true).execute(ID, ACCOUNT, 500, TransferProof.Leg.CREDIT);
        assertEquals(1,provider.calls);
        assertEquals(105.0,observation.afterDollars());
        assertEquals(TransferProof.LegOutcome.AMBIGUOUS,TransferProof.evaluate(500,TransferProof.Leg.CREDIT,observation));
    }

    @Test void wrongThreadAndWrongAccountCannotMoveMoney() {
        var provider = new Provider();
        assertThrows(IllegalStateException.class, () -> provider.adapter(false).execute(ID,ACCOUNT,500,TransferProof.Leg.DEBIT));
        assertThrows(IllegalArgumentException.class, () -> provider.adapter(true).execute(new UUID(0,8),ACCOUNT,500,TransferProof.Leg.DEBIT));
        assertEquals(0,provider.calls);
    }

    @Test void providerDriftAndUnverifiableBalancePreventTheCall() {
        var provider = new Provider(); provider.stale = true;
        assertTrue(provider.adapter(true).execute(ID,ACCOUNT,500,TransferProof.Leg.DEBIT).threw());
        provider.stale = false; provider.balance = Double.NaN;
        assertTrue(provider.adapter(true).execute(ID,ACCOUNT,500,TransferProof.Leg.DEBIT).threw());
        assertEquals(0,provider.calls);
    }
}
