package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.commerce.CustodyBytes;
import com.prosperitysmp.npc.commerce.ItemCustody;
import com.prosperitysmp.npc.commerce.ItemCustody.Source;
import com.prosperitysmp.npc.commerce.ItemCustodyRepository;
import com.prosperitysmp.npc.commerce.ItemCustodyRepository.*;
import com.prosperitysmp.npc.commerce.ItemDeliveryEvidence;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real MariaDB transactions and concurrent repositories; no Bukkit delivery or money calls. */
class ItemCustodyRepositoryIT {
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static DB mariaDb;
    private static Database database;
    private static DatabaseSettings settings;
    private ItemCustodyRepository repository;
    private UUID player;
    private UUID run;

    @BeforeAll static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder(); cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build()); mariaDb.start();
        String schema = "npc_custody_" + UUID.randomUUID().toString().replace("-", "");
        mariaDb.createDB(schema);
        database = new Database();
        settings = new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L);
        database.connect(settings);
        // V1, V35 and V36: beginDelivery also checks open item-repair hand-ins and contract hand-overs, and REPAIR_RETURN
        // needs the widened source.
        for (String migration:List.of("V1__initial_schema.sql","V2__resident_identity.sql","V21__item_custody.sql","V22__commerce_orders.sql","V35__item_repairs.sql","V36__town_contracts_partnerships.sql","V38__item_return_staff_decision.sql")) {
            try (InputStream input = ItemCustodyRepositoryIT.class.getResourceAsStream("/db/migration/"+migration)) {
                assertNotNull(input);
                for (String sql : MigrationRunner.splitStatements(new String(input.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
            }
        }
    }
    @AfterAll static void stop() throws Exception {
        try { if (database != null) database.close(); }
        finally { if (mariaDb != null) mariaDb.stop(); }
    }
    @BeforeEach void reset() throws SQLException {
        execute("DROP TRIGGER IF EXISTS custody_finish_failure");
        for (String table : List.of("npc_item_custody_resolutions", "npc_item_delivery_evidence", "npc_item_custody",
                "npc_item_repair_collections", "npc_town_contracts", "npc_item_custody_players"))
            execute("DELETE FROM " + table);
        repository = new ItemCustodyRepository(database); player = UUID.randomUUID(); run = UUID.randomUUID();
    }
    @AfterEach void removeTrigger() throws SQLException { execute("DROP TRIGGER IF EXISTS custody_finish_failure"); }

    @ParameterizedTest @EnumSource(value = Source.class, names = {"WORKER_OUTPUT", "MERCHANT_GOODS"})
    void reserveReplayAndConfirmedDeliveryBurnNonceAndOffer(Source source) throws SQLException {
        ItemCustody request = request(player, source, 4);
        assertTrue(repository.reserve(request, NOW).newlyReserved());
        assertFalse(repository.reserve(copy(request), NOW).newlyReserved());
        assertEquals(request, found(request).request());
        assertArrayEquals(request.payload().bytes(), found(request).request().payload().bytes());
        assertTrue(repository.beginDelivery(request, run, NOW).newlyStarted());
        assertFalse(repository.beginDelivery(request, run, NOW).newlyStarted());
        assertFalse(repository.beginDelivery(request, UUID.randomUUID(), NOW).newlyStarted());
        ItemDeliveryEvidence evidence = confirmed(request);
        Receipt delivered = repository.recordDelivery(request, run, evidence);
        assertEquals(State.DELIVERED, delivered.state());
        assertEquals(delivered, repository.recordDelivery(request, run, evidence));
        assertFalse(repository.beginDelivery(request, run, NOW).newlyStarted());
        assertFalse(repository.reserve(request, NOW).newlyReserved());
        assertThrows(SQLException.class, () -> repository.reserve(withNonce(request, UUID.randomUUID()), NOW));
        assertEquals(1, count("npc_item_custody"));
    }

    @Test void everyReplayBindingIsExactAndWrongRecipientCannotAcknowledge() throws SQLException {
        ItemCustody request = request(player, Source.WORKER_OUTPUT, 4);
        repository.reserve(request, NOW);
        List<ItemCustody> conflicts = List.of(
                new ItemCustody(request.requestNonce(), UUID.randomUUID(), request.offerId(), request.source(), 4, request.payload(), NOW, request.expiresAt()),
                new ItemCustody(request.requestNonce(), player, UUID.randomUUID(), request.source(), 4, request.payload(), NOW, request.expiresAt()),
                new ItemCustody(request.requestNonce(), player, request.offerId(), Source.MERCHANT_GOODS, 4, request.payload(), NOW, request.expiresAt()),
                new ItemCustody(request.requestNonce(), player, request.offerId(), request.source(), 5, request.payload(), NOW, request.expiresAt()),
                new ItemCustody(request.requestNonce(), player, request.offerId(), request.source(), 4, new CustodyBytes(new byte[]{1, 2, 4}), NOW, request.expiresAt()),
                new ItemCustody(request.requestNonce(), player, request.offerId(), request.source(), 4, request.payload(), NOW, request.expiresAt().minusSeconds(1)));
        for (ItemCustody conflict : conflicts) assertThrows(SQLException.class, () -> repository.reserve(conflict, NOW));
        assertTrue(repository.find(UUID.randomUUID(), request.requestNonce(), NOW).isEmpty());
        repository.beginDelivery(request, run, NOW);
        assertThrows(SQLException.class, () -> repository.recordDelivery(request, UUID.randomUUID(), confirmed(request)));
        assertThrows(SQLException.class, () -> repository.recordDelivery(request, run,
                new ItemDeliveryEvidence(UUID.randomUUID(), request.payload().sha256(), "a".repeat(64), "b".repeat(64), 0, 4, 0, true)));
        assertThrows(SQLException.class, () -> repository.recordDelivery(request, run,
                new ItemDeliveryEvidence(player, "f".repeat(64), "a".repeat(64), "b".repeat(64), 0, 4, 0, true)));
        assertEquals(State.DELIVERY_INTENT, found(request).state()); assertEquals(0, count("npc_item_delivery_evidence"));
    }

    @Test void concurrentReservationAndIntentReplayHaveExactlyOneWinner() throws Exception {
        ItemCustody request = request(player, Source.WORKER_OUTPUT, 4);
        List<Callable<Boolean>> reserves = new ArrayList<>();
        for (int i = 0; i < 12; i++) reserves.add(() -> new ItemCustodyRepository(database).reserve(request, NOW).newlyReserved());
        assertEquals(1, race(reserves).stream().filter(Boolean::booleanValue).count());
        List<Callable<Boolean>> starts = new ArrayList<>();
        for (int i = 0; i < 12; i++) starts.add(() -> new ItemCustodyRepository(database).beginDelivery(request, run, NOW).newlyStarted());
        assertEquals(1, race(starts).stream().filter(Boolean::booleanValue).count());
        assertEquals(1, count("npc_item_custody"));
    }

    @Test void concurrentNewOffersCannotOverrunTheSharedQuantityBudget() throws Exception {
        List<Callable<Boolean>> offers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ItemCustody request = request(player, i % 2 == 0 ? Source.WORKER_OUTPUT : Source.MERCHANT_GOODS, 128);
            offers.add(() -> {
                try { return new ItemCustodyRepository(database).reserve(request, NOW).newlyReserved(); }
                catch (SQLException held) {
                    assertEquals("Player item custody budget is held", held.getMessage()); return false;
                }
            });
        }
        assertEquals(4, race(offers).stream().filter(Boolean::booleanValue).count());
        assertEquals(4, count("npc_item_custody"));
    }

    @Test void concurrentNonceSubstitutionCannotIssueTheSameOfferTwice() throws Exception {
        ItemCustody original = request(player, Source.WORKER_OUTPUT, 4);
        ItemCustody replacement = withNonce(original, UUID.randomUUID());
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (ItemCustody request : List.of(original, replacement)) calls.add(() -> {
            try { return new ItemCustodyRepository(database).reserve(request, NOW).newlyReserved(); }
            catch (SQLException duplicate) { assertEquals(1062, duplicate.getErrorCode()); return false; }
        });
        assertEquals(1, race(calls).stream().filter(Boolean::booleanValue).count());
        assertEquals(1, count("npc_item_custody"));
    }

    @Test void deliveryConflictIsGlobalAcrossSourcesAndIndependentRepositoryInstances() throws Exception {
        ItemCustody worker = request(player, Source.WORKER_OUTPUT, 4), merchant = request(player, Source.MERCHANT_GOODS, 3);
        repository.reserve(worker, NOW); repository.reserve(merchant, NOW);
        List<Boolean> started = race(List.of(
                () -> repository.beginDelivery(worker, run, NOW).newlyStarted(),
                () -> new ItemCustodyRepository(database).beginDelivery(merchant, run, NOW).newlyStarted()));
        assertEquals(1, started.stream().filter(Boolean::booleanValue).count());
        ItemCustody winner = found(worker).state() == State.DELIVERY_INTENT ? worker : merchant;
        ItemCustody other = winner.equals(worker) ? merchant : worker;
        repository.markAmbiguous(winner, run, Uncertainty.DELIVERY_UNCERTAIN);
        assertFalse(repository.beginDelivery(other, run, NOW.plusSeconds(100)).newlyStarted());
        assertEquals(1, repository.expireReservations(NOW.plusSeconds(901), 100));
        assertEquals(State.AMBIGUOUS, found(winner).state());
        assertEquals(State.EXPIRED, repository.find(player, other.requestNonce(), NOW.plusSeconds(901)).orElseThrow().state());
    }

    @Test void confirmedCompletionReleasesOnlyThatPlayersDeliveryGate() throws SQLException {
        ItemCustody first = request(player, Source.WORKER_OUTPUT, 4), second = request(player, Source.MERCHANT_GOODS, 3);
        ItemCustody independent = request(UUID.randomUUID(), Source.MERCHANT_GOODS, 2);
        for (ItemCustody request : List.of(first, second, independent)) repository.reserve(request, NOW);
        assertTrue(repository.beginDelivery(first, run, NOW).newlyStarted());
        assertTrue(repository.beginDelivery(independent, run, NOW).newlyStarted());
        assertFalse(repository.beginDelivery(second, run, NOW).newlyStarted());
        repository.recordDelivery(first, run, confirmed(first));
        assertTrue(repository.beginDelivery(second, run, NOW).newlyStarted());
    }

    @Test void expiryIsInclusiveLazyBoundedAndNeverExpiresAnIntent() throws SQLException {
        ItemCustody lazy = request(player, Source.WORKER_OUTPUT, 1);
        repository.reserve(lazy, NOW);
        assertEquals(State.RESERVED, repository.find(player, lazy.requestNonce(), lazy.expiresAt().minusMillis(1)).orElseThrow().state());
        assertEquals(State.EXPIRED, repository.find(player, lazy.requestNonce(), lazy.expiresAt()).orElseThrow().state());
        assertFalse(repository.beginDelivery(lazy, run, NOW).newlyStarted());
        assertFalse(repository.reserve(lazy, NOW).newlyReserved());
        ItemCustody started = request(player, Source.MERCHANT_GOODS, 2);
        repository.reserve(started, NOW); repository.beginDelivery(started, run, NOW);
        assertEquals(State.DELIVERY_INTENT, repository.find(player, started.requestNonce(), started.expiresAt()).orElseThrow().state());
        for (int i = 0; i < 3; i++) repository.reserve(request(UUID.randomUUID(), Source.WORKER_OUTPUT, 1), NOW);
        assertEquals(0, repository.expireReservations(NOW.plusSeconds(901), 0));
        assertEquals(2, repository.expireReservations(NOW.plusSeconds(901), 2));
        assertEquals(1, repository.expireReservations(NOW.plusSeconds(901), 1000));
        assertEquals(0, repository.expireReservations(NOW.plusSeconds(901), 100));
    }

    @Test void preIntentCancellationReleasesBudgetButAfterIntentCancellationHoldsPlayer() throws SQLException {
        ItemCustody cancelled = request(player, Source.WORKER_OUTPUT, 256);
        repository.reserve(cancelled, NOW);
        assertEquals(State.CANCELLED, repository.cancel(cancelled, NOW).state());
        assertFalse(repository.beginDelivery(cancelled, run, NOW).newlyStarted());
        ItemCustody intent = request(player, Source.MERCHANT_GOODS, 256), other = request(player, Source.WORKER_OUTPUT, 1);
        repository.reserve(intent, NOW); repository.reserve(other, NOW); repository.beginDelivery(intent, run, NOW);
        assertEquals(State.AMBIGUOUS, repository.cancel(intent, NOW).state());
        assertFalse(repository.beginDelivery(other, run, NOW).newlyStarted());
        assertThrows(SQLException.class, () -> repository.reserve(withNonce(cancelled, UUID.randomUUID()), NOW));
    }

    @Test void realRestartRecoversOldIntentsButNeitherReservedNorCurrentRunAndLateEvidenceStaysHeld() throws SQLException {
        ItemCustody old = request(player, Source.WORKER_OUTPUT, 4), pending = request(player, Source.MERCHANT_GOODS, 3);
        ItemCustody current = request(UUID.randomUUID(), Source.MERCHANT_GOODS, 1);
        UUID nextRun = UUID.randomUUID();
        for (ItemCustody request : List.of(old, pending, current)) repository.reserve(request, NOW);
        repository.beginDelivery(old, run, NOW); repository.beginDelivery(current, nextRun, NOW);
        database.close(); database = new Database(); database.connect(settings);
        ItemCustodyRepository restarted = new ItemCustodyRepository(database);
        repository = restarted;
        assertEquals(0, restarted.recoverOtherRuns(nextRun, 0));
        assertEquals(1, restarted.recoverOtherRuns(nextRun, 1));
        assertEquals(0, restarted.recoverOtherRuns(nextRun, 100));
        assertEquals(State.AMBIGUOUS, found(old).state()); assertEquals("INTERRUPTED_RUN", found(old).outcome());
        assertEquals(State.RESERVED, found(pending).state()); assertEquals(State.DELIVERY_INTENT, found(current).state());
        assertFalse(restarted.beginDelivery(old, nextRun, NOW).newlyStarted());
        assertFalse(restarted.beginDelivery(pending, nextRun, NOW).newlyStarted());
        assertEquals(State.AMBIGUOUS, restarted.recordDelivery(old, run, confirmed(old)).state());
        assertFalse(restarted.beginDelivery(pending, nextRun, NOW).newlyStarted());
    }

    @Test void partialLeftoverThrowAndContradictoryEvidenceStayAmbiguous() throws SQLException {
        for (int variant = 0; variant < 3; variant++) {
            ItemCustody request = request(UUID.randomUUID(), Source.WORKER_OUTPUT, 4);
            repository.reserve(request, NOW); repository.beginDelivery(request, run, NOW);
            ItemDeliveryEvidence evidence = new ItemDeliveryEvidence(request.playerId(), request.payload().sha256(),
                    "a".repeat(64), variant == 2 ? "a".repeat(64) : "b".repeat(64), 0, variant == 0 ? 3 : 4,
                    variant == 0 ? 1 : 0, variant != 1);
            assertEquals(State.AMBIGUOUS, repository.recordDelivery(request, run, evidence).state());
            assertEquals(State.AMBIGUOUS, repository.recordDelivery(request, run, evidence).state());
            assertThrows(SQLException.class, () -> repository.recordDelivery(request, run, confirmed(request)));
        }
    }

    @Test void namedCountAndQuantityBudgetsIncludeUncertaintyAndExpireReservationsOnAdmission() throws SQLException {
        ItemCustody a = request(player, Source.WORKER_OUTPUT, 256), b = request(player, Source.MERCHANT_GOODS, 256);
        repository.reserve(a, NOW); repository.reserve(b, NOW); repository.beginDelivery(a, run, NOW);
        repository.markAmbiguous(a, run, Uncertainty.DELIVERY_UNCERTAIN);
        assertThrows(SQLException.class, () -> repository.reserve(request(player, Source.WORKER_OUTPUT, 1), NOW));
        ItemCustody later = new ItemCustody(UUID.randomUUID(), player, UUID.randomUUID(), Source.MERCHANT_GOODS, 256,
                b.payload(), NOW.plusSeconds(901), NOW.plusSeconds(1000));
        assertTrue(repository.reserve(later, NOW.plusSeconds(901)).newlyReserved());
        assertEquals(State.EXPIRED, found(b).state()); assertEquals(State.AMBIGUOUS, found(a).state());
        UUID another = UUID.randomUUID();
        for (int i = 0; i < ItemCustodyRepository.MAX_OPEN_REQUESTS_PER_PLAYER; i++) repository.reserve(request(another, Source.WORKER_OUTPUT, 1), NOW);
        assertThrows(SQLException.class, () -> repository.reserve(request(another, Source.MERCHANT_GOODS, 1), NOW));
    }

    /** RETURNED_BY_STAFF is for item repair returns only: a held commerce delivery never takes it, and nothing is written. */
    @Test void commerceReconciliationRefusesTheItemReturnStaffDecision() throws SQLException {
        ItemCustody request = request(player, Source.WORKER_OUTPUT, 4);
        repository.reserve(request, NOW); repository.beginDelivery(request, run, NOW);
        repository.markAmbiguous(request, run, Uncertainty.DELIVERY_UNCERTAIN);
        assertThrows(SQLException.class, () -> repository.reconcile(request, run,
                new OperatorResolution(UUID.randomUUID(), "staff:verified", Decision.RETURNED_BY_STAFF, new CustodyBytes(new byte[]{7}))));
        assertEquals(State.AMBIGUOUS, found(request).state());
        assertEquals(0, count("npc_item_custody_resolutions"));
        // The same held delivery does take a commerce decision, so only the item-return decision was refused above.
        assertEquals(State.DELIVERED, repository.reconcile(request, run, new OperatorResolution(UUID.randomUUID(), "staff:verified",
                Decision.VERIFIED_DELIVERED, new CustodyBytes(new byte[]{7}))).state());
    }

    /** The two commerce decisions release the hold and never revive the offer. */
    @ParameterizedTest @EnumSource(value = Decision.class, names = {"VERIFIED_DELIVERED", "VERIFIED_NOT_DELIVERED"})
    void explicitOperatorAuditReleasesHoldWithoutEverRevivingTheOffer(Decision decision) throws SQLException {
        ItemCustody request = request(player, Source.WORKER_OUTPUT, 4), other = request(player, Source.MERCHANT_GOODS, 2);
        repository.reserve(request, NOW); repository.reserve(other, NOW); repository.beginDelivery(request, run, NOW);
        OperatorResolution resolution = new OperatorResolution(UUID.randomUUID(), "staff:verified", decision, new CustodyBytes(new byte[]{7, 8, 9}));
        assertThrows(SQLException.class, () -> repository.reconcile(request, run, resolution));
        repository.markAmbiguous(request, run, Uncertainty.DELIVERY_UNCERTAIN);
        Receipt result = repository.reconcile(request, run, resolution);
        assertEquals(decision == Decision.VERIFIED_DELIVERED ? State.DELIVERED : State.RECONCILED_NOT_DELIVERED, result.state());
        assertEquals(result, repository.reconcile(request, run, resolution));
        assertThrows(SQLException.class, () -> repository.reconcile(request, run,
                new OperatorResolution(resolution.resolutionId(), "staff:verified", decision, new CustodyBytes(new byte[]{7, 8, 0}))));
        assertFalse(repository.beginDelivery(request, run, NOW).newlyStarted());
        assertThrows(SQLException.class, () -> repository.reserve(withNonce(request, UUID.randomUUID()), NOW));
        assertTrue(repository.beginDelivery(other, run, NOW).newlyStarted()); assertEquals(1, count("npc_item_custody_resolutions"));
    }

    @Test void failedAcknowledgementTransactionRollsBackEvidenceAndKeepsIntentNonRetryable() throws SQLException {
        ItemCustody request = request(player, Source.WORKER_OUTPUT, 4);
        repository.reserve(request, NOW); repository.beginDelivery(request, run, NOW);
        execute("CREATE TRIGGER custody_finish_failure BEFORE UPDATE ON npc_item_custody FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture finish failure'");
        assertThrows(SQLException.class, () -> repository.recordDelivery(request, run, confirmed(request)));
        assertEquals(State.DELIVERY_INTENT, found(request).state()); assertEquals(0, count("npc_item_delivery_evidence"));
        assertFalse(repository.beginDelivery(request, run, NOW).newlyStarted());
        execute("DROP TRIGGER custody_finish_failure");
        assertEquals(1, repository.recoverOtherRuns(UUID.randomUUID(), 100));
        assertEquals(State.AMBIGUOUS, found(request).state());
    }

    @Test void databaseAlsoEnforcesPayloadHashQuantityAndGlobalUnresolvedPlayerUniqueness() throws SQLException {
        ItemCustody a = request(player, Source.WORKER_OUTPUT, 4), b = request(player, Source.MERCHANT_GOODS, 4);
        repository.reserve(a, NOW); repository.reserve(b, NOW); repository.beginDelivery(a, run, NOW);
        assertThrows(SQLException.class, () -> execute("UPDATE npc_item_custody SET payload_sha256='" + "f".repeat(64) + "'"));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_item_custody SET quantity=257"));
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE npc_item_custody SET state='DELIVERY_INTENT',delivery_run_id=? WHERE request_nonce=?")) {
            ps.setQueryTimeout(5); ps.setBytes(1, Uuids.toBytes(run)); ps.setBytes(2, Uuids.toBytes(b.requestNonce()));
            assertThrows(SQLException.class, ps::executeUpdate);
        }
        assertEquals(State.RESERVED, found(b).state());
    }

    // ---- the preflight a marked order delivery asks before its save ----------------------------------------------

    /** What would block this player's delivery, set up before the check. Each case is a fresh player. */
    private enum Blocker { NONE, CUSTODY_INTENT, CUSTODY_AMBIGUOUS, RETURN_AMBIGUOUS, HAND_IN_INTENT, HAND_IN_AMBIGUOUS,
        HANDOVER_INTENT, HANDOVER_AMBIGUOUS, THIS_DELIVERED, THIS_AMBIGUOUS, THIS_RECONCILED_NOT_DELIVERED }

    @Test void wouldRefuseAgreesWithBeginDeliveryAndStartsNothing() throws SQLException {
        int refusals = 0;
        for (Blocker blocker : Blocker.values()) {
            player = UUID.randomUUID();
            ItemCustody request = request(player, Source.MERCHANT_GOODS, 4);
            repository.reserve(request, NOW);
            switch (blocker) {
                case NONE -> { }
                case CUSTODY_INTENT, CUSTODY_AMBIGUOUS -> {
                    ItemCustody other = request(player, Source.WORKER_OUTPUT, 2);
                    repository.reserve(other, NOW);
                    assertTrue(repository.beginDelivery(other, run, NOW).newlyStarted());
                    if (blocker == Blocker.CUSTODY_AMBIGUOUS) repository.markAmbiguous(other, run, Uncertainty.DELIVERY_UNCERTAIN);
                }
                case RETURN_AMBIGUOUS -> {
                    ItemCustody returned = returnStack(player, 8);
                    reserveReturns(List.of(returned));
                    assertTrue(repository.beginDelivery(returned, run, NOW).newlyStarted());
                    repository.markAmbiguous(returned, run, Uncertainty.DELIVERY_UNCERTAIN);
                }
                case HAND_IN_INTENT -> handIn("INTENT");
                case HAND_IN_AMBIGUOUS -> handIn("AMBIGUOUS");
                case HANDOVER_INTENT -> handover("HANDOVER_INTENT");
                case HANDOVER_AMBIGUOUS -> handover("AMBIGUOUS");
                case THIS_DELIVERED -> {
                    repository.beginDelivery(request, run, NOW);
                    assertEquals(State.DELIVERED, repository.recordDelivery(request, run, confirmed(request)).state());
                }
                case THIS_AMBIGUOUS -> {
                    repository.beginDelivery(request, run, NOW);
                    repository.markAmbiguous(request, run, Uncertainty.DELIVERY_UNCERTAIN);
                }
                case THIS_RECONCILED_NOT_DELIVERED -> {
                    repository.beginDelivery(request, run, NOW);
                    repository.markAmbiguous(request, run, Uncertainty.DELIVERY_UNCERTAIN);
                    repository.reconcile(request, run, new OperatorResolution(UUID.randomUUID(), "staff:verified",
                            Decision.VERIFIED_NOT_DELIVERED, new CustodyBytes(new byte[]{7})));
                }
            }
            List<Object> before = row(request);
            int evidence = count("npc_item_delivery_evidence"), custodyRows = count("npc_item_custody");
            var refusal = repository.wouldRefuse(request, NOW);
            assertEquals(before, row(request), blocker + ": the row's state, run and updated_at are untouched");
            assertEquals(evidence, count("npc_item_delivery_evidence"), blocker.name());
            assertEquals(custodyRows, count("npc_item_custody"), blocker.name());
            refusal.ifPresent(r -> assertEquals(request.requestNonce(), r.request().requestNonce(), "the refusal names this row"));
            Start start = repository.beginDelivery(request, run, NOW);
            assertEquals(refusal.isEmpty(), start.newlyStarted(), blocker + ": wouldRefuse agrees with beginDelivery");
            assertEquals(blocker == Blocker.NONE, start.newlyStarted(), blocker + ": only a free player starts");
            if (refusal.isPresent()) {
                assertEquals(start.receipt(), refusal.get(), blocker + ": the same receipt beginDelivery refused with");
                refusals++;
            }
        }
        assertEquals(Blocker.values().length - 1, refusals, "every blocker refused");
    }

    @Test void markerConfirmedIsWrittenOnlyWhenAsked() throws SQLException {
        assertEquals("INVENTORY_CONFIRMED", ItemCustodyRepository.INVENTORY_CONFIRMED);
        assertEquals("MARKER_CONFIRMED", ItemCustodyRepository.MARKER_CONFIRMED);
        ItemCustody plain = request(UUID.randomUUID(), Source.MERCHANT_GOODS, 4);
        repository.reserve(plain, NOW); repository.beginDelivery(plain, run, NOW);
        Receipt three = repository.recordDelivery(plain, run, confirmed(plain));
        assertEquals(State.DELIVERED, three.state());
        assertEquals("INVENTORY_CONFIRMED", repository.find(plain.playerId(), plain.requestNonce(), NOW).orElseThrow().outcome(),
                "the 3-arg form writes INVENTORY_CONFIRMED");
        ItemCustody marked = request(UUID.randomUUID(), Source.WORKER_OUTPUT, 4);
        repository.reserve(marked, NOW); repository.beginDelivery(marked, run, NOW);
        assertEquals(State.DELIVERED, repository.recordDelivery(marked, run, confirmed(marked), ItemCustodyRepository.MARKER_CONFIRMED).state());
        assertEquals("MARKER_CONFIRMED", repository.find(marked.playerId(), marked.requestNonce(), NOW).orElseThrow().outcome());
        // Evidence that does not confirm the delivery holds it in both forms, whatever was asked for.
        for (int form = 0; form < 2; form++) {
            ItemCustody request = request(UUID.randomUUID(), Source.MERCHANT_GOODS, 4);
            repository.reserve(request, NOW); repository.beginDelivery(request, run, NOW);
            var partial = new ItemDeliveryEvidence(request.playerId(), request.payload().sha256(), "a".repeat(64), "b".repeat(64), 0, 3, 1, true);
            Receipt held = form == 0 ? repository.recordDelivery(request, run, partial)
                    : repository.recordDelivery(request, run, partial, ItemCustodyRepository.MARKER_CONFIRMED);
            assertEquals(State.AMBIGUOUS, held.state());
            assertEquals("EVIDENCE_UNCERTAIN", held.outcome());
        }
        // Any other outcome is refused before anything is written.
        ItemCustody refused = request(UUID.randomUUID(), Source.MERCHANT_GOODS, 4);
        repository.reserve(refused, NOW); repository.beginDelivery(refused, run, NOW);
        int evidence = count("npc_item_delivery_evidence");
        for (String outcome : java.util.Arrays.asList("EVIDENCE_UNCERTAIN", "marker_confirmed", "DELIVERED", "", null))
            assertThrows(IllegalArgumentException.class, () -> repository.recordDelivery(refused, run, confirmed(refused), outcome),
                    String.valueOf(outcome));
        assertEquals(evidence, count("npc_item_delivery_evidence"));
        assertEquals(State.DELIVERY_INTENT, repository.find(refused.playerId(), refused.requestNonce(), NOW).orElseThrow().state());
    }

    /** State, run, outcome and updated_at of one custody row, read straight from the table. */
    private static List<Object> row(ItemCustody request) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT state,HEX(delivery_run_id),outcome_code,CAST(updated_at AS CHAR) FROM npc_item_custody WHERE request_nonce=?")) {
            ps.setQueryTimeout(5); ps.setBytes(1, Uuids.toBytes(request.requestNonce()));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return java.util.Arrays.asList(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
            }
        }
    }

    /**
     * A running or held item hand-in for the current player. Only the columns beginDelivery reads matter; the foreign keys
     * are switched off for this one insert, on this connection only, and switched back before it returns to the pool.
     */
    private void handIn(String state) throws SQLException {
        unchecked("INSERT INTO npc_item_repair_collections(collection_id,player_id,property_id,incident_id,ownership_epoch,"
                + "incident_type,repair_snapshot_sha256,quote_day,cash_cents,value_ratio_bps,required_value_cents,recipe_value_cents,"
                + "price_version,quote_sha256,state,run_id) VALUES(?,?,?,?,0,'PESTS','" + "a".repeat(64) + "','2026-09-29',100,10000,100,100,"
                + "'v1','" + "b".repeat(64) + "','" + state + "',?)", UUID.randomUUID(), player, UUID.randomUUID(), null, run);
    }

    /** A running or held contract hand-over for the current player (see {@link #handIn}). */
    private void handover(String status) throws SQLException {
        UUID resident = UUID.randomUUID();
        unchecked("INSERT INTO npc_town_contracts(contract_id,player_id,kind,definition_id,title,description,giver_id,recipient_id,"
                + "material,quantity,minimum_stage,duration_minutes,accepted_at_millis,accepted_economy_day,day_slot,expires_at_millis,"
                + "status,handover_run_id) VALUES(?,?,'SUPPLY','fixture','t','d',?,?,'STONE',1,'STRANGER',10,0,'2026-09-29',1,600000,'"
                + status + "',?)", UUID.randomUUID(), player, resident, resident, run);
    }

    /** UUID parameters in order; a null one stands for the next incident number. */
    private static void unchecked(String sql, UUID... ids) throws SQLException {
        try (Connection c = database.getConnection()) {
            try (var off = c.createStatement()) { off.execute("SET FOREIGN_KEY_CHECKS=0"); }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setQueryTimeout(5);
                for (int i = 0; i < ids.length; i++) {
                    if (ids[i] == null) ps.setLong(i + 1, INCIDENT.incrementAndGet());
                    else ps.setBytes(i + 1, Uuids.toBytes(ids[i]));
                }
                assertEquals(1, ps.executeUpdate());
            } finally {
                try (var on = c.createStatement()) { on.execute("SET FOREIGN_KEY_CHECKS=1"); }
            }
        }
    }
    private static final java.util.concurrent.atomic.AtomicLong INCIDENT = new java.util.concurrent.atomic.AtomicLong(1_000);

    // ---- item-repairs: REPAIR_RETURN custody ----------------------------------------------------------------

    @Test void repairReturnsNeverExpireWhileAPlainReservationDoes() throws SQLException {
        ItemCustody plain = request(player, Source.WORKER_OUTPUT, 4);
        repository.reserve(plain, NOW);
        ItemCustody returned = returnStack(player, 16);
        reserveReturns(List.of(returned));
        Instant late = NOW.plusSeconds(3_600);
        assertEquals(1, repository.expireReservations(late, 100), "the plain reservation expires");
        assertEquals(State.EXPIRED, repository.find(player, plain.requestNonce(), late).orElseThrow().state());
        assertEquals(State.RESERVED, repository.find(player, returned.requestNonce(), late).orElseThrow().state());
        assertEquals(0, repository.expireReservations(late, 100));
        assertTrue(repository.beginDelivery(returned, run, late).newlyStarted(), "still deliverable after its nominal expiry");
        assertFalse(repository.beginDelivery(returned, run, late).newlyStarted(), "one shot");
        assertEquals(State.DELIVERED, repository.recordDelivery(returned, run, confirmed(returned)).state());
    }

    /** The startup report's read: held paid orders only, including one cut off by a restart once recovery holds it. */
    @Test void heldOrdersListsHeldPaidOrdersOnly() throws SQLException {
        ItemCustody cutOff = request(player, Source.MERCHANT_GOODS, 4);
        UUID other = UUID.randomUUID();
        ItemCustody waiting = request(other, Source.WORKER_OUTPUT, 4);
        repository.reserve(cutOff, NOW); repository.reserve(waiting, NOW);
        assertTrue(repository.beginDelivery(cutOff, run, NOW).newlyStarted());
        assertEquals(List.of(), repository.heldOrders(10), "a running delivery is not held");
        assertEquals(1, repository.recoverOtherRuns(UUID.randomUUID(), 100), "a restart holds the cut-off delivery");
        // The waiting order's player: it has the custody guard row and nothing held, so its return can be held.
        ItemCustody heldReturn = returnStack(other, 1);
        reserveReturns(List.of(heldReturn));
        assertEquals(State.AMBIGUOUS, repository.holdUnreadableReturn(heldReturn, run).state(), "a held return exists");
        assertEquals(List.of(new ItemCustodyRepository.HeldOrder(player, cutOff.requestNonce(), Source.MERCHANT_GOODS,
                "INTERRUPTED_RUN")), repository.heldOrders(10), "only the held paid order, not the waiting one or the return");
        assertEquals(List.of(), repository.heldOrders(0));
        assertEquals(1, repository.heldOrderCount(), "the count leaves out the waiting order and the held return too");
    }

    @Test void heldReturnsNeverCrowdOutAnExpiringReservationInABoundedSweep() throws SQLException {
        // The return reaches its nominal expiry first; a one-row sweep must still find the plain reservation.
        ItemCustody returned = new ItemCustody(UUID.randomUUID(), player, UUID.randomUUID(), Source.REPAIR_RETURN, 1,
                new CustodyBytes(new byte[]{9}), NOW, NOW.plusSeconds(60));
        ItemCustody plain = request(player, Source.MERCHANT_GOODS, 1);
        repository.reserve(plain, NOW);
        reserveReturns(List.of(returned));
        Instant late = NOW.plusSeconds(3_600);
        assertEquals(1, repository.expireReservations(late, 1), "the return is never even picked");
        assertEquals(State.RESERVED, repository.find(player, returned.requestNonce(), late).orElseThrow().state());
    }

    @Test void returnsDoNotCountAgainstTheCommerceBudget() throws SQLException {
        var returns = new ArrayList<ItemCustody>();
        for (int i = 0; i < ItemCustodyRepository.MAX_RETURN_STACKS; i++) returns.add(returnStack(player, 64));
        repository.reserve(request(player, Source.WORKER_OUTPUT, 1), NOW); // creates the player guard
        reserveReturns(returns);
        for (int i = 1; i < ItemCustodyRepository.MAX_OPEN_REQUESTS_PER_PLAYER; i++)
            assertTrue(repository.reserve(request(player, i % 2 == 0 ? Source.WORKER_OUTPUT : Source.MERCHANT_GOODS, 1), NOW).newlyReserved(),
                    "commerce offer " + i + " fits beside 4 returns");
        var held = assertThrows(SQLException.class, () -> repository.reserve(request(player, Source.MERCHANT_GOODS, 1), NOW));
        assertEquals("Player item custody budget is held", held.getMessage(), "the commerce bound still holds, never a corrupt count");
        assertEquals(ItemCustodyRepository.MAX_OPEN_REQUESTS_PER_PLAYER + ItemCustodyRepository.MAX_RETURN_STACKS, count("npc_item_custody"));
    }

    @Test void reserveReturnNeedsTheCallerTransactionTheSourceAndItsBound() throws SQLException {
        repository.reserve(request(player, Source.WORKER_OUTPUT, 1), NOW);
        try (Connection c = database.getConnection()) {
            assertTrue(c.getAutoCommit());
            assertThrows(SQLException.class, () -> ItemCustodyRepository.reserveReturn(c, returnStack(player, 1)));
        }
        assertThrows(IllegalArgumentException.class, () -> reserveReturns(List.of(request(player, Source.MERCHANT_GOODS, 1))));
        assertThrows(IllegalArgumentException.class, () -> repository.reserve(returnStack(player, 1), NOW),
                "returns are never a budgeted offer");
        var five = new ArrayList<ItemCustody>();
        for (int i = 0; i < 5; i++) five.add(returnStack(player, 1));
        assertThrows(SQLException.class, () -> reserveReturns(five), "one set of at most 4 stacks");
        assertEquals(1, count("npc_item_custody"), "the refused set rolled back whole");
        reserveReturns(five.subList(0, 4));
        assertEquals(5, count("npc_item_custody"), "four fit");
    }

    private ItemCustody returnStack(UUID recipient, int quantity) {
        return new ItemCustody(UUID.randomUUID(), recipient, UUID.randomUUID(), Source.REPAIR_RETURN, quantity,
                new CustodyBytes(new byte[]{9, (byte) quantity}), NOW, NOW.plusSeconds(900));
    }
    private static void reserveReturns(List<ItemCustody> returns) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (ItemCustody custody : returns) ItemCustodyRepository.reserveReturn(c, custody);
                c.commit();
            } catch (SQLException | RuntimeException failure) { c.rollback(); throw failure; }
        }
    }

    private ItemCustody request(UUID recipient, Source source, int quantity) {
        return new ItemCustody(UUID.randomUUID(), recipient, UUID.randomUUID(), source, quantity,
                new CustodyBytes(new byte[]{0, 1, -1, 42}), NOW, NOW.plusSeconds(900));
    }
    private static ItemCustody copy(ItemCustody request) { return withNonce(request, request.requestNonce()); }
    private static ItemCustody withNonce(ItemCustody request, UUID nonce) {
        return new ItemCustody(nonce, request.playerId(), request.offerId(), request.source(), request.quantity(),
                new CustodyBytes(request.payload().bytes()), request.reservedAt(), request.expiresAt());
    }
    private Receipt found(ItemCustody request) throws SQLException { return repository.find(request.playerId(), request.requestNonce(), NOW).orElseThrow(); }
    private static ItemDeliveryEvidence confirmed(ItemCustody request) {
        return new ItemDeliveryEvidence(request.playerId(), request.payload().sha256(), "a".repeat(64), "b".repeat(64), 10, 10 + request.quantity(), 0, true);
    }
    private static <T> List<T> race(List<Callable<T>> calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(calls.size())) {
            var futures = calls.stream().map(call -> pool.submit(() -> { assertTrue(start.await(3, TimeUnit.SECONDS)); return call.call(); })).toList();
            start.countDown(); List<T> values = new ArrayList<>();
            for (var future : futures) values.add(future.get(15, TimeUnit.SECONDS));
            return values;
        }
    }
    private static void execute(String sql) throws SQLException {
        try (Connection c = database.getConnection(); var ps = c.prepareStatement(sql)) { ps.setQueryTimeout(5); ps.execute(); }
    }
    private static int count(String table) throws SQLException {
        try (Connection c = database.getConnection(); var ps = c.prepareStatement("SELECT COUNT(*) FROM " + table)) {
            ps.setQueryTimeout(5); try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getInt(1); }
        }
    }
}
