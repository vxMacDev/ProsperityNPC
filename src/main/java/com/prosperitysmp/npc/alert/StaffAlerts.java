package com.prosperitysmp.npc.alert;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bounded staff alert queue. Off (alerts.enabled false): nothing is queued or logged. On: each
 * (kind, subject) raises at most once per economy day, is logged once at WARN, and waits in a queue of
 * at most {@value #MAX_QUEUED}; the oldest entry is dropped and counted when the queue is full.
 * Thread-safe; draining is meant for the main thread. Moves no money and holds no plugin state.
 */
public final class StaffAlerts {
    public enum Kind { PAYMENT_HELD_FOR_REVIEW, RESERVE_BELOW_FLOOR, SERVER_CAP_NEAR, DAILY_PHASE_FAILED,
        ARM_HOOK_UNAVAILABLE, RENT_PROPERTY_HELD,
        /** An item repair hand-in is held for the offline tool (item-repairs). */
        ITEM_REPAIR_HELD,
        /** A contract or partnership hand-over is held for staff review (/npctown contract-resolve). */
        HANDOVER_HELD,
        /** A paid order's save before delivery could not be read back repeatedly; nothing was given or held. */
        COMMERCE_SAVE_CHECK,
        /** A paid order delivery is held for staff after it started (save marker on). */
        COMMERCE_DELIVERY_HELD }

    public static final int MAX_QUEUED = 64;
    public static final int MAX_DRAIN = 20;
    private static final int MAX_TEXT = 200;
    private static final int MAX_SEEN = 1024;

    private final BooleanSupplier enabled;
    private final Supplier<LocalDate> today;
    private final Consumer<String> warn;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Set<String> seen = new HashSet<>();
    private LocalDate seenDay;
    private long dropped;

    public StaffAlerts(BooleanSupplier enabled, Supplier<LocalDate> today, Consumer<String> warn) {
        this.enabled = Objects.requireNonNull(enabled);
        this.today = Objects.requireNonNull(today);
        this.warn = Objects.requireNonNull(warn);
    }

    /** Returns true when the alert was queued (enabled and not already raised for this subject today). */
    public boolean raise(Kind kind, String subject, String text) {
        Objects.requireNonNull(kind);
        final String line, key;
        final LocalDate day;
        try {
            if (!enabled.getAsBoolean()) return false;
            day = today.get();
        } catch (RuntimeException unavailable) {
            return false;
        }
        line = oneLine(text);
        key = kind.name() + "|" + oneLine(subject == null ? "" : subject);
        synchronized (this) {
            if (!day.equals(seenDay)) {
                seen.clear();
                seenDay = day;
            }
            if (seen.size() >= MAX_SEEN) seen.clear();
            if (!seen.add(key)) return false;
            if (queue.size() >= MAX_QUEUED) {
                queue.pollFirst();
                dropped++;
            }
            queue.addLast(line);
        }
        try {
            warn.accept("Staff alert " + kind.name() + ": " + line);
        } catch (RuntimeException logging) {
            /* The queue entry stands. */
        }
        return true;
    }

    /** Removes and returns up to max (clamped 1..20) one-line texts, oldest first. */
    public List<String> drain(int max) {
        int limit = Math.max(1, Math.min(MAX_DRAIN, max));
        var out = new ArrayList<String>();
        synchronized (this) {
            if (dropped > 0) {
                out.add(dropped + " older staff alert" + (dropped == 1 ? " was" : "s were") + " dropped.");
                dropped = 0;
            }
            while (out.size() < limit && !queue.isEmpty()) out.add(queue.pollFirst());
        }
        return List.copyOf(out);
    }

    public synchronized int size() {
        return queue.size();
    }

    public synchronized long droppedCount() {
        return dropped;
    }

    /** True when at least 90 percent of a positive cap is used. */
    public static boolean capNear(long spentCents, long capCents) {
        return capCents > 0 && spentCents >= 0 && spentCents >= Math.ceilDiv(Math.multiplyExact(capCents, 9L), 10L);
    }

    private static String oneLine(String text) {
        String s = text == null ? "" : text.replaceAll("[\\r\\n\\u2014\\u2013]+", " ").replaceAll("\\s{2,}", " ").trim();
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }
}
