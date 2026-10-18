package com.prosperitysmp.npc.api;

import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * One Bukkit event handler of a property backend's guard (API 1.2). ProsperityNPC registers it under the adapter
 * plugin, so Bukkit removes it when the adapter disables, and runs the handler inside its own error wrapper: a
 * throw marks the backend unavailable and holds payments.
 */
public final class GuardBinding {
    private final Class<? extends Event> type;
    private final EventPriority priority;
    private final boolean ignoreCancelled;
    private final Consumer<Event> handler;

    private GuardBinding(Class<? extends Event> type, EventPriority priority, boolean ignoreCancelled, Consumer<Event> handler) {
        this.type = Objects.requireNonNull(type);
        this.priority = Objects.requireNonNull(priority);
        this.ignoreCancelled = ignoreCancelled;
        this.handler = Objects.requireNonNull(handler);
    }

    /** The handler receives only events of {@code type} (subclasses included). */
    public static <E extends Event> GuardBinding of(Class<E> type, EventPriority priority, boolean ignoreCancelled,
                                                    Consumer<? super E> handler) {
        Objects.requireNonNull(handler);
        return new GuardBinding(type, priority, ignoreCancelled, event -> handler.accept(type.cast(event)));
    }

    public Class<? extends Event> type() { return type; }

    public EventPriority priority() { return priority; }

    public boolean ignoreCancelled() { return ignoreCancelled; }

    /** Calls the handler. Only ProsperityNPC's wrapper calls this. */
    public void handle(Event event) { handler.accept(event); }

    @Override public String toString() { return type.getSimpleName() + "@" + priority; }
}
