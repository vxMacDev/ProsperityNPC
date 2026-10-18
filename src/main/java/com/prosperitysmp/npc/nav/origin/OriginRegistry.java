package com.prosperitysmp.npc.nav.origin;

import com.prosperitysmp.npc.nav.NavGraphRepository;

/** Finds the origin for a graph row's {@code source}. An unknown source gets null, and every caller refuses it. */
public final class OriginRegistry {

    public static final ScanOrigin SCAN = new ScanOrigin();

    private OriginRegistry() {
    }

    public static GraphOrigin of(String source) {
        if (source == null || NavGraphRepository.SOURCE_SCAN.equals(source)) return SCAN;
        if (NavGraphRepository.SOURCE_WAYPOINTS.equals(source)) return DocumentOrigin.WAYPOINTS;
        if (NavGraphRepository.SOURCE_FILE.equals(source)) return DocumentOrigin.FILE;
        return null;
    }

    /** The refusal for a graph this version does not know how to check. */
    public static String unknown(String source) {
        return "This graph's source '" + source + "' is not known to this version of ProsperityNPC; it cannot be used here.";
    }
}
