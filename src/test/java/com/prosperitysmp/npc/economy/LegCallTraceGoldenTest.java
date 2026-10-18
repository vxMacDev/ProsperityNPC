package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.api.MoneySource;
import com.prosperitysmp.npc.economy.vault.VaultMoneySource;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G5: the order of provider calls that {@code LegObservation} makes for a debit and a credit. A recording fake
 * provider logs each call. The suppliers below are hand-written copies shaped like each payment site's observer
 * (utility, rent, commerce), with the site's own checks stubbed by a log entry followed by the provider triple they
 * read (registration, economy plugin enabled, provider enabled); they are not the runtimes' real suppliers and can
 * drift from them (the utility copy has no bill debit gate, for example). So this test guards LegObservation's
 * ordering only, not the call order at each real payment site. The expected traces were written by hand from the
 * dev.63 order in VaultLegObservation and the three runtimes.
 */
class LegCallTraceGoldenTest {
    private static final String T = "registration,plugin-enabled,isEnabled";

    private final List<String> trace = new ArrayList<>();
    private final Economy economy = (Economy) Proxy.newProxyInstance(Economy.class.getClassLoader(), new Class<?>[]{Economy.class},
            (p, m, a) -> switch (m.getName()) {
                case "isEnabled" -> { trace.add("isEnabled"); yield true; }
                case "getBalance" -> { trace.add("balance:" + name((OfflinePlayer) a[0])); yield 1000.0; }
                case "withdrawPlayer", "depositPlayer" -> {
                    trace.add((m.getName().equals("withdrawPlayer") ? "withdraw:" : "deposit:") + name((OfflinePlayer) a[0]));
                    yield new EconomyResponse((double) a[1], 1000.0, EconomyResponse.ResponseType.SUCCESS, null);
                }
                default -> throw new AssertionError("unexpected economy call: " + m.getName());
            });
    private final Plugin owner = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
            (p, m, a) -> switch (m.getName()) {
                case "isEnabled" -> { trace.add("plugin-enabled"); yield true; }
                case "hashCode" -> 1;
                case "equals" -> p == a[0];
                default -> throw new UnsupportedOperationException(m.getName());
            });
    private final RegisteredServiceProvider<Economy> registration =
            new RegisteredServiceProvider<>(Economy.class, economy, ServicePriority.Normal, owner);
    private final MoneySource.Pin pin = VaultMoneySource.pinFor(registration, () -> {
        trace.add("registration");
        return registration;
    });

    private static final UUID ACTOR = new UUID(0, 1), RESERVE = new UUID(0, 2), OWNER = new UUID(0, 3);

    private static OfflinePlayer player(UUID id) {
        return (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(), new Class<?>[]{OfflinePlayer.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getUniqueId" -> id;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> p == a[0];
                    default -> throw new AssertionError("unexpected player call: " + m.getName());
                });
    }

    private static String name(OfflinePlayer player) {
        UUID id = player.getUniqueId();
        return id.equals(ACTOR) ? "actor" : id.equals(RESERVE) ? "reserve" : "owner";
    }

    /** The site's validate(...): its own checks, then the provider triple. */
    private void validate() {
        trace.add("validate");
        assertTrue(pin.stillCurrent());
    }

    private String run(UUID account, UUID counterparty, TransferProof.Leg leg, Supplier<MoneySource.Pin> supplier) {
        trace.clear();
        var observation = new LegObservation(pin, supplier, () -> true)
                .execute(account, player(account), player(counterparty), 500, leg);
        assertNull(observation.refusedBeforeCall(), String.join(",", trace));
        return String.join(",", trace);
    }

    /** UtilityPaymentRuntime's observer. */
    private Supplier<MoneySource.Pin> utility(TransferProof.Leg leg) {
        int[] providerChecks = {0};
        return () -> {
            trace.add("supplier");
            UtilityPaymentRuntime.requireLegAuthority(leg, pin, this::validate);
            if (leg == TransferProof.Leg.DEBIT && ++providerChecks[0] == 2)
                UtilityPaymentRuntime.requireAwayDebitGate(leg, () -> { trace.add("away"); return false; });
            return pin;
        };
    }

    /** UtilityPaymentRuntime.payRent's observer. */
    private Supplier<MoneySource.Pin> rent(TransferProof.Leg leg) {
        int[] providerChecks = {0};
        return () -> {
            trace.add("supplier");
            UtilityPaymentRuntime.requireLegAuthority(leg, pin, this::validate);
            if (leg == TransferProof.Leg.DEBIT && ++providerChecks[0] == 2) UtilityPaymentRuntime.finalDebitChecks(leg, () -> {
                pin.balanceDollars(player(RESERVE)); // requireFloor
                validate();
            }, () -> { trace.add("gate"); return true; }, () -> { trace.add("away"); return false; });
            return pin;
        };
    }

    /** CommercePaymentRuntime.observe's observer. */
    private Supplier<MoneySource.Pin> commerce(TransferProof.Leg leg) {
        return () -> {
            trace.add("supplier");
            UtilityPaymentRuntime.requireLegAuthority(leg, pin, () -> {
                trace.add("provider");
                assertTrue(pin.stillCurrent());
                trace.add("actor");
            });
            return pin;
        };
    }

    private static String credit(String account) {
        return String.join(",", "supplier," + T, "isEnabled", "balance:" + account, "supplier," + T, "deposit:" + account,
                "supplier," + T, "balance:" + account, "supplier," + T, "isEnabled");
    }

    @Test void utilityDebitAndCredit() {
        String v = "supplier,validate," + T;
        assertEquals(String.join(",", v, "isEnabled", "balance:reserve", "balance:actor", v, "away", "withdraw:actor",
                v, "balance:actor", v, "isEnabled"), run(ACTOR, RESERVE, TransferProof.Leg.DEBIT, utility(TransferProof.Leg.DEBIT)));
        assertEquals(credit("reserve"), run(RESERVE, ACTOR, TransferProof.Leg.CREDIT, utility(TransferProof.Leg.CREDIT)));
    }

    @Test void rentDebitAndCredit() {
        String v = "supplier,validate," + T;
        String finalChecks = "gate,away,balance:reserve,validate," + T + ",gate,away";
        assertEquals(String.join(",", v, "isEnabled", "balance:owner", "balance:reserve", v, finalChecks, "withdraw:reserve",
                v, "balance:reserve", v, "isEnabled"), run(RESERVE, OWNER, TransferProof.Leg.DEBIT, rent(TransferProof.Leg.DEBIT)));
        assertEquals(credit("owner"), run(OWNER, RESERVE, TransferProof.Leg.CREDIT, rent(TransferProof.Leg.CREDIT)));
    }

    @Test void commerceDebitAndCredit() {
        String c = "supplier,provider," + T + ",actor";
        assertEquals(String.join(",", c, "isEnabled", "balance:reserve", "balance:actor", c, "withdraw:actor",
                c, "balance:actor", c, "isEnabled"), run(ACTOR, RESERVE, TransferProof.Leg.DEBIT, commerce(TransferProof.Leg.DEBIT)));
        assertEquals(credit("reserve"), run(RESERVE, ACTOR, TransferProof.Leg.CREDIT, commerce(TransferProof.Leg.CREDIT)));
    }
}
