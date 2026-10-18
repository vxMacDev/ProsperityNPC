package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.nav.Dijkstra;
import com.prosperitysmp.npc.nav.FileGraphCodec;
import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.nav.NavGraphValidator;
import com.prosperitysmp.npc.nav.NavPalette;
import com.prosperitysmp.npc.nav.NavSweep;
import com.prosperitysmp.npc.nav.StreetAudit;
import com.prosperitysmp.npc.nav.StrictYaml;
import com.prosperitysmp.npc.nav.origin.DocumentOrigin;
import com.prosperitysmp.npc.nav.waypoint.WaypointCodec;
import com.prosperitysmp.npc.nav.waypoint.WaypointDensifier;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet;
import com.prosperitysmp.npc.nav.waypoint.WaypointSetCheck;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * {@code /npcnav wp ...}, {@code /npcnav import} and {@code /npcnav export}: staff-placed waypoints and route
 * files. Edits are written by the nav worker under a row lock; a build or an import densifies or parses on the
 * worker, reads the world on the main thread through the normal sweep, then saves an ordinary DRAFT. Nothing
 * here publishes: a waypoint or file graph is audited and published exactly like a scan.
 */
final class NavWaypointCommands {

    /** What these commands borrow from {@link NpcNavCommand}: its worker queue, sweep slot and settings. */
    interface Host {
        void enqueue(CommandSender sender, Work work);

        void enqueueSweep(CommandSender sender, Work work);

        NpcNavCommand.Settings settings();

        /** Effective palette hash for every palette defined now. */
        Map<String, String> paletteHashes();
    }

    private static final String REGION = "[A-Za-z0-9_-]{1,128}";
    private static final String PALETTE = "[A-Za-z0-9_-]{1,64}";
    private static final int PAGE = 20;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);
    private static final String EMPTY = WaypointCodec.canonical(WaypointSet.EMPTY);
    // The longest name, before its extension, that an import accepts (StrictYaml.FILE_NAME).
    private static final int MAX_FILE_STEM = 64;

    private final ProsperityNpcPlugin plugin;
    private final Host host;
    // The last point each staff member added per world and region, for "wp add ... link".
    private final Map<String, String> lastAdded = new ConcurrentHashMap<>();

    /** Waypoint and import settings, read on the main thread. */
    record Config(WaypointSet.Limits limits, int maxNodes, String palette, boolean requirePalette, Path importDir,
                  long maxBytes) {
        WaypointDensifier.Limits graphLimits() {
            return new WaypointDensifier.Limits(maxNodes, Dijkstra.MAX_EDGES_EXAMINED);
        }
    }

    NavWaypointCommands(ProsperityNpcPlugin plugin, Host host) {
        this.plugin = plugin;
        this.host = host;
    }

    Config config() {
        var c = plugin.getConfig();
        String dir = c.getString("nav.import.dir", "nav-import");
        if (dir == null || !dir.matches("[A-Za-z0-9_-]{1,64}")) dir = "nav-import";
        String palette = c.getString("nav.waypoints.palette", "");
        return new Config(new WaypointSet.Limits(clamp(c.getInt("nav.waypoints.max-points", 600), 2, 5000),
                clamp(c.getInt("nav.waypoints.max-links", 1200), 1, 10000)),
                clamp(c.getInt("nav.waypoints.max-nodes", Dijkstra.MAX_VISITED), 2, Dijkstra.MAX_VISITED),
                palette == null ? "" : palette.trim(), c.getBoolean("nav.waypoints.require-palette", true),
                plugin.getDataFolder().toPath().resolve(dir),
                Math.max(1024L, Math.min(StrictYaml.DEFAULT_MAX_BYTES, c.getLong("nav.import.max-bytes", StrictYaml.DEFAULT_MAX_BYTES))));
    }

    // ---------------------------------------------------------------- dispatch (main thread)

    void wp(CommandSender sender, String[] args) {
        String op = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        if (args.length < 3 || !args[2].matches(REGION)) {
            usage(sender);
            return;
        }
        switch (op) {
            case "add" -> add(sender, args);
            case "move" -> move(sender, args);
            case "remove" -> simpleEdit(sender, args, 4, "remove");
            case "link" -> simpleEdit(sender, args, 5, "link");
            case "unlink" -> simpleEdit(sender, args, 5, "unlink");
            case "tag" -> simpleEdit(sender, args, 5, "tag");
            case "list" -> list(sender, args);
            case "check" -> check(sender, args);
            case "build" -> build(sender, args);
            case "export" -> exportSet(sender, args);
            case "import" -> importSet(sender, args);
            default -> usage(sender);
        }
    }

    private static void usage(CommandSender sender) {
        send(sender, NamedTextColor.GRAY, "Usage: /npcnav wp <op> <region> ... (a trailing world name is needed from console)");
        send(sender, NamedTextColor.GRAY, "  add <region> <name> [sidewalk|access] [link] [x y z world]");
        send(sender, NamedTextColor.GRAY, "  move <region> <name> [x y z world] | remove <region> <name>");
        send(sender, NamedTextColor.GRAY, "  link <region> <a> <b> [walk|cross|crosswalk] | unlink <region> <a> <b>");
        send(sender, NamedTextColor.GRAY, "  tag <region> <name> <tag> [remove] | list <region> [page] | check <region>");
        send(sender, NamedTextColor.GRAY, "  build <region> [palette] [load] | export <region> | import <region> <file>");
    }

    /**
     * Takes a trailing world name off {@code tokens}, or uses the player's world; null (after a reply) if neither.
     * The last token is only read as a world when {@code op} has more tokens than it needs (see {@link #worldCandidate}).
     */
    private World world(CommandSender sender, String op, List<String> tokens) {
        if (worldCandidate(op, tokens)) {
            World named = plugin.getServer().getWorld(tokens.getLast());
            if (named != null) {
                tokens.removeLast();
                return named;
            }
        }
        if (sender instanceof Player player) return player.getWorld();
        send(sender, NamedTextColor.RED, "From console, end the command with the world name.");
        return null;
    }

    /**
     * Whether the last of {@code tokens} (everything after the region) may be a world name: only when there are more
     * tokens than {@code op} needs, and the last one is not that op's own optional word. So a point, tag or file
     * that shares its name with a world is never taken for one.
     */
    static boolean worldCandidate(String op, List<String> tokens) {
        if (tokens.isEmpty()) return false;
        String last = tokens.getLast().toLowerCase(Locale.ROOT);
        int size = tokens.size();
        return switch (op) {
            // remove <name> | import <file>
            case "remove", "import" -> size > 1;
            // unlink <a> <b>
            case "unlink" -> size > 2;
            // link <a> <b> [walk|cross|crosswalk]
            case "link" -> size > 3 || (size == 3 && !List.of("walk", "cross", "crosswalk").contains(last));
            // tag <name> <tag> [remove]
            case "tag" -> size > 3 || (size == 3 && !last.equals("remove"));
            // list [page]
            case "list" -> size > 1 || !last.matches("[0-9]{1,9}");
            // build [palette] [load]
            case "build" -> !last.equals("load");
            // check, export: nothing else is taken
            default -> true;
        };
    }

    /**
     * A file name for {@code nav-import/} that an import accepts again: {@code region + suffix + ".yml"}, or, when that
     * is too long, the region cut short and marked with a hash of its full name so two long regions never share one.
     */
    static String exportName(String region, String suffix) {
        if (region.length() + suffix.length() <= MAX_FILE_STEM) return region + suffix + ".yml";
        String hash = NavGraphRepository.documentSha(region).substring(0, 8);
        int keep = Math.max(1, MAX_FILE_STEM - suffix.length() - hash.length() - 1);
        return region.substring(0, Math.min(keep, region.length())) + "_" + hash + suffix + ".yml";
    }

    private static List<String> tail(String[] args, int from) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < args.length; i++) out.add(args[i]);
        return out;
    }

    private void add(CommandSender sender, String[] args) {
        // wp add <region> <name> [sidewalk|access] [link] [x y z world]
        if (args.length < 4) {
            usage(sender);
            return;
        }
        String region = args[2];
        String name = args[3].toLowerCase(Locale.ROOT);
        List<String> rest = tail(args, 4);
        WaypointSet.PointKind kind = WaypointSet.PointKind.SIDEWALK;
        if (!rest.isEmpty() && (rest.getFirst().equalsIgnoreCase("sidewalk") || rest.getFirst().equalsIgnoreCase("access"))) {
            kind = WaypointSet.PointKind.parse(rest.removeFirst().toLowerCase(Locale.ROOT));
        }
        boolean link = !rest.isEmpty() && rest.getFirst().equalsIgnoreCase("link");
        if (link) rest.removeFirst();
        Position at = position(sender, rest);
        if (at == null) return;
        World world = at.world();
        if (sender instanceof Player && !standable(world, at.x(), at.y(), at.z())) {
            send(sender, NamedTextColor.RED, "Nobody can stand at " + at.x() + "," + at.y() + "," + at.z()
                    + ": it needs solid ground below and two clear blocks.");
            return;
        }
        String key = sender.getName() + "|" + world.getUID() + "|" + region;
        String previous = link ? lastAdded.get(key) : null;
        if (link && previous == null) {
            send(sender, NamedTextColor.GRAY, "No earlier point of yours in this region to link to; adding it unlinked.");
        }
        WaypointSet.Point point = new WaypointSet.Point(name, at.x(), at.y(), at.z(), kind, null);
        Config config = config();
        UUID worldId = world.getUID();
        submitEdit(sender, worldId, region, "add", name, set -> {
            WaypointSet next = set.addPoint(point, config.limits());
            if (previous != null && next.point(previous) != null) {
                next = next.link(previous, name, WaypointSet.LinkKind.WALK, config.limits());
            }
            return next;
        }, () -> lastAdded.put(key, name));
    }

    /** A block position captured on the main thread. */
    private record Position(World world, int x, int y, int z) { }

    /** Coordinates from {@code x y z world}, or the player's feet; null (after a reply) when neither works. */
    private Position position(CommandSender sender, List<String> rest) {
        if (rest.size() == 4) {
            World world = plugin.getServer().getWorld(rest.get(3));
            if (world == null) {
                send(sender, NamedTextColor.RED, "Unknown world: " + rest.get(3));
                return null;
            }
            try {
                return new Position(world, Integer.parseInt(rest.get(0)), Integer.parseInt(rest.get(1)),
                        Integer.parseInt(rest.get(2)));
            } catch (NumberFormatException bad) {
                send(sender, NamedTextColor.RED, "Coordinates must be whole numbers.");
                return null;
            }
        }
        if (!rest.isEmpty()) {
            usage(sender);
            return null;
        }
        if (!(sender instanceof Player player)) {
            send(sender, NamedTextColor.RED, "From console, give x y z world.");
            return null;
        }
        Location here = player.getLocation();
        return new Position(player.getWorld(), here.getBlockX(), here.getBlockY(), here.getBlockZ());
    }

    /** The same three block reads a spawning resident is held to. Only when the chunk is loaded. */
    private static boolean standable(World world, int x, int y, int z) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return true;
        Block below = world.getBlockAt(x, y - 1, z);
        return below.getType().isSolid() && clear(world.getBlockAt(x, y, z)) && clear(world.getBlockAt(x, y + 1, z));
    }

    private static boolean clear(Block block) {
        return block.isPassable() && !block.isLiquid();
    }

    private void move(CommandSender sender, String[] args) {
        if (args.length < 4) {
            usage(sender);
            return;
        }
        String region = args[2];
        String name = args[3].toLowerCase(Locale.ROOT);
        Position at = position(sender, tail(args, 4));
        if (at == null) return;
        if (sender instanceof Player && !standable(at.world(), at.x(), at.y(), at.z())) {
            send(sender, NamedTextColor.RED, "Nobody can stand at " + at.x() + "," + at.y() + "," + at.z() + ".");
            return;
        }
        submitEdit(sender, at.world().getUID(), region, "move", name, set -> set.movePoint(name, at.x(), at.y(), at.z()), null);
    }

    private void simpleEdit(CommandSender sender, String[] args, int required, String op) {
        List<String> tokens = tail(args, 3);
        World world = world(sender, op, tokens);
        if (world == null) return;
        if (tokens.size() < required - 3) {
            usage(sender);
            return;
        }
        String region = args[2];
        Config config = config();
        UnaryOperator<WaypointSet> change;
        String subject;
        switch (op) {
            case "remove" -> {
                if (tokens.size() != 1) {
                    usage(sender);
                    return;
                }
                String name = tokens.getFirst().toLowerCase(Locale.ROOT);
                subject = name;
                change = set -> set.removePoint(name);
            }
            case "link" -> {
                if (tokens.size() > 3) {
                    usage(sender);
                    return;
                }
                String a = tokens.get(0).toLowerCase(Locale.ROOT);
                String b = tokens.get(1).toLowerCase(Locale.ROOT);
                WaypointSet.LinkKind kind;
                try {
                    kind = tokens.size() == 3 ? WaypointSet.LinkKind.parse(tokens.get(2).toLowerCase(Locale.ROOT))
                            : WaypointSet.LinkKind.WALK;
                } catch (IllegalArgumentException bad) {
                    send(sender, NamedTextColor.RED, "A link is walk, cross or crosswalk.");
                    return;
                }
                subject = a + "-" + b;
                change = set -> set.link(a, b, kind, config.limits());
            }
            case "unlink" -> {
                if (tokens.size() != 2) {
                    usage(sender);
                    return;
                }
                String a = tokens.get(0).toLowerCase(Locale.ROOT);
                String b = tokens.get(1).toLowerCase(Locale.ROOT);
                subject = a + "-" + b;
                change = set -> set.unlink(a, b);
            }
            default -> {
                boolean remove = tokens.size() == 3 && tokens.get(2).equalsIgnoreCase("remove");
                if (tokens.size() > 3 || (tokens.size() == 3 && !remove)) {
                    usage(sender);
                    return;
                }
                String name = tokens.get(0).toLowerCase(Locale.ROOT);
                String tag = tokens.get(1);
                subject = name;
                change = set -> set.tag(name, tag, remove);
            }
        }
        submitEdit(sender, world.getUID(), region, op, subject, change, null);
    }

    private void submitEdit(CommandSender sender, UUID worldId, String region, String op, String subject,
                            UnaryOperator<WaypointSet> change, Runnable onSuccess) {
        NavGraphRepository repository = plugin.navGraphRepository();
        NpcNavCommand.ReportSink sink = host.settings().sink();
        String by = sender.getName();
        host.enqueue(sender, () -> editSet(repository, sink, worldId, region, op, subject, by, change, onSuccess));
    }

    // ---------------------------------------------------------------- worker side: edits

    /** Worker only: applies one edit under the set's row lock. A refused edit writes nothing. */
    static Reply editSet(NavGraphRepository repository, NpcNavCommand.ReportSink sink, UUID worldId, String region,
                         String op, String subject, String by, UnaryOperator<WaypointSet> change, Runnable onSuccess)
            throws SQLException {
        return editSet(repository, sink, worldId, region, op, subject, by, change, onSuccess, false);
    }

    /**
     * As above. With {@code replace} the current document is not read at all (an import replaces the whole set), so
     * a stored set that no longer reads can still be replaced. Every new document must read back as the same set.
     */
    static Reply editSet(NavGraphRepository repository, NpcNavCommand.ReportSink sink, UUID worldId, String region,
                         String op, String subject, String by, UnaryOperator<WaypointSet> change, Runnable onSuccess,
                         boolean replace) throws SQLException {
        NavGraphRepository.WaypointSetRow row;
        try {
            row = repository.editWaypointSet(worldId, region, by, EMPTY, current -> WaypointCodec.storable(
                    change.apply(current == null || replace ? WaypointSet.EMPTY : WaypointCodec.parse(current))));
        } catch (WaypointSet.EditRefused refused) {
            return NpcNavCommand.reply(NamedTextColor.RED, refused.code() + ": " + refused.getMessage());
        }
        if (onSuccess != null) onSuccess.run();
        sink.log("NPCNAV-WP op=" + op + " region=" + region + " point=" + subject + " revision=" + row.revision()
                + " sha=" + row.docSha().substring(0, 8) + " by=" + by);
        return NpcNavCommand.reply(NamedTextColor.GREEN, "Waypoints " + region + ": " + op + " " + subject
                + " done (revision " + row.revision() + ", set " + row.docSha().substring(0, 8) + ").");
    }

    private void list(CommandSender sender, String[] args) {
        List<String> tokens = tail(args, 3);
        World world = world(sender, "list", tokens);
        if (world == null) return;
        int page = 1;
        if (tokens.size() == 1) {
            try {
                page = Math.max(1, Integer.parseInt(tokens.getFirst()));
            } catch (NumberFormatException bad) {
                usage(sender);
                return;
            }
        } else if (!tokens.isEmpty()) {
            usage(sender);
            return;
        }
        String region = args[2];
        int wanted = page;
        UUID worldId = world.getUID();
        NavGraphRepository repository = plugin.navGraphRepository();
        host.enqueue(sender, () -> listReply(repository, worldId, region, wanted));
    }

    static Reply listReply(NavGraphRepository repository, UUID worldId, String region, int page) throws SQLException {
        NavGraphRepository.WaypointSetRow row = repository.waypointSet(worldId, region);
        if (row == null || row.revision() == 0) return NpcNavCommand.reply(NamedTextColor.GRAY, "No waypoints in " + region + " yet.");
        WaypointSet set = WaypointCodec.parse(row.document());
        List<String> entries = new ArrayList<>();
        for (WaypointSet.Point p : set.points()) {
            entries.add("point " + p.name() + " " + p.x() + "," + p.y() + "," + p.z() + " " + p.kind().word()
                    + (p.tags().isEmpty() ? "" : " " + p.tags()));
        }
        for (WaypointSet.Link l : set.links()) entries.add("link " + l.label() + " " + l.kind().word());
        int pages = Math.max(1, (entries.size() + PAGE - 1) / PAGE);
        int shown = Math.min(page, pages);
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(NamedTextColor.AQUA, "Waypoints " + region + ": " + set.points().size() + " points, "
                + set.links().size() + " links, revision " + row.revision() + ", set " + row.docSha().substring(0, 8)
                + " (page " + shown + "/" + pages + ")"));
        for (String entry : entries.subList((shown - 1) * PAGE, Math.min(entries.size(), shown * PAGE))) {
            lines.add(new Line(NamedTextColor.GRAY, NpcNavCommand.clip("  " + entry)));
        }
        return new Reply(lines, null);
    }

    private void check(CommandSender sender, String[] args) {
        List<String> tokens = tail(args, 3);
        World world = world(sender, "check", tokens);
        if (world == null) return;
        if (!tokens.isEmpty()) {
            usage(sender);
            return;
        }
        NpcNavCommand.Settings settings = host.settings();
        if (settings.rules() == null) {
            send(sender, NamedTextColor.RED, settings.rulesError());
            return;
        }
        String region = args[2];
        UUID worldId = world.getUID();
        Config config = config();
        NavGraphRepository repository = plugin.navGraphRepository();
        host.enqueue(sender, () -> checkReply(repository, worldId, region, settings, config));
    }

    static Reply checkReply(NavGraphRepository repository, UUID worldId, String region, NpcNavCommand.Settings settings,
                            Config config) throws SQLException {
        NavGraphRepository.WaypointSetRow row = repository.waypointSet(worldId, region);
        if (row == null || row.revision() == 0) return NpcNavCommand.reply(NamedTextColor.GRAY, "No waypoints in " + region + " yet.");
        WaypointSet set = WaypointCodec.parse(row.document());
        WaypointSetCheck.Report report = WaypointSetCheck.check(set, settings.rules(), settings.maxStep(), config.graphLimits());
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(report.ok() ? NamedTextColor.GREEN : NamedTextColor.RED, "Waypoint check " + region + ": "
                + (report.ok() ? "OK" : "FAIL") + " (" + report.errors() + " errors, " + report.warnings() + " warnings, "
                + report.points() + " points, " + report.links() + " links, " + report.cells() + " cells)"));
        NpcNavCommand.addDetails(lines, report.issues().stream().map(WaypointSetCheck.Issue::line).toList());
        settings.sink().log("NPCNAV-WPCHECK region=" + region + " verdict=" + (report.ok() ? "OK" : "FAIL") + " errors="
                + report.errors() + " warnings=" + report.warnings() + " points=" + report.points() + " links="
                + report.links() + " cells=" + report.cells());
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("region", region);
        json.put("verdict", report.ok() ? "OK" : "FAIL");
        json.put("set", row.docSha());
        json.put("revision", row.revision());
        json.put("points", report.points());
        json.put("links", report.links());
        json.put("cells", report.cells());
        json.put("edges", report.edges());
        json.put("issues", report.issues().stream().map(WaypointSetCheck.Issue::line).toList());
        settings.sink().write("wpcheck", region, json);
        return new Reply(lines, null);
    }

    // ---------------------------------------------------------------- build and import

    /** The palette a build or import reads the world with: null palette means classless (only when allowed). */
    record PaletteChoice(String name, NavPalette palette, String hash) { }

    /** Main thread. Null (after a reply) when the build must not run. */
    private PaletteChoice palette(CommandSender sender, String requested, Config config) {
        String name = requested != null ? requested : config.palette();
        if (name.isEmpty()) {
            if (config.requirePalette()) {
                send(sender, NamedTextColor.RED, "Name a palette (or set nav.waypoints.palette). It is the only automatic "
                        + "proof that no walk link runs along a road.");
                return null;
            }
            send(sender, NamedTextColor.YELLOW, "WARN road and sidewalk were not checked against any palette "
                    + "(nav.waypoints.require-palette is off).");
            return new PaletteChoice("", null, "");
        }
        if (!name.matches(PALETTE)) {
            send(sender, NamedTextColor.RED, "Invalid palette name.");
            return null;
        }
        try {
            NavPalette palette = NavPalette.load(plugin, name);
            if (palette.legacy()) {
                send(sender, NamedTextColor.RED, "Palette '" + name + "' has no classes; use a section-form palette.");
                return null;
            }
            return new PaletteChoice(name, palette, palette.hash());
        } catch (IllegalArgumentException refused) {
            send(sender, NamedTextColor.RED, refused.getMessage());
            return null;
        }
    }

    private void build(CommandSender sender, String[] args) {
        // wp build <region> [palette] [load] [world]
        List<String> tokens = tail(args, 3);
        World world = world(sender, "build", tokens);
        if (world == null) return;
        boolean load = tokens.removeIf(t -> t.equalsIgnoreCase("load"));
        if (tokens.size() > 1) {
            usage(sender);
            return;
        }
        NpcNavCommand.Settings settings = host.settings();
        if (settings.rules() == null) {
            send(sender, NamedTextColor.RED, settings.rulesError());
            return;
        }
        Config config = config();
        PaletteChoice choice = palette(sender, tokens.isEmpty() ? null : tokens.getFirst(), config);
        if (choice == null) return;
        String region = args[2];
        UUID worldId = world.getUID();
        NavGraphRepository repository = plugin.navGraphRepository();
        host.enqueueSweep(sender, () -> buildWaypoints(repository, worldId, region, choice, settings, config, load));
    }

    /** Worker: set rules, densify, validate and audit in memory; then hand the main thread the world read. */
    static Reply buildWaypoints(NavGraphRepository repository, UUID worldId, String region, PaletteChoice choice,
                                NpcNavCommand.Settings settings, Config config, boolean load) throws SQLException {
        NpcNavCommand.ReportSink sink = settings.sink();
        NavGraphRepository.WaypointSetRow row = repository.waypointSet(worldId, region);
        if (row == null || row.revision() == 0) return NpcNavCommand.reply(NamedTextColor.GRAY, "No waypoints in " + region + " yet.");
        WaypointSet set = WaypointCodec.parse(row.document());
        WaypointSetCheck.Report report = WaypointSetCheck.check(set, settings.rules(), settings.maxStep(), config.graphLimits());
        if (!report.ok()) {
            List<Line> lines = new ArrayList<>();
            lines.add(new Line(NamedTextColor.RED, "Waypoint build " + region + ": REFUSED (" + report.errors() + " errors). Nothing was saved."));
            List<String> issues = report.issues().stream().map(WaypointSetCheck.Issue::line).toList();
            NpcNavCommand.addDetails(lines, issues);
            buildLog(sink, "WPBUILD", "region=" + region, "REFUSED", 0, 0, 0, 0, null, row.docSha(), null);
            refusedReport(sink, "wpbuild", region, NavGraphRepository.SOURCE_WAYPOINTS, row.docSha(), choice.name(), 0, 0,
                    "set check", issues);
            return new Reply(lines, null);
        }
        WaypointDensifier.Result built;
        try {
            built = WaypointDensifier.densify(set, settings.rules(), settings.maxStep(), config.graphLimits());
        } catch (WaypointSet.EditRefused refused) {
            buildLog(sink, "WPBUILD", "region=" + region, "REFUSED", 0, 0, 0, 0, null, row.docSha(), null);
            refusedReport(sink, "wpbuild", region, NavGraphRepository.SOURCE_WAYPOINTS, row.docSha(), choice.name(), 0, 0,
                    "densify", List.of(refused.code() + " " + refused.getMessage()));
            return NpcNavCommand.reply(NamedTextColor.RED, "Waypoint build " + region + ": REFUSED (" + refused.code() + ": "
                    + refused.getMessage() + "). Nothing was saved.");
        }
        Target target = new Target("WPBUILD", "region=" + region, worldId, region, NavGraphRepository.SOURCE_WAYPOINTS,
                row.docSha(), null, null, built.provenance(), built.crosswalkLinks(), built.plainLinks(), built);
        return checkAndSweep(repository, built.graph(), target, choice, settings, load);
    }

    /**
     * Where a built graph goes and what to call it in replies and logs. {@code document} is stored on save (file only);
     * {@code built} is the densifier's result (waypoints only), whose crossing paint the world read decides.
     */
    private record Target(String logKind, String logSubject, UUID worldId, String region, String source, String docSha,
                          String document, String originRef, Map<Long, String> provenance, int crosswalks, int plain,
                          WaypointDensifier.Result built) { }

    /** Worker: steps 2 to 4 of a build, shared by waypoints and route files. */
    private static Reply checkAndSweep(NavGraphRepository repository, NavGraph graph, Target target, PaletteChoice choice,
                                       NpcNavCommand.Settings settings, boolean load) {
        Checked checked = checkInMemory(graph, target, target.crosswalks(), target.plain(), choice, settings);
        if (checked.refusal() != null) return checked.refusal();
        DocumentOrigin origin = NavGraphRepository.SOURCE_FILE.equals(target.source()) ? DocumentOrigin.FILE : DocumentOrigin.WAYPOINTS;
        String fingerprint = origin.fingerprint(target.docSha(), choice.hash(), settings.rulesText());
        // A waypoint crossing's paint is read from the world, not taken from the link kind; scans and files compare exactly.
        NavSweep sweep = target.built() != null && choice.palette() != null
                ? NavSweep.forWaypointBuild(graph, fingerprint, target.built().crossingCells())
                : NavSweep.forRevalidation(graph, fingerprint, choice.palette() != null);
        List<Line> lines = List.of(new Line(NamedTextColor.GRAY, "Checked " + graph.nodeCount() + " nodes and "
                + graph.edgeCount() + " edges (audit " + checked.audit().verdict() + "); reading " + sweep.total() + " world cells"
                + (choice.palette() == null ? " without a palette" : " with palette '" + choice.name() + "'")
                + (load ? ", loading chunks" : "") + "..."));
        return new Reply(lines, null, new NpcNavCommand.SweepJob(target.worldId(), choice.name(), null, sweep,
                load ? sweep.chunkKeys() : List.of(), load, false,
                outcome -> swept(repository, graph, target, choice, fingerprint, settings, load, outcome), choice.palette()));
    }

    /** The in-memory checks of a build: the audit when they pass, or the refusal already logged and reported. */
    private record Checked(StreetAudit.Result audit, Reply refusal) { }

    /** Worker: step 2 of a build (validator and street audit), on the graph about to be read or saved. */
    private static Checked checkInMemory(NavGraph graph, Target target, int crosswalks, int plain, PaletteChoice choice,
                                         NpcNavCommand.Settings settings) {
        NpcNavCommand.ReportSink sink = settings.sink();
        String kind = target.logKind().toLowerCase(Locale.ROOT);
        NavGraphValidator.Report validation = NavGraphValidator.validate(graph);
        if (!validation.valid()) {
            List<Line> lines = new ArrayList<>();
            lines.add(new Line(NamedTextColor.RED, target.logKind() + " " + target.region() + ": REFUSED by the graph validator."));
            lines.addAll(NpcNavCommand.validationLines(validation));
            buildLog(sink, target.logKind(), target.logSubject(), "REFUSED", graph.nodeCount(), graph.edgeCount(),
                    crosswalks, plain, null, target.docSha(), null);
            refusedReport(sink, kind, target.region(), target.source(), target.docSha(), choice.name(), graph.nodeCount(),
                    graph.edgeCount(), "validator", validation.issues().stream()
                            .filter(i -> i.severity() == NavGraphValidator.Severity.ERROR).map(i -> i.code() + " " + i.message()).toList());
            return new Checked(null, new Reply(lines, null));
        }
        StreetAudit.Result audit = StreetAudit.audit(graph, null, settings.auditOptions());
        sink.log(StreetAudit.logLine(target.region() + "-" + target.source(), audit));
        if (audit.verdict() == StreetAudit.Verdict.FAIL) {
            List<Line> lines = new ArrayList<>();
            lines.add(new Line(NamedTextColor.RED, target.logKind() + " " + target.region() + ": REFUSED, street audit FAIL ("
                    + audit.failedPaths() + " failed of " + audit.paths() + " paths). Nothing was saved."));
            List<String> detail = new ArrayList<>();
            for (StreetAudit.Violation v : audit.examples()) detail.add(v.code() + " " + v.detail());
            NpcNavCommand.addDetails(lines, detail);
            buildLog(sink, target.logKind(), target.logSubject(), "REFUSED", graph.nodeCount(), graph.edgeCount(),
                    crosswalks, plain, null, target.docSha(), null);
            refusedReport(sink, kind, target.region(), target.source(), target.docSha(), choice.name(), graph.nodeCount(),
                    graph.edgeCount(), "street audit", detail);
            return new Checked(null, new Reply(lines, null));
        }
        return new Checked(audit, null);
    }

    /** The JSON report of a build or import refused before anything was saved. Never holds a file's contents. */
    private static void refusedReport(NpcNavCommand.ReportSink sink, String kind, String region, String source, String docSha,
                                      String palette, int nodes, int edges, String stage, List<String> issues) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("region", region);
        json.put("source", source);
        json.put("document", docSha);
        json.put("palette", palette);
        json.put("nodes", nodes);
        json.put("edges", edges);
        json.put("verdict", "REFUSED");
        json.put("stage", stage);
        json.put("issues", issues);
        json.put("draft", null);
        sink.write(kind, region, json);
    }

    /** Worker: turns the world read into a verdict, and saves the draft only when the world matches. */
    private static Reply swept(NavGraphRepository repository, NavGraph graph, Target target, PaletteChoice choice,
                               String fingerprint, NpcNavCommand.Settings settings, boolean load,
                               NpcNavCommand.SweepOutcome outcome) throws SQLException {
        NpcNavCommand.ReportSink sink = settings.sink();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("region", target.region());
        json.put("source", target.source());
        json.put("document", target.docSha());
        json.put("palette", choice.name());
        json.put("nodes", graph.nodeCount());
        json.put("edges", graph.edgeCount());
        int crosswalks = target.crosswalks();
        int plain = target.plain();
        json.put("crosswalk", crosswalks);
        json.put("plain", plain);
        String verdict;
        List<Line> lines = new ArrayList<>();
        UUID draft = null;
        if (outcome.worldMissing()) {
            verdict = "WORLD_MISSING";
            lines.add(new Line(NamedTextColor.RED, "The world is not loaded: WORLD_MISSING. Nothing was saved."));
        } else if (outcome.error() != null || !outcome.sweep().done()) {
            verdict = "INCOMPLETE";
            lines.add(new Line(NamedTextColor.YELLOW, "The world read stopped" + (outcome.error() == null ? "" : ": " + outcome.error())
                    + ". Nothing was saved."));
        } else {
            NavSweep.Revalidation r = outcome.sweep().revalidation(fingerprint);
            Checked restriped = null;
            if (r.verdict() == NavSweep.Verdict.CLEAN && target.built() != null && choice.palette() != null) {
                WaypointDensifier.Result painted = WaypointDensifier.restripe(target.built(),
                        outcome.sweep().streetClasses(), settings.rules());
                graph = painted.graph();
                crosswalks = painted.crosswalkLinks();
                plain = painted.plainLinks();
                json.put("crosswalk", crosswalks);
                json.put("plain", plain);
                // The paint changes crossing costs, so the graph that will be saved is checked again.
                restriped = checkInMemory(graph, target, crosswalks, plain, choice, settings);
            }
            if (restriped != null && restriped.refusal() != null) {
                return restriped.refusal();
            } else if (r.verdict() == NavSweep.Verdict.CLEAN) {
                verdict = "BUILT";
                if (target.document() != null) {
                    repository.storeRouteDocument(NavGraphRepository.SOURCE_FILE, target.document());
                }
                draft = repository.saveDraft(target.worldId(), target.region(), choice.name(), fingerprint, graph,
                        target.source(), target.docSha(), target.originRef());
                lines.add(new Line(NamedTextColor.GREEN, "BUILT draft " + draft + " for '" + target.region() + "': "
                        + graph.nodeCount() + " nodes, " + graph.edgeCount() + " edges (crosswalk " + crosswalks
                        + ", plain " + plain + ")."));
                if (choice.palette() == null) {
                    lines.add(new Line(NamedTextColor.YELLOW, "WARN road and sidewalk were not checked against any palette."));
                }
                lines.add(new Line(NamedTextColor.GRAY, "Next: /npcnav audit " + draft + ", then /npcnav publish " + draft));
            } else {
                verdict = r.verdict() == NavSweep.Verdict.DRIFTED ? "DRIFTED" : "INCOMPLETE";
                lines.add(new Line(r.verdict() == NavSweep.Verdict.DRIFTED ? NamedTextColor.RED : NamedTextColor.YELLOW,
                        "The world does not match: " + verdict + " (changed " + r.changedClass() + ", unwalkable "
                                + r.unwalkable() + ", blocked " + r.blocked() + ", footprint " + r.footprint()
                                + ", not loaded " + r.notLoaded() + "). Nothing was saved."));
                List<String> detail = new ArrayList<>();
                for (NavSweep.Drift d : r.drifts()) {
                    String from = target.provenance() == null ? "node " + d.nodeId()
                            : target.provenance().getOrDefault(d.nodeId(), "node " + d.nodeId());
                    detail.add(d.kind() + " " + from + " at " + d.x() + "," + d.y() + "," + d.z() + " " + d.detail());
                }
                NpcNavCommand.addDetails(lines, detail);
                json.put("drifts", detail);
                if (r.notLoaded() > 0 && !load) {
                    lines.add(new Line(NamedTextColor.GRAY, "  Some chunks are not loaded; add 'load' to load them (DEV only)."));
                }
            }
        }
        if (load) {
            lines.add(new Line(NamedTextColor.GRAY, "  chunks loaded " + outcome.chunksLoaded() + ", missing " + outcome.chunksAbsent()
                    + ", over the cap " + outcome.chunksSkipped() + ", tickets_remaining=" + outcome.ticketsRemaining()));
        }
        json.put("verdict", verdict);
        json.put("draft", draft == null ? null : draft.toString());
        json.put("ticketsRemaining", outcome.ticketsRemaining());
        buildLog(sink, target.logKind(), target.logSubject(), verdict, graph.nodeCount(), graph.edgeCount(),
                crosswalks, plain, draft, target.docSha(), outcome);
        sink.write(target.logKind().toLowerCase(Locale.ROOT), target.region(), json);
        return new Reply(lines, null);
    }

    private static void buildLog(NpcNavCommand.ReportSink sink, String kind, String subject, String verdict, int nodes,
                                 int edges, int crosswalks, int plain, UUID draft, String docSha,
                                 NpcNavCommand.SweepOutcome outcome) {
        String sha = docSha == null ? "none" : docSha.substring(0, 12);
        if (kind.equals("IMPORT")) {
            sink.log("NPCNAV-IMPORT " + subject + " sha=" + sha + " verdict=" + verdict + " nodes=" + nodes + " edges=" + edges
                    + " draft=" + (draft == null ? "none" : draft));
        } else {
            sink.log("NPCNAV-WPBUILD " + subject + " verdict=" + verdict + " nodes=" + nodes + " edges=" + edges
                    + " crosswalk=" + crosswalks + " plain=" + plain + " draft=" + (draft == null ? "none" : draft) + " sha=" + sha
                    + (outcome == null ? "" : " tickets_remaining=" + outcome.ticketsRemaining()));
        }
    }

    /** {@code /npcnav import <region> <world> <file> [palette] [load]}: a dense route file into a DRAFT. */
    void importFile(CommandSender sender, String[] args) {
        if (args.length < 4 || args.length > 6 || !args[1].matches(REGION)) {
            send(sender, NamedTextColor.GRAY, "Usage: /npcnav import <region> <world> <file> [palette] [load]");
            return;
        }
        World world = plugin.getServer().getWorld(args[2]);
        if (world == null) {
            send(sender, NamedTextColor.RED, "Unknown world: " + args[2]);
            return;
        }
        String file = args[3];
        if (!StrictYaml.FILE_NAME.matcher(file).matches()) {
            send(sender, NamedTextColor.RED, "Use a bare file name in the import folder, ending .yml, .yaml or .json.");
            return;
        }
        List<String> tokens = tail(args, 4);
        boolean load = tokens.removeIf(t -> t.equalsIgnoreCase("load"));
        if (tokens.size() > 1) {
            send(sender, NamedTextColor.GRAY, "Usage: /npcnav import <region> <world> <file> [palette] [load]");
            return;
        }
        NpcNavCommand.Settings settings = host.settings();
        if (settings.rules() == null) {
            send(sender, NamedTextColor.RED, settings.rulesError());
            return;
        }
        Config config = config();
        PaletteChoice choice = palette(sender, tokens.isEmpty() ? null : tokens.getFirst(), config);
        if (choice == null) return;
        String region = args[1];
        UUID worldId = world.getUID();
        NavGraphRepository repository = plugin.navGraphRepository();
        host.enqueueSweep(sender, () -> importGraph(repository, worldId, region, file, choice, settings, config, load));
    }

    static Reply importGraph(NavGraphRepository repository, UUID worldId, String region, String file,
                             PaletteChoice choice, NpcNavCommand.Settings settings, Config config, boolean load) {
        NpcNavCommand.ReportSink sink = settings.sink();
        String text;
        NavGraph graph;
        try {
            text = StrictYaml.readImportFile(config.importDir(), file, config.maxBytes());
            graph = FileGraphCodec.read(text, settings.rules(), settings.maxStep(),
                    new FileGraphCodec.Limits(config.maxNodes(), Dijkstra.MAX_EDGES_EXAMINED));
        } catch (IllegalArgumentException refused) {
            // Only counts and the sha are ever logged, never the file's contents.
            sink.log("NPCNAV-IMPORT file=" + file + " sha=none verdict=REFUSED nodes=0 edges=0 draft=none");
            refusedReport(sink, "import", region, NavGraphRepository.SOURCE_FILE, null, choice.name(), 0, 0, "read",
                    List.of(refused.getMessage()));
            return NpcNavCommand.reply(NamedTextColor.RED, "Import refused: " + refused.getMessage());
        }
        String sha = NavGraphRepository.documentSha(text);
        Target target = new Target("IMPORT", "file=" + file, worldId, region, NavGraphRepository.SOURCE_FILE, sha, text,
                file, null, 0, 0, null);
        return checkAndSweep(repository, graph, target, choice, settings, load);
    }

    /** {@code /npcnav export <graphId>}: any graph as a dense route file in the import folder. */
    void export(CommandSender sender, String[] args) {
        if (args.length != 2) {
            send(sender, NamedTextColor.GRAY, "Usage: /npcnav export <graphId>");
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(args[1]);
        } catch (IllegalArgumentException bad) {
            send(sender, NamedTextColor.RED, "Not a valid graph id: " + args[1]);
            return;
        }
        NpcNavCommand.Settings settings = host.settings();
        if (settings.rules() == null) {
            send(sender, NamedTextColor.RED, settings.rulesError());
            return;
        }
        Config config = config();
        NavGraphRepository repository = plugin.navGraphRepository();
        host.enqueue(sender, () -> exportGraph(repository, id, settings, config));
    }

    private static Reply exportGraph(NavGraphRepository repository, UUID id, NpcNavCommand.Settings settings, Config config)
            throws SQLException {
        NavGraphRepository.GraphInfo info = repository.info(id);
        NavGraph graph = info == null ? null : repository.loadGraph(id);
        if (graph == null) return NpcNavCommand.reply(NamedTextColor.RED, "No graph " + id);
        String text;
        try {
            text = FileGraphCodec.write(graph, settings.rules());
        } catch (IllegalArgumentException refused) {
            return NpcNavCommand.reply(NamedTextColor.RED, refused.getMessage());
        }
        String name = exportName(info.regionId(), "-" + id.toString().substring(0, 8) + "-" + LocalDateTime.now().format(STAMP));
        String written = writeFile(config.importDir(), name, text);
        if (written != null) return NpcNavCommand.reply(NamedTextColor.RED, written);
        settings.sink().log("NPCNAV-EXPORT graph=" + id + " file=" + name + " nodes=" + graph.nodeCount() + " edges="
                + graph.edgeCount() + " sha=" + NavGraphRepository.documentSha(text).substring(0, 12));
        return NpcNavCommand.reply(NamedTextColor.GREEN, "Exported " + id + " to nav-import/" + name + " ("
                + graph.nodeCount() + " nodes).");
    }

    // ---------------------------------------------------------------- waypoint files

    private void exportSet(CommandSender sender, String[] args) {
        List<String> tokens = tail(args, 3);
        World world = world(sender, "export", tokens);
        if (world == null) return;
        if (!tokens.isEmpty()) {
            usage(sender);
            return;
        }
        String region = args[2];
        UUID worldId = world.getUID();
        Config config = config();
        NpcNavCommand.ReportSink sink = host.settings().sink();
        String by = sender.getName();
        NavGraphRepository repository = plugin.navGraphRepository();
        host.enqueue(sender, () -> {
            NavGraphRepository.WaypointSetRow row = repository.waypointSet(worldId, region);
            if (row == null || row.revision() == 0) return NpcNavCommand.reply(NamedTextColor.GRAY, "No waypoints in " + region + " yet.");
            String name = exportName(region, "-" + LocalDateTime.now().format(STAMP));
            String failed = writeFile(config.importDir(), name, row.document());
            if (failed != null) return NpcNavCommand.reply(NamedTextColor.RED, failed);
            sink.log("NPCNAV-WP op=export region=" + region + " point=- revision=" + row.revision() + " sha="
                    + row.docSha().substring(0, 8) + " by=" + by);
            return NpcNavCommand.reply(NamedTextColor.GREEN, "Wrote nav-import/" + name + " (set " + row.docSha().substring(0, 8) + ").");
        });
    }

    private void importSet(CommandSender sender, String[] args) {
        List<String> tokens = tail(args, 3);
        World world = world(sender, "import", tokens);
        if (world == null) return;
        if (tokens.size() != 1 || !StrictYaml.FILE_NAME.matcher(tokens.getFirst()).matches()) {
            usage(sender);
            return;
        }
        String region = args[2];
        String file = tokens.getFirst();
        UUID worldId = world.getUID();
        Config config = config();
        NpcNavCommand.ReportSink sink = host.settings().sink();
        String by = sender.getName();
        NavGraphRepository repository = plugin.navGraphRepository();
        host.enqueue(sender, () -> {
            WaypointSet imported;
            try {
                imported = WaypointCodec.parse(StrictYaml.readImportFile(config.importDir(), file, config.maxBytes()));
            } catch (WaypointSet.EditRefused refused) {
                return NpcNavCommand.reply(NamedTextColor.RED, "Import refused: " + refused.code() + " " + refused.getMessage());
            } catch (IllegalArgumentException refused) {
                return NpcNavCommand.reply(NamedTextColor.RED, "Import refused: " + refused.getMessage());
            }
            if (imported.points().size() > config.limits().maxPoints() || imported.links().size() > config.limits().maxLinks()) {
                return NpcNavCommand.reply(NamedTextColor.RED, "Import refused: more points or links than "
                        + "nav.waypoints.max-points / max-links allow.");
            }
            return editSet(repository, sink, worldId, region, "import", file, by, set -> imported, null, true);
        });
    }

    /** Writes a new file in the import folder; never replaces one. Returns a reason on failure, else null. */
    private static String writeFile(Path folder, String name, String text) {
        try {
            Files.createDirectories(folder);
            Path path = folder.resolve(name);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return "nav-import/" + name + " already exists; try again in a second.";
            Files.writeString(path, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return null;
        } catch (IOException | RuntimeException failure) {
            return "Could not write nav-import/" + name + " (" + failure.getClass().getSimpleName() + ").";
        }
    }

    // ---------------------------------------------------------------- tab completion

    static List<String> complete(String[] args, List<String> palettes) {
        if (args.length == 2) {
            return List.of("add", "move", "remove", "link", "unlink", "tag", "list", "check", "build", "export", "import");
        }
        String op = args[1].toLowerCase(Locale.ROOT);
        return switch (op) {
            case "add" -> args.length == 5 ? List.of("sidewalk", "access", "link") : args.length == 6 ? List.of("link") : List.of();
            case "link" -> args.length == 6 ? List.of("walk", "cross", "crosswalk") : List.of();
            case "tag" -> args.length == 5 ? List.of("home", "work", "market", "leisure", "spawn")
                    : args.length == 6 ? List.of("remove") : List.of();
            case "build" -> args.length == 4 ? palettes : args.length == 5 ? List.of("load") : List.of();
            default -> List.of();
        };
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static void send(CommandSender sender, NamedTextColor color, String msg) {
        sender.sendMessage(Component.text(msg, color));
    }
}
