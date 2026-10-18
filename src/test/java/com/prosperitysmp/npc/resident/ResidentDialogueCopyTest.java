package com.prosperitysmp.npc.resident;

import com.prosperitysmp.npc.property.TenantMood;
import com.prosperitysmp.npc.resident.RelationshipMemory.Kind;
import com.prosperitysmp.npc.resident.ResidentDialogue.Topic;
import com.prosperitysmp.npc.resident.ResidentProfile.Occupation;
import com.prosperitysmp.npc.resident.ResidentProfile.Preference;
import com.prosperitysmp.npc.resident.ResidentProfile.Temperament;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Every authored line, word for word, for each personality, trade, preference and variant. A wording change shows here. */
class ResidentDialogueCopyTest {
    private static final UUID RESIDENT = new UUID(0, 1);
    private static final UUID PLAYER = new UUID(0, 2);
    private static final Instant AT = Instant.parse("2026-10-14T12:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final List<Topic> CHOICES = List.of(Topic.ROUTINE, Topic.HOME, Topic.HISTORY, Topic.BUSINESS, Topic.COMMERCE, Topic.ABOUT);

    private static ResidentProfile person(Temperament temperament, Occupation occupation, Preference preference) {
        return new ResidentProfile(RESIDENT, "Mara Vale", "Vale", 1, occupation, ResidentProfile.Means.STEADY,
                ResidentProfile.LifeStage.ADULT, temperament, preference,
                new ResidentProfile.Wardrobe("resident/v1/x/everyday", "resident/v1/x/work", "resident/v1/x/leisure"));
    }

    private static ResidentProfile person(Temperament temperament) {
        return person(temperament, Occupation.COOK, Preference.QUIET_ROUTINE);
    }

    private static RelationshipMemory.State stranger() {
        return RelationshipMemory.replay(RESIDENT, PLAYER, List.of());
    }

    private static RelationshipMemory.Event event(String ref, Kind kind, int daysBack) {
        return new RelationshipMemory.Event(RESIDENT, PLAYER, ref, kind, AT.minusSeconds(86_400L * daysBack), DAY.minusDays(daysBack));
    }

    private static RelationshipMemory.State acquaintance() {
        return RelationshipMemory.replay(RESIDENT, PLAYER, List.of(event("intro:1", Kind.INTRODUCTION, 1)));
    }

    private static RelationshipMemory.State trusted() {
        return RelationshipMemory.replay(RESIDENT, PLAYER, List.of(event("repair:a", Kind.REPAIR_COMPLETED, 3),
                event("repair:b", Kind.REPAIR_COMPLETED, 2), event("repair:c", Kind.REPAIR_COMPLETED, 1)));
    }

    private static String line(ResidentProfile p, RelationshipMemory.State s, Topic topic, long time, int variant) {
        return ResidentDialogue.reply(p, s, topic, time, variant).line();
    }

    private static Stream<Arguments> table(Object[] keys, String[][] lines) {
        Stream.Builder<Arguments> out = Stream.builder();
        for (int i = 0; i < keys.length; i++)
            for (int variant = 0; variant < 2; variant++) out.add(Arguments.of(keys[i], variant, lines[i][variant]));
        return out.build();
    }

    // ---- greeting --------------------------------------------------------------------------------------------

    static Stream<Arguments> strangerGreetings() {
        return table(Temperament.values(), new String[][] {
                {"Hello. I'm Mara Vale. Settling in, or just passing through?", "I'm Mara Vale. Hello. Would you like to talk for a moment?"},
                {"I'm Mara Vale. Always good to meet a neighbor.", "Hello! I'm Mara Vale. What brings you to the neighborhood?"},
                {"Mara Vale. Good to meet you. What can I help with?", "I'm Mara Vale. What would you like to talk about?"},
                {"I'm Mara Vale. Have you found a favorite place around here yet?", "I'm Mara Vale. What brought you here today?"}});
    }

    @ParameterizedTest @MethodSource("strangerGreetings")
    void aStrangerIsGreetedInThePersonalitysVoice(Temperament temperament, int variant, String expected) {
        assertEquals(expected, line(person(temperament), stranger(), Topic.GREETING, 0, variant));
    }

    static Stream<Arguments> acquaintanceGreetings() {
        return table(Temperament.values(), new String[][] {
                {"Hello again. Is there something you'd like to talk about?", "Good to see you again. I have a moment to talk."},
                {"Good to see you again. What brings you by?", "Hello again. It's good to have someone to chat with."},
                {"We've met. What would you like to discuss?", "Hello again. What's on your mind?"},
                {"Hello again. What have you been curious about lately?", "Good to see you again. What caught your interest today?"}});
    }

    @ParameterizedTest @MethodSource("acquaintanceGreetings")
    void anAcquaintanceWithNoServiceMemoryIsGreetedAgain(Temperament temperament, int variant, String expected) {
        assertEquals(expected, line(person(temperament), acquaintance(), Topic.GREETING, 0, variant));
        assertNull(ResidentDialogue.reply(person(temperament), acquaintance(), Topic.GREETING, 0, variant).evidenceRef());
    }

    private static Stream<Arguments> memoryGreetings() {
        Stream.Builder<Arguments> out = Stream.builder();
        String[][] repair = {
                {"I noticed the repair was completed. Thank you.", "The repair is done. I appreciate you seeing it through."},
                {"Thanks for seeing that repair through. I remember who follows up.", "I'm glad you saw that repair through. Thank you for following up."},
                {"You completed the repair. I appreciate the follow-through.", "That repair is complete. Thank you for getting it done."},
                {"You saw that repair through. How did it go?", "I remember the repair you completed. Was it straightforward?"}};
        String[][] kept = {
                {"I remember you kept your word. I appreciate that.", "You followed through on that obligation. Thank you."},
                {"You kept your word last time. That matters to me.", "It was good to see you keep your word. Thank you."},
                {"You met that obligation. That's the follow-through I value.", "You kept your word. I noticed."},
                {"You kept your word on that obligation. How did it go?", "I remember you followed through. What helped you see it through?"}};
        String[][] broken = {
                {"I remember that obligation was not met. I'd rather be clear about it.", "That obligation wasn't met. I haven't forgotten."},
                {"Our last arrangement did not work out. I'd like clearer terms next time.", "I'm sorry that obligation wasn't met. I'd like to understand what happened."},
                {"That obligation was not met. We need to be clear about that.", "You didn't meet that obligation. I remember it."},
                {"That obligation wasn't met. What happened?", "I remember the obligation that fell through. What got in the way?"}};
        for (int t = 0; t < 4; t++) {
            for (int v = 0; v < 2; v++) {
                out.add(Arguments.of(Kind.REPAIR_COMPLETED, Temperament.values()[t], v, repair[t][v]));
                out.add(Arguments.of(Kind.OBLIGATION_KEPT, Temperament.values()[t], v, kept[t][v]));
                out.add(Arguments.of(Kind.OBLIGATION_BROKEN, Temperament.values()[t], v, broken[t][v]));
            }
        }
        return out.build();
    }

    @ParameterizedTest @MethodSource("memoryGreetings")
    void theLatestServiceMemorySpeaksInThePersonalitysVoiceWithItsEvidence(Kind kind, Temperament temperament, int variant, String expected) {
        var state = RelationshipMemory.replay(RESIDENT, PLAYER, List.of(event("memory:one", kind, 1)));
        var reply = ResidentDialogue.reply(person(temperament), state, Topic.GREETING, 0, variant);
        assertEquals(expected, reply.line());
        assertEquals("memory:one", reply.evidenceRef());
    }

    @ParameterizedTest @MethodSource("memoryGreetings")
    void historyRepeatsTheMemoryAndNamesTheServiceDay(Kind kind, Temperament temperament, int variant, String expected) {
        var state = RelationshipMemory.replay(RESIDENT, PLAYER, List.of(event("memory:one", kind, 1)));
        var reply = ResidentDialogue.reply(person(temperament), state, Topic.HISTORY, 0, variant);
        assertEquals(expected + " Service day: 2026-10-13.", reply.line());
        assertEquals("memory:one", reply.evidenceRef());
    }

    // ---- the other topics --------------------------------------------------------------------------------------

    static Stream<Arguments> routines() {
        Stream.Builder<Arguments> out = Stream.builder();
        // A cook starts work at tick 500: 0 is home, 1000 work, 9500 errand, 12000 leisure.
        long[] times = {0, 1_000, 9_500, 12_000};
        String[][] lines = {
                {"This is my quiet time. A short conversation is welcome.", "I'm taking some quiet time. What's on your mind?"},
                {"It is my work time now. I can stop for a moment.", "This is my work time. What did you want to talk about?"},
                {"I have a few errands on my list before I settle down.", "It's my errand time. A quick chat is welcome."},
                {"The workday is behind me. Time to enjoy the neighborhood.", "This is my time to unwind. What's on your mind?"}};
        for (int i = 0; i < 4; i++) for (int v = 0; v < 2; v++) out.add(Arguments.of(times[i], v, lines[i][v]));
        return out.build();
    }

    @ParameterizedTest @MethodSource("routines")
    void theRoutineLineFollowsTheScheduleAtThatTime(long worldTime, int variant, String expected) {
        assertEquals(expected, line(person(Temperament.WARM), stranger(), Topic.ROUTINE, worldTime, variant));
    }

    static Stream<Arguments> homes() {
        return table(Preference.values(), new String[][] {
                {"I value a place that is cared for. Small repairs should not become big problems.",
                        "I like a home where upkeep gets attention before things fall apart."},
                {"Tell me what a place costs and what is included. Clear terms come first.",
                        "I value knowing what is included before making up my mind about a home."},
                {"A dependable, quiet place suits me better than constant change.", "I like having a calm place and a routine I can count on."},
                {"A good home is also a neighborhood where you get to know people.", "I enjoy a neighborhood where familiar faces stop to say hello."}});
    }

    @ParameterizedTest @MethodSource("homes")
    void theHomeLineIsThePreferenceInWords(Preference preference, int variant, String expected) {
        assertEquals(expected, line(person(Temperament.WARM, Occupation.COOK, preference), stranger(), Topic.HOME, 0, variant));
    }

    static Stream<Arguments> abouts() {
        return table(Occupation.values(), new String[][] {
                {"I'm a restorer. I like finding what is worth keeping in an old build.",
                        "Restoration is my trade. A well-kept detail means more to me than a grand facade."},
                {"I'm a shopkeeper by trade. Clear terms make a good start to any deal.",
                        "My trade is shopkeeping. I like knowing exactly what both sides have agreed to."},
                {"I'm a gardener. I like a place with room for something to grow.",
                        "Gardening is my trade. Small changes can make a place feel quite different."},
                {"I'm a courier by trade. I enjoy getting to know a neighborhood.",
                        "My trade is courier work. Familiar streets and familiar faces appeal to me."},
                {"I'm a cook. I like the care that goes into making something well.", "Cooking is my trade. Simple things done thoughtfully suit me."},
                {"I'm a carpenter. I appreciate a good fit and a sturdy finish.", "Carpentry is my trade. Something practical can be beautiful too."}});
    }

    @ParameterizedTest @MethodSource("abouts")
    void theAboutLineIsTheTradeThenTheHousehold(Occupation occupation, int variant, String trade) {
        assertEquals(trade + " I'm part of the Vale household.",
                line(person(Temperament.WARM, occupation, Preference.QUIET_ROUTINE), stranger(), Topic.ABOUT, 0, variant));
    }

    static Stream<Arguments> fixedTopics() {
        return Stream.of(
                Arguments.of(Topic.COMMERCE, 0, "Let's check supplies and hired work against my order book."),
                Arguments.of(Topic.COMMERCE, 1, "We can look through the order book for supplies and hired work."),
                Arguments.of(Topic.TIPS, 0, "Let me think of something useful."),
                Arguments.of(Topic.TIPS, 1, "Here is something worth knowing."),
                Arguments.of(Topic.CONTRACTS, 0, "Let's look at contracts and partners."),
                Arguments.of(Topic.CONTRACTS, 1, "Here is what I have for contracts and partners."));
    }

    @ParameterizedTest @MethodSource("fixedTopics")
    void theFixedTopicsHaveTwoWordings(Topic topic, int variant, String expected) {
        assertEquals(expected, line(person(Temperament.DIRECT), stranger(), topic, 0, variant));
    }

    @Test void aStrangerOrAcquaintanceIsToldThereAreNoBusinessOffers() {
        assertEquals("I prefer to know people before discussing business. There are no offers available.",
                line(person(Temperament.WARM), stranger(), Topic.BUSINESS, 0, 0));
        assertEquals("I'm still getting to know you. There are no business offers available.",
                line(person(Temperament.WARM), stranger(), Topic.BUSINESS, 0, 1));
        assertEquals("I prefer to know people before discussing business. There are no offers available.",
                line(person(Temperament.WARM), acquaintance(), Topic.BUSINESS, 0, 0));
    }

    @Test void aTrustedFriendIsToldThereAreNoPartnershipOffersYet() {
        assertEquals("We have a good history. There are no partnership offers available just now.",
                line(person(Temperament.WARM), trusted(), Topic.BUSINESS, 0, 0));
        assertEquals("I value the history we've built. There are no business offers available.",
                line(person(Temperament.WARM), trusted(), Topic.BUSINESS, 0, 1));
    }

    @Test void withPartnershipsOnTheBusinessLinePointsAtContractsForEveryVariant() {
        for (int variant = 0; variant < 4; variant++) {
            assertEquals("I'm still getting to know you. Ask me about contracts.",
                    ResidentDialogue.reply(person(Temperament.WARM), stranger(), Topic.BUSINESS, 0, variant, null, true).line());
            assertEquals("We have a good history. Ask me about contracts and partners.",
                    ResidentDialogue.reply(person(Temperament.WARM), trusted(), Topic.BUSINESS, 0, variant, null, true).line());
        }
    }

    @Test void theBusinessHelperSplitsAtTheTrustedStage() {
        assertEquals("I'm still getting to know you. Ask me about contracts.", ResidentDialogue.businessWithPartners(stranger()));
        assertEquals("I'm still getting to know you. Ask me about contracts.", ResidentDialogue.businessWithPartners(acquaintance()));
        assertEquals("We have a good history. Ask me about contracts and partners.", ResidentDialogue.businessWithPartners(trusted()));
    }

    @Test void historyForAStrangerAndAnAcquaintanceWithoutMemoryHasItsOwnLines() {
        assertEquals("Let's start with hello. I'm glad you stopped by.", line(person(Temperament.WARM), stranger(), Topic.HISTORY, 0, 0));
        assertEquals("It's good to see you. Shall we introduce ourselves?", line(person(Temperament.WARM), stranger(), Topic.HISTORY, 0, 1));
        assertEquals("I don't have a recent service memory to recall.", line(person(Temperament.WARM), acquaintance(), Topic.HISTORY, 0, 0));
        assertEquals("There's no recent service event in my records to look back on.", line(person(Temperament.WARM), acquaintance(), Topic.HISTORY, 0, 1));
    }

    static Stream<Arguments> moods() {
        return Stream.of(
                Arguments.of(TenantMood.Band.UNHAPPY, "I'm not happy with my home right now."),
                Arguments.of(TenantMood.Band.NEUTRAL, "My home is fine. Nothing special either way."),
                Arguments.of(TenantMood.Band.CONTENT, "I'm content with my home these days."),
                Arguments.of(TenantMood.Band.HAPPY, "I'm very happy with my home right now."));
    }

    @ParameterizedTest @MethodSource("moods")
    void eachMoodBandHasOneLine(TenantMood.Band band, String expected) {
        assertEquals(expected, ResidentDialogue.moodLine(band));
    }

    @ParameterizedTest @EnumSource(Topic.class)
    void everyReplyOffersTheSameSixFollowUpTopics(Topic topic) {
        assertEquals(CHOICES, ResidentDialogue.reply(person(Temperament.WARM), stranger(), topic, 0, 0).choices());
    }

    @ParameterizedTest @EnumSource(Topic.class)
    void aReplyWithoutServiceMemoryCarriesNoEvidence(Topic topic) {
        assertNull(ResidentDialogue.reply(person(Temperament.WARM), stranger(), topic, 0, 0).evidenceRef());
    }

    @ParameterizedTest @CsvSource({"0,0", "1,1", "2,0", "3,1", "4,0", "1001,1", "2147483646,0", "2147483647,1"})
    void anyVariantUsesTheWordingOfItsParity(int variant, int wording) {
        assertEquals(line(person(Temperament.WARM), stranger(), Topic.COMMERCE, 0, wording),
                line(person(Temperament.WARM), stranger(), Topic.COMMERCE, 0, variant));
    }

    @Test void aReplyForAnotherResidentsMemoryIsRefusedWithoutCheckingTheTopic() {
        var other = RelationshipMemory.replay(new UUID(0, 99), PLAYER, List.of());
        for (Topic topic : Topic.values())
            assertThrows(IllegalArgumentException.class, () -> ResidentDialogue.reply(person(Temperament.WARM), other, topic, 0, 0), topic.name());
    }

    @Test void aNegativeVariantIsRefusedForEveryTopic() {
        for (Topic topic : Topic.values())
            assertThrows(IllegalArgumentException.class, () -> ResidentDialogue.reply(person(Temperament.WARM), stranger(), topic, 0, -1), topic.name());
    }

    @Test void theVariantCountIsTwo() {
        assertEquals(2, ResidentDialogue.VARIANT_COUNT);
    }

    @Test void theReplyChoicesAreAnImmutableCopy() {
        List<Topic> source = new java.util.ArrayList<>(List.of(Topic.HOME));
        var reply = new ResidentDialogue.Reply("line", null, source);
        source.add(Topic.TIPS);
        assertEquals(List.of(Topic.HOME), reply.choices());
        assertThrows(UnsupportedOperationException.class, () -> reply.choices().add(Topic.TIPS));
    }
}
