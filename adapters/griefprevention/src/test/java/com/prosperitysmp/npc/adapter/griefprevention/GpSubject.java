package com.prosperitysmp.npc.adapter.griefprevention;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.prosperitysmp.npc.api.GuardBinding;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.contract.PropertyBackendContract;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;

final class GpSubject implements GriefPreventionView, PropertyBackendContract.Subject, PropertyBackendContract.GuardControls {
    static final UUID WORLD = new UUID(0, 67001), OWNER = new UUID(0, 67002);
    static final String PARENT = "10", CHILD = "11";
    private static final Flow FLOW = Flow.load();
    final List<String> logged = new ArrayList<>();
    final GriefPreventionBackend backend = new GriefPreventionBackend(this, logged::add);
    final Set<UUID> members = new HashSet<>();
    boolean realEstate, readable = true, broken, parent = true, child = true;
    int lookups, edits;
    private UUID owner = OWNER;
    private Object parentIdentity = new Object(), childIdentity = new Object();
    private Events events;
    private OwnershipHooks hooks;
    private final World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
            (p, method, args) -> switch (method.getName()) {
                case "getUID" -> WORLD;
                case "getName" -> "gp-test";
                case "equals" -> p == args[0];
                case "hashCode" -> WORLD.hashCode();
                case "toString" -> "gp-test";
                default -> throw new AssertionError("Unexpected world call: " + method.getName());
            });

    private record Flow(boolean cancelledTransferStops, boolean monitorBeforeCommit, boolean cancelledMonitorSkipped,
                        boolean highestOverrideBeforeMonitor) {
        static Flow load() {
            var flow = recording("events.json").getAsJsonObject("transfer");
            var quirks = recording("quirks.json").getAsJsonObject("transfer");
            return new Flow(flow.get("cancelledTransferStops").getAsBoolean(),
                    flow.get("monitorBeforeCommit").getAsBoolean(), flow.get("cancelledMonitorSkippedByAdapter").getAsBoolean(),
                    quirks.get("laterHighestOverrideBeforeMonitor").getAsBoolean());
        }
    }

    private static JsonObject recording(String name) {
        var input = GpSubject.class.getResourceAsStream("/recorded/16.18.7/" + name);
        if (input == null) throw new AssertionError("Missing GP event recording: " + name);
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    private void check() { if (broken) throw new NoClassDefFoundError("GP compatibility fixture"); }
    @Override public PropertyBackend backend() { return backend; }
    @Override public boolean knowsRegions() { return true; }
    @Override public UUID world() { return WORLD; }
    @Override public String region() { return PARENT; }
    @Override public UUID owner() { return OWNER; }
    Location at(int x, int z) { return new Location(world, x, 70, z); }
    @Override public List<Location> cellsOf(String region) {
        return region.equals(CHILD) ? List.of(at(13,13), at(14,14)) : List.of(at(10,10), at(20,20));
    }
    @Override public Location outside() { return at(50,50); }
    @Override public void setOwner(String region, UUID value) { owner = value; }
    @Override public void addMember(String region, UUID member) { members.add(member); }
    @Override public void unrelatedEdit(String region) { edits++; }
    @Override public void removeRegion(String region) { if (region.equals(PARENT)) parent = false; child = false; }
    @Override public void makeUnreadable() { readable = false; }
    @Override public List<UUID> unpayableOwners() { return List.of(new UUID(0,0)); }
    @Override public PropertyBackendContract.GuardControls guard() { return this; }
    @Override public boolean available() { check(); return readable; }
    @Override public boolean realEstatePresent() { check(); return realEstate; }
    @Override public Claim claim(long id) {
        check(); lookups++;
        if (!readable || !parent) return null;
        if (id == 10) return new Claim(10, WORLD, "gp-test", null, owner, parentIdentity, child ? List.of(11L) : List.of());
        return id == 11 && child ? new Claim(11, WORLD, "gp-test", 10L, owner, childIdentity, List.of()) : null;
    }
    @Override public Claim claimAt(Location at) {
        check();
        if (at.getWorld() == null || !WORLD.equals(at.getWorld().getUID()) || at.getBlockX()<10 || at.getBlockX()>20
                || at.getBlockZ()<10 || at.getBlockZ()>20) return null;
        return child && at.getBlockX()>=12 && at.getBlockX()<=14 && at.getBlockZ()>=12 && at.getBlockZ()<=14 ? claim(11) : claim(10);
    }
    @Override public List<GuardBinding> bindings(Events value) { events = value; return List.of(); }
    @Override public GuardResult install(OwnershipHooks value) {
        hooks = value;
        var result = backend.installGuard(value);
        backend.guardBindings();
        return result;
    }
    @Override public void uninstall() { events = null; }
    @Override public boolean changeOwner(String region, UUID value, String reason) {
        return transfer(region, value, false);
    }
    @Override public boolean supportsOverride() { return FLOW.highestOverrideBeforeMonitor(); }
    @Override public void changeOwnerOverridden(String region, UUID value) { transfer(region, value, true); }
    private boolean transfer(String region, UUID value, boolean override) {
        boolean[] applied = {false};
        hooks.guarded("ClaimTransferEvent fixture", () -> {
            var before = claim(Long.parseLong(region));
            boolean cancelled = events != null && events.transferRequested(before);
            if (override && FLOW.highestOverrideBeforeMonitor()) cancelled = false;
            if (cancelled && FLOW.cancelledTransferStops()) return;
            if (events != null && FLOW.monitorBeforeCommit()) events.transferred(before);
            setOwner(region, value);
            applied[0] = true;
            if (events != null && !FLOW.monitorBeforeCommit()) events.transferred(claim(Long.parseLong(region)));
        });
        return applied[0];
    }
    @Override public void changeOwnerCancelledElsewhere(String region, UUID value) {
        if (!FLOW.cancelledMonitorSkipped() && events != null) events.transferred(claim(Long.parseLong(region)));
    }
    void shapeChanged() { events.changed(claim(10)); }
    @Override public List<String> neverCancelled() { return realEstate ? List.of("default") : List.of(); }
    @Override public List<String> affectedWith(String region) { return region.equals(PARENT) ? List.of(CHILD) : List.of(); }
    @Override public void recreate(String region, UUID value) {
        parent = true; child = false; owner = value; parentIdentity = new Object(); childIdentity = new Object();
    }
    @Override public void breakLinkage() { broken = true; }
}
