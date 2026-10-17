package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavChunkLoaderTest {
    /** A world whose chunk loads finish only when the test says so. */
    private static class FakeSource implements NavChunkLoader.Source {
        final List<CompletableFuture<Boolean>> started = new ArrayList<>();
        final Set<Long> tickets = new HashSet<>();
        int maxInFlight;

        @Override public CompletableFuture<Boolean> load(int cx, int cz) {
            CompletableFuture<Boolean> f = new CompletableFuture<>();
            started.add(f);
            maxInFlight = Math.max(maxInFlight, (int) started.stream().filter(x -> !x.isDone()).count());
            return f;
        }

        @Override public boolean addTicket(int cx, int cz) {
            return tickets.add(NavChunkLoader.key(cx, cz));
        }

        @Override public void removeTicket(int cx, int cz) {
            tickets.remove(NavChunkLoader.key(cx, cz));
        }

        @Override public int ticketsHeld() {
            return tickets.size();
        }
    }

    private static List<Long> chunks(int count) {
        List<Long> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) keys.add(NavChunkLoader.key(i, -i));
        return keys;
    }

    @Test
    void neverMoreLoadsInFlightThanTheConcurrencyAndEveryLoadedChunkIsTicketed() {
        FakeSource source = new FakeSource();
        NavChunkLoader loader = new NavChunkLoader(source, chunks(10), 3, 256);
        int ticks = 0;
        while (!loader.pump()) {
            assertTrue(source.started.stream().filter(f -> !f.isDone()).count() <= 3);
            // Finish the oldest unfinished load each tick.
            source.started.stream().filter(f -> !f.isDone()).findFirst().ifPresent(f -> f.complete(true));
            assertTrue(++ticks < 100);
        }
        assertEquals(3, source.maxInFlight);
        assertEquals(10, loader.loaded());
        assertEquals(10, source.tickets.size());
        assertEquals(0, loader.absent());
        assertEquals(0, loader.release());
        assertTrue(source.tickets.isEmpty());
    }

    @Test
    void chunksBeyondTheCapAreNotLoaded() {
        FakeSource source = new FakeSource();
        NavChunkLoader loader = new NavChunkLoader(source, chunks(10), 4, 6);
        assertEquals(4, loader.skipped());
        while (!loader.pump()) source.started.forEach(f -> f.complete(true));
        assertEquals(6, source.started.size());
        assertEquals(6, loader.loaded());
        loader.release();
    }

    @Test
    void chunksThatDoNotExistOrFailAreCountedAndNeverTicketed() {
        FakeSource source = new FakeSource();
        NavChunkLoader loader = new NavChunkLoader(source, chunks(4), 4, 256);
        loader.pump();
        source.started.get(0).complete(true);
        source.started.get(1).complete(false);
        source.started.get(2).completeExceptionally(new IllegalStateException("io"));
        source.started.get(3).complete(true);
        assertTrue(loader.pump());
        assertEquals(2, loader.loaded());
        assertEquals(2, loader.absent());
        assertEquals(2, source.tickets.size());
        assertEquals(0, loader.release());
    }

    @Test
    void releaseRemovesEveryTicketEvenWithLoadsStillInFlightAndALateFinishLeaksNothing() {
        FakeSource source = new FakeSource();
        NavChunkLoader loader = new NavChunkLoader(source, chunks(6), 2, 256);
        loader.pump();
        source.started.get(0).complete(true);
        assertFalse(loader.pump());
        assertEquals(1, source.tickets.size());
        assertEquals(0, loader.release(), "tickets_remaining is zero after release");
        source.started.forEach(f -> f.complete(true));
        assertTrue(loader.pump(), "a released loader does nothing more");
        assertTrue(source.tickets.isEmpty());
    }

    @Test
    void aTicketTheWorldKeepsShowsUpInTheRemainingCount() {
        FakeSource source = new FakeSource() {
            @Override public void removeTicket(int cx, int cz) {
                // a world that refuses to drop the ticket
            }
        };
        NavChunkLoader loader = new NavChunkLoader(source, chunks(1), 1, 8);
        loader.pump();
        source.started.get(0).complete(true);
        loader.pump();
        assertEquals(1, loader.release(), "the count is read from the world, so a leak cannot be hidden");
    }

    @Test
    void chunksOfAndKeysRoundTrip() {
        assertEquals(List.of(NavChunkLoader.key(-1, -1), NavChunkLoader.key(-1, 0), NavChunkLoader.key(0, -1),
                NavChunkLoader.key(0, 0)), NavChunkLoader.chunksOf(-3, 3, -3, 3));
        assertEquals(1, NavChunkLoader.chunksOf(16, 31, 0, 15).size());
        assertEquals(-5, NavChunkLoader.chunkX(NavChunkLoader.key(-5, 7)));
        assertEquals(7, NavChunkLoader.chunkZ(NavChunkLoader.key(-5, 7)));
        assertThrows(IllegalArgumentException.class, () -> new NavChunkLoader(new FakeSource(), chunks(1), 0, 8));
        assertThrows(IllegalArgumentException.class, () -> new NavChunkLoader(new FakeSource(), chunks(1), 1, 0));
    }
}
