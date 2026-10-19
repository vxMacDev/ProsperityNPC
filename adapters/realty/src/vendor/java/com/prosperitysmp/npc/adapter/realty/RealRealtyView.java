package com.prosperitysmp.npc.adapter.realty;

import com.prosperitysmp.npc.api.GuardBinding;
import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.api.event.AuctionWonPurchaseEvent;
import io.github.md5sha256.realty.api.event.LeaseExpiredEvent;
import io.github.md5sha256.realty.api.event.LeaseExtendedEvent;
import io.github.md5sha256.realty.api.event.LeaseTerminatedEvent;
import io.github.md5sha256.realty.api.event.LeaseTerminationCancelledEvent;
import io.github.md5sha256.realty.api.event.LeaseTerminationScheduledEvent;
import io.github.md5sha256.realty.api.event.OfferPurchaseCompletedEvent;
import io.github.md5sha256.realty.api.event.RealtyRegionEvent;
import io.github.md5sha256.realty.api.event.RegionBoughtEvent;
import io.github.md5sha256.realty.api.event.RegionCreatedEvent;
import io.github.md5sha256.realty.api.event.RegionDeletedEvent;
import io.github.md5sha256.realty.api.event.RegionRentedEvent;
import io.github.md5sha256.realty.api.event.RegionUnrentedEvent;
import io.github.md5sha256.realty.api.event.TenantSetEvent;
import io.github.md5sha256.realty.api.event.TitleTransferredEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Realty 2.0.x and WorldGuard 7 behind {@link RealtyView}. The only class besides the reader that touches Realty. */
final class RealRealtyView implements RealtyView {
    private final JavaPlugin plugin;
    private final PluginManager plugins;
    private final ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ProsperityNPC-Realty-reader");
        thread.setDaemon(true);
        return thread;
    });
    private final RealtyReader source = new RealtyReader(this::service);
    private ReflectiveRegions regions;
    private volatile String problem = "";

    RealRealtyView(JavaPlugin plugin) {
        this.plugin = plugin;
        this.plugins = plugin.getServer().getPluginManager();
    }

    private RealtyBackend service() {
        return plugin.getServer().getServicesManager().load(RealtyBackend.class);
    }

    @Override
    public boolean installed() {
        problem = findProblem();
        return problem.isEmpty();
    }

    private String findProblem() {
        Plugin realty = plugins.getPlugin("Realty");
        if (realty == null || !realty.isEnabled()) return "Realty is not enabled";
        String version = realty.getPluginMeta().getVersion();
        if (!version.startsWith("2.0.")) return "Realty " + version + " is not supported (2.0.x only)";
        if (!plugins.isPluginEnabled("WorldGuard")) return "WorldGuard is not enabled";
        if (!plugins.isPluginEnabled("WorldEdit")) return "WorldEdit is not enabled";
        if (service() == null) return "Realty's service is not registered";
        return "";
    }

    @Override
    public String problem() {
        return problem;
    }

    @Override
    public UUID worldId(String worldName) {
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        return world == null ? null : world.getUID();
    }

    @Override
    public String worldName(UUID worldId) {
        World world = Bukkit.getWorld(worldId);
        return world == null ? null : world.getName();
    }

    @Override
    public boolean onMainThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public void runAsync(Runnable task) {
        reader.execute(task);
    }

    @Override
    public void runOnMain(Runnable task) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public LocalDateTime now() {
        return LocalDateTime.now();
    }

    @Override
    public Source source() {
        return source;
    }

    @Override
    public List<Plot> loadFirst(long timeoutMillis) throws Exception {
        Future<List<Plot>> read = reader.submit(() -> source.read(source.list()));
        return read.get(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public Optional<Guard> worldGuard(UUID worldId, String regionId) {
        return regions().region(world(worldId), regionId);
    }

    @Override
    public List<Candidate> regionsAt(Location at) {
        return regions().regionsAt(at);
    }

    @Override
    public List<String> descendants(UUID worldId, String regionId) {
        return regions().descendants(world(worldId), regionId);
    }

    @Override
    public List<String> ancestors(UUID worldId, String regionId) {
        return regions().ancestorsOf(world(worldId), regionId);
    }

    @Override
    public Map<String, List<String>> childIndex(UUID worldId) {
        return regions().childIndex(world(worldId));
    }

    private ReflectiveRegions regions() {
        if (regions == null) regions = new ReflectiveRegions(plugins.getPlugin("WorldGuard"));
        return regions;
    }

    /** The next WorldGuard call resolves against whatever WorldGuard is running then. */
    @Override
    public void reset() {
        regions = null;
    }

    private static World world(UUID worldId) {
        World world = Bukkit.getWorld(worldId);
        if (world == null) throw new IllegalStateException("World " + worldId + " is not loaded");
        return world;
    }

    @Override
    public List<GuardBinding> bindings(Events events) {
        return List.of(
                bind(RegionBoughtEvent.class, events, false),
                bind(RegionRentedEvent.class, events, true),
                bind(RegionUnrentedEvent.class, events, false),
                bind(LeaseExpiredEvent.class, events, false),
                bind(LeaseTerminatedEvent.class, events, false),
                bind(AuctionWonPurchaseEvent.class, events, false),
                bind(OfferPurchaseCompletedEvent.class, events, false),
                bind(TitleTransferredEvent.class, events, false),
                bind(TenantSetEvent.class, events, false),
                bind(LeaseExtendedEvent.class, events, false),
                bind(LeaseTerminationScheduledEvent.class, events, false),
                bind(LeaseTerminationCancelledEvent.class, events, false),
                bind(RegionCreatedEvent.class, events, false),
                bind(RegionDeletedEvent.class, events, false));
    }

    /** Post-events cannot be cancelled, so MONITOR with ignoreCancelled changes nothing. No handler cancels. */
    private static <E extends RealtyRegionEvent> GuardBinding bind(Class<E> type, Events events, boolean rented) {
        return GuardBinding.of(type, EventPriority.MONITOR, true,
                event -> events.changed(event.getWorldId(), event.getRegionId(), rented));
    }

    @Override
    public void stop() {
        reader.shutdownNow();
    }
}
