package com.prosperitysmp.npc.nav;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

/**
 * Loads the chunks a staff scan or sweep needs, without generating anything, and holds a plugin
 * ticket on each while they are read. Main thread only: {@link #pump()} is called once per tick,
 * keeps at most {@code concurrency} loads in flight, and never loads more than {@code maxChunks}.
 * {@link #release()} removes every ticket and reports how many this plugin still holds, which must be 0.
 */
public final class NavChunkLoader {

    /** The world operations the loader needs; a fake stands in for them in tests. */
    public interface Source {
        /** Completes true if the chunk exists and is now loaded, false if it was never generated. */
        CompletableFuture<Boolean> load(int chunkX, int chunkZ);

        boolean addTicket(int chunkX, int chunkZ);

        void removeTicket(int chunkX, int chunkZ);

        /** How many chunk tickets this plugin currently holds in the world. */
        int ticketsHeld();
    }

    public static Source of(World world, Plugin plugin) {
        return new Source() {
            @Override public CompletableFuture<Boolean> load(int chunkX, int chunkZ) {
                return world.getChunkAtAsync(chunkX, chunkZ, false).thenApply(chunk -> chunk != null);
            }

            @Override public boolean addTicket(int chunkX, int chunkZ) {
                return world.addPluginChunkTicket(chunkX, chunkZ, plugin);
            }

            @Override public void removeTicket(int chunkX, int chunkZ) {
                world.removePluginChunkTicket(chunkX, chunkZ, plugin);
            }

            @Override public int ticketsHeld() {
                return world.getPluginChunkTickets().getOrDefault(plugin, List.of()).size();
            }
        };
    }

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    static int chunkX(long key) {
        return (int) (key >> 32);
    }

    static int chunkZ(long key) {
        return (int) key;
    }

    /** Every chunk key a block rectangle touches. */
    public static List<Long> chunksOf(int minX, int maxX, int minZ, int maxZ) {
        List<Long> keys = new ArrayList<>();
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                keys.add(key(cx, cz));
            }
        }
        return keys;
    }

    private final Source source;
    private final ArrayDeque<Long> pending = new ArrayDeque<>();
    private final List<Inflight> inflight = new ArrayList<>();
    private final List<Long> ticketed = new ArrayList<>();
    private final int concurrency;
    private final int skipped;
    private int absent;
    private boolean released;

    private record Inflight(long key, CompletableFuture<Boolean> result) { }

    public NavChunkLoader(Source source, Collection<Long> chunks, int concurrency, int maxChunks) {
        if (concurrency < 1 || maxChunks < 1) {
            throw new IllegalArgumentException("chunk loading limits must be positive");
        }
        this.source = source;
        this.concurrency = concurrency;
        TreeSet<Long> sorted = new TreeSet<>(chunks);
        int taken = 0;
        for (long key : sorted) {
            if (taken++ < maxChunks) {
                pending.add(key);
            }
        }
        skipped = Math.max(0, sorted.size() - maxChunks);
    }

    /** One tick of work. Returns true once nothing is pending or in flight. */
    public boolean pump() {
        if (released) {
            return true;
        }
        for (Iterator<Inflight> it = inflight.iterator(); it.hasNext(); ) {
            Inflight load = it.next();
            if (!load.result().isDone()) {
                continue;
            }
            it.remove();
            boolean loaded = !load.result().isCompletedExceptionally() && Boolean.TRUE.equals(load.result().join());
            if (loaded && source.addTicket(chunkX(load.key()), chunkZ(load.key()))) {
                ticketed.add(load.key());
            } else {
                absent++;
            }
        }
        while (inflight.size() < concurrency && !pending.isEmpty()) {
            long key = pending.removeFirst();
            inflight.add(new Inflight(key, source.load(chunkX(key), chunkZ(key))));
        }
        return pending.isEmpty() && inflight.isEmpty();
    }

    public int loaded() {
        return ticketed.size();
    }

    /** Chunks that do not exist (never generated) or failed to load. */
    public int absent() {
        return absent;
    }

    /** Chunks beyond {@code maxChunks}, which are not loaded. */
    public int skipped() {
        return skipped;
    }

    /** Removes every ticket taken (even if loads are still in flight) and returns the tickets still held. */
    public int release() {
        released = true;
        pending.clear();
        for (long key : ticketed) {
            source.removeTicket(chunkX(key), chunkZ(key));
        }
        ticketed.clear();
        // A load that completes after release is never ticketed, so it cannot leak a ticket.
        inflight.clear();
        return source.ticketsHeld();
    }

    List<Long> ticketedChunks() {
        return Collections.unmodifiableList(ticketed);
    }
}
