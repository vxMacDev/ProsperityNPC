package com.prosperitysmp.npc.interaction;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic state-machine tests; no Bukkit, database, threads or wall clock. */
class EncounterSessionsTest {
    private static final UUID PLAYER = id(1);
    private static final UUID RESIDENT = id(2);
    private static final UUID AVATAR = id(3);
    private static final UUID WORLD = id(4);

    @Test
    void openAdmitsInitialReadAndCompletePublishesReadyWithOriginalDeadline() {
        var sessions = sessions(2, 100, 10);
        var initial = open(sessions, PLAYER, 5);
        assertEquals(PLAYER, initial.playerId());
        assertEquals(RESIDENT, initial.residentId());
        assertEquals(AVATAR, initial.avatarId());
        assertEquals(WORLD, initial.worldId());
        assertEquals(105, initial.expiresAtMillis());
        assertTrue(sessions.isPending(PLAYER, initial.token()));
        assertEquals(initial, sessions.current(PLAYER, 5).orElseThrow());
        assertTrue(sessions.admit(PLAYER, initial.token(), 5).isEmpty());

        var ready = sessions.complete(PLAYER, initial.token(), 7).orElseThrow();
        assertNotEquals(initial.token(), ready.token());
        assertEquals(new EncounterSessions.Ticket(PLAYER, RESIDENT, AVATAR, WORLD,
                ready.token(), 105), ready);
        assertFalse(sessions.isPending(PLAYER, ready.token()));
        assertEquals(ready, sessions.current(PLAYER, 7).orElseThrow());
        assertTrue(sessions.complete(PLAYER, initial.token(), 7).isEmpty());
        assertTrue(sessions.complete(PLAYER, ready.token(), 7).isEmpty());
    }

    @Test
    void cooldownStartsOnOpenAndAdmitNotOnCompletion() {
        var sessions = sessions(1, 100, 10);
        var initial = open(sessions, PLAYER, 20);
        var ready = sessions.complete(PLAYER, initial.token(), 23).orElseThrow();
        assertTrue(sessions.admit(PLAYER, ready.token(), 29).isEmpty());
        var pending = sessions.admit(PLAYER, ready.token(), 30).orElseThrow();
        assertEquals(ready, pending);
        assertTrue(sessions.isPending(PLAYER, ready.token()));
        assertTrue(sessions.admit(PLAYER, ready.token(), 30).isEmpty());
        assertTrue(sessions.admit(PLAYER, ready.token(), 40).isEmpty());

        var next = sessions.complete(PLAYER, ready.token(), 45).orElseThrow();
        assertEquals(120, next.expiresAtMillis());
        assertTrue(sessions.admit(PLAYER, next.token(), 45).isPresent());
        assertTrue(sessions.complete(PLAYER, ready.token(), 45).isEmpty());
        assertEquals(next, sessions.current(PLAYER, 45).orElseThrow());
        assertTrue(sessions.isPending(PLAYER, next.token()));
    }

    @Test
    void everySuccessfulAdmitStartsAnotherCooldown() {
        var sessions = sessions(1, 100, 10);
        var initial = open(sessions, PLAYER, 0);
        var ready = sessions.complete(PLAYER, initial.token(), 0).orElseThrow();
        sessions.admit(PLAYER, ready.token(), 10).orElseThrow();
        var next = sessions.complete(PLAYER, ready.token(), 10).orElseThrow();
        assertTrue(sessions.admit(PLAYER, next.token(), 19).isEmpty());
        assertTrue(sessions.admit(PLAYER, next.token(), 20).isPresent());
    }

    @Test
    void pendingCannotBeReplacedEvenAfterCooldown() {
        var sessions = sessions(1, 100, 10);
        var initial = open(sessions, PLAYER, 0);
        assertTrue(sessions.open(PLAYER, id(20), id(21), id(22), 10).isEmpty());
        assertEquals(initial, sessions.current(PLAYER, 10).orElseThrow());
        assertEquals(1, sessions.size());
    }

    @Test
    void readyReplacementAtCapacityUpdatesTargetAndRejectsAllStaleOperations() {
        var sessions = sessions(1, 100, 10);
        var initial = open(sessions, PLAYER, 0);
        var ready = sessions.complete(PLAYER, initial.token(), 0).orElseThrow();
        assertTrue(sessions.open(PLAYER, id(20), id(21), id(22), 9).isEmpty());
        assertEquals(ready, sessions.current(PLAYER, 9).orElseThrow());
        var replacement = sessions.open(PLAYER, id(20), id(21), id(22), 10).orElseThrow();
        assertEquals(new EncounterSessions.Ticket(PLAYER, id(20), id(21), id(22),
                replacement.token(), 110), replacement);
        assertNotEquals(ready.token(), replacement.token());
        assertEquals(1, sessions.size());
        sessions.close(PLAYER, ready.token());
        sessions.close(PLAYER, initial.token());
        assertTrue(sessions.admit(PLAYER, ready.token(), 10).isEmpty());
        assertTrue(sessions.complete(PLAYER, initial.token(), 10).isEmpty());
        assertTrue(sessions.complete(PLAYER, ready.token(), 10).isEmpty());
        assertFalse(sessions.isPending(PLAYER, ready.token()));
        assertEquals(replacement, sessions.current(PLAYER, 10).orElseThrow());
    }

    @Test
    void tokensAndPlayerIdsMustMatchByValueAndNotAnotherPlayersTicket() {
        var sessions = sessions(2, 100, 0);
        var first = open(sessions, PLAYER, 0);
        var second = open(sessions, id(10), 0);
        assertTrue(sessions.current(id(11), 0).isEmpty());
        assertTrue(sessions.admit(PLAYER, second.token(), 0).isEmpty());
        assertTrue(sessions.complete(PLAYER, second.token(), 0).isEmpty());
        assertFalse(sessions.isPending(PLAYER, second.token()));
        sessions.close(PLAYER, second.token());
        assertEquals(first, sessions.current(PLAYER, 0).orElseThrow());
        var ready = sessions.complete(UUID.fromString(PLAYER.toString()),
                UUID.fromString(first.token().toString()), 0).orElseThrow();
        assertTrue(sessions.admit(id(11), ready.token(), 0).isEmpty());
        assertTrue(sessions.admit(PLAYER, second.token(), 0).isEmpty());
        assertTrue(sessions.admit(UUID.fromString(PLAYER.toString()),
                UUID.fromString(ready.token().toString()), 0).isPresent());
        assertEquals(second, sessions.current(id(10), 0).orElseThrow());
    }

    @Test
    void pendingAndReadyBothExpireAtExactDeadline() {
        for (boolean complete : new boolean[]{false, true}) {
            var sessions = sessions(1, 100, 0);
            var ticket = open(sessions, PLAYER, 0);
            if (complete) ticket = sessions.complete(PLAYER, ticket.token(), 0).orElseThrow();
            assertEquals(ticket, sessions.current(PLAYER, 99).orElseThrow());
            assertTrue(sessions.current(PLAYER, 100).isEmpty());
            assertFalse(sessions.isPending(PLAYER, ticket.token()));
            assertTrue(sessions.admit(PLAYER, ticket.token(), 100).isEmpty());
            assertTrue(sessions.complete(PLAYER, ticket.token(), 100).isEmpty());
            assertEquals(0, sessions.size());
        }
    }

    @Test
    void lateCompletionAndAdmissionPruneWithoutNeedingCurrentFirst() {
        var pending = sessions(1, 100, 0);
        var old = open(pending, PLAYER, 0);
        assertTrue(pending.complete(PLAYER, old.token(), 100).isEmpty());
        assertEquals(0, pending.size());

        var ready = sessions(1, 100, 0);
        var initial = open(ready, PLAYER, 0);
        var action = ready.complete(PLAYER, initial.token(), 0).orElseThrow();
        assertTrue(ready.admit(PLAYER, action.token(), 100).isEmpty());
        assertEquals(0, ready.size());
    }

    @Test
    void completionJustBeforeExpiryDoesNotExtendLifetime() {
        var sessions = sessions(1, 100, 0);
        var initial = open(sessions, PLAYER, 0);
        var ready = sessions.complete(PLAYER, initial.token(), 99).orElseThrow();
        assertEquals(100, ready.expiresAtMillis());
        assertTrue(sessions.admit(PLAYER, ready.token(), 100).isEmpty());
    }

    @Test
    void hardCapacityPrunesExpiredOtherPlayersBeforeRejectingOpen() {
        var sessions = sessions(2, 100, 0);
        var first = open(sessions, PLAYER, 0);
        var second = open(sessions, id(10), 1);
        assertTrue(sessions.open(id(11), RESIDENT, AVATAR, WORLD, 99).isEmpty());
        assertEquals(2, sessions.size());
        var third = open(sessions, id(11), 100);
        assertEquals(2, sessions.size());
        assertTrue(sessions.current(PLAYER, 100).isEmpty());
        assertEquals(second, sessions.current(id(10), 100).orElseThrow());
        assertEquals(third, sessions.current(id(11), 100).orElseThrow());
        assertTrue(sessions.complete(PLAYER, first.token(), 100).isEmpty());
    }

    @Test
    void closeIsConditionalIdempotentAndRetainsReopenCooldown() {
        var sessions = sessions(1, 100, 10);
        var initial = open(sessions, PLAYER, 0);
        sessions.close(PLAYER, id(999));
        assertEquals(1, sessions.size());
        sessions.close(PLAYER, initial.token());
        sessions.close(PLAYER, initial.token());
        assertEquals(0, sessions.size());
        assertTrue(sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 9).isEmpty());
        var replacement = open(sessions, PLAYER, 10);
        sessions.close(PLAYER, initial.token());
        assertTrue(sessions.complete(PLAYER, initial.token(), 10).isEmpty());
        assertEquals(replacement, sessions.current(PLAYER, 10).orElseThrow());
    }

    @Test
    void closeAfterAdmitRetainsLatestActionCooldown() {
        var sessions = sessions(1, 100, 10);
        var initial = open(sessions, PLAYER, 0);
        var ready = sessions.complete(PLAYER, initial.token(), 0).orElseThrow();
        sessions.admit(PLAYER, ready.token(), 10).orElseThrow();
        sessions.close(PLAYER, ready.token());
        assertTrue(sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 19).isEmpty());
        assertTrue(sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 20).isPresent());
    }

    @Test
    void quitInvalidatesOnlyThatPlayerAndDoesNotBypassCooldown() {
        var sessions = sessions(2, 100, 10);
        var first = open(sessions, PLAYER, 0);
        var second = open(sessions, id(10), 0);
        sessions.removePlayer(PLAYER);
        sessions.removePlayer(PLAYER);
        assertEquals(1, sessions.size());
        assertFalse(sessions.isPending(PLAYER, first.token()));
        assertTrue(sessions.complete(PLAYER, first.token(), 1).isEmpty());
        assertTrue(sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 9).isEmpty());
        var replacement = open(sessions, PLAYER, 10);
        sessions.close(PLAYER, first.token());
        assertEquals(replacement, sessions.current(PLAYER, 10).orElseThrow());
        assertEquals(second, sessions.current(id(10), 10).orElseThrow());
    }

    @Test
    void despawnInvalidatesEveryViewerOfOnlyThatAvatar() {
        var sessions = sessions(3, 100, 10);
        var first = open(sessions, PLAYER, 0);
        var second = open(sessions, id(10), 0);
        var ready = sessions.complete(id(10), second.token(), 0).orElseThrow();
        var other = sessions.open(id(11), RESIDENT, id(12), WORLD, 0).orElseThrow();
        sessions.removeAvatar(RESIDENT);
        assertEquals(3, sessions.size());
        sessions.removeAvatar(AVATAR);
        sessions.removeAvatar(AVATAR);
        assertEquals(1, sessions.size());
        assertTrue(sessions.complete(PLAYER, first.token(), 0).isEmpty());
        assertTrue(sessions.admit(id(10), ready.token(), 0).isEmpty());
        assertFalse(sessions.isPending(PLAYER, first.token()));
        assertEquals(other, sessions.current(id(11), 0).orElseThrow());
        assertTrue(sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 9).isEmpty());
        assertTrue(sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 10).isPresent());
    }

    @Test
    void clearInvalidatesBothStatesAndAllowsFreshSessions() {
        var sessions = sessions(2, 100, 10);
        var first = open(sessions, PLAYER, 0);
        var second = open(sessions, id(10), 0);
        var ready = sessions.complete(id(10), second.token(), 0).orElseThrow();
        sessions.clear();
        sessions.clear();
        assertEquals(0, sessions.size());
        assertTrue(sessions.complete(PLAYER, first.token(), 0).isEmpty());
        assertTrue(sessions.admit(id(10), ready.token(), 0).isEmpty());
        assertFalse(sessions.isPending(PLAYER, first.token()));
        var fresh = open(sessions, PLAYER, 10);
        sessions.close(PLAYER, first.token());
        assertEquals(fresh, sessions.current(PLAYER, 10).orElseThrow());
    }

    @Test
    void cooldownHistoryFailsClosedAtTwiceSessionCapacityAndPrunesAtDeadline() {
        var sessions = sessions(1, 100, 10);
        var first = open(sessions, PLAYER, 0);
        sessions.close(PLAYER, first.token());
        var second = open(sessions, id(10), 1);
        sessions.close(id(10), second.token());
        assertTrue(sessions.open(id(11), RESIDENT, AVATAR, WORLD, 2).isEmpty());
        assertEquals(0, sessions.size());
        assertTrue(sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 9).isEmpty());
        assertTrue(sessions.open(id(10), RESIDENT, AVATAR, WORLD, 10).isEmpty());
        var third = open(sessions, id(11), 10);
        sessions.close(id(11), third.token());
        assertTrue(sessions.open(id(12), RESIDENT, AVATAR, WORLD, 10).isEmpty());
        assertTrue(sessions.open(id(12), RESIDENT, AVATAR, WORLD, 11).isPresent());
    }

    @Test
    void expiredSessionDoesNotEraseLongerCooldown() {
        var sessions = sessions(1, 5, 10);
        var first = open(sessions, PLAYER, 0);
        assertTrue(sessions.complete(PLAYER, first.token(), 5).isEmpty());
        assertTrue(sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 9).isEmpty());
        assertTrue(sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 10).isPresent());
    }

    @Test
    void cooldownHistoryRemainsUsableAcrossManyDistinctPlayers() {
        var sessions = sessions(1, 5, 10);
        for (int i = 0; i < 1_000; i++) {
            var ticket = open(sessions, id(10_000 + i), i * 10L);
            sessions.removePlayer(ticket.playerId());
            assertEquals(0, sessions.size());
        }
    }

    @Test
    void defaultConstructorHasDocumentedCapacityTtlAndCooldown() {
        var sessions = new EncounterSessions();
        var initial = open(sessions, PLAYER, 0);
        assertEquals(90_000, initial.expiresAtMillis());
        var ready = sessions.complete(PLAYER, initial.token(), 0).orElseThrow();
        assertTrue(sessions.admit(PLAYER, ready.token(), 999).isEmpty());
        assertTrue(sessions.admit(PLAYER, ready.token(), 1_000).isPresent());
        for (int i = 1; i < 128; i++) open(sessions, id(10_000 + i), 1_000);
        assertEquals(128, sessions.size());
        assertTrue(sessions.open(id(20_000), RESIDENT, AVATAR, WORLD, 1_000).isEmpty());
    }

    @Test
    void zeroCooldownStillAllowsOnlyOnePendingAction() {
        var sessions = sessions(1, 100, 0);
        var initial = open(sessions, PLAYER, 0);
        assertTrue(sessions.admit(PLAYER, initial.token(), 0).isEmpty());
        var ready = sessions.complete(PLAYER, initial.token(), 0).orElseThrow();
        assertTrue(sessions.admit(PLAYER, ready.token(), 0).isPresent());
        assertTrue(sessions.admit(PLAYER, ready.token(), 0).isEmpty());
    }

    @Test
    void sameTokenSupplierFailsClosedOnCompletionAndOtherPlayersOpen() {
        var sessions = new EncounterSessions(2, 100, 0, () -> id(50));
        var initial = open(sessions, PLAYER, 0);
        assertEquals(id(50), initial.token());
        assertTrue(sessions.complete(PLAYER, initial.token(), 0).isEmpty());
        assertTrue(sessions.isPending(PLAYER, initial.token()));
        assertTrue(sessions.admit(PLAYER, initial.token(), 0).isEmpty());
        assertTrue(sessions.open(id(10), RESIDENT, AVATAR, WORLD, 0).isEmpty());
        assertEquals(1, sessions.size());
        assertEquals(initial, sessions.current(PLAYER, 0).orElseThrow());
    }

    @Test
    void rotationRejectsAnotherPlayersTokenWithoutChangingEitherSession() {
        var sessions = new EncounterSessions(2, 100, 0,
                tokens(id(50), id(51), id(51), id(52)));
        var first = open(sessions, PLAYER, 0);
        var second = open(sessions, id(10), 0);
        assertTrue(sessions.complete(PLAYER, first.token(), 0).isEmpty());
        assertEquals(first, sessions.current(PLAYER, 0).orElseThrow());
        assertEquals(second, sessions.current(id(10), 0).orElseThrow());
        var ready = sessions.complete(PLAYER, first.token(), 0).orElseThrow();
        assertEquals(id(52), ready.token());
        assertTrue(sessions.complete(PLAYER, first.token(), 0).isEmpty());
    }

    @Test
    void replacementCollisionPreservesOldReadyTicketAndDoesNotStartCooldown() {
        var sessions = new EncounterSessions(1, 100, 10,
                tokens(id(50), id(51), id(50), id(52)));
        var initial = open(sessions, PLAYER, 0);
        var ready = sessions.complete(PLAYER, initial.token(), 0).orElseThrow();
        assertTrue(sessions.open(PLAYER, id(10), id(11), id(12), 10).isEmpty());
        assertEquals(ready, sessions.current(PLAYER, 10).orElseThrow());
        assertFalse(sessions.isPending(PLAYER, ready.token()));
        var replacement = sessions.open(PLAYER, id(10), id(11), id(12), 10).orElseThrow();
        assertEquals(id(52), replacement.token());
        sessions.close(PLAYER, ready.token());
        assertEquals(replacement, sessions.current(PLAYER, 10).orElseThrow());
    }

    @Test
    void rotationRejectsAbaEvenWhenTokenIsNoLongerCurrent() {
        var sessions = new EncounterSessions(1, 100, 0,
                tokens(id(50), id(51), id(52), id(50), id(53)));
        var a = open(sessions, PLAYER, 0);
        var b = sessions.complete(PLAYER, a.token(), 0).orElseThrow();
        sessions.admit(PLAYER, b.token(), 0).orElseThrow();
        var c = sessions.complete(PLAYER, b.token(), 0).orElseThrow();
        sessions.admit(PLAYER, c.token(), 0).orElseThrow();
        assertTrue(sessions.complete(PLAYER, c.token(), 0).isEmpty());
        assertTrue(sessions.complete(PLAYER, a.token(), 0).isEmpty());
        assertTrue(sessions.admit(PLAYER, a.token(), 0).isEmpty());
        sessions.close(PLAYER, a.token());
        assertEquals(c, sessions.current(PLAYER, 0).orElseThrow());
        assertTrue(sessions.isPending(PLAYER, c.token()));
        assertEquals(id(53), sessions.complete(PLAYER, c.token(), 0).orElseThrow().token());
    }

    @Test
    void invalidationRetainsCollisionProtectionUntilOriginalExpiry() throws Exception {
        for (String invalidation : new String[]{"close", "quit", "despawn", "clear"}) {
            var sessions = new EncounterSessions(1, 100, 10,
                    tokens(id(50), id(50), id(51)));
            var old = open(sessions, PLAYER, 0);
            switch (invalidation) {
                case "close" -> sessions.close(PLAYER, old.token());
                case "quit" -> sessions.removePlayer(PLAYER);
                case "despawn" -> sessions.removeAvatar(AVATAR);
                case "clear" -> sessions.clear();
                default -> throw new AssertionError(invalidation);
            }
            assertEquals(1, retainedEntries(sessions, "tokenExpiries"), invalidation);
            assertTrue(sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 9).isEmpty(), invalidation);
            assertTrue(sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 10).isEmpty(), invalidation);
            assertEquals(0, sessions.size(), invalidation);
            var fresh = open(sessions, PLAYER, 10);
            assertNotEquals(old.token(), fresh.token(), invalidation);
            assertTrue(sessions.complete(PLAYER, old.token(), 10).isEmpty(), invalidation);
            assertTrue(sessions.admit(PLAYER, old.token(), 10).isEmpty(), invalidation);
            sessions.close(PLAYER, old.token());
            assertEquals(fresh, sessions.current(PLAYER, 10).orElseThrow(), invalidation);
            assertEquals(2, retainedEntries(sessions, "tokenExpiries"), invalidation);
            assertEquals(fresh, sessions.current(PLAYER, 100).orElseThrow(), invalidation);
            assertEquals(1, retainedEntries(sessions, "tokenExpiries"), invalidation);
            assertTrue(sessions.current(PLAYER, 110).isEmpty(), invalidation);
            assertEquals(0, retainedEntries(sessions, "tokenExpiries"), invalidation);
        }
    }

    @Test
    void rotationAndCollisionAttemptsDoNotExtendRetiredTokenTracking() throws Exception {
        var sessions = new EncounterSessions(2, 100, 0,
                tokens(id(50), id(51), id(50), id(51), id(50)));
        var initial = open(sessions, PLAYER, 0);
        var ready = sessions.complete(PLAYER, initial.token(), 50).orElseThrow();
        assertEquals(100, ready.expiresAtMillis());
        assertEquals(2, retainedEntries(sessions, "tokenExpiries"));
        assertTrue(sessions.open(id(10), RESIDENT, AVATAR, WORLD, 99).isEmpty());
        assertTrue(sessions.open(id(10), RESIDENT, AVATAR, WORLD, 99).isEmpty());
        assertEquals(ready, sessions.current(PLAYER, 99).orElseThrow());
        assertEquals(2, retainedEntries(sessions, "tokenExpiries"));
        assertTrue(sessions.current(PLAYER, 100).isEmpty());
        assertEquals(0, retainedEntries(sessions, "tokenExpiries"));

        // Deliberate post-expiry reuse demonstrates the documented tracking boundary.
        var fresh = open(sessions, id(10), 100);
        assertEquals(initial.token(), fresh.token());
        assertEquals(200, fresh.expiresAtMillis());
        assertEquals(1, retainedEntries(sessions, "tokenExpiries"));
    }

    @Test
    void replacementRetiresOldTokensOnlyUntilTheirOwnExpiry() throws Exception {
        var sessions = sessions(1, 100, 0);
        var initial = open(sessions, PLAYER, 0);
        var ready = sessions.complete(PLAYER, initial.token(), 0).orElseThrow();
        var replacement = open(sessions, PLAYER, 50);
        assertEquals(3, retainedEntries(sessions, "tokenExpiries"));
        sessions.close(PLAYER, ready.token());
        assertEquals(replacement, sessions.current(PLAYER, 100).orElseThrow());
        assertEquals(1, retainedEntries(sessions, "tokenExpiries"));
        assertEquals(150, replacement.expiresAtMillis());
        assertTrue(sessions.current(PLAYER, 150).isEmpty());
        assertEquals(0, retainedEntries(sessions, "tokenExpiries"));
    }

    @Test
    void expiryQueueOrdersDelayedCompletionsByDeadlineNotIssuanceOrder() throws Exception {
        var sessions = new EncounterSessions(3, 100, 0,
                tokens(id(50), id(51), id(52), id(53), id(51), id(52)));
        var first = open(sessions, PLAYER, 0);
        var second = open(sessions, id(10), 40);
        var early = sessions.complete(PLAYER, first.token(), 60).orElseThrow();
        var later = sessions.complete(id(10), second.token(), 70).orElseThrow();
        sessions.close(PLAYER, early.token());

        assertTrue(sessions.open(id(11), RESIDENT, AVATAR, WORLD, 99).isEmpty());
        assertEquals(4, retainedEntries(sessions, "tokenExpiries"));
        assertEquals(4, retainedEntries(sessions, "tokenExpiryQueue"));
        assertEquals(later, sessions.current(id(10), 100).orElseThrow());
        assertEquals(2, retainedEntries(sessions, "tokenExpiries"));
        assertEquals(2, retainedEntries(sessions, "tokenExpiryQueue"));

        var reusedAfterExpiry = open(sessions, PLAYER, 100);
        assertEquals(early.token(), reusedAfterExpiry.token());
        assertEquals(200, reusedAfterExpiry.expiresAtMillis());
        assertEquals(later, sessions.current(id(10), 139).orElseThrow());
        assertEquals(3, retainedEntries(sessions, "tokenExpiryQueue"));
        assertTrue(sessions.current(id(10), 140).isEmpty());
        assertEquals(reusedAfterExpiry, sessions.current(PLAYER, 140).orElseThrow());
        assertEquals(1, retainedEntries(sessions, "tokenExpiries"));
        assertEquals(1, retainedEntries(sessions, "tokenExpiryQueue"));
        assertTrue(sessions.current(PLAYER, 200).isEmpty());
        assertEquals(0, retainedEntries(sessions, "tokenExpiries"));
        assertEquals(0, retainedEntries(sessions, "tokenExpiryQueue"));
    }

    @Test
    void prolongedChurnPrunesAllHistoryAndHasNoLifetimeIssuanceLimit() throws Exception {
        var sessions = sessions(1, 5, 1);
        for (int i = 0; i < 16_384; i++) {
            long now = i * 5L;
            UUID player = id(100_000 + i);
            var initial = open(sessions, player, now);
            var ready = sessions.complete(player, initial.token(), now).orElseThrow();
            sessions.close(player, ready.token());
            assertEquals(0, sessions.size());
            assertEquals(2, retainedEntries(sessions, "tokenExpiries"));
            assertEquals(2, retainedEntries(sessions, "tokenExpiryQueue"));
            assertEquals(1, retainedEntries(sessions, "cooldowns"));
        }
        assertTrue(sessions.current(PLAYER, 16_384 * 5L).isEmpty());
        assertEquals(0, retainedEntries(sessions, "tokenExpiries"));
        assertEquals(0, retainedEntries(sessions, "tokenExpiryQueue"));
        assertEquals(0, retainedEntries(sessions, "cooldowns"));
        assertEquals(0, sessions.size());
    }

    @Test
    void nullAndThrowingSuppliersCannotPublishOrReplaceTickets() {
        var nullOpen = new EncounterSessions(1, 100, 0, () -> null);
        assertThrows(NullPointerException.class, () -> open(nullOpen, PLAYER, 0));
        assertEquals(0, nullOpen.size());

        var sessions = new EncounterSessions(1, 100, 0,
                tokens(id(50), null, id(51), null, id(52)));
        var initial = open(sessions, PLAYER, 0);
        assertThrows(NullPointerException.class, () -> sessions.complete(PLAYER, initial.token(), 0));
        assertTrue(sessions.isPending(PLAYER, initial.token()));
        var ready = sessions.complete(PLAYER, initial.token(), 0).orElseThrow();
        assertThrows(NullPointerException.class, () -> open(sessions, PLAYER, 0));
        assertEquals(ready, sessions.current(PLAYER, 0).orElseThrow());
        assertEquals(id(52), open(sessions, PLAYER, 0).token());

        var failure = new IllegalStateException("fixture");
        var throwing = new EncounterSessions(1, 100, 0, () -> { throw failure; });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> open(throwing, PLAYER, 0)));
        assertEquals(0, throwing.size());
    }

    @Test
    void refusedOperationsDoNotCallTokenSupplier() {
        var sessions = new EncounterSessions(1, 100, 10, tokens(id(50), id(51)));
        var initial = open(sessions, PLAYER, 0);
        assertTrue(sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 10).isEmpty());
        assertTrue(sessions.open(id(10), RESIDENT, AVATAR, WORLD, 10).isEmpty());
        assertTrue(sessions.complete(PLAYER, id(999), 10).isEmpty());
        var ready = sessions.complete(PLAYER, initial.token(), 10).orElseThrow();
        assertEquals(id(51), ready.token());
        assertTrue(sessions.complete(PLAYER, initial.token(), 10).isEmpty());
        assertTrue(sessions.complete(PLAYER, ready.token(), 10).isEmpty());
    }

    @Test
    void clockRegressionCannotBypassPrunedCooldownsOrReviveExpiredSessions() {
        var sessions = sessions(1, 100, 10);
        var ticket = open(sessions, PLAYER, 0);
        assertTrue(sessions.current(PLAYER, 100).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> open(sessions, PLAYER, 0));
        assertThrows(IllegalArgumentException.class, () -> sessions.current(PLAYER, 99));
        assertThrows(IllegalArgumentException.class, () -> sessions.admit(PLAYER, ticket.token(), 99));
        assertThrows(IllegalArgumentException.class, () -> sessions.complete(PLAYER, ticket.token(), 99));
        sessions.clear();
        assertThrows(IllegalArgumentException.class, () -> open(sessions, PLAYER, 0));
        assertEquals(0, sessions.size());
        assertTrue(sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 100).isPresent());
    }

    @Test
    void invalidConstructorArgumentsAreRejected() {
        for (int limit : new int[]{Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE / 2 + 1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new EncounterSessions(limit, 100, 10, sequence()));
        }
        for (long ttl : new long[]{Long.MIN_VALUE, -1, 0}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new EncounterSessions(1, ttl, 10, sequence()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new EncounterSessions(1, 100, -1, sequence()));
        assertThrows(NullPointerException.class, () -> new EncounterSessions(1, 100, 0, null));
    }

    @Test
    void nullPublicInputsAreRejectedEvenForMissingSessions() {
        var sessions = sessions(1, 100, 10);
        assertThrows(NullPointerException.class, () -> sessions.open(null, RESIDENT, AVATAR, WORLD, 0));
        assertThrows(NullPointerException.class, () -> sessions.open(PLAYER, null, AVATAR, WORLD, 0));
        assertThrows(NullPointerException.class, () -> sessions.open(PLAYER, RESIDENT, null, WORLD, 0));
        assertThrows(NullPointerException.class, () -> sessions.open(PLAYER, RESIDENT, AVATAR, null, 0));
        assertThrows(NullPointerException.class, () -> sessions.current(null, 0));
        assertThrows(NullPointerException.class, () -> sessions.admit(null, id(50), 0));
        assertThrows(NullPointerException.class, () -> sessions.admit(PLAYER, null, 0));
        assertThrows(NullPointerException.class, () -> sessions.complete(null, id(50), 0));
        assertThrows(NullPointerException.class, () -> sessions.complete(PLAYER, null, 0));
        assertThrows(NullPointerException.class, () -> sessions.isPending(null, id(50)));
        assertThrows(NullPointerException.class, () -> sessions.isPending(PLAYER, null));
        assertThrows(NullPointerException.class, () -> sessions.close(null, id(50)));
        assertThrows(NullPointerException.class, () -> sessions.close(PLAYER, null));
        assertThrows(NullPointerException.class, () -> sessions.removePlayer(null));
        assertThrows(NullPointerException.class, () -> sessions.removeAvatar(null));
        assertEquals(0, sessions.size());
    }

    @Test
    void ticketRejectsNullFieldsAndNegativeExpiry() {
        UUID[] fields = {PLAYER, RESIDENT, AVATAR, WORLD, id(50)};
        for (int i = 0; i < fields.length; i++) {
            UUID[] invalid = fields.clone();
            invalid[i] = null;
            assertThrows(NullPointerException.class, () -> new EncounterSessions.Ticket(
                    invalid[0], invalid[1], invalid[2], invalid[3], invalid[4], 100));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new EncounterSessions.Ticket(PLAYER, RESIDENT, AVATAR, WORLD, id(50), -1));
    }

    @Test
    void negativeTimeIsRejectedWithoutChangingPendingSession() {
        var sessions = sessions(1, 100, 10);
        var ticket = open(sessions, PLAYER, 0);
        for (long now : new long[]{Long.MIN_VALUE, -1}) {
            assertThrows(IllegalArgumentException.class,
                    () -> sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, now));
            assertThrows(IllegalArgumentException.class, () -> sessions.admit(PLAYER, ticket.token(), now));
            assertThrows(IllegalArgumentException.class, () -> sessions.complete(PLAYER, ticket.token(), now));
            assertThrows(IllegalArgumentException.class, () -> sessions.current(PLAYER, now));
        }
        assertEquals(ticket, sessions.current(PLAYER, 0).orElseThrow());
        assertTrue(sessions.isPending(PLAYER, ticket.token()));
    }

    @Test
    void overflowIsRejectedAndMaxLongDeadlineIsNotPrematurelyExpired() {
        var sessions = sessions(1, 10, 5);
        assertThrows(IllegalArgumentException.class,
                () -> sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, Long.MAX_VALUE - 9));
        assertEquals(0, sessions.size());
        var ticket = open(sessions, PLAYER, Long.MAX_VALUE - 10);
        assertEquals(Long.MAX_VALUE, ticket.expiresAtMillis());
        var ready = sessions.complete(PLAYER, ticket.token(), Long.MAX_VALUE - 1).orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> sessions.admit(PLAYER, ready.token(), Long.MAX_VALUE - 1));
        assertFalse(sessions.isPending(PLAYER, ready.token()));
        assertEquals(ready, sessions.current(PLAYER, Long.MAX_VALUE - 1).orElseThrow());
        assertTrue(sessions.current(PLAYER, Long.MAX_VALUE).isEmpty());

        var longCooldown = sessions(1, 1, 10);
        assertThrows(IllegalArgumentException.class,
                () -> longCooldown.open(PLAYER, RESIDENT, AVATAR, WORLD, Long.MAX_VALUE - 9));
        assertEquals(0, longCooldown.size());
    }

    @Test
    void slidingDeadlineRestartsIdleLimitOnEachChoiceButStaysIdleBounded() {
        var sessions = new EncounterSessions(2, 100, 10, sequence(), true);
        var initial = open(sessions, PLAYER, 0);
        var ready = sessions.complete(PLAYER, initial.token(), 5).orElseThrow();
        assertEquals(100, ready.expiresAtMillis());
        var chosen = sessions.admit(PLAYER, ready.token(), 90).orElseThrow();
        assertEquals(190, chosen.expiresAtMillis());
        var next = sessions.complete(PLAYER, chosen.token(), 95).orElseThrow();
        assertEquals(190, next.expiresAtMillis());
        assertTrue(sessions.current(PLAYER, 150).isPresent(), "alive past the original 100 ms deadline");
        assertTrue(sessions.current(PLAYER, 190).isEmpty(), "idle limit still applies");
    }

    @Test
    void defaultDeadlineIsNeverExtendedByAChoice() {
        var sessions = sessions(2, 100, 10);
        var initial = open(sessions, PLAYER, 0);
        var ready = sessions.complete(PLAYER, initial.token(), 5).orElseThrow();
        assertEquals(100, sessions.admit(PLAYER, ready.token(), 90).orElseThrow().expiresAtMillis());
        assertTrue(sessions.current(PLAYER, 100).isEmpty());
    }

    private static EncounterSessions sessions(int max, long ttl, long cooldown) {
        return new EncounterSessions(max, ttl, cooldown, sequence());
    }

    private static int retainedEntries(EncounterSessions sessions, String fieldName) throws Exception {
        var field = EncounterSessions.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object value = field.get(sessions);
        if (value instanceof Map<?, ?> map) return map.size();
        return ((Collection<?>) value).size();
    }

    private static EncounterSessions.Ticket open(EncounterSessions sessions, UUID player, long now) {
        return sessions.open(player, RESIDENT, AVATAR, WORLD, now).orElseThrow();
    }

    private static UUID id(long value) {
        return new UUID(0, value);
    }

    private static Supplier<UUID> sequence() {
        return new Supplier<>() {
            private long next = 100;
            @Override public UUID get() { return id(next++); }
        };
    }

    private static Supplier<UUID> tokens(UUID... tokens) {
        Iterator<UUID> values = Arrays.asList(tokens).iterator();
        return values::next;
    }

    @Test void hasOpenForSeesPendingAndReadyTicketsOfThatResidentOnly() {
        var sessions = new EncounterSessions(8, 90_000, 0, UUID::randomUUID);
        assertFalse(sessions.hasOpenFor(RESIDENT));
        var ticket = sessions.open(PLAYER, RESIDENT, AVATAR, WORLD, 0).orElseThrow();
        assertTrue(sessions.hasOpenFor(RESIDENT), "pending");
        assertFalse(sessions.hasOpenFor(id(99)));
        var ready = sessions.complete(PLAYER, ticket.token(), 1).orElseThrow();
        assertTrue(sessions.hasOpenFor(RESIDENT), "ready");
        sessions.close(PLAYER, ready.token());
        assertFalse(sessions.hasOpenFor(RESIDENT));
        assertThrows(NullPointerException.class, () -> sessions.hasOpenFor(null));
    }
}
