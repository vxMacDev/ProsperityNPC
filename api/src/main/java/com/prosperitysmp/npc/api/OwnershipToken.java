package com.prosperitysmp.npc.api;

import java.util.UUID;

/** One owner read. Retain on the main thread only. */
public interface OwnershipToken {
    UUID worldId();

    String regionId();

    UUID ownerId();

    /** True only when {@code other} reads the same region, owner and live backend objects as this one. */
    boolean sameIncarnation(OwnershipToken other);
}
