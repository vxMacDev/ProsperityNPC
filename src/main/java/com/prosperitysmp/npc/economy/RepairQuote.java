package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.formulas.IncidentType;
import java.util.Objects;

/** Server-read incident terms and affected-resident snapshot. A hash is binding, not authorization. */
public record RepairQuote(long incidentId, IncidentType type, String snapshotSha256, Long configuredPriceCents) {
    public RepairQuote(long incidentId, IncidentType type, String snapshotSha256) {
        this(incidentId, type, snapshotSha256, null);
    }

    public RepairQuote {
        Objects.requireNonNull(type);
        if (incidentId <= 0 || snapshotSha256 == null || !snapshotSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid incident repair quote");
        if (configuredPriceCents != null) {
            if (configuredPriceCents <= 0 || configuredPriceCents > ItemRepairRecipes.MAX_CASH_CENTS
                    || TransferProof.toCents(configuredPriceCents / 100.0).orElse(-1) != configuredPriceCents)
                throw new IllegalArgumentException("Invalid configured repair price");
            if (configuredPriceCents == type.cashRepairCents()) configuredPriceCents = null;
        }
    }

    public long cents() { return configuredPriceCents == null ? type.cashRepairCents() : configuredPriceCents; }

    public static long storedCents(IncidentType type, long cashCents, Long configuredPriceCents) {
        Objects.requireNonNull(type);
        long accepted = configuredPriceCents == null ? type.cashRepairCents() : configuredPriceCents;
        if (accepted <= 0 || accepted > ItemRepairRecipes.MAX_CASH_CENTS || cashCents != accepted
                || (configuredPriceCents != null && accepted == type.cashRepairCents()))
            throw new IllegalArgumentException("Stored repair price differs from its accepted terms");
        return accepted;
    }
}
