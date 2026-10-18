package com.prosperitysmp.npc.adapter.realty;

import com.prosperitysmp.npc.api.PropertyBackend;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;

/**
 * Registers the Realty property backend. ProsperityNPC binds it when the server finishes starting, when
 * {@code property.backend} is {@code realty}. Unverified.
 */
public final class RealtyAdapterPlugin extends JavaPlugin implements Listener {
    private static final Set<String> NEEDED = Set.of("Realty", "WorldGuard", "WorldEdit");

    private RealtyPropertyBackend backend;

    @Override
    public void onEnable() {
        backend = new RealtyPropertyBackend(new RealRealtyView(this), getLogger()::info);
        getServer().getServicesManager().register(PropertyBackend.class, backend, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(this, this);
        // The first load has to finish before the server does, so the core finds the backend ready when it binds.
        backend.start();
        getServer().getScheduler().runTaskTimer(this, backend::tick, 20L, 20L);
        getLogger().info("Registered property backend 'realty' (" + RealtyPropertyBackend.VERIFICATION + ").");
    }

    @Override
    public void onDisable() {
        if (backend != null) backend.stop();
    }

    /** Realty, WorldGuard or WorldEdit went away: nothing in the snapshot can be trusted any more. */
    @EventHandler
    public void pluginDisabled(PluginDisableEvent event) {
        if (backend != null && NEEDED.contains(event.getPlugin().getName())) backend.reset();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("held")) {
            List<String> held = backend.held();
            say(sender, held.isEmpty() ? "No Realty plots are held." : "Held Realty plots:");
            held.forEach(line -> say(sender, line));
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("clear")) {
            say(sender, backend.release(args[1], args[2]) ? "Released " + args[2] + "." : "That plot is not held.");
            return true;
        }
        return false;
    }

    private static void say(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text));
    }
}
