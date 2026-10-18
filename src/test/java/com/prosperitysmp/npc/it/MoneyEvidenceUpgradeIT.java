package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.commerce.CommerceOrder;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository.Budgets;
import com.prosperitysmp.npc.commerce.CustodyBytes;
import com.prosperitysmp.npc.commerce.ItemCustody.Source;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.EconomyEvent;
import com.prosperitysmp.npc.economy.EconomyEventRepository;
import com.prosperitysmp.npc.economy.EventSource;
import com.prosperitysmp.npc.economy.HeldClass;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V40 on a database that already holds money rows from before it: refunds, held payments and a commerce refund
 * decision. The migration replays cleanly after any partial run, changes only its own tables, adds only the refund
 * claims tables, backfills every old resolution as a refund and claims every old refund once, at the time it was
 * recorded, keeps every old row readable and every old proof passing, and only then accepts the new legacy-refusal
 * decision and a refund after a started credit.
 */
class MoneyEvidenceUpgradeIT {
    private static final Set<String> CHANGED = Set.of("npc_transfer_observations", "npc_commerce_money_evidence",
            "npc_transfer_resolutions", "npc_commerce_payment_resolutions", "npc_commerce_orders");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final UUID RESERVE = new UUID(0, 4001), OWNER = new UUID(0, 4002), WORLD = new UUID(0, 4003);
    private static final String PROVIDER = "a".repeat(64);
    private static DB mariaDb;
    private static String url;
    private static int port;

    private record Migration(int version, List<String> statements) { }

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        mariaDb.start();
        port = cfg.getPort();
        url = "jdbc:mariadb://localhost:" + port + "/";
    }

    @AfterAll
    static void stop() throws Exception {
        if (mariaDb != null) mariaDb.stop();
    }

    @Test
    @Timeout(300)
    void v40ReplaysAfterAnyPartialRunAndAltersOnlyItsOwnTables() throws Exception {
        List<Migration> all = migrations();
        Migration v40 = all.stream().filter(m -> m.version() == 40).findFirst().orElseThrow();
        assertEquals(40, v40.version());
        Map<String, String> clean = null;
        for (int prefix = 0; prefix <= v40.statements().size(); prefix++) {
            String name = freshSchema();
            try (Connection c = DriverManager.getConnection(url + name, "root", "")) {
                for (Migration m : all) if (m.version() < 40) for (String sql : m.statements()) execute(c, sql);
                Map<String, String> before = schema(c);
                for (String sql : v40.statements().subList(0, prefix)) execute(c, sql);
                for (String sql : v40.statements()) execute(c, sql);
                for (String sql : v40.statements()) execute(c, sql);
                Map<String, String> after = schema(c);
                if (clean == null) clean = after;
                assertEquals(clean, after, "interrupted after " + prefix + " statements");
                Set<String> expected = new java.util.TreeSet<>(before.keySet());
                expected.add("npc_refund_claims");
                expected.add("npc_refund_claim_payers");
                assertEquals(expected, after.keySet(), "only the refund claims tables are added, nothing dropped");
                for (var table : before.entrySet()) {
                    if (CHANGED.contains(table.getKey())) assertNotEquals(table.getValue(), after.get(table.getKey()), table.getKey());
                    else assertEquals(table.getValue(), after.get(table.getKey()), table.getKey());
                }
            } finally {
                drop(name);
            }
        }
    }

    @Test
    @Timeout(300)
    void rowsWrittenBeforeV40KeepTheirMeaningAndProofs() throws Exception {
        List<Migration> all = migrations();
        Migration v40 = all.stream().filter(m -> m.version() == 40).findFirst().orElseThrow();
        String name = freshSchema();
        try (Connection c = DriverManager.getConnection(url + name, "root", ""); Database db = new Database()) {
            for (Migration m : all) if (m.version() < 40) for (String sql : m.statements()) execute(c, sql);
            db.connect(new DatabaseSettings("localhost", port, name, "root", "", 4, 10_000L));
            UUID property = new UUID(4, 1);
            // Seed the V39 shape directly; today's repository also writes V45's backend id.
            execute(c, """
                    INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,
                        reference_rent_cents,asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled,row_version)
                    VALUES(?,?,'upgrade-1','downtown','room',2,1000,1000,?,0,80,TRUE,0)
                    """, property, WORLD, OWNER);
            execute(c, "INSERT INTO npc_property_funding(property_id,one_time_seed_cents) VALUES(?,5000)", property);
            execute(c, """
                    INSERT INTO npc_property_ownership(property_id,old_owner_uuid,new_owner_uuid,ownership_epoch,source_snapshot)
                    VALUES(?,NULL,?,0,'enroll')
                    """, property, OWNER);
            var events = new EconomyEventRepository(db);
            var journal = new TransferJournal(db, RESERVE);

            // A refunded debit, written the way V17-era code wrote it: no kind column yet.
            Handle refunded = started(journal, events, property, "refunded");
            observe(c, refunded.attemptId(), "DEBIT", true, true, 10.0, 100.0, 90.0, false);
            execute(c, "UPDATE npc_economy_events SET state='FAILED',paid_amount_cents=0,finalized_at=CURRENT_TIMESTAMP(3) WHERE event_id=?",
                    refunded.eventId());
            execute(c, "UPDATE npc_transfer_attempts SET state='COMPENSATED',outcome_code='OPERATOR_REFUND_VERIFIED' WHERE event_id=?",
                    refunded.eventId());
            // Before V40 each table kept its own resolution ids unique, so the two tables could share one.
            UUID sharedResolution = UUID.randomUUID();
            execute(c, """
                    INSERT INTO npc_transfer_resolutions(attempt_id,resolution_id,operator_ref,evidence_sha256,original_outcome,
                    refund_before_bits,refund_after_bits,created_at) VALUES(?,?,'staff:old',?,'DEBIT_UNCERTAIN',?,?,'2026-10-01 10:00:00.123')
                    """, refunded.attemptId(), sharedResolution, "e".repeat(64), bits(90.0), bits(100.0));
            // A held debit the old gate refused: stored as a throw with no response and an unchanged balance.
            Handle held = started(journal, events, property, "held");
            observe(c, held.attemptId(), "DEBIT", false, false, null, 100.001, 100.001, true);
            execute(c, "UPDATE npc_economy_events SET state='AMBIGUOUS' WHERE event_id=?", held.eventId());
            execute(c, "UPDATE npc_transfer_attempts SET state='AMBIGUOUS',outcome_code='DEBIT_UNCERTAIN' WHERE event_id=?", held.eventId());

            // Commerce: one held order and one recorded refund decision.
            var profile = ResidentCatalogue.betaCast().getFirst();
            new ResidentRepository(db).register(profile);
            var commerce = new CommerceOrderRepository(db, RESERVE);
            CommerceOrder heldOrder = order(profile.id()), refundedOrder = order(profile.id());
            for (CommerceOrder order : List.of(heldOrder, refundedOrder)) {
                commerce.reserve(order, new Budgets(100, 6400, 6400, 1_000_000));
                assertTrue(commerce.beginDebit(order, handle(order)).newlyStarted());
            }
            commerceEvidence(c, heldOrder.orderId(), false, false, null, 100.001, 100.001, true);
            execute(c, "UPDATE npc_commerce_orders SET state='AMBIGUOUS',outcome_code='DEBIT_UNCERTAIN' WHERE order_id=?", heldOrder.orderId());
            commerceEvidence(c, refundedOrder.orderId(), true, true, 10.0, 100.0, 90.0, false);
            execute(c, "UPDATE npc_commerce_orders SET state='COMPENSATED',outcome_code='VERIFIED_REFUNDED_DEBIT' WHERE order_id=?",
                    refundedOrder.orderId());
            CustodyBytes caseBytes = new CustodyBytes(new byte[]{1, 2, 3});
            execute(c, """
                    INSERT INTO npc_commerce_payment_resolutions(order_id,resolution_id,run_id,payer_id,reserve_id,provider_sha256,
                    request_sha256,cost_cents,operator_ref,case_ref,notes,decision,evidence_bytes,evidence_sha256,refund_before_bits,
                    refund_after_bits,resolved_at_millis,previous_outcome,recorded_at)
                    VALUES(?,?,?,?,?,?,?,1000,'operator','case-1','old refund','VERIFIED_REFUNDED_DEBIT',?,?,?,?,?,'DEBIT_UNCERTAIN',
                    '2026-10-02 11:00:00.456')
                    """, refundedOrder.orderId(), sharedResolution, RUN, refundedOrder.playerId(), RESERVE, PROVIDER,
                    refundedOrder.requestSha256(), caseBytes.bytes(), caseBytes.sha256(), bits(90.0), bits(100.0), NOW.toEpochMilli());

            // Before V40 the new decision and a refund after a started credit are both refused.
            assertThrows(SQLException.class, () -> legacyDecision(c, heldOrder));
            assertThrows(SQLException.class, () -> execute(c,
                    "UPDATE npc_commerce_orders SET credit_started=1 WHERE order_id=?", refundedOrder.orderId()));
            long observations = count(c, "npc_transfer_observations") + count(c, "npc_commerce_money_evidence");
            assertTrue(observations >= 4, "positive control: old evidence exists");

            for (String sql : v40.statements()) execute(c, sql);

            assertEquals(0, count(c, "npc_transfer_observations WHERE refused_before_call IS NOT NULL OR refusal_cause IS NOT NULL"));
            assertEquals(0, count(c, "npc_commerce_money_evidence WHERE refused_before_call IS NOT NULL OR refusal_cause IS NOT NULL"));
            assertEquals(1, count(c, "npc_transfer_resolutions"));
            assertEquals(1, count(c, "npc_transfer_resolutions WHERE kind='REFUND' AND credit_proof IS NULL"));
            // Both old refunds, one per table and each for its own payer, are claimed so neither can be used again,
            // even though they share a resolution id. Each claim keeps the time its refund was recorded.
            assertEquals(2, count(c, "npc_refund_claims WHERE refund_before_bits=" + bits(90.0) + " AND refund_after_bits=" + bits(100.0)));
            assertEquals(2, count(c, "npc_refund_claims"));
            assertEquals(2, count(c, "npc_refund_claims WHERE distinct_override=0"), "no old refund was asserted distinct by staff");
            assertEquals(1, count(c, "npc_refund_claims WHERE source='TRANSFER' AND recorded_at='2026-10-01 10:00:00.123' AND payer_id=X'"
                    + hex(OWNER) + "'"));
            assertEquals(1, count(c, "npc_refund_claims WHERE source='COMMERCE' AND recorded_at='2026-10-02 11:00:00.456' AND payer_id=X'"
                    + hex(refundedOrder.playerId()) + "'"));
            // Every old read still binds and every old proof still passes.
            var after = new TransferJournal(db, RESERVE);
            assertEquals(TransferJournal.State.COMPENSATED, after.find(refunded.eventId()).orElseThrow().state());
            assertEquals(TransferJournal.State.AMBIGUOUS, after.find(held.eventId()).orElseThrow().state());
            assertEquals(1, after.held(10).size());
            assertEquals(HeldClass.D_LEGACY_DEBIT_REFUSAL, after.held(10).getFirst().heldClass());
            assertEquals(CommerceOrderRepository.State.COMPENSATED,
                    commerce.find(refundedOrder.playerId(), refundedOrder.orderId()).orElseThrow().state());
            assertEquals(HeldClass.D_LEGACY_DEBIT_REFUSAL, commerce.held(10).getFirst().heldClass());
            // Now the new decision is accepted, and so is a refund after a started credit.
            legacyDecision(c, heldOrder);
            execute(c, "UPDATE npc_commerce_orders SET credit_started=1 WHERE order_id=?", refundedOrder.orderId());
            assertEquals(observations, count(c, "npc_transfer_observations") + count(c, "npc_commerce_money_evidence"),
                    "the migration writes no evidence");

            // Once a legacy refusal is recorded, running V40 again changes nothing: it is never turned into a refund.
            UUID legacyResolution = UUID.randomUUID();
            execute(c, """
                    INSERT INTO npc_transfer_resolutions(attempt_id,resolution_id,operator_ref,evidence_sha256,original_outcome,
                    refund_before_bits,refund_after_bits,kind,credit_proof) VALUES(?,?,'staff:new',?,'DEBIT_UNCERTAIN',NULL,NULL,'LEGACY_REFUSAL',NULL)
                    """, held.attemptId(), legacyResolution, "f".repeat(64));
            for (String sql : v40.statements()) execute(c, sql);
            assertEquals(1, count(c, "npc_transfer_resolutions WHERE kind='LEGACY_REFUSAL' AND refund_before_bits IS NULL"));
            assertEquals(1, count(c, "npc_transfer_resolutions WHERE kind='REFUND'"));
            assertEquals(2, count(c, "npc_refund_claims"), "a rerun claims nothing new, and a legacy refusal claims nothing");
        } finally {
            drop(name);
        }
    }

    private static final UUID RUN = new UUID(0, 4004);

    private static Handle started(TransferJournal journal, EconomyEventRepository events, UUID property, String key) throws SQLException {
        long event = events.insertPending(EconomyEvent.pending("upgrade|" + key, 1_000, EventSource.UPGRADE, property, OWNER, 0, OWNER,
                DAY.toString()));
        Handle handle = new Handle(UUID.randomUUID(), event, RUN, PROVIDER);
        assertTrue(journal.beginDebit(handle).newlyStarted());
        return handle;
    }

    private static CommerceOrder order(UUID resident) {
        UUID nonce = UUID.randomUUID(), player = UUID.randomUUID();
        return new CommerceOrder(CommerceOrder.idFor(nonce, player, Source.MERCHANT_GOODS, DAY), nonce, player, resident,
                Source.MERCHANT_GOODS, "merchant.stone", "STONE", 1, 1000, DAY, NOW, 0);
    }

    private static CommerceOrderRepository.Handle handle(CommerceOrder order) {
        return new CommerceOrderRepository.Handle(order.orderId(), RUN, order.playerId(), RESERVE, PROVIDER);
    }

    private static void legacyDecision(Connection c, CommerceOrder order) throws SQLException {
        execute(c, """
                INSERT INTO npc_commerce_payment_resolutions(order_id,resolution_id,run_id,payer_id,reserve_id,provider_sha256,
                request_sha256,cost_cents,operator_ref,case_ref,notes,decision,evidence_bytes,evidence_sha256,refund_before_bits,
                refund_after_bits,resolved_at_millis,previous_outcome)
                VALUES(?,?,?,?,?,?,?,1000,'operator','case-2','legacy refusal','VERIFIED_LEGACY_REFUSAL',?,?,NULL,NULL,?,'DEBIT_UNCERTAIN')
                """, order.orderId(), UUID.randomUUID(), RUN, order.playerId(), RESERVE, PROVIDER, order.requestSha256(),
                new byte[]{9}, new CustodyBytes(new byte[]{9}).sha256(), NOW.toEpochMilli());
    }

    private static void observe(Connection c, UUID attempt, String leg, boolean present, boolean success, Double reported,
                                Double before, Double after, boolean threw) throws SQLException {
        execute(c, "INSERT INTO npc_transfer_observations(attempt_id,leg,response_present,response_success,reported_bits,before_bits,"
                + "after_bits,threw) VALUES(?,?,?,?,?,?,?,?)", attempt, leg, present, success, bits(reported), bits(before), bits(after), threw);
    }

    private static void commerceEvidence(Connection c, UUID order, boolean present, boolean success, Double reported,
                                         Double before, Double after, boolean threw) throws SQLException {
        execute(c, "INSERT INTO npc_commerce_money_evidence(order_id,leg,response_present,response_success,reported_bits,before_bits,"
                + "after_bits,threw) VALUES(?,'DEBIT',?,?,?,?,?,?)", order, present, success, bits(reported), bits(before), bits(after), threw);
    }

    private static Long bits(Double value) { return value == null ? null : Double.doubleToRawLongBits(value); }

    private static String hex(UUID id) { return java.util.HexFormat.of().formatHex(Uuids.toBytes(id)); }

    private static List<Migration> migrations() throws Exception {
        var field = MigrationRunner.class.getDeclaredField("MIGRATIONS");
        field.setAccessible(true);
        List<Migration> result = new ArrayList<>();
        for (Object entry : (List<?>) field.get(null)) {
            var version = entry.getClass().getDeclaredMethod("version");
            var resource = entry.getClass().getDeclaredMethod("resource");
            version.setAccessible(true);
            resource.setAccessible(true);
            try (InputStream in = MoneyEvidenceUpgradeIT.class.getResourceAsStream("/" + resource.invoke(entry))) {
                assertNotNull(in);
                result.add(new Migration((Integer) version.invoke(entry),
                        MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))));
            }
        }
        return result;
    }

    private static String freshSchema() throws Exception {
        String name = "npc_v40_" + UUID.randomUUID().toString().replace("-", "");
        mariaDb.createDB(name);
        return name;
    }

    private static void drop(String name) throws SQLException {
        assertTrue(name.matches("npc_v40_[a-f0-9]{32}"), "only drop this disposable schema");
        try (Connection c = DriverManager.getConnection(url, "root", "")) { execute(c, "DROP DATABASE `" + name + "`"); }
    }

    private static Map<String, String> schema(Connection c) throws SQLException {
        Map<String, String> result = new TreeMap<>();
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        for (String table : tables) {
            try (PreparedStatement ps = c.prepareStatement("SHOW CREATE TABLE `" + table + "`"); ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                result.put(table, rs.getString(2).replaceAll(" AUTO_INCREMENT=\\d+", ""));
            }
        }
        return result;
    }

    private static long count(Connection c, String from) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + from); ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static void execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            for (int i = 0; i < values.length; i++) {
                Object v = values[i];
                if (v instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
                else if (v == null) ps.setNull(i + 1, java.sql.Types.BIGINT);
                else ps.setObject(i + 1, v);
            }
            ps.execute();
        }
    }
}
