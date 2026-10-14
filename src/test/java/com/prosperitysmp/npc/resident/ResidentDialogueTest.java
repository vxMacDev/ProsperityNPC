package com.prosperitysmp.npc.resident;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.prosperitysmp.npc.resident.RelationshipMemory.Kind;
import static com.prosperitysmp.npc.resident.ResidentDialogue.Topic;
import static com.prosperitysmp.npc.resident.ResidentProfile.Preference;
import static com.prosperitysmp.npc.resident.ResidentProfile.Temperament;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResidentDialogueTest {
    private static final UUID RESIDENT = new UUID(0, 1);
    private static final UUID PLAYER = new UUID(0, 2);
    private static final Instant AT = Instant.parse("2026-09-06T12:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);
    private static final List<Topic> CHOICES = List.of(Topic.ROUTINE, Topic.HOME, Topic.HISTORY, Topic.BUSINESS, Topic.COMMERCE, Topic.ABOUT);

    @Test
    void backgroundUsesTheStoredHouseholdWithoutInventingServiceEvidence() {
        var person = person(Temperament.WARM);
        var relationship = state();
        var reply = ResidentDialogue.reply(person, relationship, Topic.ABOUT, 0);
        assertTrue(reply.line().contains(person.household()));
        assertNull(reply.evidenceRef());
        assertTrue(reply.choices().contains(Topic.ABOUT));
    }

    @ParameterizedTest
    @EnumSource(Topic.class)
    void existingSignatureDelegatesToVariantZero(Topic topic) {
        for (Temperament temperament : Temperament.values()) {
            var person = person(temperament);
            for (var state : contexts()) {
                assertEquals(ResidentDialogue.reply(person, state, topic, 6000, 0),
                        ResidentDialogue.reply(person, state, topic, 6000));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void negativeVariantFailsClosed(int variant) {
        for (Topic topic : Topic.values()) {
            assertThrows(IllegalArgumentException.class,
                    () -> ResidentDialogue.reply(person(Temperament.WARM), state(), topic, 6000, variant));
        }
    }

    @Test
    void everyBranchHasTwoDeterministicAlternativesAndLargeVariantsWrap() {
        assertEquals(2, ResidentDialogue.VARIANT_COUNT, "a small authored set, not generated phrases");
        for (Temperament temperament : Temperament.values()) {
            for (Preference preference : Preference.values()) {
                var person = person(temperament, preference, "Mara Vale");
                for (var state : contexts()) {
                    for (Topic topic : Topic.values()) {
                        for (long time : new long[]{0, 6000, 9500, 12000}) {
                            var first = ResidentDialogue.reply(person, state, topic, time, 0);
                            var second = ResidentDialogue.reply(person, state, topic, time, 1);
                            assertFalse(first.line().equals(second.line()), temperament + " " + preference + " " + topic);
                            assertEquals(first.evidenceRef(), second.evidenceRef(), "wording cannot select a different fact");
                            for (int variant : new int[]{0, 1, 2, 3, 4, 5, 6, 1024, Integer.MAX_VALUE}) {
                                assertEquals(variant % 2 == 0 ? first : second,
                                        ResidentDialogue.reply(person, state, topic, time, variant));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void sixStepSessionCounterChangesWordingAtWraparound() {
        assertEquals(0, 6 % ResidentDialogue.VARIANT_COUNT);
        var person = person(Temperament.WARM);
        var introduced = state(event("introduction", Kind.INTRODUCTION, 0));
        for (Topic topic : Topic.values()) {
            for (int variant = 0; variant < 6; variant++) {
                var current = ResidentDialogue.reply(person, introduced, topic, 6000, variant);
                var next = ResidentDialogue.reply(person, introduced, topic, 6000, (variant + 1) % 6);
                assertFalse(current.line().equals(next.line()), "session boundary " + variant + " for " + topic);
            }
        }
    }

    @Test
    void personalityPersistsAfterIntroductionWithoutInventingAchievements() {
        var stranger = state();
        var introduced = state(event("introduction", Kind.INTRODUCTION, 0));
        for (int variant = 0; variant < ResidentDialogue.VARIANT_COUNT; variant++) {
            Set<String> familiarVoices = new HashSet<>();
            Set<String> firstVoices = new HashSet<>();
            for (Temperament temperament : Temperament.values()) {
                var person = person(temperament);
                var first = ResidentDialogue.reply(person, stranger, Topic.GREETING, 6000, variant);
                var familiar = ResidentDialogue.reply(person, introduced, Topic.GREETING, 6000, variant);
                assertTrue(first.line().contains(person.name()));
                assertFalse(first.line().equals(familiar.line()));
                for (var reply : List.of(first, familiar)) {
                    assertNull(reply.evidenceRef());
                    String line = reply.line().toLowerCase(Locale.ROOT);
                    for (String unsupported : List.of("repair", "obligation", "kept your word", "followed through", "thanks")) {
                        assertFalse(line.contains(unsupported), reply.line());
                    }
                }
                firstVoices.add(first.line());
                familiarVoices.add(familiar.line());
            }
            assertEquals(Temperament.values().length, firstVoices.size());
            assertEquals(Temperament.values().length, familiarVoices.size(), "temperament survives the first introduction");
        }
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"REPAIR_COMPLETED", "OBLIGATION_KEPT", "OBLIGATION_BROKEN"})
    void memoryCallbacksHaveDistinctVoicesButPreserveExactEvidence(Kind kind) {
        String source = "evidence/" + "x".repeat(151);
        var evidence = event(source, kind, 0);
        var state = state(evidence);
        for (int variant = 0; variant < ResidentDialogue.VARIANT_COUNT; variant++) {
            Set<String> voices = new HashSet<>();
            for (Temperament temperament : Temperament.values()) {
                var reply = ResidentDialogue.reply(person(temperament), state, Topic.GREETING, 6000, variant);
                assertEquals(source, reply.evidenceRef());
                assertFalse(reply.line().contains(source), "source is evidence metadata, not spoken player text");
                String line = reply.line().toLowerCase(Locale.ROOT);
                switch (kind) {
                    case REPAIR_COMPLETED -> assertTrue(line.contains("repair"));
                    case OBLIGATION_KEPT -> assertTrue(line.contains("word") || line.contains("obligation")
                            || line.contains("followed through"));
                    case OBLIGATION_BROKEN -> {
                        assertFalse(line.contains("thank") || line.contains("repair") || line.contains("kept your word"));
                        assertTrue(line.contains("not") || line.contains("wasn't") || line.contains("didn't")
                                || line.contains("fell through"));
                    }
                    case INTRODUCTION -> throw new AssertionError("not an outcome callback");
                }
                voices.add(reply.line());
            }
            assertEquals(Temperament.values().length, voices.size(), kind + " retains temperament");
        }
        assertEquals(List.of(evidence), state.recent(), "dialogue must not rewrite or normalize stored evidence");
        assertEquals(state(evidence), state, "variants must not change reliability or relationship stage");
    }

    @Test
    void variantsCannotReplaceLatestFailureWithOlderPraiseEvenWithUnorderedRecentList() {
        var repair = event("repair/old", Kind.REPAIR_COMPLETED, 0);
        var broken = event("obligation/latest", Kind.OBLIGATION_BROKEN, 1);
        var introduction = event("introduction", Kind.INTRODUCTION, 2);
        var replayed = state(repair, introduction, broken);
        var unordered = new RelationshipMemory.State(RESIDENT, PLAYER, replayed.stage(), replayed.reliability(),
                replayed.distinctPositiveDays(), List.of(repair, introduction, broken));
        for (Temperament temperament : Temperament.values()) {
            for (int variant : new int[]{0, 1, 2, Integer.MAX_VALUE}) {
                var reply = ResidentDialogue.reply(person(temperament), unordered, Topic.GREETING, 6000, variant);
                assertEquals(broken.sourceRef(), reply.evidenceRef());
                assertFalse(reply.line().toLowerCase(Locale.ROOT).contains("thank"));
                assertEquals(ResidentDialogue.reply(person(temperament), replayed, Topic.GREETING, 6000, variant), reply);
            }
        }
    }

    @Test
    void equalTimestampEvidenceUsesReplayTieBreakNotVariantOrInputOrder() {
        var kept = event("source:a", Kind.OBLIGATION_KEPT, 0);
        var broken = event("source:z", Kind.OBLIGATION_BROKEN, 0);
        var replayed = state(broken, kept);
        for (int variant = 0; variant < 4; variant++) {
            assertEquals("source:z", ResidentDialogue.reply(person(Temperament.WARM), replayed,
                    Topic.GREETING, 6000, variant).evidenceRef());
        }
    }

    @Test
    void routineAlternativesReflectScheduleNotInventedWorkOrRewards() {
        var person = person(Temperament.DIRECT);
        for (long time : new long[]{Long.MIN_VALUE, -1, 0, 1999, 2000, 6000, 9500, 12000, 16000, Long.MAX_VALUE}) {
            for (int variant = 0; variant < ResidentDialogue.VARIANT_COUNT; variant++) {
                var reply = ResidentDialogue.reply(person, state(), Topic.ROUTINE, time, variant);
                String line = reply.line().toLowerCase(Locale.ROOT);
                switch (ResidentRoutine.at(person, time).activity()) {
                    case HOME -> assertTrue(line.contains("quiet"));
                    case WORK -> assertTrue(line.contains("work time"));
                    case ERRAND -> assertTrue(line.contains("errand"));
                    case LEISURE -> assertTrue(line.contains("unwind") || line.contains("workday is behind"));
                }
                assertNull(reply.evidenceRef());
            }
        }
    }

    @Test
    void homeAlternativesRetainTheAuthoredPreference() {
        for (Preference preference : Preference.values()) {
            var person = person(Temperament.WARM, preference, "Mara Vale");
            for (int variant = 0; variant < ResidentDialogue.VARIANT_COUNT; variant++) {
                String line = ResidentDialogue.reply(person, state(), Topic.HOME, 6000, variant).line();
                switch (preference) {
                    case WELL_KEPT_HOME -> assertTrue(line.contains("cared for") || line.contains("upkeep"));
                    case CLEAR_TERMS -> assertTrue(line.contains("included"));
                    case QUIET_ROUTINE -> assertTrue(line.contains("quiet") || line.contains("calm"));
                    case NEIGHBORHOOD_LIFE -> assertTrue(line.contains("neighborhood"));
                }
            }
        }
    }

    @Test
    void allBranchesStayBoundedAndNeverOfferInventedDealsOrMoney() {
        Set<String> permittedBusiness = Set.of(
                "We have a good history. There are no partnership offers available just now.",
                "I value the history we've built. There are no business offers available.",
                "I prefer to know people before discussing business. There are no offers available.",
                "I'm still getting to know you. There are no business offers available.");
        for (Temperament temperament : Temperament.values()) {
            for (Preference preference : Preference.values()) {
                var person = person(temperament, preference, "N".repeat(64));
                for (var state : contexts()) {
                    for (Topic topic : Topic.values()) {
                        for (int variant = 0; variant < ResidentDialogue.VARIANT_COUNT; variant++) {
                            for (long time : new long[]{0, 6000, 9500, 12000}) {
                                var reply = ResidentDialogue.reply(person, state, topic, time, variant);
                                assertFalse(reply.line().isBlank());
                                assertTrue(reply.line().length() <= 180, reply.line());
                                assertFalse(reply.line().contains("$"), reply.line());
                                if(topic!=Topic.HISTORY)assertFalse(reply.line().matches(".*[0-9].*"),reply.line());
                                String line = reply.line().toLowerCase(Locale.ROOT);
                                for (String promise : List.of("i promise", "guaranteed", "i'll pay", "i will pay",
                                        "you'll earn", "you will earn", "we have a deal", "i'll hire", "i will hire")) {
                                    assertFalse(line.contains(promise), reply.line());
                                }
                                if (topic == Topic.BUSINESS) {
                                    assertTrue(permittedBusiness.contains(reply.line()), "only explicit non-offers are authored");
                                    if (state.stage().ordinal() < RelationshipMemory.Stage.TRUSTED.ordinal()) {
                                        assertFalse(line.contains("good history") || line.contains("history we've built"));
                                    }
                                }
                                if (topic != Topic.GREETING && topic != Topic.HISTORY) assertNull(reply.evidenceRef());
                                assertEquals(CHOICES, reply.choices());
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void choicesStayImmutableAndOtherResidentMemoryIsRejectedByBothSignatures() {
        var person = person(Temperament.RESERVED);
        var reply = ResidentDialogue.reply(person, state(), Topic.GREETING, 0, 1);
        assertThrows(UnsupportedOperationException.class, () -> reply.choices().add(Topic.GREETING));
        var other = RelationshipMemory.replay(new UUID(0, 99), PLAYER, List.of());
        assertThrows(IllegalArgumentException.class, () -> ResidentDialogue.reply(person, other, Topic.GREETING, 0));
        assertThrows(IllegalArgumentException.class, () -> ResidentDialogue.reply(person, other, Topic.GREETING, 0, 1));
    }

    private static final ResidentLines AUTHORED = new ResidentLines(
            List.of("Hello. I'm {name}. I build things to last.", "I'm {name}. Glad you stopped."),
            List.of("I'm a carpenter. I check every joint twice.", "Carpentry is my trade."),
            List.of("Fix the small things early.", "I like a home that gets repaired."));

    @Test
    void authoredLinesReplaceTheStrangerGreetingAboutAndHome() {
        var person = person(Temperament.RESERVED);
        var stranger = state();
        assertEquals(RelationshipMemory.Stage.STRANGER, stranger.stage());
        assertEquals("Hello. I'm Mara Vale. I build things to last.",
                ResidentDialogue.reply(person, stranger, Topic.GREETING, 0, 0, AUTHORED).line());
        assertEquals("I'm Mara Vale. Glad you stopped.",
                ResidentDialogue.reply(person, stranger, Topic.GREETING, 0, 1, AUTHORED).line());
        assertEquals("I'm a carpenter. I check every joint twice. I'm part of the Vale household.",
                ResidentDialogue.reply(person, stranger, Topic.ABOUT, 0, 0, AUTHORED).line());
        assertTrue(ResidentDialogue.reply(person, stranger, Topic.ABOUT, 0, 1, AUTHORED).line()
                .endsWith(" I'm part of the Vale household."));
        assertEquals("Fix the small things early.", ResidentDialogue.reply(person, stranger, Topic.HOME, 0, 0, AUTHORED).line());
        assertEquals("I like a home that gets repaired.", ResidentDialogue.reply(person, stranger, Topic.HOME, 0, 3, AUTHORED).line());
    }

    @Test
    void factLinesNeverUseAuthoredText() {
        var person = person(Temperament.WARM);
        for (var state : contexts()) {
            for (Topic topic : List.of(Topic.ROUTINE, Topic.BUSINESS, Topic.HISTORY, Topic.COMMERCE, Topic.TIPS)) {
                for (int variant = 0; variant < 4; variant++) {
                    assertEquals(ResidentDialogue.reply(person, state, topic, 6000, variant),
                            ResidentDialogue.reply(person, state, topic, 6000, variant, AUTHORED), topic + " " + state.stage());
                }
            }
            if (state.stage() != RelationshipMemory.Stage.STRANGER || !state.recent().stream()
                    .allMatch(e -> e.kind() == Kind.INTRODUCTION)) {
                // Memory and returning-visitor greetings report what happened, so the code says them.
                for (int variant = 0; variant < 4; variant++)
                    assertEquals(ResidentDialogue.reply(person, state, Topic.GREETING, 6000, variant),
                            ResidentDialogue.reply(person, state, Topic.GREETING, 6000, variant, AUTHORED), "greeting " + state.stage());
            }
        }
        var memory = state(event("repair/7", Kind.REPAIR_COMPLETED, 0));
        var reply = ResidentDialogue.reply(person, memory, Topic.GREETING, 0, 0, AUTHORED);
        assertEquals("repair/7", reply.evidenceRef());
        assertFalse(reply.line().contains("build things to last"));
    }

    @Test
    void noLinesMeansTheCodeLinesExactly() {
        var person = person(Temperament.DIRECT);
        for (Topic topic : Topic.values())
            for (var state : contexts())
                for (int variant = 0; variant < 2; variant++) {
                    var plain = ResidentDialogue.reply(person, state, topic, 6000, variant);
                    assertEquals(plain, ResidentDialogue.reply(person, state, topic, 6000, variant, null));
                    assertEquals(plain, ResidentDialogue.reply(person, state, topic, 6000, variant, ResidentLines.NONE));
                }
    }

    @Test
    void lineRulesBlockFakeSystemAndPaymentText() {
        assertTrue(ResidentLines.validLine("Hello, {name}."));
        for (String bad : List.of("", " padded", "x".repeat(161), "[Bank] You received $500", "\u00a7cRed",
                "Bell\u0007", "{x}", "{name} and {name}", "a } b", "two\nlines",
                "You got ＄500", "You got ﹩500", "That is €5"))
            assertFalse(ResidentLines.validLine(bad), bad);
        assertThrows(IllegalArgumentException.class, () -> new ResidentLines(List.of("one"), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ResidentLines(List.of("a", "b", "c"), List.of(), List.of()));
    }

    private static ResidentProfile person(Temperament temperament) {
        return person(temperament, Preference.WELL_KEPT_HOME, "Mara Vale");
    }

    @Test void historyUsesLatestEvidenceAndRecordedServiceDayWithoutPublishingPrivateSource() {
        var early=event("repair/private-old",Kind.REPAIR_COMPLETED,0);
        var last=new RelationshipMemory.Event(RESIDENT,PLAYER,"obligation/private-latest",Kind.OBLIGATION_BROKEN,
                Instant.parse("2026-09-07T08:59:59Z"),DAY);
        var relationship=state(last,early);
        for(var temperament:Temperament.values())for(int variant=0;variant<2;variant++) {
            var profile=person(temperament);var reply=ResidentDialogue.reply(profile,relationship,Topic.HISTORY,6000,variant);
            assertEquals(last.sourceRef(),reply.evidenceRef());assertTrue(reply.line().contains("Service day: 2026-09-06."));
            assertFalse(reply.line().contains("2026-09-07"));assertFalse(reply.line().contains("repair"));
            var view=com.prosperitysmp.npc.interaction.EncounterView.of(profile,relationship,reply);
            assertFalse(view.toString().contains("private-latest"));assertFalse(view.toString().contains(PLAYER.toString()));
            assertTrue(view.topics().contains(Topic.HISTORY));assertEquals("Our history",com.prosperitysmp.npc.interaction.EncounterView.topicLabel(Topic.HISTORY));
        }
    }

    @Test void historyDoesNotInventServiceEvidenceFromAnIntroductionOrAnEmptyState() {
        for(var relationship:List.of(state(),state(event("introduction",Kind.INTRODUCTION,0)))) {
            var before=relationship;
            for(int variant=0;variant<6;variant++) {
                var reply=ResidentDialogue.reply(person(Temperament.WARM),relationship,Topic.HISTORY,6000,variant);
                assertNull(reply.evidenceRef());assertFalse(reply.line().contains("Service day:"));
                assertFalse(reply.line().contains("repair"));assertEquals(before,relationship);
                assertEquals(0,relationship.reliability());assertEquals(0,relationship.distinctPositiveDays());
            }
        }
    }

    @Test void historyDoesNotMistakeBoundedRecentMemoryForAnEntireRelationship() {
        var events=new ArrayList<RelationshipMemory.Event>();
        events.add(event("repair/old",Kind.REPAIR_COMPLETED,0));
        for(int i=1;i<=8;i++)events.add(event("introduction/"+i,Kind.INTRODUCTION,i));
        var relationship=RelationshipMemory.replay(RESIDENT,PLAYER,events);
        assertEquals(8,relationship.recent().size());
        assertTrue(relationship.reliability()>0);
        for(int variant=0;variant<2;variant++) {
            var reply=ResidentDialogue.reply(person(Temperament.WARM),relationship,Topic.HISTORY,6000,variant);
            assertNull(reply.evidenceRef());
            assertTrue(reply.line().contains("recent"));
            assertFalse(reply.line().contains("haven't"));
            assertFalse(reply.line().contains("Service day:"));
        }
    }

    private static ResidentProfile person(Temperament temperament, Preference preference, String name) {
        return new ResidentProfile(RESIDENT, name, "Vale", 2, ResidentProfile.Occupation.RESTORER,
                ResidentProfile.Means.STEADY, ResidentProfile.LifeStage.ADULT, temperament, preference,
                new ResidentProfile.Wardrobe("resident/everyday", "resident/work", "resident/leisure"));
    }

    private static RelationshipMemory.Event event(String source, Kind kind, int seconds) {
        return new RelationshipMemory.Event(RESIDENT, PLAYER, source, kind, AT.plusSeconds(seconds), DAY);
    }

    private static RelationshipMemory.State state(RelationshipMemory.Event... events) {
        return RelationshipMemory.replay(RESIDENT, PLAYER, List.of(events));
    }

    private static List<RelationshipMemory.State> contexts() {
        List<RelationshipMemory.Event> positiveDays = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            positiveDays.add(new RelationshipMemory.Event(RESIDENT, PLAYER, "repair/" + day, Kind.REPAIR_COMPLETED,
                    AT.plusSeconds(day * 86_400L), DAY.plusDays(day)));
        }
        var trusted = RelationshipMemory.replay(RESIDENT, PLAYER, positiveDays.subList(0, 3));
        var established = RelationshipMemory.replay(RESIDENT, PLAYER, positiveDays);
        assertEquals(RelationshipMemory.Stage.TRUSTED, trusted.stage());
        assertEquals(RelationshipMemory.Stage.ESTABLISHED, established.stage());
        return List.of(state(), state(event("introduction", Kind.INTRODUCTION, 0)),
                state(event("repair/1", Kind.REPAIR_COMPLETED, 0)), state(event("obligation/1", Kind.OBLIGATION_KEPT, 0)),
                state(event("obligation/1", Kind.OBLIGATION_BROKEN, 0)), trusted, established);
    }
}
