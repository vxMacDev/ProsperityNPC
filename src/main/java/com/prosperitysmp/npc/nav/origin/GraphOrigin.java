package com.prosperitysmp.npc.nav.origin;

import com.prosperitysmp.npc.nav.NavGraphRepository.GraphInfo;

import java.sql.SQLException;

/**
 * How a graph was made, and what that means for checking it. A scan graph is checked against the palette it
 * was scanned with, exactly as before graph sources existed. A waypoint or file graph is checked against its
 * own document plus the palette and street rules it was built with, and it is always gated at publish.
 */
public interface GraphOrigin {

    /** Reads a stored waypoint or route document by sha (null when missing). */
    @FunctionalInterface
    interface RouteDocs {
        String document(String sha) throws SQLException;
    }

    /** The value of the graph row's {@code source} column. */
    String source();

    /**
     * What the graph's stored fingerprint ({@code palette_hash}) should be if it were built now with this palette
     * hash ("" for no palette) and these street rules; null when that cannot be told, which refuses publish.
     */
    String fingerprintFor(GraphInfo info, String paletteHash, String rules, RouteDocs docs) throws SQLException;

    /** What staff should do when revalidate finds drift. */
    String fixHint(GraphInfo info, int[] driftBox, String worldName, int below, int above);

    /** True when publish must run the fingerprint check and the street audit whatever nav.audit.gate-publish says. */
    boolean alwaysGate();
}
