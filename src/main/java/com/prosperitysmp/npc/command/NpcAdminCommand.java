package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.arm.ArmAdapter;
import com.prosperitysmp.npc.config.EconomyLimits;
import com.prosperitysmp.npc.config.KillSwitches;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.economy.BridgeApi;
import com.prosperitysmp.npc.economy.LinkedAccounts;
import com.prosperitysmp.npc.economy.ReserveAccount;
import com.prosperitysmp.npc.market.HousingPolicy;
import com.prosperitysmp.npc.market.HousingRuntime;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyService;
import com.prosperitysmp.npc.publication.ChronicleProvisioning;
import com.prosperitysmp.npc.publication.ChronicleRuntime;
import com.prosperitysmp.npc.render.RenderManager;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.util.Money;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The {@code /npcadmin} staff surface: health, reload, kill switches, Chronicle, ARM reconcile,
 * the housing roster and the coordinated daily cycle. {@link #SUBCOMMANDS} drives the usage text
 * and tab completion so they cannot drift from the handlers.
 */
public final class NpcAdminCommand implements TabExecutor {

    static final List<String> SUBCOMMANDS = List.of("health", "reload", "switch", "chronicle", "reconcile-arm",
            "roster", "roster-one", "match", "incidents", "rent", "settle", "rendertest", "appearance", "links", "itemrepair");

    private final ProsperityNpcPlugin plugin;

    public NpcAdminCommand(ProsperityNpcPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("prosperitynpc.admin")) return true;
        if (args.length == 0 || args[0].equalsIgnoreCase("health")) {
            health(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.holdPublicationForReload();
                if (plugin.propertyService() != null) plugin.propertyService().invalidateReconciliation();
                plugin.reloadConfig();
                plugin.killSwitches().reload();
                plugin.reloadEconomyLimits();
                plugin.reloadLinkedAccounts();
                plugin.reloadItemRepairs();
                plugin.reloadContracts();
                send(sender, NamedTextColor.GREEN, "ProsperityNPC config reloaded.");
            }
            case "switch" -> doSwitch(sender, args);
            case "chronicle" -> {
                if (args.length == 2 && args[1].equalsIgnoreCase("preview")) {
                    var runtime = plugin.chronicle();
                    var rows = runtime == null ? List.<ChronicleRuntime.HeldAdmission>of()
                            : runtime.heldAdmissions();
                    send(sender, NamedTextColor.GRAY, "Chronicle " + plugin.chronicleStatus() + " | current preview page " + rows.size());
                    for (var row : rows) {
                        send(sender, NamedTextColor.AQUA, row.preview().title());
                        send(sender, NamedTextColor.GRAY, row.preview().text());
                        send(sender, NamedTextColor.DARK_GRAY, "request=" + row.requestId() + " event=" + row.held().eventKey()
                                + " payload=" + row.held().payloadSha256());
                    }
                } else if (args.length == 1 || (args.length == 2 && args[1].equalsIgnoreCase("status")))
                    send(sender, NamedTextColor.GRAY, "Chronicle: " + plugin.chronicleStatus());
                else if (args.length == 2 && (args[1].equalsIgnoreCase("bootstrap") || args[1].equalsIgnoreCase("dispatch"))) {
                    if (!(sender instanceof ConsoleCommandSender)
                            && !(sender instanceof RemoteConsoleCommandSender)) {
                        send(sender, NamedTextColor.RED, "Chronicle delivery provisioning is console-only.");
                    } else if (args[1].equalsIgnoreCase("bootstrap")) {
                        var evidence = ChronicleProvisioning.bootstrapStatus();
                        if (evidence.isEmpty()) send(sender, NamedTextColor.YELLOW,
                                "Chronicle clean-start bootstrap: unavailable; delivery held. Phase="
                                + ChronicleProvisioning.bootstrapDiagnosticStatus());
                        else {
                            var proof = evidence.get();
                            send(sender, NamedTextColor.GRAY, "Chronicle bootstrap pid=" + proof.process().pid()
                                    + " start=" + proof.process().startedAt() + " java=" + proof.javaVersion());
                            send(sender, NamedTextColor.DARK_GRAY,
                                    "agent=" + proof.artifactSha256() + " startup=" + proof.startupInputsSha256());
                            send(sender, NamedTextColor.DARK_GRAY,
                                    "httpLog=" + proof.httpLogSha256() + " httpUtils=" + proof.httpUtilsSha256());
                            send(sender, NamedTextColor.DARK_GRAY,
                                    "httpDebug=" + proof.httpDebugSha256() + " sslLogger=" + proof.sslLoggerSha256());
                        }
                    } else if (plugin.chronicle() == null) {
                        send(sender, NamedTextColor.YELLOW, "Chronicle is not configured; no delivery started.");
                    } else {
                        var result = ChronicleProvisioning.tryEnable(
                                plugin.chronicle(), plugin.getDataFolder().toPath());
                        send(sender, result == ChronicleProvisioning.Result.ENABLED
                                ? NamedTextColor.GREEN : NamedTextColor.YELLOW, "Chronicle provisioning: " + result);
                    }
                } else send(sender, NamedTextColor.RED, "Usage: /npcadmin chronicle <status|preview|bootstrap|dispatch>");
            }
            case "reconcile-arm" -> doReconcileArm(sender);
            case "settle" -> doSettle(sender, args);
            case "rent" -> {
                // Due rent changes apply first when housing.rent-change-delay-days is in use; off: the batch directly.
                boolean accepted=plugin.requestStaffRent(
                        () -> sender.hasPermission("prosperitynpc.admin"), message -> send(sender, NamedTextColor.YELLOW, message));
                send(sender, accepted ? NamedTextColor.YELLOW : NamedTextColor.RED, accepted
                        ? "Reserving today's utility-backed rent. Wait for the verified result."
                        : "Rent settlement unavailable or busy; no batch was started.");
            }
            case "match" -> {
                boolean accepted = plugin.requestStaffMatch(
                        () -> sender.hasPermission("prosperitynpc.admin")
                                && (!(sender instanceof Player p) || p.isOnline()),
                        message -> send(sender, NamedTextColor.YELLOW, message));
                send(sender, accepted ? NamedTextColor.YELLOW : NamedTextColor.RED,
                        accepted ? "Planning resident matches..."
                                : "Matching unavailable or busy; system, moves and ownership coordination must be ready.");
            }
            case "incidents" -> {
                boolean accepted = plugin.housingRuntime() != null && plugin.housingRuntime().requestIncidents(
                        () -> sender.hasPermission("prosperitynpc.admin"), message -> send(sender, NamedTextColor.YELLOW, message));
                send(sender, accepted ? NamedTextColor.YELLOW : NamedTextColor.RED, accepted
                        ? "Running today's property checks..." : "Property checks unavailable or busy.");
            }
            case "roster" -> {
                if (args.length != 5 || plugin.housingRuntime() == null) {
                    send(sender, NamedTextColor.RED, "Usage: /npcadmin roster <world> <market> <class> <max-daily-rent>");
                    break;
                }
                World world = Bukkit.getWorld(args[1]);
                long budget = Money.parseDollarsToCents(args[4]);
                if (world == null || budget < 1 || budget > Integer.MAX_VALUE
                        || !plugin.getConfig().getStringList("enrollment.markets").contains(args[2])
                        || !plugin.getConfig().getStringList("enrollment.classes").contains(args[3])) {
                    send(sender, NamedTextColor.RED, "Invalid world, market, class or budget.");
                    break;
                }
                var policy = new HousingPolicy(world.getUID(), args[2], args[3],
                        HousingPolicy.Tier.STANDARD, budget, 60);
                boolean accepted = plugin.housingRuntime().registerCast(policy, plugin.residentCast(),
                        plugin.residentAppearances()::hasEveryday,
                        () -> sender.hasPermission("prosperitynpc.admin"), message -> send(sender, NamedTextColor.YELLOW, message));
                send(sender, accepted ? NamedTextColor.YELLOW : NamedTextColor.RED,
                        accepted ? "Registering finite housing roster..." : "Housing authoring unavailable or busy.");
            }
            case "roster-one" -> {
                if (args.length != 7 || plugin.housingRuntime() == null) {
                    send(sender, NamedTextColor.RED,
                            "Usage: /npcadmin roster-one <resident-uuid> <world> <market> <class> <max-daily-rent> <standard|premium>");
                    break;
                }
                try {
                    UUID resident = UUID.fromString(args[1]);
                    World world = Bukkit.getWorld(args[2]);
                    long budget = Money.parseDollarsToCents(args[5]);
                    var tier = HousingPolicy.Tier.valueOf(args[6].toUpperCase(Locale.ROOT));
                    if (world == null || budget < 1 || budget > Integer.MAX_VALUE
                            || !plugin.getConfig().getStringList("enrollment.markets").contains(args[3])
                            || !plugin.getConfig().getStringList("enrollment.classes").contains(args[4]))
                        throw new IllegalArgumentException();
                    var policy = new HousingPolicy(world.getUID(), args[3], args[4], tier,
                            budget, tier == HousingPolicy.Tier.PREMIUM ? 80 : 60);
                    boolean held = com.prosperitysmp.npc.market.HousingRuntime.heldForSkin(resident, plugin.residentCast(),
                            person -> plugin.residentAppearances().hasEveryday(person), () -> plugin.getConfig().getBoolean(
                                    HousingRuntime.REQUIRE_SKIN_FOR_ROSTER, true));
                    boolean accepted = plugin.housingRuntime().registerResident(resident, policy, held,
                            () -> sender.hasPermission("prosperitynpc.admin"), message -> send(sender, NamedTextColor.YELLOW, message));
                    send(sender, accepted ? NamedTextColor.YELLOW : NamedTextColor.RED,
                            accepted ? "Saving one resident's housing policy..." : "Housing authoring unavailable or premium policy held.");
                } catch (IllegalArgumentException invalid) {
                    send(sender, NamedTextColor.RED, "Invalid resident UUID, world, market, class, budget or tier.");
                }
            }
            case "rendertest" -> doRenderTest(sender, args);
            case "links" -> doLinks(sender, args);
            case "itemrepair" -> {
                // Read-only. Held hand-ins are resolved only with the offline ItemRepairRecoveryTool.
                if (args.length != 2 || !args[1].equalsIgnoreCase("list")) {
                    send(sender, NamedTextColor.GRAY, "Usage: /npcadmin itemrepair list");
                    return true;
                }
                var runtime = plugin.itemRepairs() == null ? plugin.itemRepairReturns() : plugin.itemRepairs();
                if (runtime == null) {
                    send(sender, NamedTextColor.GRAY, "No item hand-ins are open, held or waiting to give items back.");
                    return true;
                }
                if (!runtime.staffList(lines -> lines.forEach(line -> send(sender, NamedTextColor.GRAY, line))))
                    send(sender, NamedTextColor.RED, "Item repairs are busy. Try again.");
            }
            case "appearance" -> {
                // One plain chat line per cast member (at most 32), so Java and Bedrock read it the same.
                var appearances = plugin.residentAppearances();
                for (var resident : plugin.residentCast().profiles())
                    send(sender, appearances.hasEveryday(resident) ? NamedTextColor.GRAY : NamedTextColor.GOLD,
                            appearances.reportLine(resident));
            }

            default -> send(sender, NamedTextColor.RED,
                    "Unknown subcommand. Use /npcadmin <" + String.join("|", SUBCOMMANDS) + ">.");
        }
        return true;
    }

    /**
     * linked-accounts staff tools. show is read-only and always available. split needs linked-accounts.enabled; it
     * starts on the next economy day. There is no add in this release. SQL runs on the link worker; replies come
     * back on the main thread.
     */
    private void doLinks(CommandSender sender, String[] args) {
        var links = plugin.accountLinks();
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("show") && args.length == 3) {
            if (links == null) {
                send(sender, NamedTextColor.RED, "Linked accounts need the database.");
                return;
            }
            UUID account = account(args[2]);
            if (account == null) {
                send(sender, NamedTextColor.RED, "Unknown player: " + args[2]);
                return;
            }
            var day = plugin.economyToday();
            String asked = args[2];
            if (!plugin.submitLinkWork(() -> {
                // SQL on the link worker; names come from the offline player cache on the main thread.
                try {
                    var group = links.show(account, day);
                    runMain(() -> LinkedAccounts.showLines(group, asked, NpcAdminCommand::name)
                            .forEach(line -> send(sender, NamedTextColor.AQUA, line)));
                } catch (SQLException | RuntimeException failure) {
                    String reply = "Linked accounts unavailable: " + failure.getClass().getSimpleName();
                    runMain(() -> send(sender, NamedTextColor.RED, reply));
                }
            })) send(sender, NamedTextColor.RED, "Linked accounts are busy. Try again.");
            return;
        }
        if (sub.equals("split") && args.length >= 5) {
            if (!plugin.linkedAccountsEnabled()) {
                send(sender, NamedTextColor.RED, LinkedAccounts.STAFF_OFF);
                return;
            }
            if (links == null) {
                send(sender, NamedTextColor.RED, "Linked accounts need the database.");
                return;
            }
            UUID a = uuid(args[2]), b = uuid(args[3]);
            if (a == null || b == null) {
                send(sender, NamedTextColor.RED, "Use two account UUIDs. /npcadmin links show <player> lists them.");
                return;
            }
            String reason = String.join(" ", Arrays.copyOfRange(args, 4, args.length)).strip();
            if (reason.isEmpty() || reason.length() > 160) {
                send(sender, NamedTextColor.RED, "Give a reason of up to 160 characters.");
                return;
            }
            String actor = sender.getName().length() > 64 ? sender.getName().substring(0, 64) : sender.getName();
            // The save reads the day itself, inside its transaction: a task that stalls across the daily reset still
            // splits from the next day.
            var economyClock = plugin.economyClock();
            if (!plugin.submitLinkWork(() -> {
                String reply;
                try {
                    var split = links.staffSplit(a, b, actor, reason, economyClock);
                    reply = switch (split.outcome()) {
                        case SAVED -> LinkedAccounts.splitSaved(split.effectiveDay());
                        case ALREADY_SPLIT -> LinkedAccounts.splitAlready(split.effectiveDay());
                        case NOT_LINKED -> LinkedAccounts.SPLIT_REFUSED;
                    };
                } catch (SQLException | RuntimeException failure) {
                    reply = "Split not saved: " + failure.getClass().getSimpleName();
                }
                String done = reply;
                runMain(() -> {
                    send(sender, NamedTextColor.YELLOW, done);
                    plugin.getLogger().info(actor + " ran links split " + a + " " + b + ": " + done);
                });
            })) send(sender, NamedTextColor.RED, "Linked accounts are busy. Try again.");
            return;
        }
        send(sender, NamedTextColor.GRAY, "Usage: /npcadmin links show <player|uuid> | /npcadmin links split <uuidA> <uuidB> <reason>");
    }

    private static UUID uuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** A UUID, or a player name the server has seen before. */
    private static UUID account(String text) {
        var id = uuid(text);
        if (id != null) return id;
        var known = Bukkit.getOfflinePlayerIfCached(text);
        return known == null ? null : known.getUniqueId();
    }

    /** Main thread only (the offline player cache). Falls back to the UUID. */
    private static String name(UUID account) {
        var known = Bukkit.getOfflinePlayer(account).getName();
        return known == null || known.isBlank() ? account.toString() : known;
    }

    private void runMain(Runnable task) {
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (RuntimeException stopping) {
            /* Plugin stopping: no reply. */
        }
    }

    private void doSwitch(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, NamedTextColor.RED,
                    "Usage: /npcadmin switch <system|economy|incidents|moves|rendering> <on|off>");
            return;
        }
        KillSwitches.Switch sw = KillSwitches.Switch.fromKey(args[1]);
        if (sw == null) {
            send(sender, NamedTextColor.RED, "Unknown switch '" + args[1] + "'.");
            return;
        }
        Boolean value = parseOnOff(args[2]);
        if (value == null) {
            send(sender, NamedTextColor.RED, "Value must be on/off (or true/false).");
            return;
        }
        plugin.killSwitches().set(sw, value);
        send(sender, NamedTextColor.YELLOW, "Switch " + sw.key() + " = " + value + " (persisted).");
        plugin.getLogger().info(sender.getName() + " set " + sw.key() + " = " + value);
    }

    private void doRenderTest(CommandSender sender, String[] args) {
        RenderManager rm = plugin.renderManager();
        if (rm == null) {
            send(sender, NamedTextColor.RED, "Renderer unavailable (Citizens not installed / not started).");
            return;
        }
        if (args.length < 3) {
            send(sender, NamedTextColor.GRAY, "Usage: /npcadmin rendertest <world> <region>");
            return;
        }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            send(sender, NamedTextColor.RED, "Unknown world: " + args[1]);
            return;
        }
        send(sender, NamedTextColor.AQUA, "rendertest: " + rm.renderTest(world, args[2]));
    }

    private void doReconcileArm(CommandSender sender) {
        PropertyService svc = plugin.propertyService();
        if (svc == null) {
            send(sender, NamedTextColor.RED, "Property subsystem unavailable (no database).");
            return;
        }
        NpcPropertyCommand.requestReconcile(plugin, sender);
    }

    /** Current-day coordinated cycle. Old force/day rewinds are deliberately not supported. */
    private void doSettle(CommandSender sender, String[] args) {
        if (args.length != 1) {
            send(sender, NamedTextColor.RED, "Usage: /npcadmin settle (current day only; no force/backdating)");
            return;
        }
        boolean accepted = plugin.requestDailyCycle(() -> sender.hasPermission("prosperitynpc.admin"),
                message -> send(sender, NamedTextColor.YELLOW, message));
        send(sender, accepted ? NamedTextColor.YELLOW : NamedTextColor.RED, accepted
                ? "Daily cycle started; wait for phase outcomes." : "Daily cycle unavailable or already running.");
    }

    /** Null when nothing was skipped. */
    static String skippedSkinsLine(int skipped) {
        if (skipped <= 0) return null;
        return skipped == 1 ? "Skins: 1 entry for a resident not in the cast was ignored."
                : "Skins: " + skipped + " entries for residents not in the cast were ignored.";
    }

    private void health(CommandSender sender) {
        KillSwitches ks = plugin.killSwitches();
        EconomyLimits el = plugin.economyLimits();
        send(sender, NamedTextColor.AQUA, "== ProsperityNPC " + plugin.getPluginMeta().getVersion() + " ==");
        boolean operational = ks.isSystemEnabled();
        send(sender, operational ? NamedTextColor.GREEN : NamedTextColor.GOLD,
                "State: " + (operational ? "ACTIVE" : "STANDBY (system-enabled=false)"));
        send(sender, NamedTextColor.GRAY, "Appearance: " + plugin.appearanceStatus());
        send(sender, plugin.residentCast().source() == ResidentCast.Source.FILE_REJECTED
                ? NamedTextColor.GOLD : NamedTextColor.GRAY, plugin.residentCast().healthLine());
        String skipped = skippedSkinsLine(plugin.skippedSkinEntries());
        if (skipped != null) send(sender, NamedTextColor.GOLD, skipped);
        send(sender, NamedTextColor.GRAY, "Town: " + plugin.townStatus());
        send(sender, NamedTextColor.GRAY, "Training: " + plugin.trainingStatus());
        send(sender, NamedTextColor.GRAY, "Commerce: " + plugin.commerceStatus());
        send(sender, NamedTextColor.GRAY, "Private publication staging: " + plugin.publicationStatus());

        Database db = plugin.database();
        Database.Status dbs = db != null ? db.status() : Database.Status.down();
        if (!dbs.connected()) {
            send(sender, NamedTextColor.RED, "Database: DISCONNECTED (economy fail-closed)");
        } else if (!dbs.healthy()) {
            send(sender, NamedTextColor.RED, "Database: connected but UNHEALTHY");
        } else {
            send(sender, NamedTextColor.GREEN, "Database: healthy, schema V" + dbs.schemaVersion()
                    + ", " + dbs.tableCount() + " tables");
        }

        PropertyService ps = plugin.propertyService();
        if (ps == null) {
            send(sender, NamedTextColor.GRAY, "Properties: subsystem off");
        } else {
            ArmAdapter arm = plugin.armAdapter();
            long last = ps.lastReconcileMillis();
            String age = last == 0L ? "never" : ((System.currentTimeMillis() - last) / 1000) + "s ago";
            int missing = ps.missingCount();
            int waiting = ps.waitingOnBusyCount();
            send(sender, missing > 0 ? NamedTextColor.GOLD : NamedTextColor.GRAY,
                    "Properties: " + ps.enrolledCount() + " enrolled; ARM "
                    + (arm != null && arm.isAvailable() ? "available" : "UNAVAILABLE")
                    + "; last reconcile " + age
                    + (missing > 0 ? "; " + missing + " region(s) MISSING (map change?)" : "")
                    + PropertyService.waitingOnBusy(waiting));
        }

        BridgeApi bridge = plugin.bridgeApi();
        boolean bridgeReady = bridge != null && bridge.isBridgeConnected();
        send(sender, bridgeReady ? NamedTextColor.GREEN : NamedTextColor.GOLD,
                "Skript bridge: " + (bridge == null ? "not initialized"
                        : bridgeReady ? "heartbeat present" : "no heartbeat")
                        + " (it publishes accounting totals; it never moves money)");
        String reserve = bridge == null ? "" : bridge.centralBankUuid();
        send(sender, NamedTextColor.GOLD, "Payments: economy.payments-enabled="
                + plugin.getConfig().getBoolean("economy.payments-enabled", false)
                + "; reserve account " + (ReserveAccount.isCanonical(reserve) ? "set" : "NOT SET"));
        send(sender, NamedTextColor.GOLD,
                "Receipt runtime: " + (plugin.utilityPayments() == null ? "unavailable" : plugin.utilityPayments().status()));
        String rentHeld = plugin.utilityPayments() == null ? null : plugin.utilityPayments().rentHeldStatus();
        if (rentHeld != null) send(sender, NamedTextColor.GOLD, rentHeld);
        send(sender, NamedTextColor.GOLD, "Accounting projection: " + (plugin.accountingProjection() != null
                && plugin.accountingProjection().ready()
                ? "acknowledged" : "waiting for verified snapshot and Skript acknowledgement"));

        send(sender, NamedTextColor.GRAY, "Switches:");
        for (KillSwitches.Switch sw : KillSwitches.Switch.values()) {
            boolean on = ks.isEnabled(sw);
            send(sender, on ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY,
                    "  " + sw.key() + ": " + (on ? "on" : "off"));
        }
        send(sender, NamedTextColor.GRAY, "Caps (per economy-day):");
        send(sender, NamedTextColor.GRAY, "  server " + dollars(el.serverDailyCapCents())
                + (plugin.linkedAccountsEnabled() ? " | benefit-unit (linked accounts share one) " : " | benefit-unit ")
                + dollars(el.benefitUnitDailyCapCents())
                + " | property " + dollars(el.propertyDailyCapCents())
                + " | tenant " + dollars(el.tenantDailyCapCents()));
        send(sender, NamedTextColor.GRAY, "  appreciation " + dollars(el.appreciationDailyCapCents())
                + " | CentralBank floor " + dollars(el.centralBankFloorCents())
                + " | seed " + dollars(el.propertySeedCents()));
        send(sender, NamedTextColor.DARK_GRAY,
                "Town residents, relationships, commerce and property; independently gated modules.");
        send(sender, NamedTextColor.GRAY, "Chronicle: " + plugin.chronicleStatus());
        // Shown only while on, so health output with the flag off has no extra line.
        if (plugin.linkedAccountsEnabled()) send(sender, NamedTextColor.GRAY, "Linked accounts: " + plugin.linkedAccountsStatus());
        // Contracts and partnerships: shown only while one is on or a hand-over is held.
        for (String line : plugin.contractHealthLines()) send(sender, NamedTextColor.GRAY, line);
    }

    private static String dollars(long cents) {
        long abs = Math.abs(cents);
        String s = String.format(Locale.ROOT, "$%d.%02d", abs / 100, abs % 100);
        return cents < 0 ? "-" + s : s;
    }

    private static Boolean parseOnOff(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "enable", "enabled" -> Boolean.TRUE;
            case "off", "false", "no", "disable", "disabled" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static void send(CommandSender sender, NamedTextColor color, String msg) {
        sender.sendMessage(Component.text(msg, color));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBCOMMANDS) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("chronicle")) {
            for (String s : List.of("status", "preview", "bootstrap", "dispatch"))
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("switch")) {
            for (KillSwitches.Switch sw : KillSwitches.Switch.values()) {
                if (sw.key().startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(sw.key());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("itemrepair")) {
            if ("list".startsWith(args[1].toLowerCase(Locale.ROOT))) out.add("list");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("links")) {
            for (String s : List.of("show", "split")) if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("links") && args[1].equalsIgnoreCase("show")) {
            for (var online : Bukkit.getOnlinePlayers())
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(online.getName());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("switch")) {
            out.add("on");
            out.add("off");
        }
        return out;
    }
}
