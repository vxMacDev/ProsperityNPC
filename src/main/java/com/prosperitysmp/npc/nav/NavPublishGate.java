package com.prosperitysmp.npc.nav;

/**
 * The publish rules for a graph, as a pure function so they can be tested without a database.
 * A graph with unclassified nodes needs the explicit {@code legacy} argument; a classified graph must
 * have been scanned with the palette as it is now, and must pass the street audit.
 */
public final class NavPublishGate {

    public record Decision(boolean allowed, boolean legacyOverride, String reason) {
        static Decision allow() {
            return new Decision(true, false, "");
        }

        static Decision refuse(String reason) {
            return new Decision(false, false, reason);
        }
    }

    private NavPublishGate() {
    }

    /**
     * @param audit the audit of the stored graph; only read when the graph is fully classified (may be null otherwise)
     */
    public static Decision decide(boolean gateEnabled, boolean legacyArgument, int unknownNodes,
                                  String graphHash, String currentHash, StreetAudit.Result audit) {
        if (!gateEnabled) {
            return Decision.allow();
        }
        if (unknownNodes > 0) {
            if (legacyArgument) {
                return new Decision(true, true, "");
            }
            return Decision.refuse(unknownNodes + " nodes have no surface class, so roads are not protected. "
                    + "Rescan with a classified palette, or publish with the legacy argument.");
        }
        if (graphHash == null || !graphHash.equals(currentHash)) {
            return Decision.refuse("The palette changed since this graph was scanned. Rescan it.");
        }
        if (audit == null) {
            return Decision.refuse("The street audit did not run.");
        }
        if (audit.verdict() != StreetAudit.Verdict.PASS) {
            return Decision.refuse("Street audit " + audit.verdict() + " (" + audit.failedPaths() + " failed of "
                    + audit.paths() + " paths). Run /npcnav audit for the reasons.");
        }
        if (!audit.withinRuntimeLimits()) {
            // Residents plan with a bounded search; a graph past it audits fine here but never routes there.
            return Decision.refuse("The graph has " + audit.nodes() + " nodes and " + audit.edges()
                    + " edges, more than residents can plan across (" + Dijkstra.MAX_VISITED + " nodes, "
                    + Dijkstra.MAX_EDGES_EXAMINED + " edges). Scan a smaller area.");
        }
        return Decision.allow();
    }
}
