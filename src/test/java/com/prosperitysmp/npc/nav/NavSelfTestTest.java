package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The deployed self-test is only worth running if it can fail: both verdicts are pinned here. */
class NavSelfTestTest {
    @Test
    void theLegacyStreetFailsAndTheCityStreetPasses() {
        var report = NavSelfTest.run();
        assertTrue(report.ok());
        var legacy = report.cases().get(0);
        var city = report.cases().get(1);
        assertEquals("selftest-legacy", legacy.name());
        assertEquals(StreetAudit.Verdict.FAIL, legacy.result().verdict());
        assertTrue(legacy.result().count(StreetAudit.Code.LATERAL) > 0);
        assertEquals("selftest-city", city.name());
        assertEquals(StreetAudit.Verdict.PASS, city.result().verdict());
        assertTrue(city.result().paths() > 0);
    }

    @Test
    void logLinesCarryTheGraphNameAndVerdictForTheBot() {
        var lines = NavSelfTest.run().logLines();
        assertTrue(lines.get(0).startsWith("NPCNAV-AUDIT graph=selftest-legacy mode=runtime verdict=FAIL"), lines.get(0));
        assertTrue(lines.get(1).startsWith("NPCNAV-AUDIT graph=selftest-city mode=runtime verdict=PASS"), lines.get(1));
    }

    @Test
    void aWrongExpectationIsReportedAsBroken() {
        var legacy = NavSelfTest.run().cases().get(0);
        var wrong = new NavSelfTest.Case("x", StreetAudit.Verdict.PASS, legacy.result());
        assertFalse(wrong.ok());
        assertFalse(new NavSelfTest.Report(List.of(wrong)).ok());
    }
}
