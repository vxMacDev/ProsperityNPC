package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.formulas.IncidentType;
import java.util.Objects;

/** Server-read incident terms and affected-resident snapshot. A hash is binding, not authorization. */
public record RepairQuote(long incidentId, IncidentType type, String snapshotSha256) {
    public RepairQuote {
        Objects.requireNonNull(type);
        if (incidentId <= 0 || snapshotSha256 == null || !snapshotSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid incident repair quote");
    }

    public long cents() { return type.cashRepairCents(); }
}
