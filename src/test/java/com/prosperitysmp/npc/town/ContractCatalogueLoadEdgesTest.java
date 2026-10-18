package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.resident.RelationshipMemory.Stage;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.town.TownContractRepository.PartnerTerms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** The contracts and partnerships config reader: each key at its limits, each bad value with its exact warning. */
class ContractCatalogueLoadEdgesTest {
    private static final ResidentCast CAST = ResidentCast.builtIn();
    private static final UUID MARA = ResidentCatalogue.idFor("mara-vale");
    private static final UUID ELLIS = ResidentCatalogue.idFor("ellis-ward");
    private static final UUID NICO = ResidentCatalogue.idFor("nico-reed");
    private static final Predicate<String> ITEMS = name -> !name.equals("NOT_AN_ITEM");
    private static final String P = "town.contracts.";
    private static final String Q = "town.partnerships.";

    private static ContractCatalogue load(Map<String, Object> values) {
        return ContractCatalogue.load(values::get, CAST, ITEMS);
    }

    private static ContractCatalogue load(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return load(values);
    }

    private static Map<String, Object> entry(String id, UUID giver) {
        return new HashMap<>(Map.of("id", id, "giver", giver.toString(), "title", "Wall stone", "description", "Bring me cobblestone.",
                "material", "COBBLESTONE", "quantity", 64, "minimum-stage", "STRANGER", "duration-minutes", 60));
    }

    private static Map<String, Object> offer(UUID resident) {
        return new HashMap<>(Map.of("resident", resident.toString(), "material", "IRON_INGOT", "quantity", 16,
                "fee-discount-percent", 40, "rank-bonus-points", 0, "pitch", "Weekly iron."));
    }

    // ---- the plain defaults --------------------------------------------------------------------------------

    @Test void anEmptyConfigGivesTheShippedDefaultsAllOffWithNoWarnings() {
        ContractCatalogue c = load();
        assertFalse(c.contractsEnabled());
        assertFalse(c.partnershipsEnabled());
        assertFalse(c.anyOn());
        assertEquals(1, c.perPlayerPerDay());
        assertTrue(c.requireSurvival());
        assertEquals(java.util.Set.of(), c.handoverWorlds());
        assertEquals(7, c.termDays());
        assertEquals(2, c.renewWindowDays());
        assertEquals(4, c.maxPartners());
        assertEquals(Stage.TRUSTED, c.partnerMinimumStage());
        assertEquals(2, c.minContracts());
        assertFalse(c.rankBonusEnabled());
        assertEquals(6, c.definitions().size());
        assertEquals(3, c.offersByResident().size());
        assertEquals(List.of(), c.warnings());
        assertEquals(0, c.skippedCount());
    }

    @Test void theOffConstantIsTheSameDefaultsWithNothingLoaded() {
        ContractCatalogue off = ContractCatalogue.OFF;
        assertFalse(off.anyOn());
        assertEquals(1, off.perPlayerPerDay());
        assertTrue(off.requireSurvival());
        assertEquals(7, off.termDays());
        assertEquals(2, off.renewWindowDays());
        assertEquals(4, off.maxPartners());
        assertEquals(Stage.TRUSTED, off.partnerMinimumStage());
        assertEquals(2, off.minContracts());
        assertEquals(List.of(), off.definitions());
        assertEquals(Map.of(), off.offersByResident());
        assertEquals(PartnerPerks.NONE, off.configPerks(ELLIS));
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,true", "false,true,true", "true,true,true"})
    void theTopicShowsWhenEitherFeatureIsOn(boolean contracts, boolean partnerships, boolean any) {
        assertEquals(any, load(P + "enabled", contracts, Q + "enabled", partnerships).anyOn());
    }

    // ---- flags ---------------------------------------------------------------------------------------------

    @ParameterizedTest @ValueSource(strings = {"true", "yes", "on", "1", ""})
    void aTextValueNeverTurnsAFeatureOnAndIsWarnedAbout(String text) {
        ContractCatalogue c = load(P + "enabled", text, Q + "enabled", text);
        assertFalse(c.contractsEnabled());
        assertFalse(c.partnershipsEnabled());
        assertEquals(List.of(P + "enabled is not true or false; it stays off.", Q + "enabled is not true or false; it stays off."), c.warnings());
    }

    @Test void aNumberNeverTurnsAFeatureOn() {
        ContractCatalogue c = load(P + "enabled", 1, Q + "enabled", 1L);
        assertFalse(c.anyOn());
        assertEquals(2, c.warnings().size());
    }

    @Test void trueTurnsOnAndFalseOrAbsentStayOffWithoutAWarning() {
        assertTrue(load(P + "enabled", true).contractsEnabled());
        assertTrue(load(Q + "enabled", true).partnershipsEnabled());
        assertFalse(load(P + "enabled", false).contractsEnabled());
        assertEquals(List.of(), load(P + "enabled", false, Q + "enabled", false).warnings());
        assertEquals(List.of(), load(P + "enabled", true, Q + "enabled", true).warnings());
    }

    @Test void theRankBonusFlagFollowsTheSameRule() {
        assertTrue(load(Q + "rank-bonus-enabled", true).rankBonusEnabled());
        ContractCatalogue c = load(Q + "rank-bonus-enabled", "yes");
        assertFalse(c.rankBonusEnabled());
        assertEquals(List.of(Q + "rank-bonus-enabled is not true or false; it stays off."), c.warnings());
    }

    @Test void theSurvivalCheckStaysOnUnlessExplicitlyFalse() {
        assertTrue(load().requireSurvival());
        assertTrue(load(P + "require-survival", true).requireSurvival());
        assertFalse(load(P + "require-survival", false).requireSurvival());
        ContractCatalogue c = load(P + "require-survival", "off");
        assertTrue(c.requireSurvival());
        assertEquals(List.of(P + "require-survival is not true or false; the check stays on."), c.warnings());
        assertTrue(load(P + "require-survival", 0).requireSurvival());
    }

    // ---- limits --------------------------------------------------------------------------------------------

    @ParameterizedTest @ValueSource(ints = {1, 2, 3})
    void aDailyLimitOfOneToThreeIsKept(int perDay) {
        ContractCatalogue c = load(P + "enabled", true, P + "per-player-per-day", perDay);
        assertEquals(perDay, c.perPlayerPerDay());
        assertTrue(c.contractsEnabled());
        assertEquals(List.of(), c.warnings());
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, 4, 100, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void aDailyLimitOutsideOneToThreeHoldsContractsOffAndResetsToOne(int perDay) {
        ContractCatalogue c = load(P + "enabled", true, Q + "enabled", true, P + "per-player-per-day", perDay);
        assertEquals(1, c.perPlayerPerDay());
        assertFalse(c.contractsEnabled());
        assertTrue(c.partnershipsEnabled(), "partnerships are not held by a contracts limit");
        assertEquals(List.of(P + "per-player-per-day must be 1 to 3; contracts are held off."), c.warnings());
    }

    @Test void aDailyLimitThatIsNotAWholeNumberIsHeldToo() {
        for (Object bad : new Object[] {"2", 2.0, 2L, true, List.of(2)}) {
            ContractCatalogue c = load(P + "enabled", true, P + "per-player-per-day", bad);
            assertFalse(c.contractsEnabled(), String.valueOf(bad));
            assertEquals(1, c.warnings().size(), String.valueOf(bad));
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 7, 14, 30})
    void aTermOfOneToThirtyDaysIsKept(int days) {
        ContractCatalogue c = load(Q + "enabled", true, Q + "term-days", days, Q + "renew-window-days", 1);
        assertEquals(days, c.termDays());
        assertTrue(c.partnershipsEnabled());
    }

    @ParameterizedTest @ValueSource(ints = {0, -3, 31, 365})
    void aTermOutsideOneToThirtyHoldsPartnershipsOffAndResetsToSeven(int days) {
        ContractCatalogue c = load(Q + "enabled", true, Q + "term-days", days);
        assertEquals(7, c.termDays());
        assertFalse(c.partnershipsEnabled());
        assertEquals(List.of(Q + "term-days must be 1 to 30; partnerships are held off."), c.warnings());
    }

    @ParameterizedTest @CsvSource({"7,1", "7,7", "3,3", "30,30", "1,1"})
    void aRenewalWindowFromOneUpToTheTermIsKept(int term, int window) {
        ContractCatalogue c = load(Q + "enabled", true, Q + "term-days", term, Q + "renew-window-days", window);
        assertEquals(window, c.renewWindowDays());
        assertTrue(c.partnershipsEnabled());
    }

    @ParameterizedTest @CsvSource({"7,0", "7,8", "3,4", "7,-1", "1,2"})
    void aRenewalWindowOutsideOneToTheTermHoldsPartnershipsOffAndResetsToOne(int term, int window) {
        ContractCatalogue c = load(Q + "enabled", true, Q + "term-days", term, Q + "renew-window-days", window);
        assertEquals(1, c.renewWindowDays());
        assertFalse(c.partnershipsEnabled());
        assertEquals(List.of(Q + "renew-window-days must be 1 to term-days; partnerships are held off."), c.warnings());
    }

    @Test void theWindowIsCheckedAgainstTheTermAfterABadTermIsReset() {
        ContractCatalogue c = load(Q + "enabled", true, Q + "term-days", 31, Q + "renew-window-days", 7);
        assertEquals(7, c.termDays());
        assertEquals(7, c.renewWindowDays());
        assertEquals(List.of(Q + "term-days must be 1 to 30; partnerships are held off."), c.warnings());
        ContractCatalogue worse = load(Q + "enabled", true, Q + "term-days", 31, Q + "renew-window-days", 8);
        assertEquals(2, worse.warnings().size());
    }

    @ParameterizedTest @ValueSource(ints = {1, 4, 16})
    void aSeatLimitOfOneToSixteenIsKept(int seats) {
        ContractCatalogue c = load(Q + "enabled", true, Q + "max-partners-per-resident", seats);
        assertEquals(seats, c.maxPartners());
        assertTrue(c.partnershipsEnabled());
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, 17, 1000})
    void aSeatLimitOutsideOneToSixteenHoldsPartnershipsOffAndResetsToFour(int seats) {
        ContractCatalogue c = load(Q + "enabled", true, Q + "max-partners-per-resident", seats);
        assertEquals(4, c.maxPartners());
        assertFalse(c.partnershipsEnabled());
        assertEquals(List.of(Q + "max-partners-per-resident must be 1 to 16; partnerships are held off."), c.warnings());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 10})
    void aRequiredContractCountOfZeroToTenIsKept(int needed) {
        assertEquals(needed, load(Q + "enabled", true, Q + "min-contracts", needed).minContracts());
    }

    @ParameterizedTest @ValueSource(ints = {-1, 11, 100})
    void aRequiredContractCountOutsideZeroToTenHoldsPartnershipsOffAndResetsToTwo(int needed) {
        ContractCatalogue c = load(Q + "enabled", true, Q + "min-contracts", needed);
        assertEquals(2, c.minContracts());
        assertFalse(c.partnershipsEnabled());
        assertEquals(List.of(Q + "min-contracts must be 0 to 10; partnerships are held off."), c.warnings());
    }

    @ParameterizedTest @CsvSource({"acquaintance,ACQUAINTANCE", "ACQUAINTANCE,ACQUAINTANCE", "Trusted,TRUSTED", "established,ESTABLISHED"})
    void theMinimumStageIsReadWithoutRegardToCase(String text, Stage stage) {
        ContractCatalogue c = load(Q + "enabled", true, Q + "minimum-stage", text);
        assertEquals(stage, c.partnerMinimumStage());
        assertTrue(c.partnershipsEnabled());
    }

    @ParameterizedTest @ValueSource(strings = {"STRANGER", "stranger", "BEST_FRIEND", "", " TRUSTED", "5"})
    void aBadMinimumStageHoldsPartnershipsOffAndResetsToTrusted(String text) {
        ContractCatalogue c = load(Q + "enabled", true, Q + "minimum-stage", text);
        assertEquals(Stage.TRUSTED, c.partnerMinimumStage());
        assertFalse(c.partnershipsEnabled());
        assertEquals(List.of(Q + "minimum-stage must be ACQUAINTANCE, TRUSTED or ESTABLISHED; partnerships are held off."), c.warnings());
    }

    @Test void aMinimumStageThatIsNotTextIsReadByItsPrintedFormAndRefused() {
        ContractCatalogue c = load(Q + "enabled", true, Q + "minimum-stage", 5);
        assertFalse(c.partnershipsEnabled());
        assertEquals(1, c.warnings().size());
    }

    // ---- hand-over worlds ----------------------------------------------------------------------------------

    @Test void noWorldListAllowsEveryWorldAndAListAllowsOnlyItsMembers() {
        assertTrue(load().worldAllowed("anything"));
        ContractCatalogue c = load(P + "handover-worlds", List.of("world", "world_nether", "a.b/c-d"));
        assertTrue(c.worldAllowed("world_nether"));
        assertTrue(c.worldAllowed("a.b/c-d"));
        assertFalse(c.worldAllowed("World"));
        assertFalse(c.worldAllowed("world_the_end"));
        assertFalse(c.worldAllowed(""));
    }

    @Test void anEmptyWorldListIsTheSameAsNoList() {
        ContractCatalogue c = load(P + "enabled", true, P + "handover-worlds", List.of());
        assertTrue(c.worldAllowed("whatever"));
        assertTrue(c.contractsEnabled());
    }

    @Test void sixtyFourWorldsAreFineAndSixtyFiveHoldBothFeaturesOff() {
        List<String> sixtyFour = new ArrayList<>();
        for (int i = 0; i < 64; i++) sixtyFour.add("w" + i);
        ContractCatalogue ok = load(P + "enabled", true, Q + "enabled", true, P + "handover-worlds", sixtyFour);
        assertEquals(64, ok.handoverWorlds().size());
        assertTrue(ok.anyOn());
        List<String> sixtyFive = new ArrayList<>(sixtyFour);
        sixtyFive.add("w64");
        ContractCatalogue held = load(P + "enabled", true, Q + "enabled", true, P + "handover-worlds", sixtyFive);
        assertFalse(held.contractsEnabled());
        assertFalse(held.partnershipsEnabled());
        assertEquals(List.of(P + "handover-worlds must be a list of world names; contracts and partnerships are held off."), held.warnings());
    }

    @Test void aBadWorldNameOrNonListHoldsBothFeaturesOffAndLoadsNoWorlds() {
        List<Object> bad = new ArrayList<>();
        bad.add(List.of("ok", "has space"));
        bad.add(List.of("ok", 5));
        bad.add(List.of("x".repeat(65)));
        bad.add(List.of(""));
        bad.add(List.of("semi;colon"));
        bad.add("world");
        bad.add(Map.of("a", "b"));
        for (Object worlds : bad) {
            ContractCatalogue c = load(P + "enabled", true, Q + "enabled", true, P + "handover-worlds", worlds);
            assertFalse(c.contractsEnabled(), String.valueOf(worlds));
            assertFalse(c.partnershipsEnabled(), String.valueOf(worlds));
            assertEquals(java.util.Set.of(), c.handoverWorlds(), String.valueOf(worlds));
            assertEquals(1, c.warnings().size(), String.valueOf(worlds));
        }
    }

    @Test void aWorldNameOfSixtyFourCharactersIsValid() {
        assertTrue(load(P + "handover-worlds", List.of("x".repeat(64))).worldAllowed("x".repeat(64)));
    }

    @Test void theWorldSetIsImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> load(P + "handover-worlds", List.of("w")).handoverWorlds().add("x"));
    }

    // ---- contract entries ----------------------------------------------------------------------------------

    @Test void aCompleteEntryLoadsWithItsKindFromGiverAndRecipient() {
        Map<String, Object> supply = entry("supply", MARA);
        Map<String, Object> delivery = entry("delivery", MARA);
        delivery.put("recipient", ELLIS.toString());
        Map<String, Object> self = entry("self", MARA);
        self.put("recipient", MARA.toString());
        ContractCatalogue c = load(P + "definitions", List.of(supply, delivery, self));
        assertEquals(ContractDefinition.Kind.SUPPLY, c.find("supply").orElseThrow().kind());
        assertEquals(MARA, c.find("supply").orElseThrow().recipientId());
        assertEquals(ContractDefinition.Kind.DELIVER, c.find("delivery").orElseThrow().kind());
        assertEquals(ELLIS, c.find("delivery").orElseThrow().recipientId());
        assertEquals(ContractDefinition.Kind.SUPPLY, c.find("self").orElseThrow().kind());
        assertEquals(List.of("supply", "delivery", "self"), c.definitions().stream().map(ContractDefinition::id).toList());
    }

    @Test void anEntryKeepsEveryFieldItWasGiven() {
        Map<String, Object> raw = entry("wall", NICO);
        raw.put("minimum-stage", "trusted");
        raw.put("duration-minutes", 90);
        raw.put("quantity", 12);
        ContractDefinition d = load(P + "definitions", List.of(raw)).find("wall").orElseThrow();
        assertEquals("Wall stone", d.title());
        assertEquals("Bring me cobblestone.", d.description());
        assertEquals(NICO, d.giverId());
        assertEquals("COBBLESTONE", d.material());
        assertEquals(12, d.quantity());
        assertEquals(Stage.TRUSTED, d.minimumStage());
        assertEquals(90, d.durationMinutes());
    }

    @Test void theContractsOfOneGiverAreFilteredInConfigOrder() {
        ContractCatalogue c = load(P + "definitions", List.of(entry("a", MARA), entry("b", ELLIS), entry("c", MARA)));
        assertEquals(List.of("a", "c"), c.offers(MARA).stream().map(ContractDefinition::id).toList());
        assertEquals(List.of("b"), c.offers(ELLIS).stream().map(ContractDefinition::id).toList());
        assertEquals(List.of(), c.offers(NICO));
        assertTrue(c.find("missing").isEmpty());
    }

    private static String skipped(int index, String reason) {
        return P + "definitions entry " + index + " skipped: " + reason;
    }

    private static ContractCatalogue loadOne(Object entry) {
        return load(P + "definitions", List.of(entry));
    }

    @Test void anEntryThatIsNotAMapIsSkippedByNumber() {
        ContractCatalogue c = load(P + "definitions", List.of(entry("ok", MARA), "text", 5));
        assertEquals(List.of(skipped(2, "not a map"), skipped(3, "not a map")), c.warnings());
        assertEquals(2, c.skippedCount());
        assertEquals(1, c.definitions().size());
    }

    @Test void aGiverOrRecipientThatIsNotACastUuidIsSkippedWithItsField() {
        Map<String, Object> noGiver = entry("a", MARA);
        noGiver.remove("giver");
        Map<String, Object> textGiver = entry("a", MARA);
        textGiver.put("giver", "mara");
        Map<String, Object> strangerGiver = entry("a", UUID.randomUUID());
        Map<String, Object> strangerRecipient = entry("a", MARA);
        strangerRecipient.put("recipient", UUID.randomUUID().toString());
        Map<String, Object> textRecipient = entry("a", MARA);
        textRecipient.put("recipient", "not-a-uuid");
        assertEquals(List.of(skipped(1, "giver is not a resident UUID")), loadOne(noGiver).warnings());
        assertEquals(List.of(skipped(1, "giver is not a resident UUID")), loadOne(textGiver).warnings());
        assertEquals(List.of(skipped(1, "giver is not a resident on this server")), loadOne(strangerGiver).warnings());
        assertEquals(List.of(skipped(1, "recipient is not a resident on this server")), loadOne(strangerRecipient).warnings());
        assertEquals(List.of(skipped(1, "recipient is not a resident UUID")), loadOne(textRecipient).warnings());
    }

    @Test void aResidentUuidMayBeWrittenInUpperCase() {
        Map<String, Object> upper = entry("a", MARA);
        upper.put("giver", MARA.toString().toUpperCase(java.util.Locale.ROOT));
        assertEquals(MARA, loadOne(upper).find("a").orElseThrow().giverId());
    }

    @Test void aMaterialThatIsNotAnItemNameOrNotAnItemIsSkipped() {
        for (String bad : new String[] {"cobblestone", "COBBLE STONE", "1STONE", "minecraft:stone", ""}) {
            Map<String, Object> e = entry("a", MARA);
            e.put("material", bad);
            assertEquals(List.of(skipped(1, "material is not an item name")), loadOne(e).warnings(), bad);
        }
        Map<String, Object> notItem = entry("a", MARA);
        notItem.put("material", "NOT_AN_ITEM");
        assertEquals(List.of(skipped(1, "material NOT_AN_ITEM is not an item")), loadOne(notItem).warnings());
        Map<String, Object> number = entry("a", MARA);
        number.put("material", 5);
        assertEquals(List.of(skipped(1, "material is not an item name")), loadOne(number).warnings());
    }

    @Test void aPredicateThatThrowsMeansNotAnItem() {
        ContractCatalogue c = ContractCatalogue.load(Map.of(P + "definitions", List.of(entry("a", MARA)), Q + "offers", List.of())::get, CAST, name -> {
            throw new IllegalStateException("registry not ready");
        });
        assertEquals(List.of(skipped(1, "material COBBLESTONE is not an item")), c.warnings());
        assertEquals(List.of(), c.definitions());
    }

    @ParameterizedTest @CsvSource({"29", "0", "-5", "1441", "100000", "10", "9"})
    void aDurationOutsideThirtyToFourteenFortyIsSkipped(int minutes) {
        Map<String, Object> e = entry("a", MARA);
        e.put("duration-minutes", minutes);
        assertEquals(List.of(skipped(1, "duration-minutes must be 30 to 1440")), loadOne(e).warnings());
    }

    @ParameterizedTest @CsvSource({"30", "31", "1439", "1440"})
    void aDurationOfThirtyToFourteenFortyIsKept(int minutes) {
        Map<String, Object> e = entry("a", MARA);
        e.put("duration-minutes", minutes);
        assertEquals(minutes, loadOne(e).find("a").orElseThrow().durationMinutes());
    }

    @Test void aNumberThatIsNotAnIntegerIsSkippedWithItsField() {
        for (Object bad : new Object[] {"60", 60.0, 60L, true, null}) {
            Map<String, Object> minutes = entry("a", MARA);
            minutes.put("duration-minutes", bad);
            assertEquals(List.of(skipped(1, "duration-minutes is not a whole number")), loadOne(minutes).warnings(), String.valueOf(bad));
            Map<String, Object> quantity = entry("a", MARA);
            quantity.put("quantity", bad);
            assertEquals(List.of(skipped(1, "quantity is not a whole number")), loadOne(quantity).warnings(), String.valueOf(bad));
        }
    }

    @Test void anUnknownOrMissingMinimumStageIsSkipped() {
        for (Object bad : new Object[] {"BEST_FRIEND", "", 5}) {
            Map<String, Object> e = entry("a", MARA);
            e.put("minimum-stage", bad);
            assertEquals(List.of(skipped(1, "unknown minimum-stage")), loadOne(e).warnings(), String.valueOf(bad));
        }
        Map<String, Object> missing = entry("a", MARA);
        missing.remove("minimum-stage");
        assertEquals(List.of(skipped(1, "unknown minimum-stage")), loadOne(missing).warnings());
    }

    @ParameterizedTest @CsvSource({"stranger,true", "Acquaintance,true", "TRUSTED,true", "established,true", "strangers,false", "friend,false"})
    void aContractMinimumStageIsAnyStageNameWithoutRegardToCase(String stage, boolean accepted) {
        Map<String, Object> e = entry("a", MARA);
        e.put("minimum-stage", stage);
        ContractCatalogue c = loadOne(e);
        assertEquals(accepted ? 1 : 0, c.definitions().size());
        assertEquals(accepted ? List.of() : List.of(skipped(1, "unknown minimum-stage")), c.warnings());
    }

    @Test void theDefinitionsOwnRulesAreAppliedAndTheirMessageIsShown() {
        Map<String, Object> badId = entry("Bad Id", MARA);
        assertEquals(List.of(skipped(1, "Contract ID must be 1..48 of a-z 0-9 _ -")), loadOne(badId).warnings());
        Map<String, Object> zero = entry("a", MARA);
        zero.put("quantity", 0);
        assertEquals(List.of(skipped(1, "Contract quantity must be 1..256")), loadOne(zero).warnings());
        Map<String, Object> huge = entry("a", MARA);
        huge.put("quantity", 257);
        assertEquals(List.of(skipped(1, "Contract quantity must be 1..256")), loadOne(huge).warnings());
        Map<String, Object> padded = entry("a", MARA);
        padded.put("title", " Padded");
        assertEquals(List.of(skipped(1, "Invalid contract title")), loadOne(padded).warnings());
        Map<String, Object> blank = entry("a", MARA);
        blank.put("description", " ");
        assertEquals(List.of(skipped(1, "Invalid contract description")), loadOne(blank).warnings());
    }

    @Test void aMissingIdTitleOrDescriptionIsSkippedNamingTheField() {
        for (String field : new String[] {"id", "title", "description"}) {
            Map<String, Object> e = entry("a", MARA);
            e.remove(field);
            assertEquals(List.of(skipped(1, field + " is missing")), loadOne(e).warnings(), field);
            Map<String, Object> number = entry("a", MARA);
            number.put(field, 7);
            assertEquals(List.of(skipped(1, field + " is missing")), loadOne(number).warnings(), field);
        }
    }

    @Test void aSecondEntryWithTheSameIdIsSkippedByNumber() {
        ContractCatalogue c = load(P + "definitions", List.of(entry("same", MARA), entry("other", MARA), entry("same", ELLIS)));
        assertEquals(List.of(skipped(3, "duplicate id same")), c.warnings());
        assertEquals(2, c.definitions().size());
    }

    @Test void sixtyFourEntriesLoadAndEachOneAfterIsCountedWithOneWarning() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < 70; i++) list.add(entry("c" + i, MARA));
        ContractCatalogue c = load(P + "definitions", list);
        assertEquals(64, c.definitions().size());
        assertEquals(6, c.skippedCount());
        assertEquals(List.of(P + "definitions: only the first 64 contracts are loaded."), c.warnings());
    }

    @Test void sixtyFourGoodEntriesAreNotCutAtTheLimit() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < 64; i++) list.add(entry("c" + i, MARA));
        ContractCatalogue c = load(P + "definitions", list);
        assertEquals(64, c.definitions().size());
        assertEquals(0, c.skippedCount());
        assertEquals(List.of(), c.warnings());
    }

    @Test void skippedEntriesBeforeTheLimitDoNotCountAgainstIt() {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) list.add("junk");
        for (int i = 0; i < 64; i++) list.add(entry("c" + i, MARA));
        ContractCatalogue c = load(P + "definitions", list);
        assertEquals(64, c.definitions().size());
        assertEquals(10, c.skippedCount());
    }

    @Test void definitionsThatAreNotAListLoadNothingWithOneWarning() {
        for (Object bad : new Object[] {"text", Map.of("a", "b"), 5}) {
            ContractCatalogue c = load(P + "definitions", bad);
            assertEquals(List.of(), c.definitions(), String.valueOf(bad));
            assertEquals(List.of(P + "definitions is not a list; no contracts are loaded."), c.warnings(), String.valueOf(bad));
        }
    }

    @Test void anEmptyDefinitionsListLoadsNothingWithoutAWarning() {
        ContractCatalogue c = load(P + "definitions", List.of());
        assertEquals(List.of(), c.definitions());
        assertEquals(List.of(), c.warnings());
    }

    // ---- partnership offers --------------------------------------------------------------------------------

    private static String offerSkipped(int index, String reason) {
        return Q + "offers entry " + index + " skipped: " + reason;
    }

    @Test void aCompleteOfferLoadsWithItsPerksAndPitch() {
        ContractCatalogue c = load(Q + "offers", List.of(offer(ELLIS)));
        PartnerOffer o = c.partnerOffer(ELLIS).orElseThrow();
        assertEquals("IRON_INGOT", o.material());
        assertEquals(16, o.quantity());
        assertEquals(new PartnerPerks(40, 0), o.perks());
        assertEquals("Weekly iron.", o.pitch());
        assertTrue(c.partnerOffer(MARA).isEmpty());
    }

    @Test void anOfferWithNoPerkAtAllStillLoadsButGivesNoConfigPerk() {
        Map<String, Object> flat = offer(ELLIS);
        flat.put("fee-discount-percent", 0);
        ContractCatalogue c = load(Q + "enabled", true, Q + "offers", List.of(flat));
        assertTrue(c.partnerOffer(ELLIS).isPresent());
        assertEquals(PartnerPerks.NONE, c.configPerks(ELLIS));
    }

    @Test void theOfferRulesAreAppliedAndTheirMessageIsShown() {
        Map<String, Object> discount = offer(ELLIS);
        discount.put("fee-discount-percent", 61);
        Map<String, Object> rank = offer(ELLIS);
        rank.put("rank-bonus-points", 5);
        Map<String, Object> quantity = offer(ELLIS);
        quantity.put("quantity", 0);
        Map<String, Object> pitch = offer(ELLIS);
        pitch.put("pitch", "");
        assertEquals(List.of(offerSkipped(1, "Fee discount must be 0..60")), load(Q + "offers", List.of(discount)).warnings());
        assertEquals(List.of(offerSkipped(1, "Rank points must be 0..4")), load(Q + "offers", List.of(rank)).warnings());
        assertEquals(List.of(offerSkipped(1, "Offer quantity must be 1..256")), load(Q + "offers", List.of(quantity)).warnings());
        assertEquals(List.of(offerSkipped(1, "Invalid contract pitch")), load(Q + "offers", List.of(pitch)).warnings());
    }

    @Test void anOfferForAResidentOutsideTheCastOrNotAUuidIsSkipped() {
        Map<String, Object> stranger = offer(UUID.randomUUID());
        Map<String, Object> text = offer(ELLIS);
        text.put("resident", "ellis");
        assertEquals(List.of(offerSkipped(1, "resident is not a resident on this server")), load(Q + "offers", List.of(stranger)).warnings());
        assertEquals(List.of(offerSkipped(1, "resident is not a resident UUID")), load(Q + "offers", List.of(text)).warnings());
        assertEquals(List.of(offerSkipped(1, "not a map")), load(Q + "offers", List.of("text")).warnings());
    }

    @Test void aSecondOfferForOneResidentIsSkippedByNumber() {
        ContractCatalogue c = load(Q + "offers", List.of(offer(ELLIS), offer(MARA), offer(ELLIS)));
        assertEquals(List.of(offerSkipped(3, "a second offer for one resident")), c.warnings());
        assertEquals(2, c.offersByResident().size());
    }

    @Test void offersThatAreNotAListLoadNothingWithOneWarning() {
        ContractCatalogue c = load(Q + "offers", "text");
        assertEquals(Map.of(), c.offersByResident());
        assertEquals(List.of(Q + "offers is not a list; no partnership offers are loaded."), c.warnings());
    }

    @Test void skippedCountsAddUpAcrossContractsAndOffers() {
        ContractCatalogue c = load(P + "definitions", List.of("junk", entry("ok", MARA)), Q + "offers", List.of("junk", "junk", offer(ELLIS)));
        assertEquals(3, c.skippedCount());
        assertEquals(3, c.warnings().size());
    }

    // ---- what the loaded catalogue answers -----------------------------------------------------------------

    @Test void configPerksAreOnlyThereWhilePartnershipsAreOn() {
        assertEquals(PartnerPerks.NONE, load(Q + "offers", List.of(offer(ELLIS))).configPerks(ELLIS));
        assertEquals(new PartnerPerks(40, 0), load(Q + "enabled", true, Q + "offers", List.of(offer(ELLIS))).configPerks(ELLIS));
        assertEquals(PartnerPerks.NONE, load(Q + "enabled", true, Q + "offers", List.of(offer(ELLIS))).configPerks(MARA));
    }

    @Test void configPerksAreNoneWhenALimitHoldsPartnershipsOff() {
        assertEquals(PartnerPerks.NONE, load(Q + "enabled", true, Q + "term-days", 99, Q + "offers", List.of(offer(ELLIS))).configPerks(ELLIS));
    }

    @Test void rankCapsNeedBothPartnershipsAndTheRankBonusAndOnlyPositivePoints() {
        Map<String, Object> ranked = offer(NICO);
        ranked.put("fee-discount-percent", 0);
        ranked.put("rank-bonus-points", 3);
        Map<String, Object> unranked = offer(MARA);
        List<Map<String, Object>> offers = List.of(ranked, unranked);
        assertEquals(Map.of(), load(Q + "offers", offers).rankCaps());
        assertEquals(Map.of(), load(Q + "enabled", true, Q + "offers", offers).rankCaps());
        assertEquals(Map.of(), load(Q + "rank-bonus-enabled", true, Q + "offers", offers).rankCaps());
        assertEquals(Map.of(NICO, 3), load(Q + "enabled", true, Q + "rank-bonus-enabled", true, Q + "offers", offers).rankCaps());
    }

    @Test void theTermsCarryTheCataloguesLimitsAndTheOffersMaterial() {
        ContractCatalogue c = load(Q + "enabled", true, Q + "term-days", 10, Q + "renew-window-days", 3,
                Q + "max-partners-per-resident", 6, Q + "minimum-stage", "established", Q + "min-contracts", 5,
                Q + "offers", List.of(offer(ELLIS)));
        PartnerTerms terms = c.terms(c.partnerOffer(ELLIS).orElseThrow());
        assertEquals(new PartnerPerks(40, 0), terms.perks());
        assertEquals(10, terms.termDays());
        assertEquals(6, terms.maxSeats());
        assertEquals(3, terms.renewWindowDays());
        assertEquals(Stage.ESTABLISHED, terms.minimumStage());
        assertEquals(5, terms.minContracts());
        assertEquals("IRON_INGOT", terms.material());
        assertEquals(16, terms.quantity());
    }

    @Test void theLoaderRefusesMissingCollaborators() {
        assertThrows(NullPointerException.class, () -> ContractCatalogue.load(null, CAST, ITEMS));
        assertThrows(NullPointerException.class, () -> ContractCatalogue.load(key -> null, null, ITEMS));
        assertThrows(NullPointerException.class, () -> ContractCatalogue.load(key -> null, CAST, null));
    }

    @Test void theLoadedListsAndMapsAreImmutable() {
        ContractCatalogue c = load();
        assertThrows(UnsupportedOperationException.class, () -> c.definitions().clear());
        assertThrows(UnsupportedOperationException.class, () -> c.warnings().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> c.offersByResident().clear());
    }

    @Test void warningsComeInTheOrderTheKeysAreRead() {
        ContractCatalogue c = load(P + "enabled", "x", Q + "enabled", "y", P + "per-player-per-day", 9, P + "require-survival", "z",
                Q + "term-days", 99, Q + "max-partners-per-resident", 99, Q + "min-contracts", 99);
        assertEquals(List.of(P + "enabled is not true or false; it stays off.", Q + "enabled is not true or false; it stays off.",
                P + "per-player-per-day must be 1 to 3; contracts are held off.",
                P + "require-survival is not true or false; the check stays on.",
                Q + "term-days must be 1 to 30; partnerships are held off.",
                Q + "max-partners-per-resident must be 1 to 16; partnerships are held off.",
                Q + "min-contracts must be 0 to 10; partnerships are held off."), c.warnings());
    }
}
