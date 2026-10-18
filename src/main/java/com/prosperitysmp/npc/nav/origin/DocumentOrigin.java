package com.prosperitysmp.npc.nav.origin;

import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.nav.NavGraphRepository.GraphInfo;
import com.prosperitysmp.npc.nav.StreetRules;

import java.sql.SQLException;
import java.util.Locale;

/**
 * A graph built from a stored document: staff waypoints ({@code wp1}) or an imported route file ({@code fg1}).
 * Its fingerprint ties the document, the palette and the street rules together, so changing any of them after
 * the build makes publish refuse until the graph is rebuilt.
 */
public final class DocumentOrigin implements GraphOrigin {

    public static final DocumentOrigin WAYPOINTS = new DocumentOrigin(NavGraphRepository.SOURCE_WAYPOINTS, "wp1");
    public static final DocumentOrigin FILE = new DocumentOrigin(NavGraphRepository.SOURCE_FILE, "fg1");

    private final String source;
    private final String prefix;

    private DocumentOrigin(String source, String prefix) {
        this.source = source;
        this.prefix = prefix;
    }

    /** The canonical text of every number that changes a built graph. */
    public static String rules(StreetRules rules, int maxStep) {
        return "reach=" + rules.crosswalkReach() + ";length=" + rules.maxCrossingLength() + ";share="
                + String.format(Locale.ROOT, "%s", rules.minShare()) + ";step=" + maxStep;
    }

    /** SHA-256 of {@code prefix|docSha|paletteHash|rules}. */
    public String fingerprint(String docSha, String paletteHash, String rules) {
        return NavGraphRepository.documentSha(prefix + "|" + docSha + "|" + paletteHash + "|" + rules);
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public String fingerprintFor(GraphInfo info, String paletteHash, String rules, RouteDocs docs) throws SQLException {
        if (info.originSha() == null || paletteHash == null || rules == null) {
            return null;
        }
        // The graph is checked against its own document, never against whatever the region's set is now.
        if (docs.document(info.originSha()) == null) {
            return null;
        }
        return fingerprint(info.originSha(), paletteHash, rules);
    }

    @Override
    public String fixHint(GraphInfo info, int[] driftBox, String worldName, int below, int above) {
        return this == WAYPOINTS
                ? "  Fix the listed points, then /npcnav wp build " + info.regionId()
                : "  Fix the route file" + (info.originRef() == null ? "" : " " + info.originRef())
                        + ", then /npcnav import " + info.regionId() + " " + worldName + " <file>";
    }

    @Override
    public boolean alwaysGate() {
        return true;
    }
}
