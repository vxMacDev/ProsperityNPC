package com.prosperitysmp.npc.api.contract;

import com.prosperitysmp.npc.api.contract.broken.MoneySourceMutants;
import com.prosperitysmp.npc.api.contract.broken.PropertyBackendMutants;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The case ids adapters see in a failing contract run are stable, unique and each case can be caught. */
class ContractRegistryTest {

    private static List<Method> cases(Class<?> contract) {
        return Arrays.stream(contract.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Test.class))
                .toList();
    }

    private static Set<String> ids(Class<?> contract) {
        var out = new TreeSet<String>();
        for (Method method : cases(contract)) out.add(method.getAnnotation(CaseId.class).value());
        return out;
    }

    static Stream<Class<?>> contracts() {
        return Stream.of(MoneySourceContract.class, PropertyBackendContract.class);
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void everyTestMethodOfAContractCarriesACaseId(Class<?> contract) {
        for (Method method : cases(contract))
            assertNotNull(method.getAnnotation(CaseId.class), contract.getSimpleName() + "." + method.getName());
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void noTwoCasesOfAContractShareAnId(Class<?> contract) {
        assertEquals(cases(contract).size(), ids(contract).size(), contract.getSimpleName());
    }

    @Test void theMoneyContractHasTheEightDocumentedCases() {
        assertEquals(Set.of("MS-01", "MS-02", "MS-03", "MS-04", "MS-05", "MS-06", "MS-07", "MS-11"), ids(MoneySourceContract.class));
    }

    @Test void thePropertyContractHasTheFifteenDocumentedCases() {
        assertEquals(Set.of("PB-01", "PB-02", "PB-03", "PB-04", "PB-05", "PB-06", "PB-08", "PB-09", "PB-10", "PB-11",
                "PB-12", "PB-13", "PB-14", "PB-15", "PB-16"), ids(PropertyBackendContract.class));
    }

    @Test void theMoneyContractsCriticalCasesAreTheFirstSix() {
        assertEquals(Set.of("MS-01", "MS-02", "MS-03", "MS-04", "MS-05", "MS-06"),
                ContractMeta.criticalCases(MoneySourceContract.class));
    }

    @Test void thePropertyContractsCriticalCasesAreTheElevenThatGuardOrIdentifyOwnership() {
        assertEquals(Set.of("PB-02", "PB-03", "PB-04", "PB-05", "PB-06", "PB-10", "PB-11", "PB-12", "PB-13", "PB-15", "PB-16"),
                ContractMeta.criticalCases(PropertyBackendContract.class));
    }

    @Test void everyIdFollowsItsContractsPrefixAndTwoDigits() {
        for (String id : ids(MoneySourceContract.class)) assertTrue(id.matches("MS-\\d\\d"), id);
        for (String id : ids(PropertyBackendContract.class)) assertTrue(id.matches("PB-\\d\\d"), id);
    }

    @Test void everyMutantTargetsACaseThatExistsInItsContract() {
        for (MoneySourceMutants mutant : MoneySourceMutants.values())
            assertTrue(ids(MoneySourceContract.class).contains(mutant.caseId()), mutant.name());
        for (PropertyBackendMutants mutant : PropertyBackendMutants.values()) {
            assertTrue(ids(PropertyBackendContract.class).contains(mutant.caseId()), mutant.name());
            assertTrue(ids(PropertyBackendContract.class).containsAll(mutant.alsoBreaks()), mutant.name());
        }
    }

    @Test void noMutantListsItsOwnCaseAmongTheOnesItAlsoBreaks() {
        for (PropertyBackendMutants mutant : PropertyBackendMutants.values())
            assertFalse(mutant.alsoBreaks().contains(mutant.caseId()), mutant.name());
    }

    @Test void theMoneyMutantsNeverBreakAnythingBeyondTheirOwnCase() {
        for (MoneySourceMutants mutant : MoneySourceMutants.values())
            assertEquals(Set.of(), mutant.alsoBreaks(), mutant.name());
    }

    @Test void eachMoneyMutantTargetsADifferentCase() {
        var seen = new TreeSet<String>();
        for (MoneySourceMutants mutant : MoneySourceMutants.values())
            assertTrue(seen.add(mutant.caseId()), "two money mutants for " + mutant.caseId());
    }

    @Test void theCaseIdAnnotationIsReadAtRunTimeFromMethodsAndNotCriticalByDefault() throws Exception {
        Retention retention = CaseId.class.getAnnotation(Retention.class);
        Target target = CaseId.class.getAnnotation(Target.class);
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
        assertArrayEquals(new ElementType[]{ElementType.METHOD}, target.value());
        assertEquals(false, CaseId.class.getMethod("critical").getDefaultValue());
        assertNull(CaseId.class.getMethod("value").getDefaultValue(), "the id has no default");
    }

    @Test void theContractsAreAbstractSoAnAdapterMustSupplyItsOwnSubject() {
        assertTrue(Modifier.isAbstract(MoneySourceContract.class.getModifiers()));
        assertTrue(Modifier.isAbstract(PropertyBackendContract.class.getModifiers()));
    }
}
