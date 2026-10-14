package com.prosperitysmp.npc.render;

import java.util.function.BooleanSupplier;

/** Tick-counted maintenance; monotonic time is used only for failed edge retries. */
final class RenderCadence {
    private final int maintenanceInterval;
    private int remaining;

    RenderCadence(long maintenanceInterval) {
        this.maintenanceInterval = Math.clamp(maintenanceInterval, 1, 20);
        this.remaining = this.maintenanceInterval;
    }

    boolean maintenanceDue() {
        if (--remaining > 0) return false;
        remaining = maintenanceInterval;
        return true;
    }

    static final class EdgeRetry {
        private static final long SPACING_MS = 1_000;
        private long retryAt;
        private boolean pending;
        private int failures;

        boolean pending() { return pending; }

        boolean attempt(long now, int maxFails, BooleanSupplier navigate,
                        BooleanSupplier cancel, Runnable warning) {
            if (pending && now < retryAt) return true;
            pending = false;
            return navigate.getAsBoolean() || failed(now, maxFails, cancel, warning);
        }

        boolean failed(long now, int maxFails, BooleanSupplier cancel, Runnable warning) {
            if (!cancel.getAsBoolean()) return false;
            failures++;
            pending = true;
            retryAt = now + SPACING_MS;
            warning.run();
            return failures < maxFails;
        }

        void arrived() {
            failures = 0;
            pending = false;
        }
    }
}
