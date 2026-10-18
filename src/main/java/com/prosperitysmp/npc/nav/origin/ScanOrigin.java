package com.prosperitysmp.npc.nav.origin;

import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.nav.NavGraphRepository.GraphInfo;

/** A block scan: today's behaviour, unchanged. The fingerprint is the palette hash itself. */
public final class ScanOrigin implements GraphOrigin {

    @Override
    public String source() {
        return NavGraphRepository.SOURCE_SCAN;
    }

    @Override
    public String fingerprintFor(GraphInfo info, String paletteHash, String rules, RouteDocs docs) {
        return paletteHash;
    }

    @Override
    public String fixHint(GraphInfo info, int[] box, String worldName, int below, int above) {
        return "  Rescan: /npcnav scanbox " + info.regionId() + " " + worldName + " "
                + (box[0] - 3) + " " + (box[1] - below) + " " + (box[2] - 3) + " " + (box[3] + 3) + " "
                + (box[4] + above) + " " + (box[5] + 3) + " " + info.palette();
    }

    @Override
    public boolean alwaysGate() {
        return false;
    }
}
