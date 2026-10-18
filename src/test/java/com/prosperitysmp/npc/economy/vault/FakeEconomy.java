package com.prosperitysmp.npc.economy.vault;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.function.BiFunction;

/**
 * A real class (not a java.lang.reflect.Proxy) implementing Vault's Economy, so its code source is this test
 * build's classes and MS-06 can tell the provider's artifact from the Vault API jar. Every call goes to the handler.
 */
final class FakeEconomy implements Economy {
    private final BiFunction<String, Object[], Object> handler;

    FakeEconomy(BiFunction<String, Object[], Object> handler) {
        this.handler = handler;
    }

    private Object call(String name, Object... args) {
        return handler.apply(name, args);
    }

    @Override public boolean isEnabled() { return (Boolean) call("isEnabled"); }
    @Override public String getName() { return (String) call("getName"); }
    @Override public boolean hasBankSupport() { return (Boolean) call("hasBankSupport"); }
    @Override public int fractionalDigits() { return (Integer) call("fractionalDigits"); }
    @Override public String format(double a0) { return (String) call("format", a0); }
    @Override public String currencyNamePlural() { return (String) call("currencyNamePlural"); }
    @Override public String currencyNameSingular() { return (String) call("currencyNameSingular"); }
    @Override public boolean hasAccount(String a0) { return (Boolean) call("hasAccount", a0); }
    @Override public boolean hasAccount(OfflinePlayer a0) { return (Boolean) call("hasAccount", a0); }
    @Override public boolean hasAccount(String a0, String a1) { return (Boolean) call("hasAccount", a0, a1); }
    @Override public boolean hasAccount(OfflinePlayer a0, String a1) { return (Boolean) call("hasAccount", a0, a1); }
    @Override public double getBalance(String a0) { return (Double) call("getBalance", a0); }
    @Override public double getBalance(OfflinePlayer a0) { return (Double) call("getBalance", a0); }
    @Override public double getBalance(String a0, String a1) { return (Double) call("getBalance", a0, a1); }
    @Override public double getBalance(OfflinePlayer a0, String a1) { return (Double) call("getBalance", a0, a1); }
    @Override public boolean has(String a0, double a1) { return (Boolean) call("has", a0, a1); }
    @Override public boolean has(OfflinePlayer a0, double a1) { return (Boolean) call("has", a0, a1); }
    @Override public boolean has(String a0, String a1, double a2) { return (Boolean) call("has", a0, a1, a2); }
    @Override public boolean has(OfflinePlayer a0, String a1, double a2) { return (Boolean) call("has", a0, a1, a2); }
    @Override public EconomyResponse withdrawPlayer(String a0, double a1) { return (EconomyResponse) call("withdrawPlayer", a0, a1); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer a0, double a1) { return (EconomyResponse) call("withdrawPlayer", a0, a1); }
    @Override public EconomyResponse withdrawPlayer(String a0, String a1, double a2) { return (EconomyResponse) call("withdrawPlayer", a0, a1, a2); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer a0, String a1, double a2) { return (EconomyResponse) call("withdrawPlayer", a0, a1, a2); }
    @Override public EconomyResponse depositPlayer(String a0, double a1) { return (EconomyResponse) call("depositPlayer", a0, a1); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer a0, double a1) { return (EconomyResponse) call("depositPlayer", a0, a1); }
    @Override public EconomyResponse depositPlayer(String a0, String a1, double a2) { return (EconomyResponse) call("depositPlayer", a0, a1, a2); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer a0, String a1, double a2) { return (EconomyResponse) call("depositPlayer", a0, a1, a2); }
    @Override public EconomyResponse createBank(String a0, String a1) { return (EconomyResponse) call("createBank", a0, a1); }
    @Override public EconomyResponse createBank(String a0, OfflinePlayer a1) { return (EconomyResponse) call("createBank", a0, a1); }
    @Override public EconomyResponse deleteBank(String a0) { return (EconomyResponse) call("deleteBank", a0); }
    @Override public EconomyResponse bankBalance(String a0) { return (EconomyResponse) call("bankBalance", a0); }
    @Override public EconomyResponse bankHas(String a0, double a1) { return (EconomyResponse) call("bankHas", a0, a1); }
    @Override public EconomyResponse bankWithdraw(String a0, double a1) { return (EconomyResponse) call("bankWithdraw", a0, a1); }
    @Override public EconomyResponse bankDeposit(String a0, double a1) { return (EconomyResponse) call("bankDeposit", a0, a1); }
    @Override public EconomyResponse isBankOwner(String a0, String a1) { return (EconomyResponse) call("isBankOwner", a0, a1); }
    @Override public EconomyResponse isBankOwner(String a0, OfflinePlayer a1) { return (EconomyResponse) call("isBankOwner", a0, a1); }
    @Override public EconomyResponse isBankMember(String a0, String a1) { return (EconomyResponse) call("isBankMember", a0, a1); }
    @Override public EconomyResponse isBankMember(String a0, OfflinePlayer a1) { return (EconomyResponse) call("isBankMember", a0, a1); }
    @Override public List<String> getBanks() { @SuppressWarnings("unchecked") List<String> out = (List<String>) call("getBanks"); return out; }
    @Override public boolean createPlayerAccount(String a0) { return (Boolean) call("createPlayerAccount", a0); }
    @Override public boolean createPlayerAccount(OfflinePlayer a0) { return (Boolean) call("createPlayerAccount", a0); }
    @Override public boolean createPlayerAccount(String a0, String a1) { return (Boolean) call("createPlayerAccount", a0, a1); }
    @Override public boolean createPlayerAccount(OfflinePlayer a0, String a1) { return (Boolean) call("createPlayerAccount", a0, a1); }
}
