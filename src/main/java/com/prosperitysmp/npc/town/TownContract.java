package com.prosperitysmp.npc.town;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable persisted contract snapshot. Later config changes cannot change an accepted contract. The checks match
 * the SQL table: open rows carry no closing evidence, hand-over rows carry a run ID, expiry closes at the deadline.
 */
public record TownContract(UUID contractId, UUID playerId, ContractDefinition definition, Instant acceptedAt,
                           LocalDate acceptedEconomyDay, int daySlot, Instant expiresAt, Status status, UUID runId,
                           Instant closedAt, LocalDate completedEconomyDay) {
    public enum Status { ACTIVE, HANDOVER_INTENT, COMPLETED, CANCELLED, EXPIRED, AMBIGUOUS, RESOLVED_CONSUMED, RESOLVED_NOT_CONSUMED }
    public static final Set<Status> OPEN = EnumSet.of(Status.ACTIVE, Status.HANDOVER_INTENT, Status.AMBIGUOUS);
    private static final Set<Status> WITH_RUN = EnumSet.of(Status.HANDOVER_INTENT, Status.COMPLETED, Status.AMBIGUOUS,
            Status.RESOLVED_CONSUMED, Status.RESOLVED_NOT_CONSUMED);

    public TownContract {
        Objects.requireNonNull(contractId);
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(definition);
        Objects.requireNonNull(acceptedEconomyDay);
        Objects.requireNonNull(status);
        acceptedAt = normalize(acceptedAt);
        expiresAt = normalize(expiresAt);
        if (!expiresAt.equals(acceptedAt.plusSeconds(definition.durationMinutes() * 60L)))
            throw new IllegalArgumentException("Contract expiry differs from its duration snapshot");
        boolean partnership = definition.kind() == ContractDefinition.Kind.PARTNERSHIP;
        if (daySlot < 0 || daySlot > 3 || partnership != (daySlot == 0))
            throw new IllegalArgumentException("Invalid contract day slot");
        if (WITH_RUN.contains(status) != (runId != null)) throw new IllegalArgumentException("Contract run ID does not match its status");
        if (OPEN.contains(status)) {
            if (closedAt != null || completedEconomyDay != null) throw new IllegalArgumentException("Open contract has closing evidence");
        } else {
            closedAt = normalize(closedAt);
            if (closedAt.isBefore(acceptedAt) || (status == Status.EXPIRED && !closedAt.equals(expiresAt)))
                throw new IllegalArgumentException("Invalid contract closing time");
            boolean done = status == Status.COMPLETED || status == Status.RESOLVED_CONSUMED;
            if (done) {
                if (completedEconomyDay == null || completedEconomyDay.isBefore(acceptedEconomyDay))
                    throw new IllegalArgumentException("Completed contract needs a completion day on or after acceptance");
            } else if (completedEconomyDay != null) throw new IllegalArgumentException("Unfinished contract has a completion day");
        }
    }

    public boolean open() { return OPEN.contains(status); }

    static Instant normalize(Instant at) {
        Instant result = Objects.requireNonNull(at).truncatedTo(ChronoUnit.MILLIS);
        try { result.toEpochMilli(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Contract time is outside epoch-millisecond range", e); }
        return result;
    }

    TownContract with(Status next, UUID run, Instant closed, LocalDate completed) {
        return new TownContract(contractId, playerId, definition, acceptedAt, acceptedEconomyDay, daySlot, expiresAt,
                next, run, closed, completed);
    }
}
