package com.prosperitysmp.npc.api.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tiny suites for {@link ContractMetaRunnerTest} to run through {@link ContractMeta}. Each case builds its "subject"
 * (a plain value) through {@code ContractMeta.wrap}, the way a real contract builds its subject, so a change to the
 * value breaks the case. Run as they stand, every case passes.
 */
final class ApiMetaSuites {
    private ApiMetaSuites() { }

    static final String SUBJECT = "subject";

    static class Plain {
        @Test @CaseId("T-01")
        void aTextSubjectIsUnchanged() { assertEquals(SUBJECT, ContractMeta.wrap(SUBJECT)); }

        @Test @CaseId(value = "T-02", critical = true)
        void aNumberSubjectIsUnchanged() { assertEquals(42, ContractMeta.wrap(42)); }

        @Test
        void aCaseWithNoId() { assertEquals(SUBJECT, ContractMeta.wrap(SUBJECT)); }

        @Test @CaseId("T-04")
        void aCaseThatNeedsAnUnchangedSubjectOrSkips() {
            assumeTrue(SUBJECT.equals(ContractMeta.wrap(SUBJECT)), "the subject was changed");
        }
    }

    static class Parent {
        @Test @CaseId(value = "P-01", critical = true)
        void aCriticalCaseOfTheParent() { assertEquals(1, ContractMeta.wrap(1)); }

        @Test @CaseId("P-02")
        void anOrdinaryCaseOfTheParent() { assertEquals(2, ContractMeta.wrap(2)); }
    }

    static class Child extends Parent {
        @Test @CaseId(value = "C-01", critical = true)
        void aCriticalCaseOfTheChild() { assertEquals(3, ContractMeta.wrap(3)); }
    }
}
