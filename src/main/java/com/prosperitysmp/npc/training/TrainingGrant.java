package com.prosperitysmp.npc.training;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Immutable, bounded training authorization; never an instant payment or XP award. */
public record TrainingGrant(UUID grantId, UUID playerId, UUID trainerId, String jobName,
                            UUID sessionId, Instant acceptedAt, LocalDate economyDay,
                            Instant expiresAt, int moneyBasisPoints, int xpBasisPoints) {
    public static final int MONEY_BASIS_POINTS = 500;
    public static final int XP_BASIS_POINTS = 1000;
    public static final int DURATION_MINUTES = 30;

    public TrainingGrant {
        Objects.requireNonNull(grantId);
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(trainerId);
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(economyDay);
        jobName = canonicalJob(jobName);
        acceptedAt = normalize(acceptedAt);
        expiresAt = normalize(expiresAt);
        if (!expiresAt.isAfter(acceptedAt)
                || expiresAt.isAfter(acceptedAt.plusSeconds(DURATION_MINUTES * 60L))
                || moneyBasisPoints < 0 || moneyBasisPoints > MONEY_BASIS_POINTS
                || xpBasisPoints < 0 || xpBasisPoints > XP_BASIS_POINTS) {
            throw new IllegalArgumentException("Training benefit exceeds bounded policy");
        }
    }

    public boolean activeAt(Instant now) {
        return !now.isBefore(acceptedAt) && now.isBefore(expiresAt);
    }

    public static String canonicalJob(String name) {
        Objects.requireNonNull(name);
        if (!name.matches("[A-Za-z][A-Za-z0-9_-]{0,47}")) {
            throw new IllegalArgumentException("Invalid Jobs name");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    static Instant normalize(Instant at) {
        try {
            return Instant.ofEpochMilli(Objects.requireNonNull(at).toEpochMilli());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Training time is outside epoch-millisecond range", e);
        }
    }
}
