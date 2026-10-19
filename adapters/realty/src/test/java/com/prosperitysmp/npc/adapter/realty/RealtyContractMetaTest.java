package com.prosperitysmp.npc.adapter.realty;

import com.prosperitysmp.npc.api.contract.ContractMeta;
import com.prosperitysmp.npc.api.contract.broken.PropertyBackendMutants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Left out on purpose: VETO_NEVER_APPLIED (PB-03 is skipped, there is no veto), CANCELS_NEVER_CANCELLED (nothing is
 * ever cancelled), POST_HANDLER_MISSING (no override flow) and EXTERNAL_CHANGE_OFF_MAIN (events arrive on main).
 */
class RealtyContractMetaTest {
    @Test void theUnchangedSubjectPasses() {
        assertEquals(0, ContractMeta.run(RealtyContractTest.class).failed());
    }

    @ParameterizedTest
    @EnumSource(value=PropertyBackendMutants.class, names={"TOKEN_IGNORES_OWNER", "MISSING_AS_PRESENT", "NO_BUMP",
            "LOCATION_TOKEN_IGNORES_OWNER", "ERROR_SWALLOWED", "BUMPS_FIRST_KEY_ONLY", "TOKEN_DROPS_INCARNATION"})
    void theContractCatchesBrokenOwnershipAndGuards(PropertyBackendMutants mutant) {
        var result = ContractMeta.runWith(RealtyContractTest.class, mutant);
        assertTrue(result.failedCases().contains(mutant.caseId()), () -> mutant + ": " + result.failedCases());
    }
}
