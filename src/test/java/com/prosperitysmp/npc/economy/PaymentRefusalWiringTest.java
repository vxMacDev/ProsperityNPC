package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Where the payment runtimes stop before any money moves. The runtimes need a live server, so their wiring is
 * pinned in source here, next to the behaviour of the pieces they use. A request may only end as "not started"
 * before its first SQL write; anything later stays a recovery hold.
 */
class PaymentRefusalWiringTest {
    private static final String UTILITY = "src/main/java/com/prosperitysmp/npc/economy/UtilityPaymentRuntime.java";
    private static final String COMMERCE = "src/main/java/com/prosperitysmp/npc/economy/CommercePaymentRuntime.java";

    @Test void notStartedIsFoundOnlyThroughCompletionWrappers() {
        var stopped = new NotStarted(new IllegalStateException("x"));
        assertSame(stopped, NotStarted.in(stopped));
        assertSame(stopped, NotStarted.in(new CompletionException(stopped)));
        assertSame(stopped, NotStarted.in(new CompletionException(new CompletionException(stopped))));
        assertNull(NotStarted.in(null));
        assertNull(NotStarted.in(new IllegalStateException("recovery")));
        // Wrapped by anything else, it no longer proves the request never started.
        assertNull(NotStarted.in(new CompletionException(new IllegalStateException("later", stopped))));
        assertNull(NotStarted.in(new IllegalStateException("later", stopped)));
        // Through a real stage chain, as the runtimes see it.
        var failed = CompletableFuture.<String>supplyAsync(() -> { throw new IllegalStateException("fingerprint"); }, Runnable::run)
                .handle((sha, error) -> { if (error != null) throw NotStarted.of(error); return sha; })
                .thenApply(sha -> sha + "!");
        Throwable[] seen = {null};
        failed.whenComplete((value, failure) -> seen[0] = failure);
        assertNotNull(NotStarted.in(seen[0]));
        assertEquals(IllegalStateException.class, NotStarted.in(seen[0]).getCause().getClass(),
                "the wrapper a failed stage adds is not the cause");
    }

    @Test void utilityRequestsStopAsNotStartedOnlyBeforePrepare() throws Exception {
        String code = read(UTILITY);
        String core = section(code, "com.prosperitysmp.npc.property.AutoCareRepository.Claim recurring, Consumer<Boolean> finished) {",
                "/** The reply when a request stopped before any payment row or transfer existed. */");
        int prepare = core.indexOf("var prepared = prepare(requestId,");
        assertTrue(prepare > 0);
        // U1: a fingerprint failure, and U2: the first authority check, are both before any SQL.
        int handle = core.indexOf(".handle((sha, error) -> {\n                    if (error != null) throw NotStarted.of(error);");
        int compose = core.indexOf(".thenComposeAsync(sha -> {");
        int validate = core.indexOf("try { validate(property, actor, authorized, registration, lease, day); }\n"
                + "                    catch (RuntimeException | LinkageError refused) { throw new NotStarted(refused); }");
        assertTrue(handle > 0 && compose > handle && validate > compose && prepare > validate, handle + " " + compose + " " + validate);
        // Never after prepare: from there the durable state is unknown and only recovery is honest.
        assertEquals(1, count(core, "new NotStarted("));
        assertEquals(1, count(core, "NotStarted.of("));
        assertTrue(core.lastIndexOf("new NotStarted(") < prepare && core.lastIndexOf("NotStarted.of(") < prepare);
        // The completion handler releases a request that never started before the recovery branch can run.
        int settle = core.indexOf("NotStarted stopped = NotStarted.in(failure);");
        int release = core.indexOf("if (stopped != null && releaseUnstarted(lease, requestId)) {", settle);
        int recoveryBranch = core.indexOf("if (failure != null) {", settle);
        assertTrue(settle > 0 && release > settle && recoveryBranch > release);
        String branch = core.substring(release, recoveryBranch);
        for (String step : new String[]{"refusals.notStarted(\"utility\"", "finished.accept(false);",
                "respond(authorized, reply, NOT_STARTED_REPLY);", "return;"}) assertTrue(branch.contains(step), step);
        String helper = section(code, "private boolean releaseUnstarted(", "/** Explicit current-day batch;");
        for (String step : new String[]{"lease.releaseReconciled();", "retainedLease = null;", "busy = false;", "recovery = false;",
                "releaseFinancial();", "return false;"}) assertTrue(helper.contains(step), step);
        assertEquals("No payment was started. Nothing was charged.", UtilityPaymentRuntime.NOT_STARTED_REPLY);
    }

    @Test void rentBatchStopsAsNotStartedOnlyBeforeItsReservation() throws Exception {
        String code = read(UTILITY);
        String batch = section(code, "public boolean requestRentBatch(", "public boolean requestPayoutRetirement(");
        int reserve = batch.indexOf("reserveRentBatchCounted(");
        assertTrue(reserve > 0);
        assertEquals(1, count(batch, "new NotStarted("));
        assertEquals(1, count(batch, "NotStarted.of("));
        assertTrue(batch.lastIndexOf("new NotStarted(") < reserve && batch.lastIndexOf("NotStarted.of(") < reserve,
                "R1 never after the reservation step");
        assertTrue(batch.indexOf("fingerprint.handle((sha, error) -> {") > 0);
        int settle = batch.indexOf("NotStarted stopped = NotStarted.in(failure);");
        int release = batch.indexOf("if (stopped != null && releaseUnstartedRent(batchId)) {", settle);
        int recoveryBranch = batch.indexOf("if (failure != null) {", settle);
        assertTrue(settle > 0 && release > settle && recoveryBranch > release,
                "a batch whose leases cannot be released falls through to the recovery hold");
        String branch = batch.substring(release, recoveryBranch);
        for (String step : new String[]{"busy = false;", "releaseFinancial();",
                "respond(authorized, reply, RENT_NOT_STARTED_REPLY);", "return;"}) assertTrue(branch.contains(step), step);
        assertFalse(branch.contains("recovery = true"), "not started is never a recovery hold");
        String helper = section(code, "private boolean releaseUnstartedRent(UUID batchId) {", "static boolean releasedQuietly(");
        assertTrue(helper.contains("return releasedQuietly(this::releaseUnusedRent,"), helper);
        assertTrue(batch.substring(recoveryBranch).contains("recovery = true;"));
        assertEquals("Rent batch not started; nothing was paid.", UtilityPaymentRuntime.RENT_NOT_STARTED_REPLY);
    }

    @Test void aReleaseThatFailsIsReportedSoTheBatchKeepsItsHold() {
        List<String> warned = new ArrayList<>();
        assertTrue(UtilityPaymentRuntime.releasedQuietly(() -> { }, warned::add, "lease"));
        assertTrue(warned.isEmpty());
        assertFalse(UtilityPaymentRuntime.releasedQuietly(() -> { throw new IllegalStateException("lease lost"); }, warned::add, "lease"));
        assertEquals(List.of("lease"), warned);
        assertFalse(UtilityPaymentRuntime.releasedQuietly(() -> { throw new NoSuchMethodError("x"); },
                text -> { throw new IllegalStateException("log down"); }, "lease"), "a broken log still leaves the hold");
    }

    @Test void payoutAuthorityAndReserveReadsDeferInsteadOfHolding() throws Exception {
        String code = read(UTILITY);
        String rent = section(code, "Runnable floorDeferred, BooleanSupplier debitGate) {", "private CompletableFuture<Integer> payAppreciation(");
        int handle = rent.indexOf("var handle = new TransferJournal.Handle(");
        // R2: authority changed before the payout started. The reservation stays pending, the lease reopens.
        int r2 = rent.indexOf("} catch (RuntimeException | LinkageError refused) {");
        String deferred = rent.substring(r2, rent.indexOf("BooleanSupplier awayHeld", r2));
        for (String step : new String[]{"lease.releaseReconciled();", "rentLeases.remove(property.propertyId());",
                "StaffAlerts.Kind.PAYOUT_REFUSED", "Payout not started: authority changed", "return CompletableFuture.completedFuture(false);"})
            assertTrue(deferred.contains(step), step);
        assertTrue(rent.indexOf("requireLimits(limits);") < r2 && rent.indexOf("validate(property,") < r2 && r2 < handle);
        // R3: an unreadable reserve balance, or one that cannot be read at all, defers like the floor does, before
        // any journal row.
        int r3 = rent.indexOf("var available = readableCents(() -> pinned.getProvider().getBalance(Bukkit.getOfflinePlayer(reserve)));");
        int floor = rent.indexOf("if(available.isEmpty() || !floorAllows(available.getAsLong(),row.cents(),limits.centralBankFloorCents())) {");
        assertTrue(r3 > 0 && floor > r3 && handle > floor);
        assertTrue(rent.contains("the reserve balance cannot be verified"));
        // An owner-away decision that cannot be read holds the payout like an away owner, also before any journal row.
        int away = rent.indexOf("Boolean away = awayDecision(awayHeld);\n        if (away == null || away) {");
        assertTrue(away > r2 && r3 > away, "the away read is guarded");
        String unreadable = rent.substring(away, r3);
        assertTrue(unreadable.contains("lease.releaseReconciled(); rentLeases.remove(property.propertyId());")
                && unreadable.contains("StaffAlerts.Kind.PAYOUT_REFUSED") && unreadable.contains("cannot be read")
                && unreadable.contains("return CompletableFuture.completedFuture(false);"), "an unreadable decision is held and reported");
        assertEquals(0, count(rent.substring(0, handle), "awayHeld.getAsBoolean()"), "no unguarded away read before the intent");
        // A refusal inside the transfer raises PAYOUT_REFUSED and feeds the monitor.
        assertTrue(rent.contains("}, worker, mainExecutor, (leg, refused) -> {"));
        assertTrue(rent.contains("refusals.refused(\"rent\", \"event \" + row.eventId(), leg, refused);"));
        assertTrue(rent.contains("if (receipt.state() == TransferJournal.State.PAID) refusals.paid(\"rent\");"));
    }

    @Test void balanceReadsUseAvailableCentsAndEveryLegNamesItsCounterparty() throws Exception {
        String utility = read(UTILITY), commerce = read(COMMERCE);
        assertEquals(0, count(utility, "TransferProof.toCents("), "fractional balances are payable, rounded down");
        assertEquals(0, count(commerce, "TransferProof.toCents("));
        assertEquals(3, count(utility, "TransferProof.availableCents("), "admission, the rent reserve read and requireFloor");
        assertTrue(utility.contains("UUID counterparty = leg == TransferProof.Leg.DEBIT ? reserve : actor;"));
        assertTrue(utility.contains("UUID counterparty = leg == TransferProof.Leg.DEBIT ? property.ownerUuid() : reserve;"));
        assertTrue(commerce.contains("UUID counterparty = leg == TransferProof.Leg.DEBIT ? reserve : order.playerId();"));
        assertTrue(utility.contains("}, worker, mainExecutor, (leg, refused) -> refusals.refused(\"utility\", \"event \" + prepared.eventId(), leg, refused));"));
        assertTrue(utility.contains("plugin.getLogger().info(runLine(\"NPC run\", runId, build, runJar));"));
    }

    @Test void unreadableBalancesAndAwayDecisionsAreSafeAnswersBeforeAnyIntent() {
        assertEquals(Boolean.TRUE, UtilityPaymentRuntime.awayDecision(() -> true));
        assertEquals(Boolean.FALSE, UtilityPaymentRuntime.awayDecision(() -> false));
        assertNull(UtilityPaymentRuntime.awayDecision(() -> { throw new IllegalStateException("away store down"); }));
        assertNull(UtilityPaymentRuntime.awayDecision(() -> { throw new NoSuchMethodError("x"); }));
        assertEquals(java.util.OptionalLong.of(5_749_248), UtilityPaymentRuntime.readableCents(() -> 57492.488404927248686));
        assertTrue(UtilityPaymentRuntime.readableCents(() -> -1.0).isEmpty());
        assertTrue(UtilityPaymentRuntime.readableCents(() -> { throw new IllegalStateException("provider down"); }).isEmpty());
    }

    @Test void appreciationThatCannotStartMovesNothingAndHoldsNothing() throws Exception {
        String code = read(UTILITY);
        String appreciation = section(code, "private CompletableFuture<Integer> payAppreciation(", "var scorer = rents.ratedScore();");
        int outstanding = appreciation.indexOf("if (!rentLeases.isEmpty()) throw new IllegalStateException(\"Rent leases outstanding before appreciation\");");
        int limits = appreciation.indexOf("try { requireLimits(limits); }");
        int lease = appreciation.indexOf("plugin.ownershipFence().acquire(owner)");
        assertTrue(outstanding > 0 && limits > outstanding && lease > limits, "limits are read before any lease or reservation");
        String refused = appreciation.substring(limits, appreciation.indexOf("BooleanSupplier stillOn", limits));
        assertTrue(refused.contains("catch (RuntimeException | LinkageError changed) {")
                && refused.contains("return CompletableFuture.completedFuture(-1);"), refused);
        assertEquals(1, count(appreciation, "requireLimits(limits)"), "no unguarded limits read on entry");
    }

    @Test void aWrongBindingIsCheckedFirstAndHeldAsAFault() throws Exception {
        String code = read(UTILITY);
        for (String[] lease : new String[][]{
                {"public void authorize(TransferJournal.Receipt receipt, TransferProof.Leg leg) {\n                                // A receipt bound to another payment",
                        "throw new JournaledTransfer.BindingFailed(\"Utility transfer binding failed\");", "validate(property, actor,"},
                {"public void authorize(TransferJournal.Receipt receipt, TransferProof.Leg leg) {\n                // A receipt bound to another payout",
                        "throw new JournaledTransfer.BindingFailed(\"Rent transfer binding failed\");", "requireLimits(limits);"}}) {
            int start = code.indexOf(lease[0]);
            assertTrue(start > 0, lease[0]);
            String body = code.substring(start, code.indexOf("public void releaseVerified(", start));
            int binding = body.indexOf(lease[1]), check = body.indexOf(lease[2]);
            assertTrue(binding > 0 && check > binding, "the binding is checked before any authority check: " + lease[1]);
        }
        assertFalse(code.contains("new IllegalStateException(\"Utility transfer binding failed\")"));
        assertFalse(code.contains("new IllegalStateException(\"Rent transfer binding failed\")"));
    }

    @Test void everyRunLogsTheBuildThatWritesItsRows(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        UUID run = new UUID(1, 2);
        Path jar = Files.write(dir.resolve("npc.jar"), "jar bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("jar bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("NPC commerce run " + run + " build 0.2.0-dev.63 jar " + sha,
                UtilityPaymentRuntime.runLine("NPC commerce run", run, "0.2.0-dev.63", jar));
        assertEquals("NPC run " + run + " build b jar unavailable", UtilityPaymentRuntime.runLine("NPC run", run, "b", null));
        assertEquals("NPC run " + run + " build b jar unavailable",
                UtilityPaymentRuntime.runLine("NPC run", run, "b", dir.resolve("missing.jar")));
        // Commerce writes its own run_id, so it logs its own line too.
        String commerce = read(COMMERCE);
        int log = commerce.indexOf("UtilityPaymentRuntime.runLine(\"NPC commerce run\", runId, build, runJar)");
        int recover = commerce.indexOf("orders.recoverOtherRuns(runId, 100)");
        assertTrue(log > 0 && recover > log, "logged at startup, before this run writes anything");
    }

    @Test void commerceStopsBeforeReservingAndCancelsBeforeTheDebitIntent() throws Exception {
        String code = read(COMMERCE);
        String flow = section(code, "fingerprint.handle((sha, error) -> {", "private TransferProof.Observation observe(");
        int reserve = flow.indexOf("orders.reserve(order, limits)");
        int c1 = flow.indexOf("try { provider(pinned, lease); }\n            catch (RuntimeException | LinkageError refused) { throw new NotStarted(refused); }");
        assertTrue(c1 > 0 && reserve > c1, "C1 before the order is reserved");
        assertEquals(1, count(flow, "new NotStarted("));
        assertTrue(flow.lastIndexOf("new NotStarted(") < reserve);
        int beginDebit = flow.indexOf("orders.beginDebit(order, handle, partnerCheck)");
        int c2 = flow.indexOf("catch (RuntimeException | LinkageError refused) { return cancel(order); }");
        assertTrue(c2 > reserve && beginDebit > c2, "C2 cancels the reserved order before any debit intent");
        // C4: nothing between the debit acknowledgement and the credit intent may throw for authority.
        String between = flow.substring(flow.indexOf("orders.recordDebit(handle, observed)"), flow.indexOf("orders.beginCredit(handle)"));
        assertFalse(between.contains("provider(pinned, lease)"), between);
        String settle = section(code, "NotStarted stopped = NotStarted.in(failure);", "if (!closed && player.isOnline()");
        assertTrue(settle.contains("stopped != null ? new Result(Status.UNAVAILABLE, null)"));
        assertTrue(settle.contains("lease.releaseVerified();") && settle.contains("busy = false;"));
        assertTrue(settle.contains("if (actual.status() == Status.FUNDED) refusals.paid(\"commerce\");"));
        assertEquals(2, count(code, "refusals.refused(\"commerce\""), "debit and credit");
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path)).replace("\r\n", "\n");
    }

    private static String section(String code, String start, String end) {
        int from = code.indexOf(start), to = code.indexOf(end, from);
        assertTrue(from >= 0 && to > from, start);
        return code.substring(from, to);
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
