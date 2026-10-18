package com.prosperitysmp.npc.pieces;

import com.prosperitysmp.npc.api.Piece;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A third-party piece chosen by id in onEnable but only bound once the server has finished starting, so every
 * plugin has had its chance to register. Until then it reports unavailable. It binds at most once per run, and
 * once it is lost (its plugin disabled or its service unregistered) it stays unavailable until a restart.
 * It never swaps to another piece while the server runs. Main thread only.
 */
public final class Deferred<T extends Piece> {
    public enum State { WAITING, BOUND, UNAVAILABLE }

    private final String setting;
    private final String id;
    private final Consumer<String> log;
    private State state = State.WAITING;
    private T piece;
    private String owner = "";
    private String reason = "waiting for server startup";

    /**
     * @param setting the config key, for example "property.backend", used in log lines
     * @param id the configured id
     */
    public Deferred(String setting, String id, Consumer<String> log) {
        this.setting = Objects.requireNonNull(setting);
        this.id = Objects.requireNonNull(id);
        this.log = Objects.requireNonNull(log);
    }

    public String id() { return id; }

    public State state() { return state; }

    /** The bound piece, only while it is bound and not lost. */
    public Optional<T> current() { return state == State.BOUND ? Optional.of(piece) : Optional.empty(); }

    public boolean available() { return state == State.BOUND; }

    /** The plugin that registered the bound piece; "" before binding. */
    public String owner() { return owner; }

    /** Why the piece is not available; "" while it is bound. */
    public String reason() { return reason; }

    /**
     * The server finished loading. A startup binds once; a reload binds nothing, because pieces that registered
     * again after a reload are new objects the payment code has never checked.
     */
    public void serverLoaded(boolean startup, Supplier<PieceSelector.Selection<T>> select) {
        if (!startup) {
            log.accept(setting + " '" + id + "': server reload seen; restart required");
            if (state == State.WAITING) unavailable("server reloaded before binding; restart required");
            return;
        }
        if (state != State.WAITING) return;
        PieceSelector.Selection<T> selection = select.get();
        if (selection == null || !selection.found()) {
            unavailable(selection == null ? "not found" : selection.reason());
            log.accept(setting + " '" + id + "' unavailable: " + reason);
            return;
        }
        piece = selection.selected();
        owner = selection.owner();
        reason = "";
        state = State.BOUND;
    }

    /** A service of this kind was registered. After selection it is logged and never used. */
    public void registered(String pluginName) {
        if (state != State.WAITING)
            log.accept(setting + ": a piece from " + pluginName + " registered after selection; restart required");
    }

    /** A service of this kind was unregistered. When it is the bound piece, this piece is lost for the run. */
    public void unregistered(Object provider) {
        if (state == State.BOUND && provider == piece) lose("its service was unregistered");
    }

    /** A plugin was disabled. When it registered the bound piece, this piece is lost for the run. */
    public void pluginDisabled(String pluginName) {
        if (state == State.BOUND && owner.equals(pluginName)) lose("plugin " + pluginName + " was disabled");
    }

    private void lose(String why) {
        unavailable(why + "; restart required");
        log.accept(setting + " '" + id + "' is no longer available: " + reason);
    }

    private void unavailable(String why) {
        state = State.UNAVAILABLE;
        reason = why;
    }
}
