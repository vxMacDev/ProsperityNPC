package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.nav.NavGraph;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Completion-time snapshot coverage, using real immutable records and a fixed caller clock. */
class RenderSnapshotScopeTest {
    private static final UUID WORLD = new UUID(0, 1);
    private static final RenderManager.GraphKey A = new RenderManager.GraphKey(WORLD, "a");
    private static final RenderManager.GraphKey B = new RenderManager.GraphKey(WORLD, "b");
    private static final RenderManager.GraphKey C = new RenderManager.GraphKey(WORLD, "c");
    private static final NavGraph GRAPH_A = NavGraph.builder().addNode(1, 415, 72, -128, "home").build();
    private static final NavGraph GRAPH_B = NavGraph.builder().addNode(2, 415, 72, -123, "work").build();
    private static final RenderManager.Snapshot ONLY_A = snapshot(Map.of(A, GRAPH_A), Set.of(A));
    private static final RenderManager.Snapshot ONLY_B = snapshot(Map.of(B, GRAPH_B), Set.of(B));
    private static final RenderManager.Snapshot BOTH = snapshot(Map.of(A, GRAPH_A, B, GRAPH_B), Set.of(A, B));

    @Test
    void bCompletesBeforeWarmAAdmissionDrainAndCannotEvictA() throws Exception {
        assertWarmAIsRetained(true);
    }

    @Test
    void bCompletesAfterAIsAlreadySpawnableAndCannotEvictA() throws Exception {
        assertWarmAIsRetained(false);
    }

    private static void assertWarmAIsRetained(boolean completesBeforeAdmission) throws Exception {
        try (var fixture = new ReadFixture()) {
            var cache = fixture.cache;
            seed(fixture, ONLY_A, 1000, 1500);
            var launchScope = new Scope(Set.of(B));
            var currentScope = new Scope(Set.of(A, B));
            var bOnly = fixture.read(ONLY_B);
            var forbidden = fixture.read(ONLY_B);
            cache.expedite();
            cache.refresh(bOnly, 3000, launchScope);
            bOnly.awaitEntered();
            assertEquals(0, launchScope.calls.get(), "submission must not evaluate acceptance");
            if (completesBeforeAdmission) bOnly.finish();

            // A is warm: admission needs no explicit expedite, even though the pending read only covers B.
            assertSame(ONLY_A, cache.value(3500));
            assertSame(GRAPH_A, cache.value(3500).graphs().get(A));
            cache.refresh(forbidden, 3500, currentScope);
            if (!completesBeforeAdmission) {
                assertEquals(0, currentScope.calls.get());
                assertSame(ONLY_A, cache.value(3500), "A is already spawnable before B completes");
                bOnly.finish();
            }
            await(() -> currentScope.calls.get() == 1, () -> cache.refresh(forbidden, 4000, currentScope));
            assertEquals(0, launchScope.calls.get(), "completion must use CURRENT scope, not submission scope");
            assertSame(ONLY_A, cache.value(4000), "scope rejection must retain the original snapshot object");
            assertNull(cache.failure(), "missing scope is not a database failure");
            assertTrue(cache.shouldRefresh(false), "rejected scope must schedule maintenance without new demand");
            long gate = 3000 + RenderManager.ReadCache.EXPEDITED_MS;
            for (int i = 0; i < 100; i++) cache.refresh(forbidden, gate - 1, currentScope);
            assertEquals(2, fixture.calls.get());
            assertEquals(1, currentScope.calls.get(), "a consumed rejection must not be repeatedly evaluated");

            var superset = fixture.read(BOTH);
            cache.refresh(superset, gate, currentScope);
            superset.awaitEntered();
            assertSame(ONLY_A, cache.value(gate));
            assertEquals(0, forbidden.calls.get(), "scope rejection must not queue prior callables");
            for (int i = 0; i < 100; i++) cache.refresh(forbidden, gate + 500, currentScope);
            assertEquals(1, fixture.active.get());
            assertEquals(1, currentScope.calls.get());
            superset.finish();
            await(() -> cache.value(6000) == BOTH, () -> cache.refresh(forbidden, 6000, currentScope));
            assertEquals(2, currentScope.calls.get());
            assertIdleUntilRegularRefresh(fixture, forbidden, BOTH, currentScope, 6000, 3);
        }
    }

    @Test
    void scopeRejectionAfterGateStartsSingleSupersetReadAtSameCompletionClock() throws Exception {
        try (var fixture = new ReadFixture()) {
            var cache = fixture.cache;
            seed(fixture, ONLY_A, 1000, 1000);
            var bOnly = fixture.read(ONLY_B);
            var forbidden = fixture.read(ONLY_B);
            var currentScope = new Scope(Set.of(A, B));
            cache.expedite();
            cache.refresh(bOnly, 3000, new Scope(Set.of(B)));
            bOnly.awaitEntered();
            for (int i = 0; i < 100; i++) cache.refresh(forbidden, 40_000, currentScope);
            assertSame(ONLY_A, cache.value(40_000));
            assertEquals(2, fixture.calls.get());
            assertEquals(1, fixture.active.get(), "neither expired timer may overlap the pending read");
            assertEquals(0, currentScope.calls.get());
            bOnly.finish();
            var superset = fixture.read(BOTH);
            await(superset::entered, () -> cache.refresh(superset, 40_000, currentScope));
            assertEquals(1, currentScope.calls.get());
            assertSame(ONLY_A, cache.value(40_000));
            assertNull(cache.failure());
            assertEquals(0, forbidden.calls.get());
            superset.finish();
            await(() -> cache.value(40_000) == BOTH,
                    () -> cache.refresh(forbidden, 40_000, currentScope));
            assertIdleUntilRegularRefresh(fixture, forbidden, BOTH, currentScope, 40_000, 3);
        }
    }

    @Test
    void rejectedScopeKeepsOriginalAgeAndCannotResurrectExpiredA() throws Exception {
        try (var fixture = new ReadFixture()) {
            var cache = fixture.cache;
            seed(fixture, ONLY_A, 1000, 5000);
            var bOnly = fixture.read(ONLY_B);
            var forbidden = fixture.read(ONLY_B);
            var currentScope = new Scope(Set.of(A, B));
            cache.refresh(bOnly, 119_500, new Scope(Set.of(B)));
            bOnly.awaitEntered();
            assertSame(ONLY_A, cache.value(119_600));
            cache.refresh(forbidden, 119_600, currentScope);
            bOnly.finish();
            await(() -> currentScope.calls.get() == 1,
                    () -> cache.refresh(forbidden, 120_500, currentScope));
            assertSame(ONLY_A, cache.value(120_999));
            assertNull(cache.value(121_000), "rejection must not move A's read-start expiry forward");
            cache.refresh(forbidden, 121_000, currentScope);
            assertNull(cache.value(121_000), "draining rejected scope must not revive expired data");
            assertNull(cache.failure());

            var superset = fixture.read(BOTH);
            cache.refresh(superset, 121_500, currentScope);
            superset.awaitEntered();
            assertNull(cache.value(121_500), "expired A stays unavailable until an accepted read completes");
            assertEquals(0, forbidden.calls.get());
            superset.finish();
            await(() -> cache.value(122_000) == BOTH,
                    () -> cache.refresh(forbidden, 122_000, currentScope));
            assertSame(BOTH, cache.value(241_499));
            assertNull(cache.value(241_500), "accepted superset age also starts at submission");
            assertEquals(3, fixture.calls.get());
            assertEquals(1, fixture.maxActive.get());
        }
    }

    @Test
    void explicitlyCheckedNegativeAProvidesCoverageWithoutAPositiveGraph() throws Exception {
        try (var fixture = new ReadFixture()) {
            var cache = fixture.cache;
            seed(fixture, ONLY_A, 1000, 1000);
            var negativeA = snapshot(Map.of(B, GRAPH_B), Set.of(A, B));
            var pending = fixture.read(negativeA);
            var forbidden = fixture.read(ONLY_B);
            var currentScope = new Scope(Set.of(A, B));
            cache.expedite();
            cache.refresh(pending, 3000, new Scope(Set.of(B)));
            pending.awaitEntered();
            pending.finish();
            await(() -> cache.value(4000) == negativeA,
                    () -> cache.refresh(forbidden, 4000, currentScope));
            assertTrue(cache.value(4000).checked().contains(A));
            assertFalse(cache.value(4000).graphs().containsKey(A));
            assertEquals(1, currentScope.calls.get());
            assertIdleUntilRegularRefresh(fixture, forbidden, negativeA, currentScope, 4000, 2);
        }
    }

    @Test
    void currentFailureKeepsThirtySecondBackoffEvenWhenRequiredScopeGrows() throws Exception {
        try (var fixture = new ReadFixture()) {
            var cache = fixture.cache;
            seed(fixture, ONLY_A, 1000, 1000);
            var failed = fixture.failingRead();
            var forbidden = fixture.read(BOTH);
            var currentScope = new Scope(Set.of(A, B));
            cache.expedite();
            cache.refresh(failed, 3000, new Scope(Set.of(B)));
            failed.awaitEntered();
            cache.refresh(forbidden, 3500, currentScope);
            failed.finish();
            await(() -> cache.failure() != null, () -> cache.refresh(forbidden, 4000, currentScope));
            assertEquals(0, currentScope.calls.get(), "failed JDBC reads have no snapshot to accept");
            assertFalse(cache.shouldRefresh(false), "a failure must not leave a scope retry spinning");
            String failure = cache.failure();
            var expandedScope = new Scope(Set.of(A, B, C));
            for (long now : new long[]{4000, 6000, 33_000, 33_999}) {
                for (int i = 0; i < 100; i++) {
                    cache.expedite();
                    cache.refresh(forbidden, now, expandedScope);
                    assertNull(cache.value(now));
                    assertEquals(failure, cache.failure());
                }
            }
            assertEquals(0, expandedScope.calls.get());
            assertEquals(2, fixture.calls.get());
            var recovered = snapshot(Map.of(A, GRAPH_A, B, GRAPH_B), Set.of(A, B, C));
            var recovery = fixture.read(recovered);
            cache.refresh(recovery, 34_000, expandedScope);
            recovery.awaitEntered();
            assertEquals(0, forbidden.calls.get(), "backoff is 30s from observed failure, not read start");
            recovery.finish();
            await(() -> cache.value(34_000) == recovered,
                    () -> cache.refresh(forbidden, 34_000, expandedScope));
            assertEquals(1, expandedScope.calls.get());
            assertIdleUntilRegularRefresh(fixture, forbidden, recovered, expandedScope, 34_000, 3);
        }
    }

    @Test
    void shrinkingScopeUsesCompletionPredicateInsteadOfSubmissionPredicate() throws Exception {
        try (var fixture = new ReadFixture()) {
            var cache = fixture.cache;
            var launchScope = new Scope(Set.of(A, B));
            var currentScope = new Scope(Set.of(B));
            var pending = fixture.read(ONLY_B);
            var forbidden = fixture.read(ONLY_A);
            cache.refresh(pending, 1000, launchScope);
            pending.awaitEntered();
            assertEquals(0, launchScope.calls.get());
            pending.finish();
            await(() -> cache.value(1500) == ONLY_B,
                    () -> cache.refresh(forbidden, 1500, currentScope));
            assertEquals(0, launchScope.calls.get());
            assertEquals(1, currentScope.calls.get());
            assertIdleUntilRegularRefresh(fixture, forbidden, ONLY_B, currentScope, 1500, 1);
        }
    }

    @Test
    void obsoleteGenerationSkipsScopePredicateAndReplacesImmediatelyWithoutOverlap() throws Exception {
        try (var fixture = new ReadFixture()) {
            var cache = fixture.cache;
            seed(fixture, ONLY_A, 1000, 1000);
            var obsolete = fixture.read(ONLY_B);
            var forbidden = fixture.read(ONLY_B);
            var scope = new Scope(Set.of(A, B));
            cache.expedite();
            cache.refresh(obsolete, 3000, new Scope(Set.of(B)));
            obsolete.awaitEntered();
            cache.invalidate(snapshot -> snapshot);
            for (int i = 0; i < 100; i++) cache.refresh(forbidden, 3001, scope);
            assertEquals(2, fixture.calls.get());
            assertEquals(1, fixture.active.get());
            obsolete.finish();
            var fresh = fixture.read(BOTH);
            await(fresh::entered, () -> cache.refresh(fresh, 3001, scope));
            assertEquals(0, scope.calls.get(), "obsolete data must not undergo current-scope admission");
            assertSame(ONLY_A, cache.value(3001));
            assertNull(cache.failure());
            assertEquals(0, forbidden.calls.get());
            fresh.finish();
            await(() -> cache.value(3001) == BOTH, () -> cache.refresh(forbidden, 3001, scope));
            assertEquals(1, scope.calls.get());
            assertIdleUntilRegularRefresh(fixture, forbidden, BOTH, scope, 3001, 3);
        }
    }

    @Test
    void legacyTwoArgumentRefreshAcceptsResultWithoutTheOldScopePredicate() throws Exception {
        try (var fixture = new ReadFixture()) {
            var cache = fixture.cache;
            var oldScope = new Scope(Set.of(A, B));
            var pending = fixture.read(ONLY_B);
            var forbidden = fixture.read(ONLY_A);
            cache.refresh(pending, 1000, oldScope);
            pending.awaitEntered();
            pending.finish();
            await(() -> cache.value(1500) == ONLY_B, () -> cache.refresh(forbidden, 1500));
            assertEquals(0, oldScope.calls.get());
            assertIdleUntilRegularRefresh(fixture, forbidden, ONLY_B, new Scope(Set.of(B)), 1500, 1);
        }
    }

    @Test
    void actualRequestSnapshotUsesScopedOverloadAndCheckedCoveragePredicate()
            throws IOException, ReflectiveOperationException {
        try (var bytes = RenderManager.class.getResourceAsStream("RenderManager.class")) {
            assertNotNull(bytes);
            var request = ClassFile.of().parse(bytes.readAllBytes()).methods().stream()
                    .filter(method -> method.methodName().equalsString("requestSnapshot")).findFirst().orElseThrow();
            var refreshCalls = request.code().orElseThrow().elementStream()
                    .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast)
                    .filter(call -> call.name().equalsString("refresh")
                            && call.owner().asInternalName().endsWith("RenderManager$ReadCache")).toList();
            assertEquals(1, refreshCalls.size());
            assertEquals("(Ljava/util/concurrent/Callable;JLjava/util/function/Predicate;)V",
                    refreshCalls.getFirst().type().stringValue(), "renderer must supply CURRENT completion scope");
        }
        var predicates = Arrays.stream(RenderManager.class.getDeclaredMethods())
                .filter(method -> method.getName().startsWith("lambda$requestSnapshot$")
                        && method.getReturnType() == boolean.class
                        && Arrays.equals(method.getParameterTypes(),
                                new Class<?>[]{Set.class, RenderManager.Snapshot.class})).toList();
        assertEquals(1, predicates.size(), "requestSnapshot must capture its current requested graph keys");
        var predicate = predicates.getFirst();
        assertTrue(Modifier.isStatic(predicate.getModifiers()));
        predicate.setAccessible(true);
        var negativeA = snapshot(Map.of(B, GRAPH_B), Set.of(A, B));
        var uncheckedA = snapshot(Map.of(A, GRAPH_A, B, GRAPH_B), Set.of(B));
        assertEquals(true, predicate.invoke(null, Set.of(A, B), negativeA));
        assertEquals(false, predicate.invoke(null, Set.of(A, B), ONLY_B));
        assertEquals(false, predicate.invoke(null, Set.of(A), uncheckedA), "graph presence is not checked coverage");
        assertEquals(true, predicate.invoke(null, Set.of(B), ONLY_B));
    }

    @Test
    void actualTenantSnapshotUsesBoundedReaderNotLegacyList() throws IOException {
        try (var bytes = RenderManager.class.getResourceAsStream("RenderManager.class")) {
            assertNotNull(bytes);
            var method = ClassFile.of().parse(bytes.readAllBytes()).methods().stream()
                    .filter(m -> m.methodName().equalsString("readSnapshot")).findFirst().orElseThrow();
            var calls = method.code().orElseThrow().elementStream()
                    .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).toList();
            var reads = calls.stream().filter(call -> call.owner().asInternalName()
                    .endsWith("property/TenantRepository")).toList();
            assertEquals(1, reads.size());
            assertTrue(reads.getFirst().name().equalsString("listForDisplay"));
            assertEquals("(Ljava/util/UUID;I)Ljava/util/List;", reads.getFirst().type().stringValue());
            int index = calls.indexOf(reads.getFirst());
            assertTrue(index >= 2);
            assertTrue(calls.get(index - 1).owner().asInternalName().endsWith("property/Property"));
            assertTrue(calls.get(index - 1).name().equalsString("capacity"));
            assertTrue(calls.get(index - 2).name().equalsString("propertyId"));
        }
    }

    private static RenderManager.Snapshot snapshot(Map<RenderManager.GraphKey, NavGraph> graphs,
                                                   Set<RenderManager.GraphKey> checked) {
        return new RenderManager.Snapshot(Map.of(), graphs, checked);
    }

    private static void seed(ReadFixture fixture, RenderManager.Snapshot value, long startedAt, long observedAt)
            throws Exception {
        var initial = fixture.read(value);
        fixture.cache.refresh(initial, startedAt);
        initial.awaitEntered();
        initial.finish();
        await(() -> fixture.cache.value(observedAt) == value,
                () -> fixture.cache.refresh(initial, observedAt));
    }

    private static void assertIdleUntilRegularRefresh(ReadFixture fixture, GatedRead forbidden,
                                                     RenderManager.Snapshot expected, Scope scope,
                                                     long observedAt, int completedCalls) throws Exception {
        var cache = fixture.cache;
        assertNull(cache.failure());
        assertFalse(cache.shouldRefresh(false), "accepted coverage must consume the coalesced follow-up");
        long due = observedAt + RenderManager.ReadCache.REFRESH_MS;
        for (long now : new long[]{observedAt + RenderManager.ReadCache.EXPEDITED_MS, due - 1}) {
            for (int i = 0; i < 100; i++) cache.refresh(forbidden, now, scope);
            assertSame(expected, cache.value(now));
        }
        assertEquals(completedCalls, fixture.calls.get());
        // A forbidden queued callable blocks the next legitimate read, even before its call count changes.
        var regular = fixture.read(expected);
        cache.refresh(regular, due, scope);
        regular.awaitEntered();
        assertEquals(0, forbidden.calls.get());
        assertEquals(completedCalls + 1, fixture.calls.get());
        assertEquals(1, fixture.maxActive.get());
    }

    private static void await(BooleanSupplier condition, Runnable poll) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            poll.run();
            Thread.yield();
        }
        assertTrue(condition.getAsBoolean(), "snapshot was not consumed at the supplied clock within test deadline");
    }

    private static final class Scope implements Predicate<RenderManager.Snapshot> {
        final AtomicInteger calls = new AtomicInteger();
        private final Thread caller = Thread.currentThread();
        private final Set<RenderManager.GraphKey> required;

        Scope(Set<RenderManager.GraphKey> required) {
            this.required = Set.copyOf(required);
        }

        @Override
        public boolean test(RenderManager.Snapshot snapshot) {
            assertSame(caller, Thread.currentThread(), "scope acceptance belongs on the refresh caller thread");
            calls.incrementAndGet();
            return snapshot.checked().containsAll(required);
        }
    }

    private static final class ReadFixture implements AutoCloseable {
        final RenderManager.ReadCache<RenderManager.Snapshot> cache = new RenderManager.ReadCache<>();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        final List<GatedRead> reads = new ArrayList<>();

        GatedRead read(RenderManager.Snapshot value) {
            return add(value, null);
        }

        GatedRead failingRead() {
            return add(null, new IllegalStateException("fixture snapshot failure"));
        }

        private GatedRead add(RenderManager.Snapshot value, RuntimeException failure) {
            var read = new GatedRead(this, value, failure);
            reads.add(read);
            return read;
        }

        @Override
        public void close() {
            for (var read : reads) read.release.countDown();
            cache.close();
            try {
                for (var read : reads) {
                    if (read.entered()) read.awaitStopped();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while cleaning up snapshot workers", e);
            }
        }
    }

    private static final class GatedRead implements Callable<RenderManager.Snapshot> {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        private volatile Thread worker;
        private final ReadFixture fixture;
        private final RenderManager.Snapshot value;
        private final RuntimeException failure;

        GatedRead(ReadFixture fixture, RenderManager.Snapshot value, RuntimeException failure) {
            this.fixture = fixture;
            this.value = value;
            this.failure = failure;
        }

        boolean entered() {
            return started.getCount() == 0;
        }

        void awaitEntered() throws InterruptedException {
            assertTrue(started.await(3, TimeUnit.SECONDS), "snapshot read did not start at the supplied clock");
        }

        void finish() throws InterruptedException {
            release.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS), "snapshot read did not return after release");
            // Future publication can follow the return latch; caller-clock polling consumes it separately.
        }

        void awaitStopped() throws InterruptedException {
            assertTrue(finished.await(3, TimeUnit.SECONDS), "closed snapshot worker did not return");
            worker.join(3000);
            assertFalse(worker.isAlive(), "snapshot worker leaked after close");
        }

        @Override
        public RenderManager.Snapshot call() {
            worker = Thread.currentThread();
            calls.incrementAndGet();
            fixture.calls.incrementAndGet();
            fixture.maxActive.accumulateAndGet(fixture.active.incrementAndGet(), Math::max);
            started.countDown();
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (failure != null) throw failure;
                return value;
            } finally {
                fixture.active.decrementAndGet();
                finished.countDown();
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
