package com.prosperitysmp.npc.economy.vault;

import com.prosperitysmp.npc.api.LegResponse;
import com.prosperitysmp.npc.api.MoneySource;
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
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The Vault source's own answers: its capabilities, diagnose text, probe and the amounts it sends to the provider. */
class VaultMoneySourceTest {
    private static final OfflinePlayer ACCOUNT = (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(),
            new Class<?>[]{OfflinePlayer.class}, (p, m, a) -> switch (m.getName()) {
                case "getUniqueId" -> new UUID(0, 77);
                case "hashCode" -> 77;
                case "equals" -> p == a[0];
                case "toString" -> "account";
                default -> throw new AssertionError("unexpected player call: " + m.getName());
            });

    private final List<String> calls = new ArrayList<>();
    private final AtomicBoolean providerOn = new AtomicBoolean(true);
    private final AtomicReference<EconomyResponse> response = new AtomicReference<>();

    private Economy economy() {
        return new FakeEconomy((name, args) -> switch (name) {
            case "isEnabled" -> providerOn.get();
            case "getBalance" -> 12.5;
            case "withdrawPlayer", "depositPlayer" -> {
                calls.add(name + " " + args[1]);
                yield response.get();
            }
            default -> throw new AssertionError("unexpected economy call: " + name);
        });
    }

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getName" -> name;
                    case "isEnabled" -> true;
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private RegisteredServiceProvider<Economy> registration(String pluginName) {
        return new RegisteredServiceProvider<>(Economy.class, economy(), ServicePriority.Normal, plugin(pluginName));
    }

    @Test
    void theCapabilitiesAreExactlyTheDocumentedSet() {
        Map<String, String> expected = new TreeMap<>(Map.of(
                "scale", "2",
                "balance-double-exact", "true",
                "artifact-hashable", "true",
                "non-player-accounts", "true",
                "offline-accounts", "true",
                "main-thread-only", "true",
                "reentrant-callbacks", "true",
                "permits-overdraft", "provider-dependent"));
        assertEquals(expected, new VaultMoneySource(() -> null).capabilities().all());
        assertEquals("vault", new VaultMoneySource(() -> null).id());
    }

    @Test
    void withNothingRegisteredTheSourceNamesNoProviderAndCannotPin() {
        var source = new VaultMoneySource(() -> null);
        assertEquals("none", source.providerName());
        assertEquals("no economy plugin is registered with Vault", source.reason());
        assertFalse(source.probe());
        assertTrue(source.pin().isEmpty());
    }

    @Test
    void aRegisteredProviderIsNamedThroughItsPlugin() {
        var registered = registration("Essentials");
        var source = new VaultMoneySource(() -> registered);
        assertEquals("Essentials via Vault", source.providerName());
        assertEquals("", source.reason());
        assertTrue(source.probe());
        assertTrue(source.pin().isPresent());
    }

    @Test
    void theRegistrationIsReadAgainOnEveryCall() {
        AtomicReference<RegisteredServiceProvider<Economy>> current = new AtomicReference<>();
        var source = new VaultMoneySource(current::get);
        assertEquals("none", source.providerName());
        current.set(registration("CMI"));
        assertEquals("CMI via Vault", source.providerName());
        assertTrue(source.probe());
        current.set(null);
        assertFalse(source.probe());
    }

    @Test
    void aDebitWithdrawsTheCentsAsDollarsAndReportsTheProvidersAnswer() {
        response.set(new EconomyResponse(0.01, 12.49, EconomyResponse.ResponseType.SUCCESS, null));
        MoneySource.Pin pin = VaultMoneySource.pinFor(economy());
        LegResponse answer = pin.leg(ACCOUNT, 1, MoneySource.Leg.DEBIT);
        assertEquals(List.of("withdrawPlayer 0.01"), calls);
        assertTrue(answer.success());
        assertEquals(0.01, answer.amountDollars());
    }

    @Test
    void aCreditDepositsAndAFailureIsReportedAsUnsuccessful() {
        response.set(new EconomyResponse(0, 12.5, EconomyResponse.ResponseType.FAILURE, "refused"));
        LegResponse answer = VaultMoneySource.pinFor(economy()).leg(ACCOUNT, 12_345, MoneySource.Leg.CREDIT);
        assertEquals(List.of("depositPlayer 123.45"), calls);
        assertFalse(answer.success());
        assertEquals(0.0, answer.amountDollars());
    }

    @Test
    void aProviderThatAnswersNothingGivesNoResponse() {
        response.set(null);
        assertNull(VaultMoneySource.pinFor(economy()).leg(ACCOUNT, 100, MoneySource.Leg.DEBIT));
        assertEquals(List.of("withdrawPlayer 1.0"), calls);
    }

    @Test
    void theBalanceIsReadFromTheProvider() {
        assertEquals(12.5, VaultMoneySource.pinFor(economy()).balanceDollars(ACCOUNT));
    }

    @Test
    void aBarePinIsCurrentOnlyWhileItsProviderIsEnabled() {
        MoneySource.Pin pin = VaultMoneySource.pinFor(economy());
        assertTrue(pin.stillCurrent());
        assertTrue(pin.enabled());
        providerOn.set(false);
        assertFalse(pin.stillCurrent());
        assertFalse(pin.enabled());
    }

    @Test
    void aRegisteredPinStopsBeingCurrentWhenTheRegistrationChanges() {
        AtomicReference<RegisteredServiceProvider<Economy>> current = new AtomicReference<>(registration("Essentials"));
        MoneySource.Pin pin = VaultMoneySource.pinFor(current.get(), current::get);
        assertTrue(pin.stillCurrent());
        current.set(registration("Essentials"));
        assertFalse(pin.stillCurrent(), "an equal-looking new registration is not the pinned one");
    }

    @Test
    void factoriesRefuseMissingArguments() {
        assertThrows(NullPointerException.class, () -> VaultMoneySource.pinFor((Economy) null));
        assertThrows(NullPointerException.class, () -> VaultMoneySource.pinFor(null, () -> null));
        assertThrows(NullPointerException.class, () -> VaultMoneySource.pinFor(registration("x"), null));
    }
}
