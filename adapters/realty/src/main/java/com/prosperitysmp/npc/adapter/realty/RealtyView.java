package com.prosperitysmp.npc.adapter.realty;

import com.prosperitysmp.npc.api.GuardBinding;
import org.bukkit.Location;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The only Realty and WorldGuard calls the adapter needs. {@code RealRealtyView} makes them against Realty 2.0.x;
 * tests use a model written from scratch. Realty is a database plugin, so owners are read from a snapshot that the
 * reader thread fills. Everything here except {@link Source} runs on the main thread. Any method may throw a
 * LinkageError after an incompatible update; the backend turns that into "unavailable".
 */
public interface RealtyView {
    enum Kind { NONE, FREEHOLD, LEASEHOLD, BOTH }

    enum Holder { NONE, PLAYER, OTHER }

    /** A registered region with a contract, before its contracts are read. */
    record Ref(UUID worldId, String regionId, int realtyRegionId) { }

    /** One plot as Realty's database holds it. The landlord, the authority and the price are never read. */
    record Plot(UUID worldId, String regionId, int realtyRegionId, Kind kind, Holder holder, UUID holderId,
                LocalDateTime leaseStart, LocalDateTime leaseEnd, LocalDateTime terminationAt) { }

    /** One WorldGuard region: its owner domain (players by UUID, legacy names, groups) and an identity object. */
    record Guard(String id, Object identity, List<UUID> owners, int ownerNames, int ownerGroups) {
        public Guard {
            owners = List.copyOf(owners);
        }
    }

    /** A WorldGuard region at a block, with the ids of its parents, nearest first. */
    record Candidate(String id, List<String> ancestors) {
        public Candidate {
            ancestors = List.copyOf(ancestors);
        }
    }

    /** What the backend does when Realty announces a change. */
    interface Events {
        /** Any Realty post-event for one region. {@code rented} is true for a new rental. */
        void changed(UUID worldId, String regionId, boolean rented);
    }

    /** The blocking reads of Realty's database. Called on the reader thread only. */
    interface Source {
        /** Every registered region that has a contract. */
        List<Ref> list() throws Exception;

        /** The contracts of these regions. */
        List<Plot> read(List<Ref> refs) throws Exception;

        /** One region, or empty when it has no contract. */
        Optional<Plot> load(UUID worldId, String regionId) throws Exception;
    }

    /** Realty 2.x enabled with its service registered, and WorldGuard and WorldEdit enabled. */
    boolean installed();

    /** Why {@link #installed} is false, for the log. Empty when it is true. */
    String problem();

    /** The loaded world with exactly this name, or null. */
    UUID worldId(String worldName);

    /** The name of the loaded world, or null. */
    String worldName(UUID worldId);

    boolean onMainThread();

    /** Runs the task on the single reader thread. */
    void runAsync(Runnable task);

    void runOnMain(Runnable task);

    long nowMillis();

    /** The server clock. Realty stamps some lease dates with the database clock instead. */
    LocalDateTime now();

    Source source();

    /** Main thread, once. Reads everything on the reader thread and waits up to the timeout. */
    List<Plot> loadFirst(long timeoutMillis) throws Exception;

    /** The WorldGuard region, or empty when WorldGuard readably has none. Throws when it cannot be read. */
    Optional<Guard> worldGuard(UUID worldId, String regionId);

    /** Every WorldGuard region at the block, parents included. */
    List<Candidate> regionsAt(Location at);

    /** Ids of every WorldGuard region that has this region among its parents. */
    List<String> descendants(UUID worldId, String regionId);

    /** Ids of the WorldGuard parents of this region, nearest first. Empty when the region has none or is unknown. */
    List<String> ancestors(UUID worldId, String regionId);

    /** One pass over a world: lower case parent id to the ids of its direct children. */
    Map<String, List<String>> childIndex(UUID worldId);

    /** Bukkit handlers that call {@code events}. */
    List<GuardBinding> bindings(Events events);

    /** WorldGuard or WorldEdit went away: forget every object taken from them. */
    void reset();

    /** Stops the reader thread. */
    void stop();
}
