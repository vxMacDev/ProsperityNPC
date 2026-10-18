package com.prosperitysmp.npc.api.charge;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Which home and which period a meter reading is for. Built by ProsperityNPC; meters only read it. */
public final class MeterQuery {
    private final UUID world;
    private final String regionId;
    private final UUID owner;
    private final Instant periodStart;
    private final Instant periodEnd;

    private MeterQuery(UUID world, String regionId, UUID owner, Instant periodStart, Instant periodEnd) {
        this.world = world;
        this.regionId = regionId;
        this.owner = owner;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
    }

    /** For ProsperityNPC and for adapter tests. */
    public static MeterQuery of(UUID world, String regionId, UUID owner, Instant periodStart, Instant periodEnd) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(regionId);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(periodStart);
        Objects.requireNonNull(periodEnd);
        if (periodEnd.isBefore(periodStart)) throw new IllegalArgumentException("period ends before it starts");
        return new MeterQuery(world, regionId, owner, periodStart, periodEnd);
    }

    public UUID world() { return world; }
    public String regionId() { return regionId; }
    public UUID owner() { return owner; }
    public Instant periodStart() { return periodStart; }
    public Instant periodEnd() { return periodEnd; }
}
