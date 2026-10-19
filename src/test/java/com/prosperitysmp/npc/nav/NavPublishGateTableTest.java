package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every branch of the publish decision, with audits built by hand so each one says exactly what it reports. */
class NavPublishGateTableTest {

    private static StreetAudit.Result audit(StreetAudit.Verdict verdict, int nodes, int edges, boolean withinLimits,
                                            int paths, int failedPaths) {
        return new StreetAudit.Result("authoring", verdict, nodes, edges, withinLimits, 3, paths, failedPaths, 0,
                Map.of(), 0, 0, 0, 0, false, false, List.of(), List.of());
    }

    private static StreetAudit.Result passing() {
        return audit(StreetAudit.Verdict.PASS, 40, 120, true, 12, 0);
    }

    @ParameterizedTest
    @CsvSource({"false,0", "false,7", "true,0", "true,7"})
    void withTheGateOffTheDecisionIsAlwaysThePlainAllowance(boolean legacy, int unknown) {
        NavPublishGate.Decision decision = NavPublishGate.decide(false, legacy, unknown, null, "current", null);
        assertEquals(new NavPublishGate.Decision(true, false, ""), decision);
    }

    @Test
    void withTheGateOffAFailingAuditAndAStaleHashStillPass() {
        var failing = audit(StreetAudit.Verdict.FAIL, 40, 120, true, 12, 5);
        assertTrue(NavPublishGate.decide(false, false, 0, "old", "new", failing).allowed());
    }

    @Test
    void unclassifiedNodesWithTheLegacyArgumentAreAllowedAndFlagged() {
        NavPublishGate.Decision decision = NavPublishGate.decide(true, true, 3, null, null, null);
        assertEquals(new NavPublishGate.Decision(true, true, ""), decision);
    }

    @Test
    void unclassifiedNodesWithoutTheLegacyArgumentAreRefusedWithTheCount() {
        NavPublishGate.Decision decision = NavPublishGate.decide(true, false, 3, "h", "h", passing());
        assertFalse(decision.allowed());
        assertFalse(decision.legacyOverride());
        assertEquals("3 nodes have no surface class, so roads are not protected. "
                + "Rescan with a classified palette, or publish with the legacy argument.", decision.reason());
    }

    @Test
    void oneUnclassifiedNodeIsEnoughToRefuse() {
        NavPublishGate.Decision decision = NavPublishGate.decide(true, false, 1, "h", "h", passing());
        assertFalse(decision.allowed());
        assertTrue(decision.reason().startsWith("1 nodes have no surface class"), decision.reason());
    }

    @Test
    void theLegacyOverrideNeverLooksAtTheHashOrTheAudit() {
        var failing = audit(StreetAudit.Verdict.FAIL, 40, 120, true, 12, 5);
        NavPublishGate.Decision decision = NavPublishGate.decide(true, true, 9, "old", "new", failing);
        assertTrue(decision.allowed());
        assertTrue(decision.legacyOverride());
    }

    @Test
    void aClassifiedGraphNeverReportsALegacyOverrideEvenWhenTheArgumentWasGiven() {
        NavPublishGate.Decision decision = NavPublishGate.decide(true, true, 0, "h", "h", passing());
        assertEquals(new NavPublishGate.Decision(true, false, ""), decision);
    }

    @Test
    void aNegativeUnknownCountIsTreatedAsClassified() {
        NavPublishGate.Decision decision = NavPublishGate.decide(true, false, -1, "h", "other", passing());
        assertFalse(decision.allowed());
        assertEquals("The palette changed since this graph was scanned. Rescan it.", decision.reason());
    }

    @ParameterizedTest
    @CsvSource({"old,new", "NEW,new", "'new ',new", "'',new"})
    void aGraphHashThatDiffersInAnyWayIsAStalePalette(String graphHash, String currentHash) {
        NavPublishGate.Decision decision = NavPublishGate.decide(true, false, 0, graphHash, currentHash, passing());
        assertFalse(decision.allowed());
        assertEquals("The palette changed since this graph was scanned. Rescan it.", decision.reason());
    }

    @Test
    void aMissingGraphHashIsAStalePaletteEvenWhenTheCurrentHashIsAlsoMissing() {
        NavPublishGate.Decision decision = NavPublishGate.decide(true, false, 0, null, null, passing());
        assertFalse(decision.allowed());
        assertEquals("The palette changed since this graph was scanned. Rescan it.", decision.reason());
    }

    @Test
    void aGraphHashAgainstAPaletteThatIsNoLongerDefinedIsStale() {
        NavPublishGate.Decision decision = NavPublishGate.decide(true, false, 0, "h", null, passing());
        assertFalse(decision.allowed());
        assertEquals("The palette changed since this graph was scanned. Rescan it.", decision.reason());
    }

    @Test
    void aStaleHashIsReportedBeforeAMissingAudit() {
        NavPublishGate.Decision decision = NavPublishGate.decide(true, false, 0, "old", "new", null);
        assertEquals("The palette changed since this graph was scanned. Rescan it.", decision.reason());
    }

    @Test
    void aMatchingHashWithNoAuditIsRefused() {
        NavPublishGate.Decision decision = NavPublishGate.decide(true, false, 0, "h", "h", null);
        assertFalse(decision.allowed());
        assertFalse(decision.legacyOverride());
        assertEquals("The street audit did not run.", decision.reason());
    }

    @ParameterizedTest
    @EnumSource(value = StreetAudit.Verdict.class, names = {"FAIL", "INCONCLUSIVE"})
    void anAuditThatDidNotPassIsRefusedWithItsVerdictAndPathCounts(StreetAudit.Verdict verdict) {
        var result = audit(verdict, 40, 120, true, 40, 3);
        NavPublishGate.Decision decision = NavPublishGate.decide(true, false, 0, "h", "h", result);
        assertFalse(decision.allowed());
        assertEquals("Street audit " + verdict + " (3 failed of 40 paths). Run /npcnav audit for the reasons.",
                decision.reason());
    }

    @Test
    void aNonPassingAuditIsReportedBeforeTheRuntimeLimits() {
        var result = audit(StreetAudit.Verdict.FAIL, 5000, 20000, false, 10, 1);
        NavPublishGate.Decision decision = NavPublishGate.decide(true, false, 0, "h", "h", result);
        assertEquals("Street audit FAIL (1 failed of 10 paths). Run /npcnav audit for the reasons.", decision.reason());
    }

    @Test
    void aPassingAuditPastTheRuntimeLimitsIsRefusedWithTheGraphSizeAndTheLimits() {
        var result = audit(StreetAudit.Verdict.PASS, 2048, 9000, false, 10, 0);
        NavPublishGate.Decision decision = NavPublishGate.decide(true, false, 0, "h", "h", result);
        assertFalse(decision.allowed());
        assertEquals("The graph has 2048 nodes and 9000 edges, more than residents can plan across (1024 nodes, 8192 edges). "
                + "Scan a smaller area.", decision.reason());
    }

    @Test
    void theLimitsInTheMessageAreTheSearchLimitsResidentsUse() {
        var result = audit(StreetAudit.Verdict.PASS, 1, 1, false, 1, 0);
        String reason = NavPublishGate.decide(true, false, 0, "h", "h", result).reason();
        assertTrue(reason.contains("(" + Dijkstra.MAX_VISITED + " nodes, " + Dijkstra.MAX_EDGES_EXAMINED + " edges)"), reason);
    }

    @Test
    void aPassingAuditWithinTheLimitsIsAllowedWithNoReason() {
        NavPublishGate.Decision decision = NavPublishGate.decide(true, false, 0, "h", "h", passing());
        assertEquals(new NavPublishGate.Decision(true, false, ""), decision);
    }

    @Test
    void aGraphAtTheRuntimeLimitsStillCountsAsWithinThemWhenTheAuditSaysSo() {
        var result = audit(StreetAudit.Verdict.PASS, Dijkstra.MAX_VISITED, Dijkstra.MAX_EDGES_EXAMINED, true, 10, 0);
        assertTrue(NavPublishGate.decide(true, false, 0, "h", "h", result).allowed());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theLegacyArgumentDoesNotExcuseAnyClassifiedGraphProblem(boolean legacy) {
        assertFalse(NavPublishGate.decide(true, legacy, 0, "old", "new", passing()).allowed());
        assertFalse(NavPublishGate.decide(true, legacy, 0, "h", "h", null).allowed());
        assertFalse(NavPublishGate.decide(true, legacy, 0, "h", "h", audit(StreetAudit.Verdict.INCONCLUSIVE, 4, 6, true, 1, 0)).allowed());
        assertFalse(NavPublishGate.decide(true, legacy, 0, "h", "h", audit(StreetAudit.Verdict.PASS, 4, 6, false, 1, 0)).allowed());
    }
}
