package com.prosperitysmp.npc.adapter.griefprevention;

import com.prosperitysmp.npc.api.GuardBinding;
import org.bukkit.Location;

import java.util.List;
import java.util.UUID;

/**
 * The only GriefPrevention calls the adapter needs. {@code RealGriefPreventionView} makes them against
 * GriefPrevention 16.18.7; tests use a fake that replays the vendor's recorded behaviour. Main thread only. Any
 * method may throw a LinkageError after an incompatible GriefPrevention update; the backend turns that into
 * "unavailable".
 */
public interface GriefPreventionView {
    /** One claim as GriefPrevention holds it right now. */
    record Claim(long id, UUID worldId, String worldName, Long parentId, UUID ownerId, Object identity, List<Long> childIds) {
        public Claim {
            childIds = List.copyOf(childIds);
        }
    }

    /** What the backend does when GriefPrevention fires an ownership event. */
    interface Events {
        /** ClaimTransferEvent at HIGHEST, before the change. True cancels the transfer. */
        boolean transferRequested(Claim claim);

        /** ClaimTransferEvent at MONITOR, only when not cancelled. */
        void transferred(Claim claim);

        /** ClaimDeletedEvent at MONITOR. The claim is already gone from GriefPrevention. */
        void deleted(Claim claim);

        /** ClaimChangeEvent at MONITOR (a resize or other change of the claim's shape). */
        void changed(Claim claim);
    }

    boolean available();

    /** True when the RealEstate add-on is installed: it takes the buyer's money and then ignores a cancel. */
    boolean realEstatePresent();

    /** The innermost claim (a subdivision when there is one) containing the location, or null. */
    Claim claimAt(Location at);

    /** The claim with this id, or null when it was deleted. */
    Claim claim(long id);

    /** Bukkit handlers that call {@code events}; empty for a view that delivers events another way. */
    List<GuardBinding> bindings(Events events);
}
