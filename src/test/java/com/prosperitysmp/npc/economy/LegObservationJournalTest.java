package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.api.MoneySource;
import com.prosperitysmp.npc.economy.vault.VaultMoneySource;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Money properties of the leg code itself, over the Vault source and a fake provider that keeps whole cents:
 * money is conserved across debit and credit pairs, a failed or refused credit is never followed by a compensating
 * call, and the "called" flag is set immediately before the one money call.
 */
class LegObservationJournalTest {
    private static final UUID PAYER = new UUID(0, 51), RESERVE = new UUID(0, 52);

    private static OfflinePlayer player(UUID id) {
        return (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getUniqueId" -> id;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> p == a[0];
                    default -> throw new AssertionError("unexpected player call: " + m.getName());
                });
    }

    private static final OfflinePlayer PAYER_P = player(PAYER), RESERVE_P = player(RESERVE);

    private static final class Bank {
        final Map<UUID, Long> cents = new HashMap<>();
        final List<String> moneyCalls = new ArrayList<>();
        boolean failDeposits;
        final Economy economy = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[]{Economy.class},
                (p, m, a) -> switch (m.getName()) {
                    case "isEnabled" -> true;
                    case "getBalance" -> cents.get(((OfflinePlayer) a[0]).getUniqueId()) / 100.0;
                    case "withdrawPlayer", "depositPlayer" -> {
                        UUID id = ((OfflinePlayer) a[0]).getUniqueId();
                        long amount = Math.round((double) a[1] * 100);
                        boolean withdraw = m.getName().equals("withdrawPlayer");
                        moneyCalls.add((withdraw ? "withdraw " : "deposit ") + id.getLeastSignificantBits() + " " + amount);
                        if (!withdraw && failDeposits)
                            yield new EconomyResponse(0, cents.get(id) / 100.0, EconomyResponse.ResponseType.FAILURE, "full");
                        cents.merge(id, withdraw ? -amount : amount, Long::sum);
                        yield new EconomyResponse((double) a[1], cents.get(id) / 100.0, EconomyResponse.ResponseType.SUCCESS, null);
                    }
                    default -> throw new AssertionError("unexpected economy call: " + m.getName());
                });
        final MoneySource.Pin pin = VaultMoneySource.pinFor(economy);

        long total() { return cents.values().stream().mapToLong(Long::longValue).sum(); }
    }

    private static TransferProof.Observation leg(Bank bank, MoneySource.Pin current, UUID account, long cents, TransferProof.Leg leg) {
        OfflinePlayer who = account.equals(PAYER) ? PAYER_P : RESERVE_P;
        OfflinePlayer other = account.equals(PAYER) ? RESERVE_P : PAYER_P;
        return new LegObservation(bank.pin, () -> current, () -> true).execute(account, who, other, cents, leg);
    }

    @Test void aSeededRunOfDebitAndCreditPairsConservesMoney() {
        var bank = new Bank();
        bank.cents.put(PAYER, 50_000_000L);
        bank.cents.put(RESERVE, 1_000_000L);
        long total = bank.total();
        var random = new Random(64);
        int pairs = 0;
        for (int i = 0; i < 500; i++) {
            long cents = 1 + random.nextInt(100_000);
            var debit = leg(bank, bank.pin, PAYER, cents, TransferProof.Leg.DEBIT);
            if (TransferProof.evaluate(cents, TransferProof.Leg.DEBIT, debit) != TransferProof.LegOutcome.CONFIRMED) continue;
            var credit = leg(bank, bank.pin, RESERVE, cents, TransferProof.Leg.CREDIT);
            assertEquals(TransferProof.LegOutcome.CONFIRMED, TransferProof.evaluate(cents, TransferProof.Leg.CREDIT, credit));
            pairs++;
            assertEquals(total, bank.total(), "after pair " + i);
        }
        assertTrue(pairs > 400, "pairs " + pairs);
        assertEquals(2L * pairs, bank.moneyCalls.size(), "one money call per leg, nothing else");
    }

    @Test void aFailedCreditIsNeverCompensated() {
        var bank = new Bank();
        bank.cents.put(PAYER, 10_000L);
        bank.cents.put(RESERVE, 0L);
        leg(bank, bank.pin, PAYER, 500, TransferProof.Leg.DEBIT);
        bank.failDeposits = true;
        var credit = leg(bank, bank.pin, RESERVE, 500, TransferProof.Leg.CREDIT);
        assertNotEquals(TransferProof.LegOutcome.CONFIRMED, TransferProof.evaluate(500, TransferProof.Leg.CREDIT, credit));
        assertEquals(List.of("withdraw 81 500", "deposit 82 500"), bank.moneyCalls.stream()
                .map(c -> c.replace(Long.toString(PAYER.getLeastSignificantBits()), "81")
                        .replace(Long.toString(RESERVE.getLeastSignificantBits()), "82")).toList(),
                "no deposit back to the payer, no second try");
    }

    @Test void aRefusedCreditMakesNoCallAtAll() {
        var bank = new Bank();
        bank.cents.put(PAYER, 10_000L);
        bank.cents.put(RESERVE, 0L);
        leg(bank, bank.pin, PAYER, 500, TransferProof.Leg.DEBIT);
        var other = new Bank();
        var credit = leg(bank, other.pin, RESERVE, 500, TransferProof.Leg.CREDIT);
        assertEquals(TransferProof.Refusal.PROVIDER_CHANGED, credit.refusedBeforeCall());
        assertEquals(1, bank.moneyCalls.size(), "only the debit");
    }

    @Test void calledIsSetImmediatelyBeforeTheMoneyCall() throws Exception {
        String code = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/economy/LegObservation.java"))
                .replace("\r\n", "\n");
        assertEquals(1, code.split("called = true;", -1).length - 1);
        assertTrue(code.contains("            called = true;\n            response = pinned.leg(account, cents, providerLeg);\n"));
        assertEquals(1, code.split("pinned\\.leg\\(", -1).length - 1, "one money call");
    }
}
