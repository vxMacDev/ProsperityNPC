package com.prosperitysmp.npc.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Small shared pieces for the API's own tests: Bukkit events that need no server, and a thread helper. */
public final class ApiTestSupport {
    private ApiTestSupport() { }

    /** A plain Bukkit event. */
    public static class BaseEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    /** A subclass of {@link BaseEvent}: a handler for the base type must receive it. */
    public static final class DerivedEvent extends BaseEvent { }

    /** An event unrelated to {@link BaseEvent}. */
    public static final class UnrelatedEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    /** Runs {@code body} on a new thread and waits for it; a throw on that thread fails the caller. */
    public static void onAnotherThread(Runnable body) {
        Throwable[] thrown = new Throwable[1];
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable failure) {
                thrown[0] = failure;
            }
        }, "api-test-other-thread");
        thread.start();
        try {
            thread.join(10_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        if (thread.isAlive()) throw new AssertionError("the helper thread did not finish");
        if (thrown[0] != null) throw new AssertionError("the helper thread threw", thrown[0]);
    }
}
