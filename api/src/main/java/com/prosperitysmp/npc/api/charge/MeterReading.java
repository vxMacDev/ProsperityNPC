package com.prosperitysmp.npc.api.charge;

import java.util.Optional;
import java.util.regex.Pattern;

/** One meter answer. Built by the meter through {@link #builder()}. */
public final class MeterReading {
    public static final long MAX_UNITS = 1_000_000_000L;
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    private final long units;
    private final Boolean powered;
    private final String detailSha;

    private MeterReading(long units, Boolean powered, String detailSha) {
        this.units = units;
        this.powered = powered;
        this.detailSha = detailSha;
    }

    public static Builder builder() { return new Builder(); }

    /** Units used, 0 to 1,000,000,000. */
    public long units() { return units; }

    /** Whether the home was powered, when the meter knows. */
    public Optional<Boolean> powered() { return Optional.ofNullable(powered); }

    /** The meter's own evidence hash: 64 lowercase hex characters. */
    public String detailSha() { return detailSha; }

    public static final class Builder {
        private long units;
        private Boolean powered;
        private String detailSha;

        private Builder() { }

        public Builder units(long units) {
            this.units = units;
            return this;
        }

        public Builder powered(boolean powered) {
            this.powered = powered;
            return this;
        }

        public Builder detailSha(String detailSha) {
            this.detailSha = detailSha;
            return this;
        }

        /** Refuses a reading outside the documented ranges, so a bad meter fails here and not in a bill. */
        public MeterReading build() {
            if (units < 0 || units > MAX_UNITS) throw new IllegalArgumentException("units outside 0.." + MAX_UNITS);
            if (detailSha == null || !SHA.matcher(detailSha).matches())
                throw new IllegalArgumentException("detailSha must be 64 lowercase hex characters");
            return new MeterReading(units, powered, detailSha);
        }
    }
}
