package com.prosperitysmp.npc.adapter.griefprevention;

import com.prosperitysmp.npc.api.PropertyBackend;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Registers the GriefPrevention property backend. ProsperityNPC binds it when the server finishes starting, when
 * {@code property.backend} is {@code griefprevention}. Unverified.
 */
public final class GriefPreventionAdapterPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        var backend = new GriefPreventionBackend(new RealGriefPreventionView(getServer().getPluginManager()), getLogger()::info);
        getServer().getServicesManager().register(PropertyBackend.class, backend, this, ServicePriority.Normal);
        getLogger().info("Registered property backend 'griefprevention' (" + GriefPreventionBackend.VERIFICATION + ").");
    }
}
