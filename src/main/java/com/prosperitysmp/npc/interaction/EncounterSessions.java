package com.prosperitysmp.npc.interaction;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Pure encounter admission state. Main-thread-only: every call, including reads and
 * completions, must run on the owning thread. There are deliberately no locks.
 *
 * <p>Exactly one ticket per player is either pending or ready. A successful open is
 * already the initial admission: it starts the cooldown and returns a PENDING ticket
 * so the controller starts its initial DB read directly, without calling admit.
 * Only complete changes pending to ready, rotating the token without extending the
 * original expiry. Only admit changes ready to pending, preserving that token for
 * the callback and starting another cooldown. Duplicate actions/callbacks cannot
 * cross these transitions. Backend results still require controller revalidation of
 * player, resident, avatar, world and authorization; this class does not perform IO.
 *
 * <p>Expiry and cooldown deadlines are exclusive: now equal to expiry is expired;
 * now equal to a cooldown deadline is eligible. Timed calls prune all expired
 * sessions and elapsed cooldowns. Untimed reads describe the last observed state.
 * Callers must supply nondecreasing, nonnegative times, including after clear.
 *
 * <p>Cooldown history has at most twice maxSessions entries. Close, quit and despawn
 * retain live cooldowns; a full history fails closed instead of evicting them.
 * Collision tracking retains issued UUIDs only until their ticket's original
 * expiry, including tokens retired by complete, replacement, close or clear.
 * Collisions within that window fail closed without changing the current ticket.
 * Older tokens are not remembered: the supplier must produce cryptographically
 * unpredictable UUIDs with negligible reuse probability (as UUID.randomUUID does).
 * There is no lifetime issuance limit or ever-joined-player registry.
 *
 * <p>Null inputs (including a null supplied UUID) throw NullPointerException;
 * invalid bounds, negative/regressing times and deadline overflow throw
 * IllegalArgumentException. Supplier exceptions propagate without publishing a
 * replacement. Tracked UUID collisions and exhausted capacity return Optional.empty().
 */
public final class EncounterSessions {
    public record Ticket(UUID playerId, UUID residentId, UUID avatarId, UUID worldId,
                         UUID token, long expiresAtMillis) {
        public Ticket {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(residentId, "residentId");
            Objects.requireNonNull(avatarId, "avatarId");
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(token, "token");
            if (expiresAtMillis < 0) throw new IllegalArgumentException("Negative expiry");
        }
    }

    private record Session(Ticket ticket, boolean pending) { }
    private record TokenExpiry(UUID token, long expiresAtMillis) { }

    private final int maxSessions;
    private final int maxCooldowns;
    private final long ttlMillis;
    private final long cooldownMillis;
    private final boolean slideOnAdmit;
    private final Supplier<UUID> tokens;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> tokenExpiries = new HashMap<>();
    // Delayed completions can issue tokens with deadlines older than the last open.
    private final PriorityQueue<TokenExpiry> tokenExpiryQueue =
            new PriorityQueue<>(Comparator.comparingLong(TokenExpiry::expiresAtMillis));
    private long lastNowMillis;

    public EncounterSessions() {
        this(128, 90_000, 1_000, UUID::randomUUID);
    }

    /** slideOnAdmit: each accepted player choice restarts the idle limit instead of keeping the original expiry. */
    public EncounterSessions(boolean slideOnAdmit) {
        this(128, 90_000, 1_000, UUID::randomUUID, slideOnAdmit);
    }

    public EncounterSessions(int maxSessions, long ttlMillis, long cooldownMillis,
                             Supplier<UUID> tokens) {
        this(maxSessions, ttlMillis, cooldownMillis, tokens, false);
    }

    public EncounterSessions(int maxSessions, long ttlMillis, long cooldownMillis,
                             Supplier<UUID> tokens, boolean slideOnAdmit) {
        if (maxSessions <= 0 || maxSessions > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("Invalid session capacity");
        }
        if (ttlMillis <= 0 || cooldownMillis < 0) {
            throw new IllegalArgumentException("TTL must be positive and cooldown nonnegative");
        }
        this.maxSessions = maxSessions;
        this.maxCooldowns = maxSessions * 2;
        this.ttlMillis = ttlMillis;
        this.cooldownMillis = cooldownMillis;
        this.slideOnAdmit = slideOnAdmit;
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    /** Starts the initial read pending; a ready session may be replaced after cooldown. */
    public Optional<Ticket> open(UUID player, UUID resident, UUID avatar, UUID world, long nowMillis) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(resident, "resident");
        Objects.requireNonNull(avatar, "avatar");
        Objects.requireNonNull(world, "world");
        validateTime(nowMillis);
        long expiresAt = deadline(nowMillis, ttlMillis);
        long cooldownUntil = deadline(nowMillis, cooldownMillis);
        prune(nowMillis);
        Session old = sessions.get(player);
        if ((old != null && old.pending()) || (old == null && sessions.size() >= maxSessions)
                || !canStart(player)) {
            return Optional.empty();
        }
        UUID token = issueToken(expiresAt);
        if (token == null) return Optional.empty();
        Ticket ticket = new Ticket(player, resident, avatar, world, token, expiresAt);
        sessions.put(player, new Session(ticket, true));
        startCooldown(player, cooldownUntil);
        return Optional.of(ticket);
    }

    /** Admits a ready token once, preserving it as the identity of the pending work. */
    public Optional<Ticket> admit(UUID player, UUID token, long nowMillis) {
        requireIdentity(player, token);
        validateTime(nowMillis);
        long cooldownUntil = deadline(nowMillis, cooldownMillis);
        prune(nowMillis);
        Session session = matching(player, token);
        if (session == null || session.pending() || !canStart(player)) return Optional.empty();
        Ticket ticket = session.ticket();
        if (slideOnAdmit) {
            long extended = deadline(nowMillis, ttlMillis);
            if (extended > ticket.expiresAtMillis()) {
                ticket = new Ticket(ticket.playerId(), ticket.residentId(), ticket.avatarId(), ticket.worldId(),
                        ticket.token(), extended);
                tokenExpiries.put(ticket.token(), extended);
                tokenExpiryQueue.add(new TokenExpiry(ticket.token(), extended));
            }
        }
        sessions.put(player, new Session(ticket, true));
        startCooldown(player, cooldownUntil);
        return Optional.of(ticket);
    }

    /**
     * Publishes a fresh ready token only for exact, unexpired pending work. A token
     * collision leaves that work pending, never ready; the controller may
     * conditionally close it. No new cooldown or TTL starts here.
     */
    public Optional<Ticket> complete(UUID player, UUID token, long nowMillis) {
        requireIdentity(player, token);
        validateTime(nowMillis);
        prune(nowMillis);
        Session session = matching(player, token);
        if (session == null || !session.pending()) return Optional.empty();
        UUID nextToken = issueToken(session.ticket().expiresAtMillis());
        if (nextToken == null) return Optional.empty();
        Ticket old = session.ticket();
        Ticket next = new Ticket(old.playerId(), old.residentId(), old.avatarId(),
                old.worldId(), nextToken, old.expiresAtMillis());
        sessions.put(player, new Session(next, false));
        return Optional.of(next);
    }

    /** Returns pending as well as ready tickets for backend result revalidation. */
    public Optional<Ticket> current(UUID player, long nowMillis) {
        Objects.requireNonNull(player, "player");
        validateTime(nowMillis);
        prune(nowMillis);
        Session session = sessions.get(player);
        return session == null ? Optional.empty() : Optional.of(session.ticket());
    }

    /** No clock read or pruning; use current(player, now) first when checking expiry. */
    public boolean isPending(UUID player, UUID token) {
        requireIdentity(player, token);
        Session session = matching(player, token);
        return session != null && session.pending();
    }

    /** A stale close cannot erase a rotated or replacement ticket. */
    public void close(UUID player, UUID token) {
        requireIdentity(player, token);
        if (matching(player, token) != null) sessions.remove(player);
    }

    public void removePlayer(UUID player) {
        sessions.remove(Objects.requireNonNull(player, "player"));
    }

    public void removeAvatar(UUID avatar) {
        Objects.requireNonNull(avatar, "avatar");
        sessions.values().removeIf(session -> session.ticket().avatarId().equals(avatar));
    }

    /**
     * Read only, no clock read or pruning. True while any stored ticket, pending or ready, names this resident.
     * An expired ticket that was not pruned yet still counts, so the outfit swap waits rather than guesses.
     */
    public boolean hasOpenFor(UUID resident) {
        Objects.requireNonNull(resident, "resident");
        return sessions.values().stream().anyMatch(session -> session.ticket().residentId().equals(resident));
    }

    /** Invalidates every session, retaining live cooldowns and unexpired token protection. */
    public void clear() {
        sessions.clear();
    }

    /** Stored count as of the latest timed operation; this method does not read a clock. */
    public int size() {
        return sessions.size();
    }

    private Session matching(UUID player, UUID token) {
        Session session = sessions.get(player);
        return session != null && session.ticket().token().equals(token) ? session : null;
    }

    private boolean canStart(UUID player) {
        return !cooldowns.containsKey(player)
                && (cooldownMillis == 0 || cooldowns.size() < maxCooldowns);
    }

    private void startCooldown(UUID player, long until) {
        if (cooldownMillis != 0) cooldowns.put(player, until);
    }

    private UUID issueToken(long expiresAtMillis) {
        UUID token = Objects.requireNonNull(tokens.get(), "supplied token");
        if (tokenExpiries.putIfAbsent(token, expiresAtMillis) != null) return null;
        tokenExpiryQueue.add(new TokenExpiry(token, expiresAtMillis));
        return token;
    }

    private void validateTime(long nowMillis) {
        if (nowMillis < 0 || nowMillis < lastNowMillis) {
            throw new IllegalArgumentException("Time must be nonnegative and nondecreasing");
        }
    }

    private void prune(long nowMillis) {
        lastNowMillis = nowMillis;
        sessions.values().removeIf(session -> session.ticket().expiresAtMillis() <= nowMillis);
        cooldowns.values().removeIf(until -> until <= nowMillis);
        while (!tokenExpiryQueue.isEmpty() && tokenExpiryQueue.peek().expiresAtMillis() <= nowMillis) {
            TokenExpiry expired = tokenExpiryQueue.remove();
            tokenExpiries.remove(expired.token(), expired.expiresAtMillis());
        }
    }

    private static long deadline(long now, long duration) {
        try {
            return Math.addExact(now, duration);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Deadline overflow", overflow);
        }
    }

    private static void requireIdentity(UUID player, UUID token) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(token, "token");
    }
}
