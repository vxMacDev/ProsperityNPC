package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.resident.RelationshipMemory.Stage;
import com.prosperitysmp.npc.town.ContractDefinition.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Exact edges of an authored contract: every length, count and duration at its limit and one past it. */
class ContractDefinitionBoundaryTest {
    private static final UUID GIVER = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);

    private static ContractDefinition supply(String id, String title, String description, String material, int quantity, int minutes) {
        return new ContractDefinition(id, Kind.SUPPLY, title, description, GIVER, GIVER, material, quantity, Stage.STRANGER, minutes);
    }

    @Test void theDocumentedLimitsAreTheShippedOnes() {
        assertEquals(80, ContractDefinition.MAX_TITLE);
        assertEquals(512, ContractDefinition.MAX_DESCRIPTION);
        assertEquals(256, ContractDefinition.MAX_QUANTITY);
        assertEquals(10, ContractDefinition.MIN_DURATION);
        assertEquals(30, ContractDefinition.MIN_AUTHORED_MINUTES);
        assertEquals(1440, ContractDefinition.MAX_DURATION);
        assertEquals("partner-start", ContractDefinition.PARTNER_START);
        assertEquals("partner-renew", ContractDefinition.PARTNER_RENEW);
        assertEquals(10, ContractDefinition.PARTNER_WINDOW_MINUTES);
    }

    @Test void aTitleOfEightyCharactersIsKeptAndEightyOneIsRefused() {
        assertEquals(80, supply("a", "t".repeat(80), "D", "COAL", 1, 30).title().length());
        assertThrows(IllegalArgumentException.class, () -> supply("a", "t".repeat(81), "D", "COAL", 1, 30));
    }

    @Test void aDescriptionOfFiveHundredTwelveCharactersIsKeptAndOneMoreIsRefused() {
        assertEquals(512, supply("a", "T", "d".repeat(512), "COAL", 1, 30).description().length());
        assertThrows(IllegalArgumentException.class, () -> supply("a", "T", "d".repeat(513), "COAL", 1, 30));
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "  ", "\t", "\n", " lead", "trail ", "two\nlines", "tab\there", "nul\u0000", "del\u007f"})
    void aTitleOrDescriptionMustBeNonBlankUnpaddedAndFreeOfControlCharacters(String text) {
        assertThrows(IllegalArgumentException.class, () -> supply("a", text, "D", "COAL", 1, 30));
        assertThrows(IllegalArgumentException.class, () -> supply("a", "T", text, "COAL", 1, 30));
    }

    @Test void aMissingTitleOrDescriptionIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> supply("a", null, "D", "COAL", 1, 30));
        assertThrows(IllegalArgumentException.class, () -> supply("a", "T", null, "COAL", 1, 30));
    }

    @ParameterizedTest @ValueSource(strings = {"a", "0", "_", "-", "a-b_c0", "mara-wall-stone"})
    void aCanonicalIdIsKept(String id) {
        assertEquals(id, supply(id, "T", "D", "COAL", 1, 30).id());
    }

    @ParameterizedTest @ValueSource(strings = {"", "A", "a b", "a.b", "a/b", "a:b", "a\n", "\u00e9", "a;b"})
    void aNonCanonicalIdIsRefused(String id) {
        assertThrows(IllegalArgumentException.class, () -> supply(id, "T", "D", "COAL", 1, 30));
    }

    @Test void anIdOfFortyEightIsKeptAndFortyNineIsRefusedAndNullIsRefused() {
        assertEquals(48, supply("i".repeat(48), "T", "D", "COAL", 1, 30).id().length());
        assertThrows(IllegalArgumentException.class, () -> supply("i".repeat(49), "T", "D", "COAL", 1, 30));
        assertThrows(IllegalArgumentException.class, () -> supply(null, "T", "D", "COAL", 1, 30));
    }

    @ParameterizedTest @CsvSource({"1", "2", "255", "256"})
    void aQuantityOfOneToTwoFiftySixIsKept(int quantity) {
        assertEquals(quantity, supply("a", "T", "D", "COAL", quantity, 30).quantity());
    }

    @ParameterizedTest @CsvSource({"0", "-1", "257", "1000", "2147483647", "-2147483648"})
    void aQuantityOutsideOneToTwoFiftySixIsRefused(int quantity) {
        assertThrows(IllegalArgumentException.class, () -> supply("a", "T", "D", "COAL", quantity, 30));
    }

    @ParameterizedTest @CsvSource({"10", "11", "30", "1439", "1440"})
    void aDurationOfTenToFourteenFortyMinutesIsKept(int minutes) {
        assertEquals(minutes, supply("a", "T", "D", "COAL", 1, minutes).durationMinutes());
    }

    @ParameterizedTest @CsvSource({"9", "0", "-1", "1441", "100000", "2147483647", "-2147483648"})
    void aDurationOutsideTenToFourteenFortyMinutesIsRefused(int minutes) {
        assertThrows(IllegalArgumentException.class, () -> supply("a", "T", "D", "COAL", 1, minutes));
    }

    @ParameterizedTest @ValueSource(strings = {"A", "COAL", "OAK_LOG", "A1", "Z9_9"})
    void aMaterialIsUpperSnakeCase(String material) {
        assertEquals(material, supply("a", "T", "D", material, 1, 30).material());
    }

    @ParameterizedTest @ValueSource(strings = {"", "coal", "Coal", "1COAL", "_COAL", "CO AL", "CO-AL", "minecraft:coal", "COAL\n", "C\u00d6AL"})
    void anythingElseIsNotAMaterial(String material) {
        assertThrows(IllegalArgumentException.class, () -> supply("a", "T", "D", material, 1, 30));
    }

    @Test void aMaterialOfSixtyFourIsKeptAndSixtyFiveIsRefusedAndNullIsRefused() {
        assertEquals(64, supply("a", "T", "D", "M".repeat(64), 1, 30).material().length());
        assertThrows(IllegalArgumentException.class, () -> supply("a", "T", "D", "M".repeat(65), 1, 30));
        assertThrows(IllegalArgumentException.class, () -> supply("a", "T", "D", null, 1, 30));
    }

    @Test void aSupplyContractMustReturnToItsGiver() {
        assertThrows(IllegalArgumentException.class, () -> new ContractDefinition("a", Kind.SUPPLY, "T", "D", GIVER, OTHER, "COAL", 1, Stage.STRANGER, 30));
    }

    @Test void aDeliveryMustGoToSomeoneElse() {
        assertThrows(IllegalArgumentException.class, () -> new ContractDefinition("a", Kind.DELIVER, "T", "D", GIVER, GIVER, "COAL", 1, Stage.STRANGER, 30));
        ContractDefinition delivery = new ContractDefinition("a", Kind.DELIVER, "T", "D", GIVER, OTHER, "COAL", 1, Stage.STRANGER, 30);
        assertEquals(OTHER, delivery.recipientId());
        assertFalse(delivery.partnerStart());
    }

    @ParameterizedTest @CsvSource({"partner-start,true", "partner-renew,false"})
    void onlyThePartnerStartHandOverIsAStart(String id, boolean start) {
        ContractDefinition d = new ContractDefinition(id, Kind.PARTNERSHIP, "T", "D", GIVER, GIVER, "COAL", 1, Stage.TRUSTED, 10);
        assertEquals(start, d.partnerStart());
    }

    @ParameterizedTest @ValueSource(strings = {"partner", "partner-end", "Partner-Start", "mara-wall-stone", "partner-start2"})
    void aPartnershipHandOverMustUseOneOfTheTwoReservedIds(String id) {
        assertThrows(IllegalArgumentException.class, () -> new ContractDefinition(id, Kind.PARTNERSHIP, "T", "D", GIVER, GIVER, "COAL", 1, Stage.TRUSTED, 10));
    }

    @Test void aPartnershipHandOverGoesToTheResidentItself() {
        assertThrows(IllegalArgumentException.class,
                () -> new ContractDefinition("partner-start", Kind.PARTNERSHIP, "T", "D", GIVER, OTHER, "COAL", 1, Stage.TRUSTED, 10));
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void everyKindNeedsItsGiverItsRecipientAndAMinimumStage(Kind kind) {
        UUID recipient = kind == Kind.DELIVER ? OTHER : GIVER;
        String id = kind == Kind.PARTNERSHIP ? "partner-start" : "an-id";
        assertThrows(NullPointerException.class, () -> new ContractDefinition(id, kind, "T", "D", GIVER, recipient, "COAL", 1, null, 30));
        assertThrows(NullPointerException.class, () -> new ContractDefinition(id, kind, "T", "D", null, recipient, "COAL", 1, Stage.STRANGER, 30));
        assertThrows(NullPointerException.class, () -> new ContractDefinition(id, kind, "T", "D", GIVER, null, "COAL", 1, Stage.STRANGER, 30));
    }

    @Test void aMissingKindIsRefused() {
        assertThrows(NullPointerException.class, () -> new ContractDefinition("a", null, "T", "D", GIVER, GIVER, "COAL", 1, Stage.STRANGER, 30));
    }

    @Test void thePartnershipFactoryBuildsTheTenMinuteHandOverForTheResident() {
        ContractDefinition start = ContractDefinition.partnership(true, GIVER, "IRON_INGOT", 16, Stage.TRUSTED);
        assertEquals("partner-start", start.id());
        assertEquals(Kind.PARTNERSHIP, start.kind());
        assertEquals("Partnership", start.title());
        assertEquals("Materials for a partnership.", start.description());
        assertEquals(GIVER, start.giverId());
        assertEquals(GIVER, start.recipientId());
        assertEquals("IRON_INGOT", start.material());
        assertEquals(16, start.quantity());
        assertEquals(Stage.TRUSTED, start.minimumStage());
        assertEquals(10, start.durationMinutes());
        ContractDefinition renew = ContractDefinition.partnership(false, GIVER, "IRON_INGOT", 16, Stage.TRUSTED);
        assertEquals("partner-renew", renew.id());
        assertEquals("Partnership renewal", renew.title());
    }
}
