package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.commerce.ItemCollectionEvidence;
import com.prosperitysmp.npc.resident.RelationshipMemory.Stage;
import com.prosperitysmp.npc.town.TownContractRepository.Code;
import com.prosperitysmp.npc.town.TownContractRepository.NoPartnership;
import com.prosperitysmp.npc.town.TownContractRepository.PartnerTerms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The pure rules inside the contract repository: partnership terms, the changed-offer test and take evidence. */
class TownContractRulesTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);
    private static final UUID RESIDENT = new UUID(0, 3);
    private static final UUID RUN = new UUID(0, 4);
    private static final String BEFORE = "a".repeat(64);
    private static final String AFTER = "b".repeat(64);
    private static final PartnerPerks PERKS = new PartnerPerks(20, 1);

    private static PartnerTerms terms(int term, int seats, int window, int minContracts, String material, int quantity) {
        return new PartnerTerms(PERKS, term, seats, window, Stage.TRUSTED, minContracts, material, quantity);
    }

    private static PartnerTerms terms() { return terms(7, 4, 3, 3, "IRON_INGOT", 16); }

    @Test void validTermsKeepEveryField() {
        PartnerTerms t = terms();
        assertEquals(PERKS, t.perks());
        assertEquals(7, t.termDays());
        assertEquals(4, t.maxSeats());
        assertEquals(3, t.renewWindowDays());
        assertEquals(Stage.TRUSTED, t.minimumStage());
        assertEquals(3, t.minContracts());
        assertEquals("IRON_INGOT", t.material());
        assertEquals(16, t.quantity());
    }

    @ParameterizedTest @CsvSource({"1,1", "30,30", "7,7", "14,1"})
    void aTermOfOneToThirtyDaysIsAllowedWithTheWindowInsideIt(int term, int window) {
        assertEquals(term, terms(term, 4, window, 0, "COAL", 1).termDays());
    }

    @ParameterizedTest @CsvSource({"0", "-1", "31", "100", "2147483647"})
    void aTermOutsideOneToThirtyDaysIsRefused(int term) {
        assertThrows(IllegalArgumentException.class, () -> terms(term, 4, 1, 0, "COAL", 1));
    }

    @ParameterizedTest @CsvSource({"1", "16", "8"})
    void aSeatLimitOfOneToSixteenIsAllowed(int seats) {
        assertEquals(seats, terms(7, seats, 3, 0, "COAL", 1).maxSeats());
    }

    @ParameterizedTest @CsvSource({"0", "-1", "17", "1000"})
    void aSeatLimitOutsideOneToSixteenIsRefused(int seats) {
        assertThrows(IllegalArgumentException.class, () -> terms(7, seats, 3, 0, "COAL", 1));
    }

    @ParameterizedTest @CsvSource({"0", "-1", "8", "30"})
    void aRenewalWindowOfZeroOrBeyondTheTermIsRefused(int window) {
        assertThrows(IllegalArgumentException.class, () -> terms(7, 4, window, 0, "COAL", 1));
    }

    @ParameterizedTest @CsvSource({"0", "1", "10"})
    void zeroToTenRequiredContractsIsAllowed(int needed) {
        assertEquals(needed, terms(7, 4, 3, needed, "COAL", 1).minContracts());
    }

    @ParameterizedTest @CsvSource({"-1", "11", "100"})
    void requiredContractsOutsideZeroToTenAreRefused(int needed) {
        assertThrows(IllegalArgumentException.class, () -> terms(7, 4, 3, needed, "COAL", 1));
    }

    @ParameterizedTest @CsvSource({"0", "-1", "257", "2147483647"})
    void aQuantityOutsideOneToTwoFiftySixIsRefused(int quantity) {
        assertThrows(IllegalArgumentException.class, () -> terms(7, 4, 3, 0, "COAL", quantity));
    }

    @Test void theQuantityEdgesAreAllowed() {
        assertEquals(1, terms(7, 4, 3, 0, "COAL", 1).quantity());
        assertEquals(256, terms(7, 4, 3, 0, "COAL", 256).quantity());
    }

    @ParameterizedTest @CsvSource({"coal", "1COAL", "COAL INGOT", "minecraft:coal", "Coal", "COAL-1"})
    void aMaterialMustBeUpperSnakeCase(String material) {
        assertThrows(IllegalArgumentException.class, () -> terms(7, 4, 3, 0, material, 1));
    }

    @Test void aMissingMaterialPerksOrStageIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> terms(7, 4, 3, 0, null, 1));
        assertThrows(NullPointerException.class, () -> new PartnerTerms(null, 7, 4, 3, Stage.TRUSTED, 0, "COAL", 1));
        assertThrows(NullPointerException.class, () -> new PartnerTerms(PERKS, 7, 4, 3, null, 0, "COAL", 1));
    }

    private static ContractDefinition handIn(String material, int quantity) {
        return ContractDefinition.partnership(true, RESIDENT, material, quantity, Stage.TRUSTED);
    }

    @Test void anOfferThatAsksForTheSameThingHasNotChanged() {
        assertFalse(TownContractRepository.offerChanged(handIn("IRON_INGOT", 16), terms()));
    }

    @Test void anOfferThatNowAsksForAnotherMaterialHasChanged() {
        assertTrue(TownContractRepository.offerChanged(handIn("GOLD_INGOT", 16), terms()));
    }

    @Test void anOfferThatNowAsksForMoreOfTheSameMaterialHasChanged() {
        assertTrue(TownContractRepository.offerChanged(handIn("IRON_INGOT", 15), terms()));
    }

    @Test void anOfferThatNowAsksForLessIsStillHonouredByTheLargerHandIn() {
        assertFalse(TownContractRepository.offerChanged(handIn("IRON_INGOT", 17), terms()));
        assertFalse(TownContractRepository.offerChanged(handIn("IRON_INGOT", 256), terms()));
    }

    @Test void aDifferentMaterialIsAChangeEvenWhenTheQuantityIsLower() {
        assertTrue(TownContractRepository.offerChanged(handIn("GOLD_INGOT", 64), terms()));
    }

    private static ItemCollectionEvidence evidence(UUID player, String before, String after, List<Integer> beforeUnits,
                                                   List<Integer> afterUnits, boolean returned, boolean durable) {
        return new ItemCollectionEvidence(player, RUN, before, after, beforeUnits, afterUnits, returned, durable);
    }

    private static ItemCollectionEvidence clean(int before, int after) {
        return evidence(PLAYER, BEFORE, AFTER, List.of(before), List.of(after), true, true);
    }

    @Test void aCleanTakeOfExactlyTheQuantityConfirms() {
        assertTrue(TownContractRepository.confirms(clean(64, 0), PLAYER, 64, 64));
        assertTrue(TownContractRepository.confirms(clean(20, 4), PLAYER, 20, 16));
        assertTrue(TownContractRepository.confirms(clean(1, 0), PLAYER, 1, 1));
    }

    @Test void oneUnitShortOrOverNeverConfirms() {
        assertFalse(TownContractRepository.confirms(clean(20, 5), PLAYER, 20, 16));
        assertFalse(TownContractRepository.confirms(clean(20, 3), PLAYER, 20, 16));
    }

    @Test void nothingTakenNeverConfirms() {
        assertFalse(TownContractRepository.confirms(clean(20, 20), PLAYER, 20, 16));
    }

    @Test void aMoreThanAskedTakeIsNotAnAcceptableHandOver() {
        assertFalse(TownContractRepository.confirms(clean(64, 0), PLAYER, 64, 63));
    }

    @Test void aStartingCountThatDiffersFromTheRecordedOneNeverConfirms() {
        assertFalse(TownContractRepository.confirms(clean(21, 5), PLAYER, 20, 16));
        assertFalse(TownContractRepository.confirms(clean(19, 3), PLAYER, 20, 16));
    }

    @Test void evidenceForAnotherPlayerNeverConfirms() {
        assertFalse(TownContractRepository.confirms(clean(20, 4), OTHER, 20, 16));
    }

    @Test void aTakeThatLeftTheInventoryHashUnchangedNeverConfirms() {
        ItemCollectionEvidence same = evidence(PLAYER, BEFORE, BEFORE, List.of(20), List.of(4), true, true);
        assertFalse(TownContractRepository.confirms(same, PLAYER, 20, 16));
    }

    @Test void aTakeThatDidNotReturnNormallyNeverConfirms() {
        ItemCollectionEvidence abnormal = evidence(PLAYER, BEFORE, AFTER, List.of(20), List.of(4), false, true);
        assertFalse(TownContractRepository.confirms(abnormal, PLAYER, 20, 16));
    }

    @Test void aTakeWhoseSaveWasNotProvenNeverConfirms() {
        ItemCollectionEvidence unproven = evidence(PLAYER, BEFORE, AFTER, List.of(20), List.of(4), true, false);
        assertFalse(TownContractRepository.confirms(unproven, PLAYER, 20, 16));
        assertFalse(TownContractRepository.confirms(unproven.withDurable(true), PLAYER, 20, 16),
                "withDurable can only lower the proof, never raise it");
    }

    @Test void evidenceWithMoreThanOneLineNeverConfirmsASingleLineContract() {
        ItemCollectionEvidence two = evidence(PLAYER, BEFORE, AFTER, List.of(20, 5), List.of(4, 5), true, true);
        assertFalse(TownContractRepository.confirms(two, PLAYER, 20, 16));
    }

    @Test void resultKeepsItsFieldsAndDefaultsTheNoPartnershipReasonToNull() {
        var result = new TownContractRepository.Result(Code.NOT_FOUND, Optional.empty(), Optional.empty(), 3);
        assertEquals(Code.NOT_FOUND, result.code());
        assertEquals(3, result.detail());
        assertNull(result.noPartnership());
        assertTrue(result.contract().isEmpty());
        assertTrue(result.partnership().isEmpty());
    }

    @Test void resultKeepsTheReasonItWasGiven() {
        var result = new TownContractRepository.Result(Code.RESOLVED_CONSUMED, Optional.empty(), Optional.empty(), 0,
                NoPartnership.NO_PERK_LEFT);
        assertEquals(NoPartnership.NO_PERK_LEFT, result.noPartnership());
    }

    @Test void resultRefusesMissingParts() {
        assertThrows(NullPointerException.class, () -> new TownContractRepository.Result(null, Optional.empty(), Optional.empty(), 0));
        assertThrows(NullPointerException.class, () -> new TownContractRepository.Result(Code.NOT_FOUND, null, Optional.empty(), 0));
        assertThrows(NullPointerException.class, () -> new TownContractRepository.Result(Code.NOT_FOUND, Optional.empty(), null, 0));
    }

    @Test void aStaffResolveRefusalMapsToItsTrueReason() {
        assertEquals(NoPartnership.HAS_PARTNERSHIP, TownContractRepository.noPartnership(Code.PARTNER_HAS_ONE));
        assertEquals(NoPartnership.NOT_RENEWABLE, TownContractRepository.noPartnership(Code.PARTNER_NOT_RENEWABLE));
    }

    @ParameterizedTest @EnumSource(value = Code.class, names = {"PARTNER_HOME", "PARTNER_SEATS_FULL", "PARTNER_STAGE",
            "PARTNER_CONTRACTS", "NOT_FOUND", "ACCEPTED", "COMPLETED", "PARTNER_DAILY_LIMIT"})
    void everyOtherRefusalIsACheckFailure(Code refusal) {
        assertEquals(NoPartnership.CHECK_FAILED, TownContractRepository.noPartnership(refusal));
    }

    @Test void theListLimitAndKeptWindowAreTheDocumentedOnes() {
        assertEquals(100, TownContractRepository.MAX_LIST);
        assertEquals(30, TownContractRepository.KEPT_WINDOW_DAYS);
    }
}
