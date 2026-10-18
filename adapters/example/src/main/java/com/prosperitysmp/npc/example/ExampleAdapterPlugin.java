package com.prosperitysmp.npc.example;

import com.prosperitysmp.npc.api.PropertyBackend;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * DEV-only reference adapter. It registers one property backend, id {@code example-null}, and no money source:
 * it exists to prove that the API is visible across plugins and that selection, refusal and /npcadmin diagnose
 * work. Never install it on a live server.
 */
public final class ExampleAdapterPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        // Register in onEnable, before the server finishes starting: ProsperityNPC binds pieces at that moment.
        getServer().getServicesManager().register(PropertyBackend.class, new ExampleNullBackend(), this, ServicePriority.Normal);
        getLogger().info("Registered property backend 'example-null' (DEV only).");
    }
}
