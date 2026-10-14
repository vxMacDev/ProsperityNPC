package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.appearance.ResidentAppearanceCatalogue;
import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentDialogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.resident.ResidentRepository;
import com.prosperitysmp.npc.resident.ResidentRoutine;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Staff-only, nonfinancial encounter harness. Not the finished player interface. */
public final class ResidentLabCommand implements TabExecutor, AutoCloseable {
    private final ProsperityNpcPlugin plugin;
    private final ResidentRepository residents;
    private final ResidentCast cast;
    private final ResidentAppearanceCatalogue appearances;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor worker;
    private record Conversation(UUID player, UUID resident, ResidentDialogue.Topic topic) { }
    private final Map<Conversation, Integer> turns = Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Conversation, Integer> eldest) {
            return size() > 256;
        }
    });
    private volatile boolean closed;

    public ResidentLabCommand(ProsperityNpcPlugin plugin, ResidentRepository residents, ResidentCast cast,
                              ResidentAppearanceCatalogue appearances) {
        this.plugin = plugin;
        this.residents = residents;
        this.cast = Objects.requireNonNull(cast);
        this.appearances = Objects.requireNonNull(appearances);
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread thread = new Thread(task, "prosperity-npc-resident-lab");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        plugin.trackDatabaseWorker(worker);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (closed || !plugin.getConfig().getBoolean("beta.resident-lab-enabled", false)
                || !sender.hasPermission("prosperitynpc.admin")) {
            sender.sendMessage(Component.text("Resident lab is unavailable.", NamedTextColor.GRAY));
            return true;
        }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        if (!List.of("seed", "list", "talk", "profile").contains(sub)) {
            usage(sender);
            return true;
        }
        ResidentProfile target = null;
        ResidentDialogue.Topic topic = ResidentDialogue.Topic.GREETING;
        if (sub.equals("talk") || sub.equals("profile")) {
            if (args.length < 2) {
                usage(sender);
                return true;
            }
            String key = args[1].toLowerCase(Locale.ROOT);
            target = cast.profiles().stream().filter(p -> p.id().toString().equals(key)
                    || p.name().split(" ", 2)[0].toLowerCase(Locale.ROOT).equals(key)).findFirst().orElse(null);
            if (target == null) {
                sender.sendMessage(Component.text("Unknown resident. Use /residentlab list.", NamedTextColor.GRAY));
                return true;
            }
            if (sub.equals("talk")) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(Component.text("Talk uses your own player identity. Console can inspect profiles.",
                            NamedTextColor.GRAY));
                    return true;
                }
                try {
                    if (args.length > 2) topic = ResidentDialogue.Topic.valueOf(args[2].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    usage(sender);
                    return true;
                }
            }
        }
        UUID playerId = sender instanceof Player p ? p.getUniqueId() : null;
        UUID worldId = sender instanceof Player p ? p.getWorld().getUID() : null;
        long worldTime = sender instanceof Player p ? p.getWorld().getTime() : 0;
        String actor = playerId == null ? "console" : playerId.toString();
        if (!pending.add(actor)) {
            sender.sendMessage(Component.text("Your previous request is still processing.", NamedTextColor.GRAY));
            return true;
        }

        final ResidentProfile requested = target;
        final ResidentDialogue.Topic selected = topic;
        final Instant instant = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        final EconomyClock clock;
        try {
            clock = new EconomyClock(Clock.systemUTC(),
                    ZoneId.of(plugin.getConfig().getString("clock.timezone", "America/Chicago")),
                    plugin.getConfig().getInt("clock.daily-reset-hour", 4));
        } catch (RuntimeException invalidConfig) {
            pending.remove(actor);
            sender.sendMessage(Component.text("Resident clock configuration needs staff attention.", NamedTextColor.RED));
            return true;
        }
        try {
            worker.execute(() -> {
                List<String> lines;
                try {
                    if (closed) return;
                    // Reauthorize queued work at dispatch. The DB worker may wait; the server thread never does.
                    var admission = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                        if (closed || !plugin.getConfig().getBoolean("beta.resident-lab-enabled", false)) return false;
                        CommandSender current = playerId == null ? Bukkit.getConsoleSender() : Bukkit.getPlayer(playerId);
                        return current != null && current.hasPermission("prosperitynpc.admin")
                                && (!(current instanceof Player p) || p.getWorld().getUID().equals(worldId));
                    });
                    try {
                        if (!admission.get(2, TimeUnit.SECONDS)) {
                            pending.remove(actor);
                            return;
                        }
                    } finally {
                        admission.cancel(false);
                    }
                    lines = switch (sub) {
                        case "seed" -> {
                            if (cast.source() != ResidentCast.Source.BUILT_IN) {
                                var tally = new NpcTownCommand.SeedTally();
                                for (var person : cast.profiles()) tally.record(person, () -> residents.register(person));
                                yield tally.lines();
                            }
                            int created = 0;
                            for (var person : cast.profiles()) {
                                if (residents.register(person)) created++;
                            }
                            yield List.of("DEV cast: " + created + " created. No tenants, properties, avatars or money changed.");
                        }
                        case "list" -> residents.list(20).stream().map(p -> p.name() + " | " + p.occupation()
                                + " | " + p.id()).toList();
                        case "profile" -> {
                            var p = residents.find(requested.id()).orElseThrow(() -> new IllegalStateException("Cast not seeded"));
                            var intent = ResidentRoutine.at(p, worldTime);
                            yield List.of(p.name() + " | NPC resident", "Household: " + p.household() + " | generation " + p.generation(),
                                    p.occupation() + " | " + p.means() + " | " + p.lifeStage(),
                                    p.temperament() + " | " + p.preference(),
                                    "Routine intent: " + intent.activity() + " | outfit intent: " + intent.outfitKey(),
                                    "Skins: " + appearances.reportLine(p),
                                    "Intent only: final art and avatar schedule integration are not accepted yet.");
                        }
                        case "talk" -> {
                            var p = residents.find(requested.id()).orElseThrow(() -> new IllegalStateException("Cast not seeded"));
                            RelationshipMemory.State before = residents.readRelationship(p.id(), playerId);
                            if (selected == ResidentDialogue.Topic.GREETING)
                                residents.introduce(p.id(), playerId, instant, clock.dayAt(instant));
                            var conversation = new Conversation(playerId, p.id(), selected);
                            int variant = turns.getOrDefault(conversation, 0);
                            var reply = ResidentDialogue.reply(p, before, selected, worldTime, variant, cast.lines(p.id()));
                            turns.put(conversation, (variant + 1) % 6);
                            yield List.of(p.name() + ": " + reply.line(),
                                    "Topics: greeting | routine | home | business. Talking never grants money or trust points.");
                        }

                        default -> throw new IllegalStateException("Validated command changed");
                    };
                } catch (Exception error) {
                    plugin.getLogger().warning("Resident lab request failed (" + sub + ", " + error.getClass().getSimpleName() + ")");
                    lines = List.of("Resident request unavailable. Staff: check the cast/database before retrying.");
                } finally {
                    // Clear after the callback, not here, so an output backlog cannot bypass per-actor backpressure.
                    if (closed) pending.remove(actor);
                }
                if (closed) {
                    pending.remove(actor);
                    return;
                }

                final List<String> output = lines;
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        try {
                            if (closed || !plugin.getConfig().getBoolean("beta.resident-lab-enabled", false)) return;
                            CommandSender recipient = playerId == null ? Bukkit.getConsoleSender() : Bukkit.getPlayer(playerId);
                            if (recipient == null || !recipient.hasPermission("prosperitynpc.admin")) return;
                            if (recipient instanceof Player p && !p.getWorld().getUID().equals(worldId)) return;
                            for (String line : output.isEmpty()
                                    ? List.of("No residents. Staff can use /residentlab seed on dev.") : output) {
                                recipient.sendMessage(Component.text(line, NamedTextColor.AQUA));
                            }
                        } finally {
                            pending.remove(actor);
                        }
                    });
                } catch (RuntimeException stopping) {
                    pending.remove(actor);
                }
            });
        } catch (RejectedExecutionException full) {
            pending.remove(actor);
            sender.sendMessage(Component.text("Resident desk is busy. Please try again shortly.", NamedTextColor.GRAY));
        }
        return true;
    }

    /** Every conversation topic, from the enum, so help cannot drift from what talk accepts. */
    static List<String> topicNames() {
        return Arrays.stream(ResidentDialogue.Topic.values()).map(t -> t.name().toLowerCase(Locale.ROOT)).toList();
    }

    static String usageLine() {
        return "/residentlab list | seed | profile <name> | talk <name> [" + String.join("|", topicNames()) + "]";
    }

    private static void usage(CommandSender sender) {
        sender.sendMessage(Component.text(usageLine(), NamedTextColor.GRAY));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("prosperitynpc.admin") || !plugin.getConfig().getBoolean("beta.resident-lab-enabled", false))
            return List.of();
        List<String> choices = args.length == 1 ? List.of("list", "seed", "profile", "talk")
                : args.length == 2 && List.of("profile", "talk").contains(args[0].toLowerCase(Locale.ROOT))
                ? cast.firstNames() : args.length == 3 && args[0].equalsIgnoreCase("talk")
                ? topicNames() : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.startsWith(prefix)).toList();
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdownNow();
        pending.clear();
        turns.clear();
    }
}
