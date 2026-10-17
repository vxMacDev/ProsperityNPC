package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.alert.StaffAlerts;
import com.prosperitysmp.npc.economy.TransferProof.Leg;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.economy.TransferProof.Refusal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watches payments that were refused before any money moved, so a decline never hides a fault. Logs each one
 * (event, leg, reason and the cause's class name, never its message), alerts staff when the reason looks like a
 * fault, and alerts when one runtime refuses several payments in a row with nothing paid in between. Not having
 * the money is a normal answer and does not count toward that streak. Main thread only. Moves no money.
 * It only reports: none of its methods ever throws, because the payment that called it still has evidence to
 * store and leases to release. A report it cannot make is written to the server log instead.
 */
final class RefusalMonitor {
    /** Refusals in a row, in one runtime, before staff are told. */
    static final int REPEATED = 5;

    @FunctionalInterface interface Alerts { void raise(StaffAlerts.Kind kind, String subject, String text); }

    private final Alerts alerts;
    private final Consumer<String> log;
    private final BooleanSupplier mainThread;
    private final Map<String, Integer> streaks = new HashMap<>();

    RefusalMonitor(Alerts alerts, Consumer<String> log, BooleanSupplier mainThread) {
        this.alerts = Objects.requireNonNull(alerts);
        this.log = Objects.requireNonNull(log);
        this.mainThread = Objects.requireNonNull(mainThread);
    }

    /** One leg refused before its money call. */
    void refused(String runtime, String reference, Leg leg, Observation o) {
        if (!onMain()) return;
        Refusal reason = o == null ? null : o.refusedBeforeCall();
        if (reason == null) {
            quietly(() -> log.accept("Payment refusal report without a refusal: " + runtime + " " + reference + " " + leg));
            return;
        }
        quietly(() -> log.accept("Payment refused before any money moved: " + runtime + " " + reference + " " + leg
                + " reason " + reason + " cause " + o.refusalCause()));
        if (reason.unexpected())
            quietly(() -> alerts.raise(StaffAlerts.Kind.PAYMENT_REFUSED_UNEXPECTEDLY, runtime + "-" + reference,
                    "Payment refused before any money moved (" + reason + ", " + o.refusalCause() + "). Reference " + reference + "."));
        if (reason != Refusal.INSUFFICIENT_FUNDS) count(runtime);
    }

    /** A request stopped before any payment row or transfer existed. */
    void notStarted(String runtime, String reference, Throwable cause) {
        if (!onMain()) return;
        String name = TransferProof.causeName(cause);
        quietly(() -> log.accept("Payment not started: " + runtime + " " + reference + " cause " + name));
        if (!TransferProof.expectedRefusal(cause))
            quietly(() -> alerts.raise(StaffAlerts.Kind.PAYMENT_REFUSED_UNEXPECTEDLY, runtime + "-" + reference,
                    "Payment stopped before it started (" + name + "). Reference " + reference + "."));
        count(runtime);
    }

    /** A payment went through, which ends the runtime's streak. */
    void paid(String runtime) {
        if (onMain()) streaks.remove(runtime);
    }

    int streak(String runtime) {
        if (!mainThread.getAsBoolean()) throw new IllegalStateException("Refusal monitor requires main thread");
        return streaks.getOrDefault(runtime, 0);
    }

    private void count(String runtime) {
        int now = streaks.merge(runtime, 1, Integer::sum);
        if (now >= REPEATED)
            quietly(() -> alerts.raise(StaffAlerts.Kind.PAYMENT_REFUSALS_REPEATED, runtime,
                    now + " payments in a row were refused before any money moved, none paid in between (" + runtime + ")."));
    }

    /** The streaks are main-thread state. A report from another thread is logged and otherwise ignored. */
    private boolean onMain() {
        boolean main;
        try { main = mainThread.getAsBoolean(); }
        catch (RuntimeException | LinkageError unknown) { main = false; }
        if (!main) quietly(() -> Logger.getLogger(RefusalMonitor.class.getName()).log(Level.WARNING,
                "Payment refusal report off the main thread was not counted"));
        return main;
    }

    /** Runs one report. A report that fails is logged, never thrown into the payment that made it. */
    static void quietly(Runnable report) {
        try {
            report.run();
        } catch (RuntimeException | LinkageError failed) {
            try {
                Logger.getLogger(RefusalMonitor.class.getName()).log(Level.WARNING,
                        "Payment refusal report failed: " + failed.getClass().getName());
            } catch (RuntimeException | LinkageError ignored) {
                // Nothing else can be done here; the payment still settles.
            }
        }
    }
}
