package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

/** The marker for a payment request that stopped before any row or transfer existed. */
class NotStartedTest {

    @Test
    void ofKeepsAPlainCauseAsItIs() {
        SQLException cause = new SQLException("pool closed");
        NotStarted stopped = NotStarted.of(cause);
        assertSame(cause, stopped.getCause());
        assertNull(stopped.getMessage());
    }

    @Test
    void ofLooksThroughNestedCompletionWrappers() {
        IllegalStateException cause = new IllegalStateException("lease");
        NotStarted stopped = NotStarted.of(new CompletionException(new CompletionException(cause)));
        assertSame(cause, stopped.getCause());
    }

    @Test
    void ofKeepsACompletionWrapperThatHasNoCause() {
        CompletionException empty = new CompletionException("no cause", null);
        assertSame(empty, NotStarted.of(empty).getCause());
    }

    @Test
    void ofStopsAtTheFirstNonCompletionWrapper() {
        RuntimeException outer = new RuntimeException(new SQLException("inner"));
        assertSame(outer, NotStarted.of(new CompletionException(outer)).getCause());
    }

    @Test
    void itCarriesNoStackTraceAndNoSuppressedFailures() {
        NotStarted stopped = NotStarted.of(new SQLException("x"));
        assertEquals(0, stopped.getStackTrace().length);
        stopped.addSuppressed(new RuntimeException("later"));
        assertEquals(0, stopped.getSuppressed().length);
    }

    @Test
    void inFindsTheMarkerItselfOrBehindCompletionWrappers() {
        NotStarted stopped = NotStarted.of(new SQLException("x"));
        assertSame(stopped, NotStarted.in(stopped));
        assertSame(stopped, NotStarted.in(new CompletionException(stopped)));
        assertSame(stopped, NotStarted.in(new CompletionException(new CompletionException(stopped))));
    }

    @Test
    void inIgnoresAMarkerHiddenBehindAnyOtherWrapper() {
        NotStarted stopped = NotStarted.of(new SQLException("x"));
        assertNull(NotStarted.in(new RuntimeException(stopped)));
        assertNull(NotStarted.in(new CompletionException(new IllegalStateException(stopped))));
    }

    @Test
    void inReturnsNullForNullOrAnUnrelatedFailure() {
        assertNull(NotStarted.in(null));
        assertNull(NotStarted.in(new SQLException("x")));
        assertNull(NotStarted.in(new CompletionException(new SQLException("x"))));
        assertNull(NotStarted.in(new CompletionException("no cause", null)));
    }
}
