package com.prosperitysmp.npc.economy;

/** The particular occupancy, not merely the resident's permanent identity. */
public record EvictionQuote(long tenancyRevision, int slot) {
    public EvictionQuote {
        if (tenancyRevision < 0 || tenancyRevision == Long.MAX_VALUE || slot < 0 || slot >= 4)
            throw new IllegalArgumentException("Invalid eviction tenancy");
    }
}
