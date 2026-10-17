package com.prosperitysmp.npc.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.prosperitysmp.npc.economy.TransferJournal.*;
import static com.prosperitysmp.npc.economy.TransferProof.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * What each leg rechecks before its money call. The debit runs every check. The credit completes a debit the payer
 * already confirmed, so it checks only the economy provider (owner decision, 2026-10-15): a config reload, a kill
 * switch, a limit, a lost permission or a home sale between the two legs no longer strands the money already taken.
 */
class UtilityPaymentAuthorizationTest {
    private static final UUID PAYER = new UUID(0, 21), RESERVE = new UUID(0, 22), RUN = new UUID(0, 23);

    /** An economy plugin and its provider, each with a switch for isEnabled. */
    private static final class Provider {
        final AtomicBoolean pluginOn = new AtomicBoolean(true), providerOn = new AtomicBoolean(true);
        final RegisteredServiceProvider<Economy> registration;

        Provider() {
            Plugin plugin = proxy(Plugin.class, pluginOn);
            Economy economy = proxy(Economy.class, providerOn);
            registration = new RegisteredServiceProvider<>(Economy.class, economy, ServicePriority.Normal, plugin);
        }

        private static <T> T proxy(Class<T> type, AtomicBoolean enabled) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) ->
                    switch (method.getName()) {
                        case "isEnabled" -> enabled.get();
                        case "hashCode" -> System.identityHashCode(self);
                        case "equals" -> self == args[0];
                        case "toString" -> type.getSimpleName();
                        default -> throw new UnsupportedOperationException(method.getName());
                    }));
        }
    }

    @Test void theDebitRunsEveryCheck() {
        Provider pinned = new Provider();
        List<String> ran = new ArrayList<>();
        UtilityPaymentRuntime.requireLegAuthority(Leg.DEBIT, () -> fail("the debit's own checks read the provider"),
                pinned.registration, () -> ran.add("checks"));
        assertEquals(List.of("checks"), ran);
        assertThrows(IllegalStateException.class, () -> UtilityPaymentRuntime.requireLegAuthority(Leg.DEBIT,
                () -> pinned.registration, pinned.registration, () -> { throw new IllegalStateException("kill switch off"); }));
    }

    @Test void aSwitchTurnedOffAfterTheDebitNoLongerRefusesTheCredit() {
        Provider pinned = new Provider();
        // Every check a debit runs would now refuse: a kill switch, a reload, a lost permission, a sold home.
        assertDoesNotThrow(() -> UtilityPaymentRuntime.requireLegAuthority(Leg.CREDIT, () -> pinned.registration,
                pinned.registration, () -> fail("the credit must not rerun the debit's checks")));
    }

    @Test void aProviderChangeStillRefusesTheCredit() {
        Provider pinned = new Provider(), other = new Provider();
        assertEquals("Economy provider changed before the credit", assertThrows(IllegalStateException.class,
                () -> UtilityPaymentRuntime.requireLegAuthority(Leg.CREDIT, () -> other.registration, pinned.registration,
                        () -> { })).getMessage());
        assertThrows(IllegalStateException.class, () -> UtilityPaymentRuntime.requireLegAuthority(Leg.CREDIT, () -> null,
                pinned.registration, () -> { }), "no provider registered");
        pinned.pluginOn.set(false);
        assertThrows(IllegalStateException.class, () -> UtilityPaymentRuntime.requireLegAuthority(Leg.CREDIT,
                () -> pinned.registration, pinned.registration, () -> { }), "economy plugin disabled");
        pinned.pluginOn.set(true);
        pinned.providerOn.set(false);
        assertThrows(IllegalStateException.class, () -> UtilityPaymentRuntime.requireLegAuthority(Leg.CREDIT,
                () -> pinned.registration, pinned.registration, () -> { }), "provider disabled");
    }

    /** A real JournaledTransfer with a fake journal; main and worker are queued separately, so a change can land between any two hops. */
    private static final class Rig implements JournaledTransfer.Journal {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final Executor main = queue::add, worker = queue::add;
        final Provider pinned = new Provider();
        final AtomicReference<RegisteredServiceProvider<Economy>> registered = new AtomicReference<>(pinned.registration);
        final AtomicBoolean switchOn = new AtomicBoolean(true);
        final List<String> calls = new ArrayList<>();
        Runnable onDebitAck = () -> { };

        private static Receipt receipt(Handle h, State state) {
            return new Receipt(h, "b".repeat(64), PAYER, RESERVE, 1_000, state, null,
                    state == State.CREDIT_INTENT || state == State.PAID || state == State.AMBIGUOUS);
        }
        public Start beginDebit(Handle h) { return new Start(true, receipt(h, State.DEBIT_INTENT)); }
        public Receipt recordDebit(Handle h, Observation o) {
            onDebitAck.run();
            return receipt(h, o.refusedBeforeCall() != null ? State.DECLINED : State.CREDIT_READY);
        }
        public Start beginCredit(Handle h) { return new Start(true, receipt(h, State.CREDIT_INTENT)); }
        public Receipt recordCredit(Handle h, Observation o) {
            calls.add(o.refusedBeforeCall() == null ? "credited" : "credit-refused-" + o.refusedBeforeCall());
            return receipt(h, o.refusedBeforeCall() != null ? State.AMBIGUOUS : State.PAID);
        }

        /** The lease every payment runtime builds: the debit's checks include the runtime switch. */
        Receipt pay() {
            Handle handle = new Handle(new UUID(1, 1), 1, RUN, "a".repeat(64));
            var transfer = new JournaledTransfer(this, (r, leg) -> {
                calls.add("money-" + leg);
                return new Observation(true, true, 10.0, 100.0, leg == Leg.DEBIT ? 90.0 : 110.0, false);
            }, worker, main);
            var result = transfer.submit(handle, new JournaledTransfer.Lease() {
                public void authorize(Receipt r, Leg leg) {
                    UtilityPaymentRuntime.requireLegAuthority(leg, registered::get, pinned.registration, () -> {
                        if (!switchOn.get()) throw new IllegalStateException("Utility authority unavailable");
                    });
                }
                public void releaseVerified(Receipt r) { calls.add("release"); }
            }).toCompletableFuture();
            while (!queue.isEmpty()) queue.remove().run();
            return result.join();
        }
    }

    @Test void aMidTransferSwitchOffStillPaysTheCredit() {
        Rig rig = new Rig();
        rig.onDebitAck = () -> rig.switchOn.set(false); // /npcadmin reload turns payments off between the legs
        assertEquals(State.PAID, rig.pay().state());
        assertEquals(List.of("money-DEBIT", "money-CREDIT", "credited", "release"), rig.calls);
    }

    @Test void aMidTransferProviderChangeStillHoldsThePaymentForStaff() {
        Rig rig = new Rig();
        rig.onDebitAck = () -> rig.registered.set(new Provider().registration);
        assertEquals(State.AMBIGUOUS, rig.pay().state());
        assertEquals(List.of("money-DEBIT", "credit-refused-LEASE_REFUSED"), rig.calls, "no credit call, no release");
    }

    @Test void aSwitchAlreadyOffStillDeclinesTheDebit() {
        Rig rig = new Rig();
        rig.switchOn.set(false);
        assertEquals(State.DECLINED, rig.pay().state());
        assertEquals(List.of("release"), rig.calls, "no money call at all");
    }

    @Test void everyPaymentLegGoesThroughTheSharedRule() throws Exception {
        // The runtimes need a live server, so their wiring is pinned in source.
        String utility = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/economy/UtilityPaymentRuntime.java"))
                .replace("\r\n", "\n");
        String commerce = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/economy/CommercePaymentRuntime.java"))
                .replace("\r\n", "\n");
        // Utility and rent: the observer's provider check and the lease authorize, two each.
        assertEquals(5, count(utility, "requireLegAuthority("), "definition, two utility and two rent call sites");
        assertEquals(2, count(utility, "requireLegAuthority(leg, () -> registration(), registration,\n"
                + "                                        () -> validate(property, actor, authorized, registration, lease, day));"));
        assertEquals(2, count(utility, "requireLegAuthority(leg, () -> registration(), pinned, () -> {\n"));
        assertEquals(1, count(commerce, "UtilityPaymentRuntime.requireLegAuthority(leg, this::registration, pinned, () -> {\n"
                + "                provider(pinned, lease);"));
        // No authority check is chosen by leg anywhere else.
        assertFalse(utility.contains("actorAuthorization"));
        assertFalse(utility.contains("leg == TransferProof.Leg.CREDIT ? days.today() : day"));
        assertFalse(commerce.contains("leg == TransferProof.Leg.DEBIT && !actor("));
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
