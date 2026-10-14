package com.prosperitysmp.npc.interaction;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Main-thread presentation boundary: external UI callbacks may invalidate a conversation. */
final class ResidentPresentation {
    private ResidentPresentation() { }

    static void show(BooleanSupplier authorized, BooleanSupplier render, Runnable revoke,
                     Runnable closeOwned, Runnable fallback, Consumer<Throwable> reportFailure) {
        if (!authorized.getAsBoolean()) {
            revoke.run();
            closeOwned.run();
            return;
        }
        boolean shown = false;
        try {
            shown = render.getAsBoolean();
        } catch (RuntimeException | LinkageError failure) {
            reportFailure.accept(failure);
        }
        if (!authorized.getAsBoolean()) {
            revoke.run();
            closeOwned.run();
            return;
        }
        if (!shown) {
            closeOwned.run();
            if (authorized.getAsBoolean()) fallback.run();
        }
    }
}
