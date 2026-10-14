package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavCorridor;
import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.nav.NavGraphScanner;
import com.prosperitysmp.npc.nav.NavPalette;
import com.prosperitysmp.npc.nav.NavGraphValidator;
import com.prosperitysmp.npc.nav.NavScanLogic;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * {@code /npcnav}: the draft → validate → publish workflow for district
 * navigation graphs. A material scan only ever writes a DRAFT; it becomes
 * the active graph after {@code validate} passes and an admin {@code publish}es it.
 */
public final class NpcNavCommand implements CommandExecutor, TabCompleter, AutoCloseable {

    private static final String ADMIN_PERMISSION = "prosperitynpc.admin";
    private static final int TAG_QUEUE_LIMIT = 8;
    private final ProsperityNpcPlugin plugin;
    private final ThreadPoolExecutor tagWorker;
    // Requests wait on the main thread so authorization is fresh when the worker becomes free.
    private final ArrayDeque<Request> tagQueue = new ArrayDeque<>();
    private Request activeTag;
    private Future<Reply> tagResult;
    private Request scanRequest;
    private NavGraphScanner.Capture capture;
    private boolean scanOutstanding;
    private long scanStarted;
    private BukkitTask tagPump;
    private volatile boolean closed;

    private record TagRequest(UUID playerId, UUID graphId, UUID worldId, double x, double y, double z,
                              String tag, boolean add) { }
    private record TagReply(boolean success, String message) { }
    @FunctionalInterface
    private interface Work {
        Reply run() throws SQLException;
    }

    private record Request(CommandSender sender, UUID playerId, UUID worldId, World scanWorld, Work work) { }
    private record Line(NamedTextColor color, String text) { }
    private record Reply(List<Line> lines, NavGraphRepository.GraphInfo invalidate) {
        private Reply {
            lines = List.copyOf(lines);
        }
    }

    private static Reply reply(NamedTextColor color, String text) {
        return new Reply(List.of(new Line(color, text)), null);
    }

    public NpcNavCommand(ProsperityNpcPlugin plugin) {
        this.plugin = plugin;
        tagWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(TAG_QUEUE_LIMIT), task -> {
                    Thread thread = new Thread(task, "prosperity-npc-nav-tags");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        plugin.trackDatabaseWorker(tagWorker);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (closed || !sender.hasPermission(ADMIN_PERMISSION)) {
            send(sender, NamedTextColor.RED, "Navigation administration is unavailable.");
            return true;
        }
        if (plugin.navGraphRepository() == null || plugin.navGraphScanner() == null) {
            send(sender, NamedTextColor.RED, "Navigation subsystem unavailable (no database).");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "scanhere" -> scanHere(sender, args);
                case "scanbox" -> scanBox(sender, args);
                case "validate" -> validate(sender, args);
                case "publish" -> publish(sender, args);
                case "corridor" -> corridor(sender, args);
                case "taghere" -> tagHere(sender, args);
                case "info" -> info(sender, args);
                case "list" -> list(sender);
                default -> help(sender);
            }
        } catch (Exception e) {
            send(sender, NamedTextColor.RED, "Navigation request failed. Check the arguments or ask staff to review the log.");
            plugin.getLogger().warning("/npcnav " + sub + " failed (" + e.getClass().getSimpleName() + ")");
        }
        return true;
    }

    private void tagHere(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, NamedTextColor.RED, "taghere requires an in-game player position.");
            return;
        }
        if (args.length < 3 || args.length > 4 || (args.length == 4 && !args[3].equalsIgnoreCase("remove"))) {
            send(sender, NamedTextColor.GRAY, "Usage: /npcnav taghere <graphUUID> <tag> [remove]");
            return;
        }
        UUID graphId = parseId(sender, args);
        if (graphId == null) return;
        if (!NavGraphRepository.isValidDraftTag(args[2])) {
            send(sender, NamedTextColor.RED,
                    "Use home, work, market, leisure, spawn, home/work/market/leisure:<residentUUID>, or work:<occupation>.");
            return;
        }
        Location position = player.getLocation();
        if (position.getWorld() == null) return;
        TagRequest request = new TagRequest(player.getUniqueId(), graphId, position.getWorld().getUID(),
                position.getX(), position.getY(), position.getZ(), args[2], args.length == 3);
        NavGraphRepository repository = plugin.navGraphRepository();
        enqueue(sender, () -> {
            TagReply result = writeTag(repository, request);
            return reply(result.success() ? NamedTextColor.GREEN : NamedTextColor.RED, result.message());
        });
    }

    private Request request(CommandSender sender, Work work) {
        Player player = sender instanceof Player p ? p : null;
        return new Request(sender, player == null ? null : player.getUniqueId(),
                player == null ? null : player.getWorld().getUID(), null, work);
    }

    private boolean available(CommandSender sender) {
        if (tagQueue.size() >= TAG_QUEUE_LIMIT || scanOutstanding
                || activeTag != null && activeTag.sender() == sender
                || tagQueue.stream().anyMatch(r -> r.sender() == sender)) {
            send(sender, NamedTextColor.GRAY, "Navigation authoring is busy. Retry shortly.");
            return false;
        }
        return !closed;
    }

    private void startPump() {
        if (tagPump == null) tagPump = plugin.getServer().getScheduler().runTaskTimer(plugin, this::pumpTags, 1L, 1L);
    }

    private void enqueue(CommandSender sender, Work work) {
        if (!available(sender)) return;
        Request request = request(sender, work);
        if (!authorized(request)) return;
        startPump();
        tagQueue.addLast(request);
        send(sender, NamedTextColor.GRAY, "Navigation request queued.");
    }

    /** Main-thread only. Never waits for SQL or lets a completion backlog admit more work. */
    private void pumpTags() {
        if (closed) return;
        if (capture != null) {
            try {
                if (!authorized(scanRequest)) {
                    capture = null;
                    scanRequest = null;
                    scanOutstanding = false;
                }
                else if (System.nanoTime() - scanStarted >= TimeUnit.MINUTES.toNanos(10)) {
                    throw new IllegalStateException("Scan capture deadline exceeded");
                }
                else if (capture.advance()) {
                    if (authorized(scanRequest)) tagQueue.addLast(scanRequest);
                    else scanOutstanding = false;
                    capture = null;
                    scanRequest = null;
                }
            } catch (RuntimeException | LinkageError failure) {
                if (authorized(scanRequest)) send(scanRequest.sender(), NamedTextColor.RED,
                        "Scan stopped; no draft was saved. Check bounds and loaded chunks.");
                capture = null;
                scanRequest = null;
                scanOutstanding = false;
            }
        }
        if (tagResult != null) {
            if (!tagResult.isDone()) return;
            try {
                Reply reply = tagResult.get();
                // Cache invalidation is mandatory even if the requesting player left or lost permission.
                if (reply.invalidate() != null && plugin.renderManager() != null)
                    plugin.renderManager().routesChanged(reply.invalidate().worldUuid(), reply.invalidate().regionId());
                if (authorized(activeTag)) for (Line line : reply.lines()) send(activeTag.sender(), line.color(), line.text());
            } catch (ExecutionException failure) {
                plugin.getLogger().warning("/npcnav worker failed (" + failure.getCause().getClass().getSimpleName() + ")");
                if (authorized(activeTag)) send(activeTag.sender(), NamedTextColor.RED,
                        "Navigation request failed. Staff should check the database before retrying.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                if (activeTag.scanWorld() != null) scanOutstanding = false;
                activeTag = null;
                tagResult = null;
            }
        }
        while (!tagQueue.isEmpty()) {
            Request request = tagQueue.removeFirst();
            if (!authorized(request)) {
                if (request.scanWorld() != null) scanOutstanding = false;
                continue;
            }
            try {
                tagResult = tagWorker.submit(() -> {
                    if (closed || Thread.currentThread().isInterrupted()) {
                        return reply(NamedTextColor.GRAY, "Navigation authoring stopped.");
                    }
                    return request.work().run();
                });
                activeTag = request;
                return;
            } catch (RejectedExecutionException full) {
                if (request.scanWorld() != null) scanOutstanding = false;
                send(request.sender(), NamedTextColor.GRAY, "Navigation authoring is busy. Try again shortly.");
            }
        }
        if (capture == null) {
            tagPump.cancel();
            tagPump = null;
        }
    }

    private boolean authorized(Request request) {
        if (closed || request == null || !request.sender().hasPermission(ADMIN_PERMISSION)) return false;
        if (request.scanWorld() != null && plugin.getServer().getWorld(request.scanWorld().getUID()) != request.scanWorld()) return false;
        if (request.playerId() == null) return true;
        Player player = plugin.getServer().getPlayer(request.playerId());
        return player == request.sender() && player.isOnline()
                && player.getWorld().getUID().equals(request.worldId());
    }

    /** Worker-only immutable inputs. No Bukkit calls, including logging or scheduling. */
    private static TagReply writeTag(NavGraphRepository repository, TagRequest request) throws SQLException {
        try {
            Long node = repository.nearestTagNode(request.graphId(), request.worldId(), request.x(), request.y(), request.z());
            if (node == null) return new TagReply(false, "No graph node within 1.5 blocks in your world. Check the graph and position.");
            boolean changed = repository.setDraftTag(request.graphId(), node, request.tag(), request.add());
            String result = changed ? (request.add() ? "Added" : "Removed")
                    : (request.add() ? "Already present:" : "Already absent:");
            return new TagReply(true, result + " " + request.tag() + " on draft node " + node + ".");
        } catch (IllegalArgumentException refused) {
            return new TagReply(false, refused.getMessage());
        }
    }

    /** Call on the main thread during plugin disable, before closing the database. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (tagPump != null) tagPump.cancel();
        tagPump = null;
        tagQueue.clear();
        capture = null;
        scanRequest = null;
        scanOutstanding = false;
        if (tagResult != null) tagResult.cancel(true);
        tagResult = null;
        activeTag = null;
        tagWorker.shutdownNow();
    }

    private void scanHere(CommandSender sender, String[] args) throws Exception {
        if (!(sender instanceof Player player)) {
            send(sender, NamedTextColor.RED, "scanhere is for in-game players; use scanbox from console.");
            return;
        }
        if (args.length < 3) {
            send(sender, NamedTextColor.GRAY, "Usage: /npcnav scanhere <region> <radius> [palette]");
            return;
        }
        String region = args[1];
        int radius = Integer.parseInt(args[2]);
        String palette = args.length >= 4 ? args[3] : "default";
        int below = plugin.getConfig().getInt("nav.scan-below", 6);
        int above = plugin.getConfig().getInt("nav.scan-above", 4);
        if (radius < 0 || below < 0 || above < 0) {
            throw new IllegalArgumentException("radius and scan margins must be nonnegative");
        }
        int px = player.getLocation().getBlockX();
        int py = player.getLocation().getBlockY();
        int pz = player.getLocation().getBlockZ();
        NavScanLogic.Bounds bounds = new NavScanLogic.Bounds(
                Math.subtractExact(px, radius), Math.subtractExact(py, below),
                Math.addExact(px, radius), Math.addExact(py, above),
                Math.subtractExact(pz, radius), Math.addExact(pz, radius));
        runScan(sender, player.getWorld(), region, palette, bounds);
    }

    private void scanBox(CommandSender sender, String[] args) throws Exception {
        // /npcnav scanbox <region> <world> <x1> <y1> <z1> <x2> <y2> <z2> [palette]
        if (args.length < 9) {
            send(sender, NamedTextColor.GRAY,
                    "Usage: /npcnav scanbox <region> <world> <x1> <y1> <z1> <x2> <y2> <z2> [palette]");
            return;
        }
        String region = args[1];
        World world = plugin.getServer().getWorld(args[2]);
        if (world == null) {
            send(sender, NamedTextColor.RED, "Unknown world: " + args[2]);
            return;
        }
        int x1 = Integer.parseInt(args[3]);
        int y1 = Integer.parseInt(args[4]);
        int z1 = Integer.parseInt(args[5]);
        int x2 = Integer.parseInt(args[6]);
        int y2 = Integer.parseInt(args[7]);
        int z2 = Integer.parseInt(args[8]);
        String palette = args.length >= 10 ? args[9] : "default";
        NavScanLogic.Bounds bounds = new NavScanLogic.Bounds(
                Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2),
                Math.min(z1, z2), Math.max(z1, z2));
        runScan(sender, world, region, palette, bounds);
    }

    private void runScan(CommandSender sender, World world, String region, String paletteName,
                         NavScanLogic.Bounds bounds) throws Exception {
        if (!available(sender)) return;
        if (region == null || !region.matches("[A-Za-z0-9_-]{1,128}")
                || paletteName == null || !paletteName.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid region or palette name");
        NavPalette palette = NavPalette.load(plugin, paletteName);
        NavGraphRepository repository = plugin.navGraphRepository();
        UUID worldId = world.getUID();
        NavGraphScanner.Capture pendingCapture = plugin.navGraphScanner().begin(world, bounds, palette);
        Request origin = request(sender, () -> saveScan(repository, worldId, region, paletteName, pendingCapture));
        Request request = new Request(origin.sender(), origin.playerId(), origin.worldId(), world, origin.work());
        if (!authorized(request)) return;
        startPump();
        capture = pendingCapture;
        scanRequest = request;
        scanOutstanding = true;
        scanStarted = System.nanoTime();
        send(sender, NamedTextColor.GRAY, "Scanning " + bounds.columns() + " columns in " + world.getName()
                + " with palette '" + paletteName + "' (" + palette.size() + " materials)...");
    }

    private static Reply saveScan(NavGraphRepository repository, UUID worldId, String region, String paletteName,
                                  NavGraphScanner.Capture capture) throws SQLException {
        NavGraph graph = capture.finish();
        NavGraphValidator.Report report = NavGraphValidator.validate(graph);
        UUID graphId = repository.saveDraft(worldId, region, paletteName, graph);
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(NamedTextColor.GREEN, "Draft " + graphId + " for '" + region + "': "
                + graph.nodeCount() + " nodes, " + graph.edgeCount() + " edges."));
        lines.addAll(validationLines(report));
        lines.add(new Line(NamedTextColor.GRAY, report.valid() ? "Run: /npcnav publish " + graphId : "Fix errors before publishing."));
        return new Reply(lines, null);
    }

    private void validate(CommandSender sender, String[] args) throws Exception {
        UUID id = parseId(sender, args);
        if (id == null) {
            return;
        }
        NavGraphRepository repository = plugin.navGraphRepository();
        enqueue(sender, () -> {
            NavGraph graph = repository.loadGraph(id);
            return graph == null ? reply(NamedTextColor.RED, "No graph " + id)
                    : new Reply(validationLines(NavGraphValidator.validate(graph)), null);
        });
    }

    private static List<Line> validationLines(NavGraphValidator.Report report) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(report.valid() ? NamedTextColor.GREEN : NamedTextColor.GOLD,
                "Validation: " + (report.valid() ? "PASS" : "FAIL") + " (" + report.errors() + " errors, "
                        + report.warnings() + " warnings)."));
        // Validation can find an issue at every node; do not dump the whole graph into one tick/chat.
        for (var issue : report.issues().stream().limit(20).toList())
            lines.add(new Line(issue.severity() == NavGraphValidator.Severity.ERROR ? NamedTextColor.RED : NamedTextColor.YELLOW,
                    "  [" + issue.code() + "] " + issue.message()));
        if (report.issues().size() > 20) lines.add(new Line(NamedTextColor.GRAY,
                (report.issues().size() - 20) + " additional issues omitted; narrow the draft scan."));
        return lines;
    }

    private void corridor(CommandSender sender, String[] args) {
        UUID id = parseId(sender, args);
        if (id == null) return;
        NavGraphRepository repository = plugin.navGraphRepository();
        enqueue(sender, () -> {
            var info = repository.info(id);
            var graph = repository.loadGraph(id);
            if (info == null || graph == null) return reply(NamedTextColor.RED, "No graph " + id);
            final NavGraph compact;
            try {
                compact = NavCorridor.compact(graph);
            } catch (IllegalArgumentException refused) {
                return reply(NamedTextColor.RED, refused.getMessage());
            }
            UUID draft = repository.saveDraft(info.worldUuid(), info.regionId(), info.palette(), compact);
            return reply(NamedTextColor.GREEN, "Corridor DRAFT " + draft + ": " + compact.nodeCount()
                    + " nodes, " + compact.edgeCount() + " edges. Source " + id
                    + " preserved. Review and publish separately; walking remains unverified.");
        });
    }

    private void publish(CommandSender sender, String[] args) throws Exception {
        UUID id = parseId(sender, args);
        if (id == null) {
            return;
        }
        NavGraphRepository repository = plugin.navGraphRepository();
        enqueue(sender, () -> publishGraph(repository, id));
    }

    private static Reply publishGraph(NavGraphRepository repository, UUID id) throws SQLException {
        NavGraphRepository.GraphInfo info = repository.info(id);
        if (info == null) return reply(NamedTextColor.RED, "No graph " + id);
        boolean[] invalidate = {false};
        try {
            NavGraph graph = repository.loadGraph(id);
            if (graph == null) return reply(NamedTextColor.RED, "No graph " + id);
            NavGraphValidator.Report report = NavGraphValidator.validate(graph);
            if (!report.valid()) return reply(NamedTextColor.RED,
                    "Refusing to publish: " + report.errors() + " validation errors. Run /npcnav validate " + id);
            boolean ok = publishAndInvalidate(() -> repository.publish(id), () -> invalidate[0] = true);
            return new Reply(List.of(new Line(ok ? NamedTextColor.GREEN : NamedTextColor.RED,
                    ok ? "Published " + id + " as the ACTIVE graph (prior active archived)." : "Publish failed.")),
                    invalidate[0] ? info : null);
        } catch (SQLException | RuntimeException uncertain) {
            return new Reply(List.of(new Line(NamedTextColor.RED,
                    "Publish outcome unconfirmed. Routes invalidated; inspect the database before retrying.")), info);
        }
    }

    @FunctionalInterface
    interface PublishAttempt {
        boolean run() throws SQLException;
    }

    static boolean publishAndInvalidate(PublishAttempt attempt, Runnable invalidate) throws SQLException {
        boolean published;
        try {
            published = attempt.run();
        } catch (SQLException | RuntimeException uncertain) {
            // A failed JDBC acknowledgement does not establish that the commit failed.
            invalidate.run();
            throw uncertain;
        }
        if (published) invalidate.run();
        return published;
    }

    private void info(CommandSender sender, String[] args) throws Exception {
        UUID id = parseId(sender, args);
        if (id == null) {
            return;
        }
        NavGraphRepository repository = plugin.navGraphRepository();
        enqueue(sender, () -> {
            NavGraphRepository.GraphInfo gi = repository.info(id);
            if (gi == null) return reply(NamedTextColor.RED, "No graph " + id);
            List<Line> lines = new ArrayList<>();
            lines.add(new Line(NamedTextColor.AQUA, "Graph " + gi.graphId()));
            lines.add(new Line(NamedTextColor.GRAY, "  region=" + gi.regionId() + " palette=" + gi.palette()
                + " v" + gi.version() + " status=" + gi.status() + " nodes=" + gi.nodeCount()
                + " edges=" + gi.edgeCount()));
            if ("DRAFT".equals(gi.status())) lines.add(new Line(NamedTextColor.GRAY,
                    "  Tag nearby nodes: /npcnav taghere " + id + " <tag> [remove]"));
            return new Reply(lines, null);
        });
    }

    private void list(CommandSender sender) throws Exception {
        NavGraphRepository repository = plugin.navGraphRepository();
        enqueue(sender, () -> {
            List<NavGraphRepository.GraphInfo> graphs = repository.list(20);
            if (graphs.isEmpty()) return reply(NamedTextColor.GRAY, "No navigation graphs yet. Scan one with /npcnav scanhere or scanbox.");
            List<Line> lines = new ArrayList<>();
            lines.add(new Line(NamedTextColor.AQUA, graphs.size() + " navigation graph(s):"));
            for (NavGraphRepository.GraphInfo gi : graphs) lines.add(new Line(
                    gi.status().equals("ACTIVE") ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                    "  " + gi.status() + " " + gi.regionId() + " v" + gi.version() + " (" + gi.nodeCount()
                            + "n/" + gi.edgeCount() + "e) " + gi.graphId()));
            return new Reply(lines, null);
        });
    }

    private UUID parseId(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, NamedTextColor.GRAY, "Usage: /npcnav " + args[0] + " <graphId>");
            return null;
        }
        try {
            return UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            send(sender, NamedTextColor.RED, "Not a valid graph id: " + args[1]);
            return null;
        }
    }

    private void help(CommandSender sender) {
        send(sender, NamedTextColor.AQUA, "/npcnav: district navigation graphs");
        send(sender, NamedTextColor.GRAY, "  scanhere <region> <radius> [palette]   (in-game)");
        send(sender, NamedTextColor.GRAY, "  scanbox <region> <world> <x1> <y1> <z1> <x2> <y2> <z2> [palette]");
        send(sender, NamedTextColor.GRAY, "  validate <graphId> | publish <graphId> | info <graphId> | list");
        send(sender, NamedTextColor.GRAY, "  corridor <graphId> (2..8 tagged nodes; creates a new draft only)");
        send(sender, NamedTextColor.GRAY, "  taghere <graphUUID> <tag> [remove]   (DRAFT, in-game, within 1.5 blocks)");
        send(sender, NamedTextColor.GRAY, "  Tags: home|work|market|leisure[:residentUUID], work:<occupation>, spawn");
        send(sender, NamedTextColor.GRAY, "  Tags describe routes; they grant no ownership or access permissions.");
    }

    private static void send(CommandSender sender, NamedTextColor color, String msg) {
        sender.sendMessage(Component.text(msg, color));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (closed || !sender.hasPermission(ADMIN_PERMISSION)) return List.of();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(
                    List.of("scanhere", "scanbox", "validate", "publish", "corridor", "taghere", "info", "list"));
            subs.removeIf(s -> !s.startsWith(args[0].toLowerCase(Locale.ROOT)));
            return subs;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("taghere")) {
            return List.of("home", "work", "market", "leisure", "spawn").stream()
                    .filter(tag -> tag.startsWith(args[2])).toList();
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("taghere") && "remove".startsWith(args[3])) {
            return List.of("remove");
        }
        return List.of();
    }
}
