package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.RegionView;
import com.prosperitysmp.npc.arm.ArmRegionView;
import com.prosperitysmp.npc.property.OwnershipReconcilePump;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyService;
import com.prosperitysmp.npc.util.Money;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** {@code /npcproperty enroll|list|info|reconcile}. Enrollment is read-only against ARM. */
public final class NpcPropertyCommand implements TabExecutor {

    private final ProsperityNpcPlugin plugin;

    public NpcPropertyCommand(ProsperityNpcPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("prosperitynpc.admin")) return true;
        if (plugin.propertyService() == null) {
            send(sender, NamedTextColor.RED, "Property subsystem unavailable (database not connected).");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "enroll" -> doEnroll(sender, args);
            case "unenroll", "pause" -> doUnenroll(sender, args);
            case "resume" -> doResume(sender, args);
            case "list" -> doList(sender);
            case "info" -> doInfo(sender, args);
            case "reconcile" -> doReconcile(sender);
            case "armdump" -> {
                // Another backend prints its own staff lines; ARM keeps its dump.
                var backend = plugin.propertyBackend();
                if (backend != null && !com.prosperitysmp.npc.arm.ArmAdapter.ID.equals(plugin.propertyBackendId()))
                    for (String line : backend.describe()) send(sender, NamedTextColor.YELLOW, line);
                else send(sender, NamedTextColor.YELLOW,
                        plugin.armAdapter() == null ? "no arm adapter" : plugin.armAdapter().debugDump());
            }
            default -> send(sender, NamedTextColor.RED,
                    "Usage: /npcproperty <enroll|pause|resume|unenroll|list|info|reconcile|armdump>");
        }
        return true;
    }

    private void doEnroll(CommandSender sender, String[] args) {
        if (args.length != 7) {
            send(sender, NamedTextColor.RED,
                    "Usage: /npcproperty enroll <world> <region> <market> <class> <capacity> <daily-rent>");
            return;
        }
        String worldName = args[1];
        String regionId = args[2];
        String market = args[3].toLowerCase(Locale.ROOT);
        String clazz = args[4].toLowerCase(Locale.ROOT);

        PropertyBackend arm = plugin.propertyBackend();
        if (arm == null || !arm.available()) {
            send(sender, NamedTextColor.RED, "AdvancedRegionMarket is not available.");
            return;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            send(sender, NamedTextColor.RED, "Unknown or unloaded world: " + worldName);
            return;
        }
        if (!plugin.getConfig().getStringList("enrollment.markets").contains(market)) {
            send(sender, NamedTextColor.RED, "market '" + market + "' is not in the allowlist.");
            return;
        }
        if (!plugin.getConfig().getStringList("enrollment.classes").contains(clazz)) {
            send(sender, NamedTextColor.RED, "class '" + clazz + "' is not in the allowlist.");
            return;
        }
        int capacity;
        try {
            capacity = Integer.parseInt(args[5]);
        } catch (NumberFormatException e) {
            send(sender, NamedTextColor.RED, "capacity must be a whole number.");
            return;
        }
        if (capacity < 1 || capacity > 4) {
            send(sender, NamedTextColor.RED, "capacity must be between 1 and 4.");
            return;
        }
        long rentCents = Money.parseDollarsToCents(args[6]);
        if (rentCents <= 0 || rentCents > Integer.MAX_VALUE) {
            send(sender, NamedTextColor.RED, "daily-rent must be a positive dollar amount (e.g. 3 or 2.50).");
            return;
        }
        Optional<RegionView> viewOpt = arm.findRegion(worldName, regionId);
        if (viewOpt.isEmpty()) {
            send(sender, NamedTextColor.RED, "No " + arm.displayName() + " region '" + regionId + "' in world '" + worldName + "'.");
            return;
        }
        RegionView view = viewOpt.get();
        if (!ArmRegionView.sameRegion(view, world.getUID(), regionId)) {
            send(sender, NamedTextColor.RED, arm.displayName() + " returned a different region or world; enrollment refused.");
            return;
        }
        regionId = view.regionId();
        // ARM enrolls SELL regions only; another backend reports kind "owned" for a home with one paying owner.
        boolean otherBackend = !com.prosperitysmp.npc.arm.ArmAdapter.ID.equals(plugin.propertyBackendId());
        if (!"sell".equals(view.kind()) && !(otherBackend && "owned".equals(view.kind()))) {
            send(sender, NamedTextColor.RED, "Region '" + regionId + "' is a " + view.market()
                    + (otherBackend ? " region; only " + arm.displayName() + " homes with one paying owner can be enrolled."
                    : " region; only SELL regions can be enrolled."));
            return;
        }
        try {
            if (plugin.propertyService().findByWorldRegion(world.getUID(), regionId).isPresent()) {
                send(sender, NamedTextColor.RED, "That region is already enrolled.");
                return;
            }
            Property property = new Property(
                    UUID.randomUUID(), world.getUID(), regionId, market, clazz, capacity,
                    (int) rentCents, (int) rentCents, view.ownerUuid().orElse(null), 0, 80, true);
            if (!(sender instanceof Player player)) {
                send(sender, NamedTextColor.RED, "Stand inside the owned plot to enroll it.");
                return;
            }
            Location location = player.getLocation().clone();
            UUID viewOwner = view.ownerUuid().orElse(null);
            var owner = arm.ownerReadAt(location, viewOwner).orElse(null);
            if (owner == null || !owner.regionId().equals(regionId) || !owner.worldId().equals(world.getUID())) {
                send(sender, NamedTextColor.RED,
                        "Stand inside that plot; it must have one UUID owner and no inherited ownership.");
                return;
            }
            boolean queued = plugin.propertyService().enrollCoordinated(property, owner,
                    () -> arm.ownerReadAt(location, viewOwner).orElse(null), result -> {
                        if (player.isOnline()) send(player, result.enrolled() ? NamedTextColor.GREEN : NamedTextColor.RED,
                                result.reply());
                    });
            send(sender, queued ? NamedTextColor.YELLOW : NamedTextColor.RED,
                    queued ? "Enrolling property..." : "Enrollment unavailable or busy; ownership coordination must be ready.");
        } catch (RuntimeException e) {
            send(sender, NamedTextColor.RED, "Enrollment authority unavailable; no success confirmed.");
        }
    }

    private void doResume(CommandSender sender, String[] args) {
        if (args.length != 3) {
            send(sender, NamedTextColor.RED, "Usage: /npcproperty resume <world> <region>");
            return;
        }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            send(sender, NamedTextColor.RED, "Unknown world: " + args[1]);
            return;
        }
        boolean accepted = plugin.propertyService().resumeCoordinated(world.getUID(), args[2],
                () -> sender.hasPermission("prosperitynpc.admin")
                        && (!(sender instanceof Player p) || p.isOnline()),
                message -> send(sender, NamedTextColor.YELLOW, message));
        send(sender, accepted ? NamedTextColor.YELLOW : NamedTextColor.RED,
                accepted ? "Checking paused property and ownership..." : "Resume unavailable or busy; nothing started.");
    }

    private void doUnenroll(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, NamedTextColor.RED, "Usage: /npcproperty unenroll <world> <region>");
            return;
        }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            send(sender, NamedTextColor.RED, "Unknown world: " + args[1]);
            return;
        }
        boolean accepted = plugin.propertyService().unenrollCoordinated(world.getUID(), args[2],
                () -> sender.hasPermission("prosperitynpc.admin"), message -> send(sender, NamedTextColor.YELLOW, message));
        send(sender, accepted ? NamedTextColor.YELLOW : NamedTextColor.RED, accepted
                ? "Disabling property under ownership coordination..."
                : "Property busy, unavailable or not enrolled; no disable started.");
    }

    private void doList(CommandSender sender) {
        PropertyService svc = plugin.propertyService();
        if (svc.enrolledCount() == 0) {
            send(sender, NamedTextColor.GRAY, "No properties enrolled.");
            return;
        }
        send(sender, NamedTextColor.AQUA, "Enrolled properties (" + svc.enrolledCount() + "):");
        for (Property p : svc.getEnrolled()) {
            boolean missing = svc.isRegionMissing(p.propertyId());
            send(sender, missing ? NamedTextColor.GOLD : NamedTextColor.GRAY,
                    "  " + (missing ? "[MISSING] " : "") + p.regionId() + " [" + p.marketId() + "/" + p.classKey()
                    + "] cap " + p.capacity() + ", rent " + Money.format(p.referenceRentCents())
                    + ", cond " + p.conditionScore() + ", epoch " + p.ownershipEpoch()
                    + ", owner " + (p.ownerUuid() == null ? "none" : shortId(p.ownerUuid())));
        }
    }

    private void doInfo(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, NamedTextColor.RED, "Usage: /npcproperty info <world> <region>");
            return;
        }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            send(sender, NamedTextColor.RED, "Unknown world: " + args[1]);
            return;
        }
        Optional<Property> p = plugin.propertyService().findByWorldRegion(world.getUID(), args[2]);
        if (p.isEmpty()) {
            send(sender, NamedTextColor.RED, "Not enrolled: " + args[1] + "/" + args[2]);
            return;
        }
        Property x = p.get();
        send(sender, NamedTextColor.AQUA, "Property " + x.regionId());
        send(sender, NamedTextColor.GRAY, "  market=" + x.marketId() + " class=" + x.classKey()
                + " capacity=" + x.capacity());
        send(sender, NamedTextColor.GRAY, "  reference-rent=" + Money.format(x.referenceRentCents())
                + " asking-rent=" + Money.format(x.askingRentCents()));
        send(sender, NamedTextColor.GRAY, "  condition=" + x.conditionScore()
                + " ownership-epoch=" + x.ownershipEpoch()
                + " owner=" + (x.ownerUuid() == null ? "none" : x.ownerUuid()));
    }

    private void doReconcile(CommandSender sender) {
        requestReconcile(plugin, sender);
    }

    /** The property backend's name for staff text: "ARM" for arm, so ARM's text is unchanged. */
    static String backendName(ProsperityNpcPlugin plugin) {
        var backend = plugin.propertyBackend();
        return backend == null ? "ARM" : backend.displayName();
    }

    static void requestReconcile(ProsperityNpcPlugin plugin, CommandSender sender) {
        if (!canStart(sender, Bukkit::getPlayer)) return;
        var accepted = plugin.propertyService().requestReconcile(r -> {
            if (!plugin.isEnabled()) return;
            String outcome = backendName(plugin) + " reconcile " + (r.cancelled() ? "cancelled" : "completed")
                    + ": verified " + r.checked() + ", verified owner-changes " + r.changed()
                    + ", skipped " + r.skipped() + ", missing " + r.missing()
                    + ", unverified/failed " + r.failed()
                    + (r.deferred() == 0 ? "" : ", held by a payment in progress (retried automatically) " + r.deferred())
                    + ". Not admission authorization.";
            // RCON has no persistent response channel after command dispatch returns.
            if (sender instanceof RemoteConsoleCommandSender) {
                plugin.getLogger().info(outcome);
            } else if (canReceive(sender, Bukkit::getPlayer)) {
                send(sender, NamedTextColor.YELLOW, outcome);
            }
        });
        String receipt = backendName(plugin) + " reconcile request: " + accepted + ".";
        if (sender instanceof RemoteConsoleCommandSender
                && accepted == OwnershipReconcilePump.Acceptance.ACCEPTED)
            receipt += " Completion will be recorded in the server log.";
        send(sender, NamedTextColor.YELLOW, receipt);
    }

    static boolean canStart(CommandSender sender, Function<UUID, Player> online) {
        return sender instanceof RemoteConsoleCommandSender
                ? sender.hasPermission("prosperitynpc.admin") : canReceive(sender, online);
    }

    static boolean canReceive(CommandSender sender, Function<UUID, Player> online) {
        if (!sender.hasPermission("prosperitynpc.admin")) return false;
        if (sender instanceof Player p)
            return p.isOnline() && online.apply(p.getUniqueId()) == p;
        return sender instanceof ConsoleCommandSender;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static void send(CommandSender sender, NamedTextColor color, String msg) {
        sender.sendMessage(Component.text(msg, color));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("prosperitynpc.admin")) return out;
        if (args.length == 1) {
            for (String s : List.of("enroll", "pause", "resume", "unenroll", "list", "info", "reconcile", "armdump")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("enroll")) {
            out.addAll(plugin.getConfig().getStringList("enrollment.markets"));
        } else if (args.length == 5 && args[0].equalsIgnoreCase("enroll")) {
            out.addAll(plugin.getConfig().getStringList("enrollment.classes"));
        }
        return out;
    }
}
