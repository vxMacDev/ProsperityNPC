package com.prosperitysmp.npc.nav;

/**
 * What a graph node stands on. The code is what the database and the terrain capture store, so
 * the numbers never change. UNKNOWN means a graph scanned without classes: it routes as it always
 * did and is never treated as street.
 */
public enum NavSurface {
    UNKNOWN(0), SIDEWALK(1), ACCESS(2), ROAD(3), CROSSING(4);

    private final int code;

    NavSurface(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** True for the surfaces a resident may only cross in a straight line. */
    public boolean isStreet() {
        return this == ROAD || this == CROSSING;
    }

    public static NavSurface fromCode(int code) {
        for (NavSurface surface : values()) {
            if (surface.code == code) {
                return surface;
            }
        }
        throw new IllegalArgumentException("unknown nav surface code " + code);
    }
}
