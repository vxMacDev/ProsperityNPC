package com.prosperitysmp.npc.economy;

import java.util.concurrent.CompletionException;

/**
 * A payment request stopped before any SQL row or transfer attempt existed for it, so nothing can have moved.
 * Thrown only at the points the payment runtimes name, all before their first write. Anything later stays a
 * recovery hold. The wrapped cause is what stopped it.
 */
final class NotStarted extends RuntimeException {
    private static final long serialVersionUID = 1L;

    NotStarted(Throwable cause) {
        super(null, cause, false, false);
    }

    /** Wraps what stopped a request, without the CompletionException a failed stage adds around it. */
    static NotStarted of(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        return new NotStarted(cause);
    }

    /** The NotStarted behind a stage failure, looking only through CompletionException wrappers, or null. */
    static NotStarted in(Throwable failure) {
        for (Throwable next = failure; next != null; next = next.getCause()) {
            if (next instanceof NotStarted stopped) return stopped;
            if (!(next instanceof CompletionException)) return null;
        }
        return null;
    }
}
