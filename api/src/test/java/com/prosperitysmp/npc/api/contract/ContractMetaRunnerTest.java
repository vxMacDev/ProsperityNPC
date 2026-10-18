package com.prosperitysmp.npc.api.contract;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** ContractMeta runs a suite as written and with a broken subject, and says which cases failed. */
class ContractMetaRunnerTest {

    /** Turns every text subject into "broken"; leaves everything else alone. */
    private static final class BreaksText implements ContractMeta.Mutant {
        final AtomicInteger built = new AtomicInteger();
        @Override public String caseId() { return "T-01"; }
        @Override public Object mutate(Object subject) {
            built.incrementAndGet();
            return subject instanceof String ? "broken" : subject;
        }
    }

    /** Turns the number 42 into 43. */
    private static final class BreaksNumber implements ContractMeta.Mutant {
        @Override public String caseId() { return "T-02"; }
        @Override public Object mutate(Object subject) { return Integer.valueOf(42).equals(subject) ? 43 : subject; }
    }

    @Test void aPlainRunFindsEveryCaseAndFailsNone() {
        var result = ContractMeta.run(ApiMetaSuites.Plain.class);
        assertEquals(4, result.found());
        assertEquals(0, result.failed());
        assertEquals(Set.of(), result.failedCases());
    }

    @Test void aMutantFailsExactlyTheCasesItBreaksAndNamesThemByCaseId() {
        var result = ContractMeta.runWith(ApiMetaSuites.Plain.class, new BreaksText());
        assertEquals(4, result.found());
        assertEquals(2, result.failed());
        assertTrue(result.failedCases().contains("T-01"), result.failedCases().toString());
    }

    @Test void aFailedCaseWithNoIdIsNamedByItsDisplayName() {
        var result = ContractMeta.runWith(ApiMetaSuites.Plain.class, new BreaksText());
        assertEquals(Set.of("T-01", "aCaseWithNoId()"), result.failedCases());
    }

    @Test void aCaseSkippedByAnAssumptionIsFoundButNeverFailed() {
        var result = ContractMeta.runWith(ApiMetaSuites.Plain.class, new BreaksText());
        assertFalse(result.failedCases().contains("T-04"));
        assertEquals(4, result.found());
        assertEquals(2, result.failed());
    }

    @Test void aMutantThatChangesOtherSubjectsBreaksOnlyTheCaseThatUsesThem() {
        var result = ContractMeta.runWith(ApiMetaSuites.Plain.class, new BreaksNumber());
        assertEquals(Set.of("T-02"), result.failedCases());
        assertEquals(1, result.failed());
    }

    @Test void everyCaseBuildsItsSubjectThroughTheMutantOnce() {
        var mutant = new BreaksText();
        ContractMeta.runWith(ApiMetaSuites.Plain.class, mutant);
        assertEquals(4, mutant.built.get());
    }

    @Test void aMutantThatCannotBuildItsSubjectFailsEveryCaseThatBuildsOne() {
        ContractMeta.Mutant explodes = new ContractMeta.Mutant() {
            @Override public String caseId() { return "T-01"; }
            @Override public Object mutate(Object subject) { throw new IllegalStateException("cannot break this"); }
        };
        var result = ContractMeta.runWith(ApiMetaSuites.Plain.class, explodes);
        assertEquals(4, result.failed());
        assertEquals(Set.of("T-01", "T-02", "T-04", "aCaseWithNoId()"), result.failedCases());
    }

    @Test void runningWithNoMutantIsTheSameAsAPlainRun() {
        assertEquals(ContractMeta.run(ApiMetaSuites.Plain.class), ContractMeta.runWith(ApiMetaSuites.Plain.class, null));
    }

    @Test void aMutantDoesNotLeakIntoTheNextRun() {
        ContractMeta.runWith(ApiMetaSuites.Plain.class, new BreaksText());
        assertEquals(0, ContractMeta.run(ApiMetaSuites.Plain.class).failed());
        assertEquals(ApiMetaSuites.SUBJECT, ContractMeta.wrap(ApiMetaSuites.SUBJECT));
    }

    @Test void twoMutantsRunOneAfterTheOtherEachBreakOnlyItsOwnCases() {
        var first = ContractMeta.runWith(ApiMetaSuites.Plain.class, new BreaksText());
        var second = ContractMeta.runWith(ApiMetaSuites.Plain.class, new BreaksNumber());
        assertEquals(2, first.failed());
        assertEquals(Set.of("T-02"), second.failedCases());
    }

    @Test void failedCasesAreTheFailedCasesOfAPlainRun() {
        assertEquals(Set.of(), ContractMeta.failedCases(ApiMetaSuites.Plain.class));
        assertEquals(ContractMeta.run(ApiMetaSuites.Child.class).failedCases(), ContractMeta.failedCases(ApiMetaSuites.Child.class));
    }

    @Test void aSuiteThatInheritsCasesRunsThemAll() {
        var result = ContractMeta.run(ApiMetaSuites.Child.class);
        assertEquals(3, result.found());
        assertEquals(0, result.failed());
    }

    @Test void criticalCasesAreOnlyTheOnesMarkedCritical() {
        assertEquals(Set.of("T-02"), ContractMeta.criticalCases(ApiMetaSuites.Plain.class));
    }

    @Test void criticalCasesIncludeInheritedOnesInSortedOrder() {
        assertEquals(List.of("C-01", "P-01"), List.copyOf(ContractMeta.criticalCases(ApiMetaSuites.Child.class)));
    }

    @Test void aClassWithNoCaseIdsHasNoCriticalCases() {
        assertEquals(Set.of(), ContractMeta.criticalCases(ContractMetaRunnerTest.class));
        assertEquals(Set.of(), ContractMeta.criticalCases(Object.class));
    }

    @Test void aMutantBreaksOnlyItsOwnCaseUnlessItSaysOtherwise() {
        assertEquals(Set.of(), new BreaksText().alsoBreaks());
    }

    @Test void aResultKeepsWhatItWasGiven() {
        var result = new ContractMeta.Result(5, 2, Set.of("A-01", "A-02"));
        assertEquals(5, result.found());
        assertEquals(2, result.failed());
        assertEquals(Set.of("A-01", "A-02"), result.failedCases());
        assertEquals(new ContractMeta.Result(5, 2, Set.of("A-02", "A-01")), result);
        assertNotEquals(new ContractMeta.Result(5, 1, Set.of("A-01", "A-02")), result);
    }
}
