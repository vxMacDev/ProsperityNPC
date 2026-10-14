package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.appearance.OutfitChangePolicy;
import com.prosperitysmp.npc.appearance.ResidentAppearanceCatalogue;
import com.prosperitysmp.npc.nav.Dijkstra;
import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyService;
import com.prosperitysmp.npc.property.Tenant;
import com.prosperitysmp.npc.property.TenantRepository;
import com.prosperitysmp.npc.resident.ResidentActivity;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.resident.ResidentRepository;
import com.prosperitysmp.npc.resident.ResidentRoutine;
import com.prosperitysmp.npc.resident.ResidentRoutePlanner;
import com.prosperitysmp.npc.town.CivicPlacement;
import com.prosperitysmp.npc.town.CivicPlacementRepository;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Renders tenant avatars on demand (§10.2) and walks them along the district's
 * published nav graph. It runs entirely off a repeating task and READS simulation
 * state only — rendering is cosmetic and can never affect economy, occupancy, or
 * settlement. Selection grants at most {@code max-per-viewer} slots per viewer;
 * the global cap includes debug avatars and grace-period survivors. This is not
 * per-player packet visibility filtering. SQL runs on a single read worker.
 *
 * <p>Walking: {@link Dijkstra} chooses a node path across the graph and the avatar
 * is driven one edge at a time; after {@code max-fails} edge attempts it despawns
 * and the simulation simply continues abstractly.
 */
public final class RenderManager {

    private static final int MAX_PROPERTIES = 64;
    private static final int MAX_CANDIDATES = 2048;
    private static final int MAX_GRAPH_NODES = 20_000;
    private static final int MAX_GRAPH_EDGES = 160_000;
    private static final int HARD_AVATAR_CAP = 100;
    private static final long RETRY_MS = 30_000L;

    record GraphKey(UUID worldId, String region) {
        GraphKey {
            // WG ids are case-insensitive; V1's region lookup also uses a CI collation.
            region = region.toLowerCase(java.util.Locale.ROOT);
        }
    }
    record Candidate(Property property, String name, NavGraph graph, ResidentProfile profile, CivicPlacement civic) {
        Candidate(Property property, String name, NavGraph graph, ResidentProfile profile) {
            this(property, name, graph, profile, null);
        }
        Candidate {
            if ((property == null) == (civic == null)) throw new IllegalArgumentException("Exactly one resident binding required");
        }
        UUID worldId() { return property != null ? property.worldUuid() : civic.worldUuid(); }
        String district() { return property != null ? property.regionId() : civic.districtId(); }
        Candidate withProfile(ResidentProfile value) { return new Candidate(property, name, graph, value, civic); }
    }
    record Snapshot(Map<UUID, Candidate> candidates, Map<GraphKey, NavGraph> graphs, Set<GraphKey> checked) {
        Snapshot {
            candidates = Map.copyOf(candidates);
            graphs = Map.copyOf(graphs);
            checked = Set.copyOf(checked);
        }

        Snapshot without(GraphKey changed) {
            Map<UUID, Candidate> keptCandidates = new HashMap<>(candidates);
            keptCandidates.values().removeIf(c -> changed.equals(
                    new GraphKey(c.worldId(), c.district())));
            Map<GraphKey, NavGraph> keptGraphs = new HashMap<>(graphs);
            keptGraphs.remove(changed);
            Set<GraphKey> keptChecked = new java.util.HashSet<>(checked);
            keptChecked.remove(changed);
            return new Snapshot(keptCandidates, keptGraphs, keptChecked);
        }
    }

    /** Main-thread graph binding. World references never enter the asynchronous snapshot. */
    private static final class GraphView {
        NavGraph graph;
        World world;
        GraphKey key;
    }

    /** What we need to (re)spawn + walk one tenant avatar. */
    private static final class Ctx {
        Property property;
        CivicPlacement civic;
        GraphView view;
        String name;
        Location spawn;
        ResidentProfile profile;
    }

    /** Live state of one rendered avatar. */
    static final class Render {
        NPC npc;
        String appliedKey; // skin the avatar wears; null for the unskinned debug avatar
        long outfitRetryMs; // after a failed outfit spawn, no new try before this
        GraphView view;
        List<Long> path; // node ids; heading toward path.get(pathIdx)
        int pathIdx;
        final RenderCadence.EdgeRetry edgeRetry = new RenderCadence.EdgeRetry();
        long lastDesiredMs;
        long edgeStartMs;
        long expiresMs;
        UUID propertyId;
        int ownershipEpoch;
        long civicRevision;
        boolean debug;
        boolean encounterPreview;
        UUID previewOwner;
        long pausedUntilMs;
        final ConversationPause conversationPause;
        boolean pausedWalk;
        ResidentProfile profile;
        ResidentRoutine.Intent intent;
        ResidentRoutePlanner.Decision route;
        long nextPlanMs;
        GraphKey previewGraph;
        boolean walkingHome; // premium relocation walk in progress; ends on arrival, timeout or any failure
        long walkDeadlineMs;
        String walkHomeTag;
        boolean walkPlanned; // the HOME route was planned; only then may arrival end the walk

        /** The pause window lives as long as this avatar's Render; swap r.npc in place to keep it. */
        Render(ConversationPause.Policy policy) { conversationPause = new ConversationPause(policy); }
    }

    private final Map<UUID, PremiumWalk.Move> pendingWalks = new HashMap<>();
    private final Map<UUID, Long> pendingWalkExpiry = new HashMap<>();
    private final ProsperityNpcPlugin plugin;
    private final PropertyService properties;
    private final TenantRepository tenants;
    private final NavGraphRepository navGraphs;
    private final CitizensAdapter citizens;
    private final ResidentRepository residents;
    private final ResidentAppearanceCatalogue appearances;
    private final CivicPlacementRepository civicPlacements;
    private final ConversationPause.Policy pausePolicy;

    private final Map<UUID, Render> rendered = new HashMap<>();
    private final Map<UUID, Long> retryAfter = new HashMap<>();
    private final ReadCache<Snapshot> snapshots = new ReadCache<>();
    private final PreviewRequests previewRequests = new PreviewRequests();
    private BukkitTask task;
    private RenderCadence cadence;
    private boolean stopped;
    private boolean enabledLastTick;
    private long lastWarningMs;
    private long nextDebugMs;
    private long nextRenderMs;
    private GraphKey requestedDebugGraph;
    private Set<UUID> requestedWorldScope = Set.of();
    private Predicate<UUID> conversationProbe;
    private final long startedNanos = System.nanoTime();
    private final PlanBudget planBudget = new PlanBudget();

    public RenderManager(ProsperityNpcPlugin plugin, PropertyService properties, TenantRepository tenants,
                         NavGraphRepository navGraphs, CitizensAdapter citizens, ResidentRepository residents,
                         ResidentAppearanceCatalogue appearances, CivicPlacementRepository civicPlacements) {
        this.plugin = plugin;
        plugin.trackDatabaseWorker(snapshots.executor);
        this.properties = properties;
        this.tenants = tenants;
        this.navGraphs = navGraphs;
        this.citizens = citizens;
        this.residents = residents;
        this.appearances = java.util.Objects.requireNonNull(appearances);
        this.civicPlacements = java.util.Objects.requireNonNull(civicPlacements);
        // Read once, like the session idle limit: restart after changing. Not re-read on /npcadmin reload.
        this.pausePolicy = ConversationPause.Policy.of(
                plugin.getConfig().getBoolean("ux.conversation-sliding-deadline", false),
                plugin.getConfig().getInt("ux.conversation-pause-cap-seconds", 180),
                plugin.getConfig().getInt("ux.conversation-pause-cooldown-seconds", 60));
        if (pausePolicy.renews()) plugin.getLogger().info("Longer conversations on: resident pause cap "
                + pausePolicy.capMs() / 1_000 + " s, cooldown " + pausePolicy.cooldownMs() / 1_000 + " s.");
    }

    /** ux.conversation-sliding-deadline as read at startup. The encounter controller reads it from here only. */
    public boolean longerConversations() { return pausePolicy.renews(); }

    /**
     * Main thread. Set by the encounter controller after it builds its sessions: true while a player has a chat open
     * with that resident. Until it is set (or after null) every resident counts as in a chat, so no outfit swaps.
     */
    public void setConversationProbe(Predicate<UUID> probe) {
        CitizensAdapter.requireMainThread();
        conversationProbe = probe;
    }

    public void start() {
        CitizensAdapter.requireMainThread();
        if (task != null || stopped) {
            return;
        }
        cadence = new RenderCadence(plugin.getConfig().getLong("rendering.tick-interval-ticks", 20L));
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        plugin.getLogger().info("Render manager started (Citizens present).");
    }

    public void stop() {
        CitizensAdapter.requireMainThread();
        stopped = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
        snapshots.close();
        previewRequests.clear();
        retryAfter.clear();
        despawnAll();
        citizens.destroyAll();
    }

    /** Staff civic changes are cosmetic; invalidate even after an uncertain SQL outcome. */
    public void civicChanged(UUID resident) {
        CitizensAdapter.requireMainThread();
        if (stopped) return;
        snapshots.invalidate();
        Render active = rendered.get(resident);
        if (active != null && (!active.debug || active.encounterPreview)) {
            citizens.despawn(active.npc);
            rendered.remove(resident);
        }
        previewRequests.snapshot().stream().filter(request -> request.profile().id().equals(resident))
                .forEach(previewRequests::remove);
        retryAfter.remove(resident);
        requestSnapshot(now(), Set.of(), null);
    }

    /** Called on the main thread after publication, including an uncertain commit outcome. */
    public void routesChanged(UUID worldId, String region) {
        CitizensAdapter.requireMainThread();
        if (stopped) return;
        GraphKey changed = new GraphKey(worldId, region);
        snapshots.invalidate(snapshot -> snapshot.without(changed));
        for (Iterator<Map.Entry<UUID, Render>> it = rendered.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Render> entry = it.next();
            Render render = entry.getValue();
            if (render.view != null && changed.equals(render.view.key)) {
                citizens.despawn(render.npc);
                retryAfter.remove(entry.getKey());
                it.remove();
            }
        }
        requestSnapshot(now(), Set.of(worldId), changed);
    }

    public Set<UUID> activeResidentIds() {
        CitizensAdapter.requireMainThread();
        return rendered.entrySet().stream().filter(e -> !e.getValue().debug || e.getValue().encounterPreview)
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Stable identity hook only. Interaction/schedules remain owned by the resident layer. */
    public Optional<NPC> avatarForResident(UUID residentId) {
        CitizensAdapter.requireMainThread();
        Render render = rendered.get(residentId);
        return render == null || (render.debug && !render.encounterPreview) || citizens.location(render.npc) == null
                ? Optional.empty() : Optional.of(render.npc);
    }

    public boolean isAuthoritativeAvatar(UUID residentId, UUID avatarId, UUID worldId) {
        CitizensAdapter.requireMainThread();
        Render render = rendered.get(residentId);
        if (render == null || render.debug || !routableResidents(worldId).contains(residentId)) return false;
        Location position = citizens.location(render.npc);
        return position != null && position.getWorld().getUID().equals(worldId)
                && render.npc.getEntity().getUniqueId().equals(avatarId);
    }

    public Set<UUID> routableResidents(UUID worldId) {
        CitizensAdapter.requireMainThread();
        Snapshot snapshot = snapshots.value(now());
        if (stopped || snapshot == null) return Set.of();
        Map<UUID, Property> current = new HashMap<>();
        properties.getEnrolled().forEach(property -> current.put(property.propertyId(), property));
        return snapshot.candidates().entrySet().stream()
                .filter(entry -> worldId.equals(entry.getValue().worldId()) && bindingCurrent(entry.getValue(), current))
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** An authored public meeting point, never a live home/private-work location. */
    public Optional<Location> meetingPoint(UUID residentId, UUID worldId) {
        CitizensAdapter.requireMainThread();
        if (!routableResidents(worldId).contains(residentId)) return Optional.empty();
        Candidate candidate = snapshots.value(now()).candidates().get(residentId);
        World world = Bukkit.getWorld(worldId);
        if (candidate == null || world == null) return Optional.empty();
        return candidate.graph().nodes().stream().filter(node -> node.hasTag("market"))
                .min(Comparator.comparingLong(NavGraph.Node::id))
                .map(node -> new Location(world, node.x() + .5, node.y(), node.z() + .5));
    }

    public Set<String> publicStops(UUID residentId, UUID worldId) {
        CitizensAdapter.requireMainThread();
        if (!routableResidents(worldId).contains(residentId)) return Set.of();
        Candidate candidate = snapshots.value(now()).candidates().get(residentId);
        return Set.of("market", "leisure").stream().filter(tag -> candidate.graph().nodes().stream()
                .anyMatch(node -> node.hasTag(tag))).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Optional<Location> publicStop(UUID residentId, UUID worldId, String tag) {
        CitizensAdapter.requireMainThread();
        if (!publicStops(residentId, worldId).contains(tag)) return Optional.empty();
        Candidate candidate = snapshots.value(now()).candidates().get(residentId);
        World world = Bukkit.getWorld(worldId);
        if (world == null) return Optional.empty();
        return candidate.graph().nodes().stream().filter(node -> node.hasTag(tag))
                .min(Comparator.comparingLong(NavGraph.Node::id))
                .map(node -> new Location(world, node.x() + .5, node.y(), node.z() + .5));
    }

    /** Numeric ids are registry-local; the caller must first establish registry ownership. */
    public Optional<UUID> resolveTenantId(int citizensId) {
        CitizensAdapter.requireMainThread();
        return rendered.entrySet().stream().filter(e -> !e.getValue().debug || e.getValue().encounterPreview)
                .filter(e -> e.getValue().npc.getId() == citizensId
                        && citizens.location(e.getValue().npc) != null)
                .map(Map.Entry::getKey).findFirst();
    }

    /** Preferred for Citizens click events: an unrelated registry can reuse a numeric id. */
    public Optional<UUID> resolveTenantId(NPC clicked) {
        CitizensAdapter.requireMainThread();
        return rendered.entrySet().stream()
                .filter(e -> (!e.getValue().debug || e.getValue().encounterPreview) && e.getValue().npc == clicked)
                .filter(e -> citizens.location(e.getValue().npc) != null)
                .map(Map.Entry::getKey).findFirst();
    }

    /** DEV-only authored cast preview; shares the real registry, identity mapping and global cap. */
    public String previewResident(Player owner, ResidentProfile profile, String district) {
        CitizensAdapter.requireMainThread();
        if (stopped || !previewEnabled() || !owner.isOnline() || !owner.hasPermission("prosperitynpc.admin")) {
            return "Resident previews are unavailable.";
        }
        if (district != null && !district.matches("[a-zA-Z0-9_-]{1,64}")) return "Invalid route district.";
        long now = now();
        Location origin = owner.getLocation();
        PreviewRequests.Request request = new PreviewRequests.Request(owner.getUniqueId(), profile,
                owner.getWorld().getUID(), CitizensAdapter.vec(origin), district, now + PreviewRequests.TIMEOUT_MS);
        PreviewRequests.Admission admitted = previewRequests.offer(request, rendered.keySet(),
                (int) rendered.values().stream().filter(r -> r.encounterPreview).count(), serverCap());
        if (admitted == PreviewRequests.Admission.DUPLICATE) return "That resident already has an active or pending visit.";
        if (admitted == PreviewRequests.Admission.FULL) return "Preview limit reached. Dismiss a visit first.";
        if (district != null) requestSnapshot(now, Set.of(request.world()), new GraphKey(request.world(), district));
        String result = completePreview(request, owner, now);
        if (result != null) {
            previewRequests.remove(request);
            return result;
        }
        return "Preparing " + profile.name() + "'s visit. Stay here; no repeat command is needed.";
    }

    /** Null means still waiting; any message is terminal and removes this reservation. */
    private String completePreview(PreviewRequests.Request request, Player owner, long now) {
        boolean allowed = !stopped && previewEnabled() && owner != null && owner.isOnline()
                && owner.hasPermission("prosperitynpc.admin");
        Location current = allowed ? owner.getLocation() : null;
        PreviewRequests.Eligibility eligibility = PreviewRequests.eligible(request, now, allowed,
                current == null ? null : current.getWorld().getUID(), current == null ? null : CitizensAdapter.vec(current));
        if (eligibility == PreviewRequests.Eligibility.REVOKED) return "Resident preview cancelled: access is unavailable.";
        if (eligibility == PreviewRequests.Eligibility.EXPIRED) return "The visit could not be prepared in time. Please try again later.";
        if (eligibility == PreviewRequests.Eligibility.MOVED) return "Preview cancelled because you moved. Request it again where you want to meet.";
        UUID residentId = request.profile().id();
        String name = request.profile().name();
        if (rendered.containsKey(residentId)) return "That resident already has an active avatar.";
        if (rendered.size() >= serverCap()
                || rendered.values().stream().filter(r -> r.encounterPreview).count() >= PreviewRequests.LIMIT)
            return "Preview limit reached. Dismiss a visit first.";
        if (now < nextDebugMs) return null;
        Location at = owner.getLocation();
        GraphView routeView = null;
        GraphKey graphKey = null;
        if (request.district() != null) {
            graphKey = new GraphKey(request.world(), request.district());
            Snapshot snapshot = snapshots.value(now);
            if (snapshots.failure() != null) return "Route lookup is unavailable. Please try again later.";
            if (snapshot == null || !snapshot.checked().contains(graphKey))
                return null;
            NavGraph graph = snapshot.graphs().get(graphKey);
            if (graph == null) return "No usable published route for that district in this world.";
            routeView = view(graph, owner.getWorld(), graphKey);
            at = spawnLocation(routeView, Map.of(owner.getUniqueId().toString(), CitizensAdapter.vec(at)), 3);
            if (at == null) return "Stand within three blocks of a clear published route node.";
        }
        at.setYaw(at.getYaw() + 180f);
        // No search, teleport, chunk loading or world modification. Staff chooses a clear position.
        nextDebugMs = now + 2_000;
        var appearance = appearances.forSpawn(request.profile(), at.getWorld().getTime());
        if (appearance.applied().isEmpty()) return "Resident appearance unavailable: a verified base outfit is required.";
        NPC npc = citizens.spawnAuthored(name + " [NPC]", at, appearance.applied().orElseThrow());
        if (npc == null) return "Preview spawn refused: " + citizens.lastFailure();
        Render r = new Render(pausePolicy);
        r.npc = npc;
        r.appliedKey = appearance.applied().orElseThrow().assetKey();
        r.debug = true;
        r.encounterPreview = true;
        r.previewOwner = owner.getUniqueId();
        r.profile = request.profile();
        r.view = routeView;
        r.previewGraph = graphKey;
        r.lastDesiredMs = now;
        r.expiresMs = now + 600_000;
        rendered.put(residentId, r);
        return name + " preview is present for up to 10 minutes. "
                + (routeView == null ? "Standing visit; no route connected." : "Following the published district routine.")
                + " Appearance is not final.";
    }

    public void dismissPreviews(UUID owner) {
        CitizensAdapter.requireMainThread();
        previewRequests.cancel(owner);
        for (Iterator<Render> it = rendered.values().iterator(); it.hasNext();) {
            Render r = it.next();
            if (r.encounterPreview && r.previewOwner.equals(owner)) {
                citizens.despawn(r.npc);
                it.remove();
            }
        }
    }

    /** Supersede a still-loading visit before the next asynchronous profile lookup starts. */
    public void cancelPendingPreview(UUID owner) {
        CitizensAdapter.requireMainThread();
        previewRequests.cancel(owner);
    }

    /**
     * Reading a native form must not make the resident walk out of authorization range. With longer conversations
     * on, this is also the renewal entry point for admitted choices and actions, up to the shared cap. A premium
     * relocation walk never renews, so its 10 minute walk deadline keeps today's margin.
     */
    public void acknowledge(UUID residentId, Player visitor) {
        CitizensAdapter.requireMainThread();
        Render r = rendered.get(residentId);
        Location at = r == null ? null : citizens.location(r.npc);
        if (at == null || at.getWorld() != visitor.getWorld() || at.distanceSquared(visitor.getLocation()) > 25) return;
        long now = now();
        long deadline = r.conversationPause.acquire(visitor.getUniqueId(), now, !r.walkingHome);
        if (deadline <= now) return;
        if (now >= r.pausedUntilMs) {
            r.pausedWalk = r.path != null && citizens.isNavigating(r.npc);
            if (!citizens.cancel(r.npc)) return;
        }
        r.pausedUntilMs = deadline;
        r.edgeStartMs = deadline;
        citizens.face(r.npc, visitor.getEyeLocation());
    }

    /**
     * Main thread. True only while a walking resident is stopped for a conversation and that stop can no longer be
     * extended. Never true for a standing resident or with longer conversations off.
     */
    public boolean pauseEndingSoon(UUID residentId) {
        CitizensAdapter.requireMainThread();
        Render r = rendered.get(residentId);
        long now = now();
        return r != null && r.pausedWalk && now < r.pausedUntilMs && r.conversationPause.capReached(now, !r.walkingHome);
    }

    public void releaseConversation(UUID visitor) {
        CitizensAdapter.requireMainThread();
        long now = now();
        for (Render r : rendered.values()) {
            if (r.conversationPause.release(visitor) && r.pausedUntilMs > now) r.pausedUntilMs = now;
        }
    }

    private boolean previewEnabled() {
        return plugin.getConfig().getBoolean("beta.resident-lab-enabled", false)
                && plugin.getConfig().getBoolean("beta.encounters-enabled", false);
    }

    public Optional<ResidentActivity> activity(UUID residentId) {
        CitizensAdapter.requireMainThread();
        Render r = rendered.get(residentId);
        Location loc = r == null ? null : citizens.location(r.npc);
        if (r == null || r.profile == null || !CitizensAdapter.loaded(loc)) return Optional.empty();
        var intended = ResidentRoutine.at(r.profile, loc.getWorld().getTime()).activity();
        boolean atStop = r.view != null && loc.getWorld() == r.view.world
                && atDestination(r.view.graph, r.route, CitizensAdapter.vec(loc));
        ResidentActivity.State state = ResidentActivity.observed(r.view != null,
                r.path != null && citizens.isNavigating(r.npc),
                r.pausedWalk, now() < r.pausedUntilMs,
                atStop);
        return Optional.of(new ResidentActivity(state, r.intent == null ? intended : r.intent.activity()));
    }

    /**
     * Debug: force-spawns one avatar at a node of the region's ACTIVE graph
     * (bypassing the viewer gate), walks it one edge, and despawns it after ~60s.
     * Reports the attempted lifecycle only, not visual or Bedrock acceptance.
     * A cold graph is queued asynchronously; repeat the command after it warms.
     */
    public String renderTest(World world, String region) {
        CitizensAdapter.requireMainThread();
        NPC npc = null;
        try {
            long now = now();
            if (stopped || world == null || Bukkit.getWorld(world.getUID()) != world) {
                return "renderer stopped or world unloaded";
            }
            if (now < nextDebugMs || rendered.values().stream().anyMatch(r -> r.debug)) {
                return "debug avatar already active or cooling down";
            }
            if (rendered.size() >= serverCap()) {
                return "render cap reached";
            }
            GraphKey key = new GraphKey(world.getUID(), region);
            requestSnapshot(now, Set.of(world.getUID()), key);
            Snapshot snapshot = snapshots.value(now);
            if (snapshot == null || !snapshot.checked().contains(key)) {
                if (snapshots.failure() != null) {
                    return snapshots.failure();
                }
                return "graph unavailable/cold; asynchronous read requested (30s refresh limit)";
            }
            NavGraph g = snapshot.graphs().get(key);
            if (g == null || g.nodeCount() == 0) {
                return "no ACTIVE nav graph for " + region;
            }
            GraphView view = view(g, world, key);
            Location spawn = spawnLocation(view, Map.of(), Double.POSITIVE_INFINITY);
            if (spawn == null) {
                return "no loaded, clear graph node (no chunks loaded)";
            }
            long startNode = RenderGeometry.nearest(g, CitizensAdapter.vec(spawn));
            NavGraph.Node sn = g.node(startNode);
            npc = citizens.spawn("NpcRenderTest", spawn);
            nextDebugMs = now + RETRY_MS;
            if (npc == null) {
                return "SPAWN FAILED: " + citizens.lastFailure();
            }
            Render render = new Render(pausePolicy);
            render.npc = npc;
            render.view = view;
            render.debug = true;
            render.expiresMs = now + 60_000;
            render.edgeStartMs = now;
            render.lastDesiredMs = now;
            Optional<Long> goal = g.neighbors(startNode).stream().map(NavGraph.Edge::to).sorted()
                    .filter(id -> RenderGeometry.edge(g, startNode, id)).findFirst();
            if (goal.isPresent()) {
                render.path = List.of(startNode, goal.get());
                render.pathIdx = 1;
                if (!citizens.walkSegment(npc, spawn, nodeLocation(view, goal.get()))) {
                    citizens.despawn(npc);
                    return "WALK FAILED: " + citizens.lastFailure();
                }
            }
            rendered.put(UUID.randomUUID(), render);
            return "spawned 'NpcRenderTest' at " + sn.x() + "," + sn.y() + "," + sn.z()
                    + (goal.isPresent() ? " walking one edge" : " idle (no adjacent edge)")
                    + "; tracked cleanup within ~60s; not visual acceptance.";
        } catch (RuntimeException | LinkageError t) {
            citizens.despawn(npc);
            return "render error: " + t.getClass().getSimpleName();
        }
    }

    private void tick() {
        try {
            long time = now();
            runFrame(cadence, this::updateEnabled, () -> maintain(time), () -> advance(time));
        } catch (RuntimeException | LinkageError t) {
            despawnAll();
            long now = now();
            nextRenderMs = now + RETRY_MS;
            warn(now, "tick: " + t.getClass().getSimpleName());
        }
    }

    static void runFrame(RenderCadence cadence, Runnable gates, Runnable maintenance, Runnable traversal) {
        gates.run();
        if (cadence.maintenanceDue()) maintenance.run();
        traversal.run();
    }

    private void advance(long time) {
        Set<UUID> eligible = rendered.entrySet().stream().filter(e -> wantsPlan(e.getValue(), time))
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        planBudget.begin(eligible,time);
        updateDebugs(time);
        if (!enabledLastTick || time < nextRenderMs) return;
        if (plugin.getConfig().getBoolean("appearance.outfit-change-while-rendered", false)) {
            changeOutfits(rendered, outfitHost(), OutfitChangePolicy.changesPerTick(plugin.getConfig().getInt(
                    "appearance.outfit-changes-per-tick", OutfitChangePolicy.DEFAULT_CHANGES_PER_TICK)),
                    OutfitChangePolicy.hiddenRadius(plugin.getConfig().getDouble("appearance.outfit-change-hidden-radius",
                            OutfitChangePolicy.DEFAULT_HIDDEN_RADIUS), plugin.getConfig().getDouble("rendering.radius", 48.0)),
                    time);
        }
        int cap = serverCap();
        for (Iterator<Map.Entry<UUID, Render>> it = rendered.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, Render> entry = it.next();
            Render r = entry.getValue();
            if (r.debug) continue;
            if (rendered.size() > cap || snapshots.value(time) == null
                    || !walk(entry.getKey(), r, time, edgeTimeout(), maxFails())) {
                citizens.despawn(r.npc);
                retryAfter.put(entry.getKey(), time + RETRY_MS);
                it.remove();
            }
        }
    }

    /** The live world behind {@link #changeOutfits}. Main thread only. */
    interface OutfitHost {
        ResidentAppearanceCatalogue.Selection select(Render r);
        /** Blocks to the closest online player in the avatar's world; +infinity for none, NaN when unknown. */
        double nearestViewer(Render r);
        boolean conversationOpen(UUID resident);
        /** A new authored avatar where the old one stands, or null. Must not touch the old avatar. */
        NPC spawnReplacement(Render r, ResidentAppearanceCatalogue.Applied applied);
        void despawn(NPC npc);
        /** The replacement is not walking yet: issue the edge the old avatar was on, if any. */
        void resumeWalk(Render r, long now);
    }

    /**
     * Outfit swaps for avatars already on screen. Ordinary residents only: never debug avatars, staff previews or a
     * premium walk home. The new avatar is spawned first; only then does this same Render take it and the old one
     * go. Nothing else on the Render changes, so the conversation pause window and cooldown, path, ownership binding
     * and route state all carry over. A failed spawn keeps the old avatar and waits RETRY_MS. At most
     * {@code maxPerPass} tries per pass. Returns the number of avatars changed.
     */
    static int changeOutfits(Map<UUID, Render> rendered, OutfitHost host, int maxPerPass, double hiddenRadius, long now) {
        int tries = 0;
        int changed = 0;
        for (UUID resident : rendered.keySet().stream().sorted().toList()) {
            if (tries >= maxPerPass) break;
            Render r = rendered.get(resident);
            if (r == null || r.debug || r.encounterPreview || r.walkingHome || r.profile == null
                    || r.appliedKey == null || now < r.outfitRetryMs) continue;
            var selection = host.select(r);
            // Most passes find the right skin already on; skip the player scan for those.
            if (!OutfitChangePolicy.wantsChange(r.appliedKey, selection)) continue;
            boolean talking = now < r.pausedUntilMs || host.conversationOpen(resident);
            var decision = OutfitChangePolicy.decide(r.appliedKey, selection, talking ? 0 : host.nearestViewer(r),
                    talking, hiddenRadius);
            if (decision != OutfitChangePolicy.Decision.CHANGE) continue;
            tries++;
            var applied = selection.applied().orElseThrow();
            NPC replacement = host.spawnReplacement(r, applied);
            if (replacement == null) {
                r.outfitRetryMs = now + RETRY_MS;
                continue;
            }
            NPC old = r.npc;
            r.npc = replacement;
            r.appliedKey = applied.assetKey();
            host.despawn(old);
            host.resumeWalk(r, now);
            changed++;
        }
        return changed;
    }

    private OutfitHost outfitHost() {
        return new OutfitHost() {
            @Override public ResidentAppearanceCatalogue.Selection select(Render r) {
                Location at = citizens.location(r.npc);
                return at == null ? null : appearances.forSpawn(r.profile, at.getWorld().getTime());
            }
            @Override public double nearestViewer(Render r) {
                Location at = citizens.location(r.npc);
                if (!CitizensAdapter.loaded(at)) return Double.NaN;
                double nearest = Double.POSITIVE_INFINITY;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getWorld() == at.getWorld()) nearest = Math.min(nearest, p.getLocation().distance(at));
                }
                return nearest;
            }
            @Override public boolean conversationOpen(UUID resident) {
                Predicate<UUID> probe = conversationProbe;
                return probe == null || probe.test(resident);
            }
            @Override public NPC spawnReplacement(Render r, ResidentAppearanceCatalogue.Applied applied) {
                Location at = citizens.location(r.npc);
                if (!CitizensAdapter.loaded(at) || r.view == null || at.getWorld() != r.view.world) return null;
                NPC npc = citizens.spawnAuthored(r.profile.name(), at, applied);
                if (npc == null) warn(now(), citizens.lastFailure());
                return npc;
            }
            @Override public void despawn(NPC npc) { citizens.despawn(npc); }
            @Override public void resumeWalk(Render r, long now) {
                if (r.path != null && r.pathIdx > 0 && r.pathIdx < r.path.size() && now >= r.pausedUntilMs)
                    issueEdge(r, now, maxFails());
            }
        };
    }

    private void updateEnabled() {
        // Gate: rendering must be explicitly enabled AND the system on. Off -> clear avatars.
        if (!plugin.killSwitches().isSystemEnabled() || !plugin.killSwitches().isRenderingEnabled()) {
            if (enabledLastTick) {
                despawnAll();
                snapshots.invalidate();
            }
            enabledLastTick = false;
            return;
        }
        enabledLastTick = true;
    }

    private void maintain(long now) {
        // requestSnapshot gathers every active preview key and viewer world once, not once per avatar.
        if (snapshots.shouldRefresh((enabledLastTick && now >= nextRenderMs)
                || !previewRequests.isEmpty()
                || rendered.values().stream().anyMatch(r -> r.previewGraph != null)))
            requestSnapshot(now, Set.of(), null);
        for (PreviewRequests.Request request : previewRequests.snapshot()) {
            Player owner = Bukkit.getPlayer(request.owner());
            String result = completePreview(request, owner, now);
            if (result != null && previewRequests.remove(request) && owner != null && owner.isOnline())
                owner.sendMessage(net.kyori.adventure.text.Component.text(result,
                        net.kyori.adventure.text.format.NamedTextColor.GRAY));
        }
        if (!enabledLastTick || now < nextRenderMs) return;
        double radius = plugin.getConfig().getDouble("rendering.radius", 48.0);
        int maxServer = serverCap();
        int maxPerViewer = Math.clamp(plugin.getConfig().getInt("rendering.max-per-viewer", 3), 0, maxServer);
        long grace = Math.clamp(plugin.getConfig().getLong("rendering.despawn-grace-ms", 15_000L), 0, 60_000L);
        retryAfter.entrySet().removeIf(e -> now >= e.getValue());

        // Viewers grouped by world (proximity is only meaningful within a world).
        Map<String, Map<String, RenderPolicy.Vec>> viewersByWorld = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            viewersByWorld.computeIfAbsent(l.getWorld().getUID().toString(), k -> new HashMap<>())
                    .put(p.getUniqueId().toString(), new RenderPolicy.Vec(l.getX(), l.getY(), l.getZ()));
        }

        // Tenancies and civic placements share one identity-keyed render set.
        Map<String, Map<String, RenderPolicy.Vec>> candidatesByWorld = new HashMap<>();
        Map<UUID, Ctx> ctxById = new HashMap<>();
        Snapshot snapshot = snapshots.value(now);
        Map<UUID, Property> current = new HashMap<>();
        properties.getEnrolled().forEach(p -> current.put(p.propertyId(), p));
        if (snapshot != null) {
            Map<GraphKey, Ctx> contexts = new HashMap<>();
            for (Map.Entry<UUID, Candidate> entry : snapshot.candidates().entrySet()) {
                Candidate candidate = entry.getValue();
                Property prop = candidate.property();
                if (!bindingCurrent(candidate, current) || retryAfter.containsKey(entry.getKey())) {
                    continue;
                }
                World world = Bukkit.getWorld(candidate.worldId());
                Map<String, RenderPolicy.Vec> viewers = viewersByWorld.getOrDefault(candidate.worldId().toString(), Map.of());
                if (world == null || viewers.isEmpty()) {
                    continue;
                }
                GraphKey key = new GraphKey(candidate.worldId(), candidate.district());
                Ctx shared = contexts.computeIfAbsent(key, ignored -> {
                    Ctx ctx = new Ctx();
                    ctx.view = view(candidate.graph(), world, key);
                    ctx.spawn = spawnLocation(ctx.view, viewers, radius);
                    return ctx;
                });
                if (shared.spawn == null) {
                    continue;
                }
                Ctx ctx = new Ctx();
                ctx.property = prop;
                ctx.civic = candidate.civic();
                ctx.view = shared.view;
                ctx.spawn = shared.spawn;
                ctx.name = candidate.name();
                ctx.profile = candidate.profile();
                Render active = rendered.get(entry.getKey());
                Location position = active == null ? ctx.spawn : citizens.location(active.npc);
                if (!CitizensAdapter.loaded(position) || position.getWorld() != world) {
                    continue;
                }
                candidatesByWorld.computeIfAbsent(world.getUID().toString(), k -> new HashMap<>())
                        .put(entry.getKey().toString(), CitizensAdapter.vec(position));
                ctxById.put(entry.getKey(), ctx);
            }
        }

        // Desired render set = per-world selection, unioned (hard global cap enforced at spawn).
        Set<String> desired = new LinkedHashSet<>();
        rendered.entrySet().stream().filter(e -> e.getValue().debug).map(e -> e.getKey().toString())
                .sorted().forEach(desired::add);
        desired.addAll(RenderPolicy.selectWorlds(viewersByWorld, candidatesByWorld, radius,
                maxPerViewer, Math.max(0, maxServer - desired.size())));
        Map<String, Long> lastDesired = new HashMap<>();
        rendered.forEach((id, render) -> lastDesired.put(id.toString(), render.lastDesiredMs));
        Set<String> retained = RenderPolicy.retain(lastDesired, desired, now, grace, maxServer);

        // Despawn: rendered avatars no longer desired for longer than the grace window.
        for (Iterator<Map.Entry<UUID, Render>> it = rendered.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Render> e = it.next();
            Render r = e.getValue();
            Candidate candidate = snapshot == null ? null : snapshot.candidates().get(e.getKey());
            boolean invalid = !r.debug && (candidate == null
                    || !bindingCurrent(candidate, current)
                    || !matchesBinding(candidate, r.propertyId, r.ownershipEpoch, r.civicRevision)
                    || !candidate.worldId().equals(r.view.key.worldId())
                    || !candidate.district().equalsIgnoreCase(r.view.key.region())
                    || !sameGraph(candidate.graph(), r.view.graph));
            if (invalid && adoptPremiumWalk(e.getKey(), r, candidate, current, now)) invalid = false;
            if (invalid || !retained.contains(e.getKey().toString())) {
                citizens.despawn(r.npc);
                it.remove();
            } else if (desired.contains(e.getKey().toString())) {
                r.lastDesiredMs = now;
            }
            if (!invalid && !r.debug) {
                r.view.graph = candidate.graph();
                r.profile = candidate.profile();
            }
        }
        pendingWalkExpiry.entrySet().removeIf(x -> {
            if (now < x.getValue() && pendingWalks.containsKey(x.getKey())) return false;
            pendingWalks.remove(x.getKey());
            return true;
        });

        // Spawn: desired avatars not yet rendered, up to the global cap.
        for (String id : desired) {
            UUID tenantId = UUID.fromString(id);
            if (rendered.size() >= maxServer || retryAfter.size() >= MAX_CANDIDATES || citizens.cleanupFailed()) {
                break;
            }
            if (rendered.containsKey(tenantId)) {
                continue;
            }
            Ctx ctx = ctxById.get(tenantId);
            if (ctx == null || ctx.view.graph.nodeCount() == 0) {
                continue;
            }
            if (ctx.profile == null) {
                retryAfter.put(tenantId, now + RETRY_MS);
                warn(now, "resident profile unavailable; authored avatar held");
                continue;
            }
            var appearance = appearances.forSpawn(ctx.profile, ctx.spawn.getWorld().getTime());
            if (appearance.applied().isEmpty()) {
                retryAfter.put(tenantId, now + RETRY_MS);
                warn(now, "verified base outfit unavailable; authored avatar held");
                continue;
            }
            NPC npc = citizens.spawnAuthored(ctx.name, ctx.spawn, appearance.applied().orElseThrow());
            if (npc == null) {
                retryAfter.put(tenantId, now + RETRY_MS);
                warn(now, citizens.lastFailure());
                continue;
            }
            Render r = new Render(pausePolicy);
            r.npc = npc;
            r.appliedKey = appearance.applied().orElseThrow().assetKey();
            r.view = ctx.view;
            r.lastDesiredMs = now;
            r.propertyId = ctx.property == null ? null : ctx.property.propertyId();
            r.ownershipEpoch = ctx.property == null ? 0 : ctx.property.ownershipEpoch();
            r.civicRevision = ctx.civic == null ? 0 : ctx.civic.revision();
            r.profile = ctx.profile;
            rendered.put(tenantId, r);
        }
    }

    private boolean bindingCurrent(Candidate candidate, Map<UUID, Property> current) {
        Property captured = candidate.property();
        if (captured == null) return plugin.getConfig().getBoolean("town.enabled", false) && candidate.civic().enabled();
        Property live = current.get(captured.propertyId());
        return live != null && live.enabled() && live.ownershipEpoch() == captured.ownershipEpoch()
                && live.worldUuid().equals(captured.worldUuid()) && live.regionId().equals(captured.regionId())
                && !properties.isDisplayUnavailable(captured.propertyId());
    }

    static boolean matchesBinding(Candidate candidate, UUID propertyId, int epoch, long civicRevision) {
        return candidate.property() == null
                ? propertyId == null && candidate.civic().revision() == civicRevision
                : candidate.property().propertyId().equals(propertyId) && candidate.property().ownershipEpoch() == epoch;
    }

    /**
     * Main thread, after a premium relocation commits. Remembered briefly so a rendered avatar can walk to the new
     * home when the display snapshot catches up. Off by default; a lost or expired note only means the old respawn.
     */
    public void premiumRelocated(List<PremiumWalk.Move> moves) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required");
        if (!Boolean.TRUE.equals(plugin.getConfig().get("housing.premium-walk-enabled"))
                || !Boolean.TRUE.equals(plugin.getConfig().get("housing.premium-enabled"))) return;
        long now = now();
        for (PremiumWalk.Move move : moves) {
            if (pendingWalks.size() >= PremiumWalk.MAX_PENDING && !pendingWalks.containsKey(move.residentId())) break;
            pendingWalks.put(move.residentId(), move);
            pendingWalkExpiry.put(move.residentId(), now + PremiumWalk.WINDOW_MS);
        }
    }

    /**
     * Keeps a rendered avatar through a premium move when both homes share one published graph and a home route
     * exists. Never teleports: the avatar is rebound where it stands and walks edge by edge. Any doubt returns false
     * and the caller despawns it, so the resident reappears at the new home as before.
     */
    private boolean adoptPremiumWalk(UUID residentId, Render r, Candidate candidate, Map<UUID, Property> current, long now) {
        PremiumWalk.Move move = pendingWalks.get(residentId);
        if (move == null || r.debug || candidate == null || candidate.property() == null || r.propertyId == null) return false;
        if (!r.propertyId.equals(move.oldPropertyId()) || !candidate.property().propertyId().equals(move.newPropertyId())
                || !bindingCurrent(candidate, current)) return false;
        Location loc = citizens.location(r.npc);
        if (!CitizensAdapter.loaded(loc) || loc.getWorld() != r.view.world) return false;
        long start = RenderGeometry.nearest(r.view.graph, CitizensAdapter.vec(loc));
        PremiumWalk.Decision decision = PremiumWalk.decide(r.view.key.worldId(), r.view.key.region(), r.view.graph,
                candidate.worldId(), candidate.district(), candidate.graph(), candidate.profile(), move.newPropertyId(), start);
        pendingWalks.remove(residentId);
        pendingWalkExpiry.remove(residentId);
        if (!decision.walk()) return false;
        r.propertyId = candidate.property().propertyId();
        r.ownershipEpoch = candidate.property().ownershipEpoch();
        r.walkingHome = true;
        // A stop renewed before the move keeps no more than its first 90 seconds.
        if (pausePolicy.renews() && r.pausedUntilMs > now)
            r.pausedUntilMs = Math.min(r.pausedUntilMs, r.conversationPause.stopRenewing());
        r.walkPlanned = false;
        r.walkHomeTag = PremiumWalk.homeTag(move.newPropertyId());
        r.walkDeadlineMs = now + PremiumWalk.WINDOW_MS;
        r.route = null;
        r.intent = null;
        r.nextPlanMs = 0;
        return true;
    }

    /** Advances one avatar's walk; returns false if it should despawn (too many failed edges). */
    private boolean walk(UUID residentId, Render r, long now, long edgeTimeout, int maxFails) {
        Location loc = citizens.location(r.npc);
        if (!CitizensAdapter.loaded(loc) || loc.getWorld() != r.view.world) {
            return false; // gone
        }
        if (r.path == null && !CitizensAdapter.standable(loc)) return false;
        if (now < r.pausedUntilMs) return true;
        if (r.pausedUntilMs != 0) {
            r.pausedUntilMs = 0;
            if (r.path != null && r.pathIdx < r.path.size()) return issueEdge(r, now, maxFails);
        }
        // Need a fresh destination path?
        if (r.path == null || r.pathIdx >= r.path.size()) {
            if (r.debug && !r.encounterPreview) {
                return true;
            }
            if (r.profile == null) return true;
            if (r.walkingHome && (now >= r.walkDeadlineMs || r.profile == null)) return false;
            ResidentRoutine.Intent intent = r.walkingHome ? PremiumWalk.homeIntent(r.profile)
                    : ResidentRoutine.at(r.profile, r.view.world.getTime());
            boolean sameIntent = intent.equals(r.intent);
            if (!routePlanDue(sameIntent, atDestination(r.view.graph, r.route, CitizensAdapter.vec(loc)),
                    now, r.nextPlanMs) || !planBudget.acquire(residentId)) return true;
            long start = RenderGeometry.nearest(r.view.graph, CitizensAdapter.vec(loc));
            if (start < 0 || !RenderGeometry.arrived(CitizensAdapter.vec(loc),
                    RenderGeometry.position(r.view.graph.node(start)))) {
                return false;
            }
            r.intent = intent;
            r.route = r.walkingHome && r.walkHomeTag != null
                    ? ResidentRoutePlanner.planToTag(r.view.graph, start, r.walkHomeTag)
                    : ResidentRoutePlanner.plan(r.profile, intent, r.view.graph, start);
            if (r.walkingHome) r.walkPlanned = true;
            r.nextPlanMs = now + RETRY_MS;
            if (r.walkingHome) {
                if (r.route.status() == ResidentRoutePlanner.Status.AT_DESTINATION) r.walkingHome = false;
                else if (r.route.status() != ResidentRoutePlanner.Status.WALK) return false; // respawn fallback
            }
            if (r.route.status() != ResidentRoutePlanner.Status.WALK) return true;
            r.path = r.route.nodes();
            r.pathIdx = 1;
            r.edgeStartMs = now;
            return issueEdge(r, now, maxFails);
        }
        long fromId = r.path.get(r.pathIdx - 1);
        long targetId = r.path.get(r.pathIdx);
        if (!RenderGeometry.edge(r.view.graph, fromId, targetId)) {
            return false;
        }
        Location from = nodeLocation(r.view, fromId);
        Location target = nodeLocation(r.view, r.path.get(r.pathIdx));
        if (!CitizensAdapter.loaded(from) || !CitizensAdapter.loaded(target)
                || !RenderGeometry.confined(CitizensAdapter.vec(loc), CitizensAdapter.vec(from), CitizensAdapter.vec(target))) {
            return false;
        }
        if (RenderGeometry.arrived(CitizensAdapter.vec(loc), CitizensAdapter.vec(target))) {
            if (!citizens.cancel(r.npc)) {
                return false;
            }
            r.pathIdx++;
            r.edgeRetry.arrived();
            r.edgeStartMs = now;
            if (walkEnds(r.walkingHome, r.walkPlanned, r.pathIdx, r.path.size())) r.walkingHome = false; // arrived; routine resumes
            if (!r.walkingHome && r.profile != null && !ResidentRoutine.at(r.profile, r.view.world.getTime()).equals(r.intent)) {
                // A time skip changes the next destination only after finishing this safe edge.
                r.path = null;
                r.route = null;
                r.nextPlanMs = 0;
                return true;
            }
            if (r.pathIdx < r.path.size()) {
                return issueEdge(r, now, maxFails);
            } else {
                r.path = null; // confirm the tagged destination on the next bounded planning pass
                r.nextPlanMs = 0;
                r.route = null;
            }
            return true;
        }
        return continueEdge(r.edgeRetry, now, r.edgeStartMs, edgeTimeout,
                () -> citizens.isNavigating(r.npc), () -> issueEdge(r, now, maxFails),
                () -> failed(r, now, maxFails, "edge timeout/early idle"));
    }

    /** A premium walk ends on finishing a path only if that path was the HOME route, not an older routine path. */
    static boolean walkEnds(boolean walkingHome, boolean homePlanned, int pathIdx, int pathSize) {
        return walkingHome && homePlanned && pathIdx >= pathSize;
    }

    static boolean continueEdge(RenderCadence.EdgeRetry retry, long now, long started, long timeout,
                                BooleanSupplier navigating, BooleanSupplier issue, BooleanSupplier failed) {
        if (retry.pending()) return issue.getAsBoolean();
        if (now - started >= timeout || !navigating.getAsBoolean()) return failed.getAsBoolean();
        return true;
    }

    private boolean issueEdge(Render r, long now, int maxFails) {
        long from = r.path.get(r.pathIdx - 1);
        long to = r.path.get(r.pathIdx);
        if (!RenderGeometry.edge(r.view.graph, from, to)) {
            return false;
        }
        return r.edgeRetry.attempt(now, maxFails, () -> {
            r.edgeStartMs = now;
            return citizens.walkSegment(r.npc, nodeLocation(r.view, from), nodeLocation(r.view, to));
        }, () -> citizens.cancel(r.npc), () -> warn(now, citizens.lastFailure()));
    }

    private boolean failed(Render r, long now, int maxFails, String reason) {
        return r.edgeRetry.failed(now, maxFails, () -> citizens.cancel(r.npc), () -> warn(now, reason));
    }

    private void requestSnapshot(long now, Set<UUID> worlds, GraphKey debugKey) {
        CitizensAdapter.requireMainThread();
        if (debugKey != null) {
            requestedDebugGraph = debugKey;
            Snapshot cached = snapshots.value(now);
            if (cached == null || !cached.checked().contains(debugKey)) snapshots.expedite();
        }
        Set<GraphKey> keys = new java.util.HashSet<>();
        if (requestedDebugGraph != null) keys.add(requestedDebugGraph);
        rendered.values().stream().map(r -> r.previewGraph).filter(java.util.Objects::nonNull).forEach(keys::add);
        previewRequests.snapshot().stream().filter(request -> request.district() != null)
                .map(request -> new GraphKey(request.world(), request.district())).forEach(keys::add);
        Set<GraphKey> requested = Set.copyOf(keys);
        Set<UUID> viewerWorlds = Bukkit.getOnlinePlayers().stream().map(p -> p.getWorld().getUID())
                .collect(java.util.stream.Collectors.toSet());
        Set<UUID> previewWorlds = requested.stream().map(GraphKey::worldId).collect(java.util.stream.Collectors.toSet());
        Set<UUID> included = scopeWorlds(worlds,viewerWorlds,previewWorlds);
        if (!requestedWorldScope.containsAll(included)) snapshots.expedite();
        requestedWorldScope = included;
        List<Property> input = properties.getEnrolled().stream()
                .filter(p -> p.enabled() && p.worldUuid() != null && included.contains(p.worldUuid())
                        && !properties.isDisplayUnavailable(p.propertyId()))
                .sorted(Comparator.comparing(Property::propertyId)).limit(MAX_PROPERTIES).toList();
        // Only immutable records/UUIDs cross the worker boundary. No World, NPC or plugin calls.
        Set<UUID> civicWorlds = plugin.getConfig().getBoolean("town.enabled", false) ? included : Set.of();
        snapshots.refresh(() -> readSnapshot(input, requested, civicWorlds), now,
                snapshot -> snapshot.checked().containsAll(requested));
        if (snapshots.failure() != null) {
            warn(now, snapshots.failure());
        }
    }

    private Snapshot readSnapshot(List<Property> input, Set<GraphKey> debugKeys, Set<UUID> civicWorlds) throws Exception {
        Map<UUID, Candidate> candidates = new HashMap<>();
        Map<GraphKey, NavGraph> graphs = new HashMap<>();
        Set<GraphKey> read = new java.util.HashSet<>();
        for (GraphKey key : debugKeys.stream().sorted(Comparator.comparing(GraphKey::worldId).thenComparing(GraphKey::region)).toList())
            loadGraph(key, graphs, read);
        for (Property property : input) {
            if (Thread.currentThread().isInterrupted() || candidates.size() >= MAX_CANDIDATES) {
                break;
            }
            GraphKey key = new GraphKey(property.worldUuid(), property.regionId());
            loadGraph(key, graphs, read);
            NavGraph graph = graphs.get(key);
            if (graph == null) {
                continue;
            }
            for (Tenant tenant : tenants.listForDisplay(property.propertyId(), property.capacity())) {
                if (candidates.size() >= MAX_CANDIDATES) {
                    break;
                }
                if (candidates.putIfAbsent(tenant.tenantId(), new Candidate(property, tenant.displayName(), graph, null)) != null)
                    throw new java.sql.SQLException("Resident appeared in multiple display properties");
            }
        }
        for (CivicPlacement placement : civicPlacements.listForDisplay(civicWorlds, HARD_AVATAR_CAP)) {
            if (Thread.currentThread().isInterrupted() || candidates.size() >= MAX_CANDIDATES) break;
            if (candidates.containsKey(placement.residentId())) continue;
            GraphKey key = new GraphKey(placement.worldUuid(), placement.districtId());
            loadGraph(key, graphs, read);
            NavGraph graph = graphs.get(key);
            if (graph != null) candidates.put(placement.residentId(), new Candidate(null, "Resident", graph, null, placement));
        }
        var profiles = residents.findMany(candidates.keySet());
        candidates.replaceAll((id, candidate) -> {
            ResidentProfile profile = profiles.get(id);
            return new Candidate(candidate.property(), profile == null ? candidate.name() : profile.name(),
                    candidate.graph(), profile, candidate.civic());
        });
        return new Snapshot(candidates, graphs, read);
    }

    private void loadGraph(GraphKey key, Map<GraphKey, NavGraph> graphs, Set<GraphKey> read) throws Exception {
        if (!read.add(key)) {
            return;
        }
        NavGraph graph = navGraphs.loadActive(key.worldId(), key.region());
        if (graph != null && graph.nodeCount() > 0 && graph.nodeCount() <= MAX_GRAPH_NODES
                && graph.edgeCount() <= MAX_GRAPH_EDGES
                && graphs.values().stream().mapToInt(NavGraph::nodeCount).sum() + graph.nodeCount() <= MAX_GRAPH_NODES
                && graphs.values().stream().mapToInt(NavGraph::edgeCount).sum() + graph.edgeCount() <= MAX_GRAPH_EDGES) {
            graphs.put(key, graph);
        }
    }

    private static GraphView view(NavGraph graph, World world, GraphKey key) {
        GraphView view = new GraphView();
        view.graph = graph;
        view.world = world;
        view.key = key;
        return view;
    }

    private static boolean sameGraph(NavGraph a, NavGraph b) {
        return a == b || (a.nodeCount() == b.nodeCount() && a.edges().equals(b.edges())
                && a.nodes().stream().allMatch(n -> n.equals(b.node(n.id()))));
    }

    private static Location spawnLocation(GraphView view, Map<String, RenderPolicy.Vec> viewers, double radius) {
        // Choose a real loaded node near a viewer, not the graph centroid or an unseen random chunk.
        if (Double.isNaN(radius) || radius < 0) {
            return null;
        }
        return view.graph.nodes().stream().sorted(Comparator.comparingLong(NavGraph.Node::id))
                .filter(n -> viewers.isEmpty() || viewers.values().stream()
                        .anyMatch(v -> v.dist2(RenderGeometry.position(n)) <= radius * radius))
                .map(n -> nodeLocation(view, n.id())).filter(CitizensAdapter::standable)
                .findFirst().orElse(null);
    }

    private long now() { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos); }

    static Set<UUID> scopeWorlds(Set<UUID> requested, Set<UUID> viewers, Set<UUID> previews) {
        Set<UUID> result = new java.util.HashSet<>(requested);
        result.addAll(viewers);
        result.addAll(previews);
        return Set.copyOf(result);
    }

    private boolean wantsPlan(Render r, long time) {
        if (r.profile == null || r.view == null || time < r.pausedUntilMs || r.path != null) return false;
        if (Bukkit.getWorld(r.view.world.getUID()) != r.view.world) return false;
        Location loc = citizens.location(r.npc);
        if (!CitizensAdapter.loaded(loc) || loc.getWorld() != r.view.world) return false;
        return routePlanDue(ResidentRoutine.at(r.profile, r.view.world.getTime()).equals(r.intent),
                atDestination(r.view.graph, r.route, CitizensAdapter.vec(loc)), time, r.nextPlanMs);
    }

    static boolean routePlanDue(boolean sameIntent, boolean atStop, long now, long nextPlanMs) {
        return !sameIntent || (!atStop && now >= nextPlanMs);
    }

    static boolean atDestination(NavGraph graph, ResidentRoutePlanner.Decision route, RenderPolicy.Vec position) {
        if (route == null || route.status() != ResidentRoutePlanner.Status.AT_DESTINATION || route.nodes().isEmpty())
            return false;
        NavGraph.Node target = graph.node(route.nodes().getLast());
        return target != null && RenderGeometry.arrived(position, RenderGeometry.position(target));
    }

    /** Main-thread shared work admission, independent of render timer frequency and avatar count. */
    static final class PlanBudget {
        private long nextWindow;
        private int used;
        private final java.util.ArrayDeque<UUID> waiting = new java.util.ArrayDeque<>();
        void begin(Set<UUID> eligible, long now) {
            if (now < 0 || eligible.size() > HARD_AVATAR_CAP) { waiting.clear(); return; }
            if (now >= nextWindow) { nextWindow = now + 1_000; used = 0; }
            waiting.removeIf(id -> !eligible.contains(id));
            eligible.stream().sorted().filter(id -> !waiting.contains(id)).forEach(waiting::addLast);
        }
        boolean acquire(UUID resident) {
            if (used >= 2 || !resident.equals(waiting.peekFirst())) return false;
            waiting.removeFirst();
            used++;
            return true;
        }
    }

    private static Location nodeLocation(GraphView gv, long nodeId) {
        NavGraph.Node n = gv.graph.node(nodeId);
        return new Location(gv.world, n.x() + 0.5, n.y(), n.z() + 0.5);
    }

    private void despawnAll() {
        previewRequests.clear();
        for (Render r : rendered.values()) {
            citizens.despawn(r.npc);
        }
        rendered.clear();
    }

    private int serverCap() {
        return Math.clamp(plugin.getConfig().getInt("rendering.max-server", 20), 0, HARD_AVATAR_CAP);
    }

    private long edgeTimeout() {
        return Math.clamp(plugin.getConfig().getLong("rendering.edge-timeout-ms", 10_000L), 1_000L, 30_000L);
    }

    private int maxFails() {
        return Math.clamp(plugin.getConfig().getInt("rendering.max-fails", 3), 1, 5);
    }

    private void updateDebugs(long now) {
        for (Iterator<Map.Entry<UUID, Render>> it = rendered.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Render> entry = it.next();
            Render r = entry.getValue();
            boolean remove = false;
            if (r.encounterPreview) {
                Player owner = Bukkit.getPlayer(r.previewOwner);
                Location loc = citizens.location(r.npc);
                remove = !previewEnabled() || now >= r.expiresMs || serverCap() == 0
                        || owner == null || !owner.hasPermission("prosperitynpc.admin")
                        || !CitizensAdapter.loaded(loc) || owner.getWorld() != loc.getWorld()
                        || owner.getLocation().distanceSquared(loc) > 64 * 64;
                if (!remove && r.previewGraph != null) {
                    Snapshot snapshot = snapshots.value(now);
                    NavGraph current = snapshot == null ? null : snapshot.graphs().get(r.previewGraph);
                    remove = current == null;
                    if (!remove && current != r.view.graph) {
                        remove = !sameGraph(current, r.view.graph);
                        if (!remove) r.view.graph = current;
                    }
                    if (!remove) remove = !walk(entry.getKey(), r, now, edgeTimeout(), maxFails());
                } else if (!remove) {
                    remove = !CitizensAdapter.standable(loc);
                }
            } else if (r.debug) {
                remove = now >= r.expiresMs || serverCap() == 0 || !walk(entry.getKey(), r, now, edgeTimeout(), maxFails());
            }
            if (r.debug && rendered.size() > serverCap()) remove = true;
            if (remove) {
                citizens.despawn(r.npc);
                it.remove();
            }
        }
    }

    private void warn(long now, String reason) {
        if (now - lastWarningMs >= RETRY_MS) {
            plugin.getLogger().warning("NPC render failure (simulation unaffected): " + reason);
            lastWarningMs = now;
        }
    }

    /** Single-flight, read-only cache. The caller supplies time and immutable data, never Bukkit objects. */
    static final class ReadCache<T> implements AutoCloseable {
        static final long REFRESH_MS = 30_000L;
        static final long MAX_AGE_MS = 120_000L;
        static final long EXPEDITED_MS = 2_000L;
        private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "prosperity-npc-render-read");
            thread.setDaemon(true);
            return thread;
        });
        private Future<T> pending;
        private T cached;
        private long nextReadMs;
        private long readStartedMs;
        private long cachedAtMs;
        private long generation;
        private long pendingGeneration;
        private long nextExpeditedReadMs;
        private long failureRetryMs;
        private boolean expedited;
        private boolean closed;
        private String failure;

        void refresh(Callable<T> read, long now) {
            refresh(read, now, value -> true);
        }

        void refresh(Callable<T> read, long now, Predicate<T> accepts) {
            if (closed) {
                return;
            }
            if (pending != null && pending.isDone()) {
                boolean current = pendingGeneration == generation;
                try {
                    T data = pending.get(); // isDone above: never wait on the main thread.
                    if (current) {
                        if (accepts.test(data)) {
                            cached = data;
                            cachedAtMs = readStartedMs;
                            failure = null;
                            failureRetryMs = 0;
                        } else {
                            // Scope can grow during a read. Keep old data AND its age until a
                            // bounded superset read completes; never relabel old graphs as fresh.
                            expedited = true;
                        }
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    if (current) {
                        cached = null;
                        failure = "snapshot read failed: " + e.getClass().getSimpleName();
                        failureRetryMs = now + REFRESH_MS;
                        expedited = false;
                    }
                }
                pending = null;
                nextReadMs = current ? now + REFRESH_MS : now;
            }
            if (pending == null && (now >= nextReadMs
                    || (expedited && now >= nextExpeditedReadMs && now >= failureRetryMs))) {
                readStartedMs = now;
                pendingGeneration = generation;
                nextReadMs = now + REFRESH_MS;
                nextExpeditedReadMs = now + EXPEDITED_MS;
                // Demand arriving after this submission remains queued for one bounded follow-up.
                expedited = false;
                pending = executor.submit(read);
            }
        }

        T value(long now) {
            return !closed && now >= cachedAtMs && now - cachedAtMs < MAX_AGE_MS ? cached : null;
        }

        String failure() {
            return failure;
        }

        void expedite() {
            if (!closed) expedited = true;
        }

        boolean shouldRefresh(boolean hasConsumers) {
            // Publication can remove the last avatar. Still drain its pending read and replacement.
            return !closed && (hasConsumers || pending != null || expedited);
        }

        void invalidate() {
            invalidate(value -> null);
        }

        void invalidate(UnaryOperator<T> retain) {
            generation++;
            cached = cached == null ? null : retain.apply(cached);
            failure = null;
            failureRetryMs = 0;
            // Do not cancel/replace an in-flight JDBC read: interruption may not stop the driver.
            // Its completion is discarded; only then may another read be submitted.
            nextReadMs = 0;
        }

        @Override
        public void close() {
            closed = true;
            invalidate();
            executor.shutdownNow();
        }
    }
}
