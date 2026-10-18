package com.prosperitysmp.npc.api;

import java.util.UUID;

/**
 * Implemented by ProsperityNPC and called by a property backend's guard. A backend with a write hook calls
 * {@link #beginWrite}; a backend that can only watch or veto vendor events uses the other methods (API 1.2).
 */
public interface OwnershipHooks {
    /**
     * Called before the backend changes a region's ownership. Throws while a ProsperityNPC transaction holds the
     * region. The returned callback must run when the write ends, in a finally block. Main thread; write hooks only.
     */
    Runnable beginWrite(UUID world, String regionId);

    /**
     * Main thread. An owner change the backend could not block has happened (or is about to). Any payment holding
     * the region fails its next check. Never throws because a payment holds the region. API 1.2.
     */
    default void externalChange(UUID world, String regionId) {
        throw new UnsupportedOperationException("externalChange needs ProsperityNPC API 1.2");
    }

    /**
     * Any thread. Like {@link #externalChange}, for events that arrive off the main thread: the region is marked at
     * once and the change is applied on the next main-thread tick. API 1.2.
     */
    default void markDirty(UUID world, String regionId) {
        throw new UnsupportedOperationException("markDirty needs ProsperityNPC API 1.2");
    }

    /** Any thread. True while a ProsperityNPC payment or move holds the region. A snapshot; may be stale. API 1.2. */
    default boolean isHeld(UUID world, String regionId) {
        throw new UnsupportedOperationException("isHeld needs ProsperityNPC API 1.2");
    }

    /**
     * Runs {@code body}. Anything it throws, including a LinkageError from a changed vendor plugin, marks the
     * backend unavailable for the rest of the run and holds payments; nothing is rethrown. Wrap every handler body
     * the core does not register for you (events that are not Bukkit events). API 1.2.
     */
    default void guarded(String what, Runnable body) {
        throw new UnsupportedOperationException("guarded needs ProsperityNPC API 1.2");
    }
}
