package com.prosperitysmp.npc.economy.vault;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.LegResponse;
import com.prosperitysmp.npc.api.MoneySource;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import com.prosperitysmp.npc.economy.BuiltInMoneySource;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The built-in money source: whatever economy plugin is registered with Vault. The only main-source package that
 * may name Vault types. Load it only through MoneySources, which checks that Vault is installed first, so the
 * plugin still loads without Vault.
 */
public final class VaultMoneySource implements BuiltInMoneySource {
    public static final String ID = "vault";

    private static final Capabilities CAPABILITIES = Capabilities.builder()
            .value("scale", "2")
            .flag("balance-double-exact", true)
            .flag("artifact-hashable", true)
            .flag("non-player-accounts", true)
            .flag("offline-accounts", true)
            .flag("main-thread-only", true)
            .flag("reentrant-callbacks", true)
            .value("permits-overdraft", "provider-dependent")
            .build();

    private final Supplier<RegisteredServiceProvider<Economy>> registration;

    /** Reflective entry point used by MoneySources. */
    public VaultMoneySource(Server server) {
        this(() -> server.getServicesManager().getRegistration(Economy.class));
    }

    VaultMoneySource(Supplier<RegisteredServiceProvider<Economy>> registration) {
        this.registration = Objects.requireNonNull(registration);
    }

    @Override public String id() { return ID; }
    @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
    @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
    @Override public Capabilities capabilities() { return CAPABILITIES; }

    @Override
    public Optional<Pin> pin() {
        RegisteredServiceProvider<Economy> pinned = registration.get();
        return pinned == null ? Optional.empty() : Optional.of(new VaultPin(pinned, pinned.getProvider(), registration));
    }

    /** Startup check that used to live in VaultAccess: is any Vault economy registered right now. */
    @Override
    public boolean probe() {
        RegisteredServiceProvider<Economy> rsp = registration.get();
        return (rsp != null ? rsp.getProvider() : null) != null;
    }

    /** For /npcadmin diagnose: the economy plugin behind Vault, by name. Never a balance. */
    @Override
    public String providerName() {
        RegisteredServiceProvider<Economy> rsp = registration.get();
        if (rsp == null || rsp.getPlugin() == null) return "none";
        return rsp.getPlugin().getName() + " via Vault";
    }

    @Override
    public String reason() {
        return registration.get() == null ? "no economy plugin is registered with Vault" : "";
    }

    /** Test factory: a pin over one provider that stays current while the provider is enabled. */
    public static Pin pinFor(Economy economy) {
        return new VaultPin(null, Objects.requireNonNull(economy), null);
    }

    /** Test factory: a pin over {@code pinned}, current while {@code registered} still returns it and both are enabled. */
    public static Pin pinFor(RegisteredServiceProvider<Economy> pinned, Supplier<RegisteredServiceProvider<Economy>> registered) {
        return new VaultPin(Objects.requireNonNull(pinned), pinned.getProvider(), Objects.requireNonNull(registered));
    }

    private static final class VaultPin implements Pin {
        private final RegisteredServiceProvider<Economy> pinned;
        private final Economy provider;
        private final Supplier<RegisteredServiceProvider<Economy>> registration;

        VaultPin(RegisteredServiceProvider<Economy> pinned, Economy provider,
                 Supplier<RegisteredServiceProvider<Economy>> registration) {
            this.pinned = pinned;
            this.provider = Objects.requireNonNull(provider);
            this.registration = registration;
        }

        @Override
        public boolean stillCurrent() {
            // The same three checks, in the same order, the payment runtimes made against Vault directly.
            if (pinned == null) return provider.isEnabled();
            return registration.get() == pinned && pinned.getPlugin().isEnabled() && provider.isEnabled();
        }

        @Override public boolean enabled() { return provider.isEnabled(); }

        @Override
        public Path artifact() {
            // The provider's own class, never Economy.class: that would hash the Vault jar.
            try {
                return Path.of(provider.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            } catch (URISyntaxException invalid) {
                throw new IllegalStateException("Provider jar unavailable", invalid);
            }
        }

        @Override public double balanceDollars(OfflinePlayer account) { return provider.getBalance(account); }

        @Override
        public LegResponse leg(OfflinePlayer account, long cents, Leg leg) {
            double amount = cents / 100.0;
            EconomyResponse response = leg == Leg.DEBIT
                    ? provider.withdrawPlayer(account, amount) : provider.depositPlayer(account, amount);
            return response == null ? null : LegResponse.of(response.transactionSuccess(), response.amount);
        }
    }
}
