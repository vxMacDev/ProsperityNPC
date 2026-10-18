package com.prosperitysmp.npc.charge.meter;

/** The built-in {@code flat} meter: every home uses one unit, so {@code units} in a formula is always 1. */
public final class FlatMeter {
    public static final String ID = "flat";

    private FlatMeter() { }

    public static long units() {
        return 1L;
    }
}
