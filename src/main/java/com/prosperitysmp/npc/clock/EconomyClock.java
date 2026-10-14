package com.prosperitysmp.npc.clock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Labels an economy day by the local date on which its reset occurs.
 * The reset is inclusive; earlier instants belong to the previous local date.
 *
 * <p>Resets are local wall times, not elapsed-hour offsets from an instant.
 * For any reset hour, a nonexistent wall time shifts forward by the DST gap;
 * an overlapping wall time uses its first occurrence (the earlier offset).
 * The second occurrence does not reset the day again.
 */
public final class EconomyClock {

    private final Clock clock;
    private final ZoneId zone;
    private final int resetHour;

    /**
     * @param clock source of instants; its own zone does not affect economy days
     * @param zone configured economy timezone
     * @param resetHour local reset hour, from 0 through 23 inclusive
     * @throws NullPointerException if clock or zone is null
     * @throws IllegalArgumentException if resetHour is outside 0 through 23
     */
    public EconomyClock(Clock clock, ZoneId zone, int resetHour) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
        if (resetHour < 0 || resetHour > 23) {
            throw new IllegalArgumentException("daily-reset-hour must be between 0 and 23: " + resetHour);
        }
        this.resetHour = resetHour;
    }

    /** The configured economy timezone (clock.timezone). */
    public ZoneId zone() {
        return zone;
    }

    /** The configured local reset hour (clock.daily-reset-hour). */
    public int resetHour() {
        return resetHour;
    }

    public LocalDate today() {
        return dayAt(clock.instant());
    }

    /** The current instant of the clock behind this economy clock. */
    public Instant instant() {
        return clock.instant();
    }

    public LocalDate dayAt(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        LocalDate date = instant.atZone(zone).toLocalDate();
        Instant reset = startOf(date);
        return instant.isBefore(reset) ? date.minusDays(1) : date;
    }

    /** Local reset instant, shifting gaps forward and choosing the first occurrence of overlaps. */
    public Instant startOf(LocalDate day) {
        Objects.requireNonNull(day, "day");
        return day.atTime(resetHour, 0).atZone(zone).toInstant();
    }
}
