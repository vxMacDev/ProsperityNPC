package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.render.RenderManager;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.resident.ResidentRepository;
import com.prosperitysmp.npc.town.CivicPlacement;
import com.prosperitysmp.npc.town.CivicPlacementRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** Runtime staff authoring of persistent civic residents; no property, tenancy, or money creation. */
public final class NpcTownCommand implements TabExecutor, AutoCloseable {
    private static final String ADMIN_PERMISSION = "prosperitynpc.admin";
    private static final int MAX_PENDING = 16;
    private static final int LIST_LIMIT = 100;
    private final ProsperityNpcPlugin plugin;
    private final CivicPlacementRepository placements;
    private final ResidentRepository residents;
    private final RenderManager renderer;
    private final ResidentCast cast;
    private final Server server;
    private final BukkitScheduler scheduler;
    private final ThreadPoolExecutor worker;
    private final Pending pending = new Pending();
    private final ArrayBlockingQueue<Completion> completions = new ArrayBlockingQueue<>(MAX_PENDING);
    private BukkitTask pump;
    private volatile boolean closed;

    enum Verb { SEED, PLACE, DISABLE, LIST,
        /** Contracts and partnerships staff verbs. None of them returns items or money. */
        PARTNERS, PARTNER_END, CONTRACT_RESOLVE }
    /** extra: the further arguments of the staff contract verbs, already checked. */
    record Input(Verb verb, String resident, String district, List<String> extra) {
        Input(Verb verb, String resident, String district) { this(verb, resident, district, List.of()); }
        Input { extra = List.copyOf(extra); }
    }
    record Actor(CommandSender login, UUID playerId, UUID worldId, Set<UUID> displayWorlds, String key) {
        Actor { displayWorlds = Set.copyOf(displayWorlds); }
    }
    record Request(Actor actor, Input input, AtomicReference<Set<UUID>> attempted) {
        Request(Actor actor, Input input) { this(actor, input, new AtomicReference<>(Set.of())); }

        void beforeMutation(UUID resident) {
            Set<UUID> next = new HashSet<>(attempted.get());
            next.add(resident);
            attempted.set(Set.copyOf(next));
        }
    }
    record Reply(List<String> lines, Set<UUID> invalidate, NamedTextColor color, String errorType) {
        Reply { lines = List.copyOf(lines); invalidate = Set.copyOf(invalidate); }
    }
    private record Completion(Request request, Reply reply) { }

    /** Holds admission through response consumption, not merely SQL completion. */
    static final class Pending {
        private final Map<String, Request> requests = new HashMap<>();

        synchronized boolean reserve(Request request) {
            if (requests.size() >= MAX_PENDING || requests.containsKey(request.actor().key())) return false;
            requests.put(request.actor().key(), request);
            return true;
        }

        synchronized void release(Request request) { requests.remove(request.actor().key(), request); }
        synchronized List<Request> snapshot() { return List.copyOf(requests.values()); }
        synchronized void clear() { requests.clear(); }
    }

    public NpcTownCommand(ProsperityNpcPlugin plugin, CivicPlacementRepository placements,
                          ResidentRepository residents, RenderManager renderer, ResidentCast cast) {
        requireMainThread();
        this.plugin = Objects.requireNonNull(plugin);
        this.cast = Objects.requireNonNull(cast);
        this.placements = Objects.requireNonNull(placements);
        this.residents = Objects.requireNonNull(residents);
        this.renderer = Objects.requireNonNull(renderer);
        this.server = plugin.getServer();
        this.scheduler = server.getScheduler();
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread thread = new Thread(task, "prosperity-npc-town-authoring");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        plugin.trackDatabaseWorker(worker);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        requireMainThread();
        if (closed || !supported(sender) || !sender.hasPermission(ADMIN_PERMISSION)) {
            send(sender, NamedTextColor.RED, "Town administration is unavailable.");
            return true;
        }
        Input input;
        try {
            input = parse(args);
        } catch (IllegalArgumentException invalid) {
            usage(sender);
            return true;
        }
        if (input.verb() == Verb.PLACE && !(sender instanceof Player)) {
            send(sender, NamedTextColor.RED, "Place requires an in-game player in the target world.");
            return true;
        }
        final Actor actor;
        try {
            Player player = sender instanceof Player p ? p : null;
            UUID playerId = player == null ? null : player.getUniqueId();
            UUID world = player == null ? null : player.getWorld().getUID();
            Set<UUID> worlds = new HashSet<>();
            if (player != null) worlds.add(world);
            else if (input.verb() == Verb.LIST) {
                if (server.getWorlds().size() > 128) throw new IllegalArgumentException("World scope exceeds bound");
                for (var loaded : server.getWorlds()) worlds.add(loaded.getUID());
            }
            actor = new Actor(sender, playerId, world, worlds, playerId == null ? "console" : playerId.toString());
        } catch (IllegalArgumentException invalid) {
            send(sender, NamedTextColor.RED, "Town request world scope is unavailable.");
            return true;
        }
        if (!authorized(actor, input.verb())) {
            send(sender, NamedTextColor.GRAY, "Town authoring is paused or this login is unavailable.");
            return true;
        }
        if (input.verb() == Verb.PARTNER_END) {
            UUID target = playerId(input.extra().getFirst());
            if (target == null) {
                send(sender, NamedTextColor.RED, "No player by that name. Use an online name or the player UUID.");
                return true;
            }
            input = new Input(Verb.PARTNER_END, null, null, List.of(target.toString(), input.extra().get(1)));
        }
        Request request = new Request(actor, input);
        if (!pending.reserve(request)) {
            send(sender, NamedTextColor.GRAY, "Town authoring is busy or your previous request is still pending.");
            return true;
        }
        try {
            if (pump == null) pump = scheduler.runTaskTimer(plugin, this::pumpCompletions, 1L, 1L);
            worker.execute(() -> execute(request));
        } catch (RejectedExecutionException full) {
            pending.release(request);
            send(sender, NamedTextColor.GRAY, "Town authoring is busy. Retry shortly.");
            return true;
        } catch (RuntimeException stopping) {
            pending.release(request);
            send(sender, NamedTextColor.GRAY, "Town authoring is unavailable.");
            return true;
        }
        send(sender, NamedTextColor.GRAY, "Town request queued.");
        return true;
    }

    private void execute(Request request) {
        Reply reply;
        try {
            if (!admit(request)) reply = cancelled(Set.of());
            else reply = perform(request);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            reply = failed(request, interrupted);
        } catch (Exception failure) {
            reply = failed(request, failure);
        }
        if (!closed) {
            // One completion per reserved actor: even an undrained response backlog cannot exceed 16.
            completions.add(new Completion(request, reply));
        }
    }

    /** Worker waits for a main-thread gate; the server thread never waits for SQL. */
    private boolean admit(Request request) throws Exception {
        if (closed || Thread.currentThread().isInterrupted()) return false;
        Future<Boolean> admission = scheduler.callSyncMethod(plugin,
                () -> authorized(request.actor(), request.input().verb()));
        return awaitAuthorization(admission);
    }

    static boolean awaitAuthorization(Future<Boolean> admission) throws Exception {
        try {
            return admission.get(2, TimeUnit.SECONDS);
        } finally {
            admission.cancel(false);
        }
    }

    /** Repository I/O only here/on lookup: all Bukkit state is captured or obtained through admit. */
    private Reply perform(Request request) throws Exception {
        Set<UUID> invalidate = new HashSet<>();
        switch (request.input().verb()) {
            case SEED -> {
                if (cast.source() != ResidentCast.Source.BUILT_IN) return seedCast(request, invalidate);
                int created = 0;
                for (ResidentProfile profile : cast.profiles()) {
                    request.beforeMutation(profile.id());
                    if (!admit(request) || closed || Thread.currentThread().isInterrupted()) return cancelled(invalidate);
                    if (residents.register(profile)) { created++; invalidate.add(profile.id()); }
                }
                return success("Original catalogue: " + created + " registered, "
                        + (cast.profiles().size() - created) + " unchanged.", invalidate);
            }
            case PLACE, DISABLE -> {
                ResidentProfile resident = lookup(request.input().resident());
                if (resident == null) return new Reply(List.of("No matching resident. Seed the catalogue or use a registered UUID."),
                        Set.of(), NamedTextColor.RED, null);
                request.beforeMutation(resident.id());
                if (!admit(request) || closed || Thread.currentThread().isInterrupted()) return cancelled(Set.of());
                if (request.input().verb() == Verb.PLACE) {
                    boolean changed = placements.place(resident.id(), request.actor().worldId(), request.input().district());
                    if (changed) invalidate.add(resident.id());
                    return success(resident.name() + (changed ? " placed in " + request.input().district() + "."
                            : " placement unchanged."), invalidate);
                }
                boolean changed = placements.disable(resident.id());
                // A containment disable evicts stale visuals even when SQL is already disabled/missing.
                invalidate.add(resident.id());
                return success(resident.name() + (changed ? " civic placement disabled." : " has no enabled civic placement."), invalidate);
            }
            case PARTNERS, PARTNER_END, CONTRACT_RESOLVE -> { return contractStaff(request); }
            case LIST -> {
                List<ResidentProfile> profiles = residents.list(LIST_LIMIT);
                Map<UUID, CivicPlacement> visible = new HashMap<>();
                for (CivicPlacement placement : placements.listForDisplay(request.actor().displayWorlds(), LIST_LIMIT)) {
                    visible.put(placement.residentId(), placement);
                }
                List<String> lines = new ArrayList<>();
                lines.add("Residents: " + profiles.size() + " (maximum 100; current-world display scope for players).");
                for (ResidentProfile profile : profiles) {
                    CivicPlacement placement = visible.get(profile.id());
                    String binding = placement == null ? "not displayed in scope"
                            : placement.districtId() + " | " + placement.worldUuid() + " | revision " + placement.revision();
                    lines.add(profile.name() + " | " + profile.id() + " | " + binding);
                }
                return new Reply(lines, Set.of(), NamedTextColor.AQUA, null);
            }
        }
        throw new IllegalStateException("Unsupported town operation");
    }

    /**
     * Staff verbs for contracts and partnerships. They work with the features off, so staff can always settle what is
     * already recorded. operator_ref is the staff UUID (or "console"). Nothing here returns items or money.
     */
    private Reply contractStaff(Request request) throws Exception {
        var contracts = new com.prosperitysmp.npc.town.TownContractRepository(plugin.database());
        var partnerships = new com.prosperitysmp.npc.town.PartnershipRepository(plugin.database());
        var clock = plugin.economyClock();
        var catalogue = plugin.contractCatalogue();
        java.time.Instant now = java.time.Instant.now();
        java.time.LocalDate today = clock.dayAt(now);
        String operator = request.actor().playerId() == null ? "console" : request.actor().playerId().toString();
        switch (request.input().verb()) {
            case PARTNERS -> {
                UUID resident = null;
                if (request.input().resident() != null) {
                    ResidentProfile profile = lookup(request.input().resident());
                    if (profile == null) return new Reply(List.of("No matching resident."), Set.of(), NamedTextColor.RED, null);
                    resident = profile.id();
                }
                var rows = partnerships.listActive(resident, today, com.prosperitysmp.npc.town.PartnershipRepository.MAX_LIST);
                return new Reply(partnerLines(rows, id -> cast.byId(id).map(ResidentProfile::name).orElse(id.toString()),
                        catalogue.maxPartners()), Set.of(), NamedTextColor.AQUA, null);
            }
            case PARTNER_END -> {
                UUID player = UUID.fromString(request.input().extra().get(0));
                String caseRef = request.input().extra().get(1);
                if (!admit(request) || closed) return cancelled(Set.of());
                var ended = partnerships.end(player, com.prosperitysmp.npc.town.Partnership.EndReason.STAFF_ENDED, now, today);
                if (ended.isEmpty()) return new Reply(List.of("That player has no active partnership."), Set.of(), NamedTextColor.GRAY, null);
                plugin.getLogger().info("Staff " + operator + " ended partnership " + ended.get().partnershipId() + " (case " + caseRef + ").");
                return success("Partnership ended for " + player + ". Nothing was refunded. Case " + caseRef + ".", Set.of());
            }
            case CONTRACT_RESOLVE -> {
                UUID contractId = UUID.fromString(request.input().extra().get(0));
                boolean consumed = request.input().extra().get(1).equals("consumed");
                String caseRef = request.input().extra().get(2);
                var found = contracts.peek(contractId);
                if (found.isEmpty()) return new Reply(List.of("No handover with that ID."), Set.of(), NamedTextColor.RED, null);
                var giver = found.get().definition().giverId();
                var terms = plugin.partnershipsOn() ? catalogue.partnerOffer(giver).map(catalogue::terms).orElse(null) : null;
                if (!admit(request) || closed) return cancelled(Set.of());
                var outcome = contracts.resolve(contractId, consumed ? com.prosperitysmp.npc.town.TownContractRepository.Decision.VERIFIED_CONSUMED
                        : com.prosperitysmp.npc.town.TownContractRepository.Decision.VERIFIED_NOT_CONSUMED, operator, caseRef, now, today, terms);
                plugin.getLogger().info("Staff " + operator + " resolved handover " + contractId + " as "
                        + (consumed ? "consumed" : "not consumed") + " (case " + caseRef + "): " + outcome.code());
                return switch (outcome.code()) {
                    case RESOLVED_CONSUMED, RESOLVED_NOT_CONSUMED -> success(resolveLine(contractId, consumed, caseRef,
                            found.get().definition().kind() == com.prosperitysmp.npc.town.ContractDefinition.Kind.PARTNERSHIP,
                            outcome.partnership().isPresent(), outcome.noPartnership()), Set.of());
                    case NOT_HELD -> new Reply(List.of("Handover " + contractId + " is not held for review."), Set.of(), NamedTextColor.GRAY, null);
                    default -> new Reply(List.of("No handover with that ID."), Set.of(), NamedTextColor.RED, null);
                };
            }
            default -> throw new IllegalStateException("Not a contract verb");
        }
    }

    /** "<player> | <resident> | until <day> | seats <used>/<max>", one row per active partnership. */
    static List<String> partnerLines(List<com.prosperitysmp.npc.town.Partnership> rows, Function<UUID, String> residentName, int max) {
        if (rows.isEmpty()) return List.of("No active partnerships.");
        var seats = new HashMap<UUID, Integer>();
        for (var row : rows) seats.merge(row.residentId(), 1, Integer::sum);
        var lines = new ArrayList<String>();
        for (var row : rows) lines.add(row.ownerId() + " | " + residentName.apply(row.residentId()) + " | until "
                + row.lastActiveDay() + " | seats " + seats.get(row.residentId()) + "/" + max);
        return List.copyOf(lines);
    }

    /**
     * "Handover <id> marked consumed. Case <case-ref>." plus what happened to a partnership: active, or the reason the
     * repository gave for starting none. why is null when nothing was decided by this call (a repeat of an earlier one).
     */
    static String resolveLine(UUID contractId, boolean consumed, String caseRef, boolean partnership, boolean started,
                              com.prosperitysmp.npc.town.TownContractRepository.NoPartnership why) {
        String line = "Handover " + contractId + " marked " + (consumed ? "consumed" : "not consumed") + ". Case " + caseRef + ".";
        if (consumed && partnership) line += started ? " The partnership is active." : why == null
                ? " No new partnership started. If this handover was resolved before, the first reply says what happened."
                : " No partnership started: " + switch (why) {
                    case NO_OFFER -> "partnerships are off or this resident has no offer.";
                    case OFFER_CHANGED -> "the offer changed after this handover.";
                    case NO_KEPT_TERMS -> "this handover has no saved partnership terms. It is older than the update that saves them.";
                    case NO_PERK_LEFT -> "the saved perks and the current offer leave no perk above 0.";
                    case CHECK_FAILED -> "a seat or home check failed.";
                    case HAS_PARTNERSHIP -> "the player already has a partnership.";
                    case NOT_RENEWABLE -> "the player has no active partnership with this resident to renew.";
                };
        return line;
    }

    /** A UUID, or the name of an online player. Main thread only. */
    private UUID playerId(String key) {
        UUID id = residentId(key);
        if (id != null) return id;
        Player online = server.getPlayerExact(key);
        return online == null ? null : online.getUniqueId();
    }

    /**
     * residents.yml on: saves the new profiles and leaves saved ones alone. A saved profile that differs from the
     * file is kept as saved and named in the reply; identities are never rerolled. Other failures still stop.
     */
    private Reply seedCast(Request request, Set<UUID> invalidate) throws Exception {
        SeedTally tally = new SeedTally();
        for (ResidentProfile profile : cast.profiles()) {
            request.beforeMutation(profile.id());
            if (!admit(request) || closed || Thread.currentThread().isInterrupted()) return cancelled(invalidate);
            tally.record(profile, () -> residents.register(profile));
            if (tally.lastRegistered) invalidate.add(profile.id());
        }
        return success(tally.lines(), invalidate);
    }

    /** Counts one seed pass. A saved profile that differs is kept and reported, never overwritten. */
    public static final class SeedTally {
        public interface Register { boolean run() throws SQLException; }
        private int registered;
        private int unchanged;
        private final List<String> kept = new ArrayList<>();
        private boolean lastRegistered;

        public void record(ResidentProfile profile, Register register) throws SQLException {
            lastRegistered = false;
            try {
                if (register.run()) { registered++; lastRegistered = true; }
                else unchanged++;
            } catch (ResidentRepository.ProfileMismatchException mismatch) {
                kept.add(profile.name());
            }
        }

        public List<String> lines() {
            List<String> lines = new ArrayList<>();
            lines.add("Resident cast: " + registered + " registered, " + unchanged + " unchanged.");
            for (String name : kept) lines.add(name + " is already saved with different details. The saved version is kept.");
            return lines;
        }
    }

    private ResidentProfile lookup(String key) throws SQLException {
        UUID id = residentId(key);
        return id != null ? residents.find(id).orElse(null) : resolveName(residents.list(LIST_LIMIT), key);
    }

    static UUID residentId(String key) {
        if (key.length() != 36) return null;
        try {
            UUID id = UUID.fromString(key);
            return id.toString().equalsIgnoreCase(key) ? id : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    static ResidentProfile resolveName(List<ResidentProfile> profiles, String key) {
        List<ResidentProfile> matches = profiles.stream().filter(p -> p.name().equalsIgnoreCase(key)).toList();
        if (matches.isEmpty()) matches = profiles.stream()
                .filter(p -> p.name().split(" ", 2)[0].equalsIgnoreCase(key)).toList();
        if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous resident name; use UUID");
        return matches.isEmpty() ? null : matches.getFirst();
    }

    static Input parse(String[] args) {
        if (args.length > 66) throw new IllegalArgumentException("Too many arguments");
        if (args.length > 0) {
            String first = args[0].toLowerCase(Locale.ROOT);
            if (first.equals("partners")) {
                if (args.length > 3) throw new IllegalArgumentException("Unexpected arguments");
                String resident = args.length == 1 ? null : String.join(" ", Arrays.copyOfRange(args, 1, args.length)).strip();
                if (resident != null && (resident.isEmpty() || resident.length() > 64 || resident.codePoints().anyMatch(Character::isISOControl)))
                    throw new IllegalArgumentException("Invalid resident key");
                return new Input(Verb.PARTNERS, resident, null);
            }
            if (first.equals("partner-end")) {
                if (args.length != 3 || !args[1].matches("[A-Za-z0-9_.-]{1,36}") || !caseRef(args[2]))
                    throw new IllegalArgumentException("Usage");
                return new Input(Verb.PARTNER_END, null, null, List.of(args[1], args[2]));
            }
            if (first.equals("contract-resolve")) {
                if (args.length != 4 || residentId(args[1].toLowerCase(Locale.ROOT)) == null
                        || !Set.of("consumed", "not-consumed").contains(args[2].toLowerCase(Locale.ROOT)) || !caseRef(args[3]))
                    throw new IllegalArgumentException("Usage");
                return new Input(Verb.CONTRACT_RESOLVE, null, null,
                        List.of(args[1].toLowerCase(Locale.ROOT), args[2].toLowerCase(Locale.ROOT), args[3]));
            }
            if (Set.of("partner_end", "contract_resolve").contains(first)) throw new IllegalArgumentException("Unknown town operation");
        }
        Verb verb;
        try { verb = args.length == 0 ? Verb.LIST : Verb.valueOf(args[0].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Unknown town operation"); }
        if (verb == Verb.SEED || verb == Verb.LIST) {
            if (args.length > 1) throw new IllegalArgumentException("Unexpected arguments");
            return new Input(verb, null, null);
        }
        int end = verb == Verb.PLACE ? args.length - 1 : args.length;
        if (end <= 1) throw new IllegalArgumentException("Missing resident");
        String resident = String.join(" ", Arrays.copyOfRange(args, 1, end)).strip();
        if (resident.isEmpty() || resident.length() > 64 || resident.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid resident key");
        }
        String district = verb == Verb.PLACE ? args[args.length - 1] : null;
        if (district != null && !district.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid district");
        }
        return new Input(verb, resident, district);
    }

    static boolean caseRef(String value) { return value != null && value.matches("[A-Za-z0-9_.:@-]{1,96}"); }

    private boolean authorized(Actor actor, Verb verb) {
        requireMainThread();
        return authorized(actor, verb, closed, plugin.getConfig().getBoolean("town.enabled", false),
                server::getPlayer, server.getConsoleSender());
    }

    static boolean authorized(Actor actor, Verb verb, boolean closed, boolean enabled,
                              Function<UUID, Player> currentPlayer, ConsoleCommandSender console) {
        if (closed || ((verb == Verb.SEED || verb == Verb.PLACE) && !enabled)
                || !actor.login().hasPermission(ADMIN_PERMISSION)) return false;
        if (actor.playerId() == null) return actor.login() instanceof ConsoleCommandSender && actor.login() == console;
        Player player = currentPlayer.apply(actor.playerId());
        return player == actor.login() && player.isOnline() && player.getUniqueId().equals(actor.playerId())
                && player.getWorld().getUID().equals(actor.worldId());
    }

    private void pumpCompletions() {
        requireMainThread();
        if (closed) return;
        for (int i = 0; i < MAX_PENDING; i++) {
            Completion completion = completions.poll();
            if (completion == null) break;
            Request request = completion.request();
            try {
                Reply reply = completion.reply();
                if (reply.errorType() != null) plugin.getLogger().warning("Town authoring request failed ("
                        + request.input().verb() + ", " + reply.errorType() + "); check stored state before retrying.");
                publish(reply, renderer::civicChanged, () -> authorized(request.actor(), request.input().verb()),
                        result -> result.lines().forEach(line -> send(request.actor().login(), result.color(), line)));
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("Town response or renderer invalidation failed; review stored state before retrying.");
                if (authorized(request.actor(), request.input().verb())) send(request.actor().login(), NamedTextColor.RED,
                        "Town result requires staff review. Do not assume the write was rolled back.");
            } finally {
                pending.release(request);
            }
        }
        if (pending.snapshot().isEmpty() && pump != null) { pump.cancel(); pump = null; }
    }

    static void publish(Reply reply, Consumer<UUID> invalidate, BooleanSupplier authorized, Consumer<Reply> deliver) {
        // Eviction is mandatory independently of the requester's later permission/login/world/config state.
        reply.invalidate().stream().sorted().forEach(invalidate);
        if (authorized.getAsBoolean()) deliver.accept(reply);
    }

    private static Reply success(String text, Set<UUID> invalidate) {
        return new Reply(List.of(text), invalidate, NamedTextColor.GREEN, null);
    }

    private static Reply success(List<String> lines, Set<UUID> invalidate) {
        return new Reply(lines, invalidate, NamedTextColor.GREEN, null);
    }

    private static Reply cancelled(Set<UUID> invalidate) {
        return new Reply(List.of("Town request cancelled before further work."), invalidate, NamedTextColor.GRAY, null);
    }

    static Reply failed(Request request, Exception failure) {
        String text = failure instanceof IllegalArgumentException
                ? "Town request refused. Check the resident UUID, active district anchors and current occupancy."
                : "Town request failed. Check stored state before retrying; the outcome may be partial.";
        return new Reply(List.of(text), request.attempted().get(), NamedTextColor.RED, errorType(failure));
    }

    /** The log label for a failed request. A profile mismatch still logs as SQLException, as in dev.58. */
    static String errorType(Exception failure) {
        return failure instanceof ResidentRepository.ProfileMismatchException ? "SQLException" : failure.getClass().getSimpleName();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        requireMainThread();
        if (closed || !supported(sender) || !sender.hasPermission(ADMIN_PERMISSION)) return List.of();
        List<String> base = plugin.getConfig().getBoolean("town.enabled", false) ? List.of("seed", "place", "disable", "list")
                : List.of("disable", "list");
        if (contractVerbsShown()) {
            base = new ArrayList<>(base);
            base.addAll(List.of("partners", "partner-end", "contract-resolve"));
        }
        List<String> choices = args.length == 1 ? base
                : args.length == 2 && (args[0].equalsIgnoreCase("disable")
                    || args[0].equalsIgnoreCase("place") && plugin.getConfig().getBoolean("town.enabled", false))
                    ? cast.firstNames()
                    : List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.startsWith(prefix)).toList();
    }

    /** Main-thread containment before parent shutdown drains workers and closes the renderer/database. */
    @Override
    public void close() {
        requireMainThread();
        if (closed) return;
        closed = true;
        worker.shutdownNow();
        if (pump != null) pump.cancel();
        pump = null;
        Set<UUID> uncertain = new HashSet<>();
        for (Request request : pending.snapshot()) uncertain.addAll(request.attempted().get());
        for (UUID id : uncertain) {
            try { renderer.civicChanged(id); }
            catch (RuntimeException failure) { plugin.getLogger().warning("Town shutdown invalidation requires staff review."); }
        }
        pending.clear();
        completions.clear();
    }

    private static boolean supported(CommandSender sender) {
        return sender instanceof Player || sender instanceof ConsoleCommandSender;
    }

    private static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Town command requires the server thread");
    }

    private static void send(CommandSender sender, NamedTextColor color, String text) {
        sender.sendMessage(Component.text(text, color));
    }

    private void usage(CommandSender sender) {
        send(sender, NamedTextColor.GRAY,
                "Usage: /npctown seed | place <resident name|uuid> <district> | disable <resident name|uuid> | list"
                        + (contractVerbsShown() ? " | partners [resident] | partner-end <player> <case-ref>"
                        + " | contract-resolve <contract-id> <consumed|not-consumed> <case-ref>" : ""));
    }

    /** The contract verbs always work; help and tab-complete list them while contracts or partnerships are on. */
    private boolean contractVerbsShown() { return plugin.contractCatalogue().anyOn(); }
}
