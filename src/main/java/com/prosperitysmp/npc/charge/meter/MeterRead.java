package com.prosperitysmp.npc.charge.meter;

import com.prosperitysmp.npc.api.charge.MeterQuery;
import com.prosperitysmp.npc.api.charge.MeterReading;
import com.prosperitysmp.npc.api.charge.UsageMeter;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Reads one outside meter on the main thread and freezes the answer. A throw, an empty answer or a value outside
 * the documented range gives a failure with a reason: that line is not charged and nothing moves. Each call is
 * timed, and a call over {@link #SLOW_NANOS} logs a warning that names the meter.
 */
public final class MeterRead {
    public static final long SLOW_NANOS = 5_000_000L;

    /** A frozen reading as it is stored on the invoice line. */
    public record Frozen(String meterId, long units, Boolean powered, String detailSha) { }

    /** Either a frozen reading or the reason there is none. */
    public record Result(Frozen reading, String failure) {
        public boolean ok() { return reading != null; }
    }

    private MeterRead() { }

    public static Result read(String meterId, UsageMeter meter, MeterQuery query, LongSupplier nanos, Consumer<String> warn) {
        if (meter == null) return new Result(null, "meter " + meterId + " is not available");
        long started = nanos.getAsLong();
        Optional<MeterReading> answer;
        try {
            answer = meter.read(query);
        } catch (RuntimeException | LinkageError failure) {
            return new Result(null, "meter " + meterId + " failed: " + failure.getClass().getSimpleName());
        } finally {
            long took = nanos.getAsLong() - started;
            if (took > SLOW_NANOS) warn.accept("Slow bill meter " + meterId + ": " + (took / 1_000_000L) + " ms for one reading.");
        }
        if (answer == null || answer.isEmpty()) return new Result(null, "meter " + meterId + " gave no reading");
        MeterReading r = answer.get();
        long units;
        String sha;
        Boolean powered;
        try {
            units = r.units();
            sha = r.detailSha();
            powered = r.powered().orElse(null);
        } catch (RuntimeException | LinkageError failure) {
            return new Result(null, "meter " + meterId + " reading unreadable");
        }
        if (units < 0 || units > MeterReading.MAX_UNITS)
            return new Result(null, "meter " + meterId + " reading out of range");
        if (sha == null || !sha.matches("[0-9a-f]{64}")) return new Result(null, "meter " + meterId + " evidence hash invalid");
        return new Result(new Frozen(meterId, units, powered, sha), null);
    }
}
