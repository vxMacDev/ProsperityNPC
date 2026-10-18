package com.prosperitysmp.npc.adapter.realty;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A small in-memory Realty: a database of plots, a WorldGuard store, a clock for the server and one for the
 * database, and the events Realty fires. Every operation is a list of named steps in the order Realty runs them,
 * so a test can read the backend between two steps. It models only the behaviour the adapter depends on.
 */
final class RealtyModel {
    static final long START = 1_800_000_000_000L;

    /** Landlords and authorities can be a player, an account or a group. The adapter never reads them. */
    enum PartyKind { PLAYER, ACCOUNT, GROUP }

    static final class Region {
        final UUID world;
        final String id;
        final int realtyId;
        UUID freeholdHolder;
        PartyKind authority = PartyKind.ACCOUNT;
        boolean freehold;
        boolean lease;
        UUID tenant;
        LocalDateTime start;
        LocalDateTime end;
        LocalDateTime termination;
        PartyKind landlord = PartyKind.PLAYER;

        Region(UUID world, String id, int realtyId) {
            this.world = world;
            this.id = id;
            this.realtyId = realtyId;
        }
    }

    static final class Wg {
        final UUID world;
        final String id;
        String parent;
        final Set<UUID> owners = new HashSet<>();
        final Set<UUID> members = new HashSet<>();
        int names;
        int groups;
        Object identity = new Object();
        int x1, z1, x2, z2;

        Wg(UUID world, String id) {
            this.world = world;
            this.id = id;
        }

        boolean contains(int x, int z) {
            return x >= x1 && x <= x2 && z >= z1 && z <= z2;
        }
    }

    /** The steps of one operation. Run them one at a time to read the backend in between. */
    static final class Steps {
        private record Step(String name, Runnable run) { }

        private final List<Step> steps = new ArrayList<>();
        private int next;

        Steps then(String name, Runnable run) {
            steps.add(new Step(name, run));
            return this;
        }

        int size() { return steps.size(); }

        String name(int index) { return steps.get(index).name(); }

        boolean done() { return next >= steps.size(); }

        void runNext() { steps.get(next++).run().run(); }

        void runAll() { while (!done()) runNext(); }
    }

    final Map<String, UUID> worlds = new LinkedHashMap<>();
    final Map<String, Region> db = new HashMap<>();
    final Map<String, Wg> wg = new HashMap<>();
    final List<String> eventLog = new ArrayList<>();
    final Set<UUID> unloaded = new HashSet<>();
    RealtyView.Events bus;
    long jvmMillis = START;
    Duration dbOffset = Duration.ZERO;
    boolean installed = true;
    String version = "2.0.0";
    boolean sourceFails;
    boolean sourceLinkageFails;
    boolean wgReadable = true;
    int flagEdits;
    private int nextRealtyId = 1;

    // Clocks

    LocalDateTime jvmNow() {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(jvmMillis), ZoneOffset.UTC);
    }

    LocalDateTime dbNow() {
        return jvmNow().plus(dbOffset);
    }

    void advance(Duration time) {
        jvmMillis += time.toMillis();
    }

    // Setup

    UUID addWorld(String name, UUID id) {
        worlds.put(name, id);
        return id;
    }

    private static String key(UUID world, String id) {
        return world + "/" + id.toLowerCase(Locale.ROOT);
    }

    Region region(UUID world, String id) {
        return db.get(key(world, id));
    }

    Wg guard(UUID world, String id) {
        return wg.get(key(world, id));
    }

    /** A region with a WorldGuard shape and no contract yet. */
    Wg defineGuard(UUID world, String id, String parent, int x1, int z1, int x2, int z2) {
        var region = new Wg(world, id);
        region.parent = parent;
        region.x1 = x1;
        region.z1 = z1;
        region.x2 = x2;
        region.z2 = z2;
        wg.put(key(world, id), region);
        return region;
    }

    /** A sold freehold whose WorldGuard owner list holds its title holder. */
    Region addFreehold(UUID world, String id, UUID holder, String parent, int x1, int z1, int x2, int z2) {
        Wg shape = defineGuard(world, id, parent, x1, z1, x2, z2);
        var region = register(world, id);
        region.freehold = true;
        region.freeholdHolder = holder;
        if (holder != null) shape.owners.add(holder);
        return region;
    }

    /** A leasehold. With a tenant it is rented until {@code end} and the tenant owns the WorldGuard region. */
    Region addLease(UUID world, String id, UUID tenant, LocalDateTime end, String parent, int x1, int z1, int x2, int z2) {
        Wg shape = defineGuard(world, id, parent, x1, z1, x2, z2);
        var region = register(world, id);
        region.lease = true;
        region.tenant = tenant;
        if (tenant != null) {
            region.start = dbNow();
            region.end = end;
            shape.owners.add(tenant);
        }
        return region;
    }

    private Region register(UUID world, String id) {
        var region = new Region(world, id, nextRealtyId++);
        db.put(key(world, id), region);
        return region;
    }

    /** The region is registered again with the WorldGuard region left as it was: a new Realty id, the same shape. */
    Region reregister(UUID world, String id, UUID holder) {
        var region = register(world, id);
        region.freehold = true;
        region.freeholdHolder = holder;
        return region;
    }

    void deleteRegion(UUID world, String id, boolean withGuard) {
        db.remove(key(world, id));
        if (withGuard) wg.remove(key(world, id));
    }

    void fire(String type, UUID world, String id) {
        eventLog.add(type + " " + id);
        if (bus != null && BoundEvents.NAMES.contains(type)) bus.changed(world, id, type.equals("RegionRentedEvent"));
    }

    // The database as the adapter reads it

    Optional<RealtyView.Plot> plotOf(Region region) {
        if (region == null || (!region.freehold && !region.lease)) return Optional.empty();
        RealtyView.Kind kind = region.freehold && region.lease ? RealtyView.Kind.BOTH
                : region.freehold ? RealtyView.Kind.FREEHOLD : RealtyView.Kind.LEASEHOLD;
        UUID holder = kind == RealtyView.Kind.FREEHOLD ? region.freeholdHolder
                : kind == RealtyView.Kind.LEASEHOLD ? region.tenant : null;
        var holderKind = holder == null ? RealtyView.Holder.NONE : RealtyView.Holder.PLAYER;
        boolean leased = kind == RealtyView.Kind.LEASEHOLD;
        return Optional.of(new RealtyView.Plot(region.world, region.id, region.realtyId, kind, holderKind, holder,
                leased ? region.start : null, leased ? region.end : null, leased ? region.termination : null));
    }

    // Operations. Each lists the steps in Realty's order. The adapter sees only the events.

    /** Buy: the database changes first, the payment runs, WorldGuard is written last. */
    Steps buy(UUID world, String id, UUID buyer) {
        return new Steps().then("pre-event", noop())
                .then("db-commit", () -> region(world, id).freeholdHolder = buyer)
                .then("payment", noop())
                .then("wg-write", () -> setOwners(world, id, buyer))
                .then("post-event", () -> fire("RegionBoughtEvent", world, id));
    }

    Steps rent(UUID world, String id, UUID tenant, Duration length) {
        return new Steps().then("pre-event", noop())
                .then("db-commit", () -> {
                    Region region = region(world, id);
                    region.tenant = tenant;
                    region.start = dbNow();
                    region.end = dbNow().plus(length);
                })
                .then("payment", noop())
                .then("wg-write", () -> setOwners(world, id, tenant))
                .then("post-event", () -> fire("RegionRentedEvent", world, id));
    }

    Steps unrent(UUID world, String id) {
        return new Steps().then("pre-event", noop())
                .then("db-commit", () -> clearTenant(region(world, id)))
                .then("refund", noop())
                .then("wg-clear", () -> setOwners(world, id, null))
                .then("post-event", () -> fire("RegionUnrentedEvent", world, id));
    }

    Steps extend(UUID world, String id, Duration extra) {
        return new Steps().then("pre-event", noop())
                .then("db-commit", () -> region(world, id).end = region(world, id).end.plus(extra))
                .then("payment", noop())
                .then("post-event", () -> fire("LeaseExtendedEvent", world, id));
    }

    Steps scheduleTermination(UUID world, String id, LocalDateTime effective) {
        return new Steps().then("pre-event", noop())
                .then("db-commit", () -> region(world, id).termination = effective)
                .then("post-event", () -> fire("LeaseTerminationScheduledEvent", world, id));
    }

    /** set titleholder with a player, and transfer. */
    Steps transfer(UUID world, String id, UUID to) {
        return new Steps().then("pre-event", noop())
                .then("db-commit", () -> region(world, id).freeholdHolder = to)
                .then("wg-write", () -> setOwners(world, id, to))
                .then("post-event", () -> fire("TitleTransferredEvent", world, id));
    }

    Steps setTenant(UUID world, String id, UUID to) {
        return new Steps()
                .then("db-commit", () -> region(world, id).tenant = to)
                .then("wg-write", () -> setOwners(world, id, to))
                .then("post-event", () -> fire("TenantSetEvent", world, id));
    }

    /** The final payment of an auction: paid first, then the database, then WorldGuard. */
    Steps auctionWon(UUID world, String id, UUID winner) {
        return new Steps().then("payment", noop())
                .then("db-commit", () -> region(world, id).freeholdHolder = winner)
                .then("wg-write", () -> setOwners(world, id, winner))
                .then("post-event", () -> fire("AuctionWonPurchaseEvent", world, id));
    }

    /** unset titleholder: no event at all. */
    Steps unsetTitleholder(UUID world, String id) {
        return new Steps()
                .then("db-commit", () -> region(world, id).freeholdHolder = null)
                .then("wg-clear", () -> setOwners(world, id, null));
    }

    /** unset tenant: no event at all. */
    Steps unsetTenant(UUID world, String id) {
        return new Steps()
                .then("db-commit", () -> clearTenant(region(world, id)))
                .then("wg-clear", () -> setOwners(world, id, null));
    }

    /** The expiry sweep: database first, then WorldGuard and the event on a later server tick. */
    Steps expirySweep(UUID world, String id) {
        return new Steps()
                .then("db-commit", () -> clearTenant(region(world, id)))
                .then("wg-remove", () -> setOwners(world, id, null))
                .then("post-event", () -> fire("LeaseExpiredEvent", world, id));
    }

    /** The sweep for a world or region that is missing: the tenant is cleared and nothing else happens. */
    Steps sweepWithoutWorld(UUID world, String id) {
        return new Steps().then("db-commit", () -> clearTenant(region(world, id)));
    }

    /** A direct backend call, or another server on the same database: no WorldGuard write, no event. */
    Steps databaseOnlyTransfer(UUID world, String id, UUID to) {
        return new Steps().then("db-commit", () -> region(world, id).freeholdHolder = to);
    }

    Steps databaseOnlyRent(UUID world, String id, UUID tenant, Duration length) {
        return new Steps().then("db-commit", () -> {
            Region region = region(world, id);
            region.tenant = tenant;
            region.start = dbNow();
            region.end = dbNow().plus(length);
        });
    }

    /** A staff edit of the WorldGuard owner list. */
    Steps staffSetOwners(UUID world, String id, UUID owner) {
        return new Steps().then("wg-edit", () -> setOwners(world, id, owner));
    }

    Steps staffAddOwner(UUID world, String id, UUID owner) {
        return new Steps().then("wg-edit", () -> guard(world, id).owners.add(owner));
    }

    /** The buyer is stored as the holder, the payment fails, and the rollback comes later. */
    Steps failedPurchase(UUID world, String id, UUID buyer) {
        UUID[] before = new UUID[1];
        return new Steps().then("pre-event", noop())
                .then("db-commit", () -> {
                    before[0] = region(world, id).freeholdHolder;
                    region(world, id).freeholdHolder = buyer;
                })
                .then("payment-fails", noop())
                .then("db-rollback", () -> region(world, id).freeholdHolder = before[0]);
    }

    Steps setLandlord(UUID world, String id, PartyKind kind) {
        return new Steps()
                .then("db-commit", () -> region(world, id).landlord = kind)
                .then("wg-members", () -> guard(world, id).members.clear())
                .then("post-event", () -> fire("LandlordSetEvent", world, id));
    }

    private static Runnable noop() {
        return () -> { };
    }

    private void clearTenant(Region region) {
        region.tenant = null;
        region.start = null;
        region.end = null;
        region.termination = null;
    }

    /** Realty clears the owner list and members, then adds the holder. */
    private void setOwners(UUID world, String id, UUID owner) {
        Wg shape = guard(world, id);
        shape.owners.clear();
        shape.members.clear();
        if (owner != null) shape.owners.add(owner);
    }
}
