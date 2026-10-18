package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.economy.vault.VaultMoneySource;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Package bridge for the existing real-MariaDB fixture; not shipped in the plugin. */
public final class TransferDispatchFixture {
    private TransferDispatchFixture() { }

    public static String recoveryDomain(Database database, long eventId) throws Exception {
        return RefundRecoveryTool.domain(database, eventId);
    }

    public static void checkBillRecovery(Database database, UUID reserve, long eventId, UUID payer) throws Exception {
        RefundRecoveryTool.checkBillReceipt(database, reserve, eventId, payer);
    }

    public static String completeBillRecovery(Database database, UUID reserve, long eventId) throws Exception {
        return RefundRecoveryTool.completeBillReceipt(database, reserve, eventId);
    }
    public static CompletionStage<TransferJournal.Receipt> dispatch(TransferJournal journal,
            TransferJournal.Handle handle, List<String> calls) {
        return dispatch(journal, handle, calls, 100.0);
    }

    /** Both accounts start at the given whole-dollar balance. */
    public static CompletionStage<TransferJournal.Receipt> dispatch(TransferJournal journal,
            TransferJournal.Handle handle, List<String> calls, double startDollars) {
        var lease = new JournaledTransfer.Lease() {
            public void authorize(TransferJournal.Receipt r, TransferProof.Leg leg) { calls.add("authorize-" + leg); }
            public void releaseVerified(TransferJournal.Receipt r) { calls.add("release"); }
        };
        return new JournaledTransfer(journal, (r, leg) -> {
            calls.add("money-" + leg);
            double dollars = r.cents() / 100.0;
            return new TransferProof.Observation(true, true, dollars, startDollars,
                    leg == TransferProof.Leg.DEBIT ? startDollars - dollars : startDollars + dollars, false);
        }, Runnable::run, Runnable::run).submit(handle, lease);
    }

    /**
     * One dispatcher that runs every leg through the real Vault leg check against a two-account fake provider.
     * balances is changed in place by the provider's withdraw and deposit. refuse decides, per leg, whether the
     * lease's authorize refuses (as a limits reload would before a debit, or a change of economy provider before a
     * credit). Refusals are reported to heard.
     */
    public static final class Vault {
        private final JournaledTransfer dispatcher;
        private final List<String> calls;
        private final Predicate<TransferProof.Leg> refuse;

        public Vault(TransferJournal journal, Map<UUID, Double> balances, Predicate<TransferProof.Leg> refuse, List<String> calls,
                     BiConsumer<TransferProof.Leg, TransferProof.Observation> heard) {
            this.calls = calls;
            this.refuse = refuse;
            Economy economy = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[]{Economy.class},
                    (p, m, a) -> switch (m.getName()) {
                        case "isEnabled" -> true;
                        case "getBalance" -> balances.get(((OfflinePlayer) a[0]).getUniqueId());
                        case "withdrawPlayer", "depositPlayer" -> {
                            UUID id = ((OfflinePlayer) a[0]).getUniqueId();
                            double amount = (double) a[1];
                            calls.add(m.getName() + "-" + id);
                            balances.put(id, balances.get(id) + (m.getName().equals("withdrawPlayer") ? -amount : amount));
                            yield new EconomyResponse(amount, balances.get(id), EconomyResponse.ResponseType.SUCCESS, null);
                        }
                        default -> throw new AssertionError("unexpected economy call: " + m.getName());
                    });
            this.dispatcher = new JournaledTransfer(journal, (receipt, leg) -> {
                var pin = VaultMoneySource.pinFor(economy);
                var observer = new LegObservation(pin, () -> pin, () -> true);
                UUID account = leg == TransferProof.Leg.DEBIT ? receipt.payer() : receipt.payee();
                UUID other = leg == TransferProof.Leg.DEBIT ? receipt.payee() : receipt.payer();
                return observer.execute(account, player(account), player(other), receipt.cents(), leg);
            }, Runnable::run, Runnable::run, heard);
        }

        public CompletionStage<TransferJournal.Receipt> submit(TransferJournal.Handle handle) {
            return dispatcher.submit(handle, new JournaledTransfer.Lease() {
                public void authorize(TransferJournal.Receipt r, TransferProof.Leg leg) {
                    calls.add("authorize-" + leg);
                    if (refuse.test(leg)) throw new IllegalStateException("Rent limits changed");
                }
                public void releaseVerified(TransferJournal.Receipt r) { calls.add("release"); }
            });
        }

        private static OfflinePlayer player(UUID id) {
            return (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class},
                    (p, m, a) -> {
                        if (m.getName().equals("getUniqueId")) return id;
                        if (m.getName().equals("hashCode")) return id.hashCode();
                        if (m.getName().equals("equals")) return p == a[0];
                        throw new AssertionError("unexpected player call: " + m.getName());
                    });
        }
    }
}
