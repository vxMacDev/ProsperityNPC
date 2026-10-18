package com.prosperitysmp.npc.property.guard;

import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.PropertyBackend;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Whether the selected property backend can keep ownership still while a payment holds a home, and so whether
 * payments, moves and payouts may start. READY(mode, identity) or UNAVAILABLE(reason).
 *
 * <p>ARM (a write hook) is READY exactly when the ownership fence exists, and its payouts are always allowed: that is
 * today's behaviour. Any other backend starts UNAVAILABLE, becomes READY when it is bound and its guard is
 * installed, and allows payouts only after the startup ownership pass reads every enrolled home. A guard of none,
 * or a backend that cannot tell a recreated region from the old one, follows {@code property.when-unguarded}.
 * Once a backend fails it stays UNAVAILABLE for the rest of the run. Fields are read from any thread.
 */
public final class PropertyGuard {
    public static final String WHEN_UNGUARDED = "property.when-unguarded";
    public static final String PAYOUTS_HELD_REPLY =
            "Rent waits: the home plugin cannot block owner changes (property.when-unguarded=hold-payouts).";
    public static final String NOT_RECONCILED_REPLY =
            "Rent waits: the first ownership check since startup has not read every home yet.";

    /** Whether a recreated region with the same id gives a different token. */
    public enum Identity { STRONG, WEAK }

    /** The backend has guard handlers, but the plugin that registered it cannot be found to file them under. */
    public static final class AdapterPluginMissing extends IllegalStateException {
        public AdapterPluginMissing() { super("the plugin that registered the property backend was not found"); }
    }

    /**
     * The backend's {@code token-identity} capability. Only {@code object} and {@code generation} are STRONG; anything
     * else, including a missing or misspelled value, is WEAK, so payouts wait unless the opt-in allows them.
     */
    public static Identity identityOf(String tokenIdentity) {
        return "object".equals(tokenIdentity) || "generation".equals(tokenIdentity) ? Identity.STRONG : Identity.WEAK;
    }

    /** property.when-unguarded. */
    public enum WhenUnguarded {
        /** Rent and appreciation wait. The default. */
        HOLD_PAYOUTS,
        /** Rent and appreciation run; each payout already reads the owner fresh on the main thread just before it. */
        REREAD_BEFORE_PAYOUT;

        /** The setting's value; anything unknown or missing is the safe default. */
        public static WhenUnguarded parse(Object value) {
            if (value instanceof String text && "reread-before-payout".equals(text.trim().toLowerCase(Locale.ROOT)))
                return REREAD_BEFORE_PAYOUT;
            return HOLD_PAYOUTS;
        }

        /** True for a missing setting or one of the two values; false for anything that parse quietly ignores. */
        static boolean known(Object value) {
            if (value == null) return true;
            if (!(value instanceof String text)) return false;
            String v = text.trim().toLowerCase(Locale.ROOT);
            return "hold-payouts".equals(v) || "reread-before-payout".equals(v);
        }
    }

    private final boolean writeHook;
    private volatile GuardMode mode;
    private volatile Identity identity = Identity.STRONG;
    private volatile String reason;
    private volatile boolean failed;
    private volatile boolean reconciled;
    private volatile WhenUnguarded whenUnguarded = WhenUnguarded.HOLD_PAYOUTS;
    // The bound backend's available(); a lost backend (its plugin disabled) is not ready.
    private volatile BooleanSupplier available = () -> true;

    private PropertyGuard(boolean writeHook, GuardMode mode, String reason) {
        this.writeHook = writeHook;
        this.mode = mode;
        this.reason = reason;
        this.reconciled = writeHook;
    }

    /** ARM with its write hook registered: READY, payouts as today. */
    public static PropertyGuard writeHook() {
        return new PropertyGuard(true, GuardMode.WRITE_HOOK, "");
    }

    /** No guard yet: ARM whose hook did not register, or a backend that is not bound yet. */
    public static PropertyGuard waiting(String reason) {
        return new PropertyGuard(false, null, Objects.requireNonNull(reason));
    }

    /**
     * Main thread. Installs a bound non-ARM backend's guard: its installGuard, then its handlers filed under
     * {@code filedUnder} through the error wrapper, then its token identity. {@code filedUnder} is the adapter plugin
     * (so Bukkit drops the handlers when the adapter disables), the core plugin for a built-in backend, or null when
     * the adapter plugin cannot be found; a backend with handlers then fails instead of filing them under the core.
     * Any throw fails the guard through {@code failed}. {@code available} is read on every check.
     */
    public void install(PropertyBackend backend, OwnershipHooks hooks, Plugin filedUnder, PluginManager manager,
                        BooleanSupplier available, BiConsumer<String, Throwable> failed) {
        try {
            GuardResult result = backend.installGuard(hooks);
            if (result != null && result.isInstalled()) {
                var bindings = backend.guardBindings();
                if (!bindings.isEmpty()) {
                    if (filedUnder == null) throw new AdapterPluginMissing();
                    GuardBindingWrapper.register(manager, filedUnder, bindings, failed);
                }
            }
            installed(result, identityOf(backend.capabilities().value("token-identity")), available);
        } catch (RuntimeException | LinkageError failure) {
            failed.accept("guard install", failure);
        }
    }

    /** Main thread. A non-ARM backend's guard install finished. {@code available} is read on every check. */
    public void installed(GuardResult result, Identity identity, BooleanSupplier available) {
        if (failed) return;
        if (result == null || !result.isInstalled()) {
            fail(result == null ? "guard install returned nothing" : result.reason());
            return;
        }
        GuardMode installed = result.mode().orElseThrow();
        if (installed == GuardMode.WRITE_HOOK) {
            // Only ARM's own hook is a write hook here; another backend claiming one is not trusted with payouts.
            fail("a write hook is only supported for the built-in arm backend");
            return;
        }
        this.identity = Objects.requireNonNull(identity);
        this.available = Objects.requireNonNull(available);
        this.reason = "";
        this.mode = installed;
    }

    /** Any thread. The backend failed; it stays unavailable for the rest of the run. Never changes ARM's guard. */
    public void fail(String why) {
        if (writeHook) return;
        reason = why == null || why.isBlank() ? "backend failed" : why;
        failed = true;
        mode = null;
    }

    /** Main thread. The startup ownership pass read every enrolled home, none unreadable. */
    public void reconciled() {
        reconciled = true;
    }

    /**
     * Main thread. One startup ownership pass finished. Payouts may follow only when the enrolled homes loaded at
     * startup and none was unreadable: a failed load leaves an empty list that would otherwise pass. Returns whether
     * the pass counted.
     */
    public boolean startupPass(boolean homesLoaded, int unreadable) {
        if (!homesLoaded || unreadable != 0) return false;
        reconciled();
        return true;
    }

    /** Read at startup and on /npcadmin reload. */
    public void whenUnguarded(Object setting) {
        whenUnguarded(setting, line -> { });
    }

    /** Read at startup and on /npcadmin reload. A value it ignores gets one warning that names it. */
    public void whenUnguarded(Object setting, Consumer<String> warn) {
        whenUnguarded = WhenUnguarded.parse(setting);
        if (!WhenUnguarded.known(setting))
            warn.accept(WHEN_UNGUARDED + " '" + shown(setting) + "' is not hold-payouts or reread-before-payout; "
                    + "it is ignored and payouts follow hold-payouts.");
    }

    /** A config value for a log line: no control or section characters, at most 80 characters. */
    private static String shown(Object value) {
        String text = String.valueOf(value).replaceAll("[\\p{Cntrl}§]", "");
        return text.length() <= 80 ? text : text.substring(0, 80);
    }

    /** Payments, moves and debits may start. */
    public boolean ready() {
        if (failed || mode == null) return false;
        if (writeHook) return true;
        try {
            return available.getAsBoolean() && !failed && mode != null;
        } catch (RuntimeException | LinkageError unreadable) {
            return false;
        }
    }

    /** Rent and appreciation payouts may start. Always true for ARM's write hook. */
    public boolean payoutsAllowed() {
        if (writeHook) return true;
        if (!ready() || !reconciled) return false;
        if (mode == GuardMode.VETO_EVENTS && identity == Identity.STRONG) return true;
        return whenUnguarded == WhenUnguarded.REREAD_BEFORE_PAYOUT;
    }

    /** The reply when payouts wait; "" when they may run. */
    public String payoutRefusal() {
        if (payoutsAllowed()) return "";
        if (!ready()) return "Rent waits: the home plugin is unavailable (" + reason() + ").";
        if (!reconciled) return NOT_RECONCILED_REPLY;
        return PAYOUTS_HELD_REPLY;
    }

    /** True until the startup ownership pass has read every enrolled home. Never for ARM. */
    public boolean needsStartupReconcile() {
        return !writeHook && !reconciled;
    }

    public boolean isWriteHook() { return writeHook; }

    /** The installed mode while ready. */
    public Optional<GuardMode> mode() { return Optional.ofNullable(ready() ? mode : null); }

    public Identity identity() { return identity; }

    public WhenUnguarded whenUnguarded() { return whenUnguarded; }

    /** Why the guard is not ready; "" while ready. */
    public String reason() {
        if (ready()) return "";
        return failed || mode == null ? reason : "the backend is unavailable";
    }

    /** One line for diagnose. ARM reads exactly as before: "guard write-hook installed". */
    public String describe() {
        GuardMode current = mode;
        if (!ready() || current == null) return "guard unavailable (" + reason() + "; payments held)";
        String name = current.name().toLowerCase(Locale.ROOT).replace('_', '-');
        if (writeHook) return "guard " + name + " installed";
        return "guard " + name + " installed; identity " + identity.name().toLowerCase(Locale.ROOT) + "; payouts "
                + (payoutsAllowed() ? "allowed" : reconciled ? "held (" + WHEN_UNGUARDED + "=hold-payouts)"
                : "held until the startup ownership check reads every home");
    }
}
