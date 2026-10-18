package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.economy.vault.VaultMoneySource;
import com.prosperitysmp.npc.property.guard.PropertyGuard;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PayoutGuardDebitRecheckTest {
    private static PropertyGuard guard() {
        var guard = PropertyGuard.waiting("test");
        guard.installed(GuardResult.installed(GuardMode.NONE), PropertyGuard.Identity.STRONG, () -> true);
        guard.reconciled();
        guard.whenUnguarded("reread-before-payout");
        assertTrue(guard.payoutsAllowed());
        return guard;
    }

    private record Observed(TransferProof.Observation result, int moneyCalls) { }

    private static Observed observe(TransferProof.Leg leg, PropertyGuard guard, boolean revoke) {
        int[] calls = {0}, checks = {0};
        double[] balance = {100.0};
        var economy = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[]{Economy.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> true;
                    case "getBalance" -> {
                        if (revoke) guard.whenUnguarded("hold-payouts");
                        yield balance[0];
                    }
                    case "withdrawPlayer", "depositPlayer" -> {
                        calls[0]++;
                        double amount = (double) args[1];
                        balance[0] += method.getName().equals("withdrawPlayer") ? -amount : amount;
                        yield new EconomyResponse(amount, balance[0], EconomyResponse.ResponseType.SUCCESS, null);
                    }
                    default -> throw new AssertionError(method.getName());
                });
        var player = (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(),
                new Class<?>[]{OfflinePlayer.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getUniqueId")) return new UUID(0, 671);
                    throw new AssertionError(method.getName());
                });
        var pin = VaultMoneySource.pinFor(economy);
        var observer = new LegObservation(pin, () -> {
            if (leg == TransferProof.Leg.DEBIT && ++checks[0] == 2)
                UtilityPaymentRuntime.finalDebitChecks(leg, () -> { }, guard::payoutsAllowed, () -> false);
            return pin;
        }, () -> true);
        var result = observer.execute(player.getUniqueId(), player, player, 500, leg);
        return new Observed(result, calls[0]);
    }

    @Test void aReloadInsideTheBalanceReadStopsTheWithdraw() {
        var stopped = observe(TransferProof.Leg.DEBIT, guard(), true);
        assertEquals(0, stopped.moneyCalls());
        assertEquals(TransferProof.Refusal.PRECHECK_REFUSED, stopped.result().refusedBeforeCall());
        assertFalse(stopped.result().responsePresent());
        assertEquals(1, observe(TransferProof.Leg.DEBIT, guard(), false).moneyCalls());
    }

    @Test void aNewHoldCannotStrandAnAcknowledgedDebit() {
        var credit = observe(TransferProof.Leg.CREDIT, guard(), true);
        assertEquals(1, credit.moneyCalls());
        assertNull(credit.result().refusedBeforeCall());
        assertTrue(credit.result().responseSuccess());
    }

    @Test void rentAndAppreciationUseTheLiveGuardAtBothDebitGates() throws Exception {
        String code = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/economy/UtilityPaymentRuntime.java"));
        int from = code.indexOf("Runnable floorDeferred, BooleanSupplier debitGate) {");
        String rent = code.substring(from, code.indexOf("private CompletableFuture<Integer> payAppreciation(", from));
        assertTrue(rent.contains("BooleanSupplier payoutGate = () -> debitGate.getAsBoolean() && plugin.propertyGuard().payoutsAllowed();"));
        int admission = rent.indexOf("requireDebitGate(TransferProof.Leg.DEBIT, payoutGate);");
        assertTrue(admission >= 0 && admission < rent.indexOf("var handle = new TransferJournal.Handle("));
        assertEquals(2, rent.split(java.util.regex.Pattern.quote("}, payoutGate, awayHeld);"), -1).length - 1);
    }
}
