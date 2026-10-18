package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import com.prosperitysmp.npc.api.RegionRef;
import com.prosperitysmp.npc.api.RegionView;
import com.prosperitysmp.npc.api.contract.PropertyBackendContract;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The property contract's guard cases run against an in-memory vendor with every awkward feature at once: a veto
 * that a later subscriber can overwrite, events off the main thread, a never-cancel reason, merged regions and
 * delete-and-recreate. It proves the guard cases and their mutants; real adapters run the same cases over their
 * own fake views.
 */
public class FakeGuardedBackendContractTest extends PropertyBackendContract {
    static final UUID WORLD = FakeBackendContractTest.WORLD, OWNER = new UUID(6, 6);

    @Override
    protected Subject subject(Scenario s) {
        var vendor = new Vendor();
        vendor.put("plot-1", 0, 0, 4, 4, OWNER, "group-a");
        vendor.put("plot-2", 10, 0, 14, 4, OWNER, "group-a");
        var backend = new GuardedBackend(vendor);
        return new Subject() {
            @Override public PropertyBackend backend() { return backend; }
            @Override public boolean knowsRegions() { return true; }
            @Override public UUID world() { return WORLD; }
            @Override public String region() { return "plot-1"; }
            @Override public UUID owner() { return OWNER; }

            @Override
            public List<Location> cellsOf(String region) {
                var r = vendor.regions.get(region);
                var out = new ArrayList<Location>();
                for (int x = r.x1; x <= r.x2; x++) for (int z = r.z1; z <= r.z2; z++)
                    out.add(new Location(FakeBackendContractTest.FakeBackend.WORLD_OBJECT, x, 64, z));
                return out;
            }

            @Override public Location outside() { return new Location(FakeBackendContractTest.FakeBackend.WORLD_OBJECT, 100, 64, 100); }
            @Override public void setOwner(String region, UUID owner) { vendor.regions.get(region).owner(owner); }
            @Override public void addMember(String region, UUID member) { }
            @Override public void unrelatedEdit(String region) { }
            @Override public void removeRegion(String region) { vendor.regions.remove(region); }
            @Override public void makeUnreadable() { vendor.broken = true; }
            @Override public List<UUID> unpayableOwners() { return List.of(new UUID(0, 0)); }

            @Override
            public GuardControls guard() {
                return new GuardControls() {
                    @Override public GuardResult install(OwnershipHooks hooks) { return backend.installGuard(hooks); }
                    @Override public void uninstall() { backend.uninstall(); }
                    @Override public boolean changeOwner(String region, UUID owner, String reason) {
                        return vendor.change(region, owner, reason, false, false);
                    }
                    @Override public void changeOwnerCancelledElsewhere(String region, UUID owner) {
                        vendor.change(region, owner, "default", true, false);
                    }
                    @Override public List<String> neverCancelled() { return List.of("admin"); }
                    @Override public boolean eventsOffMain() { return true; }
                    @Override public boolean supportsOverride() { return true; }
                    @Override public void changeOwnerOverridden(String region, UUID owner) {
                        vendor.change(region, owner, "default", false, true);
                    }
                    @Override public List<String> affectedWith(String region) {
                        return vendor.group(region).stream().filter(other -> !other.equals(region)).toList();
                    }
                    @Override public void recreate(String region, UUID owner) {
                        var old = vendor.regions.remove(region);
                        vendor.put(region, old.x1, old.z1, old.x2, old.z2, owner, old.group);
                    }
                    @Override public void breakLinkage() { vendor.broken = true; }
                };
            }
        };
    }

    /** The fake vendor: rectangular regions in one world, merged groups, and one owner-change event bus. */
    static final class Vendor {
        static final class Region {
            final int x1, z1, x2, z2;
            final String group;
            volatile UUID owner;
            volatile long generation;
            static long next = 1;

            Region(int x1, int z1, int x2, int z2, UUID owner, String group) {
                this.x1 = x1; this.z1 = z1; this.x2 = x2; this.z2 = z2; this.group = group;
                owner(owner);
            }

            synchronized void owner(UUID owner) {
                this.owner = owner;
                generation = next++;
            }
        }

        /** The pre-change event: a subscriber may deny it; the last one to write wins. */
        static final class Change {
            final String region;
            final String reason;
            boolean denied;
            Change(String region, String reason) { this.region = region; this.reason = reason; }
        }

        interface Subscriber {
            void pre(Change change);
            void post(Change change);
        }

        final Map<String, Region> regions = new ConcurrentHashMap<>();
        final List<Subscriber> subscribers = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean broken;

        void put(String id, int x1, int z1, int x2, int z2, UUID owner, String group) {
            regions.put(id, new Region(x1, z1, x2, z2, owner, group));
        }

        List<String> group(String region) {
            var r = regions.get(region);
            if (r == null) return List.of(region);
            return regions.entrySet().stream().filter(e -> e.getValue().group.equals(r.group)).map(Map.Entry::getKey).sorted().toList();
        }

        /** Runs the vendor's flow on the calling thread. */
        boolean change(String region, UUID owner, String reason, boolean cancelledElsewhere, boolean overridden) {
            var change = new Change(region, reason);
            for (Subscriber s : subscribers) s.pre(change);
            if (cancelledElsewhere) change.denied = true;
            if (overridden) change.denied = false;
            if (change.denied) return false;
            for (String member : group(region)) regions.get(member).owner(owner);
            for (Subscriber s : subscribers) s.post(change);
            return true;
        }

        void check() {
            if (broken) throw new NoSuchMethodError("fake vendor changed its API");
        }
    }

    /** The adapter logic under test: a veto on the pre event, markDirty on the post event, never off-main fence calls. */
    static final class GuardedBackend implements PropertyBackend {
        private final Vendor vendor;
        private Vendor.Subscriber subscriber;

        GuardedBackend(Vendor vendor) { this.vendor = vendor; }

        record Token(UUID worldId, String regionId, UUID ownerId, long generation) implements OwnershipToken {
            @Override public boolean sameIncarnation(OwnershipToken other) { return other instanceof Token t && t.equals(this); }
        }

        @Override public String id() { return "fake-guarded"; }
        @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
        @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
        @Override public Capabilities capabilities() {
            return Capabilities.builder().value("guard", "veto-events").value("token-identity", "generation")
                    .flag("distinguishes-missing", true).flag("sees-owner-round-trip", true).build();
        }
        @Override public String displayName() { return "Fake guarded"; }

        @Override
        public boolean available() {
            try {
                vendor.check();
                return true;
            } catch (LinkageError failure) {
                return false;
            }
        }

        @Override
        public synchronized GuardResult installGuard(OwnershipHooks hooks) {
            uninstall();
            subscriber = new Vendor.Subscriber() {
                @Override public void pre(Vendor.Change change) {
                    hooks.guarded("owner change", () -> {
                        vendor.check();
                        if ("admin".equals(change.reason)) return;
                        for (String key : vendor.group(change.region))
                            if (hooks.isHeld(FakeBackendContractTest.WORLD, key)) change.denied = true;
                    });
                }
                @Override public void post(Vendor.Change change) {
                    hooks.guarded("owner changed", () -> {
                        vendor.check();
                        for (String key : vendor.group(change.region)) hooks.markDirty(FakeBackendContractTest.WORLD, key);
                    });
                }
            };
            vendor.subscribers.add(subscriber);
            return GuardResult.installed(GuardMode.VETO_EVENTS);
        }

        synchronized void uninstall() {
            if (subscriber != null) vendor.subscribers.remove(subscriber);
            subscriber = null;
        }

        @Override
        public Optional<RegionRef> regionAt(Location at) {
            try {
                vendor.check();
                for (var entry : vendor.regions.entrySet()) {
                    var r = entry.getValue();
                    if (at.getBlockX() >= r.x1 && at.getBlockX() <= r.x2 && at.getBlockZ() >= r.z1 && at.getBlockZ() <= r.z2) {
                        String id = entry.getKey();
                        return Optional.of(new RegionRef() {
                            @Override public UUID worldId() { return WORLD; }
                            @Override public String regionId() { return id; }
                        });
                    }
                }
            } catch (LinkageError failure) {
                return Optional.empty();
            }
            return Optional.empty();
        }

        @Override public Optional<RegionView> findRegion(String worldName, String regionId) { return Optional.empty(); }

        @Override
        public Optional<OwnershipToken> ownerReadAt(Location at, UUID owner) {
            return regionAt(at).flatMap(ref -> ownerReadRegion(ref.worldId(), ref.regionId(), owner));
        }

        @Override
        public Optional<OwnershipToken> ownerReadRegion(UUID world, String regionId, UUID owner) {
            try {
                vendor.check();
                var r = WORLD.equals(world) ? vendor.regions.get(regionId) : null;
                if (r == null || owner == null || !owner.equals(r.owner) || owner.equals(new UUID(0, 0))) return Optional.empty();
                return Optional.of(new Token(world, regionId, owner, r.generation));
            } catch (LinkageError failure) {
                return Optional.empty();
            }
        }

        @Override
        public Presence observe(UUID world, String regionId) {
            try {
                vendor.check();
                return WORLD.equals(world) && vendor.regions.containsKey(regionId) ? Presence.PRESENT : Presence.MISSING;
            } catch (LinkageError failure) {
                return Presence.UNREADABLE;
            }
        }

        @Override public List<String> describe() { return List.of("fake guarded: " + vendor.regions.size() + " region(s)"); }
    }
}
