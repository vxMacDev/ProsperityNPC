package com.prosperitysmp.npc.resident;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Pure replay model. Trusted adapters supply events after verifying the referenced source; never clients. */
public final class RelationshipMemory {
    public static final int MAX_EVENTS = 4096;
    public static final int MAX_VISIBLE_MEMORIES = 8;
    public static final int MAX_POSITIVE_PER_DAY = 4;
    public enum Kind { INTRODUCTION, REPAIR_COMPLETED, OBLIGATION_KEPT, OBLIGATION_BROKEN }
    public enum Stage { STRANGER, ACQUAINTANCE, TRUSTED, ESTABLISHED }
    public record Event(UUID residentId, UUID playerId, String sourceRef, Kind kind,
                        Instant occurredAt, LocalDate economyDay) {
        public Event {
            Objects.requireNonNull(residentId);
            Objects.requireNonNull(playerId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(occurredAt);
            Objects.requireNonNull(economyDay);
            if (sourceRef == null || !sourceRef.matches("[a-z][a-z0-9:_./-]{0,159}")) {
                throw new IllegalArgumentException("Invalid memory source reference");
            }
        }
    }
    public record State(UUID residentId, UUID playerId, Stage stage, int reliability,
                        int distinctPositiveDays, List<Event> recent) {
        public State {
            Objects.requireNonNull(residentId);
            Objects.requireNonNull(playerId);
            Objects.requireNonNull(stage);
            recent = List.copyOf(recent);
            if (reliability < 0 || reliability > 100 || distinctPositiveDays < 0
                    || distinctPositiveDays > MAX_EVENTS || recent.size() > MAX_VISIBLE_MEMORIES
                    || reliability > distinctPositiveDays * MAX_POSITIVE_PER_DAY) {
                throw new IllegalArgumentException("Invalid relationship summary bounds");
            }
            Stage expected = recent.isEmpty() ? Stage.STRANGER
                    : reliability >= 28 && distinctPositiveDays >= 7 ? Stage.ESTABLISHED
                    : reliability >= 12 && distinctPositiveDays >= 3 ? Stage.TRUSTED : Stage.ACQUAINTANCE;
            if (stage != expected || (recent.isEmpty() && (reliability != 0 || distinctPositiveDays != 0))) {
                throw new IllegalArgumentException("Inconsistent relationship stage");
            }
            Set<String> sources = new HashSet<>();
            for (Event event : recent) {
                if (!event.residentId().equals(residentId) || !event.playerId().equals(playerId)
                        || !sources.add(event.sourceRef())) {
                    throw new IllegalArgumentException("Inconsistent relationship evidence identity");
                }
            }
        }
    }

    private RelationshipMemory() { }

    public static State replay(UUID resident, UUID player, Collection<Event> input) {
        Objects.requireNonNull(resident);
        Objects.requireNonNull(player);
        if (input.size() > MAX_EVENTS) throw new IllegalArgumentException("Memory replay requires a checkpoint");
        Map<String, Event> unique = new HashMap<>();
        for (Event event : input) {
            if (!event.residentId().equals(resident) || !event.playerId().equals(player)) {
                throw new IllegalArgumentException("Mixed relationship identities");
            }
            Event old = unique.putIfAbsent(event.sourceRef(), event);
            if (old != null && !old.equals(event)) throw new IllegalArgumentException("Memory source payload conflict");
        }
        List<Event> events = new ArrayList<>(unique.values());
        events.sort(Comparator.comparing(Event::occurredAt).thenComparing(Event::sourceRef));
        Map<LocalDate, Integer> gains = new HashMap<>();
        var positiveDays = new HashSet<LocalDate>();
        int score = 0;
        boolean met = false;
        LocalDate lastDay = null;
        for (Event event : events) {
            if (lastDay != null && event.economyDay().isBefore(lastDay)) {
                throw new IllegalArgumentException("Economy day regressed within chronological memory");
            }
            lastDay = event.economyDay();
            met = true;
            int raw = switch (event.kind()) {
                case INTRODUCTION -> 0;
                case REPAIR_COMPLETED -> 4;
                case OBLIGATION_KEPT -> 3;
                case OBLIGATION_BROKEN -> -6;
            };
            int gain = raw;
            if (raw > 0) {
                int used = gains.getOrDefault(event.economyDay(), 0);
                gain = Math.min(raw, MAX_POSITIVE_PER_DAY - used);
                gains.put(event.economyDay(), used + gain);
                if (gain > 0) positiveDays.add(event.economyDay());
            }
            score = Math.clamp(score + gain, 0, 100);
        }
        Stage stage = !met ? Stage.STRANGER
                : score >= 28 && positiveDays.size() >= 7 ? Stage.ESTABLISHED
                : score >= 12 && positiveDays.size() >= 3 ? Stage.TRUSTED : Stage.ACQUAINTANCE;
        int from = Math.max(0, events.size() - MAX_VISIBLE_MEMORIES);
        List<Event> recent = new ArrayList<>(events.subList(from, events.size()));
        Collections.reverse(recent);
        return new State(resident, player, stage, score, positiveDays.size(), recent);
    }
}
