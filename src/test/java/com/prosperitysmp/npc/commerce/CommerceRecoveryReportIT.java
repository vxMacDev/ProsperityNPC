package com.prosperitysmp.npc.commerce;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository.Budgets;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository.Handle;
import com.prosperitysmp.npc.commerce.ItemCustody.Source;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.db.V40Fixture;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.economy.TransferProof.Refusal;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The offline tool's read-only reports against an actual MariaDB: the held-order classes and recorded delivery counts. */
class CommerceRecoveryReportIT {
    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);
    private static final Budgets BUDGETS = new Budgets(100, 6400, 6400, 1_000_000);
    private static final Observation DEBIT = new Observation(true, true, 10.0, 100.0, 90.0, false);
    private static final Observation CREDIT = new Observation(true, true, 10.0, 20.0, 30.0, false);
    private static DB mariaDb;
    private static Path baseDir;
    private static Database database;
    private static UUID resident;
    private CommerceOrderRepository orders;
    private ItemCustodyRepository custody;
    private UUID run;
    private UUID reserve;

    @BeforeAll static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        baseDir = Files.createTempDirectory("npc-recovery-report-it-");
        cfg.setBaseDir(baseDir.toFile());
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build()); mariaDb.start();
        String schema = "npc_recovery_" + UUID.randomUUID().toString().replace("-", ""); mariaDb.createDB(schema);
        database = new Database();
        database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
        for (String migration : List.of("V1__initial_schema.sql", "V2__resident_identity.sql", "V21__item_custody.sql",
                "V22__commerce_orders.sql", "V25__commerce_resolution.sql", "V35__item_repairs.sql",
                "V36__town_contracts_partnerships.sql")) {
            try (InputStream input = CommerceRecoveryReportIT.class.getResourceAsStream("/db/migration/" + migration)) {
                assertNotNull(input, migration);
                for (String sql : MigrationRunner.splitStatements(new String(input.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
            }
        }
        try (var v40 = database.getConnection()) { V40Fixture.apply(v40); }
        var profile = ResidentCatalogue.betaCast().getFirst();
        new ResidentRepository(database).register(profile);
        resident = profile.id();
    }

    @AfterAll static void stop() throws Exception {
        try { if (database != null) database.close(); } finally {
            try { if (mariaDb != null) mariaDb.stop(); } finally { deleteBaseDir(); }
        }
    }

    private static void deleteBaseDir() throws IOException {
        if (baseDir == null || !Files.exists(baseDir)) return;
        try (var paths = Files.walk(baseDir)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    @BeforeEach void reset() throws SQLException {
        for (String table : List.of("npc_commerce_payment_resolutions", "npc_commerce_money_evidence", "npc_commerce_orders",
                "npc_commerce_material_days", "npc_commerce_days", "npc_commerce_players", "npc_item_custody_resolutions",
                "npc_item_delivery_evidence", "npc_item_custody", "npc_item_custody_players")) execute("DELETE FROM " + table);
        reserve = UUID.randomUUID();
        orders = new CommerceOrderRepository(database, reserve);
        custody = new ItemCustodyRepository(database);
        run = UUID.randomUUID();
    }

    @Test void anEmptyDatabaseReportsEveryClassAtZero() throws Exception {
        assertEquals("""
                Commerce orders holding payments: 0
                  D_LEGACY_DEBIT_REFUSAL: 0
                  C_LEGACY_CREDIT_REFUSAL: 0
                  R_REFUSED_CREDIT: 0
                  E_NOW_PROVABLE: 0
                  U_UNPROVABLE: 0""", CommerceRecoveryTool.classifyReport(orders));
    }

    @Test void eachHeldOrderIsCountedInItsOwnClassAndFundedOrdersAreNot() throws Exception {
        CommerceOrder refusedCredit = order();
        orders.reserve(refusedCredit, BUDGETS);
        Handle h = handle(refusedCredit);
        orders.beginDebit(refusedCredit, h);
        orders.recordDebit(h, DEBIT);
        orders.beginCredit(h);
        orders.recordCredit(h, Observation.refused(Refusal.LEASE_REFUSED, new IllegalStateException("private"), null, null), NOW);
        CommerceOrder legacy = order();
        orders.reserve(legacy, BUDGETS);
        orders.beginDebit(legacy, handle(legacy));
        orders.recordDebit(handle(legacy), new Observation(false, false, null, 100.001, 100.001, true));
        CommerceOrder unknown = order();
        orders.reserve(unknown, BUDGETS);
        orders.beginDebit(unknown, handle(unknown));
        orders.recordDebit(handle(unknown), new Observation(false, false, null, null, null, true));
        fund(order());
        orders.reserve(order(), BUDGETS);
        assertEquals("""
                Commerce orders holding payments: 3
                  D_LEGACY_DEBIT_REFUSAL: 1
                  C_LEGACY_CREDIT_REFUSAL: 0
                  R_REFUSED_CREDIT: 1
                  E_NOW_PROVABLE: 0
                  U_UNPROVABLE: 1""", CommerceRecoveryTool.classifyReport(orders));
    }

    @Test void anOrderStoppedAtItsDebitIntentIsHeldAsUnprovable() throws Exception {
        CommerceOrder started = order();
        orders.reserve(started, BUDGETS);
        orders.beginDebit(started, handle(started));
        assertEquals("""
                Commerce orders holding payments: 1
                  D_LEGACY_DEBIT_REFUSAL: 0
                  C_LEGACY_CREDIT_REFUSAL: 0
                  R_REFUSED_CREDIT: 0
                  E_NOW_PROVABLE: 0
                  U_UNPROVABLE: 1""", CommerceRecoveryTool.classifyReport(orders));
    }

    @Test void theReportReadsWithoutWritingAnything() throws Exception {
        CommerceOrder unknown = order();
        orders.reserve(unknown, BUDGETS);
        orders.beginDebit(unknown, handle(unknown));
        orders.recordDebit(handle(unknown), new Observation(false, false, null, null, null, true));
        int evidence = count("npc_commerce_money_evidence"), rows = count("npc_commerce_orders");
        String state = orders.find(unknown.playerId(), unknown.orderId()).orElseThrow().state().name();
        CommerceRecoveryTool.classifyReport(orders);
        CommerceRecoveryTool.classifyReport(orders);
        assertEquals(evidence, count("npc_commerce_money_evidence"));
        assertEquals(rows, count("npc_commerce_orders"));
        assertEquals(state, orders.find(unknown.playerId(), unknown.orderId()).orElseThrow().state().name());
    }

    @Test void theLegacyRowTimeIsTheStoredCreationTimeInAnyState() throws Exception {
        CommerceOrder funded = order();
        fund(funded);
        String stored = createdAt(funded.orderId());
        assertTrue(stored.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?"), stored);
        assertEquals(stored, CommerceRecoveryTool.legacyRowTime(orders, funded.playerId(), funded.orderId()));
        CommerceOrder reserved = order();
        orders.reserve(reserved, BUDGETS);
        assertEquals(createdAt(reserved.orderId()), CommerceRecoveryTool.legacyRowTime(orders, reserved.playerId(), reserved.orderId()));
    }

    @Test void theLegacyRowTimeOfAnUnknownOrAnotherPlayersOrderIsRefused() throws Exception {
        CommerceOrder reserved = order();
        orders.reserve(reserved, BUDGETS);
        var unknown = assertThrows(IllegalStateException.class,
                () -> CommerceRecoveryTool.legacyRowTime(orders, reserved.playerId(), UUID.randomUUID()));
        assertEquals("Unknown commerce order", unknown.getMessage());
        var other = assertThrows(IllegalStateException.class,
                () -> CommerceRecoveryTool.legacyRowTime(orders, UUID.randomUUID(), reserved.orderId()));
        assertEquals("Unknown commerce order", other.getMessage());
    }

    @Test void recordedEvidenceIsEmptyForAPlayerWithNoDeliveries() throws Exception {
        assertEquals(Optional.empty(), custody.recordedEvidence(UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test void recordedEvidenceIsEmptyUntilTheRuntimeRecordsCounts() throws Exception {
        ItemCustody request = request(UUID.randomUUID(), 4);
        custody.reserve(request, NOW);
        assertEquals(Optional.empty(), custody.recordedEvidence(request.playerId(), request.requestNonce()), "reserved");
        custody.beginDelivery(request, run, NOW);
        assertEquals(Optional.empty(), custody.recordedEvidence(request.playerId(), request.requestNonce()), "started");
        assertEquals(Optional.empty(), custody.recordedEvidence(request.playerId(), UUID.randomUUID()), "another nonce");
    }

    @Test void confirmedCountsReadBackExactly() throws Exception {
        ItemCustody request = request(UUID.randomUUID(), 4);
        custody.reserve(request, NOW);
        custody.beginDelivery(request, run, NOW);
        var evidence = new ItemDeliveryEvidence(request.playerId(), request.payload().sha256(), "a".repeat(64), "b".repeat(64),
                10, 14, 0, true);
        assertEquals(ItemCustodyRepository.State.DELIVERED, custody.recordDelivery(request, run, evidence).state());
        assertEquals(Optional.of(evidence), custody.recordedEvidence(request.playerId(), request.requestNonce()));
    }

    @Test void unconfirmedCountsReadBackExactlyAndHoldTheDelivery() throws Exception {
        ItemCustody request = request(UUID.randomUUID(), 4);
        custody.reserve(request, NOW);
        custody.beginDelivery(request, run, NOW);
        var evidence = new ItemDeliveryEvidence(request.playerId(), request.payload().sha256(), "c".repeat(64), "d".repeat(64),
                10, 12, 2, true);
        var held = custody.recordDelivery(request, run, evidence);
        assertEquals(ItemCustodyRepository.State.AMBIGUOUS, held.state());
        assertEquals("EVIDENCE_UNCERTAIN", held.outcome());
        assertEquals(Optional.of(evidence), custody.recordedEvidence(request.playerId(), request.requestNonce()));
    }

    @Test void anotherPlayerCannotReadADeliverysCounts() throws Exception {
        UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
        ItemCustody mine = request(owner, 4);
        custody.reserve(mine, NOW);
        custody.beginDelivery(mine, run, NOW);
        custody.recordDelivery(mine, run, new ItemDeliveryEvidence(owner, mine.payload().sha256(), "a".repeat(64), "b".repeat(64),
                0, 4, 0, true));
        ItemCustody theirs = request(stranger, 2);
        custody.reserve(theirs, NOW);
        assertEquals(Optional.empty(), custody.recordedEvidence(stranger, mine.requestNonce()));
        assertTrue(custody.recordedEvidence(owner, mine.requestNonce()).isPresent());
    }

    @Test void aFundedOrdersClaimIsPaidCustodyAndAPlainReservationIsNot() throws Exception {
        CommerceOrder funded = order();
        fund(funded);
        var claim = orders.claimItems(funded, new CustodyBytes(new byte[]{7, 7}), NOW);
        assertTrue(claim.newlyReserved());
        assertEquals(ItemCustodyRepository.State.RESERVED, claim.custody().state());
        ItemCustody plain = request(UUID.randomUUID(), 2);
        custody.reserve(plain, NOW);
        try (Connection c = database.getConnection()) {
            assertTrue(CommerceOrderRepository.isPaidCustody(c, claim.custody().request()));
            assertFalse(CommerceOrderRepository.isPaidCustody(c, plain));
        }
    }

    @Test void aClaimReplaysOnlyWithTheSamePayload() throws Exception {
        CommerceOrder funded = order();
        fund(funded);
        var first = orders.claimItems(funded, new CustodyBytes(new byte[]{7, 7}), NOW);
        var again = orders.claimItems(funded, new CustodyBytes(new byte[]{7, 7}), NOW.plusSeconds(5));
        assertFalse(again.newlyReserved());
        assertEquals(first.custody().request(), again.custody().request());
        var conflict = assertThrows(SQLException.class, () -> orders.claimItems(funded, new CustodyBytes(new byte[]{7, 8}), NOW));
        assertEquals("Commerce custody replay conflicts", conflict.getMessage());
        assertEquals(1, count("npc_item_custody"));
    }

    @Test void paidCustodyRefusesARequestThatDiffersFromTheStoredOne() throws Exception {
        CommerceOrder funded = order();
        fund(funded);
        ItemCustody stored = orders.claimItems(funded, new CustodyBytes(new byte[]{7, 7}), NOW).custody().request();
        ItemCustody laterExpiry = new ItemCustody(stored.requestNonce(), stored.playerId(), stored.offerId(), stored.source(),
                stored.quantity(), stored.payload(), stored.reservedAt(), stored.expiresAt().minusSeconds(1));
        ItemCustody otherPayload = new ItemCustody(stored.requestNonce(), stored.playerId(), stored.offerId(), stored.source(),
                stored.quantity(), new CustodyBytes(new byte[]{7, 8}), stored.reservedAt(), stored.expiresAt());
        ItemCustody moreItems = new ItemCustody(stored.requestNonce(), stored.playerId(), stored.offerId(), stored.source(),
                stored.quantity() + 1, stored.payload(), stored.reservedAt(), stored.expiresAt());
        try (Connection c = database.getConnection()) {
            assertEquals("Paid custody payload binding conflicts",
                    assertThrows(SQLException.class, () -> CommerceOrderRepository.isPaidCustody(c, laterExpiry)).getMessage());
            assertEquals("Paid custody payload binding conflicts",
                    assertThrows(SQLException.class, () -> CommerceOrderRepository.isPaidCustody(c, otherPayload)).getMessage());
            assertEquals("Paid commerce custody binding conflicts",
                    assertThrows(SQLException.class, () -> CommerceOrderRepository.isPaidCustody(c, moreItems)).getMessage());
        }
    }

    private CommerceOrder order() {
        UUID nonce = UUID.randomUUID(), player = UUID.randomUUID();
        return new CommerceOrder(CommerceOrder.idFor(nonce, player, Source.MERCHANT_GOODS, DAY), nonce, player, resident,
                Source.MERCHANT_GOODS, "merchant.stone", "STONE", 1, 1000, DAY, NOW, 0);
    }

    private Handle handle(CommerceOrder order) {
        return new Handle(order.orderId(), run, order.playerId(), reserve, "a".repeat(64));
    }

    private void fund(CommerceOrder order) throws SQLException {
        orders.reserve(order, BUDGETS);
        Handle h = handle(order);
        orders.beginDebit(order, h);
        orders.recordDebit(h, DEBIT);
        orders.beginCredit(h);
        orders.recordCredit(h, CREDIT, NOW);
    }

    private static ItemCustody request(UUID player, int quantity) {
        return new ItemCustody(UUID.randomUUID(), player, UUID.randomUUID(), Source.WORKER_OUTPUT, quantity,
                new CustodyBytes(new byte[]{0, 1, -1, 42}), NOW, NOW.plusSeconds(900));
    }

    private static String createdAt(UUID orderId) throws SQLException {
        try (Connection c = database.getConnection();
             var ps = c.prepareStatement("SELECT created_at FROM npc_commerce_orders WHERE order_id=?")) {
            ps.setBytes(1, Uuids.toBytes(orderId));
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getString(1); }
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = database.getConnection(); var ps = c.prepareStatement(sql)) { ps.setQueryTimeout(5); ps.execute(); }
    }

    private static int count(String table) throws SQLException {
        try (Connection c = database.getConnection(); var ps = c.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getInt(1); }
    }
}
