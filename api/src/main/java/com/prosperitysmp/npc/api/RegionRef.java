package com.prosperitysmp.npc.api;

import java.util.UUID;

/** A region's identity only. No owner data. */
public interface RegionRef {
    UUID worldId();

    String regionId();
}
