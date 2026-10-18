package com.prosperitysmp.npc.adapter.griefprevention;

import com.prosperitysmp.npc.api.contract.ContractMeta;
import com.prosperitysmp.npc.api.contract.broken.PropertyBackendMutants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class GriefPreventionContractMetaTest {
    @Test void theUnchangedSubjectsPass() {
        assertEquals(0, ContractMeta.run(GriefPreventionContractTest.class).failed());
        assertEquals(0, ContractMeta.run(RealEstatePresenceContractTest.class).failed());
    }

    @ParameterizedTest
    @EnumSource(value=PropertyBackendMutants.class, names={"TOKEN_IGNORES_OWNER", "MISSING_AS_PRESENT",
            "VETO_NEVER_APPLIED", "NO_BUMP", "LOCATION_TOKEN_IGNORES_OWNER", "ERROR_SWALLOWED",
            "BUMPS_FIRST_KEY_ONLY", "TOKEN_DROPS_INCARNATION", "POST_HANDLER_MISSING"})
    void theContractCatchesBrokenOwnershipAndGuards(PropertyBackendMutants mutant) {
        var result = ContractMeta.runWith(GriefPreventionContractTest.class, mutant);
        assertTrue(result.failedCases().contains(mutant.caseId()), () -> mutant + ": " + result.failedCases());
    }

    @Test void cancellingWithRealEstatePresentFailsTheContract() {
        var result = ContractMeta.runWith(RealEstatePresenceContractTest.class, PropertyBackendMutants.CANCELS_NEVER_CANCELLED);
        assertTrue(result.failedCases().contains("PB-06"));
    }
}
