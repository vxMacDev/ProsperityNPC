package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.commerce.CommerceRuntime;
import com.prosperitysmp.npc.property.TenantMoodRepository;
import com.prosperitysmp.npc.render.CitizensAdapter;
import com.prosperitysmp.npc.render.RenderManager;
import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentDialogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.resident.ResidentRepository;
import com.prosperitysmp.npc.town.ContractCatalogue;
import com.prosperitysmp.npc.town.ContractDefinition;
import com.prosperitysmp.npc.town.ContractRuntime;
import com.prosperitysmp.npc.town.Partnership;
import com.prosperitysmp.npc.town.TownContract;
import com.prosperitysmp.npc.town.TownContractRepository;
import com.prosperitysmp.npc.town.TownTask;
import com.prosperitysmp.npc.town.TownTaskCatalogue;
import com.prosperitysmp.npc.town.TownTaskRepository;
import com.prosperitysmp.npc.training.TrainingRuntime;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.npc.NPC;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Shared encounter backend. All Bukkit authorization happens on the main thread. */
public final class ResidentEncounterController implements Listener, TabExecutor, AutoCloseable {
    /** Said by a walking resident whose conversation stop is at its cap. Only with longer conversations on. */
    static final String PAUSE_ENDING_LINE = "I can only stay a little longer.";
    public interface NativeUi extends AutoCloseable {
        boolean handles(UUID player);
        boolean show(Player player, EncounterSessions.Ticket ticket, EncounterView view);
        @Override void close();
    }
    private record Context(Instant at, long worldTime, Set<UUID> reachable, boolean townEnabled,
                           Set<String> employed, TrainingRuntime.View training,
                           Map<String, Long> fullPrices, boolean commerceEnabled) { }
    private record Offered(UUID token, List<ResidentAction> actions) { }
    private record ActionResult(EncounterView view, UUID directionsTo, String stopTag) { }
    private record VariantKey(UUID player, UUID resident, ResidentDialogue.Topic topic) { }
    @FunctionalInterface private interface Read<T> { T run(Context context) throws Exception; }

    private final ProsperityNpcPlugin plugin;
    private final RenderManager renderer;
    private final ResidentRepository residents;
    private final TenantMoodRepository moodRepository;
    private final TownTaskRepository townTasks;
    private final ResidentCast cast;
    private final Map<UUID, Offered> offered = new HashMap<>();
    private final EconomyClock clock;
    private final EncounterSessions sessions;
    /** ux.conversation-sliding-deadline, read once by RenderManager so the idle limit and the pause always agree. */
    private final boolean longerConversations;
    private final long startedNanos = System.nanoTime();
    private final Map<UUID, Long> requestCooldown = new HashMap<>();
    private final Map<UUID, UUID> pending = new ConcurrentHashMap<>();
    private final Map<VariantKey, Integer> variants = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<VariantKey, Integer> eldest) { return size() > 256; }
    };
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "prosperity-npc-encounter");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final ResidentMenu menu;
    private final ResidentSpeech speech;
    private final ResidentGuide guide;
    private final BukkitTask watcher;
    private NativeUi nativeUi;
    private TrainingRuntime training;
    private CommerceRuntime commerce;
    private volatile boolean closed;

    public ResidentEncounterController(ProsperityNpcPlugin plugin, RenderManager renderer, ResidentRepository residents,
                                       TownTaskRepository townTasks, ResidentCast cast) {
        this.plugin = plugin;
        this.cast = Objects.requireNonNull(cast);
        longerConversations = renderer.longerConversations();
        sessions = new EncounterSessions(longerConversations);
        plugin.trackDatabaseWorker(worker);
        this.renderer = renderer;
        this.residents = residents;
        this.moodRepository = new TenantMoodRepository(plugin.database());
        this.townTasks = townTasks;
        clock = new EconomyClock(Clock.systemUTC(), ZoneId.of(plugin.getConfig().getString("clock.timezone", "America/Chicago")),
                plugin.getConfig().getInt("clock.daily-reset-hour", 4));
        menu = new ResidentMenu(this);
        speech = new ResidentSpeech(plugin);
        guide = new ResidentGuide(plugin);
        try {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            Bukkit.getPluginManager().registerEvents(menu, plugin);
            watcher = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 10, 10);
            // Outfit swaps wait while a ticket names the resident: tickets bind the avatar's entity UUID.
            renderer.setConversationProbe(sessions::hasOpenFor);
        } catch (RuntimeException | LinkageError error) {
            HandlerList.unregisterAll(this);
            HandlerList.unregisterAll(menu);
            guide.close();
            worker.shutdownNow();
            throw error;
        }
    }

    public void setNativeUi(NativeUi ui) { this.nativeUi = ui; }
    public void setTraining(TrainingRuntime runtime) { this.training = runtime; }
    public void setCommerce(CommerceRuntime runtime) { this.commerce = runtime; }

    boolean mayPresent(Player player, EncounterSessions.Ticket ticket) {
        return Bukkit.isPrimaryThread() && Bukkit.getPlayer(ticket.playerId()) == player && valid(ticket);
    }

    private long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos); }

    private boolean probeAllowed(UUID player) {
        long time = now();
        requestCooldown.entrySet().removeIf(entry -> time >= entry.getValue());
        if (requestCooldown.containsKey(player) || requestCooldown.size() >= 256) return false;
        requestCooldown.put(player, time + 1_000);
        return true;
    }

    private boolean allowed(Player player) {
        return !closed && plugin.isEnabled() && player != null && player.isOnline()
                && plugin.getConfig().getBoolean("beta.encounters-enabled", false)
                && ((player.hasPermission("prosperitynpc.use")
                     && plugin.killSwitches().isSystemEnabled() && plugin.killSwitches().isRenderingEnabled())
                    || previewAllowed(player));
    }

    private boolean previewAllowed(Player player) {
        return !closed && plugin.isEnabled() && player != null && player.isOnline()
                && plugin.getConfig().getBoolean("beta.encounters-enabled", false)
                && plugin.getConfig().getBoolean("beta.resident-lab-enabled", false)
                && player.hasPermission("prosperitynpc.admin");
    }

    private NPC nearby(Player player, UUID resident) {
        if (!allowed(player)) return null;
        var npc = renderer.avatarForResident(resident).orElse(null);
        if (npc == null || npc.getEntity() == null) return null;
        var at = npc.getEntity().getLocation();
        if (!CitizensAdapter.loaded(at) || !CitizensAdapter.loaded(player.getLocation())
                || at.getWorld() != player.getWorld() || at.distanceSquared(player.getLocation()) > 25
                || !player.hasLineOfSight(npc.getEntity())) return null;
        return npc;
    }

    private boolean valid(EncounterSessions.Ticket ticket) {
        var current = sessions.current(ticket.playerId(), now());
        if (current.isEmpty() || !current.get().token().equals(ticket.token())) return false;
        Player player = Bukkit.getPlayer(ticket.playerId());
        NPC npc = nearby(player, ticket.residentId());
        return npc != null && player.getWorld().getUID().equals(ticket.worldId())
                && npc.getEntity().getUniqueId().equals(ticket.avatarId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void avatarClick(NPCRightClickEvent event) {
        var resident = renderer.resolveTenantId(event.getNPC());
        if (resident.isEmpty()) return;
        event.setCancelled(true);
        event.setDelayedCancellation(true);
        if (probeAllowed(event.getClicker().getUniqueId())) begin(event.getClicker(), resident.get());
        else if (allowed(event.getClicker())) note(event.getClicker(), "Please wait a moment before speaking again.");
    }

    public void begin(Player player, UUID resident) {
        NPC npc = nearby(player, resident);
        if (npc == null) {
            note(player, "That resident is not available nearby.");
            return;
        }
        if (pending.containsKey(player.getUniqueId())) {
            note(player, "One moment, please.");
            return;
        }
        var ticket = sessions.open(player.getUniqueId(), resident, npc.getEntity().getUniqueId(),
                player.getWorld().getUID(), now());
        if (ticket.isEmpty()) {
            note(player, "Please wait a moment before starting another conversation.");
            return;
        }
        renderer.acknowledge(resident, player);
        request(ticket.get(), ResidentDialogue.Topic.GREETING, true);
    }

    public void choose(Player player, UUID token, ResidentDialogue.Topic topic) {
        choose(player, token, topic, true);
    }

    private void choose(Player player, UUID token, ResidentDialogue.Topic topic, boolean mayDefer) {
        if (!allowed(player)) {
            viewClosed(player.getUniqueId(), token);
            return;
        }
        var current = sessions.current(player.getUniqueId(), now());
        if (current.isEmpty() || !current.get().token().equals(token) || !valid(current.get())) {
            leave(player, token);
            return;
        }
        if (topic == ResidentDialogue.Topic.TIPS && tips().isEmpty()) {
            note(player, "That topic is not available.");
            return;
        }
        // Hidden while contracts and partnerships are off, so the reply is the same as for an unknown topic.
        if (topic == ResidentDialogue.Topic.CONTRACTS && !contractsTopicShown()) {
            note(player, "Unknown resident or conversation choice.");
            return;
        }
        if (pending.containsKey(player.getUniqueId())) {
            note(player, "One moment, please.");
            return;
        }
        var admitted = sessions.admit(player.getUniqueId(), token, now());
        if (admitted.isEmpty()) {
            // Keep one bounded click pending on either platform, then revalidate
            // the original session before dispatch. A fast click is not a command fallback.
            if (mayDefer) {
                UUID operation = UUID.randomUUID();
                if (pending.putIfAbsent(player.getUniqueId(), operation) == null) {
                    try {
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (pending.remove(player.getUniqueId(), operation)) choose(player, token, topic, false);
                        }, 25L);
                    } catch (RuntimeException failure) { pending.remove(player.getUniqueId(), operation); }
                }
                return;
            }
            note(player, "Please wait a moment before choosing another topic."); return;
        }
        if (longerConversations) renderer.acknowledge(admitted.get().residentId(), player);
        request(admitted.get(), topic, false);
    }

    private void request(EncounterSessions.Ticket ticket, ResidentDialogue.Topic topic, boolean introduction) {
        if (topic == ResidentDialogue.Topic.BUSINESS && training != null) {
            Player player = Bukkit.getPlayer(ticket.playerId());
            if (player == null || !valid(ticket)) {
                sessions.close(ticket.playerId(), ticket.token());
                return;
            }
            training.view(player, ignored -> {
                if (valid(ticket)) requestReady(ticket, topic, introduction);
                else sessions.close(ticket.playerId(), ticket.token());
            });
            return;
        }
        requestReady(ticket, topic, introduction);
    }

    private void requestReady(EncounterSessions.Ticket ticket, ResidentDialogue.Topic topic, boolean introduction) {
        VariantKey key = new VariantKey(ticket.playerId(), ticket.residentId(), topic);
        int variant = variants.getOrDefault(key, 0);
        List<ResidentAction> directions = topic == ResidentDialogue.Topic.ROUTINE && plugin.getConfig().getBoolean("town.enabled", false)
                ? renderer.publicStops(ticket.residentId(), ticket.worldId()).stream().sorted()
                .map(tag -> new ResidentAction(ResidentAction.Kind.VISIT, tag, null, ticket.residentId(),
                        tag.equals("market") ? "Show me the market" : "Show me a quiet stop")).toList() : List.of();
        boolean moodsOn = plugin.getConfig().getBoolean("housing.moods-enabled", false);
        boolean reserveTrain = plugin.getConfig().getBoolean("ux.reserve-train-slot", false);
        boolean hireNotice = plugin.getConfig().getBoolean("ux.hire-used-notice", false);
        List<String> tipList = topic == ResidentDialogue.Topic.TIPS ? tips() : List.of();
        boolean partnersOn = plugin.partnershipsOn();
        var contractRuntime = topic == ResidentDialogue.Topic.CONTRACTS && contractsTopicShown() ? plugin.contractRuntime() : null;
        var contractFlags = contractFlags();
        var historyRuntime = topic == ResidentDialogue.Topic.HISTORY && plugin.contractsOn() ? plugin.contractRuntime() : null;
        submit(ticket.playerId(), () -> valid(ticket), context -> {
            var profile = residents.find(ticket.residentId()).orElseThrow(() -> new IllegalStateException("Resident unavailable"));
            var before = residents.readRelationship(profile.id(), ticket.playerId());
            var after = introduction ? residents.introduce(profile.id(), ticket.playerId(), context.at(), clock.dayAt(context.at()))
                    : before;
            var reply = ResidentDialogue.reply(profile, before, topic, context.worldTime(), variant, cast.lines(profile.id()), partnersOn);
            EncounterView view = EncounterView.of(profile, after, reply);
            if (!directions.isEmpty()) view = new EncounterView(view.name(), view.occupation(), view.familiarity(),
                    view.line(), view.topics(), directions);
            if (topic == ResidentDialogue.Topic.ROUTINE && moodsOn) {
                var band = moodRepository.band(profile.id());
                if (band.isPresent()) view = view.withMood(ResidentDialogue.moodLine(band.get()));
            }
            if (topic == ResidentDialogue.Topic.BUSINESS) {
                if (context.townEnabled()) view = townView(ticket, context, view, profile, after);
                view = TrainingPresentation.add(view, profile, context.employed(), context.training(), reserveTrain);
            }
            if (topic == ResidentDialogue.Topic.TIPS && !tipList.isEmpty()) {
                var errand = context.townEnabled() ? townTasks.current(ticket.playerId(), context.at()) : Optional.<TownTask>empty();
                view = TipsPresentation.view(view, tipList, ticket.playerId(), clock.dayAt(context.at()), errand, context.training(),
                        context.at(), cast);
            }
            if (topic == ResidentDialogue.Topic.HISTORY && historyRuntime != null) {
                // Contracts on: History also says how many contracts you kept for this resident in the last 30 days.
                int kept = historyRuntime.contracts().keptFor(profile.id(), ticket.playerId(),
                        TownContractRepository.keptSince(clock.dayAt(context.at())));
                if (kept > 0) view = view.withNotice(ContractPresentation.kept(kept));
            }
            if (topic == ResidentDialogue.Topic.CONTRACTS) {
                view = contractRuntime == null ? new EncounterView(view.name(), view.occupation(), view.familiarity(),
                        ContractPresentation.UNAVAILABLE, view.topics())
                        : contractView(contractRuntime, contractFlags, ticket, context, view, profile, after);
            }
            if (topic == ResidentDialogue.Topic.COMMERCE) {
                view = commerce != null
                        ? commerce.view(view, profile, after, ticket.playerId(), context.at(), clock.dayAt(context.at()),
                                context.fullPrices(), context.commerceEnabled(), hireNotice)
                        : new EncounterView(view.name(), view.occupation(), view.familiarity(), "The order book is unavailable right now.",
                        view.topics());
            }
            return view;
        }, result -> {
            var next = sessions.complete(ticket.playerId(), ticket.token(), now());
            if (next.isEmpty()) {
                sessions.close(ticket.playerId(), ticket.token());
                return;
            }
            Player player = Bukkit.getPlayer(ticket.playerId());
            variants.put(key, (variant + 1) % 6);
            show(player, next.get(), result.withActivity(topic, renderer.activity(ticket.residentId()).orElse(null)));
        }, () -> sessions.close(ticket.playerId(), ticket.token()));
    }

    private boolean townEnabled() {
        return plugin.getConfig().getBoolean("town.enabled", false) && plugin.getConfig().getBoolean("town.errands-enabled", false);
    }

    private boolean townActor(EncounterSessions.Ticket ticket) {
        Player player = Bukkit.getPlayer(ticket.playerId());
        return player != null && player.isOnline() && player.hasPermission("prosperitynpc.use")
                && plugin.killSwitches().isSystemEnabled() && plugin.killSwitches().isRenderingEnabled()
                && valid(ticket) && renderer.isAuthoritativeAvatar(ticket.residentId(), ticket.avatarId(), ticket.worldId());
    }

    public boolean trainingActor(Player player, UUID resident) {
        if (!Bukkit.isPrimaryThread() || player == null || Bukkit.getPlayer(player.getUniqueId()) != player) return false;
        return sessions.current(player.getUniqueId(), now()).filter(ticket -> ticket.residentId().equals(resident))
                .map(this::townActor).orElse(false);
    }

    private EncounterView townView(EncounterSessions.Ticket ticket, Context context, EncounterView base,
                                   ResidentProfile profile,
                                   RelationshipMemory.State relationship) throws SQLException {
        return TownOfferPresentation.view(base, profile, relationship, townTasks.current(ticket.playerId(), context.at()),
                townTasks.history(ticket.playerId(), context.at(), 3), clock.dayAt(context.at()), context.reachable(), cast);
    }

    /** Flags read on main for one request: contracts on, partnerships on, rank perk live. */
    private record ContractFlags(ContractCatalogue catalogue, boolean contracts, boolean partners, boolean rankLive) { }

    private ContractFlags contractFlags() {
        return new ContractFlags(plugin.contractCatalogue(), plugin.contractsOn(), plugin.partnershipsOn(), plugin.partnerRankLive());
    }

    /** The "Contracts and partners" topic shows while either feature is on and the hand-over runtime can start. */
    private boolean contractsTopicShown() {
        return (plugin.contractsOn() || plugin.partnershipsOn()) && plugin.contractRuntime() != null;
    }

    private EncounterView contractView(ContractRuntime runtime, ContractFlags flags,
                                       EncounterSessions.Ticket ticket, Context context, EncounterView base,
                                       ResidentProfile profile,
                                       RelationshipMemory.State relationship) throws SQLException {
        var facts = runtime.facts(flags.catalogue(), flags.contracts(), flags.partners(), flags.rankLive(), ticket.playerId(),
                profile.id(), context.reachable(), context.at());
        return ContractPresentation.view(base, profile, relationship, facts, cast, clock);
    }

    private static boolean contractKind(ResidentAction.Kind kind) {
        return switch (kind) {
            case CONTRACT_ACCEPT, HANDOVER, CONTRACT_CANCEL, CONTRACT_DIRECTIONS, PARTNER, PARTNER_CONFIRM, PARTNER_END,
                 PARTNER_END_CONFIRM, CONTRACT_BACK -> true;
            default -> false;
        };
    }

    /** The feature an action needs, read live on main. */
    private boolean contractActionAllowed(ResidentAction.Kind kind) {
        if (plugin.contractRuntime() == null) return false;
        return switch (kind) {
            case CONTRACT_ACCEPT, HANDOVER, CONTRACT_CANCEL, CONTRACT_DIRECTIONS -> plugin.contractsOn();
            case PARTNER, PARTNER_CONFIRM, PARTNER_END, PARTNER_END_CONFIRM -> plugin.partnershipsOn();
            case CONTRACT_BACK -> plugin.contractsOn() || plugin.partnershipsOn();
            default -> false;
        };
    }

    /** Hand over or confirm a partnership: items move, so it runs through the hand-over runtime, not the read worker. */
    private void contractHandover(Player player, EncounterSessions.Ticket ticket, UUID token, ResidentAction selected) {
        var runtime = plugin.contractRuntime();
        var catalogue = plugin.contractCatalogue();
        var offer = catalogue.partnerOffer(ticket.residentId());
        if (runtime == null || !selected.recipientId().equals(ticket.residentId())) {
            viewClosed(player.getUniqueId(), token);
            return;
        }
        // The offer must still be live and the one the confirmation showed, or nothing is taken.
        if (selected.kind() == ResidentAction.Kind.PARTNER_CONFIRM
                && !ContractPresentation.confirmStillValid(offer, selected.taskId(), plugin.partnerRankLive())) {
            viewClosed(player.getUniqueId(), token);
            note(player, ContractPresentation.UNAVAILABLE); return;
        }
        UUID operation = UUID.randomUUID();
        if (pending.putIfAbsent(player.getUniqueId(), operation) != null) {
            viewClosed(player.getUniqueId(), token);
            return;
        }
        ResidentAction.Kind kind = selected.kind();
        BooleanSupplier authority = () -> ownsOperation(pending, player.getUniqueId(), operation) && townActor(ticket)
                && contractActionAllowed(kind);
        Consumer<ContractRuntime.Outcome> response = outcome -> {
            try {
                if (!ownsOperation(pending, player.getUniqueId(), operation) || !townActor(ticket)) return;
                var next = sessions.complete(ticket.playerId(), ticket.token(), now());
                if (next.isEmpty()) {
                    viewClosed(ticket.playerId(), ticket.token());
                    return;
                }
                String line = outcome.line() != null ? outcome.line()
                        : ContractPresentation.result(outcome.result().code(), outcome.result().contract(), outcome.result().partnership(),
                                outcome.result().detail(), plugin.contractCatalogue(), cast, clock, Instant.now());
                show(player, next.orElseThrow(), new EncounterView(TownTaskCatalogue.name(ticket.residentId(), cast),
                        "Contracts", "Handover", line, List.of(ResidentDialogue.Topic.ROUTINE, ResidentDialogue.Topic.HOME,
                        ResidentDialogue.Topic.HISTORY, ResidentDialogue.Topic.BUSINESS, ResidentDialogue.Topic.COMMERCE,
                        ResidentDialogue.Topic.ABOUT)));
            } finally { pending.remove(player.getUniqueId(), operation); }
        };
        try {
            if (kind == ResidentAction.Kind.HANDOVER) runtime.handover(player, selected.taskId(), ticket.residentId(), authority, response);
            else runtime.partner(player, offer.orElseThrow(), ContractDefinition.PARTNER_START.equals(selected.definitionId()),
                    catalogue, authority, response);
        } catch (RuntimeException unavailable) {
            pending.remove(player.getUniqueId(), operation);
            viewClosed(player.getUniqueId(), token);
            note(player, ContractPresentation.UNAVAILABLE);
        }
    }

    /** Worker: the read-and-write contract actions that move no items. */
    private ActionResult contractAction(ResidentAction selected, EncounterSessions.Ticket ticket, Context context,
                                        ContractRuntime runtime, ContractFlags flags) throws Exception {
        var catalogue = flags.catalogue();
        var repository = runtime.contracts();
        var day = clock.dayAt(context.at());
        var profile = residents.find(ticket.residentId()).orElseThrow();
        var relationship = residents.readRelationship(ticket.residentId(), ticket.playerId());
        UUID directions = null;
        String result = null;
        switch (selected.kind()) {
            case CONTRACT_ACCEPT -> {
                var definition = catalogue.find(selected.definitionId()).orElseThrow();
                if (!definition.giverId().equals(ticket.residentId())
                        || !definition.recipientId().equals(ticket.residentId()) && !context.reachable().contains(definition.recipientId()))
                    throw new IllegalStateException("Contract no longer reachable");
                var outcome = repository.accept(definition, ticket.playerId(), context.at(), day, catalogue.perPlayerPerDay());
                result = ContractPresentation.result(outcome.code(), outcome.contract(), outcome.partnership(), outcome.detail(),
                        catalogue, cast, clock, context.at());
            }
            case CONTRACT_CANCEL -> {
                var outcome = repository.cancel(selected.taskId(), ticket.playerId(), context.at());
                result = ContractPresentation.result(outcome.code(), outcome.contract(), outcome.partnership(), outcome.detail(),
                        catalogue, cast, clock, context.at());
            }
            case CONTRACT_DIRECTIONS -> {
                var active = repository.current(ticket.playerId(), context.at());
                if (active.isEmpty() || !active.get().contractId().equals(selected.taskId())
                        || active.get().status() != TownContract.Status.ACTIVE)
                    result = ContractPresentation.EXPIRED;
                else {
                    directions = active.get().definition().recipientId();
                    result = "I'll point you toward their market stop. They may be out on their routine.";
                }
            }
            case PARTNER -> {
                var offer = catalogue.partnerOffer(ticket.residentId());
                if (!ContractPresentation.offerLive(offer, flags.rankLive())) result = ContractPresentation.UNAVAILABLE;
                else {
                    boolean top = flags.rankLive() && repository.advertisingTop(ticket.playerId());
                    return new ActionResult(ContractPresentation.confirmPartner(profile, offer.get(),
                            ContractDefinition.PARTNER_START.equals(selected.definitionId()),
                            catalogue.termDays(), flags.rankLive(), top), null, null);
                }
            }
            case PARTNER_END -> {
                var current = runtime.partnerships().current(ticket.playerId(), day, context.at());
                if (current.isEmpty() || !current.get().partnershipId().equals(selected.taskId())
                        || !current.get().residentId().equals(ticket.residentId())) result = ContractPresentation.UNAVAILABLE;
                else return new ActionResult(ContractPresentation.confirmEnd(profile, current.get().partnershipId()), null, null);
            }
            case PARTNER_END_CONFIRM -> {
                var current = runtime.partnerships().current(ticket.playerId(), day, context.at());
                if (current.isEmpty() || !current.get().partnershipId().equals(selected.taskId())
                        || !current.get().residentId().equals(ticket.residentId())) result = ContractPresentation.UNAVAILABLE;
                else {
                    runtime.partnerships().end(ticket.playerId(), Partnership.EndReason.OWNER_ENDED,
                            context.at(), day);
                    result = ContractPresentation.ENDED;
                }
            }
            case CONTRACT_BACK -> { }
            default -> throw new IllegalStateException("Not a contract action");
        }
        var base = EncounterView.of(profile, relationship, ResidentDialogue.reply(profile, relationship,
                ResidentDialogue.Topic.CONTRACTS, context.worldTime()));
        var view = contractView(runtime, flags, ticket, context, base, profile, relationship);
        String line = result == null ? view.line() : TownOfferPresentation.replyLine(result, view.line());
        return new ActionResult(new EncounterView(view.name(), view.occupation(), view.familiarity(), line, view.topics(),
                view.actions()), directions, null);
    }

    public void act(Player player, UUID token, int index) { act(player, token, index, true); }

    private void act(Player player, UUID token, int index, boolean mayDefer) {
        if (!allowed(player) || !plugin.getConfig().getBoolean("town.enabled", false)) {
            viewClosed(player.getUniqueId(), token);
            return;
        }
        var current = sessions.current(player.getUniqueId(), now());
        Offered presented = offered.get(player.getUniqueId());
        if (current.isEmpty() || !current.get().token().equals(token) || !valid(current.get())
                || presented == null || !presented.token().equals(token) || index < 0 || index >= presented.actions().size()) {
            note(player, "That opportunity is no longer open. Speak with the resident again.");
            return;
        }
        if (pending.containsKey(player.getUniqueId())) {
            note(player, "One moment, please.");
            return;
        }
        var admitted = sessions.admit(player.getUniqueId(), token, now());
        if (admitted.isEmpty()) {
            if (mayDefer) {
                UUID operation = UUID.randomUUID();
                if (pending.putIfAbsent(player.getUniqueId(), operation) == null) {
                    try { Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (pending.remove(player.getUniqueId(), operation)) act(player, token, index, false);
                    }, 25L); }
                    catch (RuntimeException stopping) { pending.remove(player.getUniqueId(), operation); }
                }
            } else note(player, "Please wait a moment before choosing another action.");
            return;
        }
        EncounterSessions.Ticket ticket = admitted.orElseThrow();
        ResidentAction selected = presented.actions().get(index);
        boolean visit = selected.kind() == ResidentAction.Kind.VISIT;
        if (!townActor(ticket)) { viewClosed(player.getUniqueId(), token); return; }
        if (longerConversations) renderer.acknowledge(ticket.residentId(), player);
        if (selected.kind() == ResidentAction.Kind.ORDER || selected.kind() == ResidentAction.Kind.COLLECT) {
            offered.remove(player.getUniqueId());
            if (commerce == null || !selected.recipientId().equals(ticket.residentId())) {
                viewClosed(player.getUniqueId(), token);
                return;
            }
            UUID operation = UUID.randomUUID();
            if (pending.putIfAbsent(player.getUniqueId(), operation) != null) {
                viewClosed(player.getUniqueId(), token);
                return;
            }
            BooleanSupplier authority = () -> ownsOperation(pending, player.getUniqueId(), operation) && townActor(ticket);
            Consumer<String> response = line -> {
                try {
                    if (!authority.getAsBoolean()) return;
                    var next = sessions.complete(ticket.playerId(), ticket.token(), now());
                    if (next.isEmpty()) {
                        viewClosed(ticket.playerId(), ticket.token());
                        return;
                    }
                    show(player, next.orElseThrow(), new EncounterView(TownTaskCatalogue.name(ticket.residentId(), cast),
                            "Trade desk", "Order book", line, List.of(ResidentDialogue.Topic.ROUTINE, ResidentDialogue.Topic.HOME,
                            ResidentDialogue.Topic.HISTORY, ResidentDialogue.Topic.BUSINESS, ResidentDialogue.Topic.COMMERCE,
                            ResidentDialogue.Topic.ABOUT)));
                } finally { pending.remove(player.getUniqueId(), operation); }
            };
            try {
                if (selected.kind() == ResidentAction.Kind.ORDER) commerce.order(player, selected.order(), authority, response);
                else commerce.collect(player, selected.taskId(), ticket.residentId(), authority, response);
            } catch (RuntimeException unavailable) {
                pending.remove(player.getUniqueId(), operation);
                viewClosed(player.getUniqueId(), token);
                note(player, "The trade desk needs confirmation. Recorded receipts are retained; do not buy again blindly.");
            }
            return;
        }
        if (contractKind(selected.kind())) {
            offered.remove(player.getUniqueId());
            if (!contractActionAllowed(selected.kind()) || !selected.recipientId().equals(ticket.residentId())) {
                viewClosed(player.getUniqueId(), token);
                return;
            }
            if (selected.kind() == ResidentAction.Kind.HANDOVER || selected.kind() == ResidentAction.Kind.PARTNER_CONFIRM) {
                contractHandover(player, ticket, token, selected); return;
            }
            var runtime = plugin.contractRuntime();
            var flags = contractFlags();
            ResidentAction.Kind kind = selected.kind();
            submit(player.getUniqueId(), () -> contractActionAllowed(kind) && townActor(ticket),
                    context -> contractAction(selected, ticket, context, runtime, flags), result -> {
                var next = sessions.complete(ticket.playerId(), ticket.token(), now());
                if (next.isEmpty()) {
                    viewClosed(ticket.playerId(), ticket.token());
                    return;
                }
                show(player, next.orElseThrow(), result.view());
                if (result.directionsTo() != null) {
                    var point = renderer.meetingPoint(result.directionsTo(), ticket.worldId());
                    if (point.isEmpty() || !guide.start(player, point.orElseThrow(), TownTaskCatalogue.name(result.directionsTo(), cast)
                            + " - meeting point"))
                        note(player, "That public destination is unavailable right now. Your contract is kept.");
                }
            }, () -> viewClosed(ticket.playerId(), ticket.token()));
            return;
        }
        if (selected.kind() == ResidentAction.Kind.TRAIN) {
            offered.remove(player.getUniqueId());
            if (training == null || !selected.recipientId().equals(ticket.residentId())) {
                viewClosed(player.getUniqueId(), token);
                return;
            }
            UUID operation = UUID.randomUUID();
            if (pending.putIfAbsent(player.getUniqueId(), operation) != null) {
                viewClosed(player.getUniqueId(), token);
                return;
            }
            try {
                training.request(player, ticket.residentId(), selected.definitionId(), result -> {
                    try {
                        if (!ownsOperation(pending, player.getUniqueId(), operation) || !townActor(ticket)) return;
                        var next = sessions.complete(ticket.playerId(), ticket.token(), now());
                        if (next.isEmpty()) {
                            viewClosed(ticket.playerId(), ticket.token());
                            return;
                        }
                        show(player, next.orElseThrow(), new EncounterView(TownTaskCatalogue.name(ticket.residentId(), cast),
                                "Practice", "Training", TrainingPresentation.result(result),
                                List.of(ResidentDialogue.Topic.ROUTINE, ResidentDialogue.Topic.HOME, ResidentDialogue.Topic.HISTORY,
                                        ResidentDialogue.Topic.BUSINESS, ResidentDialogue.Topic.COMMERCE, ResidentDialogue.Topic.ABOUT)));
                    } finally { pending.remove(player.getUniqueId(), operation); }
                });
            } catch (RuntimeException unavailable) {
                pending.remove(player.getUniqueId(), operation);
                viewClosed(player.getUniqueId(), token);
                note(player, "Training confirmation is unavailable. Check with the resident before retrying.");
            }
            return;
        }
        if (!visit && !townEnabled()) {
            viewClosed(player.getUniqueId(), token);
            return;
        }
        offered.remove(player.getUniqueId());
        boolean partnersOnForErrand = plugin.partnershipsOn();
        submit(player.getUniqueId(), () -> (visit ? plugin.getConfig().getBoolean("town.enabled", false) : townEnabled())
                && townActor(ticket), context -> {
            UUID directions = null;
            String result;
            if (visit) {
                directions = ticket.residentId();
                result = "I'll mark that public stop for you. Follow the compass; it points straight there, not around obstacles.";
            } else if (selected.kind() == ResidentAction.Kind.DIRECTIONS) {
                var active = townTasks.current(ticket.playerId(), context.at());
                if (active.isEmpty() || !active.get().taskId().equals(selected.taskId())
                        || !active.get().definition().recipientId().equals(selected.recipientId()))
                    result = "That errand is no longer active.";
                else {
                    directions = selected.recipientId();
                    result = "I'll point you toward their district. They may be out on their routine.";
                }
            } else {
                TownTaskRepository.Result outcome = switch (selected.kind()) {
                    case ACCEPT -> {
                        var definition = TownTaskCatalogue.find(selected.definitionId(), cast).orElseThrow();
                        if (!definition.giverId().equals(ticket.residentId())
                                || !context.reachable().contains(definition.recipientId()))
                            throw new IllegalStateException("Offer no longer reachable");
                        yield townTasks.accept(definition, ticket.playerId(), context.at(), clock.dayAt(context.at()));
                    }
                    case COMPLETE -> townTasks.complete(selected.taskId(), ticket.playerId(), ticket.residentId(),
                            context.at(), clock.dayAt(context.at()));
                    case CANCEL -> townTasks.cancel(selected.taskId(), ticket.playerId(), context.at());
                    case DIRECTIONS, VISIT, TRAIN, ORDER, COLLECT, CONTRACT_ACCEPT, HANDOVER, CONTRACT_CANCEL, CONTRACT_DIRECTIONS,
                         PARTNER, PARTNER_CONFIRM, PARTNER_END, PARTNER_END_CONFIRM, CONTRACT_BACK ->
                            throw new IllegalStateException("Already handled external action");
                };
                result = TownOfferPresentation.result(outcome.code());
            }
            var profile = residents.find(ticket.residentId()).orElseThrow();
            var relationship = residents.readRelationship(ticket.residentId(), ticket.playerId());
            var base = EncounterView.of(profile, relationship, ResidentDialogue.reply(profile, relationship,
                    ResidentDialogue.Topic.BUSINESS, context.worldTime(), 0, null, partnersOnForErrand));
            var view = visit ? base : townView(ticket, context, base, profile, relationship);
            return new ActionResult(new EncounterView(view.name(), view.occupation(), view.familiarity(),
                    visit ? result : TownOfferPresentation.replyLine(result, view.line()), view.topics(), view.actions()), directions,
                    visit ? selected.definitionId() : null);
        }, result -> {
            var next = sessions.complete(ticket.playerId(), ticket.token(), now());
            if (next.isEmpty()) {
                viewClosed(ticket.playerId(), ticket.token());
                return;
            }
            show(player, next.orElseThrow(), result.view());
            if (result.directionsTo() != null) {
                var point = result.stopTag() == null ? renderer.meetingPoint(result.directionsTo(), ticket.worldId())
                        : renderer.publicStop(result.directionsTo(), ticket.worldId(), result.stopTag());
                String label = result.stopTag() == null ? TownTaskCatalogue.name(result.directionsTo(), cast) + " - meeting point"
                        : EncounterView.label(result.stopTag()) + " - public stop";
                if (point.isEmpty() || !guide.start(player, point.orElseThrow(), label))
                    note(player, "That public destination is unavailable right now. Any active note is retained.");
            }
        }, () -> viewClosed(ticket.playerId(), ticket.token()));
    }

    private <T> void submit(UUID playerId, BooleanSupplier admissible, Read<T> read, Consumer<T> deliver, Runnable discard) {
        UUID operation = UUID.randomUUID();
        if (pending.putIfAbsent(playerId, operation) != null) {
            discard.run();
            return;
        }
        try {
            worker.execute(() -> {
                T result;
                try {
                    var admission = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                        if (!ownsOperation(pending, playerId, operation) || !admissible.getAsBoolean()) return null;
                        Player current = Bukkit.getPlayer(playerId);
                        Set<String> employed = new HashSet<>();
                        if (training != null && training.available())
                            for (String job : List.of("builder", "woodcutter", "farmer", "fisherman"))
                                if (training.isEmployed(playerId, job)) employed.add(job);
                        return new Context(Instant.now().truncatedTo(ChronoUnit.MILLIS), current.getWorld().getTime(),
                                renderer.routableResidents(current.getWorld().getUID()), townEnabled(), Set.copyOf(employed),
                                training == null ? null : training.current(playerId), plugin.commercePrices().snapshot(),
                                commerce != null && commerce.enabled());
                    });
                    Context context;
                    try { context = admission.get(2, TimeUnit.SECONDS); }
                    finally { admission.cancel(false); }
                    if (context == null) {
                        finish(playerId, operation, discard);
                        return;
                    }
                    result = read.run(context);
                } catch (Exception error) {
                    if (!closed) plugin.getLogger().warning("Resident encounter unavailable: " + error.getClass().getSimpleName());
                    finish(playerId, operation, () -> {
                        discard.run();
                        if (allowed(Bukkit.getPlayer(playerId))) note(Bukkit.getPlayer(playerId),
                                "This conversation is unavailable just now.");
                    });
                    return;
                }
                finish(playerId, operation, () -> {
                    if (admissible.getAsBoolean()) deliver.accept(result);
                    else discard.run();
                });
            });
        } catch (RejectedExecutionException full) {
            pending.remove(playerId, operation);
            discard.run();
            note(Bukkit.getPlayer(playerId), "The resident desk is busy. Please try again shortly.");
        }
    }

    private void finish(UUID playerId, UUID operation, Runnable action) {
        if (closed) {
            pending.remove(playerId, operation);
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try { if (!closed && ownsOperation(pending, playerId, operation)) action.run(); }
                catch (RuntimeException | LinkageError error) {
                    sessions.removePlayer(playerId);
                    plugin.getLogger().warning("Resident callback discarded: " + error.getClass().getSimpleName());
                }
                finally { pending.remove(playerId, operation); }
            });
        } catch (RuntimeException stopping) { pending.remove(playerId, operation); }
    }

    static boolean ownsOperation(Map<UUID, UUID> requests, UUID player, UUID operation) {
        return operation.equals(requests.get(player));
    }

    private void supersedePreview(UUID player) {
        pending.remove(player);
        cancelPendingConversation(sessions, player, now());
        renderer.cancelPendingPreview(player);
    }

    static void cancelPendingConversation(EncounterSessions sessions, UUID player, long now) {
        sessions.current(player, now).filter(ticket -> sessions.isPending(player, ticket.token()))
                .ifPresent(ticket -> sessions.close(player, ticket.token()));
    }

    private List<String> tips() {
        return TipsPresentation.clean(plugin.getConfig().getList("town.tips"));
    }

    private void show(Player player, EncounterSessions.Ticket ticket, EncounterView shown) {
        if (!valid(ticket)) return;
        if (longerConversations && renderer.pauseEndingSoon(ticket.residentId())) shown = shown.withNotice(PAUSE_ENDING_LINE);
        EncounterView data = shown.confirmation() ? shown
                : ContractPresentation.withTopic(TipsPresentation.withTopic(shown, !tips().isEmpty()), contractsTopicShown());
        offered.put(ticket.playerId(), new Offered(ticket.token(), data.actions()));
        ResidentPresentation.show(() -> valid(ticket), () -> {
            NativeUi selected = nativeUi;
            if (selected != null && selected.handles(player.getUniqueId())) {
                if (!valid(ticket) || nativeUi != selected) return false;
                return selected.show(player, ticket, data);
            }
            if (!valid(ticket)) return false;
            showChat(player, ticket, data);
            return true;
        }, () -> sessions.close(ticket.playerId(), ticket.token()),
                () -> menu.closeOwned(player, ticket.token()), () -> {
            showChat(player, ticket, data);
        }, error -> plugin.getLogger().warning("Resident presentation unavailable: " + error.getClass().getSimpleName()));
    }

    private void showChat(Player player, EncounterSessions.Ticket ticket, EncounterView data) {
        if (!valid(ticket)) return;
        NPC speaker = nearby(player, ticket.residentId());
        if (speaker == null || (nativeUi != null && nativeUi.handles(player.getUniqueId()))
                || !speech.show(player, speaker.getEntity(), data.line(), () -> valid(ticket))) {
            player.sendMessage(Component.text(data.name() + ": ", NamedTextColor.AQUA)
                    .append(Component.text(data.line(), NamedTextColor.WHITE)));
        }
        Component choices = Component.empty();
        Component actions = Component.empty();
        for (int index = 0; index < data.actions().size(); index++) {
            actions = actions.append(Component.text("[" + data.actions().get(index).label() + "] ", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand("/resident act " + ticket.token() + " " + index)));
        }
        if (nativeUi != null && nativeUi.handles(player.getUniqueId())
                && plugin.getConfig().getBoolean("ux.bedrock-typed-fallback", false)) {
            for (String line : BedrockTypedFallback.lines(data.actions(), data.topics()))
                player.sendMessage(Component.text(line, NamedTextColor.GRAY));
            return;
        }
        if (!data.actions().isEmpty()) player.sendMessage(actions);
        for (var topic : data.topics()) {
            String label = switch (topic) {
                case ROUTINE -> "Day";
                case HOME -> "Home";
                case HISTORY -> "History";
                case BUSINESS -> "Business";
                case COMMERCE -> "Supplies";
                case ABOUT -> "About";
                case TIPS -> "Tips";
                case CONTRACTS -> "Contracts";
                default -> EncounterView.topicLabel(topic);
            };
            choices = choices.append(Component.text("[" + label + "] ", NamedTextColor.AQUA)
                    .hoverEvent(HoverEvent.showText(Component.text(EncounterView.topicLabel(topic))))
                    .clickEvent(ClickEvent.runCommand("/resident choose " + ticket.token() + " " + topic.name().toLowerCase(Locale.ROOT))));
        }
        player.sendMessage(choices.append(Component.text("[Bye]", NamedTextColor.GRAY)
                .clickEvent(ClickEvent.runCommand("/resident leave " + ticket.token()))));
    }

    public void viewClosed(UUID player, UUID token) {
        if (sessions.current(player, now()).filter(ticket -> ticket.token().equals(token)).isPresent())
            renderer.releaseConversation(player);
        sessions.close(player, token);
        offered.computeIfPresent(player, (id, view) -> view.token().equals(token) ? null : view);
    }

    public void leave(Player player, UUID token) {
        viewClosed(player.getUniqueId(), token);
        speech.remove(player.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ResidentMenu.View view
                    && view.ticket.token().equals(token)) player.closeInventory();
        });
    }

    private void sweep() {
        try { speech.tick(); }
        catch (RuntimeException | LinkageError error) { speech.close(); }
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
            var ticket = sessions.current(player.getUniqueId(), now());
            if (ticket.isPresent() && !valid(ticket.get())) leave(player, ticket.get().token());
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof ResidentMenu.View view
                    && (ticket.isEmpty() || !view.ticket.token().equals(ticket.get().token())
                    || !valid(ticket.get()))) player.closeInventory();
            } catch (RuntimeException | LinkageError error) {
                // An incompatible runtime surface disables encounters, rather than
                // silently losing the cleanup timer or logging on every tick.
                plugin.getLogger().warning("Resident encounters stopped: " + error.getClass().getSimpleName());
                close();
                return;
            }
        }
        offered.keySet().removeIf(id -> sessions.current(id, now()).isEmpty());
    }

    @EventHandler public void quit(PlayerQuitEvent event) { departed(event.getPlayer()); }
    @EventHandler public void worldChange(PlayerChangedWorldEvent event) { departed(event.getPlayer()); }
    private void departed(Player player) {
        renderer.releaseConversation(player.getUniqueId());
        guide.stop(player.getUniqueId());
        speech.remove(player.getUniqueId());
        sessions.removePlayer(player.getUniqueId());
        pending.remove(player.getUniqueId());
        offered.remove(player.getUniqueId());
        variants.keySet().removeIf(key -> key.player().equals(player.getUniqueId()));
        renderer.dismissPreviews(player.getUniqueId());
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Resident encounters require an in-game player.");
            return true;
        }
        if (!allowed(player)) {
            note(player, "Resident encounters are unavailable.");
            return true;
        }
        String action = args.length == 0 ? "talk" : args[0].toLowerCase(Locale.ROOT);
        if (!action.equals("leave") && !action.equals("dismiss") && !action.equals("choose") && !action.equals("act")
                && !probeAllowed(player.getUniqueId())) {
            note(player, "Please wait a moment before speaking again.");
            return true;
        }
        try {
            if (action.equals("stop")) {
                guide.stop(player.getUniqueId());
                note(player, "Resident directions stopped.");
            } else if (action.equals("leave")) {
                UUID requested = args.length == 2 ? strictUuid(args[1]) : null;
                if (args.length > 2) return true;
                sessions.current(player.getUniqueId(), now()).filter(t -> requested == null || t.token().equals(requested))
                        .ifPresent(t -> { leave(player, t.token());
                note(player, "Conversation ended."); });
            } else if (action.equals("dismiss")) {
                renderer.releaseConversation(player.getUniqueId());
                pending.remove(player.getUniqueId());
                sessions.removePlayer(player.getUniqueId());
                renderer.dismissPreviews(player.getUniqueId());
                note(player, "Your resident previews are dismissed.");
            } else if (action.equals("preview") && (args.length == 2 || args.length == 3)) {
                if (!previewAllowed(player)) {
                    note(player, "Resident previews are staff-only.");
                    return true;
                }
                UUID resident = resolve(args[1]);
                String district = args.length == 3 ? args[2] : null;
                if (district != null && !district.matches("[a-zA-Z0-9_-]{1,64}")) {
                    note(player, "Invalid route district.");
                    return true;
                }
                UUID world = player.getWorld().getUID();
                var position = player.getLocation();
                supersedePreview(player.getUniqueId());
                submit(player.getUniqueId(), () -> {
                    Player current = Bukkit.getPlayer(player.getUniqueId());
                    return previewAllowed(current) && current.getWorld().getUID().equals(world)
                            && current.getLocation().distanceSquared(position) <= 1;
                }, context -> residents.find(resident).orElseThrow(() -> new IllegalStateException("Cast not seeded")),
                        profile -> note(player, renderer.previewResident(player, profile, district)), () -> { });
            } else if ((action.equals("choose") || action.equals("act")) && args.length == 2
                    && plugin.getConfig().getBoolean("ux.bedrock-typed-fallback", false)) {
                var open = sessions.current(player.getUniqueId(), now());
                if (open.isEmpty()) note(player, "No conversation is open. Use /resident talk.");
                else if (action.equals("choose")) choose(player, open.get().token(), typedTopic(args[1], contractsTopicShown()));
                else {
                    int index = BedrockTypedFallback.index(args[1]);
                    if (index < 0) note(player, "Unknown resident or conversation choice."); else act(player, open.get().token(), index);
                }
            } else if (action.equals("choose") && args.length == 3) {
                choose(player, strictUuid(args[1]), typedTopic(args[2], contractsTopicShown()));
            } else if (action.equals("act") && args.length == 3) {
                act(player, strictUuid(args[1]), Integer.parseInt(args[2]));
            } else if (action.equals("talk") && args.length <= 2) {
                UUID id = args.length == 2 ? resolve(args[1]) : renderer.activeResidentIds().stream()
                        .filter(resident -> nearby(player, resident) != null)
                        .min(Comparator.comparingDouble(resident -> nearby(player, resident).getEntity().getLocation()
                                .distanceSquared(player.getLocation()))).orElse(null);
                if (id == null) note(player, "No resident is nearby."); else begin(player, id);
            } else {
                note(player, previewAllowed(player)
                        ? "/resident talk [name] | preview <name> [district] | dismiss | leave | stop"
                        : "/resident talk [name] | leave | stop");
            }
        } catch (IllegalArgumentException invalid) { note(player, "Unknown resident or conversation choice."); }
        return true;
    }

    /**
     * A typed topic. While the contracts topic is hidden it counts as unknown: it throws before any session check,
     * so a stale conversation is left alone.
     */
    static ResidentDialogue.Topic typedTopic(String text, boolean contractsShown) {
        var topic = ResidentDialogue.Topic.valueOf(text.toUpperCase(Locale.ROOT));
        if (topic == ResidentDialogue.Topic.CONTRACTS && !contractsShown) throw new IllegalArgumentException("Unknown topic");
        return topic;
    }

    private static UUID strictUuid(String text) {
        if (!text.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw new IllegalArgumentException("Invalid UUID");
        return UUID.fromString(text);
    }
    private UUID resolve(String key) {
        return cast.byFirstName(key).map(p -> p.id()).orElseGet(() -> strictUuid(key));
    }
    private static void note(Player player, String line) {
        if (player != null) player.sendMessage(Component.text(line, NamedTextColor.GRAY));
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !allowed(player)) return List.of();
        var options = args.length == 1 ? (previewAllowed(player) ? List.of("talk", "preview", "dismiss", "leave", "stop")
                : List.of("talk", "leave", "stop"))
                : args.length == 2 && List.of("talk", "preview").contains(args[0]) ? cast.firstNames() : List.<String>of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(s -> s.startsWith(prefix)).toList();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        // Null counts every resident as mid-chat, so no outfit swap runs against a closed controller.
        try { renderer.setConversationProbe(null); }
        catch (RuntimeException | LinkageError error) {
            plugin.getLogger().warning("Outfit probe cleanup failed: " + error.getClass().getSimpleName());
        }
        speech.close();
        guide.close();
        watcher.cancel();
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(menu);
        sessions.clear();
        pending.clear();
        variants.clear();
        offered.clear();
        requestCooldown.clear();
        worker.shutdownNow();
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                renderer.releaseConversation(p.getUniqueId());
                if (p.getOpenInventory().getTopInventory().getHolder() instanceof ResidentMenu.View) p.closeInventory();
                renderer.dismissPreviews(p.getUniqueId());
            } catch (RuntimeException | LinkageError error) {
                plugin.getLogger().warning("Resident view cleanup failed: " + error.getClass().getSimpleName());
            }
        }
        if (nativeUi != null) {
            try { nativeUi.close(); }
            catch (RuntimeException | LinkageError error) {
                plugin.getLogger().warning("Resident native UI cleanup failed: " + error.getClass().getSimpleName());
            }
        }
    }
}
