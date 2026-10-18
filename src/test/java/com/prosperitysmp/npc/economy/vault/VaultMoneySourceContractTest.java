package com.prosperitysmp.npc.economy.vault;

import com.prosperitysmp.npc.api.MoneySource;
import com.prosperitysmp.npc.api.contract.MoneySourceContract;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;

import java.lang.reflect.Proxy;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** The money-source contract run against the built-in Vault source over a fake economy. No Mockito. */
public class VaultMoneySourceContractTest extends MoneySourceContract {
    private static final OfflinePlayer ACCOUNT = (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(),
            new Class<?>[]{OfflinePlayer.class}, (p, m, a) -> switch (m.getName()) {
                case "getUniqueId" -> new UUID(0, 41);
                case "hashCode" -> 41;
                case "equals" -> p == a[0];
                case "toString" -> "account";
                default -> throw new AssertionError("unexpected player call: " + m.getName());
            });

    @Override
    protected Subject subject(Scenario s) {
        return new VaultSubject(s);
    }

    private static Path codeSource(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class VaultSubject implements Subject {
        final List<String> moneyCalls = new ArrayList<>(), allCalls = new ArrayList<>();
        final AtomicBoolean pluginOn = new AtomicBoolean(true), providerOn = new AtomicBoolean(true);
        final AtomicReference<RegisteredServiceProvider<Economy>> registered = new AtomicReference<>();
        final Plugin owner = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (p, m, a) -> switch (m.getName()) {
                    case "isEnabled" -> pluginOn.get();
                    case "getName" -> "FakeEconomyPlugin";
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        final VaultMoneySource source;

        VaultSubject(Scenario s) {
            Economy economy = new FakeEconomy((name, args) -> {
                allCalls.add(name);
                switch (name) {
                    case "isEnabled": return providerOn.get();
                    case "getName": return "FakeEconomy";
                    case "getBalance": return s.balance();
                    case "withdrawPlayer":
                    case "depositPlayer": {
                        double amount = (double) args[1];
                        moneyCalls.add((name.equals("withdrawPlayer") ? "withdraw " : "deposit ") + amount);
                        if (s.legThrows() != null) throw s.legThrows();
                        if (s.legsFail())
                            return new EconomyResponse(0, s.balance(), EconomyResponse.ResponseType.FAILURE, "refused");
                        return new EconomyResponse(amount, s.balance(), EconomyResponse.ResponseType.SUCCESS, null);
                    }
                    default: throw new AssertionError("unexpected economy call: " + name);
                }
            });
            registered.set(new RegisteredServiceProvider<>(Economy.class, economy, ServicePriority.Normal, owner));
            source = new VaultMoneySource(registered::get);
        }

        @Override public MoneySource source() { return source; }
        @Override public OfflinePlayer account() { return ACCOUNT; }
        @Override public List<String> moneyCalls() { return moneyCalls; }
        @Override public List<String> allCalls() { return allCalls; }

        @Override
        public void replaceRegistration() {
            Economy other = new FakeEconomy((name, args) -> name.equals("isEnabled") ? Boolean.TRUE : null);
            registered.set(new RegisteredServiceProvider<>(Economy.class, other, ServicePriority.Normal, owner));
        }

        @Override public void disableOwnerPlugin() { pluginOn.set(false); }
        @Override public void disableProvider() { providerOn.set(false); }
        @Override public Path providerArtifact() { return codeSource(FakeEconomy.class); }
        @Override public Path apiArtifact() { return codeSource(Economy.class); }
    }
}
