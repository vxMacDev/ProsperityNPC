package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.nav.NavChunkLoader;
import com.prosperitysmp.npc.nav.NavCorridor;
import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.nav.NavGraphScanner;
import com.prosperitysmp.npc.nav.NavGraphValidator;
import com.prosperitysmp.npc.nav.NavPalette;
import com.prosperitysmp.npc.nav.NavPublishGate;
import com.prosperitysmp.npc.nav.NavReportJson;
import com.prosperitysmp.npc.nav.NavScanLogic;
import com.prosperitysmp.npc.nav.NavSelfTest;
import com.prosperitysmp.npc.nav.NavSurface;
import com.prosperitysmp.npc.nav.NavSweep;
import com.prosperitysmp.npc.nav.NavWorldReader;
import com.prosperitysmp.npc.nav.StreetAudit;
import com.prosperitysmp.npc.nav.StreetRules;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * {@code /npcnav}: the draft → validate → publish workflow for district
 * navigation graphs. A material scan only ever writes a DRAFT; it becomes
 * the active graph after {@code validate} passes and an admin {@code publish}es it.
 *
 * <p>After the map changes, {@code revalidate} says whether a published graph still matches the world and
 * {@code audit} proves its routes obey the sidewalk rules. Replies arrive ticks later, so every command
 * also writes one log line and a JSON file under {@code nav-audit/} for an RCON caller or a bot to read.
 */
public final class NpcNavCommand implements CommandExecutor, TabCompleter, AutoCloseable {

    private static final String ADMIN_PERMISSION = "prosperitynpc.admin";
    private static final int TAG_QUEUE_LIMIT = 8;
    private static final int DETAIL_LINES = 20;
    private static final int LINE_WIDTH = 90;
    private static final long MAIN_JOB_DEADLINE_NANOS = TimeUnit.MINUTES.toNanos(10);
    private final ProsperityNpcPlugin plugin;
    private final ThreadPoolExecutor tagWorker;
    // Requests wait on the main thread so authorization is fresh when the worker becomes free.
    private final ArrayDeque<Request> tagQueue = new ArrayDeque<>();
    private Request activeTag;
    private Future<Reply> tagResult;
    private Request scanRequest;
    private NavGraphScanner.Capture capture;
    private NavChunkLoader scanLoader;
    private boolean scanLoadDone;
    // A scan, audit or revalidate holds this from the command until its reply is delivered.
    private boolean scanOutstanding;
    private long scanStarted;
    private Request sweepRequest;
    private SweepJob sweepJob;
    private World sweepWorld;
    private NavWorldReader sweepReader;
    private NavChunkLoader sweepLoader;
    private String sweepPaletteHash;
    private String sweepPaletteName;
    private NavPalette sweepPalette;
    private long sweepStarted;
    private int sweepReads;
    private BukkitTask tagPump;
    private volatile boolean closed;

    private record TagRequest(UUID playerId, UUID graphId, UUID worldId, double x, double y, double z,
                              String tag, boolean add) { }
    private record TagReply(boolean success, String message) { }
    @FunctionalInterface
    private interface Work {
        Reply run() throws SQLException;
    }

    /** {@code sweeps} marks a request that keeps the scan/sweep slot busy until its reply is delivered. */
    private record Request(CommandSender sender, UUID playerId, UUID worldId, World scanWorld, Work work,
                           boolean sweeps) { }
    private record Line(NamedTextColor color, String text) { }

    /**
     * A world read the worker wants the main thread to run, then hand back to {@code followup}. Everything
     * that only needs the graph, such as the chunk list, is worked out by the worker before the hand-off.
     */
    private record SweepJob(UUID worldId, String paletteName, String requestedPalette, NavSweep sweep,
                            List<Long> chunkKeys, boolean load, boolean live, Followup followup, NavPalette palette) {
        private SweepJob(UUID worldId, String paletteName, String requestedPalette, NavSweep sweep, boolean load,
                         boolean live, Followup followup) {
            this(worldId, paletteName, requestedPalette, sweep, load ? sweep.chunkKeys() : List.of(), load, live,
                    followup, null);
        }

        /** A later round of a read: it uses the palette the earlier round read with, not one looked up again by name. */
        private SweepJob(UUID worldId, String paletteName, NavPalette palette, NavSweep sweep, boolean load,
                         Followup followup) {
            this(worldId, paletteName, null, sweep, load ? sweep.chunkKeys() : List.of(), load, true, followup, palette);
        }
    }
    @FunctionalInterface
    private interface Followup {
        Reply run(SweepOutcome outcome) throws SQLException;
    }
    private record SweepOutcome(NavSweep sweep, String currentHash, String paletteName,
                                String worldName, String error, boolean worldMissing, int chunksLoaded,
                                int chunksAbsent, int chunksSkipped, int ticketsRemaining, NavPalette palette) {
        /** The same read continued: chunk counts add up across rounds, the ticket count is the latest. */
        SweepOutcome after(SweepOutcome earlier) {
            return new SweepOutcome(sweep, currentHash, paletteName, worldName, error, worldMissing,
                    chunksLoaded + earlier.chunksLoaded, chunksAbsent + earlier.chunksAbsent,
                    chunksSkipped + earlier.chunksSkipped, ticketsRemaining, palette);
        }
    }

    private record Reply(List<Line> lines, NavGraphRepository.GraphInfo invalidate, SweepJob next) {
        private Reply {
            lines = List.copyOf(lines);
        }

        private Reply(List<Line> lines, NavGraphRepository.GraphInfo invalidate) {
            this(lines, invalidate, null);
        }
    }

    /** Street and audit numbers, read on the main thread so workers never touch config. */
    private record Settings(StreetRules rules, String rulesError, long maxNanos, int maxStarts, int auditMaxNodes,
                            boolean gate, int maxNodes, int readsPerTick, int maxChunks, int concurrency, int below, int above,
                            ReportSink sink) {
        /** {@code rules} is null (with the reason in {@code rulesError}) when nav.street is misconfigured. */
        StreetAudit.Options auditOptions() {
            return new StreetAudit.Options(rules, maxNanos, maxStarts, System::nanoTime, auditMaxNodes);
        }
    }

    /** Where a command leaves its log line and JSON file. Safe to call from the worker. */
    private record ReportSink(Logger logger, Path dir) {
        void log(String line) {
            logger.info(line);
        }

        void write(String kind, String graph, Map<String, Object> body) {
            try {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve(NavReportJson.fileName(kind, graph, Instant.now())),
                        NavReportJson.toJson(body) + "\n", StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException failure) {
                logger.warning("NPCNAV could not write its " + kind + " report (" + failure.getClass().getSimpleName() + ")");
            }
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
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        // The palette check reads config only, so it works even without a database.
        if (sub.equals("palette")) {
            palette(sender, args);
            return true;
        }
        if (plugin.navGraphRepository() == null || plugin.navGraphScanner() == null) {
            send(sender, NamedTextColor.RED, "Navigation subsystem unavailable (no database).");
            return true;
        }
        try {
            switch (sub) {
                case "scanhere" -> scanHere(sender, args);
                case "scanbox" -> scanBox(sender, args);
                case "validate" -> validate(sender, args);
                case "publish" -> publish(sender, args);
                case "corridor" -> corridor(sender, args);
                case "taghere" -> tagHere(sender, args);
                case "copytags" -> copyTags(sender, args);
                case "restore" -> restore(sender, args);
                case "revalidate" -> revalidate(sender, args);
                case "audit" -> audit(sender, args);
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
                player == null ? null : player.getWorld().getUID(), null, work, false);
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

    /** Like {@link #enqueue}, for work that may hand the main thread a world read; it holds the scan slot. */
    private void enqueueSweep(CommandSender sender, Work work) {
        if (!available(sender)) return;
        Request base = request(sender, work);
        Request request = new Request(base.sender(), base.playerId(), base.worldId(), null, work, true);
        if (!authorized(request)) return;
        startPump();
        tagQueue.addLast(request);
        scanOutstanding = true;
        send(sender, NamedTextColor.GRAY, "Navigation request queued.");
    }

    private static boolean holdsScanSlot(Request request) {
        return request != null && (request.scanWorld() != null || request.sweeps());
    }

    /** Main-thread only. Never waits for SQL or lets a completion backlog admit more work. */
    private void pumpTags() {
        if (closed) return;
        if (capture != null) {
            try {
                if (!authorized(scanRequest)) {
                    releaseScanLoader();
                    capture = null;
                    scanRequest = null;
                    scanOutstanding = false;
                }
                else if (System.nanoTime() - scanStarted >= MAIN_JOB_DEADLINE_NANOS) {
                    throw new IllegalStateException("Scan capture deadline exceeded");
                }
                else if (scanLoader != null && !scanLoadDone) {
                    // Chunks load first, a few at a time; the capture itself starts once they are all held.
                    if (scanLoader.pump()) {
                        scanLoadDone = true;
                        if (scanLoader.absent() > 0) throw new IllegalStateException("scan box has chunks that do not exist");
                    }
                }
                else if (capture.advance()) {
                    releaseScanLoader();
                    if (authorized(scanRequest)) tagQueue.addLast(scanRequest);
                    else scanOutstanding = false;
                    capture = null;
                    scanRequest = null;
                }
            } catch (RuntimeException | LinkageError failure) {
                boolean missing = scanLoader != null && scanLoader.absent() > 0;
                releaseScanLoader();
                if (authorized(scanRequest)) send(scanRequest.sender(), NamedTextColor.RED, missing
                        ? "Scan stopped; part of the box was never generated, so there is nothing to scan there."
                        : "Scan stopped; no draft was saved. Check bounds and loaded chunks.");
                capture = null;
                scanRequest = null;
                scanOutstanding = false;
            }
        }
        pumpSweep();
        if (tagResult != null) {
            if (!tagResult.isDone()) return;
            boolean handedOff = false;
            try {
                Reply reply = tagResult.get();
                // Cache invalidation is mandatory even if the requesting player left or lost permission.
                if (reply.invalidate() != null && plugin.renderManager() != null)
                    plugin.renderManager().routesChanged(reply.invalidate().worldUuid(), reply.invalidate().regionId());
                if (authorized(activeTag)) for (Line line : reply.lines()) send(activeTag.sender(), line.color(), line.text());
                if (reply.next() != null && authorized(activeTag)) {
                    startSweep(activeTag, reply.next());
                    handedOff = true;
                }
            } catch (ExecutionException failure) {
                plugin.getLogger().warning("/npcnav worker failed (" + failure.getCause().getClass().getSimpleName() + ")");
                if (authorized(activeTag)) send(activeTag.sender(), NamedTextColor.RED,
                        "Navigation request failed. Staff should check the database before retrying.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                if (holdsScanSlot(activeTag) && !handedOff) scanOutstanding = false;
                activeTag = null;
                tagResult = null;
            }
        }
        while (!tagQueue.isEmpty()) {
            Request request = tagQueue.removeFirst();
            if (!authorized(request)) {
                if (holdsScanSlot(request)) scanOutstanding = false;
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
                if (holdsScanSlot(request)) scanOutstanding = false;
                send(request.sender(), NamedTextColor.GRAY, "Navigation authoring is busy. Try again shortly.");
            }
        }
        if (capture == null && sweepJob == null) {
            tagPump.cancel();
            tagPump = null;
        }
    }

    private void releaseScanLoader() {
        NavChunkLoader loader = scanLoader;
        scanLoader = null;
        scanLoadDone = false;
        if (loader != null) {
            int loaded = loader.loaded();
            int remaining = loader.release();
            plugin.getLogger().info("NPCNAV-SCAN chunks loaded=" + loaded + " absent=" + loader.absent()
                    + " skipped=" + loader.skipped() + " tickets_remaining=" + remaining);
        }
    }

    /** Takes a world read from the worker: finds the world and palette, loads chunks if asked, then reads. */
    private void startSweep(Request origin, SweepJob job) {
        World world = plugin.getServer().getWorld(job.worldId());
        if (world == null) {
            queueFollowup(origin, job, new SweepOutcome(job.sweep(), null, null, null, null, true, 0, 0, 0, 0, null));
            return;
        }
        NavPalette palette = job.palette();
        String paletteName = palette != null ? palette.name() : job.paletteName();
        // A later round of a read keeps the palette the first round used, even if config was reloaded between them.
        if (palette == null && job.live()) {
            // A graph scanned with a list palette stores no classes; read its world through a classified one.
            String configured = plugin.getConfig().getString("nav.audit.palette", "");
            paletteName = NavPalette.liveAuditPalette(job.requestedPalette(), job.paletteName(),
                    configured == null || configured.isBlank() ? null : configured.trim(), this::classifiedPalette);
            if (paletteName == null) {
                queueFollowup(origin, job, new SweepOutcome(job.sweep(), null, null, world.getName(),
                        "no classified palette to read the world with (graph palette '" + job.paletteName() + "'"
                                + (job.requestedPalette() == null ? "" : ", asked for '" + job.requestedPalette() + "'")
                                + "). Use: /npcnav audit <graphId> live palette <name>, or set nav.audit.palette",
                        false, 0, 0, 0, 0, null));
                return;
            }
        }
        if (palette == null) {
            try {
                // The graph already exists, so a palette name config no longer (or never) defined still reads.
                palette = NavPalette.loadForExistingGraph(plugin, paletteName);
            } catch (IllegalArgumentException refused) {
                queueFollowup(origin, job, new SweepOutcome(job.sweep(), null, paletteName, world.getName(),
                        refused.getMessage(), false, 0, 0, 0, 0, null));
                return;
            }
        }
        Settings settings = readSettings();
        sweepRequest = origin;
        sweepJob = job;
        sweepWorld = world;
        sweepReader = new NavWorldReader(world, palette);
        sweepPaletteHash = palette.hash();
        sweepPaletteName = paletteName;
        sweepPalette = palette;
        sweepReads = settings.readsPerTick();
        sweepLoader = job.load() ? new NavChunkLoader(NavChunkLoader.of(world, plugin), job.chunkKeys(),
                settings.concurrency(), settings.maxChunks()) : null;
        sweepStarted = System.nanoTime();
    }

    private boolean classifiedPalette(String name) {
        try {
            return !NavPalette.load(plugin, name).legacy();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private void queueFollowup(Request origin, SweepJob job, SweepOutcome outcome) {
        tagQueue.addLast(new Request(origin.sender(), origin.playerId(), origin.worldId(), null,
                () -> job.followup().run(outcome), true));
    }

    /** One tick of a world read: load chunks first (if asked), then read a budgeted slice of cells. */
    private void pumpSweep() {
        if (sweepJob == null) return;
        try {
            if (!authorized(sweepRequest)) {
                abandonSweep();
                return;
            }
            if (System.nanoTime() - sweepStarted >= MAIN_JOB_DEADLINE_NANOS) {
                throw new IllegalStateException("Sweep deadline exceeded");
            }
            if (sweepLoader != null && !sweepLoader.pump()) return;
            // Block names for the report are taken inside the slice, while the chunks are held.
            NavSweep.MaterialNamer namer = sweepJob.live() ? sweepReader::materialName : null;
            if (sweepJob.sweep().step(sweepReader, namer, sweepReads, 2_000_000L, System::nanoTime)) {
                completeSweep(null);
            }
        } catch (RuntimeException | LinkageError failure) {
            completeSweep("the world read stopped (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private void abandonSweep() {
        NavChunkLoader loader = sweepLoader;
        clearSweep();
        if (loader != null) {
            loader.release();
        }
        scanOutstanding = false;
    }

    private void clearSweep() {
        sweepRequest = null;
        sweepJob = null;
        sweepWorld = null;
        sweepReader = null;
        sweepLoader = null;
        sweepPaletteHash = null;
        sweepPaletteName = null;
        sweepPalette = null;
    }

    private void completeSweep(String error) {
        SweepJob job = sweepJob;
        Request origin = sweepRequest;
        NavChunkLoader loader = sweepLoader;
        World world = sweepWorld;
        String hash = sweepPaletteHash;
        String paletteName = sweepPaletteName;
        NavPalette palette = sweepPalette;
        clearSweep();
        // Only the tickets are settled here. Turning the cells into a verdict is the worker's job.
        int loaded = 0;
        int absent = 0;
        int skipped = 0;
        int remaining = 0;
        if (loader != null) {
            loaded = loader.loaded();
            absent = loader.absent();
            skipped = loader.skipped();
            remaining = loader.release();
        }
        queueFollowup(origin, job, new SweepOutcome(job.sweep(), hash, paletteName, world.getName(), error, false,
                loaded, absent, skipped, remaining, palette));
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
            if (node == null) return new TagReply(false,
                    "No sidewalk node within 1.5 blocks in your world (roads cannot be stops). Check the graph and position.");
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
        releaseScanLoader();
        capture = null;
        scanRequest = null;
        scanOutstanding = false;
        if (sweepLoader != null) sweepLoader.release();
        clearSweep();
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
        runScan(sender, player.getWorld(), region, palette, bounds, false);
    }

    private void scanBox(CommandSender sender, String[] args) throws Exception {
        // /npcnav scanbox <region> <world> <x1> <y1> <z1> <x2> <y2> <z2> [palette] [load]
        if (args.length < 9) {
            send(sender, NamedTextColor.GRAY,
                    "Usage: /npcnav scanbox <region> <world> <x1> <y1> <z1> <x2> <y2> <z2> [palette] [load]");
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
        boolean load = args.length >= 10 && args[args.length - 1].equalsIgnoreCase("load");
        int rest = load ? args.length - 1 : args.length;
        if (rest > 10) {
            send(sender, NamedTextColor.GRAY,
                    "Usage: /npcnav scanbox <region> <world> <x1> <y1> <z1> <x2> <y2> <z2> [palette] [load]");
            return;
        }
        String palette = rest >= 10 ? args[9] : "default";
        NavScanLogic.Bounds bounds = new NavScanLogic.Bounds(
                Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2),
                Math.min(z1, z2), Math.max(z1, z2));
        runScan(sender, world, region, palette, bounds, load);
    }

    private void runScan(CommandSender sender, World world, String region, String paletteName,
                         NavScanLogic.Bounds bounds, boolean load) throws Exception {
        if (!available(sender)) return;
        if (region == null || !region.matches("[A-Za-z0-9_-]{1,128}")
                || paletteName == null || !paletteName.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid region or palette name");
        NavPalette palette;
        try {
            palette = NavPalette.load(plugin, paletteName);
        } catch (IllegalArgumentException refused) {
            send(sender, NamedTextColor.RED, refused.getMessage());
            return;
        }
        NavGraphRepository repository = plugin.navGraphRepository();
        UUID worldId = world.getUID();
        NavGraphScanner.Capture pendingCapture = plugin.navGraphScanner().begin(world, bounds, palette);
        Settings settings = readSettings();
        NavChunkLoader loader = null;
        if (load) {
            List<Long> chunks = pendingCapture.chunks();
            if (chunks.size() > settings.maxChunks()) {
                send(sender, NamedTextColor.RED, "That box touches " + chunks.size() + " chunks; nav.revalidate.max-chunks is "
                        + settings.maxChunks() + ". Scan a smaller box.");
                return;
            }
            loader = new NavChunkLoader(NavChunkLoader.of(world, plugin), chunks, settings.concurrency(),
                    settings.maxChunks());
        }
        Logger logger = plugin.getLogger();
        String hash = palette.hash();
        boolean legacy = palette.legacy();
        for (String line : palette.parsed().describe()) logger.info("NPCNAV-PALETTE name=" + paletteName + " " + line);
        Request origin = request(sender, () -> saveScan(repository, worldId, region, paletteName, hash, legacy,
                pendingCapture, logger));
        Request request = new Request(origin.sender(), origin.playerId(), origin.worldId(), world, origin.work(), false);
        if (!authorized(request)) return;
        startPump();
        capture = pendingCapture;
        scanRequest = request;
        scanLoader = loader;
        scanLoadDone = loader == null;
        scanOutstanding = true;
        scanStarted = System.nanoTime();
        send(sender, NamedTextColor.GRAY, "Scanning " + bounds.columns() + " columns in " + world.getName()
                + " with palette '" + paletteName + "' (" + palette.size() + " materials"
                + (palette.legacy() ? ", unclassified" : "") + ")" + (load ? ", loading chunks first" : "") + "...");
        if (!palette.legacy()) {
            Map<NavSurface, Integer> sizes = palette.classSizes();
            send(sender, NamedTextColor.GRAY, "  classes: sidewalk " + sizes.getOrDefault(NavSurface.SIDEWALK, 0)
                    + ", access " + sizes.getOrDefault(NavSurface.ACCESS, 0) + ", road "
                    + sizes.getOrDefault(NavSurface.ROAD, 0) + ", crossing " + sizes.getOrDefault(NavSurface.CROSSING, 0));
        }
    }

    private static Reply saveScan(NavGraphRepository repository, UUID worldId, String region, String paletteName,
                                  String paletteHash, boolean legacyPalette, NavGraphScanner.Capture capture,
                                  Logger logger) throws SQLException {
        NavScanLogic.ScanResult result = capture.finish();
        NavGraph graph = result.graph();
        NavScanLogic.Stats stats = result.stats();
        NavGraphValidator.Report report = NavGraphValidator.validate(graph);
        UUID graphId = repository.saveDraft(worldId, region, paletteName, paletteHash, graph);
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(NamedTextColor.GREEN, "Draft " + graphId + " for '" + region + "': "
                + graph.nodeCount() + " nodes, " + graph.edgeCount() + " edges."));
        lines.add(new Line(NamedTextColor.GRAY, "  nodes: sidewalk " + count(stats, NavSurface.SIDEWALK)
                + ", access " + count(stats, NavSurface.ACCESS) + ", road " + count(stats, NavSurface.ROAD)
                + ", crossing " + count(stats, NavSurface.CROSSING) + ", unclassified " + count(stats, NavSurface.UNKNOWN)));
        int blocked = stats.dropped().getOrDefault(NavScanLogic.Drop.BLOCKED, 0);
        if (!legacyPalette) {
            lines.add(new Line(NamedTextColor.GRAY, "  crossings kept " + (stats.crosswalkRays() + stats.plainRays())
                    + " (crosswalk " + stats.crosswalkRays() + ", plain " + stats.plainRays() + "), dropped "
                    + stats.totalDropped()));
            stats.firstDropped().forEach((reason, where) -> lines.add(new Line(NamedTextColor.GRAY,
                    clip("  dropped " + reason + " x" + stats.dropped().get(reason) + ", first " + String.join(" ", where)))));
            if (stats.streetNodes() == 0) {
                lines.add(new Line(NamedTextColor.YELLOW, "WARN no road crossings were built; check the road and sidewalk lists."));
            } else if (stats.crosswalkRays() == 0) {
                lines.add(new Line(NamedTextColor.YELLOW, "WARN no painted crosswalk was found; check the crossing list."));
            }
            if (blocked > 0) {
                lines.add(new Line(NamedTextColor.YELLOW, "WARN " + blocked + " crossings hit a block in no list "
                        + "(maybe an edge-line block missing from road)."));
            }
            int curbs = stats.dropped().getOrDefault(NavScanLogic.Drop.CURB, 0);
            if (curbs > 0) {
                lines.add(new Line(NamedTextColor.GRAY, "  " + curbs + " crossings stopped at a block in no list "
                        + "with sidewalk right behind it (a curb or a plot outline)."));
            }
        }
        lines.addAll(validationLines(report));
        lines.add(new Line(NamedTextColor.GRAY, report.valid() ? "Run: /npcnav publish " + graphId : "Fix errors before publishing."));
        logger.info("NPCNAV-SCAN graph=" + graphId + " region=" + region + " palette=" + paletteName + " hash=" + paletteHash
                + " nodes=" + graph.nodeCount() + " edges=" + graph.edgeCount()
                + " sidewalk=" + count(stats, NavSurface.SIDEWALK) + " access=" + count(stats, NavSurface.ACCESS)
                + " road=" + count(stats, NavSurface.ROAD) + " crossing=" + count(stats, NavSurface.CROSSING)
                + " unknown=" + count(stats, NavSurface.UNKNOWN) + " crosswalkRays=" + stats.crosswalkRays()
                + " plainRays=" + stats.plainRays() + " dropped=" + stats.totalDropped() + " blocked=" + blocked
                + " curb=" + stats.dropped().getOrDefault(NavScanLogic.Drop.CURB, 0));
        return new Reply(lines, null);
    }

    private static int count(NavScanLogic.Stats stats, NavSurface surface) {
        return stats.nodes().getOrDefault(surface, 0);
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
            UUID draft = repository.saveDraft(info.worldUuid(), info.regionId(), info.palette(), info.paletteHash(), compact);
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
        boolean legacy = args.length >= 3 && args[2].equalsIgnoreCase("legacy");
        if (args.length > 3 || (args.length == 3 && !legacy)) {
            send(sender, NamedTextColor.GRAY, "Usage: /npcnav publish <graphId> [legacy]");
            return;
        }
        Settings settings = readSettings();
        if (settings.gate() && settings.rules() == null) {
            send(sender, NamedTextColor.RED, settings.rulesError());
            return;
        }
        PublishPolicy policy = new PublishPolicy(settings.gate(), legacy, currentHashes(), settings.auditOptions(),
                settings.sink(), sender.getName());
        NavGraphRepository repository = plugin.navGraphRepository();
        enqueue(sender, () -> publishGraph(repository, id, policy));
    }

    /** What the publish gate needs, read on the main thread. */
    private record PublishPolicy(boolean gate, boolean legacy, Map<String, String> hashes,
                                 StreetAudit.Options options, ReportSink sink, String by) { }

    private static Reply publishGraph(NavGraphRepository repository, UUID id, PublishPolicy policy) throws SQLException {
        NavGraphRepository.GraphInfo info = repository.info(id);
        if (info == null) return reply(NamedTextColor.RED, "No graph " + id);
        boolean[] invalidate = {false};
        try {
            NavGraph graph = repository.loadGraph(id);
            if (graph == null) return reply(NamedTextColor.RED, "No graph " + id);
            NavGraphValidator.Report report = NavGraphValidator.validate(graph);
            if (!report.valid()) return reply(NamedTextColor.RED,
                    "Refusing to publish: " + report.errors() + " validation errors. Run /npcnav validate " + id);
            int unknown = (int) graph.nodes().stream().filter(n -> n.surface() == NavSurface.UNKNOWN).count();
            String current = policy.hashes().get(info.palette());
            StreetAudit.Result audit = null;
            if (policy.gate() && unknown == 0 && info.paletteHash() != null && info.paletteHash().equals(current)) {
                audit = StreetAudit.audit(graph, null, policy.options());
                policy.sink().log(StreetAudit.logLine(id.toString(), audit));
            }
            NavPublishGate.Decision decision = NavPublishGate.decide(policy.gate(), policy.legacy(), unknown,
                    info.paletteHash(), current, audit);
            if (!decision.allowed()) return reply(NamedTextColor.RED, "Refusing to publish: " + decision.reason());
            if (decision.legacyOverride()) {
                policy.sink().log("NPCNAV-PUBLISH legacy=true graph=" + id + " by=" + policy.by());
            }
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

    private void copyTags(CommandSender sender, String[] args) {
        if (args.length < 3 || args.length > 4 || (args.length == 4 && !args[3].equalsIgnoreCase("partial"))) {
            send(sender, NamedTextColor.GRAY, "Usage: /npcnav copytags <fromGraphId> <toDraftId> [partial]");
            return;
        }
        UUID from = parseUuid(sender, args[1]);
        UUID to = parseUuid(sender, args[2]);
        if (from == null || to == null) return;
        boolean partial = args.length == 4;
        ReportSink sink = reportSink();
        NavGraphRepository repository = plugin.navGraphRepository();
        enqueue(sender, () -> copyTagsReply(repository, from, to, partial, sink));
    }

    private static Reply copyTagsReply(NavGraphRepository repository, UUID from, UUID to, boolean partial,
                                       ReportSink sink) throws SQLException {
        NavGraphRepository.CopyReport report;
        try {
            report = repository.copyTags(from, to, partial);
        } catch (IllegalArgumentException refused) {
            return reply(NamedTextColor.RED, refused.getMessage());
        }
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(report.written() ? NamedTextColor.GREEN : NamedTextColor.RED, report.written()
                ? "Copied " + report.inserted() + " tags onto draft " + to + "."
                : "Nothing was written to " + to + ": some tags have no place to go."));
        lines.add(new Line(NamedTextColor.GRAY, "  EXACT " + report.exact() + ", NEAR " + report.near()
                + ", UNMATCHED " + report.unmatched() + ", SKIPPED_INVALID " + report.skippedInvalid()
                + ", REFUSED_FULL " + report.refusedFull() + ", already there " + report.alreadyPresent()));
        addDetails(lines, report.details());
        lines.add(new Line(NamedTextColor.GRAY, "  anchor nodes on the draft: " + report.anchorNodes()));
        if (report.anchorNodes() > 8) {
            lines.add(new Line(NamedTextColor.YELLOW, "WARN corridor refuses more than 8 anchor nodes; split the district."));
        }
        if (!report.written()) {
            lines.add(new Line(NamedTextColor.GRAY, "  Add the doorstep or lot-path blocks to the palette access list and rescan, "
                    + "tag with taghere, or repeat with 'partial'."));
        }
        sink.log("NPCNAV-COPYTAGS from=" + from + " to=" + to + " written=" + report.written() + " exact=" + report.exact()
                + " near=" + report.near() + " unmatched=" + report.unmatched() + " invalid=" + report.skippedInvalid()
                + " full=" + report.refusedFull() + " inserted=" + report.inserted() + " anchors=" + report.anchorNodes());
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("from", from.toString());
        json.put("to", to.toString());
        json.put("written", report.written());
        json.put("exact", report.exact());
        json.put("near", report.near());
        json.put("unmatched", report.unmatched());
        json.put("skippedInvalid", report.skippedInvalid());
        json.put("refusedFull", report.refusedFull());
        json.put("inserted", report.inserted());
        json.put("anchorNodes", report.anchorNodes());
        json.put("details", report.details());
        sink.write("copytags", to.toString(), json);
        return new Reply(lines, null);
    }

    private void restore(CommandSender sender, String[] args) {
        UUID id = parseId(sender, args);
        if (id == null) return;
        ReportSink sink = reportSink();
        NavGraphRepository repository = plugin.navGraphRepository();
        enqueue(sender, () -> {
            try {
                UUID draft = repository.restore(id);
                var info = repository.info(draft);
                sink.log("NPCNAV-RESTORE from=" + id + " draft=" + draft + " version=" + info.version());
                return reply(NamedTextColor.GREEN, "Restored " + id + " as DRAFT " + draft + " (v" + info.version() + ", "
                        + info.nodeCount() + " nodes). It is not published.");
            } catch (IllegalArgumentException refused) {
                return reply(NamedTextColor.RED, refused.getMessage());
            }
        });
    }

    private void palette(CommandSender sender, String[] args) {
        if (args.length < 2) {
            ConfigurationSection palettes = plugin.getConfig().getConfigurationSection("nav.palettes");
            send(sender, NamedTextColor.GRAY, "Usage: /npcnav palette <name>. Defined: default"
                    + (palettes == null || palettes.getKeys(false).isEmpty() ? "" : ", " + String.join(", ", palettes.getKeys(false))));
            return;
        }
        try {
            NavPalette palette = NavPalette.load(plugin, args[1]);
            List<String> lines = palette.parsed().describe();
            send(sender, NamedTextColor.AQUA, "Palette '" + palette.name() + "' (" + (palette.legacy() ? "unclassified" : "classified") + "):");
            for (String line : lines) {
                send(sender, NamedTextColor.GRAY, "  " + clip(line));
                plugin.getLogger().info("NPCNAV-PALETTE name=" + palette.name() + " " + line);
            }
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("palette", palette.name());
            json.put("legacy", palette.legacy());
            json.put("hash", palette.hash());
            json.put("lines", lines);
            // The file is written off the command thread.
            ReportSink sink = reportSink();
            String paletteName = palette.name();
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> sink.write("palette", paletteName, json));
        } catch (IllegalArgumentException refused) {
            send(sender, NamedTextColor.RED, refused.getMessage());
            plugin.getLogger().warning("NPCNAV-PALETTE name=" + args[1] + " error=" + refused.getMessage());
        }
    }

    private void revalidate(CommandSender sender, String[] args) {
        boolean load = args.length == 3 && args[2].equalsIgnoreCase("load");
        if (args.length < 2 || args.length > 3 || (args.length == 3 && !load)) {
            send(sender, NamedTextColor.GRAY, "Usage: /npcnav revalidate <graphId|active> [load]");
            return;
        }
        boolean active = args[1].equalsIgnoreCase("active");
        UUID id = active ? null : parseUuid(sender, args[1]);
        if (!active && id == null) return;
        Settings settings = readSettings();
        NavGraphRepository repository = plugin.navGraphRepository();
        enqueueSweep(sender, () -> {
            UUID target = id;
            if (active) {
                List<NavGraphRepository.GraphInfo> live = repository.listActive();
                if (live.size() != 1) {
                    return reply(NamedTextColor.RED, live.isEmpty() ? "No ACTIVE graph to revalidate."
                            : live.size() + " ACTIVE graphs; name one: " + live.stream()
                            .map(g -> g.regionId() + "=" + g.graphId()).reduce((a, b) -> a + " " + b).orElse(""));
                }
                target = live.getFirst().graphId();
            }
            NavGraphRepository.GraphInfo info = repository.info(target);
            NavGraph graph = info == null ? null : repository.loadGraph(target);
            if (graph == null) return reply(NamedTextColor.RED, "No graph " + target);
            if (graph.nodeCount() > settings.maxNodes()) {
                return reply(NamedTextColor.RED, "Graph has " + graph.nodeCount() + " nodes; nav.revalidate.max-nodes is "
                        + settings.maxNodes() + ".");
            }
            NavSweep sweep = NavSweep.forRevalidation(graph, info.paletteHash());
            return new Reply(List.of(new Line(NamedTextColor.GRAY, "Revalidating " + target + " (" + graph.nodeCount()
                    + " nodes, " + sweep.total() + " cells" + (load ? ", loading chunks" : "") + ")...")), null,
                    new SweepJob(info.worldUuid(), info.palette(), null, sweep, load, false,
                            outcome -> revalidated(info, outcome, settings, load)));
        });
    }

    private static Reply revalidated(NavGraphRepository.GraphInfo info, SweepOutcome out, Settings settings,
                                     boolean load) {
        String id = info.graphId().toString();
        ReportSink sink = settings.sink();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("graph", id);
        json.put("region", info.regionId());
        if (out.worldMissing() || out.error() != null || !out.sweep().done()) {
            String verdict = out.worldMissing() ? "WORLD_MISSING" : "STOPPED";
            sink.log("NPCNAV-REVALIDATE graph=" + id + " region=" + info.regionId() + " verdict=" + verdict
                    + (out.error() == null ? "" : " error=" + out.error()));
            json.put("verdict", verdict);
            json.put("error", out.error());
            sink.write("revalidate", id, json);
            return reply(NamedTextColor.RED, out.worldMissing() ? "The world for this graph is not loaded: WORLD_MISSING."
                    : "Revalidate stopped: " + out.error());
        }
        NavSweep.Revalidation r = out.sweep().revalidation(out.currentHash());
        List<Line> lines = new ArrayList<>();
        NamedTextColor color = r.verdict() == NavSweep.Verdict.CLEAN ? NamedTextColor.GREEN
                : r.verdict() == NavSweep.Verdict.DRIFTED ? NamedTextColor.RED : NamedTextColor.YELLOW;
        lines.add(new Line(color, "Revalidate " + id + ": " + r.verdict() + " (" + r.nodes() + " nodes, " + r.ok() + " ok)."));
        lines.add(new Line(NamedTextColor.GRAY, "  changed " + r.changedClass() + ", unwalkable " + r.unwalkable()
                + ", blocked " + r.blocked() + ", footprint " + r.footprint() + ", not loaded " + r.notLoaded()
                + ", palette " + r.palette()));
        for (String line : r.lines()) lines.add(new Line(NamedTextColor.GRAY, "  " + clip(line)));
        if (r.driftBox() != null) {
            int[] box = r.driftBox();
            lines.add(new Line(NamedTextColor.GRAY, clip("  Rescan: /npcnav scanbox " + info.regionId() + " " + out.worldName() + " "
                    + (box[0] - 3) + " " + (box[1] - settings.below()) + " " + (box[2] - 3) + " " + (box[3] + 3) + " "
                    + (box[4] + settings.above()) + " " + (box[5] + 3) + " " + info.palette())));
        }
        if (r.notLoaded() > 0 && !load) {
            lines.add(new Line(NamedTextColor.GRAY, "  Some chunks are not loaded; add 'load' to load them (DEV only)."));
        }
        if (load) {
            lines.add(new Line(NamedTextColor.GRAY, "  chunks loaded " + out.chunksLoaded() + ", missing " + out.chunksAbsent()
                    + ", over the cap " + out.chunksSkipped() + ", tickets_remaining=" + out.ticketsRemaining()));
        }
        sink.log(r.logLine(id, info.regionId(), out.ticketsRemaining()));
        json.put("verdict", r.verdict().name());
        json.put("nodes", r.nodes());
        json.put("ok", r.ok());
        json.put("changed", r.changedClass());
        json.put("unwalkable", r.unwalkable());
        json.put("blocked", r.blocked());
        json.put("footprint", r.footprint());
        json.put("notLoaded", r.notLoaded());
        json.put("palette", r.palette());
        json.put("ticketsRemaining", out.ticketsRemaining());
        List<Object> drifts = new ArrayList<>();
        for (NavSweep.Drift d : r.drifts()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", d.kind());
            entry.put("node", d.nodeId());
            entry.put("tagged", d.tagged());
            entry.put("at", new int[]{d.x(), d.y(), d.z()});
            entry.put("detail", d.detail());
            drifts.add(entry);
        }
        json.put("drifts", drifts);
        json.put("driftBox", r.driftBox());
        sink.write("revalidate", id, json);
        return new Reply(lines, null);
    }

    private void audit(CommandSender sender, String[] args) {
        if (args.length == 2 && args[1].equalsIgnoreCase("selftest")) {
            ReportSink sink = reportSink();
            enqueue(sender, () -> selfTestReply(sink));
            return;
        }
        boolean live = false;
        boolean load = false;
        boolean unknownWord = false;
        String requestedPalette = null;
        for (int i = 2; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("live") && !live) live = true;
            else if (args[i].equalsIgnoreCase("load") && !load) load = true;
            else if (args[i].equalsIgnoreCase("palette") && requestedPalette == null && i + 1 < args.length
                    && args[i + 1].matches("[A-Za-z0-9_-]{1,64}")) requestedPalette = args[++i];
            else unknownWord = true;
        }
        if (args.length < 2 || unknownWord) {
            send(sender, NamedTextColor.GRAY, "Usage: /npcnav audit <graphId> [live] [load] [palette <name>] | audit selftest");
            return;
        }
        UUID id = parseUuid(sender, args[1]);
        if (id == null) return;
        Settings settings = readSettings();
        if (settings.rules() == null) {
            send(sender, NamedTextColor.RED, settings.rulesError());
            return;
        }
        // Naming a palette means "read the world with it", so it implies a live audit.
        boolean wantLive = live || requestedPalette != null;
        boolean wantLoad = load;
        String wantPalette = requestedPalette;
        NavGraphRepository repository = plugin.navGraphRepository();
        enqueueSweep(sender, () -> {
            NavGraphRepository.GraphInfo info = repository.info(id);
            NavGraph graph = info == null ? null : repository.loadGraph(id);
            if (graph == null) return reply(NamedTextColor.RED, "No graph " + id);
            boolean unclassified = graph.nodes().stream().anyMatch(n -> n.surface() == NavSurface.UNKNOWN);
            if (!needsWorldRead(wantLive, unclassified, graph.nodeCount(), settings.auditMaxNodes())) {
                return auditReply(info, StreetAudit.audit(graph, null, settings.auditOptions()), settings, null);
            }
            NavSweep sweep = NavSweep.forClasses(graph);
            return new Reply(List.of(new Line(NamedTextColor.GRAY, "Reading the world under " + graph.nodeCount()
                    + " nodes (" + sweep.total() + " cells)" + (wantLoad ? ", loading chunks" : "") + "...")), null,
                    new SweepJob(info.worldUuid(), info.palette(), wantPalette, sweep, wantLoad, true,
                            outcome -> liveAudit(info, graph, settings, wantLoad, outcome)));
        });
    }

    /**
     * Whether an audit has to read the world first: it does for a live audit or a graph with unclassified nodes,
     * but not for a graph over the node cap, which the audit turns away at once.
     */
    static boolean needsWorldRead(boolean wantLive, boolean unclassified, int nodes, int auditMaxNodes) {
        return (wantLive || unclassified) && nodes <= auditMaxNodes;
    }

    /**
     * Worker side of a live audit. The first read gives the class under every node; the crossings those
     * classes show are only known then, so the worker plans the width probes and hands the main thread a
     * second read. Only after that does it turn the cells into a verdict.
     */
    private static Reply liveAudit(NavGraphRepository.GraphInfo info, NavGraph graph, Settings settings, boolean load,
                                   SweepOutcome outcome) throws SQLException {
        if (outcome.worldMissing() || outcome.error() != null || !outcome.sweep().done()) {
            String why = outcome.worldMissing() ? "the world is not loaded"
                    : outcome.error() != null ? outcome.error() : "the world read did not finish";
            settings.sink().log("NPCNAV-AUDIT graph=" + info.graphId() + " verdict=INCONCLUSIVE reason=" + why);
            return reply(NamedTextColor.YELLOW, "Audit INCONCLUSIVE: " + why);
        }
        NavSweep sweep = outcome.sweep();
        if (sweep.needsWidthProbes()) {
            sweep.planWidthProbes(settings.rules().maxCrossingLength());
            // The second read uses the palette the first one read with, not one looked up again by name.
            return new Reply(List.of(new Line(NamedTextColor.GRAY, "Reading the road beside the crossings ("
                    + sweep.total() + " cells in all)...")), null,
                    new SweepJob(info.worldUuid(), info.palette(), outcome.palette(), sweep, load,
                            second -> liveAudit(info, graph, settings, load, second.after(outcome))));
        }
        StreetAudit.Live live;
        try {
            live = sweep.live();
        } catch (RuntimeException failure) {
            String why = "the class sweep could not be read (" + failure.getClass().getSimpleName() + ")";
            settings.sink().log("NPCNAV-AUDIT graph=" + info.graphId() + " verdict=INCONCLUSIVE reason=" + why);
            return reply(NamedTextColor.YELLOW, "Audit INCONCLUSIVE: " + why);
        }
        return auditReply(info, StreetAudit.audit(graph, live, settings.auditOptions()), settings, outcome);
    }

    private static Reply selfTestReply(ReportSink sink) {
        NavSelfTest.Report report = NavSelfTest.run();
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(report.ok() ? NamedTextColor.GREEN : NamedTextColor.RED,
                report.ok() ? "Audit self-test OK: the audit fails the unprotected street and passes the protected one."
                        : "Audit self-test BROKEN: the audit did not give the expected verdicts."));
        Map<String, Object> json = new LinkedHashMap<>();
        List<Object> cases = new ArrayList<>();
        for (NavSelfTest.Case c : report.cases()) {
            lines.add(new Line(NamedTextColor.GRAY, "  " + c.name() + ": " + c.result().verdict() + " (expected " + c.expected() + ")"));
            sink.log(StreetAudit.logLine(c.name(), c.result()));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", c.name());
            entry.put("expected", c.expected().name());
            entry.put("verdict", c.result().verdict().name());
            cases.add(entry);
        }
        json.put("ok", report.ok());
        json.put("cases", cases);
        sink.log("NPCNAV-AUDIT selftest=" + (report.ok() ? "OK" : "BROKEN"));
        sink.write("audit", "selftest", json);
        return new Reply(lines, null);
    }

    private static Reply auditReply(NavGraphRepository.GraphInfo info, StreetAudit.Result r, Settings settings,
                                    SweepOutcome outcome) {
        String id = info.graphId().toString();
        List<Line> lines = new ArrayList<>();
        NamedTextColor color = r.verdict() == StreetAudit.Verdict.PASS ? NamedTextColor.GREEN
                : r.verdict() == StreetAudit.Verdict.FAIL ? NamedTextColor.RED : NamedTextColor.YELLOW;
        lines.add(new Line(color, "Audit " + id + ": " + r.verdict() + " (" + r.mode() + " mode)."));
        lines.add(new Line(NamedTextColor.GRAY, "  starts " + r.starts() + ", paths " + r.paths() + ", failed "
                + r.failedPaths() + ", longest crossing " + r.maxRun() + ", unclassified " + r.unclassified()
                + (r.unchecked() > 0 ? ", unchecked " + r.unchecked() : "")));
        StringBuilder codes = new StringBuilder();
        r.counts().forEach((code, n) -> codes.append(codes.isEmpty() ? "" : ", ").append(code).append(' ').append(n));
        if (!codes.isEmpty()) lines.add(new Line(NamedTextColor.GRAY, clip("  " + codes)));
        if (r.timedOut()) lines.add(new Line(NamedTextColor.YELLOW, "  stopped at nav.audit.max-seconds; the verdict cannot be PASS."));
        if (r.startsCapped()) lines.add(new Line(NamedTextColor.YELLOW, "  more starts than nav.audit.max-starts; the verdict cannot be PASS."));
        if (r.overNodeCap()) lines.add(new Line(NamedTextColor.YELLOW, "  more nodes than nav.audit.max-nodes; nothing was audited and the verdict cannot be PASS."));
        lines.add(new Line(NamedTextColor.GRAY, "  nodes " + r.nodes() + "/" + com.prosperitysmp.npc.nav.Dijkstra.MAX_VISITED
                + " edges " + r.edges() + "/" + com.prosperitysmp.npc.nav.Dijkstra.MAX_EDGES_EXAMINED + " (runtime limits)"));
        List<String> detail = new ArrayList<>();
        for (StreetAudit.Violation v : r.examples()) detail.add(v.code() + " " + v.detail());
        addDetails(lines, detail);
        List<String> stops = r.stops();
        for (int i = 0; i < Math.min(stops.size(), 8); i++) lines.add(new Line(NamedTextColor.GRAY, clip("  stop " + stops.get(i))));
        if (stops.size() > 8) lines.add(new Line(NamedTextColor.GRAY, "  (" + (stops.size() - 8) + " more stops omitted)"));
        if (outcome != null) {
            if (outcome.paletteName() != null) lines.add(new Line(NamedTextColor.GRAY, "  world read with palette '" + outcome.paletteName() + "'"));
            lines.add(new Line(NamedTextColor.GRAY, "  chunks loaded " + outcome.chunksLoaded() + ", missing " + outcome.chunksAbsent()
                    + ", over the cap " + outcome.chunksSkipped() + ", tickets_remaining=" + outcome.ticketsRemaining()));
        }
        settings.sink().log(StreetAudit.logLine(id, r));
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("graph", id);
        json.put("region", info.regionId());
        json.put("mode", r.mode());
        json.put("verdict", r.verdict().name());
        json.put("nodes", r.nodes());
        json.put("edges", r.edges());
        json.put("starts", r.starts());
        json.put("paths", r.paths());
        json.put("failed", r.failedPaths());
        json.put("maxRun", r.maxRun());
        json.put("unclassified", r.unclassified());
        json.put("unchecked", r.unchecked());
        json.put("overNodeCap", r.overNodeCap());
        json.put("offPalette", r.offPaletteNodes());
        json.put("stopsOnStreet", r.stopsOnStreet());
        Map<String, Object> counts = new LinkedHashMap<>();
        r.counts().forEach((code, n) -> counts.put(code.name(), n));
        json.put("counts", counts);
        json.put("violations", detail);
        json.put("stops", stops);
        if (outcome != null) {
            json.put("readPalette", outcome.paletteName());
            json.put("ticketsRemaining", outcome.ticketsRemaining());
        }
        settings.sink().write("audit", id, json);
        return new Reply(lines, null);
    }

    /** At most 20 detail lines, then how many were left out. */
    private static void addDetails(List<Line> lines, List<String> details) {
        for (int i = 0; i < Math.min(details.size(), DETAIL_LINES); i++) {
            lines.add(new Line(NamedTextColor.GRAY, clip("  " + details.get(i))));
        }
        if (details.size() > DETAIL_LINES) {
            lines.add(new Line(NamedTextColor.GRAY, "  (" + (details.size() - DETAIL_LINES) + " more omitted)"));
        }
    }

    private static String clip(String text) {
        return text.length() <= LINE_WIDTH ? text : text.substring(0, LINE_WIDTH - 3) + "...";
    }

    /** Effective palette hash for every palette defined in config now (and the built-in default). */
    private Map<String, String> currentHashes() {
        Map<String, String> hashes = new HashMap<>();
        List<String> names = new ArrayList<>(List.of(NavPalette.DEFAULT_NAME));
        ConfigurationSection palettes = plugin.getConfig().getConfigurationSection("nav.palettes");
        if (palettes != null) names.addAll(palettes.getKeys(false));
        for (String name : names) {
            try {
                hashes.put(name, NavPalette.load(plugin, name).hash());
            } catch (IllegalArgumentException invalid) {
                plugin.getLogger().warning("NPCNAV-PALETTE name=" + name + " error=" + invalid.getMessage());
            }
        }
        return hashes;
    }

    private ReportSink reportSink() {
        String dir = plugin.getConfig().getString("nav.audit.report-dir", "nav-audit");
        if (dir == null || !dir.matches("[A-Za-z0-9_-]{1,64}")) dir = "nav-audit";
        return new ReportSink(plugin.getLogger(), plugin.getDataFolder().toPath().resolve(dir));
    }

    /** Reads the street, audit and sweep numbers; a bad street value leaves {@code rules} null with a readable reason. */
    private Settings readSettings() {
        var config = plugin.getConfig();
        StreetRules rules = null;
        String rulesError = null;
        try {
            rules = NavPalette.streetRules(config.getConfigurationSection("nav"));
        } catch (IllegalArgumentException invalid) {
            rulesError = invalid.getMessage();
        }
        return new Settings(rules, rulesError, TimeUnit.SECONDS.toNanos(clamp(config.getInt("nav.audit.max-seconds", 20), 1, 600)),
                clamp(config.getInt("nav.audit.max-starts", 1024), 1, 20_000),
                clamp(config.getInt("nav.audit.max-nodes", StreetAudit.Options.DEFAULT_MAX_NODES), 1, 200_000),
                config.getBoolean("nav.audit.gate-publish", true),
                clamp(config.getInt("nav.revalidate.max-nodes", 50_000), 1, 200_000),
                clamp(config.getInt("nav.revalidate.reads-per-tick", 512), 1, 4096),
                clamp(config.getInt("nav.revalidate.max-chunks", 256), 1, 4096),
                clamp(config.getInt("nav.revalidate.load-concurrency", 4), 1, 16),
                clamp(config.getInt("nav.scan-below", 6), 0, 64), clamp(config.getInt("nav.scan-above", 4), 0, 64),
                reportSink());
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
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
            Map<NavSurface, Integer> classes = new java.util.EnumMap<>(NavSurface.class);
            for (NavGraphRepository.NodeSurface node : repository.nodeSurfaces(id)) classes.merge(node.surface(), 1, Integer::sum);
            lines.add(new Line(NamedTextColor.GRAY, "  classes: sidewalk " + classes.getOrDefault(NavSurface.SIDEWALK, 0)
                    + ", access " + classes.getOrDefault(NavSurface.ACCESS, 0) + ", road " + classes.getOrDefault(NavSurface.ROAD, 0)
                    + ", crossing " + classes.getOrDefault(NavSurface.CROSSING, 0)
                    + ", unclassified " + classes.getOrDefault(NavSurface.UNKNOWN, 0)));
            lines.add(new Line(NamedTextColor.GRAY, "  palette hash: " + (gi.paletteHash() == null ? "none (legacy scan)" : gi.paletteHash())));
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
        return parseUuid(sender, args[1]);
    }

    private UUID parseUuid(CommandSender sender, String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            send(sender, NamedTextColor.RED, "Not a valid graph id: " + text);
            return null;
        }
    }

    private void help(CommandSender sender) {
        send(sender, NamedTextColor.AQUA, "/npcnav: district navigation graphs");
        send(sender, NamedTextColor.GRAY, "  scanhere <region> <radius> [palette]   (in-game)");
        send(sender, NamedTextColor.GRAY, "  scanbox <region> <world> <x1> <y1> <z1> <x2> <y2> <z2> [palette] [load]");
        send(sender, NamedTextColor.GRAY, "  palette <name>   (prints the block classes, street numbers and hash)");
        send(sender, NamedTextColor.GRAY, "  validate <graphId> | publish <graphId> [legacy] | info <graphId> | list");
        send(sender, NamedTextColor.GRAY, "  corridor <graphId> (2..8 tagged nodes; creates a new draft only)");
        send(sender, NamedTextColor.GRAY, "  copytags <fromGraphId> <toDraftId> [partial]   (carry tags onto a new draft)");
        send(sender, NamedTextColor.GRAY, "  restore <archivedGraphId>   (new draft from an archived graph)");
        send(sender, NamedTextColor.GRAY, "  revalidate <graphId|active> [load]   (does the world still match the graph?)");
        send(sender, NamedTextColor.GRAY, "  audit <graphId> [live] [load] [palette <name>] | audit selftest   (do routes keep to sidewalks?)");
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
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("scanhere", "scanbox", "palette", "validate", "publish", "corridor",
                    "copytags", "restore", "revalidate", "audit", "taghere", "info", "list"));
            subs.removeIf(s -> !s.startsWith(last));
            return subs;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 3 && sub.equals("taghere")) {
            return List.of("home", "work", "market", "leisure", "spawn").stream()
                    .filter(tag -> tag.startsWith(args[2])).toList();
        }
        if (args.length == 4 && sub.equals("taghere") && "remove".startsWith(args[3])) {
            return List.of("remove");
        }
        List<String> options = switch (sub) {
            case "palette" -> args.length == 2 ? paletteNames() : List.of();
            case "scanbox" -> args.length == 10 ? paletteNames() : args.length >= 10 ? List.of("load") : List.of();
            case "scanhere" -> args.length == 4 ? paletteNames() : List.of();
            case "publish" -> args.length == 3 ? List.of("legacy") : List.of();
            case "copytags" -> args.length == 4 ? List.of("partial") : List.of();
            case "revalidate" -> args.length == 2 ? List.of("active") : args.length == 3 ? List.of("load") : List.of();
            case "audit" -> args.length == 2 ? List.of("selftest") : args.length >= 3
                    ? (args[args.length - 2].equalsIgnoreCase("palette") ? paletteNames() : List.of("live", "load", "palette"))
                    : List.of();
            default -> List.of();
        };
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }

    private List<String> paletteNames() {
        List<String> names = new ArrayList<>(List.of(NavPalette.DEFAULT_NAME));
        ConfigurationSection palettes = plugin.getConfig().getConfigurationSection("nav.palettes");
        if (palettes != null) names.addAll(palettes.getKeys(false));
        return names;
    }
}
