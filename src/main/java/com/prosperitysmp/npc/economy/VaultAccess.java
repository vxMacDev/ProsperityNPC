package com.prosperitysmp.npc.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * READ-ONLY Vault access, used only to check whether a player can afford a
 * purchase before an event is enqueued. This class never deposits or withdraws;
 * journaled transfers move money through VaultLegObservation.
 */
public final class VaultAccess {

    private Economy economy;

    public boolean init() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        this.economy = rsp != null ? rsp.getProvider() : null;
        return economy != null;
    }

    public boolean isAvailable() {
        return economy != null;
    }
}
