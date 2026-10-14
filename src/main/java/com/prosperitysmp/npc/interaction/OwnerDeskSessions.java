package com.prosperitysmp.npc.interaction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Main-thread-only admission and identity-scoped cleanup for private property views. */
final class OwnerDeskSessions {
    static final int LIMIT = 32;
    static final long READ_MS = 10_000, VIEW_MS = 60_000, COOLDOWN_MS = 2_000;
    record Ticket(UUID token, UUID actor, UUID world, String region, long openedMs) { }
    private final Map<UUID, Ticket> current = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    Optional<Ticket> open(UUID actor, UUID world, String region, long now) {
        if (!attempt(actor, now)) return Optional.empty();
        return admit(actor, world, region, now);
    }

    /** Charge denied attempts too, before any ARM/WG lookup. */
    boolean attempt(UUID actor, long now) {
        if (actor == null || now < 0) throw new IllegalArgumentException("Invalid attempt");
        cooldowns.entrySet().removeIf(e -> now >= e.getValue());
        if (cooldowns.containsKey(actor) || cooldowns.size() >= 256) return false;
        cooldowns.put(actor, Math.addExact(now, COOLDOWN_MS));
        return true;
    }

    Optional<Ticket> admit(UUID actor, UUID world, String region, long now) {
        if (actor == null || world == null || region == null || region.isBlank() || now < 0)
            throw new IllegalArgumentException("Invalid owner desk request");
        if (cooldowns.getOrDefault(actor, -1L) != now + COOLDOWN_MS
                || (!current.containsKey(actor) && current.size() >= LIMIT)) return Optional.empty();
        Ticket next = new Ticket(UUID.randomUUID(), actor, world, region, now);
        current.put(actor, next);
        return Optional.of(next);
    }

    boolean valid(Ticket ticket, long now) {
        return ticket != null && current.get(ticket.actor()) == ticket
                && now >= ticket.openedMs() && now - ticket.openedMs() < VIEW_MS;
    }
    Optional<Ticket> rotate(Ticket ticket, long now) {
        if (!valid(ticket, now)) return Optional.empty();
        Ticket next = new Ticket(UUID.randomUUID(), ticket.actor(), ticket.world(), ticket.region(), ticket.openedMs());
        current.put(ticket.actor(), next);
        return Optional.of(next);
    }
    /** Sweep rule: a view with no data yet is retired once its read window passes; a paused view is already delivered. */
    static boolean undelivered(boolean hasData, boolean paused, boolean withinReadWindow) {
        return !hasData && !paused && !withinReadWindow;
    }
    boolean mayDeliver(Ticket ticket, long now) {
        return valid(ticket, now) && now - ticket.openedMs() < READ_MS;
    }
    boolean remove(Ticket ticket) {
        return ticket != null && current.get(ticket.actor()) == ticket && current.remove(ticket.actor()) != null;
    }
    Optional<Ticket> current(UUID actor) { return Optional.ofNullable(current.get(actor)); }
    List<Ticket> all() { return List.copyOf(current.values()); }
    void quit(UUID actor) {
        current.remove(actor);
        cooldowns.remove(actor);
    }
    void clear() {
        current.clear();
        cooldowns.clear();
    }
}
