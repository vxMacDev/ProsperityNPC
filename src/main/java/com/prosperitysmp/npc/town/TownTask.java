package com.prosperitysmp.npc.town;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Immutable persisted snapshot; later authored-definition changes cannot change an accepted errand. */
public record TownTask(UUID taskId, UUID playerId, TownTaskDefinition definition, Instant acceptedAt,
                       LocalDate acceptedEconomyDay, Instant expiresAt, Status status, Instant closedAt,
                       LocalDate completedEconomyDay) {
    public enum Status { ACTIVE, COMPLETED, CANCELLED, EXPIRED }

    public TownTask {
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(definition);
        Objects.requireNonNull(acceptedEconomyDay);
        Objects.requireNonNull(status);
        acceptedAt = normalize(acceptedAt);
        expiresAt = normalize(expiresAt);
        if (!expiresAt.equals(acceptedAt.plusSeconds(definition.durationMinutes() * 60L))) {
            throw new IllegalArgumentException("Task expiry differs from its duration snapshot");
        }
        if (status == Status.ACTIVE) {
            if (closedAt != null || completedEconomyDay != null) {
                throw new IllegalArgumentException("Active task has terminal evidence");
            }
        } else {
            closedAt = normalize(closedAt);
            if (closedAt.isBefore(acceptedAt)
                    || (status == Status.EXPIRED ? !closedAt.equals(expiresAt) : !closedAt.isBefore(expiresAt))) {
                throw new IllegalArgumentException("Invalid task closing time");
            }
            if (status == Status.COMPLETED) {
                Objects.requireNonNull(completedEconomyDay);
                if (completedEconomyDay.isBefore(acceptedEconomyDay)) {
                    throw new IllegalArgumentException("Task completion day regressed");
                }
            } else if (completedEconomyDay != null) {
                throw new IllegalArgumentException("Uncompleted task has completion day");
            }
        }
    }

    static Instant normalize(Instant at) {
        Instant result = Objects.requireNonNull(at).truncatedTo(ChronoUnit.MILLIS);
        try {
            result.toEpochMilli();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Task time is outside epoch-millisecond range", e);
        }
        return result;
    }
}
