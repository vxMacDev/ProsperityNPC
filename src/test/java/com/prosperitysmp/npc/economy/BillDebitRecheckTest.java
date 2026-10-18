package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.vault.VaultMoneySource;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class BillDebitRecheckTest {
    private record Observed(TransferProof.Observation result, int calls) { }

    private static Observed observe(TransferProof.Leg leg, boolean revoke, boolean recheck) {
        var enabled = new AtomicBoolean(true);
        int[] calls = {0}, checks = {0};
        double[] balance = {100};
        var economy = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[]{Economy.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> true;
                    case "getBalance" -> {
                        if (revoke) enabled.set(false);
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
                    if (method.getName().equals("getUniqueId")) return new UUID(0, 6501);
                    throw new AssertionError(method.getName());
                });
        var pin = VaultMoneySource.pinFor(economy);
        var observer = new LegObservation(pin, () -> {
            if (recheck && leg == TransferProof.Leg.DEBIT && ++checks[0] == 2)
                UtilityPaymentRuntime.requireDebitGate(leg, enabled::get);
            return pin;
        }, () -> true);
        return new Observed(observer.execute(player.getUniqueId(), player, player, 500, leg), calls[0]);
    }

    @Test void switchingOffBillsInsideTheBalanceReadStopsTheDebit() {
        var result = observe(TransferProof.Leg.DEBIT, true, true);
        assertEquals(0, result.calls());
        assertEquals(TransferProof.Refusal.PRECHECK_REFUSED, result.result().refusedBeforeCall());
        assertFalse(result.result().responsePresent());
        assertEquals(1, observe(TransferProof.Leg.DEBIT, false, true).calls());
    }

    @Test void theOldOrderStillWithdrawsAfterBillsAreSwitchedOff() {
        assertEquals(1, observe(TransferProof.Leg.DEBIT, true, false).calls());
    }

    @Test void anAcknowledgedDebitCanFinishItsCreditAfterSwitchOff() {
        var result = observe(TransferProof.Leg.CREDIT, true, true);
        assertEquals(1, result.calls());
        assertNull(result.result().refusedBeforeCall());
        assertTrue(result.result().responseSuccess());
        assertDoesNotThrow(() -> UtilityPaymentRuntime.requireDebitGate(TransferProof.Leg.CREDIT,
                () -> { fail("credit must not read the bill switch"); return false; }));
    }

    @Test void manualAndAutomaticBillsShareAdmissionAndBothFinalDebitChecks() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/economy/UtilityPaymentRuntime.java"));
        int start = source.indexOf("private boolean requestCore(");
        String core = source.substring(start, source.indexOf("static String runLine(", start));
        assertTrue(core.contains("BooleanSupplier billGate = () -> invoiceId == null ||"));
        assertTrue(core.contains("plugin.billing().enabledNow()"));
        int admission = core.indexOf("!billGate.getAsBoolean()");
        assertTrue(admission >= 0 && admission < core.indexOf("registration.balanceDollars("));
        assertEquals(2, core.split(java.util.regex.Pattern.quote("requireDebitGate(leg, billGate);"), -1).length - 1);
        int observer = core.indexOf("var observer = new LegObservation(");
        int lastCheck = core.indexOf("requireDebitGate(leg, billGate);", observer);
        assertTrue(lastCheck > core.indexOf("validate(property, actor, authorized", observer));
        assertTrue(lastCheck < core.indexOf("return registration;", observer));
    }
}
