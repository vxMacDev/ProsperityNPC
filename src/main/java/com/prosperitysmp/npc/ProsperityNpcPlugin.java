package com.prosperitysmp.npc;

import com.prosperitysmp.npc.alert.StaffAlerts;
import com.prosperitysmp.npc.appearance.ResidentAppearanceCatalogue;
import com.prosperitysmp.npc.appearance.ResidentAppearanceFiles;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.MoneySource;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.arm.ArmAdapter;
import com.prosperitysmp.npc.arm.OwnershipFence;
import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.command.NpcAdminCommand;
import com.prosperitysmp.npc.command.NpcNavCommand;
import com.prosperitysmp.npc.command.NpcPropertyCommand;
import com.prosperitysmp.npc.command.NpcTownCommand;
import com.prosperitysmp.npc.command.PropertyCommand;
import com.prosperitysmp.npc.command.ResidentLabCommand;
import com.prosperitysmp.npc.command.TenantsCommand;
import com.prosperitysmp.npc.commerce.CommercePriceBook;
import com.prosperitysmp.npc.commerce.CommerceRuntime;
import com.prosperitysmp.npc.commerce.InventoryCustodyGate;
import com.prosperitysmp.npc.commerce.ItemDeliveryRuntime;
import com.prosperitysmp.npc.config.EconomyLimits;
import com.prosperitysmp.npc.config.KillSwitches;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.DatabaseWorkers;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.charge.BillingRuntime;
import com.prosperitysmp.npc.charge.ChargeCatalogue;
import com.prosperitysmp.npc.economy.PurchasePrices;
import com.prosperitysmp.npc.economy.UtilityPrices;
import com.prosperitysmp.npc.economy.RepairPrices;
import com.prosperitysmp.npc.economy.UtilityRepository;
import org.bukkit.configuration.ConfigurationSection;
import com.prosperitysmp.npc.economy.AccountLinkRepository;
import com.prosperitysmp.npc.economy.AccountingProjection;
import com.prosperitysmp.npc.economy.BillPayments;
import com.prosperitysmp.npc.economy.BridgeApi;
import com.prosperitysmp.npc.economy.CommercePaymentRuntime;
import com.prosperitysmp.npc.economy.EconomyEventRepository;
import com.prosperitysmp.npc.economy.FinancialFence;
import com.prosperitysmp.npc.economy.FloodgateOwnerLookup;
import com.prosperitysmp.npc.economy.ItemRepairRepository;
import com.prosperitysmp.npc.economy.ItemRepairRuntime;
import com.prosperitysmp.npc.economy.ItemRepairSettings;
import com.prosperitysmp.npc.economy.LinkedAccounts;
import com.prosperitysmp.npc.economy.PurchaseRepository;
import com.prosperitysmp.npc.economy.PurchaseService;
import com.prosperitysmp.npc.economy.ReserveAccount;
import com.prosperitysmp.npc.economy.UtilityPaymentRuntime;
import com.prosperitysmp.npc.economy.BuiltInMoneySource;
import com.prosperitysmp.npc.economy.MoneySources;
import com.prosperitysmp.npc.economy.accounting.AccountingSink;
import com.prosperitysmp.npc.interaction.BedrockNpcUi;
import com.prosperitysmp.npc.interaction.FloodgateLinkListener;
import com.prosperitysmp.npc.interaction.OwnerDeskController;
import com.prosperitysmp.npc.interaction.ResidentEncounterController;
import com.prosperitysmp.npc.market.HousingRuntime;
import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.pieces.Deferred;
import com.prosperitysmp.npc.pieces.DeferredMoneySource;
import com.prosperitysmp.npc.pieces.DeferredPropertyBackend;
import com.prosperitysmp.npc.pieces.Pieces;
import com.prosperitysmp.npc.property.guard.FenceHooks;
import com.prosperitysmp.npc.property.guard.PropertyGuard;
import com.prosperitysmp.npc.property.worldguard.ReflectiveWorldGuard;
import com.prosperitysmp.npc.property.worldguard.WorldGuardListedBackend;
import com.prosperitysmp.npc.nav.NavGraphScanner;
import com.prosperitysmp.npc.property.AutoCareRuntime;
import com.prosperitysmp.npc.property.DueRentFirst;
import com.prosperitysmp.npc.property.OwnerAway;
import com.prosperitysmp.npc.property.OwnerAwayDecisionRepository;
import com.prosperitysmp.npc.property.OwnerDeskRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.PropertyService;
import com.prosperitysmp.npc.property.PublicRatingService;
import com.prosperitysmp.npc.property.TenantRepository;
import com.prosperitysmp.npc.publication.ChronicleRuntime;
import com.prosperitysmp.npc.publication.ChronicleSettings;
import com.prosperitysmp.npc.publication.OccupancyPublicationHost;
import com.prosperitysmp.npc.publication.OccupancyPublicationSettings;
import com.prosperitysmp.npc.publication.OccupancyTokenFile;
import com.prosperitysmp.npc.publication.PublicOccupancyRepository;
import com.prosperitysmp.npc.publication.PublicationShutdown;
import com.prosperitysmp.npc.render.CitizensAdapter;
import com.prosperitysmp.npc.render.RenderManager;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentCastFile;
import com.prosperitysmp.npc.resident.ResidentRepository;
import com.prosperitysmp.npc.town.CivicPlacementRepository;
import com.prosperitysmp.npc.town.ContractCatalogue;
import com.prosperitysmp.npc.town.ContractRuntime;
import com.prosperitysmp.npc.town.PartnerRateReader;
import com.prosperitysmp.npc.town.PartnershipRepository;
import com.prosperitysmp.npc.town.TownContractRepository;
import com.prosperitysmp.npc.training.TrainingRuntime;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * ProsperityNPC: persistent residents and a living town economy for ProsperitySMP.
 *
 * <p>Ships in STANDBY. Resident encounter development is separately staff-gated;
 * its nonfinancial lab must not be mistaken for the complete property beta.
 */
public final class ProsperityNpcPlugin extends JavaPlugin {

    private KillSwitches killSwitches;
    private EconomyLimits economyLimits;
    private Database database;
    private final DatabaseWorkers databaseWorkers = new DatabaseWorkers();
    private ArmAdapter armAdapter;
    // The selected pieces (dev.64). Built-in arm, vault and skript are chosen in onEnable exactly where the old
    // code built them; a third-party id binds once the server has finished starting.
    private PropertyBackend propertyBackend;
    private MoneySource moneySource;
    private AccountingSink accountingSink;
    private String accountingSinkId = "";
    // money.source and property.backend as they were when the plugin enabled; the pieces in use were chosen from these.
    private String moneySourceId, propertyBackendId;
    // ARM: READY exactly when the fence is set. Other backends: READY once bound and guarded (dev.67). Never null.
    private PropertyGuard propertyGuard = PropertyGuard.waiting("not installed");
    private WorldGuardListedBackend worldGuardListed;
    private Deferred<MoneySource> deferredMoney;
    private Deferred<PropertyBackend> deferredProperty;
    private Pieces pieces;
    private OwnershipFence ownershipFence;
    private HousingRuntime housingRuntime;
    private UtilityPaymentRuntime utilityPayments;
    // dev.65 configured bills and taxes; null without a database or utility runtime.
    private BillingRuntime billing;
    private AccountingProjection accountingProjection;
    private final FinancialFence financialFence = new FinancialFence();
    private PropertyRepository propertyRepository;
    private PropertyService propertyService;
    private EconomyEventRepository economyEventRepository;
    private BridgeApi bridgeApi;
    private boolean dailyCycleRunning;
    // A staff /npcadmin rent or match is applying due rent changes first; the daily cycle waits. Never set while
    // rentChangesDue() is false (housing.rent-change-delay-days 0 and nothing pending), so it stays false with
    // that feature off.
    private boolean staffRentChangeStep;
    private final StaffAlerts staffAlerts = new StaffAlerts(
            () -> getConfig().getBoolean("alerts.enabled", false), this::alertDay, message -> getLogger().warning(message));
    // Vacation protection: one main-thread decision per property per economy day. housing.vacation-days 0 = off.
    private final OwnerAway ownerAway = new OwnerAway(
            () -> getConfig().getInt(OwnerAway.CONFIG, 0), this::alertDay,
            System::currentTimeMillis, ProsperityNpcPlugin::ownerSighting);
    private LocalDate ownerAwayLogged;
    // Durable owner-away decisions (V32). Only touched while housing.vacation-days is above 0.
    private OwnerAwayDecisionRepository ownerAwayDecisions;
    private ExecutorService ownerAwayWorker;
    private PurchaseService purchaseService;
    private NavGraphRepository navGraphRepository;
    private NavGraphScanner navGraphScanner;
    private CitizensAdapter citizensAdapter;
    private RenderManager renderManager;
    // Both are set once in onEnable (cast first), before anything reads them. Full restart to change.
    private ResidentCast residentCast = ResidentCast.builtIn();
    private ResidentAppearanceCatalogue appearances = new ResidentAppearanceCatalogue();
    private int skippedSkinEntries;
    private String appearanceStatus = "not loaded";
    private ResidentLabCommand residentLab;
    private ResidentEncounterController encounters;
    private OwnerDeskController ownerDesk;
    private PublicRatingService publicRating;
    private NpcNavCommand navCommand;
    private NpcTownCommand townCommand;
    private TrainingRuntime training;
    private final CommercePriceBook commercePrices = new CommercePriceBook();
    private CommercePaymentRuntime commercePayments;
    private ItemDeliveryRuntime itemDelivery;
    // Shared per-player inventory exclusion for deliveries and item repairs (main thread).
    private final InventoryCustodyGate inventoryGate = new InventoryCustodyGate();
    // item-repairs.*: read on start and /npcadmin reload (main thread); read by payment workers for the cash block.
    private volatile ItemRepairSettings itemRepairSettings = ItemRepairSettings.OFF;
    private PurchasePrices purchasePrices = PurchasePrices.DEFAULT;
    private UtilityPrices utilityPrices = UtilityPrices.DEFAULT;
    private RepairPrices repairPrices = RepairPrices.DEFAULT;
    private ItemRepairRuntime itemRepairs;
    // town.contracts.* and town.partnerships.*: read on start and /npcadmin reload (main thread); read by workers.
    private volatile ContractCatalogue contractCatalogue = ContractCatalogue.OFF;
    private ContractRuntime contractRuntime;
    // Startup recovery of contract hand-overs: null until it ran; held IDs are alerted once the runtime starts.
    private List<TownContractRepository.Held> contractRecovery;
    private CommerceRuntime commerce;
    private AutoCareRuntime autoCare;
    private OccupancyPublicationHost publication;
    private BukkitTask publicationTask;
    private String publicationStatus = "DISABLED";
    private ChronicleRuntime chronicle;
    private BukkitTask chronicleTask;
    private String chronicleStatus = "DISABLED";
    // linked-accounts.*: read on start and /npcadmin reload (main thread); the flag is read by rent batches on workers.
    private volatile LinkedAccounts.Settings linkedSettings = LinkedAccounts.Settings.OFF;
    private AccountLinkRepository accountLinks;
    private ThreadPoolExecutor linkWorker;
    private Listener linkListener;
    private FloodgateOwnerLookup ownerLookup;

    @Override
    public void onEnable() {
        if (databaseWorkers.sealed()) throw new IllegalStateException("NPC worker lifecycle closed; full server restart required");
        saveDefaultConfig();
        moneySourceId = configuredMoneySourceId();
        propertyBackendId = configuredPropertyBackendId();
        this.killSwitches = KillSwitches.load(this);
        try {
            purchasePrices = readPurchasePrices(getConfig());
        } catch (IllegalArgumentException invalid) {
            getLogger().severe("Invalid owner-prices: " + invalid.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            utilityPrices = readUtilityPrices(getConfig());
        } catch (IllegalArgumentException invalid) {
            getLogger().severe("Invalid utility-prices: " + invalid.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            repairPrices = readRepairPrices(getConfig());
        } catch (IllegalArgumentException invalid) {
            getLogger().severe("Invalid repair-prices: " + invalid.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        reloadEconomyLimits();
        loadItemRepairSettings();
        initResidentCast();
        loadContractCatalogue();
        initDatabase();
        initPropertySubsystem();
        initAccountLinks();
        initOwnershipCoordination();
        if (ownershipFence != null && database != null && database.isConnected())
            housingRuntime = new HousingRuntime(this);
        initEconomy();
        initPieces();
        initSettlement();
        initNav();
        initRender();
        initTown();
        initResidentLab();
        initEncounters();
        initTraining();
        initCommerce();
        initItemRepairs();
        if (database != null && database.isConnected() && propertyService != null && propertyBackend != null) {
            try {
                autoCare = new AutoCareRuntime(this);
            } catch (RuntimeException failure) {
                if (!reserveUnsetExplains(failure)) getLogger().warning("Auto-care runtime unavailable; recurring payments held.");
            }
        }
        initOwnerDesk();
        initPublicRating();
        initBedrockUi();
        initLinkDiscovery();
        initPublication();
        initChronicle();

        PluginCommand cmd = getCommand("npcadmin");
        if (cmd != null) {
            NpcAdminCommand executor = new NpcAdminCommand(this);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        } else {
            getLogger().severe("Command 'npcadmin' is missing from plugin.yml; admin surface unavailable.");
        }

        PluginCommand propertyCmd = getCommand("property");
        if (propertyCmd != null) {
            propertyCmd.setExecutor(new PropertyCommand(this));
            propertyCmd.setTabCompleter(new PropertyCommand(this));
        }
        PluginCommand tenantsCmd = getCommand("tenants");
        if (tenantsCmd != null) {
            tenantsCmd.setExecutor(new TenantsCommand(this));
            tenantsCmd.setTabCompleter(new TenantsCommand(this));
        }

        getLogger().info("ProsperityNPC " + getPluginMeta().getVersion() + " enabled.");
        if (!killSwitches.isSystemEnabled()) {
            getLogger().info("STANDBY: automatic simulation, economy and normal avatars are off. "
                    + "Administrative tools and the separately gated resident lab remain available.");
        }
        getLogger().info("Health: " + killSwitches.summary());
    }

    /** Reached only by a command whose feature did not start, so players never see the raw usage line. */
    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        sender.sendMessage(Component.text("/" + command.getName()
                + " is unavailable right now.", NamedTextColor.RED));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                                String alias, String[] args) {
        return List.of();
    }

    @Override
    public void onDisable() {
        if (ownershipFence != null) ownershipFence.stop();
        var drains = new ArrayList<CompletableFuture<?>>();
        if (autoCare != null) stopComponent(() -> drains.add(autoCare.closeAsync().toCompletableFuture()), drains);
        if (housingRuntime != null) stopComponent(housingRuntime::close, drains);
        // Order is CommerceWiring.closeOrder (tested): commerce, payments, item repairs, then item delivery.
        // A part that never started is skipped.
        for (var part : com.prosperitysmp.npc.commerce.CommerceWiring.closeOrder(commerce != null, commercePayments != null,
                itemRepairs != null, itemDelivery != null))
            stopComponent(() -> drains.add(switch (part) {
                case COMMERCE -> commerce.closeAsync().toCompletableFuture();
                case COMMERCE_PAYMENTS -> commercePayments.closeAsync().toCompletableFuture();
                case ITEM_REPAIRS -> itemRepairs.closeAsync().toCompletableFuture();
                case ITEM_DELIVERY -> itemDelivery.closeAsync().toCompletableFuture();
            }), drains);
        stopComponent(commercePrices::invalidate, drains);
        if (billing != null) stopComponent(billing::close, drains);
        if (utilityPayments != null) stopComponent(utilityPayments::close, drains);
        if (accountingProjection != null) stopComponent(accountingProjection::close, drains);
        if (publicationTask != null) {
            stopComponent(publicationTask::cancel, drains);
            publicationTask = null;
        }
        if (publication != null) stopComponent(() -> drains.add(publication.shutdown().toCompletableFuture()), drains);
        if (chronicleTask != null) {
            stopComponent(chronicleTask::cancel, drains);
            chronicleTask = null;
        }
        if (chronicle != null) stopComponent(() -> drains.add(chronicle.shutdown().toCompletableFuture()), drains);
        if (propertyService != null) stopComponent(propertyService::close, drains);
        if (ownerDesk != null) stopComponent(ownerDesk::close, drains);
        if (publicRating != null) stopComponent(publicRating::close, drains);
        if (purchaseService != null) stopComponent(purchaseService::close, drains);
        if (encounters != null) stopComponent(encounters::close, drains);
        if (contractRuntime != null) stopComponent(() -> drains.add(contractRuntime.closeAsync().toCompletableFuture()), drains);
        if (training != null) stopComponent(() -> drains.add(training.closeAsync().toCompletableFuture()), drains);
        if (townCommand != null) stopComponent(townCommand::close, drains);
        if (navCommand != null) stopComponent(navCommand::close, drains);
        if (residentLab != null) stopComponent(residentLab::close, drains);
        if (renderManager != null) stopComponent(renderManager::stop, drains);
        drains.add(databaseWorkers.shutdown().toCompletableFuture());
        if (database != null) {
            Database closingDatabase = database;
            database = null;
            PublicationShutdown.releaseAfter(CompletableFuture.allOf(drains.toArray(CompletableFuture[]::new)),
                    closingDatabase::close)
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) getLogger().severe(
                                "NPC worker/database shutdown incomplete; operator review required. No failure contents logged.");
                    });
        }
        getLogger().info("ProsperityNPC disabled.");
    }

    /** Register before a constructor can submit any SQL work; failed instances remain owned. */
    public void trackDatabaseWorker(ExecutorService worker) {
        databaseWorkers.register(worker);
    }

    private static void stopComponent(Runnable stop, List<CompletableFuture<?>> drains) {
        try {
            stop.run();
        } catch (RuntimeException | LinkageError failure) {
            drains.add(CompletableFuture.failedFuture(new IllegalStateException("NPC component cleanup failed")));
        }
    }

    private void initPublication() {
        try {
            var configured = OccupancyPublicationSettings.read(getConfig().getConfigurationSection("publication"));
            if (configured.isEmpty()) return;
            if (database == null || !database.isConnected()) {
                publicationStatus = "HELD_DATABASE";
                getLogger().warning("Publication held: database unavailable.");
                return;
            }
            var settings = configured.get();
            var clock = Clock.systemUTC();
            var dataDirectory = getDataFolder().toPath();
            publication = new OccupancyPublicationHost(dataDirectory.resolve("publication"),
                    new PublicOccupancyRepository(database, clock)::readEvidence, clock, System::nanoTime,
                    settings.interval(), settings.maxAge(), settings.recoveryAge(), settings.retentionAge());
            publication.configure(settings.approvals());
            if (settings.httpPort() != 0) publication.startTransport(
                    new InetSocketAddress("127.0.0.1", settings.httpPort()),
                    () -> OccupancyTokenFile.read(dataDirectory));
            publicationTask = getServer().getScheduler().runTaskTimer(this, publication::tick, 1L, 20L);
            getLogger().info("Private occupancy staging configured; transport status is available in npcadmin health.");
        } catch (RuntimeException | LinkageError failure) {
            publicationStatus = "HELD_CONFIG_OR_STARTUP";
            holdPublicationForReload();
            getLogger().warning("Publication configuration/startup rejected; no supplied values logged.");
        }
    }

    /** Config reload revokes first. Re-enabling or changing publication policy requires a restart. */
    public void holdPublicationForReload() {
        holdChronicle();
        if (publicationTask != null) {
            publicationTask.cancel();
            publicationTask = null;
        }
        if (publication != null) {
            publication.shutdown().whenComplete((ignored, failure) -> {
                if (failure != null) getLogger().severe("Publication shutdown failed; operator review required.");
            });
            getLogger().info("Publication held until restart; old scopes revoked.");
        }
    }

    public String publicationStatus() {
        return publication == null ? publicationStatus : publication.status() + " / HTTP " + publication.transportStatus();
    }

    private void initChronicle() {
        try {
            var settings = ChronicleSettings.read(
                    getConfig().getConfigurationSection("chronicle"), getConfig().getConfigurationSection("publication"));
            if (!settings.enabled()) return;
            if (database == null || !database.isConnected()) {
                chronicleStatus = "HELD_DATABASE";
                return;
            }
            chronicle = new ChronicleRuntime(database, Clock.systemUTC(),
                    ZoneId.of(getConfig().getString("clock.timezone", "America/Chicago")),
                    getConfig().getInt("clock.daily-reset-hour", 4), settings);
            chronicleTask = getServer().getScheduler().runTaskTimer(this, chronicle::tick, 1L, 20L);
            getLogger().info("Chronicle private previews/recovery configured; public delivery requires separate authority.");
        } catch (RuntimeException | LinkageError failure) {
            chronicleStatus = "HELD_CONFIG_OR_STARTUP";
            holdChronicle();
            getLogger().warning("Chronicle startup held; no supplied values logged.");
        }
    }

    private void holdChronicle() {
        if (chronicleTask != null) {
            chronicleTask.cancel();
            chronicleTask = null;
        }
        if (chronicle != null) chronicle.shutdown().whenComplete((ignored, failure) -> {
            if (failure != null) getLogger().severe("Chronicle shutdown unconfirmed; operator review required.");
        });
    }

    public String chronicleStatus() {
        return chronicle == null ? chronicleStatus : chronicle.status().name();
    }

    public ChronicleRuntime chronicle() {
        return chronicle;
    }

    private void initBedrockUi() {
        if (!getServer().getPluginManager().isPluginEnabled("floodgate") || encounters == null) return;
        try {
            var ui = new BedrockNpcUi(this, encounters, ownerDesk);
            encounters.setNativeUi(ui);
            if (ownerDesk != null) ownerDesk.setNativeUi(ui);
            getLogger().info("Native Bedrock NPC forms available; existing authorization and release gates apply.");
        } catch (RuntimeException | LinkageError failure) {
            getLogger().warning("Native Bedrock NPC forms unavailable: " + failure.getClass().getSimpleName());
        }
    }

    /**
     * linked-accounts: the link repository exists whenever the database does, so saved links and day pins apply to the
     * rent cap even without Floodgate. Its worker saves Floodgate links and runs staff reads and splits.
     */
    private void initAccountLinks() {
        linkedSettings = LinkedAccounts.Settings.from(getConfig());
        if (database == null || !database.isConnected()) return;
        accountLinks = new com.prosperitysmp.npc.economy.AccountLinkRepository(database);
        var worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), r -> {
                    var t = new Thread(r, "npc-account-links");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        trackDatabaseWorker(worker);
        linkWorker = worker;
    }

    /** Registers Floodgate join discovery when linked-accounts.enabled is on. Safe to call again after a reload. */
    private void initLinkDiscovery() {
        if (!linkedSettings.enabled() || accountLinks == null || linkListener != null) return;
        boolean floodgate = getServer().getPluginManager().isPluginEnabled("floodgate");
        if (!floodgate) {
            getLogger().info(LinkedAccounts.NO_FLOODGATE);
            return;
        }
        linkListener = optionalFloodgate(true, true, () -> FloodgateLinkListener.create(linkHost()), getLogger()::warning,
                "Linked account discovery");
        if (linkListener != null) {
            getServer().getPluginManager().registerEvents(linkListener, this);
            getLogger().info("Linked accounts: Floodgate links are saved when a linked player joins.");
        }
    }

    /**
     * Builds an optional Floodgate-backed part. Null when not wanted or Floodgate is missing. A missing or
     * mismatched Floodgate API (RuntimeException or LinkageError) logs one warning and returns null, never a crash.
     */
    public static <T> T optionalFloodgate(boolean wanted, boolean floodgatePresent, Supplier<T> create,
                                          Consumer<String> warn, String what) {
        if (!wanted || !floodgatePresent) return null;
        try {
            return create.get();
        } catch (RuntimeException | LinkageError failure) {
            warn.accept(what + " unavailable: " + failure.getClass().getSimpleName());
            return null;
        }
    }

    private FloodgateLinkListener.Host linkHost() {
        return new FloodgateLinkListener.Host() {
            @Override public boolean enabled() {
                return linkedSettings.enabled() && accountLinks != null;
            }

            @Override public LocalDate today() {
                return alertDay();
            }

            @Override public void observe(UUID javaId, UUID bedrock,
                                          Consumer<AccountLinkRepository.Observed> done) {
                var day = alertDay();
                var reserve = reserveOrNull();
                var links = accountLinks;
                submitLinkWork(() -> {
                    try {
                        var outcome = links.observe(javaId, bedrock,
                                AccountLinkRepository.Source.FLOODGATE_LOGIN,
                                Instant.now(), day, reserve);
                        postMain(() -> done.accept(outcome));
                    } catch (SQLException | RuntimeException failure) {
                        getLogger().warning("Linked accounts: link not saved (" + failure.getClass().getSimpleName() + ").");
                    }
                });
            }

            @Override public void inSavedGroup(UUID account, Consumer<Boolean> done) {
                var day = alertDay();
                var links = accountLinks;
                submitLinkWork(() -> {
                    try {
                        boolean grouped = links.inSavedGroup(account, day);
                        postMain(() -> done.accept(grouped));
                    } catch (SQLException | RuntimeException failure) {
                        /* No notice; nothing changes. */
                    }
                });
            }

            @Override public void warn(String message) {
                getLogger().warning(message);
            }

            @Override public void tell(UUID player, String message) {
                var online = getServer().getPlayer(player);
                if (online != null) online.sendMessage(Component.text(message,
                        NamedTextColor.YELLOW));
            }

            @Override public String linkedNotice() {
                return LinkedAccounts.linkedNotice(economyLimits.benefitUnitDailyCapCents());
            }

            @Override public String unlinkedNotice() {
                return LinkedAccounts.unlinkedNotice();
            }
        };
    }

    /** Runs SQL for linked accounts on its own bounded worker. False when it is missing or full. */
    public boolean submitLinkWork(Runnable work) {
        var worker = linkWorker;
        if (worker == null) return false;
        try {
            worker.execute(work);
            return true;
        } catch (RejectedExecutionException busy) {
            getLogger().warning("Linked accounts: worker busy; one link task was skipped.");
            return false;
        }
    }

    private UUID reserveOrNull() {
        String text = ReserveAccount.liveText(getConfig());
        return ReserveAccount.isCanonical(text) ? UUID.fromString(text) : null;
    }

    /** Main thread. /npcadmin reload: rereads linked-accounts.* and registers discovery if it was just turned on. */
    public void reloadLinkedAccounts() {
        linkedSettings = LinkedAccounts.Settings.from(getConfig());
        initLinkDiscovery();
    }

    /** linked-accounts.enabled as last loaded. Safe from any thread. */
    public boolean linkedAccountsEnabled() {
        return linkedSettings.enabled();
    }

    public LinkedAccounts.Settings linkedAccountSettings() {
        return linkedSettings;
    }

    /** Null without a database. */
    public AccountLinkRepository accountLinks() {
        return accountLinks;
    }

    /** Today's economy day from clock.timezone and clock.daily-reset-hour. */
    public LocalDate economyToday() {
        return alertDay();
    }

    /** Main thread: reads clock.* now. today() on the result is safe later on any thread and reads the time then. */
    public EconomyClock economyClock() {
        return new EconomyClock(Clock.systemUTC(),
                ZoneId.of(getConfig().getString("clock.timezone", "America/Chicago")), getConfig().getInt("clock.daily-reset-hour", 4));
    }

    /** One health line: off, or on with Floodgate's linking mode and the lookup sub-flag. */
    public String linkedAccountsStatus() {
        var settings = linkedSettings;
        if (!settings.enabled()) return "off";
        String floodgate;
        if (!getServer().getPluginManager().isPluginEnabled("floodgate")) floodgate = "Floodgate not found, saved links only";
        else {
            String mode = optionalFloodgate(true, true, FloodgateLinkListener::linkingMode, message -> { }, "Floodgate linking");
            floodgate = (mode == null ? "Floodgate linking unknown" : mode) + (linkListener == null ? ", join discovery not running" : "");
        }
        return "on; " + floodgate + "; owner lookup " + (settings.lookupOwners() ? "on, " + settings.lookupDailyLimit() + " a day" : "off");
    }

    /** Main thread, at the start of each daily cycle. Does nothing unless linked-accounts.lookup-floodgate-owners is on. */
    private void startOwnerLookup() {
        var settings = linkedSettings;
        if (!settings.lookupOwners() || accountLinks == null || propertyService == null || linkWorker == null) return;
        if (!getServer().getPluginManager().isPluginEnabled("floodgate")) return;
        if (ownerLookup == null) ownerLookup = optionalFloodgate(true, true, () -> FloodgateOwnerLookup.create(
                Duration.ofSeconds(10), this::submitLinkWorkOrThrow, getLogger()::info), getLogger()::warning, "Linked account lookup");
        if (ownerLookup == null) return;
        var day = alertDay();
        var reserve = reserveOrNull();
        var links = accountLinks;
        var owners = propertyService.getEnrolled().stream().map(Property::ownerUuid)
                .filter(Objects::nonNull).toList();
        try {
            ownerLookup.start(true, day, owners, settings.lookupDailyLimit(), (javaId, bedrock) -> links.observe(javaId, bedrock,
                    AccountLinkRepository.Source.FLOODGATE_LOOKUP, Instant.now(), day, reserve));
        } catch (RuntimeException | LinkageError failure) {
            getLogger().warning("Linked account lookup did not start: " + failure.getClass().getSimpleName());
        }
    }

    private void submitLinkWorkOrThrow(Runnable work) {
        var worker = linkWorker;
        if (worker == null) throw new RejectedExecutionException("No link worker");
        worker.execute(work);
    }

    private void initPublicRating() {
        if (!getConfig().getBoolean("housing.public-rating-enabled", false) || database == null
                || !database.isConnected() || propertyBackend == null) return;
        try {
            publicRating = new PublicRatingService(this,
                    new OwnerDeskRepository(database, Clock.systemUTC(),
                            ZoneId.of(getConfig().getString("clock.timezone", "America/Chicago")),
                            getConfig().getInt("clock.daily-reset-hour", 4), purchasePrices));
        } catch (RuntimeException | LinkageError error) {
            getLogger().warning("Public property ratings unavailable: " + error.getClass().getSimpleName());
            publicRating = null;
        }
    }

    private void initOwnerDesk() {
        if (database == null || !database.isConnected() || propertyBackend == null) return;
        try {
            ownerDesk = new OwnerDeskController(this, new OwnerDeskRepository(database, Clock.systemUTC(),
                    ZoneId.of(getConfig().getString("clock.timezone", "America/Chicago")),
                    getConfig().getInt("clock.daily-reset-hour", 4),
                    // Off-thread read of the live config value: only a plain Integer above 0 shows the line.
                    () -> PropertyService.rentChangeDelayDays(
                            getConfig().get(PropertyService.RENT_CHANGE_DELAY)) > 0,
                    // linked-accounts: the desk line, live; off reads no link table.
                    this::linkedAccountsEnabled, () -> economyLimits.benefitUnitDailyCapCents(),
                    // town.partnerships: the partner line, live; off reads no partnership table.
                    () -> partnershipsOn() ? new OwnerDeskRepository.PartnerConfig(true, partnerRankLive(),
                            contractCatalogue::configPerks) : OwnerDeskRepository.PartnerConfig.OFF, purchasePrices)
                    // A home enrolled under another property backend is not shown (V45).
                    .backendId(propertyRepository == null ? null : propertyRepository.backendId()));
        } catch (RuntimeException | LinkageError error) {
            getLogger().warning("Owner desk unavailable: " + error.getClass().getSimpleName());
            if (ownerDesk != null) ownerDesk.close();
            ownerDesk = null;
        }
    }

    /** Read once per full start. The skin universe is built from the cast before the skin file is read. */
    private void initResidentCast() {
        residentCast = ResidentCastFile.load(getDataFolder().toPath(),
                getConfig().getBoolean("residents.cast-file-enabled", false));
        appearances = ResidentAppearanceCatalogue.forCast(residentCast);
        // Source and count only. File contents are never logged.
        if (residentCast.source() == ResidentCast.Source.FILE)
            getLogger().info("Resident cast: " + residentCast.profiles().size() + " from residents.yml.");
        else if (residentCast.source() == ResidentCast.Source.FILE_REJECTED)
            getLogger().warning("residents.yml was rejected. Using the built-in three.");
    }

    public ResidentCast residentCast() {
        return residentCast;
    }

    public ResidentAppearanceCatalogue residentAppearances() {
        return appearances;
    }

    public int skippedSkinEntries() {
        return skippedSkinEntries;
    }

    private void initResidentLab() {
        if (database == null || !database.isConnected()) return;
        PluginCommand cmd = getCommand("residentlab");
        if (cmd == null) {
            getLogger().warning("Resident lab command missing from plugin.yml.");
            return;
        }
        residentLab = new ResidentLabCommand(this, new ResidentRepository(database), residentCast, appearances);
        cmd.setExecutor(residentLab);
        cmd.setTabCompleter(residentLab);
    }

    private void initEncounters() {
        if (renderManager == null || database == null || !database.isConnected()) return;
        PluginCommand command = getCommand("resident");
        if (command == null) return;
        recoverContractHandovers();
        try {
            encounters = new ResidentEncounterController(this, renderManager, new ResidentRepository(database),
                    new com.prosperitysmp.npc.town.TownTaskRepository(database), residentCast);
            command.setExecutor(encounters);
            command.setTabCompleter(encounters);
        } catch (RuntimeException | LinkageError e) {
            getLogger().warning("Resident encounter interface unavailable: " + e.getClass().getSimpleName());
            if (encounters != null) encounters.close();
            encounters = null;
        }
    }

    private void initTown() {
        if (renderManager == null || database == null || !database.isConnected()) return;
        PluginCommand command = getCommand("npctown");
        if (command == null) {
            getLogger().warning("Town authoring command missing; civic placements remain unchanged.");
            return;
        }
        townCommand = new NpcTownCommand(this, new CivicPlacementRepository(database),
                new ResidentRepository(database), renderManager, residentCast);
        command.setExecutor(townCommand);
        command.setTabCompleter(townCommand);
    }

    public String townStatus() {
        return townCommand == null ? "HELD_RUNTIME" : getConfig().getBoolean("town.enabled", false)
                ? "ENABLED / errands " + (getConfig().getBoolean("town.errands-enabled", false) ? "ON" : "OFF")
                : "DISABLED";
    }

    private void initTraining() {
        if (encounters == null || database == null || !database.isConnected()) return;
        try {
            var clock = new EconomyClock(Clock.systemUTC(),
                    ZoneId.of(getConfig().getString("clock.timezone", "America/Chicago")),
                    getConfig().getInt("clock.daily-reset-hour", 4));
            training = new TrainingRuntime(this, database, clock,
                    () -> killSwitches.isSystemEnabled() && killSwitches.isEconomyEnabled()
                            && getConfig().getBoolean("town.enabled", false), encounters::trainingActor);
            encounters.setTraining(training);
        } catch (RuntimeException | LinkageError failure) {
            getLogger().warning("Training held: " + failure.getClass().getSimpleName());
        }
    }

    public String trainingStatus() {
        return training == null || !training.available() ? "HELD_JOBS_OR_RUNTIME"
                : getConfig().getBoolean("town.training-enabled", false) ? "CONFIGURED (runtime switches apply)" : "DISABLED";
    }

    private void initCommerce() {
        if (encounters == null || database == null || !database.isConnected() || accountingProjection == null) return;
        try {
            var clock = new EconomyClock(Clock.systemUTC(),
                    ZoneId.of(getConfig().getString("clock.timezone", "America/Chicago")), getConfig().getInt("clock.daily-reset-hour", 4));
            commercePayments = new CommercePaymentRuntime(this, commercePrices, clock);
            itemDelivery = itemCustody();
            commerce = new CommerceRuntime(this, commercePayments, itemDelivery, clock);
            commerce.setPartnerRates(partnerRates());
            encounters.setCommerce(commerce);
        } catch (RuntimeException | LinkageError failure) {
            getLogger().warning("Commerce held: " + failure.getClass().getSimpleName());
            if (commerce != null) commerce.closeAsync();
            if (itemDelivery != null) itemDelivery.closeAsync();
            if (commercePayments != null) commercePayments.closeAsync();
            commerce = null;
            itemDelivery = null;
            commercePayments = null;
        }
    }

    /**
     * Main only. The one item delivery runtime, built on first need while the database is connected. Commerce
     * goods keep system && economy && town.commerce-enabled; item-repair returns need system and the database.
     */
    public com.prosperitysmp.npc.commerce.ItemDeliveryRuntime itemCustody() {
        if (itemDelivery == null && database != null && database.isConnected())
            itemDelivery = new ItemDeliveryRuntime(this, database,
                    com.prosperitysmp.npc.commerce.CommerceWiring.deliveryGate(() -> killSwitches.isSystemEnabled(),
                            () -> killSwitches.isEconomyEnabled(), () -> getConfig().getBoolean("town.commerce-enabled", false),
                            () -> database != null && database.isConnected()), inventoryGate);
        return itemDelivery;
    }

    /** Main thread: reads item-repairs.* and tells the price book which materials to accept. */
    private void loadItemRepairSettings() {
        ItemRepairSettings loaded;
        try {
            loaded = ItemRepairSettings.load(getConfig()::get,
                    ItemRepairRuntime::maxStack);
        } catch (RuntimeException | LinkageError invalid) {
            getLogger().warning("item-repairs settings unreadable; item repairs are held off.");
            loaded = ItemRepairSettings.OFF;
        }
        for (String warning : loaded.warnings()) getLogger().warning(warning);
        itemRepairSettings = loaded;
        try {
            commercePrices.configureMaterials(loaded.priceMaterials());
        } catch (RuntimeException invalid) {
            getLogger().warning("item-repairs materials rejected by the price book; item repairs are held off.");
            itemRepairSettings = ItemRepairSettings.OFF;
            commercePrices.configureMaterials(Set.of());
        }
    }

    /** Main thread. /npcadmin reload: rereads item-repairs.*; a bad value holds only this feature. */
    public void reloadItemRepairs() {
        loadItemRepairSettings();
        if (itemRepairSettings.active()) itemRepairs();
    }

    public PurchasePrices purchasePrices() { return purchasePrices; }

    public UtilityPrices utilityPrices() { return utilityPrices; }

    public RepairPrices repairPrices() { return repairPrices; }

    static RepairPrices readRepairPrices(ConfigurationSection config) {
        Object raw = config.get("repair-prices");
        if (raw == null) return RepairPrices.DEFAULT;
        if (!(raw instanceof ConfigurationSection section))
            throw new IllegalArgumentException("repair-prices must be a settings section");
        return RepairPrices.load(ChargeCatalogue.plain(section));
    }

    public String repairPriceReloadMessage() {
        try {
            return repairPrices.equals(readRepairPrices(getConfig())) ? null
                    : "repair-prices changed; a full server restart is required. Active prices are unchanged.";
        } catch (IllegalArgumentException invalid) {
            return "repair-prices rejected; fix the settings before restarting. Active prices are unchanged.";
        }
    }

    static UtilityPrices readUtilityPrices(ConfigurationSection config) {
        Object raw = config.get("utility-prices");
        if (raw == null) return UtilityPrices.DEFAULT;
        if (!(raw instanceof ConfigurationSection section))
            throw new IllegalArgumentException("utility-prices must be a settings section");
        return UtilityPrices.load(ChargeCatalogue.plain(section));
    }

    public String utilityPriceReloadMessage() {
        try {
            return utilityPrices.equals(readUtilityPrices(getConfig())) ? null
                    : "utility-prices changed; a full server restart is required. Active prices are unchanged.";
        } catch (IllegalArgumentException invalid) {
            return "utility-prices rejected; fix the settings before restarting. Active prices are unchanged.";
        }
    }

    static PurchasePrices readPurchasePrices(ConfigurationSection config) {
        Object raw = config.get("owner-prices");
        if (raw == null) return PurchasePrices.DEFAULT;
        if (!(raw instanceof ConfigurationSection section))
            throw new IllegalArgumentException("owner-prices must be a settings section");
        return PurchasePrices.load(ChargeCatalogue.plain(section));
    }

    public String purchasePriceReloadMessage() {
        try {
            return purchasePrices.equals(readPurchasePrices(getConfig())) ? null
                    : "owner-prices changed; a full server restart is required. Active prices are unchanged.";
        } catch (IllegalArgumentException invalid) {
            return "owner-prices rejected; fix the settings before restarting. Active prices are unchanged.";
        }
    }

    public ItemRepairSettings itemRepairSettings() {
        return itemRepairSettings;
    }

    /**
     * Starts at enable only when the feature is on or earlier hand-ins still need it (running, held or with items to
     * give back). With the feature never used, nothing is built and no item-repair worker runs.
     */
    private void initItemRepairs() {
        if (database == null || !database.isConnected()) return;
        boolean needed = itemRepairSettings.enabled();
        if (!needed) {
            try {
                needed = new ItemRepairRepository(database).hasOpenWork();
            } catch (SQLException | RuntimeException unreadable) {
                getLogger().warning("Item repair rows unreadable at startup; item repairs stay held.");
                return;
            }
        }
        if (needed) startItemRepairs();
    }

    private void startItemRepairs() {
        if (itemRepairs != null || database == null || !database.isConnected()) return;
        try {
            if (itemCustody() == null) return;
            itemRepairs = new ItemRepairRuntime(this, database, inventoryGate, () -> itemDelivery);
        } catch (RuntimeException | LinkageError failure) {
            getLogger().warning("Item repairs held: " + failure.getClass().getSimpleName());
            itemRepairs = null;
        }
    }

    /** Main only. The item repair runtime, started on first use while item-repairs is on. Null when unavailable. */
    public ItemRepairRuntime itemRepairs() {
        if (itemRepairs == null && itemRepairSettings.active()) startItemRepairs();
        return itemRepairs;
    }

    /**
     * Main only. The runtime for collecting returned items. It also runs with item-repairs off when earlier hand-ins
     * left items to give back (started at enable), so those items stay deliverable.
     */
    public ItemRepairRuntime itemRepairReturns() {
        if (itemRepairs == null && itemRepairSettings.enabled()) startItemRepairs();
        return itemRepairs;
    }

    public boolean itemRepairsRunning() {
        return itemRepairs != null;
    }

    /** Main thread: reads town.contracts.* and town.partnerships.* against the active cast. */
    private void loadContractCatalogue() {
        ContractCatalogue loaded;
        try {
            loaded = ContractCatalogue.load(getConfig()::get, residentCast, name -> {
                var material = Material.getMaterial(name);
                return material != null && !material.isLegacy() && material.isItem() && !material.isAir();
            });
        } catch (RuntimeException | LinkageError invalid) {
            getLogger().warning("Contract and partnership settings unreadable; both are held off.");
            loaded = ContractCatalogue.OFF;
        }
        for (String warning : loaded.warnings()) getLogger().warning(warning);
        contractCatalogue = loaded;
    }

    /** Main thread. /npcadmin reload: rereads contracts and partnerships; starts the runtime if one just turned on. */
    public void reloadContracts() {
        loadContractCatalogue();
        if (contractCatalogue.anyOn()) contractRuntime();
    }

    public ContractCatalogue contractCatalogue() {
        return contractCatalogue;
    }

    /** town.enabled and town.contracts.enabled (valid). Any thread. */
    public boolean contractsOn() {
        return getConfig().getBoolean("town.enabled", false) && contractCatalogue.contractsEnabled();
    }

    /** town.enabled and town.partnerships.enabled (valid). Any thread. While off, no partnership perk applies. */
    public boolean partnershipsOn() {
        return getConfig().getBoolean("town.enabled", false) && contractCatalogue.partnershipsEnabled();
    }

    /** The rank perk acts only with partnerships, the rank bonus and housing.advertising-rank-enabled all on. */
    public boolean partnerRankLive() {
        return partnershipsOn() && contractCatalogue.rankBonusEnabled()
                && Boolean.TRUE.equals(getConfig().get("housing.advertising-rank-enabled"));
    }

    /** Current config rank points per resident for daily matching; empty unless the rank perk is live. */
    public Map<UUID, Integer> partnerRankCaps() {
        return partnerRankLive() ? contractCatalogue.rankCaps() : Map.of();
    }

    /**
     * Startup barrier before any encounter: every contract hand-over left in HANDOVER_INTENT becomes AMBIGUOUS (held
     * for staff, never retried). Reads an empty table while the features were never used.
     */
    private void recoverContractHandovers() {
        try {
            var repository = new TownContractRepository(database);
            var held = new ArrayList<TownContractRepository.Held>();
            for (int round = 0; round < 100; round++) {
                var batch = repository.recoverStaleIntents(TownContractRepository.MAX_LIST);
                held.addAll(batch);
                if (batch.isEmpty()) break;
            }
            contractRecovery = List.copyOf(held);
            if (!held.isEmpty()) {
                getLogger().warning("Contract hand-overs held at startup for staff review: " + held.size());
                contractRuntime();
            }
        } catch (SQLException | RuntimeException failure) {
            contractRecovery = null;
            getLogger().warning("Contract hand-over recovery did not finish; contracts and partnerships stay held.");
        }
    }

    /**
     * Main only. The hand-over runtime, started on first need once startup recovery finished. Null while recovery
     * failed, the database is down or both features have never been on.
     */
    public ContractRuntime contractRuntime() {
        if (contractRuntime == null && contractRecovery != null && database != null && database.isConnected()
                && (contractCatalogue.anyOn() || !contractRecovery.isEmpty())) {
            try {
                var clock = new EconomyClock(Clock.systemUTC(),
                        ZoneId.of(getConfig().getString("clock.timezone", "America/Chicago")),
                        getConfig().getInt("clock.daily-reset-hour", 4));
                contractRuntime = new ContractRuntime(this, database, inventoryGate, clock, contractRecovery);
                contractRecovery = List.of();
            } catch (RuntimeException | LinkageError failure) {
                getLogger().warning("Contracts held: " + failure.getClass().getSimpleName());
                contractRuntime = null;
            }
        }
        return contractRuntime;
    }

    /** The partner rate for commerce quotes and orders. Worker threads; no read while partnerships are off. */
    private CommerceRuntime.PartnerRates partnerRates() {
        return new PartnerRateReader(new PartnershipRepository(database),
                () -> contractCatalogue, this::partnershipsOn, Instant::now);
    }

    /**
     * /npcadmin health lines for contracts and partnerships. Empty while both are off and nothing is held, so the
     * flag-off health output has no extra lines.
     */
    public List<String> contractHealthLines() {
        var catalogue = contractCatalogue;
        var runtime = contractRuntime;
        if (!catalogue.anyOn() && runtime == null) return List.of();
        String held = runtime == null ? "?" : runtime.heldText();
        String active = runtime == null ? "?" : runtime.activeText();
        return List.of(
                "contracts: " + (contractsOn() ? "on" : "off") + ", " + catalogue.definitions().size() + " loaded, "
                        + catalogue.skippedCount() + " skipped, " + held + " held for review",
                "partnerships: " + (partnershipsOn() ? "on" : "off") + ", " + active + " active, rank bonus "
                        + (partnerRankLive() ? "live" : "inactive"));
    }

    public CommercePriceBook commercePrices() {
        return commercePrices;
    }

    public String commerceStatus() {
        return com.prosperitysmp.npc.commerce.CommerceWiring.commerceStatus(commercePayments != null, commerce != null,
                itemDelivery != null, () -> commercePayments.status(), () -> itemDelivery.ready());
    }

    /**
     * Connects to the dedicated database and runs migrations. On any failure the
     * plugin continues DB-less; the economy is independently fail-closed, so a
     * missing database can never turn into an unbounded payout.
     */
    private void initDatabase() {
        this.database = new Database();
        DatabaseSettings settings = DatabaseSettings.load(this);
        if (!settings.isConfigured()) {
            getLogger().warning("Database not configured (no password in config or db-password.txt): "
                    + "running WITHOUT a database. Economy stays fail-closed.");
            return;
        }
        try {
            database.connect(settings);
            int version = new MigrationRunner(this, database).run();
            Database.Status st = database.status();
            getLogger().info("Database connected (" + settings.jdbcUrl() + "); schema V" + version
                    + ", " + st.tableCount() + " tables.");
        } catch (Exception e) {
            getLogger().severe("Database init failed: " + e.getMessage()
                    + ": running WITHOUT a database (economy fail-closed).");
            database.close();
        }
    }

    /**
     * Wires the read-only ARM adapter, the property repository/service, the
     * {@code /npcproperty} command, and a periodic ARM ownership reconcile. All
     * of this is read-only against ARM and moves no money.
     */
    private void initPropertySubsystem() {
        if (database == null || !database.isConnected()) {
            getLogger().warning("Property subsystem disabled: no database.");
            return;
        }
        this.armAdapter = new ArmAdapter(this);
        this.propertyBackend = selectPropertyBackend();
        this.propertyRepository = new PropertyRepository(database);
        // Enrollment records the backend; homes enrolled under another one stay out of the cache (V45).
        String backendId = propertyBackendId();
        propertyRepository.backendId(backendId.length() <= 32 ? backendId : backendId.substring(0, 32));
        this.propertyService = new PropertyService(this, propertyBackend, propertyRepository);
        // Before the cache load, so a failed load cannot skip it. Unreadable counts as pending (fails closed).
        propertyService.notePendingRentChanges(PropertyService.pendingRentChangesAtStartup(
                propertyRepository::countPendingRentChanges, getLogger()::warning));
        try {
            propertyService.loadCache();
            getLogger().info("Property subsystem ready: " + propertyService.enrolledCount()
                    + " enrolled; " + propertyBackend.displayName() + " "
                    + (propertyBackend.available() ? "available" : "UNAVAILABLE") + ".");
            otherBackendHomes = PropertyRepository.otherBackendsText(propertyRepository.enrolledUnderOtherBackends(),
                    propertyRepository.backendId());
            if (!otherBackendHomes.isEmpty()) getLogger().warning(otherBackendHomes);
        } catch (SQLException e) {
            getLogger().severe("Failed to load enrolled properties: " + e.getMessage());
        }

        PluginCommand pc = getCommand("npcproperty");
        if (pc != null) {
            NpcPropertyCommand executor = new NpcPropertyCommand(this);
            pc.setExecutor(executor);
            pc.setTabCompleter(executor);
        }

        long ticks = Math.max(1L, getConfig().getLong("enrollment.reconcile-interval-ticks", 6000L));
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (propertyService != null && propertyService.enrolledCount() > 0) {
                propertyService.requestReconcile(r -> {
                    if (!ArmAdapter.ID.equals(propertyBackendId())) {
                        if (PropertyService.reconcileIncomplete(r))
                            getLogger().warning(PropertyService.reconcileIncompleteText(propertyBackend.displayName(), r));
                    } else if (PropertyService.reconcileIncomplete(r)) getLogger().warning(PropertyService.reconcileIncompleteText(r));
                });
            }
        }, ticks, ticks);
    }

    /**
     * Wires the economy-event ledger, the accounting projection and the journaled receipt runtime.
     * There is no boolean Skript money bridge. Runs startup crash-recovery: any CLAIMED event
     * (a crash between money movement and acknowledgement) becomes AMBIGUOUS and is
     * never auto-retried.
     */
    private void initEconomy() {
        if (database == null || !database.isConnected()) {
            getLogger().warning("Economy ledger disabled: no database.");
            return;
        }
        this.economyEventRepository = new EconomyEventRepository(database);
        this.bridgeApi = new BridgeApi(this, economyEventRepository);
        String reserve = bridgeApi.centralBankUuid();
        if (reserve.isBlank())
            getLogger().warning("centralbank.uuid is not set; NPC payments and the scheduled daily cycle stay held until "
                    + "it names your reserve account.");
        else if (!ReserveAccount.isCanonical(reserve))
            getLogger().warning("centralbank.uuid must be a lowercase UUID like 123e4567-e89b-42d3-a456-426614174000; "
                    + "NPC payments and the scheduled daily cycle stay held until it is.");
        this.moneySource = selectMoneySource();
        if (moneySource instanceof BuiltInMoneySource builtIn && !builtIn.probe()) {
            getLogger().warning("Vault economy not found; player purchases will be unavailable.");
        }
        Database receipts = database;
        var itemReceipts = new ItemRepairRepository(receipts);
        var utilityReceipts = ReserveAccount.isCanonical(reserve)
                ? new UtilityRepository(receipts, UUID.fromString(reserve), Clock.systemUTC(), java.time.ZoneOffset.UTC, 0) : null;
        this.purchaseService = new PurchaseService(this, request -> PurchaseRepository.findByRequest(receipts, request),
                itemReceipts::find, (request, payer) -> BillPayments.findByRequest(receipts, request, payer),
                (request, payer) -> {
                    if (utilityReceipts == null) throw new SQLException("Utility receipt reserve is not configured");
                    return utilityReceipts.findByRequest(request, payer);
                });
        accountingSinkId = Pieces.configuredId(getConfig().getString(Pieces.ACCOUNTING_KEY), "skript");
        accountingSink = AccountingSink.forId(accountingSinkId, () -> bridgeApi != null && bridgeApi.isBridgeConnected());
        if (accountingSink == null) {
            getLogger().warning(Pieces.ACCOUNTING_KEY + " '" + accountingSinkId + "' is unknown; use skript or internal");
        } else {
            try {
                accountingProjection = new AccountingProjection(this, accountingSink);
            } catch (RuntimeException failure) {
                if (!reserveUnsetExplains(failure))
                    getLogger().warning("Accounting projection unavailable: " + failure.getClass().getSimpleName());
            }
        }
        getLogger().info("Pieces: money=" + moneySourceId() + " property=" + propertyBackendId()
                + " accounting=" + accountingSinkId);
        if (ownershipFence != null && propertyService != null) {
            try {
                utilityPayments = new UtilityPaymentRuntime(this);
            } catch (RuntimeException failure) {
                if (!reserveUnsetExplains(failure))
                    getLogger().warning("Utility runtime unavailable: " + failure.getClass().getSimpleName());
            }
        }
        // dev.65 bills: with no bills: block this reads nothing, logs nothing and phase 5 stays a pass-through.
        if (utilityPayments != null) {
            try {
                billing = new BillingRuntime(this);
                billing.loadCatalogue(true, line -> { });
            } catch (RuntimeException failure) {
                getLogger().warning("Bills runtime unavailable: " + failure.getClass().getSimpleName());
            }
        }
        try {
            int ambiguous = economyEventRepository.markClaimedAsAmbiguousOnStartup();
            if (ambiguous > 0) {
                getLogger().warning("Startup recovery: " + ambiguous + " orphaned CLAIMED settlement(s) "
                        + "marked AMBIGUOUS (require manual review; never auto-retried).");
            }
        } catch (Exception e) {
            getLogger().severe("Economy startup recovery failed: " + e.getMessage());
        }
        getLogger().info("Economy ledger ready. Payments run only through journaled receipts, and only when "
                + "centralbank.uuid, Vault, economy.payments-enabled, the Skript accounting acknowledgement "
                + "and the system and economy switches all allow it. The Skript heartbeat cannot release money.");
    }

    /**
     * Bootstraps the simulation seed and registers the coordinated daily cycle
     * scheduler. The scheduler fires often but only requests a daily cycle at/after
     * the configured settlement time, and only while the kill switches allow it.
     */
    private void initSettlement() {
        if (database == null || !database.isConnected() || propertyService == null
                || economyEventRepository == null) {
            getLogger().warning("Settlement engine disabled: dependencies missing.");
            return;
        }
        long seed = getConfig().getLong("state.sim-seed", 0L);
        if (seed == 0L) {
            seed = new Random().nextLong();
            if (seed == 0L) {
                seed = 1L;
            }
            getConfig().set("state.sim-seed", seed);
            saveConfig();
        }

        // An invalid clock.timezone or clock.daily-reset-hour stops startup here.
        new EconomyClock(Clock.systemUTC(),
                ZoneId.of(getConfig().getString("clock.timezone", "America/Chicago")),
                getConfig().getInt("clock.daily-reset-hour", 4));
        initOwnerAwayDecisions();
        // Durable per-property decisions and rent reservations make repeated cycles safe. A skipped (owner away)
        // property leaves its durable mark in npc_owner_away_decisions, so a same-day re-run keeps it skipped.
        getServer().getScheduler().runTaskTimer(this, this::maybeAutoSettle, 6000L, 12000L);
        getLogger().info("Coordinated daily cycle ready; legacy settlement remains retired.");
    }

    /**
     * Wires the navigation graph repository, block scanner, and {@code /npcnav}
     * command. This is an admin build tool (draft/validate/publish); it moves no
     * money and renders no NPCs, so it is not gated by any kill switch.
     */
    private void initNav() {
        if (database == null || !database.isConnected()) {
            getLogger().warning("Navigation subsystem disabled: no database.");
            return;
        }
        this.navGraphRepository = new NavGraphRepository(database);
        this.navGraphScanner = new NavGraphScanner(this);
        PluginCommand navCmd = getCommand("npcnav");
        if (navCmd != null) {
            navCommand = new NpcNavCommand(this);
            navCmd.setExecutor(navCommand);
            navCmd.setTabCompleter(navCommand);
        }
        getLogger().info("Navigation subsystem ready.");
    }

    /**
     * Wires the Citizens-backed visible-NPC renderer. Only started when
     * Citizens is present; the tick loop then self-gates on the {@code rendering}
     * kill switch, so nothing renders until it is switched on. Rendering is purely
     * cosmetic: a Citizens fault degrades to no avatars and never affects the sim.
     */
    private void initRender() {
        if (database == null || !database.isConnected() || propertyService == null || navGraphRepository == null) {
            return;
        }
        if (!getServer().getPluginManager().isPluginEnabled("Citizens")) {
            getLogger().info("Citizens absent or disabled - NPC rendering disabled (economy unaffected).");
            return;
        }
        try {
            try {
                if (residentCast.source() == ResidentCast.Source.BUILT_IN) {
                    appearances.replace(ResidentAppearanceFiles.load(getDataFolder().toPath()));
                } else {
                    // A well-formed entry for a resident outside the cast is skipped and counted, not fatal.
                    var loaded = ResidentAppearanceFiles.load(getDataFolder().toPath(), appearances.allowedKeys(),
                            ResidentAppearanceCatalogue::wellFormedResidentKey);
                    appearances.replace(loaded.assets());
                    skippedSkinEntries = loaded.skipped();
                }
                appearanceStatus = appearances.size() == 0 ? "no authored assets; resident avatars held"
                        : appearances.size() + " verified assets; visual acceptance pending";
            } catch (IllegalArgumentException failure) {
                appearances.replace(java.util.Map.of());
                appearanceStatus = "manifest rejected; resident avatars held";
                getLogger().warning("NPC appearance manifest rejected; no supplied file contents logged.");
            }
            this.citizensAdapter = new CitizensAdapter(appearances);
            this.renderManager = new RenderManager(this, propertyService,
                    new TenantRepository(database),
                    navGraphRepository, citizensAdapter, new ResidentRepository(database), appearances,
                    new CivicPlacementRepository(database));
            renderManager.start();
        } catch (Throwable t) {
            getLogger().warning("NPC rendering unavailable (economy unaffected): " + t.getMessage());
            this.renderManager = null;
            this.citizensAdapter = null;
        }
    }

    private void maybeAutoSettle() {
        if (killSwitches == null) {
            return;
        }
        if (!hasScheduledWork(killSwitches.isSystemEnabled(), killSwitches.isEconomyEnabled(),
                killSwitches.isMovesEnabled(), killSwitches.isIncidentsEnabled())) {
            return;
        }
        ZoneId tz = ZoneId.of(getConfig().getString("clock.timezone", "America/Chicago"));
        ZonedDateTime now = ZonedDateTime.now(tz);
        int sh = getConfig().getInt("clock.settlement-hour", 4);
        int sm = getConfig().getInt("clock.settlement-minute", 10);
        boolean pastSettlementTime = now.getHour() > sh || (now.getHour() == sh && now.getMinute() >= sm);
        if (pastSettlementTime) {
            requestDailyCycle(() -> true, message -> getLogger().info("Daily cycle: " + message));
        } else if (!dailyCycleRunning) {
            // Before the settlement time, still save today's owner-away decisions so bills and auto-care can run.
            syncOwnerAway(() -> {}, () -> getLogger().warning("Owner-away decisions not saved; recurring charges wait."));
        }
    }

    /**
     * Durable owner-away decisions need the database. Off (housing.vacation-days 0) the repository is never called,
     * so no rows are written or read. On: phases and recurring charges read only saved decisions.
     */
    private void initOwnerAwayDecisions() {
        ownerAwayDecisions = new OwnerAwayDecisionRepository(database);
        var worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4), r -> {
                    var t = new Thread(r, "npc-owner-away");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        trackDatabaseWorker(worker);
        ownerAwayWorker = worker;
        ownerAway.requireDurable();
        // Shortly after startup: today's saved decisions come back before any bill or auto-care charge reads them.
        getServer().getScheduler().runTaskLater(this, () -> syncOwnerAway(() -> {},
                () -> getLogger().warning("Owner-away decisions not saved at startup; recurring charges wait.")), 100L);
    }

    /**
     * Main thread. Saves today's first owner-away decision for every enrolled property (the first saved row wins,
     * also over this process's own in-memory decision), adopts what is stored, then runs {@code then} on main.
     * Off: runs {@code then} at once and touches nothing. Any failure runs {@code failed} and never re-decides.
     */
    void syncOwnerAway(Runnable then, Runnable failed) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Owner-away save requires main thread");
        if (!ownerAway.enabled() || ownerAwayDecisions == null || ownerAwayWorker == null) {
            then.run();
            return;
        }
        if (propertyService == null) {
            failed.run();
            return;
        }
        var day = alertDay();
        final List<OwnerAway.Choice> choices;
        try {
            choices = ownerAway.unsaved(propertyService.getEnrolled());
        } catch (RuntimeException tooMany) {
            failed.run();
            return;
        }
        if (choices.isEmpty() && ownerAway.settled()) {
            then.run();
            return;
        }
        var repository = ownerAwayDecisions;
        try {
            ownerAwayWorker.execute(() -> {
                List<OwnerAway.Choice> stored;
                try {
                    stored = repository.recordFirst(day, choices);
                } catch (SQLException | RuntimeException unconfirmed) {
                    postMain(failed);
                    return;
                }
                try {
                    repository.prune(day, OwnerAwayDecisionRepository.MAX_PRUNE);
                } catch (SQLException | RuntimeException pruneLater) {
                    /* Old rows wait for the next save. */
                }
                postMain(() -> {
                    if (ownerAway.confirm(day, stored)) then.run();
                    else failed.run();
                });
            });
        } catch (RejectedExecutionException busy) {
            failed.run();
        }
    }

    private void postMain(Runnable task) {
        try {
            getServer().getScheduler().runTask(this, task);
        } catch (RuntimeException disabled) {
            /* Plugin stopping: nothing further runs. */
        }
    }

    public boolean requestDailyCycle(BooleanSupplier authority, Consumer<String> reply) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Daily cycle requires main thread");
        if(dailyCycleRunning || staffRentChangeStep || !isEnabled() || !killSwitches.isSystemEnabled() || housingRuntime==null
                || utilityPayments == null || !authority.getAsBoolean()) return false;
        dailyCycleRunning = true;
        // Owner-away is decided and saved once for the day before any phase; every phase reads the saved decisions.
        // Off: this runs the cycle at once. A failed save never falls back to re-deciding.
        syncOwnerAway(() -> startDailyCycle(authority, reply), () -> {
            dailyCycleRunning = false;
            try {
                reply.accept("Owner-away decisions for today were not saved; no phase started.");
            } catch (RuntimeException failure) {
                getLogger().warning("Daily cycle reply unavailable");
            }
            staffAlerts.raise(StaffAlerts.Kind.DAILY_PHASE_FAILED, "phase-owner-away",
                    "Owner-away decisions were not saved; the daily cycle did not start.");
        });
        return true;
    }

    /**
     * /npcadmin rent. Main thread. housing.rent-change-delay-days in use (rentChangesDue): today's due asking-rent
     * changes apply first, as in the daily cycle, or no batch starts (busy, failed, cache not reconciled, day
     * changed, authority lost). Off and nothing pending: the rent batch starts directly.
     */
    public boolean requestStaffRent(BooleanSupplier authority, Consumer<String> reply) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Staff rent requires main thread");
        if (utilityPayments == null) return false;
        return DueRentFirst.request(dueRentFirst(authority,
                        r->utilityPayments!=null && utilityPayments.requestRentBatch(authority,r)),
                reply, "Rent settlement unavailable or busy; no batch was started.");
    }

    /** /npcadmin match. Matching reads asking rent for eligibility and fairness, so the same rule as staff rent. */
    public boolean requestStaffMatch(BooleanSupplier authority, Consumer<String> reply) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Staff matching requires main thread");
        if (housingRuntime == null) return false;
        return DueRentFirst.request(dueRentFirst(authority,
                        r->housingRuntime!=null && housingRuntime.request(authority,r)),
                reply, "Matching unavailable or busy; system, moves and ownership coordination must be ready.");
    }

    private DueRentFirst.Steps dueRentFirst(BooleanSupplier authority,
            Predicate<Consumer<String>> batch) {
        return new DueRentFirst.Steps() {
            // Every method reads live plugin state on main; nothing is carried across the step's hop.
            @Override public boolean rentChangesDue() { return propertyService!=null && propertyService.rentChangesDue(); }
            @Override public boolean stepOwnedElsewhere() {
                return dailyCycleRunning || staffRentChangeStep || propertyService == null;
            }

            @Override public void stepRunning(boolean running) {
                staffRentChangeStep = running;
            }

            @Override public LocalDate today() {
                return alertDay();
            }

            @Override public boolean applyDueRentChanges(LocalDate day, BiConsumer<Boolean, String> done) {
                return propertyService!=null && propertyService.applyDueRentChanges(day,done);
            }

            @Override public boolean stillAllowed() {
                return isEnabled() && killSwitches != null && killSwitches.isSystemEnabled() && !dailyCycleRunning
                        && authority.getAsBoolean();
            }

            @Override public boolean start(Consumer<String> r) {
                return batch.test(r);
            }
        };
    }

    private void startDailyCycle(BooleanSupplier authority, Consumer<String> reply) {
        if (!isEnabled() || !killSwitches.isSystemEnabled() || !authority.getAsBoolean()) {
            dailyCycleRunning = false;
            reply.accept("Stopped before the first phase; nothing was changed.");
            return;
        }
        // linked-accounts.lookup-floodgate-owners: a bounded async pass; the cycle never waits for it. Off: nothing runs.
        startOwnerLookup();
        if (propertyService != null) {
            var away = ownerAway.awayAmong(propertyService.getEnrolled());
            var day = alertDay();
            if (!away.isEmpty() && !day.equals(ownerAwayLogged)) {
                ownerAwayLogged = day;
                getLogger().info("Vacation: " + away.size() + " properties skipped today because the owner is away.");
            }
        }
        // housing.rent-change-delay-days: due asking-rent changes apply first, in one transaction, so every
        // later phase (matching, rent) reads the new value from its effective day. Off: phase 0 starts directly.
        if (propertyService != null && propertyService.rentChangesDue()) {
            boolean started = propertyService.applyDueRentChanges(alertDay(), (ok, message) -> {
                try {
                    reply.accept(message);
                } catch (RuntimeException failure) {
                    getLogger().warning("Daily cycle reply unavailable");
                }
                if (ok)runDailyPhase(0, authority, reply);
                else {
                    dailyCycleRunning = false;
                    staffAlerts.raise(StaffAlerts.Kind.DAILY_PHASE_FAILED, "phase-rent-change",
                            "Daily rent change step did not finish; later phases were not run.");
                }
            });
            if (!started) {
                dailyCycleRunning = false;
                reply.accept("Rent change step unavailable or busy; later phases not started.");
                staffAlerts.raise(StaffAlerts.Kind.DAILY_PHASE_FAILED, "phase-rent-change",
                        "Daily rent change step did not start; later phases were not run.");
            }
            return;
        }
        runDailyPhase(0, authority, reply);
    }

    /** Main thread only. Online players are never away; last-seen falls back to last-played. */
    @SuppressWarnings("deprecation")
    private static OwnerAway.Seen ownerSighting(UUID owner) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Owner sighting requires main thread");
        if (Bukkit.getPlayer(owner) != null) return new OwnerAway.Seen(true, 0, 0);
        var offline = Bukkit.getOfflinePlayer(owner);
        return new OwnerAway.Seen(offline.isOnline(), offline.getLastSeen(), offline.getLastPlayed());
    }

    /** Main thread only. Vacation decisions for today; empty while housing.vacation-days is 0. */
    public OwnerAway ownerAway() {
        return ownerAway;
    }

    static boolean hasScheduledWork(boolean system, boolean economy, boolean moves, boolean incidents) {
        return system && (economy || moves || incidents);
    }

    private void runDailyPhase(int phase, BooleanSupplier authority, Consumer<String> reply) {
        runDailyPhase(phase, authority, reply, false);
    }

    /**
     * Phase 4 with economy.payout-retirement-enabled on: first a standalone bounded retirement pass, then the rent
     * batch, re-entering here so every gate (system, economy, authority) is checked again after that hop. The pass
     * does not depend on any home being payable, so an all-paused or all-away server still retires expired rows.
     * Off, or a pass that cannot start: the rent batch runs directly (it keeps its own in-batch pass).
     */
    public static boolean retireBeforeRent(int phase, boolean retired, boolean retirementEnabled) {
        return phase == 4 && !retired && retirementEnabled;
    }

    private void runDailyPhase(int phase,java.util.function.BooleanSupplier authority,java.util.function.Consumer<String> reply,boolean retired) {
        if (!isEnabled() || !killSwitches.isSystemEnabled() || !authority.getAsBoolean()) {
            dailyCycleRunning = false;
            reply.accept("Stopped before next phase; committed receipts are retained.");
            return;
        }
        if (phase == 6) {
            dailyCycleRunning = false;
            reply.accept("Cycle finished. Individual phase receipts determine actual outcomes.");
            return;
        }
        // Phases: 0 incidents, 1 resident moods (flag-gated, before matching),
        // 2 voluntary departures (flag-gated, after moods, before matching), 3 matching, 4 rent,
        // 5 configured bills (only with bills.enabled and a loaded catalogue; otherwise skipped with no reply).
        if ((phase == 0 && !killSwitches.isIncidentsEnabled()) || (phase == 1 && !housingRuntime.moodsEnabled())
                || (phase == 2 && (!housingRuntime.voluntaryDeparturesEnabled() || !killSwitches.isMovesEnabled()))
                || (phase==3 && !killSwitches.isMovesEnabled()) || (phase==4 && !killSwitches.isEconomyEnabled())
                || (phase==5 && !billsPhaseRuns(billing))) {
            runDailyPhase(phase + 1, authority, reply);
            return;
        }
        if(retireBeforeRent(phase,retired,utilityPayments.payoutRetirementActive())) {
            boolean started=utilityPayments.requestPayoutRetirement(authority,message->{
                try {
                    reply.accept(message);
                } catch (RuntimeException failure) {
                    getLogger().warning("Daily cycle reply unavailable");
                }
                runDailyPhase(4,authority,reply,true);
            });
            if (started) return;
            // Not started (busy, not ready or fence held): the rent batch runs and does its own retirement pass.
        }
        Consumer<String> next = message -> {
            try {
                reply.accept(message);
            } catch (RuntimeException failure) {
                getLogger().warning("Daily cycle reply unavailable");
            }
            runDailyPhase(phase + 1, authority, reply);
        };
        boolean accepted = switch (phase) {
            case 0->housingRuntime.requestIncidents(authority,next);
            case 1->housingRuntime.requestMoods(authority,next);
            case 2->housingRuntime.requestDepartures(authority,next);
            case 3->housingRuntime.request(authority,next);
            case 4->utilityPayments.requestRentBatch(authority,next);
            case 5->billing.requestDaily(authority,next);
            default->false;
        };
        if (!accepted) {
            dailyCycleRunning = false;
            reply.accept("Phase " + phase + " unavailable or busy; later phases not started.");
            staffAlerts.raise(StaffAlerts.Kind.DAILY_PHASE_FAILED, "phase-" + phase,
                    "Daily cycle phase " + phase + " did not start; later phases were not run.");
        }
    }

    /** Reloads and re-validates the economy limits; falls back to fail-closed defaults on error. */
    public void reloadEconomyLimits() {
        try {
            this.economyLimits = EconomyLimits.load(this);
        } catch (IllegalStateException e) {
            getLogger().severe("Invalid economy limits in config.yml: " + e.getMessage()
                    + ": using STANDBY defaults (all caps 0).");
            this.economyLimits = EconomyLimits.disabledDefaults();
        }
    }

    public KillSwitches killSwitches() {
        return killSwitches;
    }

    public EconomyLimits economyLimits() {
        return economyLimits;
    }

    public Database database() {
        return database;
    }

    /** Null means no cooperative writer authority; callers must not transact. */
    public OwnershipFence ownershipFence() {
        return ownershipFence;
    }

    public HousingRuntime housingRuntime() {
        return housingRuntime;
    }

    public UtilityPaymentRuntime utilityPayments() {
        return utilityPayments;
    }

    /** Configured bills (dev.65); null when the database or the utility runtime is unavailable. */
    public BillingRuntime billing() {
        return billing;
    }

    /** Phase 5 runs only with a bills runtime whose catalogue is switched on and loaded. */
    static boolean billsPhaseRuns(BillingRuntime billing) {
        return billing != null && billing.phaseRuns();
    }

    /** /npcadmin reload: re-reads the bills: block. A bad block keeps the previous catalogue. */
    public void reloadBills(java.util.function.Consumer<String> sender) {
        if (billing != null) billing.loadCatalogue(false, sender);
    }

    public AutoCareRuntime autoCare() {
        return autoCare;
    }

    /**
     * True when a payment runtime refused to start only because centralbank.uuid is blank or not canonical. The
     * startup warning about centralbank.uuid already says payments stay held, so a second line naming the exception
     * would only read like a crash.
     */
    private boolean reserveUnsetExplains(RuntimeException failure) {
        return failure instanceof IllegalArgumentException && !ReserveAccount.isCanonical(ReserveAccount.liveText(getConfig()));
    }

    public AccountingProjection accountingProjection() {
        return accountingProjection;
    }

    public FinancialFence financialFence() {
        return financialFence;
    }

    public StaffAlerts alerts() {
        return staffAlerts;
    }

    /** Main thread. Plain one-line staff alert texts, oldest first; max is clamped to 1..20. Empty when alerts.enabled is off. */
    public List<String> drainAlerts(int max) {
        return staffAlerts.drain(max);
    }

    private LocalDate alertDay() {
        var zone = ZoneId.of(getConfig().getString("clock.timezone", "America/Chicago"));
        return new EconomyClock(Clock.systemUTC(), zone,
                getConfig().getInt("clock.daily-reset-hour", 4)).today();
    }

    private void initOwnershipCoordination() {
        String backendId = propertyBackendId();
        if (!ArmAdapter.ID.equals(backendId)) {
            initGuardedCoordination(backendId);
            return;
        }
        if (!getConfig().getBoolean("integration.arm-coordination", false)) return;
        var fence = new OwnershipFence(Bukkit::isPrimaryThread);
        try {
            // Without a database the property subsystem is off, but the guard is installed as before.
            PropertyBackend guarded = propertyBackend != null ? propertyBackend : new ArmAdapter(this);
            GuardResult result = guarded.installGuard(fence::beginWrite);
            if (!result.isInstalled()) throw new IllegalStateException(result.reason());
            propertyGuard = PropertyGuard.writeHook();
            ownershipFence = fence;
            getLogger().info("Cooperative ARM ownership hook registered; transaction services still require their own readiness gates.");
        } catch (RuntimeException | LinkageError failure) {
            fence.stop();
            staffAlerts.raise(StaffAlerts.Kind.ARM_HOOK_UNAVAILABLE, "arm-hook",
                    "ARM ownership hook did not register; payments and moves are held.");
            // A reflective failure arrives wrapped; name the original exception, as before.
            Throwable shown = failure instanceof ArmAdapter.GuardInstallFailure wrapped ? wrapped.reflective() : failure;
            getLogger().severe("ARM ownership coordination unavailable; financial/admission authority held. "
                    + shown.getClass().getSimpleName());
        }
    }

    /** property.backend: arm (the default) is built here; any other id waits for the server to finish starting. */
    private PropertyBackend selectPropertyBackend() {
        String id = propertyBackendId();
        if (ArmAdapter.ID.equals(id)) return armAdapter;
        if (WorldGuardListedBackend.ID.equals(id)) {
            worldGuardListed = new WorldGuardListedBackend(new ReflectiveWorldGuard(getServer().getPluginManager()),
                    Bukkit::isPrimaryThread, Bukkit::getWorld, Bukkit::getWorld,
                    at -> at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4));
            return worldGuardListed;
        }
        deferredProperty = new Deferred<>(Pieces.PROPERTY_KEY, id, getLogger()::warning);
        return new DeferredPropertyBackend(deferredProperty);
    }

    /** money.source: vault (the default) is built here; any other id waits for the server to finish starting. */
    private MoneySource selectMoneySource() {
        String id = moneySourceId();
        if (MoneySources.VAULT.equals(id)) return MoneySources.vault(getServer());
        deferredMoney = new Deferred<>(Pieces.MONEY_KEY, id, getLogger()::warning);
        return new DeferredMoneySource(deferredMoney);
    }

    /** Lists third-party pieces for diagnose, and binds deferred ones at startup. Listens only when one is deferred. */
    private void initPieces() {
        Map<String, MoneySource> money = moneySource instanceof BuiltInMoneySource builtIn
                ? Map.of(MoneySources.VAULT, builtIn) : Map.of();
        Map<String, PropertyBackend> property = armAdapter != null ? Map.of(ArmAdapter.ID, armAdapter) : Map.of();
        if (worldGuardListed != null)
            property = Map.of(ArmAdapter.ID, armAdapter, WorldGuardListedBackend.ID, worldGuardListed);
        pieces = new Pieces(getServer(), money, property, deferredMoney, deferredProperty);
        if (deferredProperty != null && ownershipFence != null) pieces.onPropertyBound(this::installBackendGuard);
        if (deferredMoney != null || deferredProperty != null)
            getServer().getPluginManager().registerEvents(pieces, this);
    }

    /** money.source as it was at startup: the source in use. A config reload changes it only after a restart. */
    public String moneySourceId() {
        return moneySourceId != null ? moneySourceId : configuredMoneySourceId();
    }

    /** property.backend as it was at startup: the backend in use. A config reload changes it only after a restart. */
    public String propertyBackendId() {
        return propertyBackendId != null ? propertyBackendId : configuredPropertyBackendId();
    }

    /** money.source in the live config, which may differ from moneySourceId() after /npcadmin reload. */
    public String configuredMoneySourceId() {
        return Pieces.configuredId(getConfig().getString(Pieces.MONEY_KEY), MoneySources.VAULT);
    }

    /** property.backend in the live config, which may differ from propertyBackendId() after /npcadmin reload. */
    public String configuredPropertyBackendId() {
        return Pieces.configuredId(getConfig().getString(Pieces.PROPERTY_KEY), ArmAdapter.ID);
    }

    /** The selected money source; null without a database. Third-party sources report no pin until bound. */
    public MoneySource moneySource() {
        return moneySource;
    }

    /** The selected property backend; null without a database. */
    public PropertyBackend propertyBackend() {
        return propertyBackend;
    }

    /** The selected accounting sink; null when accounting.sink is unknown or there is no database. */
    public AccountingSink accountingSink() {
        return accountingSink;
    }

    public String accountingSinkId() {
        return accountingSinkId;
    }

    /** Whether payments, moves and payouts may start for the property backend. Never null. */
    public PropertyGuard propertyGuard() {
        return propertyGuard;
    }

    /** The health line for homes enrolled under another backend; "" when there are none. */
    public String otherBackendHomes() {
        return otherBackendHomes;
    }

    /** property.when-unguarded, read on /npcadmin reload. */
    public void reloadPropertyGuard() {
        propertyGuard.whenUnguarded(getConfig().get(PropertyGuard.WHEN_UNGUARDED), getLogger()::warning);
    }

    // ---- dev.67: guards for property backends other than ARM -------------------------------------------------

    private String otherBackendHomes = "";

    /**
     * A backend other than ARM: the fence is created now, so the runtimes are built in today's order, but the guard
     * stays UNAVAILABLE until the backend is bound and its guard installed. A built-in backend is bound already; a
     * third-party one binds when the server finishes starting.
     */
    private void initGuardedCoordination(String backendId) {
        if (propertyBackend == null) return; // No database: the property subsystem is off and nothing can pay.
        ownershipFence = new OwnershipFence(Bukkit::isPrimaryThread, task -> getServer().getScheduler().runTask(this, task));
        propertyGuard = PropertyGuard.waiting("waiting for " + backendId + " to bind at server startup");
        reloadPropertyGuard();
        if (!(propertyBackend instanceof DeferredPropertyBackend)) installBackendGuard();
    }

    /** Main thread. Installs the bound backend's guard, registers its handlers through the wrapper, then checks every home. */
    private void installBackendGuard() {
        PropertyBackend backend = propertyBackend instanceof DeferredPropertyBackend deferred
                ? deferred.deferred().current().orElse(null) : propertyBackend;
        if (backend == null) {
            String why = propertyBackend instanceof DeferredPropertyBackend deferred ? deferred.deferred().reason() : "not bound";
            propertyGuard.fail(why);
            getLogger().warning(Pieces.PROPERTY_KEY + " '" + propertyBackendId() + "' has no guard (" + why + "); payments and moves are held.");
            return;
        }
        var hooks = new FenceHooks(ownershipFence, this::propertyBackendFailed);
        // A built-in backend's handlers are core code. A third-party backend's are filed under its adapter plugin, so
        // Bukkit removes them when the adapter disables; if that plugin cannot be found the guard fails instead.
        org.bukkit.plugin.Plugin filedUnder = this;
        if (propertyBackend instanceof DeferredPropertyBackend deferred) {
            String owner = deferred.deferred().owner();
            filedUnder = owner == null || owner.isEmpty() ? null : getServer().getPluginManager().getPlugin(owner);
        }
        PropertyBackend bound = propertyBackend;
        propertyGuard.install(backend, hooks, filedUnder, getServer().getPluginManager(), bound::available,
                this::propertyBackendFailed);
        getLogger().info(backend.displayName() + " " + propertyGuard.describe() + ".");
        if (!propertyGuard.ready()) return;
        startupOwnershipPass();
        if (propertyGuard.needsStartupReconcile()) {
            long ticks = Math.max(1L, getConfig().getLong("enrollment.reconcile-interval-ticks", 6000L));
            getServer().getScheduler().runTaskTimer(this, task -> {
                if (!propertyGuard.needsStartupReconcile() || !propertyGuard.ready()) task.cancel();
                else startupOwnershipPass();
            }, ticks, ticks);
        }
    }

    /** Main thread. Payouts stay held until one pass reads every enrolled home. */
    private void startupOwnershipPass() {
        if (propertyService == null) return;
        boolean loaded = propertyService.cacheLoaded();
        int unreadable = loaded ? propertyService.startupOwnershipPass() : 0;
        if (propertyGuard.startupPass(loaded, unreadable)) {
            getLogger().info("Startup ownership check read every enrolled home; " + propertyGuard.describe() + ".");
        } else if (!loaded) {
            if (!homesNotLoadedLogged) getLogger().warning("Startup ownership check cannot run: the enrolled homes did not "
                    + "load at startup; rent and appreciation wait until a restart.");
            homesNotLoadedLogged = true;
        } else {
            getLogger().warning("Startup ownership check could not read " + unreadable
                    + " enrolled home(s); rent and appreciation wait and the check runs again.");
        }
    }

    // Main thread. The "homes did not load" warning is logged once, not on every retry.
    private boolean homesNotLoadedLogged;

    /** Any thread. A guard handler or call threw: the backend is unavailable until a restart and payments are held. */
    private void propertyBackendFailed(String what, Throwable failure) {
        propertyGuard.fail(what + ": " + failure.getClass().getSimpleName());
        staffAlerts.raise(StaffAlerts.Kind.PROPERTY_BACKEND_FAILED, "property-backend",
                "Property backend " + propertyBackendId() + " failed in " + what + "; payments and moves are held until a restart.");
        getLogger().severe("Property backend " + propertyBackendId() + " failed in " + what + " ("
                + failure.getClass().getSimpleName() + "); payments and moves are held until a restart.");
    }

    // ---- end dev.67 -----------------------------------------------------------------------------------------

    /** Piece listing for /npcadmin diagnose. */
    public Pieces pieces() {
        return pieces;
    }

    public ArmAdapter armAdapter() {
        return armAdapter;
    }

    public PropertyRepository propertyRepository() {
        return propertyRepository;
    }

    public PropertyService propertyService() {
        return propertyService;
    }

    public EconomyEventRepository economyEventRepository() {
        return economyEventRepository;
    }

    public BridgeApi bridgeApi() {
        return bridgeApi;
    }

    public PurchaseService purchaseService() {
        return purchaseService;
    }

    public OwnerDeskController ownerDesk() {
        return ownerDesk;
    }

    public PublicRatingService publicRating() {
        return publicRating;
    }

    public NavGraphRepository navGraphRepository() {
        return navGraphRepository;
    }

    public NavGraphScanner navGraphScanner() {
        return navGraphScanner;
    }

    public RenderManager renderManager() {
        return renderManager;
    }

    public String appearanceStatus() {
        return appearanceStatus;
    }
}
