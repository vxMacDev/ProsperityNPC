package com.prosperitysmp.npc.adapter.realty;

import com.prosperitysmp.npc.api.GuardBinding;
import org.bukkit.Location;
import org.bukkit.World;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * {@link RealtyView} over the model. Tasks wait in two queues, one for the reader thread and one for the main
 * thread, and run only when a test pumps them, so a test decides what happens between two steps.
 */
final class ModelView implements RealtyView {
    final RealtyModel model;
    boolean broken;
    boolean firstLoadFails;
    boolean firstLoadTimesOut;
    boolean stopped;
    boolean childIndexFails;
    boolean descendantsFail;
    int bindingCalls;
    int guardCalls;
    int loads;
    int resets;
    private boolean onReader;
    private final ArrayDeque<Runnable> reader = new ArrayDeque<>();
    private final ArrayDeque<Runnable> main = new ArrayDeque<>();
    private final Map<UUID, World> bukkitWorlds = new HashMap<>();

    ModelView(RealtyModel model) {
        this.model = model;
    }

    private void check() {
        if (broken) throw new NoClassDefFoundError("Realty compatibility fixture");
    }

    // Tasks

    void pump() {
        while (!reader.isEmpty() || !main.isEmpty()) {
            pumpAsyncOnly();
            pumpMainOnly();
        }
    }

    void pumpAsyncOnly() {
        while (!reader.isEmpty()) {
            onReader = true;
            try {
                reader.poll().run();
            } finally {
                onReader = false;
            }
        }
    }

    void pumpMainOnly() {
        while (!main.isEmpty()) main.poll().run();
    }

    int asyncSize() {
        return reader.size();
    }

    boolean idle() {
        return reader.isEmpty() && main.isEmpty();
    }

    @Override public void runAsync(Runnable task) { reader.add(task); }
    @Override public void runOnMain(Runnable task) { main.add(task); }
    @Override public boolean onMainThread() { check(); return !onReader; }
    @Override public long nowMillis() { return model.jvmMillis; }
    @Override public LocalDateTime now() { check(); return model.jvmNow(); }

    // Realty

    @Override
    public boolean installed() {
        check();
        return model.installed && model.version.startsWith("2.0.");
    }

    @Override
    public String problem() {
        if (!model.installed) return "Realty is not enabled";
        return model.version.startsWith("2.0.") ? "" : "Realty " + model.version + " is not supported (2.0.x only)";
    }

    @Override
    public Source source() {
        return new Source() {
            @Override
            public List<Ref> list() throws Exception {
                loads++;
                failIfAsked();
                var refs = new ArrayList<Ref>();
                for (var region : model.db.values()) {
                    if (region.freehold || region.lease) refs.add(new Ref(region.world, region.id, region.realtyId));
                }
                return refs;
            }

            @Override
            public List<Plot> read(List<Ref> refs) throws Exception {
                failIfAsked();
                var plots = new ArrayList<Plot>();
                for (var ref : refs) model.plotOf(model.region(ref.worldId(), ref.regionId())).ifPresent(plots::add);
                return plots;
            }

            @Override
            public Optional<Plot> load(UUID worldId, String regionId) throws Exception {
                loads++;
                failIfAsked();
                return model.plotOf(model.region(worldId, regionId));
            }

            private void failIfAsked() throws Exception {
                if (model.sourceLinkageFails) throw new NoClassDefFoundError("Realty compatibility fixture");
                if (model.sourceFails) throw new Exception("database down");
            }
        };
    }

    @Override
    public List<Plot> loadFirst(long timeoutMillis) throws Exception {
        if (firstLoadFails) throw new IllegalStateException("database down");
        if (firstLoadTimesOut) throw new TimeoutException();
        var source = source();
        return source.read(source.list());
    }

    // Worlds

    @Override
    public UUID worldId(String worldName) {
        check();
        UUID id = model.worlds.get(worldName);
        return id == null || model.unloaded.contains(id) ? null : id;
    }

    @Override
    public String worldName(UUID worldId) {
        return model.worlds.entrySet().stream().filter(e -> e.getValue().equals(worldId)).map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    /** A Bukkit world for a test location. Only its name and id can be asked. */
    World bukkitWorld(UUID id) {
        return bukkitWorlds.computeIfAbsent(id, key -> (World) Proxy.newProxyInstance(World.class.getClassLoader(),
                new Class<?>[]{World.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> key;
                    case "getName" -> worldName(key);
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> key.hashCode();
                    case "toString" -> worldName(key);
                    default -> throw new AssertionError("Unexpected world call: " + method.getName());
                }));
    }

    Location at(UUID world, int x, int z) {
        return new Location(bukkitWorld(world), x, 70, z);
    }

    // WorldGuard

    @Override
    public Optional<Guard> worldGuard(UUID worldId, String regionId) {
        check();
        guardCalls++;
        if (!model.wgReadable || model.unloaded.contains(worldId)) throw new IllegalStateException("WorldGuard cannot be read");
        var shape = model.guard(worldId, regionId);
        if (shape == null) return Optional.empty();
        return Optional.of(new Guard(shape.id, shape.identity, List.copyOf(shape.owners), shape.names, shape.groups));
    }

    @Override
    public List<Candidate> regionsAt(Location at) {
        check();
        if (!model.wgReadable) throw new IllegalStateException("WorldGuard cannot be read");
        var found = new ArrayList<Candidate>();
        UUID world = at.getWorld().getUID();
        for (var shape : model.wg.values()) {
            if (shape.world.equals(world) && shape.contains(at.getBlockX(), at.getBlockZ())) {
                found.add(new Candidate(shape.id, ancestors(shape)));
            }
        }
        return found;
    }

    private List<String> ancestors(RealtyModel.Wg shape) {
        var ids = new ArrayList<String>();
        var parent = shape.parent == null ? null : model.guard(shape.world, shape.parent);
        while (parent != null && ids.size() < 32) {
            ids.add(parent.id);
            parent = parent.parent == null ? null : model.guard(parent.world, parent.parent);
        }
        return ids;
    }

    @Override
    public List<String> descendants(UUID worldId, String regionId) {
        check();
        if (descendantsFail) throw new IllegalStateException("WorldGuard cannot be read");
        var found = new ArrayList<String>();
        for (var shape : model.wg.values()) {
            if (shape.world.equals(worldId) && ancestors(shape).stream().anyMatch(regionId::equalsIgnoreCase)) found.add(shape.id);
        }
        return found;
    }

    @Override
    public List<String> ancestors(UUID worldId, String regionId) {
        check();
        var shape = model.guard(worldId, regionId);
        return shape == null ? List.of() : ancestors(shape);
    }

    @Override
    public void reset() {
        resets++;
    }

    @Override
    public Map<String, List<String>> childIndex(UUID worldId) {
        check();
        if (childIndexFails) throw new IllegalStateException("WorldGuard cannot be read");
        var index = new HashMap<String, List<String>>();
        for (var shape : model.wg.values()) {
            if (shape.world.equals(worldId) && shape.parent != null) {
                index.computeIfAbsent(shape.parent.toLowerCase(Locale.ROOT), id -> new ArrayList<>()).add(shape.id);
            }
        }
        return index;
    }

    @Override
    public List<GuardBinding> bindings(Events events) {
        bindingCalls++;
        model.bus = events;
        return List.of();
    }

    @Override
    public void stop() {
        stopped = true;
    }
}
