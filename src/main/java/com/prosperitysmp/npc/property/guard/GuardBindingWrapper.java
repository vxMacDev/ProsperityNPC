package com.prosperitysmp.npc.property.guard;

import com.prosperitysmp.npc.api.GuardBinding;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Registers a backend's guard handlers with Bukkit through core code. Each handler runs inside a catch: a throw
 * (a LinkageError from a changed vendor plugin, a bug in the adapter) marks the backend unavailable for the rest of
 * the run instead of disappearing into the vendor plugin's event log. Handlers are registered under the adapter
 * plugin, so Bukkit removes them when the adapter disables.
 */
public final class GuardBindingWrapper {
    /** The object Bukkit files the handlers under; it has no handler methods of its own. */
    public static final class GuardListener implements Listener { }

    private GuardBindingWrapper() { }

    /** The executor for one binding. Events of another type are ignored. */
    public static EventExecutor executor(GuardBinding binding, BiConsumer<String, Throwable> failed) {
        Objects.requireNonNull(binding);
        Objects.requireNonNull(failed);
        return (listener, event) -> {
            if (!binding.type().isInstance(event)) return;
            try {
                binding.handle(event);
            } catch (Throwable failure) {
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                failed.accept(binding.type().getName(), failure);
            }
        };
    }

    /** Registers every binding under {@code owner}. Returns the listener object they are filed under. */
    public static Listener register(PluginManager manager, Plugin owner, List<GuardBinding> bindings,
                                    BiConsumer<String, Throwable> failed) {
        var listener = new GuardListener();
        for (GuardBinding binding : bindings) {
            manager.registerEvent(binding.type(), listener, binding.priority(), executor(binding, failed), owner,
                    binding.ignoreCancelled());
        }
        return listener;
    }
}
