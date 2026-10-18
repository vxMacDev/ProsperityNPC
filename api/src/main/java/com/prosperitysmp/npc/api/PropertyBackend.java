package com.prosperitysmp.npc.api;

import org.bukkit.Location;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The plugin that says who owns a home. All reads run on the main thread.
 *
 * <p>Capability names: {@code guard} ({@code write-hook}, {@code veto-events}, {@code none}), {@code owner-model}
 * (one line naming the paying principal), {@code location-lookup}, {@code region-by-id},
 * {@code distinguishes-missing}, {@code token-identity} ({@code object}, {@code generation}, or {@code weak} when
 * a deleted and recreated region with the same id cannot be told apart; a missing or unknown value is treated as
 * {@code weak}), {@code sees-owner-round-trip} (an owner
 * change and change back that fired no event still gives a new token; false when the vendor edits the owner in place),
 * {@code needs-worldguard}, {@code region-id-max-length} ({@code 128}), {@code main-thread-only}.
 */
public interface PropertyBackend extends Piece {
    /** The name staff and players see, for example "ARM". */
    String displayName();

    boolean available();

    /** Called once at startup on the main thread. */
    GuardResult installGuard(OwnershipHooks hooks);

    Optional<RegionRef> regionAt(Location at);

    Optional<RegionView> findRegion(String worldName, String regionId);

    /** The owner read at a location, only when {@code owner} is the one paying principal there. */
    Optional<OwnershipToken> ownerReadAt(Location at, UUID owner);

    /** The owner read for one region by exact identity, only when {@code owner} is the one paying principal. */
    Optional<OwnershipToken> ownerReadRegion(UUID world, String regionId, UUID owner);

    /** PRESENT, MISSING or UNREADABLE. Never throws. */
    Presence observe(UUID world, String regionId);

    /** Debug lines for staff. Never secrets. */
    List<String> describe();

    /**
     * The Bukkit event handlers of this backend's guard, read once right after {@link #installGuard} succeeds.
     * ProsperityNPC registers them through its error wrapper. Empty for a write hook or a guard of none. API 1.2.
     */
    default List<GuardBinding> guardBindings() { return List.of(); }
}
