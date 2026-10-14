package com.prosperitysmp.npc.render;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One shared pause window per rendered resident. Everyone talking to the resident shares it, and reopening a
 * conversation cannot restart it. Under {@link Policy#LEGACY} (the default) the window is fixed: 90 s long, and the
 * next one opens 120 s after this one opened. Only when {@link Policy#renews()} is set does an admitted choice push
 * the deadline out, to at most {@code windowStart + capMs}, shared by every visitor. The cooldown always runs from
 * the window's final deadline. A premium relocation walk never renews, and {@link #stopRenewing} pulls a deadline
 * renewed before the walk began back to the first 90 s. {@link #release} never resets the window.
 */
final class ConversationPause {
    /** Read once at startup. {@link #of} returns LEGACY when renewal is off, so the cap and cooldown keys are inert. */
    record Policy(boolean renews, long readMs, long capMs, long cooldownMs) {
        static final Policy LEGACY = new Policy(false, 90_000, 90_000, 30_000);

        static Policy of(boolean renews, int capSeconds, int cooldownSeconds) {
            if (!renews) return LEGACY;
            return new Policy(true, 90_000, Math.clamp(capSeconds, 90, 300) * 1_000L,
                    Math.clamp(cooldownSeconds, 30, 600) * 1_000L);
        }
    }

    private final Policy policy;
    private final Set<UUID> visitors = new HashSet<>();
    private long windowStart;
    private long deadline;
    private long nextWindow;

    ConversationPause() { this(Policy.LEGACY); }

    ConversationPause(Policy policy) { this.policy = Objects.requireNonNull(policy); }

    long acquire(UUID visitor, long now) { return acquire(visitor, now, true); }

    /**
     * Returns the window's deadline, or 0 when the resident cannot pause now. {@code renew} false (a premium
     * relocation walk) never extends the deadline, even when the policy renews.
     */
    long acquire(UUID visitor, long now, boolean renew) {
        if (now >= nextWindow) {
            visitors.clear();
            windowStart = now;
            deadline = now + policy.readMs();
            nextWindow = deadline + policy.cooldownMs();
        }
        // A window renewed before a premium relocation walk began (even one released since) gives the walk only its
        // first 90 s: pull it back here too, not only when the walk is adopted mid-stop.
        if (!renew) stopRenewing();
        if (now >= deadline) return 0;
        visitors.add(visitor);
        if (policy.renews() && renew) {
            long renewed = Math.min(now + policy.readMs(), windowStart + policy.capMs());
            if (renewed > deadline) {
                deadline = renewed;
                nextWindow = deadline + policy.cooldownMs();
            }
        }
        return deadline;
    }

    /**
     * A premium relocation walk began during this window. A deadline that was already renewed is pulled back to
     * {@code windowStart + readMs}, and the cooldown runs from there. Returns the deadline. Does nothing under LEGACY.
     */
    long stopRenewing() {
        if (policy.renews() && deadline > windowStart + policy.readMs()) {
            deadline = windowStart + policy.readMs();
            nextWindow = deadline + policy.cooldownMs();
        }
        return deadline;
    }

    boolean capReached(long now) { return capReached(now, true); }

    /**
     * True only while a renewing window is active and can no longer move: it hit the cap, or renewal is
     * blocked for this resident ({@code renew} false). Always false under LEGACY.
     */
    boolean capReached(long now, boolean renew) {
        return policy.renews() && now < deadline && (!renew || deadline >= windowStart + policy.capMs());
    }

    boolean release(UUID visitor) {
        visitors.remove(visitor);
        return visitors.isEmpty();
    }
}
