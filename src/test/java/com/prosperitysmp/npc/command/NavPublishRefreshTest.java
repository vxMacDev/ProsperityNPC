package com.prosperitysmp.npc.command;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NavPublishRefreshTest {
    @Test void successfulCommitInvalidatesExactlyOnceAfterAttempt() throws SQLException {
        var order = new ArrayList<String>();
        assertTrue(NpcNavCommand.publishAndInvalidate(() -> { order.add("commit"); return true; },
                () -> order.add("invalidate")));
        assertEquals(List.of("commit", "invalidate"), order);
    }

    @Test void knownRejectionDoesNotDiscardGoodRoutes() throws SQLException {
        assertFalse(NpcNavCommand.publishAndInvalidate(() -> false, () -> fail("rejected publish invalidated routes")));
    }

    @Test void ambiguousCommitInvalidatesAndPreservesSqlFailure() {
        var failure = new SQLException("commit acknowledgement lost");
        var order = new ArrayList<String>();
        var caught = assertThrows(SQLException.class, () -> NpcNavCommand.publishAndInvalidate(
                () -> { order.add("attempt"); throw failure; }, () -> order.add("invalidate")));
        assertSame(failure, caught);
        assertEquals(List.of("attempt", "invalidate"), order);
    }

    @Test void runtimeFailureAlsoInvalidates() {
        var failure = new IllegalStateException("connection teardown");
        var calls = new ArrayList<String>();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> NpcNavCommand.publishAndInvalidate(
                () -> { throw failure; }, () -> calls.add("invalidate"))));
        assertEquals(List.of("invalidate"), calls);
    }

    @Test void failedRefreshDoesNotRepeatPublishOrCallback() {
        var calls = new ArrayList<String>();
        assertThrows(IllegalStateException.class, () -> NpcNavCommand.publishAndInvalidate(
                () -> { calls.add("commit"); return true; },
                () -> { calls.add("invalidate"); throw new IllegalStateException("renderer stopped"); }));
        assertEquals(List.of("commit", "invalidate"), calls);
    }

    @Test void actualCommandWiresRepositoryAttemptAndRendererNotification() throws IOException {
        try (var bytes = NpcNavCommand.class.getResourceAsStream("NpcNavCommand.class")) {
            assertNotNull(bytes);
            var model = ClassFile.of().parse(bytes.readAllBytes());
            var publish = model.methods().stream().filter(m -> m.methodName().equalsString("publishGraph"))
                    .findFirst().orElseThrow();
            assertEquals(1, publish.code().orElseThrow().elementStream()
                    .filter(e -> e instanceof InvokeInstruction c && c.name().equalsString("publishAndInvalidate")).count());
            var callbackCalls = model.methods().stream().filter(m -> m.methodName().stringValue().startsWith("lambda$publishGraph$"))
                    .flatMap(m -> m.code().stream()).flatMap(c -> c.elementStream())
                    .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).toList();
            assertEquals(0, callbackCalls.stream().filter(c -> c.name().equalsString("routesChanged")).count());
            assertEquals(1, callbackCalls.stream().filter(c -> c.name().equalsString("publish")
                    && c.owner().asInternalName().endsWith("NavGraphRepository")).count());
            var pumpCalls = model.methods().stream().filter(m -> m.methodName().equalsString("pumpTags"))
                    .flatMap(m -> m.code().stream()).flatMap(c -> c.elementStream())
                    .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).toList();
            assertEquals(1, pumpCalls.stream().filter(c -> c.name().equalsString("routesChanged")).count());
            assertEquals(1, pumpCalls.stream().filter(c -> c.name().equalsString("worldUuid")).count());
            assertEquals(1, pumpCalls.stream().filter(c -> c.name().equalsString("regionId")).count());
        }
    }

    @Test void validationRepliesBoundIssuesBeforeOwnerDelivery() throws Exception {
        var issues = new ArrayList<com.prosperitysmp.npc.nav.NavGraphValidator.Issue>();
        for (int i = 0; i < 100; i++) issues.add(new com.prosperitysmp.npc.nav.NavGraphValidator.Issue(
                com.prosperitysmp.npc.nav.NavGraphValidator.Severity.ERROR, "ISSUE" + i, "fixture"));
        var method = NpcNavCommand.class.getDeclaredMethod("validationLines", com.prosperitysmp.npc.nav.NavGraphValidator.Report.class);
        method.setAccessible(true);
        var lines = (List<?>) method.invoke(null, new com.prosperitysmp.npc.nav.NavGraphValidator.Report(issues));
        assertEquals(22, lines.size());
        assertTrue(lines.getFirst().toString().contains("100 errors"));
        assertTrue(lines.get(20).toString().contains("ISSUE19"));
        assertTrue(lines.getLast().toString().contains("80 additional issues omitted"));
        assertFalse(lines.toString().contains("ISSUE20"));
    }

    /** Compiled placement check only, not a live Bukkit/executor integration test. */
    @Test void commandEntryPointsContainNoDirectRepositoryIoAndCaptureFinishesOffOwner() throws Exception {
        try (var bytes = NpcNavCommand.class.getResourceAsStream("NpcNavCommand.class")) {
            var model = ClassFile.of().parse(bytes.readAllBytes());
            for (var method : model.methods()) {
                String name = method.methodName().stringValue();
                var calls = method.code().stream().flatMap(c -> c.elementStream())
                        .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).toList();
                if (List.of("runScan", "validate", "publish", "info", "list", "tagHere", "pumpTags").contains(name))
                    assertFalse(calls.stream().anyMatch(c -> c.owner().asInternalName().endsWith("NavGraphRepository")
                            && !c.name().equalsString("isValidDraftTag")), name);
                if (name.equals("saveScan")) {
                    assertEquals(1, calls.stream().filter(c -> c.name().equalsString("finish")).count());
                    assertFalse(calls.stream().anyMatch(c -> c.owner().asInternalName().startsWith("org/bukkit/")));
                }
                if (name.equals("pumpTags")) assertEquals(1, calls.stream().filter(c -> c.name().equalsString("advance")).count());
            }
        }
    }
}
