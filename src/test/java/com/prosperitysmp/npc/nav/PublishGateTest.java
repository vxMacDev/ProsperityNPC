package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishGateTest {
    private static final StreetAudit.Options OPTIONS =
            new StreetAudit.Options(StreetWorld.RULES, 60_000_000_000L, 1024, System::nanoTime);

    private static StreetAudit.Result audit(StreetAudit.Verdict wanted) {
        NavGraph base = StreetWorld.strip(5, 20).build().graph();
        long home = StreetWorld.node(base, 2, 1).id();
        long work = StreetWorld.node(base, 14, 9).id();
        StreetAudit.Result result = switch (wanted) {
            case PASS -> StreetAudit.audit(StreetWorld.tag(base, Map.of(home, "home", work, "work")), null, OPTIONS);
            case INCONCLUSIVE -> StreetAudit.audit(base, null, OPTIONS);
            case FAIL -> StreetAudit.audit(StreetWorld.tag(base, Map.of(home, "home")),
                    new StreetAudit.Live(Map.of(), Set.of(home), 0, Map.of(), Map.of()), OPTIONS);
        };
        assertEquals(wanted, result.verdict(), "fixture check");
        return result;
    }

    @Test
    void anUnclassifiedGraphNeedsTheExplicitLegacyArgument() {
        var refused = NavPublishGate.decide(true, false, 12, null, "h", null);
        assertFalse(refused.allowed());
        assertTrue(refused.reason().contains("legacy"));
        var allowed = NavPublishGate.decide(true, true, 12, null, "h", null);
        assertTrue(allowed.allowed());
        assertTrue(allowed.legacyOverride(), "the override is what the command logs");
    }

    @Test
    void aClassifiedGraphNeedsTheCurrentPaletteHash() {
        var pass = audit(StreetAudit.Verdict.PASS);
        assertTrue(NavPublishGate.decide(true, false, 0, "h", "h", pass).allowed());
        assertFalse(NavPublishGate.decide(true, false, 0, "old", "h", pass).allowed());
        assertTrue(NavPublishGate.decide(true, false, 0, "old", "h", pass).reason().contains("palette"));
        assertFalse(NavPublishGate.decide(true, false, 0, null, "h", pass).allowed());
        assertFalse(NavPublishGate.decide(true, false, 0, "h", null, pass).allowed(), "a palette no longer defined");
        assertFalse(NavPublishGate.decide(true, true, 0, "old", "h", pass).allowed(), "legacy never bypasses a stale hash");
    }

    @Test
    void aGraphTooBigForResidentsToPlanAcrossIsRefusedEvenWhenTheAuditPasses() {
        var pass = audit(StreetAudit.Verdict.PASS);
        var oversized = new StreetAudit.Result("authoring", StreetAudit.Verdict.PASS, Dijkstra.MAX_VISITED + 1,
                pass.edges(), false, pass.starts(), pass.paths(), 0, pass.maxRun(), pass.counts(), 0, 0, 0, 0,
                false, false, java.util.List.of(), java.util.List.of());
        var refusal = NavPublishGate.decide(true, false, 0, "h", "h", oversized);
        assertFalse(refusal.allowed());
        assertTrue(refusal.reason().contains("smaller area"), refusal.reason());
        assertTrue(NavPublishGate.decide(true, false, 0, "h", "h", pass).allowed(), "control: the same audit within limits");
    }

    @Test
    void anAuditFailOrInconclusiveRefusesAndPassAllows() {
        assertFalse(NavPublishGate.decide(true, false, 0, "h", "h", audit(StreetAudit.Verdict.FAIL)).allowed());
        assertFalse(NavPublishGate.decide(true, false, 0, "h", "h", audit(StreetAudit.Verdict.INCONCLUSIVE)).allowed());
        assertFalse(NavPublishGate.decide(true, false, 0, "h", "h", null).allowed());
        var refusal = NavPublishGate.decide(true, true, 0, "h", "h", audit(StreetAudit.Verdict.FAIL));
        assertFalse(refusal.allowed(), "legacy does not excuse a failed audit of a classified graph");
        assertTrue(refusal.reason().contains("FAIL"));
        assertTrue(NavPublishGate.decide(true, false, 0, "h", "h", audit(StreetAudit.Verdict.PASS)).allowed());
    }

    @Test
    void withTheGateOffEverythingIsAllowedAsBefore() {
        assertTrue(NavPublishGate.decide(false, false, 50, null, null, null).allowed());
        assertTrue(NavPublishGate.decide(false, false, 0, "old", "h", audit(StreetAudit.Verdict.FAIL)).allowed());
    }
}
