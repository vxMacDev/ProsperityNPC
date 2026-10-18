package com.prosperitysmp.npc.economy.accounting;

import com.prosperitysmp.npc.economy.AccountingProjection;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * accounting.sink=internal: the plugin acknowledges a fresh snapshot of the current generation itself, and nothing
 * else. The projection's timing rules (TTL, refresh, invalidate) are unchanged, so a pending refresh still holds.
 */
class InternalSinkTest {
    private static final Instant AT = Instant.parse("2026-10-14T16:00:00Z");

    private static AccountingProjection.Snapshot snapshot(long generation, long cents) {
        return new AccountingProjection.Snapshot(generation, AT.plusSeconds(generation), "UTC",
                List.of(new AccountingProjection.Group("2026-10-14", "xfer-to-public",
                        List.of(new AccountingProjection.SourceTotal("npc-property-rent", cents)))), List.of());
    }

    @Test void selfAcknowledgesTheFreshSnapshotOnly() {
        assertTrue(new InternalSink().selfAcknowledges());
        assertFalse(new SkriptSink(() -> true).selfAcknowledges());
        // A changed snapshot with nothing acknowledged: internal acknowledges its own generation, Skript does not.
        assertEquals(7, AccountingProjection.acknowledgementAfterRefresh(snapshot(6, 25), -1, snapshot(7, 30), true));
        assertEquals(-1, AccountingProjection.acknowledgementAfterRefresh(snapshot(6, 25), -1, snapshot(7, 30), false));
        // A failed read acknowledges nothing, for either sink.
        assertEquals(-1, AccountingProjection.acknowledgementAfterRefresh(snapshot(6, 25), 6, null, true));
        assertEquals(-1, AccountingProjection.acknowledgementAfterRefresh(snapshot(6, 25), 6, null, false));
        // Skript keeps an acknowledgement only for unchanged data, as before.
        assertEquals(7, AccountingProjection.acknowledgementAfterRefresh(snapshot(6, 25), 6, snapshot(7, 25), false));
    }

    /**
     * The real projection with its server parts replaced: its constructor needs the running plugin, so the object is
     * made without it and given a clock, the system zone, its sink, and a worker that keeps each refresh pending until
     * the test delivers it. The main-thread check answers true. Everything else is AccountingProjection's own code.
     */
    private static final class Projection implements AutoCloseable {
        final AccountingProjection projection;
        final List<Runnable> pending = new ArrayList<>();
        private final Field serverField;
        private final Object oldServer;

        Projection(AccountingSink sink) throws Exception {
            serverField = Bukkit.class.getDeclaredField("server");
            serverField.setAccessible(true);
            oldServer = serverField.get(null);
            serverField.set(null, Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                    (p, m, a) -> {
                        if (m.getName().equals("isPrimaryThread")) return true;
                        throw new AssertionError(m.getName());
                    }));
            try {
                Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
                unsafeField.setAccessible(true);
                Object unsafe = unsafeField.get(null);
                Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
                projection = (AccountingProjection) allocate.invoke(unsafe, AccountingProjection.class);
                set("sink", sink);
                set("clock", Clock.systemUTC());
                set("zone", ZoneId.systemDefault());
                set("completionLock", new Object());
                set("worker", new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1)) {
                    @Override public void execute(Runnable command) { pending.add(command); }
                });
                set("acknowledged", -1L);
            } catch (Exception | Error failure) {
                serverField.set(null, oldServer);
                throw failure;
            }
        }

        void set(String name, Object value) throws Exception {
            Field field = AccountingProjection.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(projection, value);
        }

        Object get(String name) throws Exception {
            Field field = AccountingProjection.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(projection);
        }

        void tick() throws Exception {
            Method tick = AccountingProjection.class.getDeclaredMethod("tick");
            tick.setAccessible(true);
            tick.invoke(projection);
        }

        long generation() throws Exception { return (long) get("generation"); }

        /** The refresh that was submitted for {@code token} finishes with {@code snapshot} (null: the read failed). */
        void finish(long token, AccountingProjection.Snapshot value) throws Exception {
            Class<?> completion = Class.forName(AccountingProjection.class.getName() + "$Completion");
            var make = completion.getDeclaredConstructor(long.class, long.class, AccountingProjection.Snapshot.class);
            make.setAccessible(true);
            set("completion", make.newInstance(token, System.nanoTime(), value));
        }

        @Override public void close() throws Exception { serverField.set(null, oldServer); }
    }

    private static AccountingProjection.Snapshot fresh(long generation) {
        return new AccountingProjection.Snapshot(generation, Instant.now().minusSeconds(1), "UTC",
                List.of(new AccountingProjection.Group("2026-10-14", "xfer-to-public",
                        List.of(new AccountingProjection.SourceTotal("npc-property-rent", 25)))), List.of());
    }

    @Test void readyIsFalseWhileARefreshIsPendingAndNeverForAnOlderGeneration() throws Exception {
        try (var p = new Projection(new InternalSink())) {
            var projection = p.projection;
            assertFalse(projection.ready(), "nothing read yet");
            p.tick();
            assertEquals(1, p.pending.size(), "a refresh was submitted");
            long first = p.generation();
            assertFalse(projection.ready(), "pending: nothing is acknowledged before the read finishes");
            p.finish(first, fresh(first));
            p.tick();
            assertTrue(projection.ready(), "positive control: the internal sink acknowledges its own fresh snapshot");

            projection.invalidate();
            assertFalse(projection.ready(), "a payment invalidates the snapshot");
            assertFalse(projection.acknowledge(first), "the old generation cannot be acknowledged again");
            p.tick();
            assertEquals(2, p.pending.size(), "a new refresh was submitted");
            long second = p.generation();
            assertFalse(projection.ready(), "still pending");
            p.finish(first, fresh(first));
            p.tick();
            assertFalse(projection.ready(), "a late read of an older generation is ignored");
            projection.invalidate();
            p.finish(second, fresh(second));
            p.tick();
            assertFalse(projection.ready(), "a read that a newer payment overtook is ignored");

            long third = p.generation();
            p.finish(third, null);
            p.tick();
            assertFalse(projection.ready(), "a failed read acknowledges nothing");
        }
    }

    @Test void theSkriptSinkWaitsForItsAcknowledgementAfterTheRefresh() throws Exception {
        try (var p = new Projection(new SkriptSink(() -> true))) {
            var projection = p.projection;
            p.tick();
            long token = p.generation();
            assertFalse(projection.ready());
            assertFalse(projection.acknowledge(token), "nothing to acknowledge while the refresh is pending");
            p.finish(token, fresh(token));
            p.tick();
            assertFalse(projection.ready(), "Skript has not acknowledged it yet");
            assertTrue(projection.acknowledge(token));
            assertTrue(projection.ready());
        }
    }

    @Test void healthTextNamesTheMode() {
        assertEquals("Accounting projection: acknowledged by the plugin (accounting.sink=internal; no Skript totals are published)",
                new InternalSink().healthLine(true));
        assertTrue(new InternalSink().healthLine(false).contains("accounting.sink=internal"));
        assertEquals("internal", new InternalSink().id());
        assertSame(null, AccountingSink.forId("nope", () -> true), "an unknown value selects no sink");
        assertInstanceOf(InternalSink.class, AccountingSink.forId("internal", () -> true));
        assertInstanceOf(SkriptSink.class, AccountingSink.forId("skript", () -> true));
    }
}
