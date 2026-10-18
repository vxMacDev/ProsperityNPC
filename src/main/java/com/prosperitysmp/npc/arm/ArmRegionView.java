package com.prosperitysmp.npc.arm;

import com.prosperitysmp.npc.api.RegionView;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An immutable, read-only snapshot of an ARM region as ProsperityNPC sees it.
 * The plugin never mutates ARM; it only reads these fields.
 *
 * <p>A final class rather than a record because {@link RegionView#ownerUuid()} returns an Optional.
 */
public final class ArmRegionView implements RegionView {
    private final String regionId;
    private final String worldName;
    private final UUID worldUuid;
    private final UUID ownerUuid;
    private final String sellType;
    private final boolean sold;

    public ArmRegionView(String regionId, String worldName, UUID worldUuid, UUID ownerUuid, String sellType, boolean sold) {
        this.regionId = regionId;
        this.worldName = worldName;
        this.worldUuid = worldUuid;
        this.ownerUuid = ownerUuid;
        this.sellType = sellType;
        this.sold = sold;
    }

    @Override public String regionId() { return regionId; }
    public String worldName() { return worldName; }
    public UUID worldUuid() { return worldUuid; }
    @Override public UUID worldId() { return worldUuid; }
    public String sellType() { return sellType; }
    public boolean sold() { return sold; }

    public boolean isSell() {
        return "SELL".equalsIgnoreCase(sellType);
    }

    /** ARM's sell type, for the enrollment.markets allowlist and staff text. */
    @Override public String market() { return sellType; }

    /** "sell" for SELL regions, "rent" for every other ARM type. */
    @Override public String kind() { return isSell() ? "sell" : "rent"; }

    @Override public Optional<UUID> ownerUuid() { return Optional.ofNullable(ownerUuid); }

    public Optional<UUID> owner() {
        return Optional.ofNullable(ownerUuid);
    }

    public boolean matches(UUID expectedWorld, String requestedRegion) {
        return expectedWorld != null && expectedWorld.equals(worldUuid)
                && requestedRegion != null && regionId != null
                && regionId.equalsIgnoreCase(requestedRegion);
    }

    @Override
    public boolean matches(String expectedWorldName, String requestedRegion) {
        return expectedWorldName != null && expectedWorldName.equals(worldName)
                && requestedRegion != null && regionId != null
                && regionId.equalsIgnoreCase(requestedRegion);
    }

    /** The world-and-id check ProsperityNPC applies to any backend's view: same world UUID, region id ignoring case. */
    public static boolean sameRegion(RegionView view, UUID expectedWorld, String requestedRegion) {
        return view != null && expectedWorld != null && expectedWorld.equals(view.worldId())
                && requestedRegion != null && view.regionId() != null
                && view.regionId().equalsIgnoreCase(requestedRegion);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ArmRegionView v && sold == v.sold && Objects.equals(regionId, v.regionId)
                && Objects.equals(worldName, v.worldName) && Objects.equals(worldUuid, v.worldUuid)
                && Objects.equals(ownerUuid, v.ownerUuid) && Objects.equals(sellType, v.sellType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(regionId, worldName, worldUuid, ownerUuid, sellType, sold);
    }

    @Override
    public String toString() {
        return "ArmRegionView[regionId=" + regionId + ", worldName=" + worldName + ", worldUuid=" + worldUuid
                + ", ownerUuid=" + ownerUuid + ", sellType=" + sellType + ", sold=" + sold + "]";
    }
}
