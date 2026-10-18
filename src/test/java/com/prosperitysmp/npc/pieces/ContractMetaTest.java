package com.prosperitysmp.npc.pieces;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.MoneySource;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import com.prosperitysmp.npc.api.contract.ContractMeta;
import com.prosperitysmp.npc.api.contract.MoneySourceContract;
import com.prosperitysmp.npc.api.contract.PropertyBackendContract;
import com.prosperitysmp.npc.api.contract.broken.MoneySourceMutants;
import com.prosperitysmp.npc.api.contract.broken.PropertyBackendMutants;
import com.prosperitysmp.npc.economy.vault.VaultMoneySourceContractTest;
import com.prosperitysmp.npc.property.FakeBackendContractTest;
import com.prosperitysmp.npc.property.FakeGuardedBackendContractTest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** The contract suites can fail: each critical case catches its own broken subject, and only that case fails. */
class ContractMetaTest {

    @Test void theVaultSourcePassesTheMoneyContract() {
        var result = ContractMeta.run(VaultMoneySourceContractTest.class);
        assertTrue(result.found() >= 8, "cases found: " + result.found());
        assertEquals(0, result.failed(), "failed: " + result.failedCases());
    }

    @Test void eachMoneyMutantFailsExactlyItsOwnCase() {
        for (MoneySourceMutants mutant : MoneySourceMutants.values()) {
            var result = ContractMeta.runWith(VaultMoneySourceContractTest.class, mutant);
            assertEquals(Set.of(mutant.caseId()), result.failedCases(), mutant.name());
        }
    }

    /** MS-01's rule is the selector's: offered as a third-party source, the scale mutant is refused. */
    @Test void theScaleMutantIsRefusedByTheSelector() {
        MoneySource good = new MoneySource() {
            @Override public String id() { return "test-money"; }
            @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
            @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
            @Override public Capabilities capabilities() {
                return Capabilities.builder().value("scale", "2").flag("artifact-hashable", true).build();
            }
            @Override public Optional<Pin> pin() { return Optional.empty(); }
        };
        var subject = new MoneySourceContract.Subject() {
            @Override public MoneySource source() { return good; }
            @Override public org.bukkit.OfflinePlayer account() { throw new AssertionError("not read"); }
            @Override public List<String> moneyCalls() { return List.of(); }
            @Override public List<String> allCalls() { return List.of(); }
            @Override public void replaceRegistration() { }
            @Override public void disableOwnerPlugin() { }
            @Override public void disableProvider() { }
            @Override public Path providerArtifact() { return Path.of("provider.jar"); }
            @Override public Path apiArtifact() { return Path.of("api.jar"); }
        };
        var mutant = Arrays.stream(MoneySourceMutants.values()).filter(m -> m.caseId().equals("MS-01")).findFirst().orElseThrow();
        var broken = ((MoneySourceContract.Subject) mutant.mutate(subject)).source();
        var selector = new PieceSelector<>(MoneySource.class);
        var refused = selector.select("test-money", Map.of(), List.of(new PieceSelector.Candidate<>(broken, "Mutant", "1.0", 0)));
        assertNull(refused.selected(), mutant.name());
        assertEquals(List.of(new PieceSelector.Refused("test-money", "Mutant 1.0",
                "scale '3' (only 2 decimal places are supported)")), refused.refused());
        // Positive control: the same source before the mutant is selected.
        var accepted = selector.select("test-money", Map.of(), List.of(new PieceSelector.Candidate<>(good, "Good", "1.0", 0)));
        assertSame(good, accepted.selected());
        assertEquals(List.of(), accepted.refused());
    }

    @Test void theFakeBackendPassesThePropertyContract() {
        var result = ContractMeta.run(FakeBackendContractTest.class);
        assertTrue(result.found() >= 5, "cases found: " + result.found());
        assertEquals(0, result.failed(), "failed: " + result.failedCases());
    }

    @Test void theGuardedFakePassesEveryGuardCase() {
        var result = ContractMeta.run(FakeGuardedBackendContractTest.class);
        assertTrue(result.found() >= 15, "cases found: " + result.found());
        assertEquals(0, result.failed(), "failed: " + result.failedCases());
    }

    /** Against the fake that supports every guard case: each mutant fails its own case plus only those it declares. */
    @Test void eachPropertyMutantFailsExactlyItsOwnCase() {
        for (PropertyBackendMutants mutant : PropertyBackendMutants.values()) {
            var result = ContractMeta.runWith(FakeGuardedBackendContractTest.class, mutant);
            var expected = new TreeSet<String>(mutant.alsoBreaks());
            expected.add(mutant.caseId());
            assertEquals(expected, result.failedCases(), mutant.name());
        }
    }

    @Test void theOldFakeWithoutAGuardStillCatchesTheReadMutants() {
        for (PropertyBackendMutants mutant : List.of(PropertyBackendMutants.TOKEN_IGNORES_OWNER, PropertyBackendMutants.MISSING_AS_PRESENT)) {
            var result = ContractMeta.runWith(FakeBackendContractTest.class, mutant);
            assertEquals(Set.of(mutant.caseId()), result.failedCases(), mutant.name());
        }
    }

    @Test void everyCriticalCaseHasAMutant() {
        assertEquals(ContractMeta.criticalCases(MoneySourceContract.class), ids(MoneySourceMutants.values()));
        assertEquals(ContractMeta.criticalCases(PropertyBackendContract.class), ids(PropertyBackendMutants.values()));
        // Positive control: the registry really reads the annotations.
        assertTrue(ContractMeta.criticalCases(MoneySourceContract.class).contains("MS-02"));
    }

    private static Set<String> ids(ContractMeta.Mutant[] mutants) {
        var out = new TreeSet<String>();
        Arrays.stream(mutants).forEach(m -> out.add(m.caseId()));
        return out;
    }
}
