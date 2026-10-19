package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.resident.ResidentDialogue.Topic;
import com.prosperitysmp.npc.town.TownTask;
import com.prosperitysmp.npc.town.TownTaskCatalogue;
import com.prosperitysmp.npc.training.TrainingGrant;
import com.prosperitysmp.npc.training.TrainingRuntime;
import com.prosperitysmp.npc.training.TrainingRuntime.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The edges of the tips topic: how tips are cleaned, how a day picks one, and when the status line speaks. */
class TipsPresentationBoundaryTest {
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);
    private static final String EM_DASH = Character.toString(0x2014);
    private static final String EN_DASH = Character.toString(0x2013);
    private static final TrainingRuntime.View NONE = new TrainingRuntime.View(Status.NONE, Optional.empty());

    private static TownTask errand(TownTask.Status status, Instant expires) {
        var def = TownTaskCatalogue.find("mara-material-list").orElseThrow();
        Instant accepted = expires.minusSeconds(def.durationMinutes() * 60L);
        Instant closed = status == TownTask.Status.ACTIVE ? null : accepted.plusSeconds(60);
        LocalDate completed = status == TownTask.Status.COMPLETED ? DAY : null;
        return new TownTask(UUID.randomUUID(), UUID.randomUUID(), def, accepted, DAY, expires, status, closed, completed);
    }

    private static TrainingRuntime.View training(Status status, Instant expires) {
        var grant = new TrainingGrant(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "builder", UUID.randomUUID(),
                expires.minusSeconds(900), DAY, expires, 500, 1000);
        return new TrainingRuntime.View(status, Optional.of(grant));
    }

    @Test void theLimitsAreTwentyFourTipsOfOneHundredSixtyCharacters() {
        assertEquals(24, TipsPresentation.MAX_TIPS);
        assertEquals(160, TipsPresentation.MAX_LENGTH);
        assertEquals("Nothing on your plate right now.", TipsPresentation.IDLE);
        assertEquals("I can't check your training just now.", TipsPresentation.TRAINING_UNCHECKED);
    }

    @Test void aTipOfExactlyTheLimitIsKeptWholeAndALongerOneIsCutToIt() {
        assertEquals("a".repeat(160), TipsPresentation.clean(List.of("a".repeat(160))).get(0));
        assertEquals("a".repeat(160), TipsPresentation.clean(List.of("a".repeat(161))).get(0));
        assertEquals("a".repeat(160), TipsPresentation.clean(List.of("a".repeat(1000))).get(0));
    }

    @Test void aCutThatLeavesTrailingSpacesDropsThem() {
        String tip = "a".repeat(159) + " " + "b".repeat(20);
        assertEquals("a".repeat(159), TipsPresentation.clean(List.of(tip)).get(0));
        String insideTheCut = "a".repeat(158) + " " + "b".repeat(20);
        assertEquals("a".repeat(158) + " b", TipsPresentation.clean(List.of(insideTheCut)).get(0));
    }

    @Test void bothKindsOfLongDashBecomeAHyphenAndHyphensAreKept() {
        assertEquals("Vote - daily - always - ok", TipsPresentation.clean(List.of("Vote " + EM_DASH + " daily " + EN_DASH + " always - ok")).get(0));
    }

    @Test void anyRunOfWhitespaceBecomesOneSpaceAndEdgesAreTrimmed() {
        assertEquals("a b c", TipsPresentation.clean(List.of("  a   b\tc\n")).get(0));
        assertEquals("one two", TipsPresentation.clean(List.of("\n\none\r\n\r\ntwo\t")).get(0));
    }

    @Test void entriesThatAreNotTextOrAreBlankAreDroppedAndTheRestKeepTheirOrder() {
        List<?> raw = Arrays.asList("first", 5, null, "  ", "", 7.5, List.of("nested"), "second");
        assertEquals(List.of("first", "second"), TipsPresentation.clean(raw));
    }

    @Test void blankEntriesDoNotCountAgainstTheTwentyFourTipLimit() {
        List<String> raw = new ArrayList<>();
        for (int i = 0; i < 30; i++) raw.add(" ");
        for (int i = 0; i < 30; i++) raw.add("tip " + i);
        List<String> cleaned = TipsPresentation.clean(raw);
        assertEquals(24, cleaned.size());
        assertEquals("tip 0", cleaned.get(0));
        assertEquals("tip 23", cleaned.get(23));
    }

    @Test void theLimitIsAppliedAfterCleaningSoTwentyFourShortTipsAllSurvive() {
        List<String> raw = new ArrayList<>();
        for (int i = 0; i < 24; i++) raw.add("tip " + i);
        assertEquals(raw, TipsPresentation.clean(raw));
    }

    @Test void duplicateTipsAreKeptAsGiven() {
        assertEquals(List.of("same", "same"), TipsPresentation.clean(List.of("same", " same ")));
    }

    @Test void cleaningNothingGivesAnImmutableEmptyList() {
        assertEquals(List.of(), TipsPresentation.clean(null));
        assertThrows(UnsupportedOperationException.class, () -> TipsPresentation.clean(List.of("a")).add("b"));
    }

    @Test void aSingleTipIsEveryDaysTip() {
        for (int d = 0; d < 10; d++)
            assertEquals(Optional.of("only"), TipsPresentation.tip(List.of("only"), new UUID(d, d), DAY.plusDays(d)));
    }

    @Test void aMissingOrEmptyTipListHasNoTip() {
        assertEquals(Optional.empty(), TipsPresentation.tip(null, UUID.randomUUID(), DAY));
        assertEquals(Optional.empty(), TipsPresentation.tip(List.of(), UUID.randomUUID(), DAY));
    }

    @ParameterizedTest @ValueSource(ints = {2, 3, 4, 5, 7, 12, 24})
    void eachNewDayMovesToTheNextTipInTheListAndWrapsAround(int size) {
        List<String> tips = new ArrayList<>();
        for (int i = 0; i < size; i++) tips.add("tip-" + i);
        Random random = new Random(20261013L + size);
        for (int p = 0; p < 40; p++) {
            UUID player = new UUID(random.nextLong(), random.nextLong());
            for (LocalDate day : new LocalDate[] {DAY, LocalDate.of(1969, 12, 25), LocalDate.of(2100, 2, 27)}) {
                int index = tips.indexOf(TipsPresentation.tip(tips, player, day).orElseThrow());
                int next = tips.indexOf(TipsPresentation.tip(tips, player, day.plusDays(1)).orElseThrow());
                assertEquals((index + 1) % size, next, player + " " + day);
            }
        }
    }

    @Test void differentPlayersStartOnDifferentTipsOnTheSameDay() {
        List<String> tips = List.of("a", "b", "c", "d");
        Set<String> seen = new HashSet<>();
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) seen.add(TipsPresentation.tip(tips, new UUID(random.nextLong(), random.nextLong()), DAY).orElseThrow());
        assertEquals(Set.of("a", "b", "c", "d"), seen);
    }

    @Test void aPlayersTipDoesNotChangeBetweenReadsOfTheSameDay() {
        List<String> tips = List.of("a", "b", "c");
        UUID player = new UUID(123, 456);
        String first = TipsPresentation.tip(tips, player, DAY).orElseThrow();
        for (int i = 0; i < 20; i++) assertEquals(first, TipsPresentation.tip(tips, player, DAY).orElseThrow());
    }

    @Test void anErrandThatEndsExactlyNowIsNotCarried() {
        assertEquals(TipsPresentation.IDLE, TipsPresentation.status(Optional.of(errand(TownTask.Status.ACTIVE, NOW)), NONE, NOW));
        assertTrue(TipsPresentation.status(Optional.of(errand(TownTask.Status.ACTIVE, NOW.plusMillis(1))), NONE, NOW)
                .startsWith("You are carrying a note for Ellis Ward."));
    }

    @ParameterizedTest @EnumSource(value = TownTask.Status.class, names = {"COMPLETED", "CANCELLED", "EXPIRED"})
    void aFinishedErrandIsNotCarried(TownTask.Status status) {
        Instant expires = NOW.plusSeconds(600);
        Instant closed = status == TownTask.Status.EXPIRED ? expires : expires.minusSeconds(300);
        var def = TownTaskCatalogue.find("mara-material-list").orElseThrow();
        var task = new TownTask(UUID.randomUUID(), UUID.randomUUID(), def, expires.minusSeconds(def.durationMinutes() * 60L), DAY,
                expires, status, closed, status == TownTask.Status.COMPLETED ? DAY : null);
        assertEquals(TipsPresentation.IDLE, TipsPresentation.status(Optional.of(task), NONE, NOW));
    }

    @Test void theErrandLineSendsThePlayerToBusinessForDirections() {
        assertEquals("You are carrying a note for Ellis Ward. Deliver it to them; ask about Business for directions.",
                TipsPresentation.status(Optional.of(errand(TownTask.Status.ACTIVE, NOW.plusSeconds(600))), NONE, NOW));
    }

    @ParameterizedTest
    @CsvSource({"1,1", "30,1", "59,1", "60,1", "61,2", "119,2", "120,2", "121,3", "1199,20", "1200,20"})
    void theTrainingMinutesLeftRoundUp(int secondsLeft, int minutes) {
        var active = training(Status.ACTIVE, NOW.plusSeconds(secondsLeft));
        assertEquals("Your builder training has " + minutes + " min left.", TipsPresentation.status(Optional.empty(), active, NOW));
    }

    @Test void trainingThatEndsExactlyNowIsNotMentioned() {
        assertEquals(TipsPresentation.IDLE, TipsPresentation.status(Optional.empty(), training(Status.ACTIVE, NOW), NOW));
        assertEquals("Your builder training has 1 min left.",
                TipsPresentation.status(Optional.empty(), training(Status.ACTIVE, NOW.plusMillis(1)), NOW));
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"NONE", "EXPIRED", "INELIGIBLE", "DISABLED", "UNAVAILABLE"})
    void aTrainingStatusThatIsNotActiveAddsNothingAndIsNotAnUncheckedWarning(Status status) {
        assertEquals(TipsPresentation.IDLE, TipsPresentation.status(Optional.empty(), training(status, NOW.plusSeconds(600)), NOW));
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"UNKNOWN", "LOADING", "UNCERTAIN"})
    void anUnresolvedTrainingStatusIsAnUncheckedWarningEvenWithAGrantOnHand(Status status) {
        assertEquals(TipsPresentation.TRAINING_UNCHECKED, TipsPresentation.status(Optional.empty(), training(status, NOW.plusSeconds(600)), NOW));
    }

    @Test void anErrandAndTrainingAreJoinedWithOneSpaceErrandFirst() {
        String both = TipsPresentation.status(Optional.of(errand(TownTask.Status.ACTIVE, NOW.plusSeconds(600))),
                training(Status.ACTIVE, NOW.plusSeconds(90)), NOW);
        assertEquals("You are carrying a note for Ellis Ward. Deliver it to them; ask about Business for directions. "
                + "Your builder training has 2 min left.", both);
    }

    private static EncounterView base() {
        return new EncounterView("Mara Vale", "Restorer", "Stranger", "Original line.", List.of(Topic.BUSINESS),
                List.of(new ResidentAction(ResidentAction.Kind.CANCEL, null, UUID.randomUUID(), new UUID(0, 1), "Cancel")));
    }

    @Test void theViewReplacesTheLineKeepsTheTopicsAndDropsTheActions() {
        EncounterView v = TipsPresentation.view(base(), List.of("Vote daily."), new UUID(1, 2), DAY, Optional.empty(), NONE, NOW);
        assertEquals("Tip: Vote daily. Nothing on your plate right now.", v.line());
        assertEquals(List.of(Topic.BUSINESS), v.topics());
        assertEquals(List.of(), v.actions());
        assertEquals("Mara Vale", v.name());
        assertEquals("Restorer", v.occupation());
    }

    @Test void withoutTipsTheViewIsJustTheStatus() {
        EncounterView v = TipsPresentation.view(base(), List.of(), new UUID(1, 2), DAY, Optional.empty(), NONE, NOW);
        assertEquals(TipsPresentation.IDLE, v.line());
        assertEquals(TipsPresentation.IDLE, TipsPresentation.view(base(), null, new UUID(1, 2), DAY, Optional.empty(), NONE, NOW).line());
    }

    @Test void aTipThatKeepsTheWholeLineWithinSixLinesIsShown() {
        String tip = String.join(" ", java.util.Collections.nCopies(4, "w".repeat(48)));
        EncounterView v = TipsPresentation.view(base(), List.of(tip), new UUID(1, 2), DAY, Optional.empty(), NONE, NOW);
        assertEquals("Tip: " + tip + " " + TipsPresentation.IDLE, v.line());
        assertEquals(6, ResidentSpeech.wrap(v.line()).size());
    }

    @Test void aTipThatWouldPushTheLineToSevenLinesIsLeftOutAndTheStatusSurvives() {
        String tip = String.join(" ", java.util.Collections.nCopies(5, "w".repeat(48)));
        EncounterView v = TipsPresentation.view(base(), List.of(tip), new UUID(1, 2), DAY, Optional.empty(), NONE, NOW);
        assertEquals(TipsPresentation.IDLE, v.line());
    }

    @Test void theTopicIsAppendedLastAndOnlyOnce() {
        EncounterView v = TipsPresentation.withTopic(base(), true);
        assertEquals(List.of(Topic.BUSINESS, Topic.TIPS), v.topics());
        assertEquals(base().actions().size(), v.actions().size());
        assertSame(v, TipsPresentation.withTopic(v, true));
        assertEquals(List.of(Topic.BUSINESS), TipsPresentation.withTopic(base(), false).topics());
    }
}
