package com.prosperitysmp.npc.api;

import java.util.Objects;
import java.util.Optional;

/** What {@link PropertyBackend#installGuard} achieved. */
public final class GuardResult {
    private final GuardMode mode;
    private final String reason;

    private GuardResult(GuardMode mode, String reason) {
        this.mode = mode;
        this.reason = reason;
    }

    public static GuardResult installed(GuardMode mode) { return new GuardResult(Objects.requireNonNull(mode), ""); }

    public static GuardResult failed(String reason) { return new GuardResult(null, Objects.requireNonNull(reason)); }

    public boolean isInstalled() { return mode != null; }

    /** The installed mode; empty when the install failed. */
    public Optional<GuardMode> mode() { return Optional.ofNullable(mode); }

    /** Why the install failed; "" when installed. */
    public String reason() { return reason; }
}
