package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentDialogue.Topic;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.town.TownTask;
import com.prosperitysmp.npc.town.TownTaskCatalogue;
import com.prosperitysmp.npc.town.TownTaskDefinition;
import com.prosperitysmp.npc.town.TownTaskRepository.Code;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The errand offer: the exact lines for each state, which offers a visitor can take, and every result wording. */
class TownOfferPresentationCopyTest {
    private static final UUID PLAYER = new UUID(0, 70);
    private static final Instant NOW = Instant.parse("2026-09-29T15:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);
    private static final List<ResidentProfile> CAST = ResidentCatalogue.betaCast();
    private static final ResidentProfile MARA = CAST.get(0);
    private static final ResidentProfile ELLIS = CAST.get(1);
    private static final ResidentProfile NICO = CAST.get(2);
    private static final Set<UUID> EVERYONE = Set.of(MARA.id(), ELLIS.id(), NICO.id());

    private static RelationshipMemory.State stranger(ResidentProfile resident) {
        return RelationshipMemory.replay(resident.id(), PLAYER, List.of());
    }

    private static RelationshipMemory.State trusted(ResidentProfile resident) {
        List<RelationshipMemory.Event> events = new ArrayList<>();
        for (int i = 3; i >= 1; i--)
            events.add(new RelationshipMemory.Event(resident.id(), PLAYER, "repair:r" + i, RelationshipMemory.Kind.REPAIR_COMPLETED,
                    NOW.minusSeconds(86_400L * i), DAY.minusDays(i)));
        RelationshipMemory.State state = RelationshipMemory.replay(resident.id(), PLAYER, events);
        assertEquals(RelationshipMemory.Stage.TRUSTED, state.stage());
        return state;
    }

    private static EncounterView base() {
        return new EncounterView("n", "o", "f", "Hello.", List.of(Topic.BUSINESS));
    }

    private static TownTask task(String id, TownTask.Status status, Instant closed, LocalDate completed, LocalDate acceptedDay) {
        TownTaskDefinition definition = TownTaskCatalogue.find(id).orElseThrow();
        return new TownTask(UUID.randomUUID(), PLAYER, definition, NOW, acceptedDay, NOW.plusSeconds(10_800), status, closed, completed);
    }

    private static TownTask active() {
        return task("mara-material-list", TownTask.Status.ACTIVE, null, null, DAY);
    }

    @Test void aStrangerIsOfferedOnlyTheFirstNoTrustErrandWithTheTrustedHint() {
        EncounterView v = TownOfferPresentation.view(base(), MARA, stranger(MARA), Optional.empty(), List.of(), DAY, EVERYONE);
        assertEquals("Take my sealed materials list to Ellis Ward. One virtual note per service day; 3h to deliver. "
                + "Trusted friends unlock specialist introductions. Builds familiarity, not cash or items.", v.line());
        assertEquals(1, v.actions().size());
        ResidentAction accept = v.actions().get(0);
        assertEquals(ResidentAction.Kind.ACCEPT, accept.kind());
        assertEquals("mara-material-list", accept.definitionId());
        assertEquals(ELLIS.id(), accept.recipientId());
        assertEquals("Take: Restoration materials", accept.label());
        assertNull(accept.taskId());
    }

    @Test void aTrustedVisitorSeesBothErrandsAndNoUnlockHint() {
        EncounterView v = TownOfferPresentation.view(base(), MARA, trusted(MARA), Optional.empty(), List.of(), DAY, EVERYONE);
        assertEquals(List.of("mara-material-list", "mara-restoration-plan"),
                v.actions().stream().map(ResidentAction::definitionId).toList());
        assertEquals(List.of("Take: Restoration materials", "Take: A trusted restoration contact"),
                v.actions().stream().map(ResidentAction::label).toList());
        assertEquals("Take my sealed materials list to Ellis Ward. One virtual note per service day; 3h to deliver. "
                + "Builds familiarity, not cash or items.", v.line());
    }

    @Test void anErrandWhoseRecipientIsOutOfReachIsNotOfferedAndTheOtherStillIs() {
        EncounterView v = TownOfferPresentation.view(base(), MARA, trusted(MARA), Optional.empty(), List.of(), DAY, Set.of(NICO.id()));
        assertEquals(List.of("mara-restoration-plan"), v.actions().stream().map(ResidentAction::definitionId).toList());
        assertTrue(v.line().startsWith("I trust your follow-through. Take this restoration plan to Nico Reed."));
    }

    @Test void withNothingReachableTheLineSaysSoAndKeepsTheUnlockHintForAStranger() {
        EncounterView v = TownOfferPresentation.view(base(), MARA, stranger(MARA), Optional.empty(), List.of(), DAY, Set.of());
        assertEquals("I have no reachable errand to offer right now. Trusted friends unlock specialist introductions. "
                + "Builds familiarity, not cash or items.", v.line());
        assertEquals(List.of(), v.actions());
    }

    @Test void withNothingReachableTheLineSaysSoWithoutTheHintForATrustedVisitor() {
        EncounterView v = TownOfferPresentation.view(base(), MARA, trusted(MARA), Optional.empty(), List.of(), DAY, Set.of());
        assertEquals("I have no reachable errand to offer right now. Builds familiarity, not cash or items.", v.line());
    }

    @Test void aResidentWhoGivesNoErrandsSaysThereIsNoReachableOne() {
        ResidentCast onlyMara = new ResidentCast(List.of(MARA), Map.of(), ResidentCast.Source.FILE);
        EncounterView v = TownOfferPresentation.view(base(), MARA, stranger(MARA), Optional.empty(), List.of(), DAY,
                Set.of(NICO.id(), ELLIS.id()), onlyMara);
        assertEquals("I have no reachable errand to offer right now. Builds familiarity, not cash or items.", v.line());
        assertEquals(List.of(), v.actions());
    }

    @Test void anErrandWhosePartiesAreNotInTheCastIsHidden() {
        ResidentCast maraAndNico = new ResidentCast(List.of(MARA, NICO), Map.of(), ResidentCast.Source.FILE);
        EncounterView v = TownOfferPresentation.view(base(), MARA, stranger(MARA), Optional.empty(), List.of(), DAY,
                EVERYONE, maraAndNico);
        assertEquals(List.of(), v.actions());
        assertEquals("I have no reachable errand to offer right now. Trusted friends unlock specialist introductions. "
                + "Builds familiarity, not cash or items.", v.line());
    }

    @Test void theRecipientOfAnActiveErrandCanReceiveItAndFindOrCancel() {
        TownTask t = active();
        EncounterView v = TownOfferPresentation.view(base(), ELLIS, stranger(ELLIS), Optional.of(t), List.of(t), DAY, Set.of());
        assertEquals("You have a sealed note for me: Restoration materials. Ready to hand it over? Until 18:00 UTC.", v.line());
        assertEquals(List.of(ResidentAction.Kind.COMPLETE, ResidentAction.Kind.DIRECTIONS, ResidentAction.Kind.CANCEL),
                v.actions().stream().map(ResidentAction::kind).toList());
        assertEquals(List.of("Deliver sealed note", "Find Ellis Ward", "Cancel errand (today's slot stays used)"),
                v.actions().stream().map(ResidentAction::label).toList());
        assertTrue(v.actions().stream().allMatch(a -> a.taskId().equals(t.taskId())));
        assertTrue(v.actions().stream().allMatch(a -> a.recipientId().equals(ELLIS.id())));
    }

    @Test void anyoneElseJustGetsTheDirectionsAndTheCancelButton() {
        TownTask t = active();
        EncounterView v = TownOfferPresentation.view(base(), MARA, stranger(MARA), Optional.of(t), List.of(t), DAY, EVERYONE);
        assertEquals("Your current errand is Restoration materials. Deliver it to Ellis Ward. Until 18:00 UTC.", v.line());
        assertEquals(List.of(ResidentAction.Kind.DIRECTIONS, ResidentAction.Kind.CANCEL),
                v.actions().stream().map(ResidentAction::kind).toList());
        assertEquals("Find Ellis Ward", v.actions().get(0).label());
    }

    @Test void anErrandTakenTodayBlocksAnotherOnePerServiceDay() {
        TownTask done = task("mara-material-list", TownTask.Status.COMPLETED, NOW.plusSeconds(600), DAY, DAY);
        EncounterView v = TownOfferPresentation.view(base(), MARA, stranger(MARA), Optional.empty(), List.of(done), DAY, EVERYONE);
        assertEquals("You've taken an errand this service day. Next slot: 4 AM reset. Cancelled or expired notes cost no trust.", v.line());
        assertEquals(List.of(), v.actions());
    }

    @Test void anErrandTakenYesterdayDoesNotBlockToday() {
        TownTask old = task("mara-material-list", TownTask.Status.COMPLETED, NOW.plusSeconds(600), DAY.minusDays(1), DAY.minusDays(1));
        EncounterView v = TownOfferPresentation.view(base(), MARA, stranger(MARA), Optional.empty(), List.of(old), DAY, EVERYONE);
        assertEquals(1, v.actions().size());
        assertEquals(ResidentAction.Kind.ACCEPT, v.actions().get(0).kind());
    }

    @Test void anErrandAcceptedOnALaterServiceDayStillCountsAsTodays() {
        TownTask future = task("mara-material-list", TownTask.Status.CANCELLED, NOW.plusSeconds(600), null, DAY.plusDays(1));
        EncounterView v = TownOfferPresentation.view(base(), MARA, stranger(MARA), Optional.empty(), List.of(future), DAY, EVERYONE);
        assertEquals(List.of(), v.actions());
    }

    @Test void theViewKeepsTheBaseNameOccupationFamiliarityAndTopics() {
        EncounterView v = TownOfferPresentation.view(base(), MARA, stranger(MARA), Optional.empty(), List.of(), DAY, EVERYONE);
        assertEquals("n", v.name());
        assertEquals("o", v.occupation());
        assertEquals("f", v.familiarity());
        assertEquals(List.of(Topic.BUSINESS), v.topics());
    }

    static Stream<Arguments> answers() {
        String other = "This errand is unavailable. Nothing new was granted.";
        return Stream.of(
                Arguments.of(Code.ACCEPTED, "The sealed note is in your errand journal."),
                Arguments.of(Code.ALREADY_ACTIVE, "You already carry this note. Your original deadline is unchanged."),
                Arguments.of(Code.COMPLETED, "Delivered. We both remember your follow-through."),
                Arguments.of(Code.ALREADY_COMPLETED, "That note was already delivered; it has not earned another memory."),
                Arguments.of(Code.CANCELLED, "The errand is cancelled. No trust is lost, but today's slot remains used."),
                Arguments.of(Code.ALREADY_CANCELLED, "The errand is cancelled. No trust is lost, but today's slot remains used."),
                Arguments.of(Code.EXPIRED, "The note has expired. No trust is lost; ask for a new errand on another service day."),
                Arguments.of(Code.DAILY_LIMIT, "You've already taken an errand this service day. Come back after the next 4 AM reset."),
                Arguments.of(Code.ACTIVE_CONFLICT, "Finish or cancel your current errand before taking another."),
                Arguments.of(Code.RELATIONSHIP_TOO_LOW, "That introduction needs a Trusted relationship first."),
                Arguments.of(Code.WRONG_RECIPIENT, "That note is addressed to someone else."),
                Arguments.of(Code.UNKNOWN_GIVER, other),
                Arguments.of(Code.UNKNOWN_RECIPIENT, other),
                Arguments.of(Code.TIME_REGRESSION, other),
                Arguments.of(Code.NOT_ACTIVE, other),
                Arguments.of(Code.NOT_FOUND, other));
    }

    @ParameterizedTest @MethodSource("answers")
    void everyCodeHasItsExactAnswer(Code code, String text) {
        assertEquals(text, TownOfferPresentation.result(code));
    }

    @Test void theAnswerTableCoversEveryCode() {
        assertEquals(Code.values().length, answers().count());
    }

    private static String words(int count) {
        return String.join(" ", java.util.Collections.nCopies(count, "w".repeat(48)));
    }

    @Test void aReplyJoinsTheResultAndItsContextWhileTheyFitSixLines() {
        String context = words(5);
        assertEquals("Done. " + context, TownOfferPresentation.replyLine("Done.", context));
        assertEquals(6, ResidentSpeech.wrap("Done. " + context).size());
    }

    @Test void aReplyDropsTheContextWhenTogetherTheyWouldNeedSevenLines() {
        String context = words(6);
        assertEquals(7, ResidentSpeech.wrap("Done. " + context).size());
        assertEquals("Done.", TownOfferPresentation.replyLine("Done.", context));
    }

    @Test void aReplyWithShortContextIsJoinedWithOneSpace() {
        assertEquals("Delivered. Hello.", TownOfferPresentation.replyLine("Delivered.", "Hello."));
    }
}
