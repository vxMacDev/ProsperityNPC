package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.economy.AccountLinkRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.link.PlayerLink;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import org.geysermc.floodgate.util.LinkedPlayer;

/**
 * linked-accounts discovery at join. Main thread only. Reads the link Floodgate already resolved at login
 * (no network call), checks it, and hands a save to the host's database worker. Registered only when
 * linked-accounts.enabled is on and Floodgate is installed; the live flag is checked again on every join,
 * so a reload that turns it off stops discovery at once.
 */
public final class FloodgateLinkListener implements Listener {
    /** What the plugin provides. Every call is on the main thread; results come back on the main thread. */
    public interface Host {
        boolean enabled();
        LocalDate today();
        /** Saves the pair on a database worker, then calls done on main with the outcome. Nothing on failure. */
        void observe(UUID java, UUID bedrock, Consumer<AccountLinkRepository.Observed> done);
        /** Reads on a database worker whether account is in a saved group today, then calls done on main. */
        void inSavedGroup(UUID account, Consumer<Boolean> done);
        void warn(String message);
        /** Sends the line to the player if they are still online. */
        void tell(UUID player, String message);
        String linkedNotice();
        String unlinkedNotice();
    }

    private final FloodgateApi api;
    private final Host host;
    // Main thread only. The economy day a UUID was last warned about or told the unlinked notice.
    private final Map<UUID, LocalDate> warned = new HashMap<>(), unlinkedChecked = new HashMap<>();

    public FloodgateLinkListener(FloodgateApi api, Host host) {
        this.api = Objects.requireNonNull(api);
        this.host = Objects.requireNonNull(host);
    }

    /** The plugin's entry point, so the plugin class itself never names a Floodgate type. */
    public static FloodgateLinkListener create(Host host) { return new FloodgateLinkListener(FloodgateApi.getInstance(), host); }

    /** Health text: which kind of linking Floodgate uses. Global linking makes lookups call api.geysermc.org. */
    public static String linkingMode() { return linkingMode(FloodgateApi.getInstance()); }

    static String linkingMode(FloodgateApi api) {
        PlayerLink link = api.getPlayerLink();
        if (link == null || !link.isEnabled()) return "Floodgate linking off";
        if (link.getClass().getSimpleName().contains("Global"))
            return "Floodgate global linking (lookups call api.geysermc.org)";
        return "Floodgate local linking (lookups read Floodgate's own database)";
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) { joined(event.getPlayer().getUniqueId()); }

    /** The join handler without Bukkit, for tests. */
    void joined(UUID player) {
        if (!host.enabled()) return;
        FloodgatePlayer fp = api.getPlayer(player);
        if (fp == null) return;
        LocalDate today = host.today();
        if (!fp.isLinked()) {
            // An unlinked Bedrock account that still shares a limit gets one notice a day.
            if (today.equals(unlinkedChecked.get(player))) return;
            prune(unlinkedChecked, today);
            unlinkedChecked.put(player, today);
            host.inSavedGroup(player, grouped -> { if (Boolean.TRUE.equals(grouped)) host.tell(player, host.unlinkedNotice()); });
            return;
        }
        LinkedPlayer lp = fp.getLinkedPlayer();
        UUID java = lp == null ? null : lp.getJavaUniqueId(), bedrock = lp == null ? null : lp.getBedrockId();
        if (java == null || bedrock == null || !java.equals(player) || !player.equals(fp.getCorrectUniqueId())
                || !api.isFloodgateId(bedrock) || api.isFloodgateId(java)) {
            if (!today.equals(warned.get(player))) {
                prune(warned, today);
                warned.put(player, today);
                host.warn("Linked accounts: Floodgate link for " + player + " did not check out; nothing was saved.");
            }
            return;
        }
        host.observe(java, bedrock, outcome -> {
            if (outcome == AccountLinkRepository.Observed.NEW || outcome == AccountLinkRepository.Observed.REJOINED)
                host.tell(player, host.linkedNotice());
        });
    }

    private static void prune(Map<UUID, LocalDate> days, LocalDate today) {
        if (days.size() > 4096) days.values().removeIf(day -> !day.equals(today));
    }
}
