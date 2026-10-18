package com.prosperitysmp.npc.api;

import java.util.Optional;
import java.util.UUID;

/** A read-only snapshot of one region. */
public interface RegionView extends RegionRef {
    /** ARM: the sell type. Other backends: their own id. */
    String market();

    /** ARM: "sell" or "rent". Other backends: "owned". */
    String kind();

    Optional<UUID> ownerUuid();

    boolean matches(String worldName, String regionId);
}
