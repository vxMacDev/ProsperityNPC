package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.resident.RelationshipMemory.Stage;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.town.ContractCatalogue;
import com.prosperitysmp.npc.town.ContractDefinition;
import com.prosperitysmp.npc.town.PartnerOffer;
import com.prosperitysmp.npc.town.PartnerPerks;
import com.prosperitysmp.npc.town.Partnership;
import com.prosperitysmp.npc.town.TownContract;
import com.prosperitysmp.npc.town.TownContractRepository.Code;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The exact wording and time formatting of every contracts-and-partners line, and which line each result gets. */
class ContractPresentationCopyTest {
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final Instant NOON_CDT = Instant.parse("2026-10-14T17:00:00Z");
    private static final ResidentCast CAST = ResidentCast.builtIn();
    private static final ResidentProfile MARA = CAST.profiles().get(0);
    private static final ResidentProfile ELLIS = CAST.profiles().get(1);
    private static final UUID PLAYER = new UUID(0, 5);
    private static final ContractCatalogue CATALOGUE = ContractCatalogue.load(
            Map.of("town.partnerships.enabled", true)::get, CAST, name -> true);

    private static EconomyClock clock(Instant now, ZoneId zone, int resetHour) {
        return new EconomyClock(Clock.fixed(now, ZoneOffset.UTC), zone, resetHour);
    }

    private static final EconomyClock CHICAGO_NOON = clock(NOON_CDT, CHICAGO, 4);

    private static ContractDefinition supply() {
        return new ContractDefinition("mara-wall-stone", ContractDefinition.Kind.SUPPLY, "Wall stone", "Bring me cobblestone.",
                MARA.id(), MARA.id(), "COBBLESTONE", 64, Stage.STRANGER, 1440);
    }

    private static TownContract contract(Instant accepted, int minutes) {
        ContractDefinition d = new ContractDefinition("nico-coal-run", ContractDefinition.Kind.SUPPLY, "Coal run", "Coal.",
                MARA.id(), MARA.id(), "COAL", 16, Stage.STRANGER, minutes);
        return new TownContract(new UUID(0, 9), PLAYER, d, accepted, LocalDate.of(2026, 10, 14), 1,
                accepted.plusSeconds(minutes * 60L), TownContract.Status.ACTIVE, null, null, null);
    }

    private static Partnership partnership(LocalDate last) {
        return new Partnership(new UUID(0, 7), PLAYER, ELLIS.id(), LocalDate.of(2026, 10, 14), last, new PartnerPerks(40, 0),
                Partnership.Status.ACTIVE, null, null, new UUID(0, 8), 1);
    }

    @ParameterizedTest
    @CsvSource({"IRON_INGOT,Iron Ingot", "COBBLESTONE,Cobblestone", "OAK_LOG,Oak Log", "A__B,A B", "_X_,X",
            "NETHERITE_UPGRADE_SMITHING_TEMPLATE,Netherite Upgrade Smithing Template", "coal,Coal", "X,X"})
    void aMaterialNameIsTitleCasedWordByWord(String material, String shown) {
        assertEquals(shown, ContractPresentation.materialName(material));
    }

    @Test void itemsNamesTheQuantityAndTheMaterialForContractsAndOffers() {
        assertEquals("64 Cobblestone", ContractPresentation.items(supply()));
        PartnerOffer offer = new PartnerOffer(ELLIS.id(), "IRON_INGOT", 16, 40, 0, "Pitch.");
        assertEquals("16 Iron Ingot", ContractPresentation.items(offer));
    }

    @Test void aDeadlineTodayIsJustTheLocalTimeAndZone() {
        assertEquals("12:30 CDT", ContractPresentation.deadline(NOON_CDT.plusSeconds(30 * 60L), CHICAGO_NOON));
        assertEquals("12:00 CDT", ContractPresentation.deadline(NOON_CDT, CHICAGO_NOON));
        assertEquals("23:59 CDT", ContractPresentation.deadline(Instant.parse("2026-10-15T04:59:00Z"), CHICAGO_NOON));
    }

    @Test void aDeadlineOnTheNextCalendarDaySaysTomorrow() {
        assertEquals("tomorrow 00:00 CDT", ContractPresentation.deadline(Instant.parse("2026-10-15T05:00:00Z"), CHICAGO_NOON));
        assertEquals("tomorrow 12:00 CDT", ContractPresentation.deadline(NOON_CDT.plusSeconds(86_400), CHICAGO_NOON));
    }

    @Test void aDeadlineFurtherOutNamesTheDate() {
        assertEquals("Oct 16 12:00 CDT", ContractPresentation.deadline(NOON_CDT.plusSeconds(2 * 86_400L), CHICAGO_NOON));
        assertEquals("Oct 21 07:30 CDT", ContractPresentation.deadline(Instant.parse("2026-10-21T12:30:00Z"), CHICAGO_NOON));
    }

    @Test void aDeadlineUsesTheStandardTimeNameAfterTheClocksChange() {
        EconomyClock winter = clock(Instant.parse("2026-11-10T18:00:00Z"), CHICAGO, 4);
        assertEquals("12:30 CST", ContractPresentation.deadline(Instant.parse("2026-11-10T18:30:00Z"), winter));
    }

    @Test void aDeadlineInAnotherZoneIsWrittenInThatZone() {
        EconomyClock utc = clock(NOON_CDT, ZoneId.of("UTC"), 0);
        assertEquals("17:30 UTC", ContractPresentation.deadline(NOON_CDT.plusSeconds(30 * 60L), utc));
        EconomyClock tokyo = clock(NOON_CDT, ZoneId.of("Asia/Tokyo"), 4);
        assertEquals("tomorrow 02:00 JST", ContractPresentation.deadline(NOON_CDT.plusSeconds(86_400L), tokyo));
        assertEquals("11:00 JST", ContractPresentation.deadline(NOON_CDT.plusSeconds(9 * 3600L), tokyo));
    }

    @Test void theNextResetIsWrittenAsHourAmPmAndZone() {
        assertEquals("4 AM CDT", ContractPresentation.resetTime(CHICAGO_NOON, NOON_CDT));
        assertEquals("4 AM CDT", ContractPresentation.resetTime(CHICAGO_NOON, Instant.parse("2026-10-14T08:59:00Z")));
        assertEquals("4 AM CDT", ContractPresentation.resetTime(CHICAGO_NOON, Instant.parse("2026-10-14T09:00:00Z")));
    }

    @ParameterizedTest
    @CsvSource({"0,UTC,12 AM UTC", "4,UTC,4 AM UTC", "12,UTC,12 PM UTC", "13,UTC,1 PM UTC", "23,UTC,11 PM UTC",
            "4,America/Chicago,4 AM CDT", "6,Asia/Tokyo,6 AM JST"})
    void theResetIsWrittenInTheConfiguredZoneAndHour(int hour, String zone, String shown) {
        assertEquals(shown, ContractPresentation.resetTime(clock(NOON_CDT, ZoneId.of(zone), hour), NOON_CDT));
    }

    @Test void theDailyLimitLinesNameTheReset() {
        assertEquals("You've taken a contract today. Come back after the daily reset at 4 AM CDT.",
                ContractPresentation.dailyLimit(CHICAGO_NOON, NOON_CDT));
        assertEquals("You can start or renew one partnership a day. Come back after the daily reset at 4 AM CDT.",
                ContractPresentation.partnerDailyLimit(CHICAGO_NOON, NOON_CDT));
    }

    @Test void theAcceptedLineNamesTheItemsAndTheDeadline() {
        assertEquals("The contract is in your journal. Bring 16 Coal by 12:30 CDT.",
                ContractPresentation.accepted(contract(NOON_CDT, 30), CHICAGO_NOON));
        assertEquals("The contract is in your journal. Bring 16 Coal by tomorrow 12:00 CDT.",
                ContractPresentation.accepted(contract(NOON_CDT, 1440), CHICAGO_NOON));
    }

    @Test void theNotEnoughLineNamesWhatIsNeededAndWhatIsHeld() {
        assertEquals("You need 64 Cobblestone in your inventory. You have 10.", ContractPresentation.notEnough(64, "COBBLESTONE", 10));
        assertEquals("You need 1 Oak Log in your inventory. You have 0.", ContractPresentation.notEnough(1, "OAK_LOG", 0));
    }

    @Test void theHeldLinesCarryTheReferenceAndTellThePlayerNotToRepeat() {
        UUID ref = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
        assertEquals("That handover needs staff review. Do not hand it over again. Reference: 00000000-0000-0000-0000-0000000000ab",
                ContractPresentation.held(ref));
        assertEquals("Your last handover is waiting for staff review. Reference: 00000000-0000-0000-0000-0000000000ab",
                ContractPresentation.blockedByHeld(ref));
    }

    @Test void theWrongResidentLineNamesWhoGetsTheItems() {
        ContractDefinition d = new ContractDefinition("nico-coal-run", ContractDefinition.Kind.DELIVER, "Coal run", "Coal.",
                MARA.id(), ELLIS.id(), "COAL", 16, Stage.STRANGER, 180);
        assertEquals("Your contract is for Ellis Ward. Bring them 16 Coal.", ContractPresentation.wrongResident("Ellis Ward", d));
    }

    @Test void theKeptLineAgreesInNumber() {
        assertEquals("You have kept 0 contracts for me this month.", ContractPresentation.kept(0));
        assertEquals("You have kept 1 contract for me this month.", ContractPresentation.kept(1));
        assertEquals("You have kept 2 contracts for me this month.", ContractPresentation.kept(2));
        assertEquals("You have kept 11 contracts for me this month.", ContractPresentation.kept(11));
    }

    @Test void theFooterAgreesInNumberAndPromisesNoMoney() {
        assertEquals("One contract a day. You get familiarity, not money or items.", ContractPresentation.footer(1));
        assertEquals("2 contracts a day. You get familiarity, not money or items.", ContractPresentation.footer(2));
        assertEquals("3 contracts a day. You get familiarity, not money or items.", ContractPresentation.footer(3));
    }

    @Test void theMoreContractsLineAgreesInNumber() {
        assertEquals("Keep 1 more contract for me first.", ContractPresentation.moreContracts(1));
        assertEquals("Keep 2 more contracts for me first.", ContractPresentation.moreContracts(2));
        assertEquals("Keep 10 more contracts for me first.", ContractPresentation.moreContracts(10));
    }

    @Test void theSeatsAndPartnerLines() {
        assertEquals("I already have 4 partners. Try again next week.", ContractPresentation.seatsFull(4));
        assertEquals("I already have 1 partners. Try again next week.", ContractPresentation.seatsFull(1));
        assertEquals("You already partner with Ellis Ward until 2026-10-20.",
                ContractPresentation.hasOne("Ellis Ward", LocalDate.of(2026, 10, 20)));
    }

    @ParameterizedTest
    @CsvSource({"0,0,true,nothing changes for now", "0,0,false,nothing changes for now",
            "40,0,true,my fee is 40% lower for you", "40,0,false,my fee is 40% lower for you",
            "0,2,true,I tell new arrivals about your homes", "0,2,false,nothing changes for now",
            "25,3,true,my fee is 25% lower for you and I tell new arrivals about your homes",
            "25,3,false,my fee is 25% lower for you", "1,1,true,my fee is 1% lower for you and I tell new arrivals about your homes"})
    void thePerkSentenceNamesOnlyWhatDoesSomething(int pct, int rank, boolean rankLive, String sentence) {
        assertEquals(sentence, ContractPresentation.perkSentence(new PartnerPerks(pct, rank), rankLive));
    }

    @ParameterizedTest
    @CsvSource({"0,true,true,true", "0,true,false,false", "0,false,true,false", "0,false,false,false",
            "10,true,true,false", "10,true,false,false"})
    void theAdvertisingNoteIsAddedOnlyForARankOnlyOfferThatCanAddNothing(int pct, boolean rankLive, boolean top, boolean noted) {
        PartnerOffer offer = new PartnerOffer(MARA.id(), "STONE", 64, pct, pct == 0 ? 2 : 0, "Bring me stone.");
        String expected = noted ? "Bring me stone. Your advertising is already at the top." : "Bring me stone.";
        assertEquals(expected, ContractPresentation.pitch(offer, rankLive, top));
    }

    @Test void aShortLabelIsJustTheHeadAndTail() {
        assertEquals("Take: Wall stone", ContractPresentation.fit("Take: Wall stone", ""));
        assertEquals("Wall stone (64 Cobblestone)", ContractPresentation.fit("Wall stone", " (64 Cobblestone)"));
    }

    @Test void aLabelThatExactlyFillsTheLimitIsNotCut() {
        String head = "h".repeat(97);
        assertEquals(head + "end", ContractPresentation.fit(head, "end"));
        assertEquals(100, ContractPresentation.fit(head, "end").length());
    }

    @Test void aLabelOneOverTheLimitCutsTheHeadAndEndsInDots() {
        String head = "h".repeat(98);
        String fitted = ContractPresentation.fit(head, "end");
        assertEquals("h".repeat(94) + "..." + "end", fitted);
        assertEquals(100, fitted.length());
    }

    @Test void aVeryLongHeadStillFitsTheLimit() {
        String fitted = ContractPresentation.fit("x".repeat(5000), " | tail");
        assertEquals(ResidentAction.MAX_LABEL, fitted.length());
        assertTrue(fitted.endsWith("... | tail"));
    }

    @Test void theCutNeverEndsOnTheHalfOfASurrogatePair() {
        String emoji = Character.toString(0x1F600);
        String head = "a".repeat(93) + emoji + "b".repeat(10);
        String fitted = ContractPresentation.fit(head, "end");
        assertEquals("a".repeat(93) + "...end", fitted);
        assertFalse(Character.isHighSurrogate(fitted.charAt(92)));
    }

    @Test void trailingSpacesBeforeTheCutAreDropped() {
        String head = "h".repeat(90) + "    " + "z".repeat(10);
        assertEquals("h".repeat(90) + "...end", ContractPresentation.fit(head, "end"));
    }

    @Test void aKnownResidentIsNamedAndAnUnknownOneIsNot() {
        assertEquals("Mara Vale", ContractPresentation.name(MARA.id(), CAST));
        assertEquals("the named resident", ContractPresentation.name(new UUID(9, 9), CAST));
    }

    @Test void theFirstNameIsTheFirstWord() {
        assertEquals("Mara", ContractPresentation.firstName(MARA));
        ResidentProfile single = new ResidentProfile(new UUID(3, 3), "Cher", "Cher", 1, ResidentProfile.Occupation.COOK,
                ResidentProfile.Means.STEADY, ResidentProfile.LifeStage.ADULT, ResidentProfile.Temperament.WARM,
                ResidentProfile.Preference.QUIET_ROUTINE, MARA.wardrobe());
        assertEquals("Cher", ContractPresentation.firstName(single));
    }

    private static String result(Code code, Optional<TownContract> contract, Optional<Partnership> partnership, int detail) {
        return ContractPresentation.result(code, contract, partnership, detail, CATALOGUE, CAST, CHICAGO_NOON, NOON_CDT);
    }

    @Test void aSubjectlessResultKeepsItsConstantLine() {
        assertEquals(ContractPresentation.ALREADY_ACTIVE, result(Code.ALREADY_ACTIVE, Optional.empty(), Optional.empty(), 0));
        assertEquals(ContractPresentation.ACTIVE_CONFLICT, result(Code.ACTIVE_CONFLICT, Optional.empty(), Optional.empty(), 0));
        assertEquals(ContractPresentation.TOO_LOW, result(Code.RELATIONSHIP_TOO_LOW, Optional.empty(), Optional.empty(), 0));
        assertEquals(ContractPresentation.CANCELLED, result(Code.CANCELLED, Optional.empty(), Optional.empty(), 0));
        assertEquals(ContractPresentation.CANCELLED, result(Code.ALREADY_CANCELLED, Optional.empty(), Optional.empty(), 0));
        assertEquals(ContractPresentation.EXPIRED, result(Code.EXPIRED, Optional.empty(), Optional.empty(), 0));
        assertEquals(ContractPresentation.COMPLETED, result(Code.COMPLETED, Optional.empty(), Optional.empty(), 0));
        assertEquals(ContractPresentation.ALREADY_COMPLETED, result(Code.ALREADY_COMPLETED, Optional.empty(), Optional.empty(), 0));
        assertEquals(ContractPresentation.BUSY, result(Code.BUSY, Optional.empty(), Optional.empty(), 0));
        assertEquals(ContractPresentation.ITEMS_BUSY, result(Code.ITEMS_BUSY, Optional.empty(), Optional.empty(), 0));
        assertEquals(ContractPresentation.LOCKED_HOME, result(Code.PARTNER_HOME, Optional.empty(), Optional.empty(), 0));
        assertEquals(ContractPresentation.NOT_RENEWABLE, result(Code.PARTNER_NOT_RENEWABLE, Optional.empty(), Optional.empty(), 0));
        assertEquals(ContractPresentation.CHECKING, result(Code.HANDOVER_STARTED, Optional.empty(), Optional.empty(), 0));
    }

    @ParameterizedTest @EnumSource(value = Code.class, names = {"UNKNOWN_GIVER", "UNKNOWN_RECIPIENT", "TIME_REGRESSION",
            "NOT_ACTIVE", "NOT_FOUND", "RESOLVED_CONSUMED", "RESOLVED_NOT_CONSUMED", "NOT_HELD"})
    void anInternalResultSaysThereIsNothingToDo(Code code) {
        assertEquals(ContractPresentation.UNAVAILABLE, result(code, Optional.empty(), Optional.empty(), 0));
        assertEquals(ContractPresentation.UNAVAILABLE, result(code, Optional.of(contract(NOON_CDT, 30)), Optional.empty(), 0));
    }

    @Test void anAcceptedResultNeedsItsContractToNameTheDeadline() {
        assertEquals(ContractPresentation.UNAVAILABLE, result(Code.ACCEPTED, Optional.empty(), Optional.empty(), 0));
        assertEquals("The contract is in your journal. Bring 16 Coal by 12:30 CDT.",
                result(Code.ACCEPTED, Optional.of(contract(NOON_CDT, 30)), Optional.empty(), 0));
    }

    @Test void aHeldResultNeedsItsContractToNameTheReference() {
        TownContract t = contract(NOON_CDT, 30);
        assertEquals(ContractPresentation.held(t.contractId()), result(Code.HANDOVER_HELD, Optional.of(t), Optional.empty(), 0));
        assertEquals(ContractPresentation.blockedByHeld(t.contractId()), result(Code.HELD_FOR_REVIEW, Optional.of(t), Optional.empty(), 0));
        assertEquals(ContractPresentation.UNAVAILABLE, result(Code.HANDOVER_HELD, Optional.empty(), Optional.empty(), 0));
        assertEquals(ContractPresentation.UNAVAILABLE, result(Code.HELD_FOR_REVIEW, Optional.empty(), Optional.empty(), 0));
    }

    @Test void aWrongRecipientResultNamesTheRecipientFromTheCast() {
        ContractDefinition d = new ContractDefinition("nico-coal-run", ContractDefinition.Kind.DELIVER, "Coal run", "Coal.",
                MARA.id(), ELLIS.id(), "COAL", 16, Stage.STRANGER, 180);
        TownContract t = new TownContract(new UUID(0, 9), PLAYER, d, NOON_CDT, LocalDate.of(2026, 10, 14), 1,
                NOON_CDT.plusSeconds(180 * 60L), TownContract.Status.ACTIVE, null, null, null);
        assertEquals("Your contract is for Ellis Ward. Bring them 16 Coal.", result(Code.WRONG_RECIPIENT, Optional.of(t), Optional.empty(), 0));
        assertEquals(ContractPresentation.UNAVAILABLE, result(Code.WRONG_RECIPIENT, Optional.empty(), Optional.empty(), 0));
    }

    @Test void thePartnerResultsNameTheLastDayOfTheTerm() {
        Partnership p = partnership(LocalDate.of(2026, 10, 20));
        assertEquals("We're partners until 2026-10-20. Thank you.", result(Code.PARTNER_STARTED, Optional.empty(), Optional.of(p), 0));
        assertEquals("Partnership renewed until 2026-10-20.", result(Code.PARTNER_RENEWED, Optional.empty(), Optional.of(p), 0));
        assertEquals(ContractPresentation.COMPLETED, result(Code.PARTNER_STARTED, Optional.empty(), Optional.empty(), 0));
        assertEquals(ContractPresentation.COMPLETED, result(Code.PARTNER_RENEWED, Optional.empty(), Optional.empty(), 0));
    }

    @Test void anExistingPartnershipIsNamedFromTheCast() {
        Partnership p = partnership(LocalDate.of(2026, 10, 20));
        assertEquals("You already partner with Ellis Ward until 2026-10-20.",
                result(Code.PARTNER_HAS_ONE, Optional.empty(), Optional.of(p), 0));
        assertEquals(ContractPresentation.UNAVAILABLE, result(Code.PARTNER_HAS_ONE, Optional.empty(), Optional.empty(), 0));
    }

    @Test void thePartnerStageAndSeatLinesUseTheCataloguesLimits() {
        assertEquals("Partnerships open at Trusted.", result(Code.PARTNER_STAGE, Optional.empty(), Optional.empty(), 0));
        assertEquals("I already have 4 partners. Try again next week.", result(Code.PARTNER_SEATS_FULL, Optional.empty(), Optional.empty(), 0));
    }

    @Test void theContractsStillNeededIsAtLeastOneWhateverTheDetail() {
        assertEquals("Keep 2 more contracts for me first.", result(Code.PARTNER_CONTRACTS, Optional.empty(), Optional.empty(), 2));
        assertEquals("Keep 1 more contract for me first.", result(Code.PARTNER_CONTRACTS, Optional.empty(), Optional.empty(), 0));
        assertEquals("Keep 1 more contract for me first.", result(Code.PARTNER_CONTRACTS, Optional.empty(), Optional.empty(), -3));
    }

    @Test void theDailyLimitResultsNameTheReset() {
        assertEquals(ContractPresentation.dailyLimit(CHICAGO_NOON, NOON_CDT), result(Code.DAILY_LIMIT, Optional.empty(), Optional.empty(), 0));
        assertEquals(ContractPresentation.partnerDailyLimit(CHICAGO_NOON, NOON_CDT),
                result(Code.PARTNER_DAILY_LIMIT, Optional.empty(), Optional.empty(), 0));
    }

    @Test void everyCodeHasALine() {
        for (Code code : Code.values()) {
            String line = result(code, Optional.empty(), Optional.empty(), 1);
            assertNotNull(line, code.name());
            assertFalse(line.isBlank(), code.name());
            assertTrue(line.length() <= 200, code.name());
        }
    }

    @Test void noResultLineMentionsMoneyOrAPrice() {
        for (Code code : Code.values()) {
            String line = result(code, Optional.of(contract(NOON_CDT, 30)), Optional.of(partnership(LocalDate.of(2026, 10, 20))), 1);
            assertFalse(line.contains("$"), code.name() + ": " + line);
        }
    }

    @Test void theCatalogueFixtureIsTheShippedOne() {
        assertEquals(Stage.TRUSTED, CATALOGUE.partnerMinimumStage());
        assertEquals(4, CATALOGUE.maxPartners());
    }
}
