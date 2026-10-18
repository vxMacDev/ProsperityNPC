package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.util.Money;
import com.prosperitysmp.npc.property.Property;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Player-facing {@code /tenants list|evict}. Operates on the property you stand in. */
public final class TenantsCommand implements TabExecutor {

    private final ProsperityNpcPlugin plugin;

    public TenantsCommand(ProsperityNpcPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, NamedTextColor.RED, "Only players can use /tenants.");
            return true;
        }
        if (!player.hasPermission("prosperitynpc.use")) {
            send(player, NamedTextColor.RED, "You don't have permission to use this command.");
            return true;
        }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            if (args.length > 2 || (args.length == 2 && !args[1].equalsIgnoreCase("text"))) {
                send(player, NamedTextColor.RED, "Usage: /tenants [list [text]]");
            } else if (plugin.ownerDesk() == null) {
                send(player, NamedTextColor.GRAY, "The owner desk is unavailable.");
            } else plugin.ownerDesk().open(player, args.length == 2, true);
            return true;
        }
        if (!sub.equals("evict")) {
            send(player, NamedTextColor.RED, "Usage: /tenants <list [text]|evict <resident-uuid> <maximum-dollars>>");
            return true;
        }
        doEvict(player, args);
        return true;
    }

    private void doEvict(Player player, String[] args) {
        if (args.length != 3) {
            send(player, NamedTextColor.GOLD, "Usage: /tenants evict <resident-uuid> <maximum-dollars> (cost $10.00)");
            send(player, NamedTextColor.GRAY, "Use /tenants list text for each resident's exact command.");
            return;
        }
        UUID tenant;
        long maximum;
        try {
            tenant = UUID.fromString(args[1]);
            if (!tenant.toString().equalsIgnoreCase(args[1])) throw new IllegalArgumentException();
            maximum = Money.parseDollarsToCents(args[2]);
            if (maximum <= 0) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | ArithmeticException e) {
            send(player, NamedTextColor.RED, "Enter the resident's full UUID and a positive maximum payment.");
            return;
        }
        if (plugin.utilityPayments() == null || plugin.propertyBackend() == null || plugin.propertyService() == null) {
            send(player, NamedTextColor.RED, "Property payments are unavailable.");
            return;
        }
        var authority = plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId()).orElse(null);
        var property = authority == null ? Optional.<Property>empty()
                : plugin.propertyService().findByWorldRegion(authority.worldId(), authority.regionId());
        if (property.isEmpty() || !property.get().enabled() || !player.getUniqueId().equals(property.get().ownerUuid())) {
            send(player, NamedTextColor.RED, "Stand in your enrolled property.");
            return;
        }
        boolean accepted = plugin.utilityPayments().requestEviction(property.get().propertyId(), player.getUniqueId(), tenant,
                maximum, () -> player.isOnline() && !player.isDead()
                        && plugin.getServer().getPlayer(player.getUniqueId()) == player
                        && player.hasPermission("prosperitynpc.use")
                        && plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId())
                            .filter(authority::sameIncarnation).isPresent(),
                message -> {
                    if (player.isOnline()) send(player, NamedTextColor.GRAY, message);
                });
        send(player, accepted ? NamedTextColor.GRAY : NamedTextColor.RED, accepted
                ? "Checking the selected resident and payment limit. Wait for the verified result."
                : "Property payments are busy or unavailable. No payment was started.");
    }

    private static void send(CommandSender s, NamedTextColor c, String m) {
        s.sendMessage(Component.text(m, c));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player) || !sender.hasPermission("prosperitynpc.use")) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("list", "evict")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("list") && "text".startsWith(args[1].toLowerCase(Locale.ROOT))) out.add("text");
        return out;
    }
}
