package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentActivity;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentDialogue;
import com.prosperitysmp.npc.resident.ResidentDialogue.Topic;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.resident.ResidentRoutine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** What an encounter shows: topic labels, line wrapping, and how the mood, notice and live activity sentences stack. */
class EncounterViewCopyTest {
    private static final UUID RESIDENT = new UUID(0, 1);
    private static final ResidentActivity WALKING = new ResidentActivity(ResidentActivity.State.WALKING, ResidentRoutine.Activity.WORK);

    private static EncounterView view(String line, List<Topic> topics) {
        return new EncounterView("Mara Vale", "Restorer", "Stranger", line, topics);
    }

    private static ResidentAction action(ResidentAction.Kind kind, String label) {
        return new ResidentAction(kind, null, UUID.randomUUID(), RESIDENT, label);
    }

    @ParameterizedTest
    @CsvSource({"GREETING,Say hello", "ROUTINE,How is your day?", "HOME,What makes a good home?",
            "BUSINESS,Business and partnerships", "COMMERCE,Supplies and hired work", "HISTORY,Our history",
            "ABOUT,Tell me about yourself", "TIPS,Any tips?", "CONTRACTS,Contracts and partners"})
    void everyTopicHasItsButtonLabel(Topic topic, String label) {
        assertEquals(label, EncounterView.topicLabel(topic));
    }

    @ParameterizedTest @EnumSource(Topic.class)
    void noTopicLabelIsLongerThanAButtonAllows(Topic topic) {
        assertTrue(EncounterView.topicLabel(topic).length() <= ResidentAction.MAX_LABEL);
    }

    @ParameterizedTest
    @CsvSource({"STRANGER,Stranger", "ACQUAINTANCE,Acquaintance", "OLDER_ADULT,Older adult", "x,X", "A_B_C,A b c",
            "ALREADY lower_Mixed,Already lower mixed"})
    void labelTurnsAKeyIntoSentenceCase(String key, String label) {
        assertEquals(label, EncounterView.label(key));
    }

    @Test void wrapBreaksOnWordBoundariesWithinTheWidth() {
        assertEquals(List.of("aaaa bbbb", "cccc"), EncounterView.wrap("aaaa bbbb cccc", 9));
        assertEquals(List.of("aaaa", "bbbb", "cccc"), EncounterView.wrap("aaaa bbbb cccc", 8));
    }

    @Test void aLineMayFillTheWidthExactly() {
        assertEquals(List.of("abcdefgh"), EncounterView.wrap("abcdefgh", 8));
        assertEquals(List.of("abc defg"), EncounterView.wrap("abc defg", 8));
        assertEquals(List.of("abc", "defgh"), EncounterView.wrap("abc defgh", 8));
    }

    @Test void aWordLongerThanTheWidthIsCutIntoWidthSizedPieces() {
        assertEquals(List.of("abcdefgh", "ijkl"), EncounterView.wrap("abcdefghijkl", 8));
        assertEquals(List.of("abcdefgh", "ijklmnop"), EncounterView.wrap("abcdefghijklmnop", 8));
        assertEquals(List.of("ab", "cdefghij", "klmnop"), EncounterView.wrap("ab cdefghijklmnop", 8));
    }

    @Test void wrapCollapsesAnyWhitespaceBetweenWords() {
        assertEquals(List.of("one two three"), EncounterView.wrap("one  two\tthree", 40));
        assertEquals(List.of("one two"), EncounterView.wrap("one\ntwo", 40));
        assertEquals(List.of("a b"), EncounterView.wrap(" a   b", 40));
    }

    @Test void wrapOfNothingIsEmpty() {
        assertEquals(List.of(), EncounterView.wrap("", 20));
        assertEquals(List.of(), EncounterView.wrap("   ", 20));
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 7, 81, 1000, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void wrapRefusesAWidthOutsideEightToEighty(int width) {
        assertThrows(IllegalArgumentException.class, () -> EncounterView.wrap("text", width));
    }

    @ParameterizedTest @ValueSource(ints = {8, 9, 38, 42, 79, 80})
    void wrapAcceptsEveryWidthFromEightToEighty(int width) {
        assertEquals(List.of("text"), EncounterView.wrap("text", width));
    }

    @Test void wrapRefusesNullText() {
        assertThrows(NullPointerException.class, () -> EncounterView.wrap(null, 20));
    }

    @Test void wrapReturnsAnImmutableList() {
        assertThrows(UnsupportedOperationException.class, () -> EncounterView.wrap("a b", 20).add("c"));
    }

    @Test void noWrappedLineEverExceedsTheWidth() {
        String text = "The quick brown fox jumps over the lazy dog while an extraordinarilylongwordthatdoesnotfitanywhere sits nearby";
        for (int width : new int[] {8, 12, 20, 38, 42}) {
            for (String line : EncounterView.wrap(text, width)) assertTrue(line.length() <= width, width + ": " + line);
        }
    }

    @Test void wrappingNeverLosesACharacterOfTheWords() {
        String text = "The quick brown fox jumps over the lazy dog while an extraordinarilylongwordthatdoesnotfitanywhere sits nearby";
        String joined = String.join("", EncounterView.wrap(text, 12));
        assertEquals(text.replace(" ", ""), joined.replace(" ", ""));
    }

    @Test void moreThanThreeActionsAreRefused() {
        List<ResidentAction> four = new ArrayList<>();
        for (int i = 0; i < 4; i++) four.add(action(ResidentAction.Kind.CANCEL, "Cancel " + i));
        assertThrows(IllegalArgumentException.class, () -> new EncounterView("n", "o", "f", "l", List.of(), four));
        assertDoesNotThrow(() -> new EncounterView("n", "o", "f", "l", List.of(), four.subList(0, 3)));
    }

    @Test void theTopicAndActionListsAreImmutableCopies() {
        List<Topic> topics = new ArrayList<>(List.of(Topic.HOME));
        List<ResidentAction> actions = new ArrayList<>(List.of(action(ResidentAction.Kind.CANCEL, "Cancel")));
        EncounterView v = new EncounterView("n", "o", "f", "l", topics, actions);
        topics.add(Topic.TIPS);
        actions.clear();
        assertEquals(List.of(Topic.HOME), v.topics());
        assertEquals(1, v.actions().size());
        assertThrows(UnsupportedOperationException.class, () -> v.topics().add(Topic.TIPS));
        assertThrows(UnsupportedOperationException.class, () -> v.actions().clear());
    }

    @Test void theShortConstructorsLeaveActionsAndMoodEmpty() {
        EncounterView v = view("line", List.of(Topic.HOME));
        assertEquals(List.of(), v.actions());
        assertNull(v.moodSuffix());
        assertNull(new EncounterView("n", "o", "f", "l", List.of(), List.of()).moodSuffix());
    }

    @Test void ofBuildsTheViewFromTheProfileRelationshipAndReply() {
        ResidentProfile mara = ResidentCatalogue.betaCast().get(0);
        UUID player = new UUID(0, 2);
        RelationshipMemory.State stranger = RelationshipMemory.replay(mara.id(), player, List.of());
        ResidentDialogue.Reply reply = ResidentDialogue.reply(mara, stranger, Topic.ABOUT, 0);
        EncounterView v = EncounterView.of(mara, stranger, reply);
        assertEquals("Mara Vale", v.name());
        assertEquals("Restorer", v.occupation());
        assertEquals("Stranger", v.familiarity());
        assertEquals(reply.line(), v.line());
        assertEquals(reply.choices(), v.topics());
        assertEquals(List.of(), v.actions());
    }

    @Test void withActivityReplacesTheLineOnlyForTheRoutineTopic() {
        EncounterView v = view("old line", List.of(Topic.ROUTINE));
        assertEquals(WALKING.line(), v.withActivity(Topic.ROUTINE, WALKING).line());
        assertSame(v, v.withActivity(Topic.HOME, WALKING));
        assertSame(v, v.withActivity(Topic.ROUTINE, null));
        assertSame(v, v.withActivity(Topic.GREETING, WALKING));
    }

    @Test void withActivityKeepsTheOtherFields() {
        EncounterView v = view("old line", List.of(Topic.ROUTINE, Topic.HOME));
        EncounterView changed = v.withActivity(Topic.ROUTINE, WALKING);
        assertEquals(v.name(), changed.name());
        assertEquals(v.occupation(), changed.occupation());
        assertEquals(v.familiarity(), changed.familiarity());
        assertEquals(v.topics(), changed.topics());
        assertEquals(v.actions(), changed.actions());
    }

    @Test void withMoodAppendsOneSentenceAndRemembersIt() {
        EncounterView v = view("Line.", List.of()).withMood("I'm content.");
        assertEquals("Line. I'm content.", v.line());
        assertEquals("I'm content.", v.moodSuffix());
    }

    @Test void aNullOrBlankMoodChangesNothing() {
        EncounterView v = view("Line.", List.of());
        assertSame(v, v.withMood(null));
        assertSame(v, v.withMood(""));
        assertSame(v, v.withMood("   "));
    }

    @Test void theMoodSentenceSurvivesTheLiveActivityOverride() {
        EncounterView v = view("Line.", List.of(Topic.ROUTINE)).withMood("I'm content.");
        assertEquals(WALKING.line() + " I'm content.", v.withActivity(Topic.ROUTINE, WALKING).line());
    }

    @Test void aNoticeIsAppendedAndKeepsTheMoodSuffixUnchanged() {
        EncounterView v = view("Line.", List.of()).withMood("Mood.").withNotice("Notice.");
        assertEquals("Line. Mood. Notice.", v.line());
        assertEquals("Mood.", v.moodSuffix());
    }

    @Test void aNullOrBlankNoticeChangesNothing() {
        EncounterView v = view("Line.", List.of());
        assertSame(v, v.withNotice(null));
        assertSame(v, v.withNotice(" "));
    }

    @Test void aNoticeAppliedAfterTheActivityOverrideIsNotDropped() {
        EncounterView v = view("Line.", List.of(Topic.ROUTINE)).withMood("Mood.")
                .withActivity(Topic.ROUTINE, WALKING).withNotice("Notice.");
        assertEquals(WALKING.line() + " Mood. Notice.", v.line());
    }

    @Test void aNoticeAppliedBeforeTheActivityOverrideIsReplacedWithTheLine() {
        EncounterView v = view("Line.", List.of(Topic.ROUTINE)).withNotice("Notice.").withActivity(Topic.ROUTINE, WALKING);
        assertEquals(WALKING.line(), v.line());
    }

    @Test void aConfirmationHasNoTopicsAndAConfirmFirstAction() {
        ResidentAction confirm = new ResidentAction(ResidentAction.Kind.PARTNER_CONFIRM, "partner-start", UUID.randomUUID(), RESIDENT, "Confirm");
        ResidentAction end = new ResidentAction(ResidentAction.Kind.PARTNER_END_CONFIRM, null, UUID.randomUUID(), RESIDENT, "Confirm");
        ResidentAction back = new ResidentAction(ResidentAction.Kind.CONTRACT_BACK, null, null, RESIDENT, "Back");
        assertTrue(new EncounterView("n", "o", "f", "l", List.of(), List.of(confirm, back)).confirmation());
        assertTrue(new EncounterView("n", "o", "f", "l", List.of(), List.of(end, back)).confirmation());
    }

    @Test void aViewIsNotAConfirmationWithTopicsNoActionsOrAnotherFirstAction() {
        ResidentAction confirm = new ResidentAction(ResidentAction.Kind.PARTNER_CONFIRM, "partner-start", UUID.randomUUID(), RESIDENT, "Confirm");
        ResidentAction back = new ResidentAction(ResidentAction.Kind.CONTRACT_BACK, null, null, RESIDENT, "Back");
        assertFalse(new EncounterView("n", "o", "f", "l", List.of(Topic.HOME), List.of(confirm)).confirmation());
        assertFalse(new EncounterView("n", "o", "f", "l", List.of(), List.of()).confirmation());
        assertFalse(new EncounterView("n", "o", "f", "l", List.of(), List.of(back, confirm)).confirmation());
        assertFalse(new EncounterView("n", "o", "f", "l", List.of(),
                List.of(action(ResidentAction.Kind.CANCEL, "Cancel"))).confirmation());
    }
}
