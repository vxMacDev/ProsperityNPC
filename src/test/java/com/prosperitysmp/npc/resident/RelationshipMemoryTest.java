package com.prosperitysmp.npc.resident;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RelationshipMemoryTest {
    private static final UUID RESIDENT = new UUID(1, 2);
    private static final UUID PLAYER = new UUID(3, 4);
    private static RelationshipMemory.Event event(String key, RelationshipMemory.Kind kind, int day, int minute) {
        return new RelationshipMemory.Event(RESIDENT, PLAYER, key, kind,
                Instant.parse("2026-09-01T15:00:00Z").plusSeconds(day * 86400L + minute * 60L),
                LocalDate.of(2026, 9, 1).plusDays(day));
    }
    private static RelationshipMemory.State replay(List<RelationshipMemory.Event> events) {
        return RelationshipMemory.replay(RESIDENT, PLAYER, events);
    }

    @Test void strangerWithoutEvidence() {
        assertEquals(RelationshipMemory.Stage.STRANGER, replay(List.of()).stage());
    }
    @Test void greetingSpamNeverBuildsTrust() {
        var events = new ArrayList<RelationshipMemory.Event>();
        for (int i = 0; i < 300; i++) events.add(event("greeting:" + i, RelationshipMemory.Kind.INTRODUCTION, 0, i));
        var state = replay(events);
        assertEquals(RelationshipMemory.Stage.ACQUAINTANCE, state.stage());
        assertEquals(0, state.reliability());
        assertEquals(0, state.distinctPositiveDays());
        assertEquals(RelationshipMemory.MAX_VISIBLE_MEMORIES, state.recent().size());
    }
    @Test void duplicateSourceDoesNotPayTrustTwice() {
        var event = event("incident:1", RelationshipMemory.Kind.REPAIR_COMPLETED, 0, 0);
        assertEquals(replay(List.of(event)), replay(List.of(event, event, event)));
    }
    @Test void duplicateWithChangedPayloadRejectedNotSilentlyMerged() {
        assertThrows(IllegalArgumentException.class, () -> replay(List.of(
                event("incident:1", RelationshipMemory.Kind.REPAIR_COMPLETED, 0, 0),
                event("incident:1", RelationshipMemory.Kind.OBLIGATION_KEPT, 0, 0))));
    }
    @Test void distinctSameDayRepairsCannotRushRelationship() {
        var events = new ArrayList<RelationshipMemory.Event>();
        for (int i = 0; i < 80; i++) events.add(event("incident:" + i, RelationshipMemory.Kind.REPAIR_COMPLETED, 0, i));
        var state = replay(events);
        assertEquals(4, state.reliability());
        assertEquals(1, state.distinctPositiveDays());
        assertEquals(RelationshipMemory.Stage.ACQUAINTANCE, state.stage());
    }
    @Test void trustNeedsSeparateDaysAndCloseRelationshipNeedsSeven() {
        var events = new ArrayList<RelationshipMemory.Event>();
        for (int i = 0; i < 7; i++) {
            events.add(event("incident:" + i, RelationshipMemory.Kind.REPAIR_COMPLETED, i, 0));
            assertEquals(i < 2 ? RelationshipMemory.Stage.ACQUAINTANCE
                    : i < 6 ? RelationshipMemory.Stage.TRUSTED : RelationshipMemory.Stage.ESTABLISHED, replay(events).stage());
        }
    }
    @Test void offlineTimeDoesNotDecayTrust() {
        var events = new ArrayList<RelationshipMemory.Event>();
        for (int day = 0; day < 7; day++) {
            events.add(event("incident:" + day, RelationshipMemory.Kind.REPAIR_COMPLETED, day, 0));
        }
        var before = replay(events);
        assertEquals(RelationshipMemory.Stage.ESTABLISHED, before.stage());
        var returnVisit = event("introduction", RelationshipMemory.Kind.INTRODUCTION, 365, 0);
        events.add(returnVisit);
        var after = replay(events);
        assertEquals(before.reliability(), after.reliability());
        assertEquals(before.distinctPositiveDays(), after.distinctPositiveDays());
        assertEquals(before.stage(), after.stage());
        assertEquals(returnVisit, after.recent().getFirst());
        assertEquals(before.recent(), after.recent().subList(1, after.recent().size()));
    }
    @Test void brokenObligationHasConsequencesButNeverNegativeScore() {
        var events = List.of(event("incident:1", RelationshipMemory.Kind.REPAIR_COMPLETED, 0, 0),
                event("contract:1", RelationshipMemory.Kind.OBLIGATION_BROKEN, 0, 1));
        assertEquals(0, replay(events).reliability());
    }
    @Test void reorderedReplayHasIdenticalVerdictAndRecentMemories() {
        var events = new ArrayList<RelationshipMemory.Event>();
        for (int i = 0; i < 20; i++) events.add(event("source:" + i, i % 3 == 0
                ? RelationshipMemory.Kind.OBLIGATION_BROKEN : RelationshipMemory.Kind.REPAIR_COMPLETED, i, 0));
        var expected = replay(events);
        Collections.shuffle(events, new java.util.Random(42));
        assertEquals(expected, replay(events));
    }
    @Test void anotherPlayersMemoryCannotInfluenceRelationship() {
        var foreign = new RelationshipMemory.Event(RESIDENT, UUID.randomUUID(), "incident:1",
                RelationshipMemory.Kind.REPAIR_COMPLETED, Instant.now(), LocalDate.of(2026, 9, 1));
        assertThrows(IllegalArgumentException.class, () -> replay(List.of(foreign)));
    }
    @Test void financialBackgroundOrFamilyIsNotAnInputToTrust() {
        assertEquals(4, replay(List.of(event("incident:1", RelationshipMemory.Kind.REPAIR_COMPLETED, 0, 0))).reliability());
        assertEquals(0, RelationshipMemory.replay(new UUID(1, 99), PLAYER, List.of()).reliability());
    }
    @Test void unboundedInputRequiresCheckpointInsteadOfHotPathScan() {
        var event = event("incident:1", RelationshipMemory.Kind.REPAIR_COMPLETED, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> replay(Collections.nCopies(4097, event)));
    }
    @Test void regressingEconomyDayFailsClosed() {
        var later = new RelationshipMemory.Event(RESIDENT, PLAYER, "incident:2", RelationshipMemory.Kind.REPAIR_COMPLETED,
                Instant.parse("2026-09-03T15:00:00Z"), LocalDate.of(2026, 9, 1));
        assertThrows(IllegalArgumentException.class,
                () -> replay(List.of(event("incident:1", RelationshipMemory.Kind.REPAIR_COMPLETED, 1, 0), later)));
    }
    @Test void mutableCallerListCannotRewriteEvidenceAfterReplay() {
        var events = new ArrayList<>(List.of(event("incident:1", RelationshipMemory.Kind.REPAIR_COMPLETED, 0, 0)));
        var state = replay(events);
        events.clear();
        assertEquals(1, state.recent().size());
        assertThrows(UnsupportedOperationException.class, () -> state.recent().clear());
    }

    @Test void stateRejectsMismatchedResidentOrPlayerInRecentEvidence() {
        var source = event("incident:1", RelationshipMemory.Kind.REPAIR_COMPLETED, 0, 0);
        var anotherResident = new RelationshipMemory.Event(new UUID(1, 99), PLAYER,
                source.sourceRef(), source.kind(), source.occurredAt(), source.economyDay());
        var anotherPlayer = new RelationshipMemory.Event(RESIDENT, new UUID(3, 99),
                source.sourceRef(), source.kind(), source.occurredAt(), source.economyDay());
        for (var foreign : List.of(anotherResident, anotherPlayer)) {
            assertThrows(IllegalArgumentException.class, () -> new RelationshipMemory.State(
                    RESIDENT, PLAYER, RelationshipMemory.Stage.ACQUAINTANCE, 4, 1, List.of(foreign)));
        }
    }

    @Test void stateRejectsNullIdentityStageOrEvidence() {
        assertThrows(NullPointerException.class, () -> new RelationshipMemory.State(
                null, PLAYER, RelationshipMemory.Stage.STRANGER, 0, 0, List.of()));
        assertThrows(NullPointerException.class, () -> new RelationshipMemory.State(
                RESIDENT, null, RelationshipMemory.Stage.STRANGER, 0, 0, List.of()));
        assertThrows(NullPointerException.class, () -> new RelationshipMemory.State(
                RESIDENT, PLAYER, null, 0, 0, List.of()));
        assertThrows(NullPointerException.class, () -> new RelationshipMemory.State(
                RESIDENT, PLAYER, RelationshipMemory.Stage.STRANGER, 0, 0, null));
        assertThrows(NullPointerException.class, () -> new RelationshipMemory.State(
                RESIDENT, PLAYER, RelationshipMemory.Stage.ACQUAINTANCE, 0, 0, Collections.singletonList(null)));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 101, Integer.MAX_VALUE})
    void stateRejectsOutOfRangeReliability(int reliability) {
        var source = event("incident:1", RelationshipMemory.Kind.REPAIR_COMPLETED, 0, 0);
        var stage = reliability >= 28 ? RelationshipMemory.Stage.ESTABLISHED : RelationshipMemory.Stage.ACQUAINTANCE;
        assertThrows(IllegalArgumentException.class, () -> new RelationshipMemory.State(
                RESIDENT, PLAYER, stage, reliability, 25, List.of(source)));
    }

    @Test void stateRejectsOutOfRangePositiveDayCounts() {
        var introduction = event("introduction", RelationshipMemory.Kind.INTRODUCTION, 0, 0);
        for (int days : new int[]{Integer.MIN_VALUE, -1, RelationshipMemory.MAX_EVENTS + 1, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new RelationshipMemory.State(
                    RESIDENT, PLAYER, RelationshipMemory.Stage.ACQUAINTANCE, 0, days, List.of(introduction)));
        }
    }

    @Test void stateRejectsPositiveReliabilityWithoutPositiveDays() {
        var source = event("incident:1", RelationshipMemory.Kind.REPAIR_COMPLETED, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new RelationshipMemory.State(
                RESIDENT, PLAYER, RelationshipMemory.Stage.ACQUAINTANCE, 4, 0, List.of(source)));
    }

    @Test void emptyStateRequiresStrangerAndZeroSummary() {
        assertDoesNotThrow(() -> new RelationshipMemory.State(
                RESIDENT, PLAYER, RelationshipMemory.Stage.STRANGER, 0, 0, List.of()));
        for (var stage : List.of(RelationshipMemory.Stage.ACQUAINTANCE,
                RelationshipMemory.Stage.TRUSTED, RelationshipMemory.Stage.ESTABLISHED)) {
            assertThrows(IllegalArgumentException.class, () -> new RelationshipMemory.State(
                    RESIDENT, PLAYER, stage, 0, 0, List.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> new RelationshipMemory.State(
                RESIDENT, PLAYER, RelationshipMemory.Stage.STRANGER, 4, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RelationshipMemory.State(
                RESIDENT, PLAYER, RelationshipMemory.Stage.STRANGER, 0, 1, List.of()));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0, ACQUAINTANCE",
            "0, 7, ACQUAINTANCE",
            "8, 2, ACQUAINTANCE",
            "11, 3, ACQUAINTANCE",
            "12, 3, TRUSTED",
            "24, 6, TRUSTED",
            "27, 7, TRUSTED",
            "28, 7, ESTABLISHED",
            "100, 25, ESTABLISHED"
    })
    void stateRequiresStageToMatchReliabilityAndDays(int reliability, int days, RelationshipMemory.Stage expected) {
        // Recent evidence is a window, not the full history behind the summary.
        var introduction = event("introduction", RelationshipMemory.Kind.INTRODUCTION, 365, 0);
        for (var stage : RelationshipMemory.Stage.values()) {
            if (stage == expected) {
                assertDoesNotThrow(() -> new RelationshipMemory.State(
                        RESIDENT, PLAYER, stage, reliability, days, List.of(introduction)));
            } else {
                assertThrows(IllegalArgumentException.class, () -> new RelationshipMemory.State(
                        RESIDENT, PLAYER, stage, reliability, days, List.of(introduction)), stage.name());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"5, 1, ACQUAINTANCE", "13, 3, TRUSTED", "29, 7, ESTABLISHED"})
    void stateRejectsReliabilityExceedingPositiveDayBudget(int reliability, int days, RelationshipMemory.Stage stage) {
        var source = event("incident:1", RelationshipMemory.Kind.REPAIR_COMPLETED, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new RelationshipMemory.State(
                RESIDENT, PLAYER, stage, reliability, days, List.of(source)));
    }

    @Test void stateBoundsRecentEvidenceWithoutRelyingOnDuplicateDetection() {
        var recent = new ArrayList<RelationshipMemory.Event>();
        for (int i = 0; i <= RelationshipMemory.MAX_VISIBLE_MEMORIES; i++) {
            recent.add(event("greeting:" + i, RelationshipMemory.Kind.INTRODUCTION, 0, i));
        }
        assertDoesNotThrow(() -> new RelationshipMemory.State(RESIDENT, PLAYER,
                RelationshipMemory.Stage.ACQUAINTANCE, 0, 0,
                recent.subList(0, RelationshipMemory.MAX_VISIBLE_MEMORIES)));
        assertThrows(IllegalArgumentException.class, () -> new RelationshipMemory.State(
                RESIDENT, PLAYER, RelationshipMemory.Stage.ACQUAINTANCE, 0, 0, recent));
    }

    @Test void stateRejectsDuplicateRecentSourcesEvenForIdenticalPayloads() {
        var source = event("incident:1", RelationshipMemory.Kind.REPAIR_COMPLETED, 0, 0);
        var conflicting = event("incident:1", RelationshipMemory.Kind.OBLIGATION_KEPT, 0, 1);
        for (var duplicate : List.of(source, conflicting)) {
            assertThrows(IllegalArgumentException.class, () -> new RelationshipMemory.State(
                    RESIDENT, PLAYER, RelationshipMemory.Stage.ACQUAINTANCE, 4, 1, List.of(source, duplicate)));
        }
    }

    @Test void stateAcceptsSummaryCoveringMoreDaysThanVisibleEvidence() {
        var events = new ArrayList<RelationshipMemory.Event>();
        for (int day = 0; day < 25; day++) {
            events.add(event("incident:" + day, RelationshipMemory.Kind.REPAIR_COMPLETED, day, 0));
        }
        var replayed = replay(events);
        assertEquals(100, replayed.reliability());
        assertEquals(25, replayed.distinctPositiveDays());
        assertEquals(RelationshipMemory.MAX_VISIBLE_MEMORIES, replayed.recent().size());
        assertEquals(replayed, new RelationshipMemory.State(RESIDENT, PLAYER, replayed.stage(),
                replayed.reliability(), replayed.distinctPositiveDays(), replayed.recent()));
        assertDoesNotThrow(() -> new RelationshipMemory.State(RESIDENT, PLAYER,
                RelationshipMemory.Stage.ESTABLISHED, 100, RelationshipMemory.MAX_EVENTS, replayed.recent()));
    }

    @Test void stateDirectConstructionCopiesAndFreezesRecentEvidence() {
        var source = event("incident:1", RelationshipMemory.Kind.REPAIR_COMPLETED, 0, 0);
        var recent = new ArrayList<>(List.of(source));
        var state = new RelationshipMemory.State(
                RESIDENT, PLAYER, RelationshipMemory.Stage.ACQUAINTANCE, 4, 1, recent);
        recent.clear();
        assertEquals(List.of(source), state.recent());
        assertThrows(UnsupportedOperationException.class, () -> state.recent().clear());
    }
}
