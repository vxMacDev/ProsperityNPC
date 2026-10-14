package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.formulas.PropertyFormulas;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Coherent evidence summary, not premium authorization or statistical confidence.
 * Missing data does not become a successful observation, a neutral rating or a denominator discount.
 */
public record ServiceAssessment(UtilityServiceAssessment utilities, IncidentServiceAssessment incidents,
                                RetentionAssessment retention) {
    public static final String POLICY_VERSION = "recorded-service-v1";
    public enum Component { UTILITIES, INCIDENTS, RETENTION }
    /** Mathematical extrema under missing rates in [0,1], not a confidence interval. */
    public record ScoreBounds(int minimum, int maximum) { }

    public ServiceAssessment {
        Objects.requireNonNull(utilities); Objects.requireNonNull(incidents); Objects.requireNonNull(retention);
        if (!utilities.ownerId().equals(incidents.ownerId()) || !utilities.ownerId().equals(retention.ownerId())
                || !utilities.capturedAt().equals(incidents.capturedAt()) || !utilities.capturedAt().equals(retention.capturedAt())
                || !utilities.dueFrom().equals(incidents.dueFrom()) || !utilities.dueFrom().equals(retention.admittedFrom()))
            throw new IllegalArgumentException("Service components must share owner, cutoff and window start");
    }

    public UUID ownerId() { return utilities.ownerId(); }
    public Instant capturedAt() { return utilities.capturedAt(); }
    public Instant windowFrom() { return utilities.dueFrom(); }

    public Set<Component> unknownComponents() {
        EnumSet<Component> result = EnumSet.noneOf(Component.class);
        if (utilities.onTimeRate().isEmpty()) result.add(Component.UTILITIES);
        if (incidents.onTimeRate().isEmpty()) result.add(Component.INCIDENTS);
        if (retention.retentionRate().isEmpty()) result.add(Component.RETENTION);
        return Set.copyOf(result);
    }

    /** Existing formula, attainable range 25..75 for three valid rates. No minimum-sample or premium claim. */
    public OptionalInt operatorScore() {
        OptionalDouble u = utilities.onTimeRate(), i = incidents.onTimeRate(), r = retention.retentionRate();
        if (u.isEmpty() || i.isEmpty() || r.isEmpty()) return OptionalInt.empty();
        return OptionalInt.of(score(u.getAsDouble(), i.getAsDouble(), r.getAsDouble()));
    }

    public ScoreBounds scoreBounds() {
        OptionalDouble u = utilities.onTimeRate(), i = incidents.onTimeRate(), r = retention.retentionRate();
        return new ScoreBounds(score(u.orElse(0), i.orElse(0), r.orElse(0)),
                score(u.orElse(1), i.orElse(1), r.orElse(1)));
    }

    private static int score(double u, double i, double r) {
        if (!Double.isFinite(u) || !Double.isFinite(i) || !Double.isFinite(r)
                || u < 0 || u > 1 || i < 0 || i > 1 || r < 0 || r > 1)
            throw new IllegalArgumentException("Invalid service rate");
        return PropertyFormulas.operatorScore(u, i, r);
    }
}
