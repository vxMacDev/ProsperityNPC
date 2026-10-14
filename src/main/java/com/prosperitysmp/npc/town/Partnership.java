package com.prosperitysmp.npc.town;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** One stored partnership. Active from startedDay through lastActiveDay, both inclusive. */
public record Partnership(UUID partnershipId, UUID ownerId, UUID residentId, LocalDate startedDay, LocalDate lastActiveDay,
                          PartnerPerks perks, Status status, EndReason endReason, Instant endedAt, UUID startContractId,
                          long revision) {
    public enum Status { ACTIVE, ENDED }
    public enum EndReason { LAPSED, OWNER_ENDED, STAFF_ENDED }

    public Partnership {
        Objects.requireNonNull(partnershipId); Objects.requireNonNull(ownerId); Objects.requireNonNull(residentId);
        Objects.requireNonNull(startedDay); Objects.requireNonNull(lastActiveDay); Objects.requireNonNull(perks);
        Objects.requireNonNull(status); Objects.requireNonNull(startContractId);
        if (lastActiveDay.isBefore(startedDay) || revision < 1 || !perks.any()
                || (status == Status.ACTIVE) != (endReason == null) || (endReason == null) != (endedAt == null))
            throw new IllegalArgumentException("Invalid partnership");
    }

    /** Active and not past its last day. */
    public boolean activeOn(LocalDate day) { return status == Status.ACTIVE && !day.isAfter(lastActiveDay); }

    /** Inside the last renewWindowDays of the term (and not past it). */
    public boolean renewableOn(LocalDate day, int renewWindowDays) {
        return activeOn(day) && !day.isBefore(lastActiveDay.minusDays(renewWindowDays - 1L));
    }
}
