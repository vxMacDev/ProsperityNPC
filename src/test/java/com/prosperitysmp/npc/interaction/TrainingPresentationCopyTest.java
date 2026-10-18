package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.resident.ResidentProfile.Occupation;
import com.prosperitysmp.npc.resident.ResidentDialogue.Topic;
import com.prosperitysmp.npc.training.TrainingGrant;
import com.prosperitysmp.npc.training.TrainingRepository;
import com.prosperitysmp.npc.training.TrainingRepository.Code;
import com.prosperitysmp.npc.training.TrainingRuntime;
import com.prosperitysmp.npc.training.TrainingRuntime.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The mentor's offer: which trades each occupation teaches and the exact wording of every training answer. */
class TrainingPresentationCopyTest {
    private static final Instant AT = Instant.parse("2026-10-14T15:05:00Z");

    private static ResidentProfile resident(Occupation occupation) {
        return new ResidentProfile(new UUID(0, 1), "Mara Vale", "Vale", 1, occupation, ResidentProfile.Means.STEADY,
                ResidentProfile.LifeStage.ADULT, ResidentProfile.Temperament.WARM, ResidentProfile.Preference.QUIET_ROUTINE,
                ResidentCatalogue.betaCast().get(0).wardrobe());
    }

    private static EncounterView base() {
        return new EncounterView("Mara Vale", "Restorer", "Stranger", "How can I help?", List.of(Topic.BUSINESS));
    }

    private static TrainingRuntime.View view(Status status) {
        return new TrainingRuntime.View(status, Optional.empty());
    }

    @ParameterizedTest
    @CsvSource({"RESTORER,builder woodcutter", "CARPENTER,builder woodcutter", "GARDENER,farmer woodcutter",
            "COOK,farmer fisherman", "SHOPKEEPER,", "COURIER,"})
    void eachOccupationTeachesItsOwnTrades(Occupation occupation, String trades) {
        List<String> expected = trades == null ? List.of() : List.of(trades.split(" "));
        assertEquals(expected, TrainingPresentation.trades(occupation));
    }

    private static final String CANNOT_CONFIRM = "I cannot confirm that practice session right now.";
    private static final String UNAVAILABLE = "Training is unavailable right now. No new benefit is active.";

    static Stream<Arguments> answers() {
        return Stream.of(
                Arguments.of(Code.GRANTED, "Practice agreed: +5% Jobs pay and +10% XP for 30 minutes. One session per service day."),
                Arguments.of(Code.ALREADY_ACTIVE, "Your existing practice session is still active. Its original deadline is unchanged."),
                Arguments.of(Code.ACTIVE_CONFLICT, "You already have another practice session running."),
                Arguments.of(Code.DAILY_LIMIT, "You've used today's practice session. Return after the 4 AM service reset."),
                Arguments.of(Code.RELATIONSHIP_TOO_LOW, "Introduce yourself first. Practice needs an Acquaintance relationship."),
                Arguments.of(Code.NOT_EMPLOYED, "You need to be employed in that job before we can practice."),
                Arguments.of(Code.DISABLED, UNAVAILABLE),
                Arguments.of(Code.UNAVAILABLE, UNAVAILABLE),
                Arguments.of(Code.UNCERTAIN, "Training confirmation is unresolved. Benefits are held; ask again to check stored state."),
                Arguments.of(Code.UNAUTHORIZED, CANNOT_CONFIRM),
                Arguments.of(Code.UNKNOWN_TRAINER, CANNOT_CONFIRM),
                Arguments.of(Code.TIME_REGRESSION, CANNOT_CONFIRM),
                Arguments.of(Code.BUSY, CANNOT_CONFIRM));
    }

    @ParameterizedTest @MethodSource("answers")
    void everyCodeHasItsExactAnswer(Code code, String text) {
        assertEquals(text, TrainingPresentation.result(TrainingRepository.Result.denied(code)));
    }

    @Test void everyCodeIsCoveredByTheAnswerTable() {
        assertEquals(13, Code.values().length);
    }

    @Test void aResidentWhoTeachesNothingLeavesTheViewUntouched() {
        EncounterView v = base();
        assertSame(v, TrainingPresentation.add(v, resident(Occupation.SHOPKEEPER), Set.of("farmer", "builder"), view(Status.NONE)));
        assertSame(v, TrainingPresentation.add(v, resident(Occupation.COURIER), Set.of("farmer", "builder"), view(Status.NONE)));
    }

    @Test void aMissingTrainingStatusLeavesTheViewUntouched() {
        EncounterView v = base();
        assertSame(v, TrainingPresentation.add(v, resident(Occupation.COOK), Set.of("farmer"), null));
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"DISABLED", "UNAVAILABLE", "UNCERTAIN", "INELIGIBLE"})
    void aStatusThatCannotTrainLeavesTheViewUntouched(Status status) {
        EncounterView v = base();
        assertSame(v, TrainingPresentation.add(v, resident(Occupation.COOK), Set.of("farmer"), view(status)));
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"UNKNOWN", "LOADING"})
    void anUnresolvedStatusAsksTheVisitorToReopenAndOffersNothing(Status status) {
        EncounterView v = TrainingPresentation.add(base(), resident(Occupation.COOK), Set.of("farmer"), view(status));
        assertEquals("Checking your saved training. Reopen Business in a moment. How can I help?", v.line());
        assertEquals(List.of(), v.actions());
        assertEquals(base().topics(), v.topics());
    }

    private static TrainingGrant grant(String job, Instant accepted) {
        return new TrainingGrant(UUID.randomUUID(), new UUID(0, 2), new UUID(0, 1), job, UUID.randomUUID(), accepted,
                LocalDate.of(2026, 10, 14), accepted.plusSeconds(1800), 500, 1000);
    }

    @Test void anActiveSessionShowsItsJobAndItsUtcEndTime() {
        var active = new TrainingRuntime.View(Status.ACTIVE, Optional.of(grant("Farmer", Instant.parse("2026-10-14T15:00:00Z"))));
        EncounterView v = TrainingPresentation.add(base(), resident(Occupation.COOK), Set.of("farmer", "fisherman"), active);
        assertEquals("Your farmer training lasts until 15:30 UTC. How can I help?", v.line());
        assertEquals(List.of(), v.actions());
    }

    @Test void theEndTimeIsPaddedToTwoDigits() {
        var active = new TrainingRuntime.View(Status.ACTIVE, Optional.of(grant("miner", Instant.parse("2026-10-14T03:04:00Z"))));
        EncounterView v = TrainingPresentation.add(base(), resident(Occupation.COOK), Set.of(), active);
        assertEquals("Your miner training lasts until 03:34 UTC. How can I help?", v.line());
    }

    @Test void anActiveSessionKeepsTheExistingActionsAndTopics() {
        ResidentAction cancel = new ResidentAction(ResidentAction.Kind.CANCEL, null, UUID.randomUUID(), new UUID(0, 1), "Cancel errand");
        EncounterView with = new EncounterView("n", "o", "f", "How can I help?", List.of(Topic.HOME), List.of(cancel));
        var active = new TrainingRuntime.View(Status.ACTIVE, Optional.of(grant("farmer", AT)));
        EncounterView v = TrainingPresentation.add(with, resident(Occupation.COOK), Set.of("farmer"), active);
        assertEquals(List.of(cancel), v.actions());
        assertEquals(List.of(Topic.HOME), v.topics());
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"NONE", "EXPIRED"})
    void aVisitorWhoCanTrainGetsOneButtonPerEmployedTradeInTradeOrder(Status status) {
        EncounterView v = TrainingPresentation.add(base(), resident(Occupation.COOK), Set.of("fisherman", "farmer"), view(status));
        assertEquals(List.of("farmer", "fisherman"), v.actions().stream().map(ResidentAction::definitionId).toList());
        assertTrue(v.actions().stream().allMatch(a -> a.kind() == ResidentAction.Kind.TRAIN));
        assertTrue(v.actions().stream().allMatch(a -> new UUID(0, 1).equals(a.recipientId())));
        assertEquals("Train farmer: +5% pay, +10% XP / 30m", v.actions().get(0).label());
        assertEquals("Train fisherman: +5% pay, +10% XP / 30m", v.actions().get(1).label());
        assertEquals(base().line(), v.line());
    }

    @Test void onlyTradesTheVisitorIsEmployedInAreOffered() {
        EncounterView v = TrainingPresentation.add(base(), resident(Occupation.COOK), Set.of("farmer", "miner"), view(Status.NONE));
        assertEquals(List.of("farmer"), v.actions().stream().map(ResidentAction::definitionId).toList());
    }

    @Test void aVisitorEmployedInNoTaughtTradeGetsNoButton() {
        EncounterView v = TrainingPresentation.add(base(), resident(Occupation.COOK), Set.of("miner", "hunter"), view(Status.NONE));
        assertEquals(List.of(), v.actions());
    }

    @Test void trainButtonsAreAddedAfterExistingActionsAndNeverBeyondThree() {
        List<ResidentAction> existing = new ArrayList<>();
        for (String id : List.of("one", "two"))
            existing.add(new ResidentAction(ResidentAction.Kind.ACCEPT, id, null, new UUID(0, 1), "Errand " + id));
        EncounterView two = new EncounterView("n", "o", "f", "l", List.of(), existing);
        EncounterView v = TrainingPresentation.add(two, resident(Occupation.COOK), Set.of("farmer", "fisherman"), view(Status.NONE));
        assertEquals(3, v.actions().size());
        assertEquals(List.of("one", "two", "farmer"), v.actions().stream().map(ResidentAction::definitionId).toList());
    }

    @Test void withoutAReservedSlotThreeErrandsLeaveNoRoomForTraining() {
        List<ResidentAction> three = new ArrayList<>();
        for (String id : List.of("one", "two", "three"))
            three.add(new ResidentAction(ResidentAction.Kind.ACCEPT, id, null, new UUID(0, 1), "Errand " + id));
        EncounterView full = new EncounterView("n", "o", "f", "l", List.of(), three);
        EncounterView v = TrainingPresentation.add(full, resident(Occupation.COOK), Set.of("farmer"), view(Status.NONE), false);
        assertEquals(three, v.actions());
    }

    @Test void reservingASlotDropsErrandsFromTheEndUntilTrainFits() {
        List<ResidentAction> three = new ArrayList<>();
        for (String id : List.of("one", "two", "three"))
            three.add(new ResidentAction(ResidentAction.Kind.ACCEPT, id, null, new UUID(0, 1), "Errand " + id));
        EncounterView full = new EncounterView("n", "o", "f", "l", List.of(), three);
        EncounterView v = TrainingPresentation.add(full, resident(Occupation.COOK), Set.of("farmer", "fisherman"), view(Status.NONE), true);
        assertEquals(List.of("one", "two", "farmer"), v.actions().stream().map(ResidentAction::definitionId).toList());
    }

    @Test void reservingASlotChangesNothingWhileThereIsRoomAlready() {
        ResidentAction one = new ResidentAction(ResidentAction.Kind.ACCEPT, "one", null, new UUID(0, 1), "Errand one");
        EncounterView partly = new EncounterView("n", "o", "f", "l", List.of(), List.of(one));
        EncounterView v = TrainingPresentation.add(partly, resident(Occupation.COOK), Set.of("farmer"), view(Status.NONE), true);
        assertEquals(List.of("one", "farmer"), v.actions().stream().map(ResidentAction::definitionId).toList());
    }

    @Test void theResultLineAlwaysFitsSixLinesOfPrivateSpeech() {
        for (Code code : Code.values()) {
            String line = TrainingPresentation.result(TrainingRepository.Result.denied(code));
            assertTrue(ResidentSpeech.wrap(line).size() <= 6, code.name());
            assertFalse(ResidentSpeech.wrap(line).isEmpty(), code.name());
        }
    }
}
