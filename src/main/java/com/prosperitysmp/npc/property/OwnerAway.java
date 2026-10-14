package com.prosperitysmp.npc.property;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Vacation protection (housing.vacation-days, 0 = off). A property whose owner has not been seen for more than
 * that many days is "owner away" for the economy day: the daily phases leave it out, so no money moves either way
 * and nothing decays. Each property is decided once per economy day on the main thread, the first time any phase
 * or view asks, and that decision holds for the rest of the day. A returning owner resumes on the next day's cycle.
 * Workers only ever receive the resulting id set; they never read Bukkit.
 *
 * <p>Durable mode ({@link #requireDurable()}, set by the plugin once the database is up): the first decision per
 * property, owner and economy day is saved in {@code npc_owner_away_decisions} and adopted from there, so a restart or
 * reload later that day keeps it. Daily phases and recurring charges then read saved decisions only ({@link #held}):
 * a property whose decision is not saved yet is left out for now (no money moves) until the next save, and until
 * today's decisions have been saved at all those paths refuse. While the flag is 0 nothing is saved, read or held:
 * exactly the old behavior. Views ({@link #isAway}) still answer at once from memory.
 */
public final class OwnerAway {
    public static final String CONFIG = "housing.vacation-days";
    public static final int MAX_DAYS = 365;
    public static final String LABEL = "Owner away";
    public static final String DESK_NOTE = "Owner away. Rent, utility bills, incidents and resident moods are paused until the next daily cycle.";
    public static final String PAY_NOTE = "Owner away for today. Utility bills and rent start again at the next daily cycle.";
    private static final long DAY_MILLIS = 86_400_000L;
    /** Most decisions kept for one day. A daily save refuses above this; the day's decisions are never wiped mid-day. */
    public static final int MAX_CACHED = 4096;

    /** Last-sighting facts read on the main thread. A time of 0 or less means unknown. */
    public record Seen(boolean online, long lastSeenMillis, long lastPlayedMillis) { }
    private record Decision(UUID owner, boolean away, boolean durable) { }
    /** One property's decision for one owner on one economy day, as saved or to be saved. */
    public record Choice(UUID propertyId, UUID ownerUuid, boolean away) {
        public Choice { Objects.requireNonNull(propertyId); Objects.requireNonNull(ownerUuid); }
    }

    private final IntSupplier configuredDays;
    private final Supplier<LocalDate> today;
    private final LongSupplier nowMillis;
    private final Function<UUID, Seen> sighting;
    private final Map<UUID, Decision> decided = new HashMap<>(); // main thread only
    private LocalDate day;
    private boolean durableRequired; // main thread only
    private LocalDate durableDay;    // the day whose decisions were last saved and adopted

    public OwnerAway(IntSupplier configuredDays, Supplier<LocalDate> today, LongSupplier nowMillis, Function<UUID, Seen> sighting) {
        this.configuredDays = Objects.requireNonNull(configuredDays);
        this.today = Objects.requireNonNull(today);
        this.nowMillis = Objects.requireNonNull(nowMillis);
        this.sighting = Objects.requireNonNull(sighting);
    }

    /** Config value clamped to 0..365. Anything else is off. */
    public static int days(int configured) { return Math.max(0, Math.min(MAX_DAYS, configured)); }

    /** Online is never away. Otherwise last-seen (falling back to last-played) must be more than {@code days} old. Unknown is not away. */
    public static boolean away(Seen seen, long nowMillis, int days) {
        if (days <= 0 || seen == null || seen.online()) return false;
        long last = seen.lastSeenMillis() > 0 ? seen.lastSeenMillis() : seen.lastPlayedMillis();
        if (last <= 0 || last > nowMillis) return false;
        return nowMillis - last > days * DAY_MILLIS;
    }

    /** Shared by every skipped phase: away properties are left out, order kept. No away ids returns the same list. */
    public static List<Property> present(List<Property> properties, Set<UUID> away) {
        Objects.requireNonNull(properties); Objects.requireNonNull(away);
        if (away.isEmpty()) return properties;
        return properties.stream().filter(p -> !away.contains(p.propertyId())).toList();
    }

    /** Main thread. The ids among these properties whose owner is away today. Always empty while the flag is 0. */
    public Set<UUID> awayAmong(Collection<Property> properties) {
        Objects.requireNonNull(properties);
        if (days(configuredDays.getAsInt()) == 0) return Set.of();
        var result = new HashSet<UUID>();
        for (Property p : properties) if (p != null && isAway(p)) result.add(p.propertyId());
        return Set.copyOf(result);
    }

    /** Main thread. Today's decision for this property, made now if no phase or view has asked yet today. */
    public boolean isAway(Property property) {
        Objects.requireNonNull(property);
        int days = days(configuredDays.getAsInt());
        if (days == 0 || property.ownerUuid() == null) return false;
        return decide(property, days).away();
    }

    /** True while housing.vacation-days is above 0. */
    public boolean enabled() { return days(configuredDays.getAsInt()) > 0; }

    /** Main thread. Daily phases and recurring charges must read saved decisions only from now on. */
    public void requireDurable() { durableRequired = true; }

    /** Main thread. Whether today's decisions have been saved and read back (always true when not required or off). */
    public boolean settled() {
        if (!durableRequired || !enabled()) return true;
        roll();
        return day.equals(durableDay);
    }

    /**
     * Main thread. The ids to leave out of a daily phase or recurring charge today: away homes, plus (durable mode)
     * homes whose decision is not saved yet. Empty Optional: durable mode and today's decisions are not saved yet, so
     * the caller must not run. Off: always an empty set, as before. Not durable: identical to {@link #awayAmong}.
     */
    public Optional<Set<UUID>> held(Collection<Property> properties) {
        Objects.requireNonNull(properties);
        int days = days(configuredDays.getAsInt());
        if (days == 0) return Optional.of(Set.of());
        if (!durableRequired) return Optional.of(awayAmong(properties));
        if (!settled()) return Optional.empty();
        var result = new HashSet<UUID>();
        for (Property p : properties) {
            if (p == null || p.ownerUuid() == null) continue;
            Decision d = decide(p, days);
            if (d.away() || !d.durable()) result.add(p.propertyId());
        }
        return Optional.of(Set.copyOf(result));
    }

    /** Main thread. One property's {@link #held} answer; unsaved or unsettled counts as held. Off: never held. */
    public boolean holds(Property property) {
        Objects.requireNonNull(property);
        var held = held(List.of(property));
        return held.isEmpty() || held.get().contains(property.propertyId());
    }

    /**
     * Main thread. Decides every enrolled property not yet decided today and returns the decisions that are not saved
     * yet, for {@link #confirm} after the worker saved them. Off or ownerless: nothing.
     */
    public List<Choice> unsaved(Collection<Property> enrolled) {
        Objects.requireNonNull(enrolled);
        int days = days(configuredDays.getAsInt());
        if (days == 0) return List.of();
        if (enrolled.size() > MAX_CACHED) throw new IllegalStateException("Too many enrolled properties for owner-away decisions");
        var out = new ArrayList<Choice>();
        for (Property p : enrolled) {
            if (p == null || p.ownerUuid() == null) continue;
            Decision d = decide(p, days);
            if (!d.durable()) out.add(new Choice(p.propertyId(), p.ownerUuid(), d.away()));
        }
        return List.copyOf(out);
    }

    /**
     * Main thread. Adopts the saved decisions (the first decision of the day wins, even over this process's own) and
     * marks the day settled. False when the flag is off or the economy day changed meanwhile; nothing is adopted then.
     */
    public boolean confirm(LocalDate savedDay, Collection<Choice> saved) {
        Objects.requireNonNull(savedDay); Objects.requireNonNull(saved);
        if (!enabled()) return false;
        roll();
        if (!day.equals(savedDay)) return false;
        for (Choice c : saved) {
            Decision current = decided.get(c.propertyId());
            // Sold while saving: the old owner's row is not the new owner's decision.
            if (current != null && !current.owner().equals(c.ownerUuid())) continue;
            if (current == null && decided.size() >= MAX_CACHED) continue;
            decided.put(c.propertyId(), new Decision(c.ownerUuid(), c.away(), true));
        }
        durableDay = savedDay;
        return true;
    }

    private void roll() {
        LocalDate current = today.get();
        if (!current.equals(day)) { decided.clear(); day = current; }
    }

    private Decision decide(Property property, int days) {
        roll();
        Decision known = decided.get(property.propertyId());
        if (known != null && known.owner().equals(property.ownerUuid())) return known;
        boolean away;
        try { away = away(sighting.apply(property.ownerUuid()), nowMillis.getAsLong(), days); }
        catch (RuntimeException unreadable) { away = false; } // Unknown keeps the normal behavior.
        Decision made = new Decision(property.ownerUuid(), away, false);
        // Never wiped mid-day. Over the cap a decision is answered but not kept, so it never counts as saved.
        if (known != null || decided.size() < MAX_CACHED) decided.put(property.propertyId(), made);
        return made;
    }
}
