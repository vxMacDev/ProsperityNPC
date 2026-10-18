package com.prosperitysmp.npc.nav.contract;

import com.prosperitysmp.npc.api.contract.ContractMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The graph-source contract can fail: each mutant is caught by its own case, and only by that case. */
class GraphSourceContractMetaTest {

    private static final List<Class<? extends GraphSourceContract>> SUITES = List.of(
            DensifierGraphSourceContractTest.class, FileGraphSourceContractTest.class, ScanGraphSourceContractTest.class);

    private static GraphSourceContract.GraphSource subjectOf(Class<? extends GraphSourceContract> suite) throws Exception {
        var constructor = suite.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance().subject();
    }

    @Test
    void everySourcePassesTheContract() {
        for (var suite : SUITES) {
            var result = GraphSourceContract.runWith(suite, null);
            assertEquals(7, result.found(), suite.getSimpleName());
            assertEquals(0, result.failed(), suite.getSimpleName() + " failed: " + result.failedCases());
        }
    }

    @Test
    void eachMutantFailsExactlyItsOwnCase() throws Exception {
        for (var suite : SUITES) {
            var subject = subjectOf(suite);
            for (GraphSourceMutants mutant : GraphSourceMutants.values()) {
                var result = GraphSourceContract.runWith(suite, mutant);
                Set<String> expected = mutant.appliesTo(subject) ? Set.of(mutant.caseId()) : Set.of();
                assertEquals(expected, result.failedCases(), suite.getSimpleName() + " " + mutant.name());
            }
        }
    }

    @Test
    void everyCriticalCaseHasAMutant() {
        Set<String> critical = ContractMeta.criticalCases(GraphSourceContract.class);
        // Positive control: the registry really reads the annotations, and GS-03 is the one case marked not critical.
        assertEquals(Set.of("GS-01", "GS-02", "GS-04", "GS-05", "GS-06", "GS-07"), critical);
        Set<String> mutants = new TreeSet<>();
        for (GraphSourceMutants mutant : GraphSourceMutants.values()) mutants.add(mutant.caseId());
        assertTrue(mutants.containsAll(critical), "mutants " + mutants);
        assertEquals(GraphSourceMutants.values().length, mutants.size(), "one mutant per case");
    }
}
