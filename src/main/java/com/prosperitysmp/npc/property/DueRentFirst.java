package com.prosperitysmp.npc.property;

import java.time.LocalDate;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Staff entry points that read asking rent (/npcadmin rent and /npcadmin match) apply today's due asking-rent
 * changes first, the same step the daily cycle runs before its phases, so neither ever pays or matches on a rent
 * the owner already changed for today. Daily rent reservations are unique per property and day, so a batch on
 * the old rate could not be corrected later the same day.
 *
 * housing.rent-change-delay-days 0 with nothing pending (rentChangesDue false): the batch starts directly,
 * exactly as before. Otherwise the step runs first; if it is busy, fails, or leaves the cache needing
 * reconciliation, no batch starts. Every gate is read again on main after the step's async hop.
 */
public final class DueRentFirst {
    private DueRentFirst() { }

    /** Main thread only. Each call reads live state; nothing is cached across the async hop. */
    public interface Steps {
        /** PropertyService.rentChangesDue(): the delay flag is on, a change is pending, or the cache is suspect. */
        boolean rentChangesDue();
        /** The daily cycle (or another staff rent-change step) is running and owns the step right now. */
        boolean stepOwnedElsewhere();
        /** Marks this staff step running, so the daily cycle does not start in the middle of it. */
        void stepRunning(boolean running);
        /** The economy day the rent-change step applies (the daily cycle's day). */
        LocalDate today();
        /** PropertyService.applyDueRentChanges. False: busy, nothing started, done is not called. */
        boolean applyDueRentChanges(LocalDate day, BiConsumer<Boolean, String> done);
        /** Plugin enabled, system switch on and the caller's authority still held. Re-read after the hop. */
        boolean stillAllowed();
        /** Starts the batch itself (it keeps all its own gates). False: not started. */
        boolean start(Consumer<String> reply);
    }

    static final String OWNED_ELSEWHERE = "Daily cycle or rent change step in progress; it applies rent changes first. No batch started.";
    static final String STEP_BUSY = "Rent change step busy; no batch started.";
    static final String STEP_FAILED = "Due rent changes were not confirmed; no batch started.";
    static final String DAY_CHANGED = "Economy day changed during the rent change step; no batch started. Run it again.";
    static final String STOPPED = "Stopped after the rent change step; no batch started.";

    /**
     * Main thread. True: accepted, and reply receives the outcome (the batch's own replies, or why it did not
     * start). False: refused at once and nothing started; reply may carry the reason.
     * notStarted is replied when the batch itself refuses after the rent-change step succeeded.
     */
    public static boolean request(Steps steps, Consumer<String> reply, String notStarted) {
        Objects.requireNonNull(steps); Objects.requireNonNull(reply); Objects.requireNonNull(notStarted);
        if (!steps.rentChangesDue()) return steps.start(reply);
        if (steps.stepOwnedElsewhere()) { say(reply, OWNED_ELSEWHERE); return false; }
        final LocalDate day = Objects.requireNonNull(steps.today());
        steps.stepRunning(true);
        final boolean started;
        try {
            started = steps.applyDueRentChanges(day, (ok, message) -> {
                steps.stepRunning(false);
                say(reply, message);
                if (!Boolean.TRUE.equals(ok)) { say(reply, STEP_FAILED); return; }
                // Re-read after the hop: the day, the plugin, the switches and the caller's authority.
                if (!day.equals(steps.today())) { say(reply, DAY_CHANGED); return; }
                if (!steps.stillAllowed()) { say(reply, STOPPED); return; }
                if (!steps.start(reply)) say(reply, notStarted);
            });
        } catch (RuntimeException failure) { steps.stepRunning(false); throw failure; }
        if (!started) { steps.stepRunning(false); say(reply, STEP_BUSY); return false; }
        return true;
    }

    private static void say(Consumer<String> reply, String message) {
        try { reply.accept(message); } catch (RuntimeException ignored) { /* Caller gone; the outcome is unchanged. */ }
    }
}
