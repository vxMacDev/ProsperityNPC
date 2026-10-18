package com.prosperitysmp.npc.charge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The home facts a bill formula may read, each a whole number with a documented range. The values are read from
 * SQL on the worker when the invoice is issued. A value outside its range means the reading is wrong, so the line
 * is not charged.
 */
public final class ChargeVariables {
    /** A closed whole-number range. */
    public record Range(long lo, long hi) {
        public Range {
            if (lo > hi) throw new IllegalArgumentException("range low above high");
        }

        public boolean contains(long value) { return value >= lo && value <= hi; }
    }

    public static final long MAX_RENT_CENTS = 1_000_000L;
    public static final long MAX_METER_UNITS = 1_000_000_000L;
    public static final long MAX_OWNER_HOMES = 10_000L;

    /** Variables every policy may read. {@code units} and {@code powered} depend on the meter and are added per policy. */
    public static final Map<String, Range> HOME;

    static {
        Map<String, Range> m = new LinkedHashMap<>();
        m.put("capacity", new Range(1, 4));
        m.put("tenants", new Range(0, 4));
        m.put("upgrade_levels", new Range(0, 6));
        m.put("advertising_level", new Range(0, 3));
        m.put("facilities_level", new Range(0, 3));
        m.put("condition", new Range(0, 100));
        m.put("asking_rent_cents", new Range(0, MAX_RENT_CENTS));
        m.put("reference_rent_cents", new Range(0, MAX_RENT_CENTS));
        m.put("rent_paid_cents", new Range(0, 4 * MAX_RENT_CENTS));
        m.put("owner_homes", new Range(0, MAX_OWNER_HOMES));
        HOME = java.util.Collections.unmodifiableMap(m);
    }

    /** The variables a {@code derived:<variable>} meter may count. */
    public static final Set<String> DERIVABLE = Set.of("tenants", "upgrade_levels", "advertising_level",
            "facilities_level", "capacity");

    public static final String UNITS = "units";
    public static final String POWERED = "powered";

    private ChargeVariables() { }
}
