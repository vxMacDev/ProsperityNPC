package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.market.HousingMoveRepository;
import com.prosperitysmp.npc.market.HousingMoveRepository.Receipt;
import com.prosperitysmp.npc.market.HousingMoveRepository.Result;
import com.prosperitysmp.npc.market.HousingMoveRequest;
import com.prosperitysmp.npc.market.HousingMoveRequest.Policy;
import com.prosperitysmp.npc.market.HousingPolicy;
import com.prosperitysmp.npc.market.HousingRegistry;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Custody, receipt and quota boundary only; no ARM authorization, rating producer or scheduler. */
@Timeout(45)
class HousingMoveRepositoryIT {
    private static final String V10 = "/db/migration/V10__housing_moves.sql";
    private static final UUID RESIDENT = new UUID(0, 17);
    private static final UUID PROPERTY = new UUID(0, 800);
    private static final UUID OWNER = new UUID(0, 900);
    private static final UUID WORLD = UUID.fromString("fedcba98-7654-3210-89ab-cdef01234567");
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final Instant NOW = DAY.atTime(12, 0).atZone(ZONE).toInstant();
    // Fixture values, not a production policy recommendation.
    private static final Policy POLICY = new Policy(10, 5, 2, 7);
    private static final HousingPolicy STANDARD = new HousingPolicy(
            WORLD, "downtown", "room", HousingPolicy.Tier.STANDARD, 1200, 40);
    private static final List<String> CHANGED_TABLES = List.of(
            "npc_tenants", "npc_housing_days", "npc_housing_moves");
    private static DB mariaDb;
    private static Database database;
    private static DatabaseSettings settings;
    private static String schemaName;
    private static List<String> tables;
    private final List<String> triggers = new ArrayList<>();
    private MutableClock clock;
    private HousingMoveRepository repo;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            schemaName = "npc_hmove_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schemaName);
            settings = new DatabaseSettings("localhost", cfg.getPort(), schemaName, "root", "", 6, 10_000L);
            database = new Database();
            database.connect(settings);
            applyResource("/db/migration/V1__initial_schema.sql");
            applyResource("/db/migration/V2__resident_identity.sql");
            applyResource("/db/migration/V9__housing_registry.sql");
            applyResource(V10);
            applyResource("/db/migration/V16__chronicle_outbox.sql");
            tables = rows("SELECT TABLE_NAME AS name FROM information_schema.TABLES WHERE TABLE_SCHEMA=? "
                    + "AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME", schemaName).stream()
                    .map(row -> row.get("name")).toList();
        } catch (Exception | AssertionError failure) {
            try { stopDatabase(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        try {
            if (database != null) database.close();
        } finally {
            if (mariaDb != null) {
                DB stopping = mariaDb;
                mariaDb = null;
                stopping.stop();
            }
        }
    }

    @BeforeEach
    void freshTables() throws SQLException {
        for (String table : List.of("npc_chronicle_send_intents", "npc_chronicle_attempts", "npc_chronicle_progress", "npc_chronicle_outbox", "npc_housing_moves", "npc_housing_days", "npc_housing_members",
                "npc_resident_memory", "npc_resident_profiles", "npc_tenants", "npc_economy_events",
                "npc_operator_daily", "npc_operator_reputation", "npc_benefit_unit_members",
                "npc_benefit_units", "npc_property_funding", "npc_properties", "npc_housing_registry_lock")) {
            execute("DELETE FROM " + table);
        }
        execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (1)");
        clock = new MutableClock(NOW, ZoneOffset.UTC);
        repo = reopened();
        resident(RESIDENT, STANDARD);
        property(PROPERTY, WORLD, "downtown", "room");
        execute("UPDATE npc_resident_profiles SET name='Authored Resident',occupation='CARPENTER',"
                + "household='Chosen House',generation=8 WHERE resident_id=?", RESIDENT);
        new ResidentRepository(database).introduce(RESIDENT, OWNER, NOW.minusSeconds(60), DAY);
        execute("INSERT INTO npc_property_funding(property_id,one_time_seed_cents,successful_cash_sinks_cents,"
                + "successful_cash_payouts_cents) VALUES (?,5000,4321,1234)", PROPERTY);
        execute("""
                INSERT INTO npc_economy_events(idempotency_key,requested_amount_cents,paid_amount_cents,source,
                  direction,property_id,counterparty_uuid,economy_day,state)
                VALUES ('hmove-preservation',1200,1200,'RENT','PAYOUT',?,?,'2026-09-07','PAID')
                """, PROPERTY, OWNER);
    }

    @AfterEach
    void removeFaults() throws SQLException {
        for (String trigger : triggers) execute("DROP TRIGGER IF EXISTS " + trigger);
        triggers.clear();
        execute("DROP TABLE IF EXISTS housing_it_barrier");
    }

    @Test
    void quoteAndMissingReceiptAreReadOnlyAndBindAllThirteenFields() throws SQLException {
        var before = snapshot();
        UUID requestId = UUID.randomUUID();
        HousingMoveRequest request = repo.quote(requestId, RESIDENT, PROPERTY, 1, DAY);
        assertEquals(new HousingMoveRequest(requestId, RESIDENT, PROPERTY, OWNER, WORLD, "downtown", "room",
                3, 11, 0, -1, 1, DAY), request);
        assertTrue(repo.find(requestId).isEmpty());
        assertEquals(before, snapshot());
    }
    @Test void chronicleReadsCommittedReceiptWithoutChangingAnyTable() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);var committed=repo.apply(request,POLICY).receipt();
        var reader=new com.prosperitysmp.npc.publication.ChronicleAdmissionRepository(database,clock,ZONE,4);
        var before=snapshot();var batch=reader.read(List.of(request.requestId()));
        assertEquals(committed,batch.receipts().get(request.requestId()));assertEquals(before,snapshot());
        assertThrows(UnsupportedOperationException.class,()->batch.receipts().clear());
        try(var read=com.prosperitysmp.npc.db.ReadSnapshot.open(database,clock)) {
            var borrowed=reader.read(read,List.of(request.requestId()));
            assertEquals(batch.receipts(),borrowed.receipts());
            assertEquals(read.capturedAt(),borrowed.capturedAt());
            assertFalse(read.connection(database).isClosed());
            try(var statement=read.connection(database).createStatement()) {
                SQLException rejected=assertThrows(SQLException.class,()->statement.executeUpdate("DELETE FROM npc_tenants"));
                assertEquals(1792,rejected.getErrorCode());
            }
        }
        assertEquals(before,snapshot());
    }
    @Test void chronicleRejectsMissingCorruptAndChangedPolicyEvidence() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);
        var reader=new com.prosperitysmp.npc.publication.ChronicleAdmissionRepository(database,clock,ZONE,4);
        var before=snapshot();assertThrows(SQLException.class,()->reader.read(List.of(request.requestId(),UUID.randomUUID())));
        assertEquals(before,snapshot());
        execute("UPDATE npc_housing_days SET server_limit=server_limit+1");before=snapshot();
        assertThrows(SQLException.class,()->reader.read(List.of(request.requestId())));assertEquals(before,snapshot());
        execute("UPDATE npc_housing_days SET server_limit=server_limit-1");
        execute("UPDATE npc_housing_moves SET payload_hash=? WHERE request_id=?","0".repeat(64),request.requestId());before=snapshot();
        assertThrows(SQLException.class,()->reader.read(List.of(request.requestId())));assertEquals(before,snapshot());
    }
    @Test void chronicleBatchBoundsAndEmptyReadDoNotNeedConnection() throws Exception {
        var reader=new com.prosperitysmp.npc.publication.ChronicleAdmissionRepository(new Database(),clock,ZONE,4);
        assertTrue(reader.read(List.of()).receipts().isEmpty());
        var id=UUID.randomUUID();assertThrows(IllegalArgumentException.class,()->reader.read(List.of(id,id)));
        assertThrows(IllegalArgumentException.class,()->reader.read(java.util.stream.IntStream.range(0,129)
                .mapToObj(i->new UUID(0,i)).toList()));
    }

    @Test void chronicleBindsEachHistoricalDayPolicyAndSnapshotTime() throws Exception {
        var first=repo.apply(quote(RESIDENT,PROPERTY,0),POLICY).receipt();
        UUID other=new UUID(0,18);resident(other,STANDARD);
        clock.set(NOW.plusSeconds(86400));
        var next=repo.quote(UUID.randomUUID(),other,PROPERTY,1,DAY.plusDays(1));
        var second=repo.apply(next,new Policy(9,4,1,8)).receipt();
        var reader=new com.prosperitysmp.npc.publication.ChronicleAdmissionRepository(database,clock,ZONE,4);
        var ids=List.of(next.requestId(),first.request().requestId());var before=snapshot();
        assertEquals(Map.of(first.request().requestId(),first,next.requestId(),second),reader.read(ids).receipts());
        clock.set(NOW.plusSeconds(86400).minusMillis(1));
        assertThrows(SQLException.class,()->reader.read(ids));
        assertEquals(before,snapshot());
    }

    @Test void chronicleReadsMaximumBatchOfActualCommittedAdmissions() throws Exception {
        var policy=new Policy(512,512,4,7);
        var expected=new LinkedHashMap<UUID,Receipt>();
        for(int i=0;i<128;i++) {
            UUID propertyId=new UUID(0,10_000+i/4);
            if(i%4==0)property(propertyId,WORLD,"downtown","room");
            UUID residentId=new UUID(0,20_000+i);resident(residentId,STANDARD);
            var request=quote(residentId,propertyId,i%4);
            expected.put(request.requestId(),repo.apply(request,policy).receipt());
        }
        var before=snapshot();
        var reader=new com.prosperitysmp.npc.publication.ChronicleAdmissionRepository(database,clock,ZONE,4);
        assertEquals(expected,reader.read(new ArrayList<>(expected.keySet())).receipts());
        UUID target=new UUID(0,10_000);
        List<UUID> keys=expected.values().stream().filter(r->r.request().propertyId().equals(target))
                .map(r->r.request().requestId()).sorted((a,b)->java.util.Arrays.compareUnsigned(Uuids.toBytes(a),Uuids.toBytes(b))).toList();
        for(var plan:List.of(
                rows("EXPLAIN SELECT request_id FROM npc_housing_moves WHERE property_id=? ORDER BY request_id DESC LIMIT 1",target),
                rows("EXPLAIN SELECT request_id FROM npc_housing_moves WHERE property_id=? AND request_id<=? AND request_id>? ORDER BY request_id LIMIT 129",
                        target,keys.getLast(),keys.getFirst()))) {
            assertEquals(1,plan.size());assertNotNull(plan.getFirst().get("key"));
            assertNotEquals("ALL",plan.getFirst().get("type"));
            assertFalse(plan.getFirst().getOrDefault("Extra","").toLowerCase(Locale.ROOT).contains("filesort"));
            System.out.println("Chronicle discovery plan on 128 actual receipts / 32 properties: "+plan);
        }
        assertEquals(before,snapshot());
    }

    @Test void chronicleRejectsOrphanedPolicyWithoutRepairingSource() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);
        try(Connection c=database.getConnection()) {
            execute(c,"SET SESSION foreign_key_checks=0");
            try {execute(c,"DELETE FROM npc_housing_days");}
            finally {execute(c,"SET SESSION foreign_key_checks=1");}
        }
        var before=snapshot();
        var reader=new com.prosperitysmp.npc.publication.ChronicleAdmissionRepository(database,clock,ZONE,4);
        SQLException failure=assertThrows(SQLException.class,()->reader.read(List.of(request.requestId())));
        assertEquals("Missing admission policy",failure.getMessage());
        assertEquals(before,snapshot());
    }

    @Test void chronicleBorrowedSnapshotCannotMixLaterPolicyMutation() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);var receipt=repo.apply(request,POLICY).receipt();
        var reader=new com.prosperitysmp.npc.publication.ChronicleAdmissionRepository(database,clock,ZONE,4);
        try(var read=com.prosperitysmp.npc.db.ReadSnapshot.open(database,clock)) {
            assertEquals(receipt,reader.read(read,List.of(request.requestId())).receipts().get(request.requestId()));
            execute("UPDATE npc_housing_days SET cooldown_days=8");
            var afterWriter=snapshot();
            assertEquals(receipt,reader.read(read,List.of(request.requestId())).receipts().get(request.requestId()));
            assertEquals(afterWriter,snapshot());
        }
        var afterWriter=snapshot();
        assertThrows(SQLException.class,()->reader.read(List.of(request.requestId())));
        assertEquals(afterWriter,snapshot());
    }

    @Test void chronicleOutboxStagesOnlyHeldDraftAndReplaysAfterPoolRestart() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);
        var before=snapshot();UUID generation=UUID.randomUUID();
        var held=outbox().stage(request.requestId(),chronicleMapping(),generation);
        var after=snapshot();var sources=new LinkedHashMap<>(after);sources.put("npc_chronicle_outbox",List.of());
        assertEquals(before,sources);
        assertEquals("HELD",after.get("npc_chronicle_outbox").getFirst().get("state"));
        database.close();database=new Database();database.connect(settings);repo=reopened();
        assertEquals(held,outbox().stage(request.requestId(),chronicleMapping(),generation));
        assertEquals(after,snapshot());assertEquals(1,count("npc_chronicle_outbox"));
        applyResource("/db/migration/V16__chronicle_outbox.sql");assertEquals(after,snapshot());
        assertThrows(SQLException.class,()->execute("UPDATE npc_chronicle_outbox SET state='PUBLISHED'"));
        assertEquals(after,snapshot());
    }

    @Test void chronicleOutboxConflictsNeverOverwriteEarlierDraft() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        outbox().stage(request.requestId(),chronicleMapping(),generation);var before=snapshot();
        assertThrows(SQLException.class,()->outbox().stage(request.requestId(),chronicleMapping(),UUID.randomUUID()));
        assertEquals(before,snapshot());
        execute("UPDATE npc_chronicle_outbox SET payload_sha256=?","0".repeat(64));before=snapshot();
        assertThrows(SQLException.class,()->outbox().stage(request.requestId(),chronicleMapping(),generation));
        assertEquals(before,snapshot());
    }

    @Test void chronicleOutboxInsertFailurePreservesCommittedMoveAndRetryStagesOnce() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        createTrigger("chronicle_insert_fault","BEFORE INSERT ON npc_chronicle_outbox FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture insert failure'");
        var before=snapshot();assertThrows(SQLException.class,()->outbox().stage(request.requestId(),chronicleMapping(),generation));
        assertEquals(before,snapshot());
        execute("DROP TRIGGER chronicle_insert_fault");triggers.remove("chronicle_insert_fault");
        outbox().stage(request.requestId(),chronicleMapping(),generation);
        assertEquals(1,count("npc_chronicle_outbox"));assertEquals(1,count("npc_housing_moves"));assertEquals(1,count("npc_tenants"));
    }

    @Test void chronicleOutboxRequiresUnchangedHistoricalMappingAndRealReceipt() throws Exception {
        UUID generation=UUID.randomUUID();var before=snapshot();
        assertThrows(SQLException.class,()->outbox().stage(UUID.randomUUID(),chronicleMapping(),generation));assertEquals(before,snapshot());
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);
        execute("UPDATE npc_properties SET row_version=row_version+1");before=snapshot();
        assertThrows(SQLException.class,()->outbox().stage(request.requestId(),chronicleMapping(),generation));assertEquals(before,snapshot());
        execute("UPDATE npc_properties SET row_version=row_version-1,enabled=FALSE");before=snapshot();
        assertThrows(SQLException.class,()->outbox().stage(request.requestId(),chronicleMapping(),generation));assertEquals(before,snapshot());
        execute("UPDATE npc_properties SET enabled=2");before=snapshot();
        assertEquals("Chronicle property unavailable",assertThrows(SQLException.class,
                ()->outbox().stage(request.requestId(),chronicleMapping(),generation)).getMessage());
        assertEquals(before,snapshot());assertEquals(0,count("npc_chronicle_outbox"));
    }

    private com.prosperitysmp.npc.publication.ChronicleOutboxRepository receiptOutbox() {
        return new com.prosperitysmp.npc.publication.ChronicleOutboxRepository(database,clock,ZONE,4,true);
    }
    @Test void chronicleReceiptEvidenceSurvivesLaterRentChangeAndStaysIdempotent() throws Exception {
        UUID generation=UUID.randomUUID();
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);
        // Later unrelated edit (asking rent change bumps the property revision).
        execute("UPDATE npc_properties SET asking_rent_cents=asking_rent_cents+100,row_version=row_version+1");
        var before=snapshot();
        assertThrows(SQLException.class,()->outbox().stage(request.requestId(),chronicleMapping(),generation));
        assertEquals(before,snapshot());
        receiptOutbox().stage(request.requestId(),chronicleMapping(),generation);
        assertEquals(1,count("npc_chronicle_outbox"));
        // Replay stays idempotent.
        receiptOutbox().stage(request.requestId(),chronicleMapping(),generation);
        assertEquals(1,count("npc_chronicle_outbox"));
    }
    @Test void chronicleReceiptEvidenceFlagOnRefusesForgedReceiptEvenAfterLaterEdit() throws Exception {
        UUID generation=UUID.randomUUID();
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);
        execute("UPDATE npc_properties SET asking_rent_cents=asking_rent_cents+100,row_version=row_version+1");
        execute("UPDATE npc_housing_moves SET property_revision=property_revision-1");var before=snapshot();
        assertThrows(SQLException.class,()->receiptOutbox().stage(request.requestId(),chronicleMapping(),generation));
        assertEquals(before,snapshot());assertEquals(0,count("npc_chronicle_outbox"));
        execute("UPDATE npc_housing_moves SET property_revision=property_revision+1");
        execute("UPDATE npc_housing_moves SET payload_hash=?","0".repeat(64));before=snapshot();
        assertThrows(SQLException.class,()->receiptOutbox().stage(request.requestId(),chronicleMapping(),generation));
        assertEquals(before,snapshot());assertEquals(0,count("npc_chronicle_outbox"));
    }
    @Test void chronicleReceiptEvidenceRefusesRegressedPropertyAndWrongMapping() throws Exception {
        UUID generation=UUID.randomUUID();
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);
        assertThrows(SQLException.class,()->receiptOutbox().stage(UUID.randomUUID(),chronicleMapping(),generation));
        var other=new com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval(new UUID(0,9999),WORLD,"fixture-x","main");
        assertThrows(SQLException.class,()->receiptOutbox().stage(request.requestId(),other,generation));
        execute("UPDATE npc_properties SET row_version=row_version-1");var before=snapshot();
        assertThrows(SQLException.class,()->receiptOutbox().stage(request.requestId(),chronicleMapping(),generation));
        assertEquals(before,snapshot());assertEquals(0,count("npc_chronicle_outbox"));
    }

    @Test void concurrentChronicleStagingHasOneImmutableDraft() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            Callable<com.prosperitysmp.npc.publication.ChronicleOutboxRepository.Held> task=()->{
                assertTrue(start.await(5,TimeUnit.SECONDS));return outbox().stage(request.requestId(),chronicleMapping(),generation);
            };
            var first=workers.submit(task);var second=workers.submit(task);start.countDown();
            assertEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,count("npc_chronicle_outbox"));assertEquals(1,count("npc_housing_moves"));
    }

    @Test void chronicleLostCommitAcknowledgmentPreservesDraftAndExactRetryDoesNotRepeatMove() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var sources=snapshot();
        try(var probe=new ChronicleProbe()) {
            probe.loseCommit.set(true);
            var repository=new com.prosperitysmp.npc.publication.ChronicleOutboxRepository(probe.routed,clock,ZONE,4);
            SQLException lost=assertThrows(SQLException.class,()->repository.stage(request.requestId(),chronicleMapping(),generation));
            assertEquals("fixture lost commit acknowledgment",lost.getMessage());assertEquals(1,probe.commits.get());
            var committed=snapshot();assertEquals(1,committed.get("npc_chronicle_outbox").size());
            var withoutQueue=new LinkedHashMap<>(committed);withoutQueue.put("npc_chronicle_outbox",List.of());
            assertEquals(sources,withoutQueue);
            repository.stage(request.requestId(),chronicleMapping(),generation);
            assertEquals(2,probe.commits.get());assertEquals(committed,snapshot());
        }
    }

    @Test void chronicleConcurrentDuplicateWaitsBehindActualUncommittedInsert() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        String gate="chronicle-"+UUID.randomUUID();
        createTrigger("chronicle_after_insert_gate","AFTER INSERT ON npc_chronicle_outbox FOR EACH ROW BEGIN "
                +"IF GET_LOCK('"+gate+"',5)<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test gate timeout'; END IF; "
                +"DO RELEASE_LOCK('"+gate+"'); END");
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<com.prosperitysmp.npc.publication.ChronicleOutboxRepository.Held> first,second;
            try(Connection blocker=database.getConnection()) {
                assertEquals(1,namedLock(blocker,"GET_LOCK",gate));
                try {
                    long blockerId=connectionId(blocker);
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                    first=workers.submit(()->outbox().stage(request.requestId(),chronicleMapping(),generation));
                    awaitAfterTriggerGate(gate,blockerId);
                    second=workers.submit(()->outbox().stage(request.requestId(),chronicleMapping(),generation));
                    boolean observed=false;
                    List<Map<String,String>> diagnostic=List.of();
                    while(System.nanoTime()<deadline) {
                        diagnostic=rows("SELECT ID,STATE,INFO FROM information_schema.PROCESSLIST WHERE DB=? AND ID<>CONNECTION_ID() "
                                +"AND ID<>?",schemaName,blockerId);
                        var pending=diagnostic.stream().filter(r->r.get("INFO")!=null
                                && (r.get("INFO").stripLeading().startsWith("INSERT INTO npc_chronicle_outbox")
                                    || r.get("INFO").stripLeading().startsWith("SET STATEMENT max_statement_time=5 FOR INSERT INTO npc_chronicle_outbox"))
                                && !"user lock".equalsIgnoreCase(r.get("STATE"))).toList();
                        if(pending.size()==1){observed=true;break;}Thread.sleep(10);
                    }
                    assertTrue(observed,"duplicate INSERT must reach the server while original is held after insertion: "+diagnostic);
                    assertFalse(first.isDone());assertFalse(second.isDone());
                } finally {assertEquals(1,namedLock(blocker,"RELEASE_LOCK",gate));}
            }
            assertEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertEquals(1,count("npc_chronicle_outbox"));assertEquals(1,count("npc_housing_moves"));
        } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));}
    }

    private com.prosperitysmp.npc.publication.ChronicleOutboxRepository outbox() {
        return new com.prosperitysmp.npc.publication.ChronicleOutboxRepository(database,clock,ZONE,4);
    }
    private com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval chronicleMapping() {
        return new com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval(PROPERTY,WORLD,"fixture-"+PROPERTY,"main");
    }

    @Test void chronicleRealWorkerPersistsUnresolvedThenResolvesAfterPoolRestart() throws Exception {
        UUID first=new UUID(0,1),second=new UUID(0,2),generation=UUID.randomUUID();
        repo.apply(repo.quote(first,RESIDENT,PROPERTY,0,DAY),POLICY);
        UUID other=new UUID(0,18);resident(other,STANDARD);repo.apply(repo.quote(second,other,PROPERTY,1,DAY),POLICY);
        var discovery=new com.prosperitysmp.npc.publication.ChronicleDiscoveryRepository(database,clock);
        var journal=new com.prosperitysmp.npc.publication.ChronicleRecoveryJournal(database);
        var queue=outbox();
        var worker=new com.prosperitysmp.npc.publication.ChronicleRecoveryWorker(discovery::page,(id,s,g)->{
            if(id.equals(first))throw new SQLException("fixture lost staging response");return queue.stage(id,s,g);
        },journal);
        try(var executor=Executors.newSingleThreadExecutor()) {
            assertEquals(new com.prosperitysmp.npc.publication.ChronicleRecoveryWorker.Result(true,1,1),
                    executor.submit(()->worker.step(chronicleMapping(),generation,2)).get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,journal.load(chronicleMapping(),generation).revision());
        assertEquals(List.of(Map.of("outcome","UNRESOLVED","ever_unresolved","1")),rows("SELECT outcome,ever_unresolved FROM npc_chronicle_attempts WHERE request_id=?",first));
        database.close();database=new Database();database.connect(settings);repo=reopened();
        var resumedJournal=new com.prosperitysmp.npc.publication.ChronicleRecoveryJournal(database);
        var resumedDiscovery=new com.prosperitysmp.npc.publication.ChronicleDiscoveryRepository(database,clock);
        var resumedQueue=outbox();var resumed=new com.prosperitysmp.npc.publication.ChronicleRecoveryWorker(resumedDiscovery::page,resumedQueue::stage,resumedJournal);
        try(var executor=Executors.newSingleThreadExecutor()) {
            assertEquals(new com.prosperitysmp.npc.publication.ChronicleRecoveryWorker.Result(true,2,0),
                    executor.submit(()->resumed.step(chronicleMapping(),generation,2)).get(10,TimeUnit.SECONDS));
        }
        assertEquals(2,resumedJournal.load(chronicleMapping(),generation).revision());
        assertEquals(List.of(Map.of("outcome","STAGED","ever_unresolved","1")),rows("SELECT outcome,ever_unresolved FROM npc_chronicle_attempts WHERE request_id=?",first));
        assertEquals(2,count("npc_chronicle_outbox"));assertEquals(2,count("npc_housing_moves"));assertEquals(2,count("npc_tenants"));
    }

    @Test void chronicleJournalAtomicRollbackAndActualLostCommitReply() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var journal=new com.prosperitysmp.npc.publication.ChronicleRecoveryJournal(database);
        var expected=journal.load(chronicleMapping(),generation);
        var page=new com.prosperitysmp.npc.publication.ChronicleDiscoveryRepository(database,clock).page(chronicleMapping(),generation,null,1);
        var attempts=List.of(new com.prosperitysmp.npc.publication.ChronicleRecoveryWorker.Attempt(request.requestId(),
                com.prosperitysmp.npc.publication.ChronicleRecoveryWorker.Outcome.UNRESOLVED,null));
        createTrigger("chronicle_checkpoint_fail","BEFORE UPDATE ON npc_chronicle_progress FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture checkpoint failure'");
        var before=snapshot();assertThrows(SQLException.class,()->journal.commit(expected,page,attempts));assertEquals(before,snapshot());
        dropTrigger("chronicle_checkpoint_fail");
        try(var probe=new ChronicleProbe()) {
            var routed=new com.prosperitysmp.npc.publication.ChronicleRecoveryJournal(probe.routed);probe.loseCommit.set(true);
            assertEquals("fixture lost commit acknowledgment",assertThrows(SQLException.class,()->routed.commit(expected,page,attempts)).getMessage());
            assertEquals(1,probe.commits.get());var committed=snapshot();assertEquals(1,journal.load(chronicleMapping(),generation).revision());
            routed.commit(expected,page,attempts);assertEquals(2,probe.commits.get());assertEquals(committed,snapshot());
            var changed=new com.prosperitysmp.npc.publication.ChronicleDiscoveryRepository.Page(page.capturedAt().plusMillis(1),page.requestIds(),null);
            assertThrows(SQLException.class,()->journal.commit(expected,changed,attempts));assertEquals(committed,snapshot());
        }
    }

    @Test void chronicleJournalCannotCertifyMissingOrCorruptHeldDraftOrChangeGeneration() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var journal=new com.prosperitysmp.npc.publication.ChronicleRecoveryJournal(database);var expected=journal.load(chronicleMapping(),generation);
        var page=new com.prosperitysmp.npc.publication.ChronicleDiscoveryRepository(database,clock).page(chronicleMapping(),generation,null,1);
        var fake=List.of(new com.prosperitysmp.npc.publication.ChronicleRecoveryWorker.Attempt(request.requestId(),
                com.prosperitysmp.npc.publication.ChronicleRecoveryWorker.Outcome.STAGED,new com.prosperitysmp.npc.publication.ChronicleOutboxRepository.Held("a".repeat(64),"b".repeat(64))));
        var before=snapshot();assertThrows(SQLException.class,()->journal.commit(expected,page,fake));assertEquals(before,snapshot());
        assertThrows(SQLException.class,()->journal.load(chronicleMapping(),UUID.randomUUID()));assertEquals(before,snapshot());
        var held=outbox().stage(request.requestId(),chronicleMapping(),generation);
        var real=List.of(new com.prosperitysmp.npc.publication.ChronicleRecoveryWorker.Attempt(request.requestId(),
                com.prosperitysmp.npc.publication.ChronicleRecoveryWorker.Outcome.STAGED,held));
        execute("UPDATE npc_chronicle_outbox SET public_payload='{}'");before=snapshot();
        assertThrows(SQLException.class,()->journal.commit(expected,page,real));assertEquals(before,snapshot());
    }

    @Test void chronicleSendIntentIsSingleAcrossRetryRestartAndDestinationChanges() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var held=outbox().stage(request.requestId(),chronicleMapping(),generation);
        var destination=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination("123","456");
        var sends=new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(database,clock);
        var before=snapshot();var intent=sends.reserve(held,generation,destination).orElseThrow();
        assertEquals(held.eventKey(),intent.message().eventKey());
        var expectedMessage=com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.message(
                com.prosperitysmp.npc.publication.ChronicleAdmissionTemplate.render(repo.find(request.requestId()).orElseThrow(),chronicleMapping(),ZONE));
        assertEquals(expectedMessage.json(),intent.message().json());
        var after=snapshot();for(String table:before.keySet())if(!table.equals("npc_chronicle_send_intents"))assertEquals(before.get(table),after.get(table),table);
        assertTrue(sends.reserve(held,generation,destination).isEmpty());assertEquals(after,snapshot());
        database.close();database=new Database();database.connect(settings);
        sends=new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(database,clock);
        assertTrue(sends.reserve(held,generation,destination).isEmpty());
        var restarted=sends;
        assertThrows(SQLException.class,()->restarted.reserve(held,generation,new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination("999","456")));
        assertThrows(SQLException.class,()->restarted.reserve(held,UUID.randomUUID(),destination));assertEquals(after,snapshot());
        sends.uncertain(intent);assertTrue(sends.reserve(held,generation,destination).isEmpty());
        var receipt=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Receipt(held.eventKey(),"789",destination);
        sends.acknowledge(intent,receipt);var acknowledged=snapshot();sends.acknowledge(intent,receipt);sends.uncertain(intent);
        assertEquals(acknowledged,snapshot());assertTrue(sends.reserve(held,generation,destination).isEmpty());
        assertThrows(SQLException.class,()->restarted.acknowledge(intent,new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Receipt(held.eventKey(),"790",destination)));
    }

    @Test void chronicleOperatorReconciliationDoesNotCreateOrRegrantSendPermission() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var held=outbox().stage(request.requestId(),chronicleMapping(),generation);
        var destination=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination("123","456");
        var sends=new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(database,clock);
        var intent=sends.reserve(held,generation,destination).orElseThrow();sends.uncertain(intent);
        var o=new com.google.gson.JsonObject();o.addProperty("id","789");o.addProperty("webhook_id","123");o.addProperty("channel_id","456");
        o.addProperty("content","");o.addProperty("tts",false);o.addProperty("mention_everyone",false);
        for(String field:List.of("mentions","mention_roles","attachments"))o.add(field,new com.google.gson.JsonArray());
        o.add("embeds",com.google.gson.JsonParser.parseString(intent.message().json()).getAsJsonObject().get("embeds"));
        o.getAsJsonArray("embeds").get(0).getAsJsonObject().addProperty("content_scan_version",0);
        byte[] body=o.toString().getBytes(StandardCharsets.UTF_8);var before=snapshot();
        assertThrows(SQLException.class,()->sends.reconcileAuthenticatedResponse(held,UUID.randomUUID(),generation,destination,200,body));
        assertThrows(SQLException.class,()->sends.reconcileAuthenticatedResponse(held,intent.id(),UUID.randomUUID(),destination,200,body));
        assertThrows(SQLException.class,()->sends.reconcileAuthenticatedResponse(held,intent.id(),generation,destination,500,body));
        assertEquals(before,snapshot());
        database.close();database.connect(settings);
        assertEquals("789",sends.reconcileAuthenticatedResponse(held,intent.id(),generation,destination,200,body).messageId());
        var acknowledged=snapshot();sends.reconcileAuthenticatedResponse(held,intent.id(),generation,destination,200,body);
        assertEquals(acknowledged,snapshot());assertTrue(sends.reserve(held,generation,destination).isEmpty());
        for(String table:before.keySet())if(!table.equals("npc_chronicle_send_intents"))assertEquals(before.get(table),acknowledged.get(table),table);
        o.addProperty("id","790");byte[] conflict=o.toString().getBytes(StandardCharsets.UTF_8);
        assertThrows(SQLException.class,()->sends.reconcileAuthenticatedResponse(held,intent.id(),generation,destination,200,conflict));
        assertEquals(acknowledged,snapshot());
    }

    @Test void chronicleOperatorExecutableChecksBeforeWriteAndRecoversIdempotently() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var held=outbox().stage(request.requestId(),chronicleMapping(),generation);
        var destination=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination("123","456");
        var sends=new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(database,clock);
        var intent=sends.reserve(held,generation,destination).orElseThrow();sends.uncertain(intent);
        // MariaDB4j's isolated server uses skip-grant-tables. This exercises stdin/SQL
        // binding, not database authentication.
        var body=new com.google.gson.JsonObject();body.addProperty("id","789");body.addProperty("webhook_id","123");body.addProperty("channel_id","456");
        body.addProperty("content","");body.addProperty("tts",false);body.addProperty("mention_everyone",false);
        for(String field:List.of("mentions","mention_roles","attachments"))body.add(field,new com.google.gson.JsonArray());
        body.add("embeds",com.google.gson.JsonParser.parseString(intent.message().json()).getAsJsonObject().get("embeds"));
        body.getAsJsonArray("embeds").get(0).getAsJsonObject().addProperty("content_scan_version",0);
        var input=new com.google.gson.JsonObject();
        for(var entry:Map.of("mode","inspect","expires_at",Instant.now().plusSeconds(120).toString(),
                "database_name",schemaName,"database_user","opsfixture","database_password","disposable-ops-fixture",
                "database_port",Integer.toString(settings.port()),"event_key",held.eventKey(),"payload_sha256",held.payloadSha256(),
                "intent_id",intent.id().toString(),"generation",generation.toString()).entrySet())input.addProperty(entry.getKey(),entry.getValue());
        input.addProperty("webhook_id","123");input.addProperty("channel_id","456");input.addProperty("message_id","789");input.addProperty("response","");
        var before=snapshot();assertEquals("OK inspect UNCERTAIN 789",operatorChild(input.toString()));assertEquals(before,snapshot());
        input.addProperty("response",java.util.Base64.getEncoder().encodeToString(body.toString().getBytes(StandardCharsets.UTF_8)));
        input.addProperty("mode","check");assertEquals("OK check UNCERTAIN 789",operatorChild(input.toString()));assertEquals(before,snapshot());
        input.addProperty("message_id","790");assertEquals("REFUSED",operatorChild(input.toString()));assertEquals(before,snapshot());
        input.addProperty("message_id","789");input.addProperty("intent_id",UUID.randomUUID().toString());
        assertEquals("REFUSED",operatorChild(input.toString()));assertEquals(before,snapshot());
        input.addProperty("intent_id",intent.id().toString());
        assertEquals("REFUSED",operatorChild(input.toString().replaceFirst("\\{","{\"mode\":\"apply\",")));assertEquals(before,snapshot());
        input.addProperty("expires_at",Instant.now().minusSeconds(1).toString());
        assertEquals("REFUSED",operatorChild(input.toString()));assertEquals(before,snapshot());
        input.addProperty("expires_at",Instant.now().plusSeconds(120).toString());input.addProperty("mode","apply");
        assertEquals("OK apply UNCERTAIN 789",operatorChild(input.toString()));var after=snapshot();
        assertEquals("ACKNOWLEDGED",rows("SELECT state FROM npc_chronicle_send_intents").getFirst().get("state"));
        assertEquals("OK apply ACKNOWLEDGED 789",operatorChild(input.toString()));assertEquals(after,snapshot());
        assertTrue(sends.reserve(held,generation,destination).isEmpty());
        for(String table:before.keySet())if(!table.equals("npc_chronicle_send_intents"))assertEquals(before.get(table),after.get(table),table);
    }

    private static String operatorChild(String input) throws Exception {
        var process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("npc.chronicle.ops.classpath"),"com.prosperitysmp.npc.publication.ChronicleReceiptOperator").start();
        try {
            try(var stream=process.getOutputStream()){stream.write(input.getBytes(StandardCharsets.UTF_8));}
            assertTrue(process.waitFor(20,TimeUnit.SECONDS));
            String text=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
            assertEquals(text.equals("REFUSED") ? 1 : 0,process.exitValue());
            return text;
        }finally{process.destroyForcibly();}
    }

    @Test void chronicleSendIntentLostCommitReplyDoesNotRegrantAndAckReplyCanRetry() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var held=outbox().stage(request.requestId(),chronicleMapping(),generation);
        var destination=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination("123","456");
        try(var probe=new ChronicleProbe()) {
            var sends=new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(probe.routed,clock);probe.loseCommit.set(true);
            assertEquals("fixture lost commit acknowledgment",assertThrows(SQLException.class,()->sends.reserve(held,generation,destination)).getMessage());
            var committed=snapshot();assertTrue(sends.reserve(held,generation,destination).isEmpty());assertEquals(committed,snapshot());
        }
        // A separate event proves acknowledgment replay; do not recover a send permission from the first intent.
        UUID secondResident=new UUID(0,18);resident(secondResident,STANDARD);
        var second=quote(secondResident,PROPERTY,1);repo.apply(second,POLICY);
        var secondHeld=outbox().stage(second.requestId(),chronicleMapping(),generation);
        try(var probe=new ChronicleProbe()) {
            var sends=new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(probe.routed,clock);
            var intent=sends.reserve(secondHeld,generation,destination).orElseThrow();probe.loseCommit.set(true);
            var receipt=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Receipt(secondHeld.eventKey(),"790",destination);
            assertThrows(SQLException.class,()->sends.acknowledge(intent,receipt));var committed=snapshot();
            sends.acknowledge(intent,receipt);assertEquals(committed,snapshot());assertTrue(sends.reserve(secondHeld,generation,destination).isEmpty());
        }
    }

    @Test void chronicleSendIntentCompetingReservationsAndFailedInsert() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var held=outbox().stage(request.requestId(),chronicleMapping(),generation);
        var destination=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination("123","456");
        var sends=new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(database,clock);
        createTrigger("send_intent_fail","BEFORE INSERT ON npc_chronicle_send_intents FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture insert failure'");
        var before=snapshot();assertThrows(SQLException.class,()->sends.reserve(held,generation,destination));assertEquals(before,snapshot());dropTrigger("send_intent_fail");
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> call=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return sends.reserve(held,generation,destination).isPresent();};
            var first=executor.submit(call);var second=executor.submit(call);start.countDown();
            assertNotEquals(first.get(8,TimeUnit.SECONDS),second.get(8,TimeUnit.SECONDS));
        }
        assertEquals(1,rows("SELECT * FROM npc_chronicle_send_intents").size());
    }

    @Test void chronicleSendIntentCannotAcknowledgeTriggerRewrittenState() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var held=outbox().stage(request.requestId(),chronicleMapping(),generation);
        var destination=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination("123","456");
        var sends=new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(database,clock);
        createTrigger("send_intent_rewrite","BEFORE INSERT ON npc_chronicle_send_intents FOR EACH ROW SET NEW.state='UNCERTAIN'");
        var before=snapshot();assertEquals("New send intent was not preserved",assertThrows(SQLException.class,()->sends.reserve(held,generation,destination)).getMessage());assertEquals(before,snapshot());
        dropTrigger("send_intent_rewrite");var intent=sends.reserve(held,generation,destination).orElseThrow();
        createTrigger("send_ack_rewrite","BEFORE UPDATE ON npc_chronicle_send_intents FOR EACH ROW SET NEW.state=OLD.state,NEW.message_id=OLD.message_id");
        var receipt=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Receipt(held.eventKey(),"789",destination);
        var reserved=snapshot();assertEquals("Send outcome was not preserved",assertThrows(SQLException.class,()->sends.acknowledge(intent,receipt)).getMessage());assertEquals(reserved,snapshot());
        assertEquals("Send outcome was not preserved",assertThrows(SQLException.class,()->sends.uncertain(intent)).getMessage());assertEquals(reserved,snapshot());
        dropTrigger("send_ack_rewrite");sends.acknowledge(intent,receipt);
        assertEquals("ACKNOWLEDGED",rows("SELECT state FROM npc_chronicle_send_intents").getFirst().get("state"));
    }

    @Test void chronicleSendIntentRejectsSchemaAndEventMismatchEvenWithMatchingStoredHash() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var held=outbox().stage(request.requestId(),chronicleMapping(),generation);
        var original=com.google.gson.JsonParser.parseString(rows("SELECT public_payload FROM npc_chronicle_outbox").getFirst().get("public_payload")).getAsJsonObject();
        var sends=new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(database,clock);
        var destination=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination("123","456");
        for(boolean wrongEvent:List.of(false,true)) {
            var changed=original.deepCopy();changed.addProperty(wrongEvent ? "eventKey" : "privateBalance",wrongEvent ? "f".repeat(64) : "private fixture");
            String payload=changed.toString(),hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
            execute("UPDATE npc_chronicle_outbox SET public_payload=?,payload_sha256=?",payload,hash);
            var before=snapshot();var changedHeld=new com.prosperitysmp.npc.publication.ChronicleOutboxRepository.Held(held.eventKey(),hash);
            assertEquals(wrongEvent ? "Held send event mismatch" : "Invalid held send message",
                    assertThrows(SQLException.class,()->sends.reserve(changedHeld,generation,destination)).getMessage());
            assertEquals(before,snapshot());
        }
    }

    private static com.prosperitysmp.npc.publication.ChronicleDispatcher.Reply dispatchReply(
            com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination destination,
            com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Message message) {
        // Synthetic documented API response, not a real Discord call.
        var o=new com.google.gson.JsonObject();o.addProperty("id","789");o.addProperty("webhook_id",destination.webhookId());
        o.addProperty("channel_id",destination.channelId());o.addProperty("content","");o.addProperty("tts",false);o.addProperty("mention_everyone",false);
        for(String field:List.of("mentions","mention_roles","attachments"))o.add(field,new com.google.gson.JsonArray());
        o.add("embeds",com.google.gson.JsonParser.parseString(message.json()).getAsJsonObject().get("embeds"));
        return new com.prosperitysmp.npc.publication.ChronicleDispatcher.Reply(200,o.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static <T>T offOwner(Callable<T> call) throws Exception {
        try(var executor=Executors.newSingleThreadExecutor()){return executor.submit(call).get(8,TimeUnit.SECONDS);}
    }
    @Test void chronicleDispatcherAuthorizationAndRevocationCannotPostOrResetIntent() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var held=outbox().stage(request.requestId(),chronicleMapping(),generation);
        var destination=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination("123","456");
        var intents=new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(database,clock);
        var posts=new java.util.concurrent.atomic.AtomicInteger();
        com.prosperitysmp.npc.publication.ChronicleDispatcher.Transport transport=(d,m)->{posts.incrementAndGet();return dispatchReply(d,m);};
        var denied=new com.prosperitysmp.npc.publication.ChronicleDispatcher(intents,(h,g,d)->false,transport);
        var before=snapshot();assertThrows(IllegalStateException.class,()->denied.send(held,generation,destination));
        assertEquals("NOT_AUTHORIZED",offOwner(()->denied.send(held,generation,destination)).name());assertEquals(before,snapshot());
        var checks=new java.util.concurrent.atomic.AtomicInteger();
        var revoked=new com.prosperitysmp.npc.publication.ChronicleDispatcher(intents,(h,g,d)->checks.incrementAndGet()==1,transport);
        assertEquals("HELD_BEFORE_POST",offOwner(()->revoked.send(held,generation,destination)).name());assertEquals(0,posts.get());
        var replacement=new com.prosperitysmp.npc.publication.ChronicleDispatcher(intents,(h,g,d)->true,transport);
        assertEquals("ALREADY_RESERVED",offOwner(()->replacement.send(held,generation,destination)).name());assertEquals(0,posts.get());
    }
    @Test void chronicleDispatcherLostNetworkReplyNeverPostsTwice() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var held=outbox().stage(request.requestId(),chronicleMapping(),generation);
        var destination=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination("123","456");
        var intents=new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(database,clock);var posts=new java.util.concurrent.atomic.AtomicInteger();
        var dispatcher=new com.prosperitysmp.npc.publication.ChronicleDispatcher(intents,(h,g,d)->true,(d,m)->{posts.incrementAndGet();throw new java.io.IOException("fixture remote may have posted");});
        assertEquals("UNCONFIRMED",offOwner(()->dispatcher.send(held,generation,destination)).name());
        assertEquals("UNCERTAIN",rows("SELECT state FROM npc_chronicle_send_intents").getFirst().get("state"));
        assertEquals("ALREADY_RESERVED",offOwner(()->dispatcher.send(held,generation,destination)).name());assertEquals(1,posts.get());
    }
    @Test void chronicleDispatcherLostDatabaseRepliesDoNotRepostAndCancelStillKeepsReceipt() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var held=outbox().stage(request.requestId(),chronicleMapping(),generation);
        var destination=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination("123","456");var posts=new java.util.concurrent.atomic.AtomicInteger();
        try(var probe=new ChronicleProbe()) {
            var intents=new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(probe.routed,clock);
            var dispatcher=new com.prosperitysmp.npc.publication.ChronicleDispatcher(intents,(h,g,d)->true,(d,m)->{posts.incrementAndGet();return dispatchReply(d,m);});
            probe.loseCommit.set(true);
            assertInstanceOf(SQLException.class,assertThrows(java.util.concurrent.ExecutionException.class,()->offOwner(()->dispatcher.send(held,generation,destination))).getCause());
            assertEquals("ALREADY_RESERVED",offOwner(()->dispatcher.send(held,generation,destination)).name());assertEquals(0,posts.get());
        }
        UUID secondResident=new UUID(0,18);resident(secondResident,STANDARD);var second=quote(secondResident,PROPERTY,1);repo.apply(second,POLICY);
        var secondHeld=outbox().stage(second.requestId(),chronicleMapping(),generation);
        try(var probe=new ChronicleProbe()) {
            var intents=new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(probe.routed,clock);
            var ref=new AtomicReference<com.prosperitysmp.npc.publication.ChronicleDispatcher>();
            var dispatcher=new com.prosperitysmp.npc.publication.ChronicleDispatcher(intents,(h,g,d)->true,(d,m)->{
                posts.incrementAndGet();ref.get().cancel();probe.loseCommit.set(true);return dispatchReply(d,m);
            });ref.set(dispatcher);
            assertInstanceOf(SQLException.class,assertThrows(java.util.concurrent.ExecutionException.class,()->offOwner(()->dispatcher.send(secondHeld,generation,destination))).getCause());
            assertEquals(1,rows("SELECT state FROM npc_chronicle_send_intents WHERE state='ACKNOWLEDGED'").size());
            var replacement=new com.prosperitysmp.npc.publication.ChronicleDispatcher(intents,(h,g,d)->true,(d,m)->{posts.incrementAndGet();return dispatchReply(d,m);});
            assertEquals("ALREADY_RESERVED",offOwner(()->replacement.send(secondHeld,generation,destination)).name());assertEquals(1,posts.get());
        }
    }

    @Test void chronicleDispatcherChecksCancellationAfterAuthorizationReturns() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var held=outbox().stage(request.requestId(),chronicleMapping(),generation);
        var destination=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination("123","456");
        var ref=new AtomicReference<com.prosperitysmp.npc.publication.ChronicleDispatcher>();var checks=new java.util.concurrent.atomic.AtomicInteger();
        var dispatcher=new com.prosperitysmp.npc.publication.ChronicleDispatcher(
                new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(database,clock),(h,g,d)->{
                    if(checks.incrementAndGet()==2)ref.get().cancel();return true;
                },(d,m)->{fail("cancelled authorization must prevent transport call");return null;});ref.set(dispatcher);
        assertEquals("HELD_BEFORE_POST",offOwner(()->dispatcher.send(held,generation,destination)).name());assertEquals(2,checks.get());
    }
    @Test void chronicleDispatcherRejectsOverlapAndRetainsSuccessfulInflightReceipt() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var held=outbox().stage(request.requestId(),chronicleMapping(),generation);
        var destination=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination("123","456");
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var posts=new java.util.concurrent.atomic.AtomicInteger();
        var dispatcher=new com.prosperitysmp.npc.publication.ChronicleDispatcher(
                new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(database,clock),(h,g,d)->true,(d,m)->{
                    posts.incrementAndGet();entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return dispatchReply(d,m);
                });
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->dispatcher.send(held,generation,destination));
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                var second=executor.submit(()->dispatcher.send(held,generation,destination));
                assertEquals("Chronicle dispatch already running",assertThrows(java.util.concurrent.ExecutionException.class,()->second.get(5,TimeUnit.SECONDS)).getCause().getMessage());
                dispatcher.cancel();release.countDown();assertEquals("ACKNOWLEDGED",first.get(5,TimeUnit.SECONDS).name());
            }finally{release.countDown();}
        }
        assertEquals(1,posts.get());assertEquals("789",rows("SELECT message_id FROM npc_chronicle_send_intents").getFirst().get("message_id"));
    }

    @Test void chronicleDispatchHostDrainsReceiptBeforeTransportClose() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var held=outbox().stage(request.requestId(),chronicleMapping(),generation);
        var destination=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination("123","456");
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var closed=new java.util.concurrent.atomic.AtomicInteger();var acknowledgedAtClose=new java.util.concurrent.atomic.AtomicBoolean();
        class Transport implements com.prosperitysmp.npc.publication.ChronicleDispatcher.Transport,AutoCloseable {
            public com.prosperitysmp.npc.publication.ChronicleDispatcher.Reply post(
                    com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination d,
                    com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Message m) throws InterruptedException {
                entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return dispatchReply(d,m);
            }
            public void close() throws Exception {
                acknowledgedAtClose.set("ACKNOWLEDGED".equals(rows("SELECT state FROM npc_chronicle_send_intents").getFirst().get("state")));closed.incrementAndGet();
            }
        }
        var transport=new Transport();var dispatcher=new com.prosperitysmp.npc.publication.ChronicleDispatcher(
                new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(database,clock),(h,g,d)->true,transport);
        var host=new com.prosperitysmp.npc.publication.ChronicleDispatchHost(dispatcher,transport);
        try {
            assertTrue(host.trySend(held,generation,destination));assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertFalse(host.trySend(held,generation,destination));assertTrue(host.poll().isEmpty());
            var drain=host.shutdown();assertFalse(drain.toCompletableFuture().isDone());assertEquals(0,closed.get());
            drain.toCompletableFuture().complete(null);assertFalse(host.shutdown().toCompletableFuture().isDone());
            assertFalse(host.trySend(held,generation,destination));release.countDown();
            host.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS);assertEquals(1,closed.get());assertTrue(acknowledgedAtClose.get());
            var outcome=host.poll().orElseThrow();assertEquals("ACKNOWLEDGED",outcome.result().name());assertEquals("NONE",outcome.failure().name());
            assertEquals(held,outcome.request());assertEquals(generation,outcome.generation());assertEquals(destination,outcome.destination());assertTrue(host.poll().isEmpty());
        }finally{release.countDown();host.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS);}
    }
    @Test void chronicleDispatchHostRejectsWrongResourceAndReportsFailedClose() throws Exception {
        class Transport implements com.prosperitysmp.npc.publication.ChronicleDispatcher.Transport,AutoCloseable {
            public com.prosperitysmp.npc.publication.ChronicleDispatcher.Reply post(
                    com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination d,
                    com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Message m){throw new AssertionError("No send expected");}
            public void close() throws java.io.IOException {throw new java.io.IOException("fixture close failure");}
        }
        var transport=new Transport();var dispatcher=new com.prosperitysmp.npc.publication.ChronicleDispatcher(
                new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(database,clock),(h,g,d)->false,transport);
        assertThrows(IllegalArgumentException.class,()->new com.prosperitysmp.npc.publication.ChronicleDispatchHost(dispatcher,()->{}));
        var host=new com.prosperitysmp.npc.publication.ChronicleDispatchHost(dispatcher,transport);
        var failed=assertThrows(java.util.concurrent.ExecutionException.class,()->host.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS));
        assertEquals("fixture close failure",failed.getCause().getMessage());assertTrue(host.held());
    }

    @Test void chronicleDispatchHostUnreadOutcomeBlocksAdmissionAndPollIsOwnerOnly() throws Exception {
        class Transport implements com.prosperitysmp.npc.publication.ChronicleDispatcher.Transport,AutoCloseable {
            public com.prosperitysmp.npc.publication.ChronicleDispatcher.Reply post(
                    com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination d,
                    com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Message m){throw new AssertionError("Denied must not post");}
            public void close(){}
        }
        var transport=new Transport();var dispatcher=new com.prosperitysmp.npc.publication.ChronicleDispatcher(
                new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(database,clock),(h,g,d)->false,transport);
        var host=new com.prosperitysmp.npc.publication.ChronicleDispatchHost(dispatcher,transport);
        var held=new com.prosperitysmp.npc.publication.ChronicleOutboxRepository.Held("a".repeat(64),"b".repeat(64));
        UUID generation=UUID.randomUUID();var dest=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination("123","456");
        try {
            var wrong=assertThrows(java.util.concurrent.ExecutionException.class,()->offOwner(host::poll));
            assertEquals("Chronicle dispatch host owner required",wrong.getCause().getMessage());
            assertTrue(host.trySend(held,generation,dest));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(host.running() && System.nanoTime()<until)Thread.sleep(1);
            assertFalse(host.running());assertFalse(host.trySend(held,generation,dest));
            assertEquals("NOT_AUTHORIZED",host.poll().orElseThrow().result().name());assertTrue(host.poll().isEmpty());
            assertTrue(host.trySend(held,generation,dest));host.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertEquals("NOT_AUTHORIZED",host.poll().orElseThrow().result().name());
        }finally{host.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS);}
    }

    @Test void chronicleDispatchHostFailureIsRedactedAndHoldsFurtherWork() throws Exception {
        class Transport implements com.prosperitysmp.npc.publication.ChronicleDispatcher.Transport,AutoCloseable {
            public com.prosperitysmp.npc.publication.ChronicleDispatcher.Reply post(
                    com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination d,
                    com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Message m){throw new AssertionError("Must not post");}
            public void close(){}
        }
        var transport=new Transport();var dispatcher=new com.prosperitysmp.npc.publication.ChronicleDispatcher(
                new com.prosperitysmp.npc.publication.ChronicleSendIntentRepository(database,clock),(h,g,d)->{throw new IllegalStateException("fixture-private-details");},transport);
        var host=new com.prosperitysmp.npc.publication.ChronicleDispatchHost(dispatcher,transport);
        var held=new com.prosperitysmp.npc.publication.ChronicleOutboxRepository.Held("a".repeat(64),"b".repeat(64));
        UUID generation=UUID.randomUUID();var dest=new com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination("123","456");
        try {
            assertTrue(host.trySend(held,generation,dest));long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(host.running() && System.nanoTime()<until)Thread.sleep(1);
            assertFalse(host.running());var outcome=host.poll().orElseThrow();assertEquals("INTERNAL",outcome.failure().name());assertNull(outcome.result());
            assertFalse(outcome.toString().contains("fixture-private-details"));assertTrue(host.held());assertFalse(host.trySend(held,generation,dest));
        }finally{host.shutdown().toCompletableFuture().get(5,TimeUnit.SECONDS);}
    }

    @Test void chronicleJournalRejectsDamagedReplayAndInitialCursor() throws Exception {
        var request=quote(RESIDENT,PROPERTY,0);repo.apply(request,POLICY);UUID generation=UUID.randomUUID();
        var journal=new com.prosperitysmp.npc.publication.ChronicleRecoveryJournal(database);
        var expected=journal.load(chronicleMapping(),generation);
        String cursor="cursor_after=UNHEX('00000000000000000000000000000001'),cursor_through=UNHEX('00000000000000000000000000000002')";
        assertThrows(SQLException.class,()->execute("UPDATE npc_chronicle_progress SET "+cursor));
        try(var c=database.getConnection();var s=c.createStatement()) {
            s.execute("SET SESSION check_constraint_checks=OFF");
            try{s.execute("UPDATE npc_chronicle_progress SET "+cursor);}
            finally{s.execute("SET SESSION check_constraint_checks=ON");}
        }
        assertThrows(SQLException.class,()->journal.load(chronicleMapping(),generation));
        execute("UPDATE npc_chronicle_progress SET cursor_after=NULL,cursor_through=NULL");
        var page=new com.prosperitysmp.npc.publication.ChronicleDiscoveryRepository(database,clock).page(chronicleMapping(),generation,null,1);
        var held=outbox().stage(request.requestId(),chronicleMapping(),generation);
        var attempts=List.of(new com.prosperitysmp.npc.publication.ChronicleRecoveryWorker.Attempt(request.requestId(),
                com.prosperitysmp.npc.publication.ChronicleRecoveryWorker.Outcome.STAGED,held));
        journal.commit(expected,page,attempts);journal.commit(expected,page,attempts);
        execute("UPDATE npc_chronicle_progress SET "+cursor);
        assertThrows(SQLException.class,()->journal.commit(expected,page,attempts));
        execute("UPDATE npc_chronicle_progress SET cursor_after=NULL,cursor_through=NULL");
        execute("UPDATE npc_chronicle_outbox SET public_payload='{}'");
        assertThrows(SQLException.class,()->journal.commit(expected,page,attempts));
        execute("DELETE FROM npc_chronicle_attempts");
        var damaged=snapshot();assertThrows(SQLException.class,()->journal.commit(expected,page,attempts));assertEquals(damaged,snapshot());
    }

    @Test void chronicleDiscoveryCycleHasFiniteFenceAndFindsLateLowerCommitNextCycle() throws Exception {
        var policy=new Policy(10,5,4,7);UUID generation=UUID.randomUUID();
        UUID first=new UUID(0,10),last=new UUID(0,30),lateLow=new UUID(0,5),lateHigh=new UUID(0,40);
        repo.apply(repo.quote(first,RESIDENT,PROPERTY,0,DAY),policy);
        UUID second=new UUID(0,18);resident(second,STANDARD);
        repo.apply(repo.quote(last,second,PROPERTY,1,DAY),policy);
        var discovery=new com.prosperitysmp.npc.publication.ChronicleDiscoveryRepository(database,clock);
        var before=snapshot();var page=discovery.page(chronicleMapping(),generation,null,1);
        assertEquals(List.of(first),page.requestIds());assertNotNull(page.continuation());
        assertEquals(last,page.continuation().through());assertEquals(before,snapshot());
        UUID third=new UUID(0,19),fourth=new UUID(0,20);resident(third,STANDARD);resident(fourth,STANDARD);
        repo.apply(repo.quote(lateLow,third,PROPERTY,2,DAY),policy);
        repo.apply(repo.quote(lateHigh,fourth,PROPERTY,3,DAY),policy);before=snapshot();
        var end=discovery.page(chronicleMapping(),generation,page.continuation(),1);
        assertEquals(List.of(last),end.requestIds());assertNull(end.continuation());
        var next=discovery.page(chronicleMapping(),generation,null,128);
        assertEquals(List.of(lateLow,first,last,lateHigh),next.requestIds());assertNull(next.continuation());
        assertThrows(UnsupportedOperationException.class,()->next.requestIds().clear());assertEquals(before,snapshot());
    }

    @Test void chronicleDiscoveryUsesBinaryUuidOrderAndDoesNotSkipAlreadyHeldRows() throws Exception {
        UUID low=new UUID(Long.MAX_VALUE,0),high=new UUID(Long.MIN_VALUE,0),generation=UUID.randomUUID();
        repo.apply(repo.quote(low,RESIDENT,PROPERTY,0,DAY),POLICY);
        UUID other=new UUID(0,18);resident(other,STANDARD);repo.apply(repo.quote(high,other,PROPERTY,1,DAY),POLICY);
        outbox().stage(low,chronicleMapping(),generation);
        var discovery=new com.prosperitysmp.npc.publication.ChronicleDiscoveryRepository(database,clock);
        var before=snapshot();var first=discovery.page(chronicleMapping(),generation,null,1);
        assertEquals(List.of(low),first.requestIds());assertNotNull(first.continuation());
        var last=discovery.page(chronicleMapping(),generation,first.continuation(),1);
        assertEquals(List.of(high),last.requestIds());assertNull(last.continuation());assertEquals(before,snapshot());
    }

    @Test void chronicleDiscoveryFenceAndPageShareSnapshotAcrossActualCommit() throws Exception {
        UUID first=new UUID(0,20),lateLower=new UUID(0,10),generation=UUID.randomUUID();
        repo.apply(repo.quote(first,RESIDENT,PROPERTY,0,DAY),POLICY);
        UUID other=new UUID(0,18);resident(other,STANDARD);
        var later=repo.quote(lateLower,other,PROPERTY,1,DAY);
        var committed=new java.util.concurrent.atomic.AtomicBoolean();
        try(var probe=new ChronicleProbe()) {
            probe.beforePrepare=sql->{
                if(sql.contains("request_id<=?") && committed.compareAndSet(false,true))repo.apply(later,POLICY);
            };
            var discovery=new com.prosperitysmp.npc.publication.ChronicleDiscoveryRepository(probe.routed,clock);
            var page=discovery.page(chronicleMapping(),generation,null,128);
            assertTrue(committed.get());assertEquals(List.of(first),page.requestIds());assertNull(page.continuation());
            var afterWriter=snapshot();
            assertEquals(List.of(lateLower,first),discovery.page(chronicleMapping(),generation,null,128).requestIds());
            assertEquals(afterWriter,snapshot());
        }
    }

    @FunctionalInterface private interface ChronicleSqlHook {void run(String sql) throws SQLException;}
    private final class ChronicleProbe implements AutoCloseable {
        final Database routed=new Database();
        final java.util.concurrent.atomic.AtomicBoolean loseCommit=new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger commits=new java.util.concurrent.atomic.AtomicInteger();
        ChronicleSqlHook beforePrepare=sql->{};
        ChronicleProbe() throws Exception {
            var forwarding=new com.zaxxer.hikari.HikariDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    Connection actual=database.getConnection();
                    return (Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},
                            (proxy,method,args)->{
                                if(method.getName().equals("prepareStatement") && args[0] instanceof String sql)beforePrepare.run(sql);
                                Object value;
                                try {value=method.invoke(actual,args);}
                                catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                                if(method.getName().equals("commit")) {
                                    commits.incrementAndGet();
                                    if(loseCommit.compareAndSet(true,false))throw new SQLException("fixture lost commit acknowledgment");
                                }
                                return value;
                            });
                }
            };
            var field=Database.class.getDeclaredField("dataSource");field.setAccessible(true);field.set(routed,forwarding);
        }
        @Override public void close(){routed.close();}
    }

    @Test void chronicleDiscoveryEmptyPropertyAndChangedCursorRejectWithoutSideEffects() throws Exception {
        UUID generation=UUID.randomUUID();
        var discovery=new com.prosperitysmp.npc.publication.ChronicleDiscoveryRepository(database,clock);
        var before=snapshot();var page=discovery.page(chronicleMapping(),generation,null,128);
        assertTrue(page.requestIds().isEmpty());assertNull(page.continuation());assertEquals(before,snapshot());
        var disconnected=new com.prosperitysmp.npc.publication.ChronicleDiscoveryRepository(new Database(),clock);
        var cursor=new com.prosperitysmp.npc.publication.ChronicleDiscoveryRepository.Cursor(generation,
                chronicleMapping(),new UUID(0,1),new UUID(0,2));
        assertThrows(IllegalArgumentException.class,()->disconnected.page(chronicleMapping(),generation,null,0));
        assertThrows(IllegalArgumentException.class,()->disconnected.page(chronicleMapping(),generation,null,129));
        assertThrows(IllegalArgumentException.class,()->disconnected.page(chronicleMapping(),UUID.randomUUID(),cursor,1));
        var changed=new com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval(PROPERTY,WORLD,"c001","main");
        assertThrows(IllegalArgumentException.class,()->disconnected.page(changed,generation,cursor,1));
    }

    @Test
    void freshAdmissionUsesExistingIdentityAndNeverWritesProfilesMemoriesOrMoney() throws SQLException {
        HousingMoveRequest request = quote(RESIDENT, PROPERTY, 0);
        var sides = sideRows();
        guardSideWrites();
        Result result = repo.apply(request, POLICY);
        assertReceipt(result, request, POLICY, 0);
        Map<String, String> tenant = tenant(RESIDENT);
        assertEquals(hex(RESIDENT), tenant.get("tenant_id"));
        assertEquals("Authored Resident", tenant.get("display_name"));
        assertEquals("CARPENTER", tenant.get("archetype"));
        assertEquals("STANDARD", tenant.get("tier"));
        assertEquals(hex(PROPERTY), tenant.get("property_id"));
        assertEquals("0", tenant.get("slot"));
        assertEquals("0", tenant.get("row_version"));
        assertEquals(DAY.plusDays(7).toString(), tenant.get("move_cooldown_until_day"));
        assertEquals(1, count("npc_tenants"));
        assertQuotas(DAY, 1, 1);
        assertEquals(sides, sideRows());
    }

    @Test
    void returningTenancyChangesOnlyCustodyRevisionAndCooldown() throws SQLException {
        vacant(RESIDENT, 19, DAY.minusDays(1));
        Map<String, String> expected = new LinkedHashMap<>(tenant(RESIDENT));
        var sides = sideRows();
        guardSideWrites();
        HousingMoveRequest request = quote(RESIDENT, PROPERTY, 2);
        assertEquals(19, request.tenancyRevision());
        assertReceipt(repo.apply(request, POLICY), request, POLICY, 20);
        expected.put("property_id", hex(PROPERTY));
        expected.put("slot", "2");
        expected.put("row_version", "20");
        expected.put("move_cooldown_until_day", DAY.plusDays(7).toString());
        assertEquals(expected, tenant(RESIDENT));
        assertEquals(sides, sideRows());
    }

    @Test
    void exactReplayAfterDepartureAndLaterDayNeverRehousesRechargesOrRevalidatesCurrentCustody() throws SQLException {
        HousingMoveRequest request = quote(RESIDENT, PROPERTY, 0);
        Receipt receipt = repo.apply(request, POLICY).receipt();
        vacate(RESIDENT);
        execute("UPDATE npc_properties SET owner_uuid=?,ownership_epoch=4,row_version=12,enabled=FALSE", new UUID(0, 901));
        new HousingRegistry(database).setEnabled(RESIDENT, 0, false);
        clock.set(NOW.plusSeconds(10 * 86400L));
        var before = snapshot();
        guardAllWrites();
        for (int i = 0; i < 2; i++) {
            Result replay = reopened().apply(request, POLICY);
            assertFalse(replay.newlyApplied());
            assertEquals(receipt, replay.receipt());
            assertEquals(receipt, reopened().find(request.requestId()).orElseThrow());
        }
        assertNull(tenant(RESIDENT).get("property_id"));
        assertEquals(before, snapshot());
    }
    @Test
    void sameRequestIdRejectsEveryConflictingPayloadAndPolicyField() throws SQLException {
        HousingMoveRequest r = quote(RESIDENT, PROPERTY, 0);
        repo.apply(r, POLICY);
        for (HousingMoveRequest conflict : List.of(
                copy(r, new UUID(0, 18), r.propertyId(), r.ownerId(), r.worldId(), r.marketId(), r.classKey(), 3, 11, 0, -1, 0, DAY),
                copy(r, r.residentId(), new UUID(0, 801), r.ownerId(), r.worldId(), r.marketId(), r.classKey(), 3, 11, 0, -1, 0, DAY),
                copy(r, r.residentId(), r.propertyId(), new UUID(0, 901), r.worldId(), r.marketId(), r.classKey(), 3, 11, 0, -1, 0, DAY),
                copy(r, r.residentId(), r.propertyId(), r.ownerId(), new UUID(0, 1), r.marketId(), r.classKey(), 3, 11, 0, -1, 0, DAY),
                copy(r, r.residentId(), r.propertyId(), r.ownerId(), r.worldId(), "uptown", r.classKey(), 3, 11, 0, -1, 0, DAY),
                copy(r, r.residentId(), r.propertyId(), r.ownerId(), r.worldId(), r.marketId(), "apartment", 3, 11, 0, -1, 0, DAY),
                copy(r, r.residentId(), r.propertyId(), r.ownerId(), r.worldId(), r.marketId(), r.classKey(), 4, 11, 0, -1, 0, DAY),
                copy(r, r.residentId(), r.propertyId(), r.ownerId(), r.worldId(), r.marketId(), r.classKey(), 3, 12, 0, -1, 0, DAY),
                copy(r, r.residentId(), r.propertyId(), r.ownerId(), r.worldId(), r.marketId(), r.classKey(), 3, 11, 1, -1, 0, DAY),
                copy(r, r.residentId(), r.propertyId(), r.ownerId(), r.worldId(), r.marketId(), r.classKey(), 3, 11, 0, 0, 0, DAY),
                copy(r, r.residentId(), r.propertyId(), r.ownerId(), r.worldId(), r.marketId(), r.classKey(), 3, 11, 0, -1, 1, DAY),
                copy(r, r.residentId(), r.propertyId(), r.ownerId(), r.worldId(), r.marketId(), r.classKey(), 3, 11, 0, -1, 0, DAY.plusDays(1)))) {
            rejected("Housing request payload conflict", () -> repo.apply(conflict, POLICY));
        }
        for (Policy conflict : policyConflicts()) {
            rejected("Housing request payload conflict", () -> repo.apply(r, conflict));
        }
    }

    @Test
    void staleOwnerEpochAndPropertyRevisionRejectIndependently() throws SQLException {
        HousingMoveRequest request = quote(RESIDENT, PROPERTY, 0);
        for (String assignment : List.of("owner_uuid=UNHEX('00000000000000000000000000000385')",
                "ownership_epoch=4", "row_version=12")) {
            execute("UPDATE npc_properties SET " + assignment + " WHERE property_id=?", PROPERTY);
            rejected("Housing property changed", () -> repo.apply(request, POLICY));
            execute("UPDATE npc_properties SET owner_uuid=?,ownership_epoch=3,row_version=11 WHERE property_id=?", OWNER, PROPERTY);
        }
        assertTrue(repo.apply(request, POLICY).newlyApplied());
    }

    @Test
    void membershipDisableAndDisableEnableCycleInvalidateTheQuote() throws SQLException {
        HousingRegistry registry = new HousingRegistry(database);
        HousingMoveRequest request = quote(RESIDENT, PROPERTY, 0);
        registry.setEnabled(RESIDENT, 0, false);
        rejected("Housing membership changed", () -> repo.apply(request, POLICY));
        rejected("Housing member unavailable", () -> quote(RESIDENT, PROPERTY, 0));
        registry.setEnabled(RESIDENT, 1, true);
        rejected("Housing membership changed", () -> repo.apply(request, POLICY));
        HousingMoveRequest fresh = quote(RESIDENT, PROPERTY, 0);
        assertEquals(2, fresh.membershipRevision());
        execute("UPDATE npc_housing_members SET enabled=FALSE WHERE resident_id=?", RESIDENT);
        rejected("Housing member unavailable", () -> repo.apply(fresh, POLICY));
        execute("UPDATE npc_housing_members SET enabled=TRUE,available_since='2026-09-08' WHERE resident_id=?", RESIDENT);
        rejected("Housing member unavailable", () -> repo.apply(fresh, POLICY));
    }

    @Test
    void appearedVacantRowStaleRevisionAndPartialVacancyReject() throws SQLException {
        HousingMoveRequest absent = quote(RESIDENT, PROPERTY, 0);
        vacant(RESIDENT, 19, DAY);
        rejected("Housing tenancy changed", () -> repo.apply(absent, POLICY));
        HousingMoveRequest request = quote(RESIDENT, PROPERTY, 0);
        execute("UPDATE npc_tenants SET row_version=20 WHERE tenant_id=?", RESIDENT);
        rejected("Housing tenancy changed", () -> repo.apply(request, POLICY));
        execute("UPDATE npc_tenants SET row_version=19 WHERE tenant_id=?", RESIDENT);
        for (String assignment : List.of("property_id=NULL,slot=1", "property_id=?,slot=NULL", "property_id=?,slot=1")) {
            if (assignment.contains("?")) execute("UPDATE npc_tenants SET " + assignment + " WHERE tenant_id=?", PROPERTY, RESIDENT);
            else execute("UPDATE npc_tenants SET " + assignment + " WHERE tenant_id=?", RESIDENT);
            rejected("Resident is not vacant", () -> repo.apply(request, POLICY));
            rejected("Resident is not vacant", () -> quote(RESIDENT, PROPERTY, 0));
        }
        execute("UPDATE npc_tenants SET property_id=NULL,slot=NULL WHERE tenant_id=?", RESIDENT);
        assertTrue(repo.apply(request, POLICY).newlyApplied());
    }

    @Test
    void activeCooldownIsRecheckedAndItsExactDayIsInclusive() throws SQLException {
        vacant(RESIDENT, 19, DAY);
        HousingMoveRequest request = quote(RESIDENT, PROPERTY, 0);
        execute("UPDATE npc_tenants SET move_cooldown_until_day=? WHERE tenant_id=?", DAY.plusDays(1).toString(), RESIDENT);
        rejected("Resident move cooldown active", () -> repo.apply(request, POLICY));
        rejected("Resident move cooldown active", () -> quote(RESIDENT, PROPERTY, 0));
        execute("UPDATE npc_tenants SET move_cooldown_until_day=? WHERE tenant_id=?", DAY.toString(), RESIDENT);
        assertReceipt(repo.apply(request, POLICY), request, POLICY, 20);
    }

    @Test
    void actualAskingRentAndConditionAreCheckedAgainWithoutRelyingOnRevisionBumps() throws SQLException {
        HousingMoveRequest request = quote(RESIDENT, PROPERTY, 0);
        for (String assignment : List.of("asking_rent_cents=1201", "condition_score=39")) {
            execute("UPDATE npc_properties SET " + assignment + " WHERE property_id=?", PROPERTY);
            rejected("Housing terms ineligible", () -> repo.apply(request, POLICY));
            rejected("Housing terms ineligible", () -> quote(RESIDENT, PROPERTY, 0));
            execute("UPDATE npc_properties SET asking_rent_cents=1200,condition_score=40 WHERE property_id=?", PROPERTY);
        }
        execute("UPDATE npc_properties SET reference_rent_cents=999999 WHERE property_id=?", PROPERTY);
        assertTrue(repo.apply(request, POLICY).newlyApplied(), "eligibility uses actual rent, not reference rent");
    }

    @Test
    void malformedExistingSlotsFailClosedEvenWhenTheSelectedSlotIsEmpty() throws SQLException {
        HousingMoveRequest request = quote(RESIDENT, PROPERTY, 3);
        UUID other = new UUID(0, 700);
        for (Integer bad : new Integer[]{null, -1, 4}) {
            occupant(other, PROPERTY, bad);
            String message = bad == null ? "Missing housing number" : "Invalid existing housing occupancy";
            rejected(message, () -> repo.apply(request, POLICY));
            rejected(message, () -> quote(RESIDENT, PROPERTY, 3));
            execute("DELETE FROM npc_tenants WHERE tenant_id=?", other);
        }
        occupant(other, PROPERTY, 0);
        occupant(new UUID(0, 701), PROPERTY, 0);
        rejected("Invalid existing housing occupancy", () -> repo.apply(request, POLICY));
        rejected("Invalid existing housing occupancy", () -> quote(RESIDENT, PROPERTY, 3));
        execute("DELETE FROM npc_tenants");
        occupant(other, PROPERTY, 3);
        rejected("Housing slot occupied", () -> repo.apply(request, POLICY));
        rejected("Housing slot occupied", () -> quote(RESIDENT, PROPERTY, 3));
    }

    @Test
    void worldMarketAndClassMustMatchBothQuoteAndCurrentMembership() throws SQLException {
        HousingMoveRequest request = quote(RESIDENT, PROPERTY, 0);
        for (String assignment : List.of("world_uuid=UNHEX('00000000000000000000000000000001')",
                "market_id='uptown'", "class_key='apartment'")) {
            execute("UPDATE npc_housing_members SET " + assignment + " WHERE resident_id=?", RESIDENT);
            rejected("Housing scope mismatch", () -> repo.apply(request, POLICY));
            rejected("Housing scope mismatch", () -> quote(RESIDENT, PROPERTY, 0));
            execute("UPDATE npc_housing_members SET world_uuid=?,market_id='downtown',class_key='room' WHERE resident_id=?", WORLD, RESIDENT);
            execute("UPDATE npc_properties SET " + assignment + " WHERE property_id=?", PROPERTY);
            rejected("Housing property changed", () -> repo.apply(request, POLICY));
            rejected("Housing scope mismatch", () -> quote(RESIDENT, PROPERTY, 0));
            execute("UPDATE npc_properties SET world_uuid=?,market_id='downtown',class_key='room' WHERE property_id=?", WORLD, PROPERTY);
        }
    }
    @Test
    void missingDisabledUnownedAndShrunkPropertyCannotAdmit() throws SQLException {
        HousingMoveRequest request = quote(RESIDENT, PROPERTY, 3);
        for (String assignment : List.of("enabled=FALSE", "capacity=3")) {
            execute("UPDATE npc_properties SET " + assignment + " WHERE property_id=?", PROPERTY);
            rejected("Housing property unavailable", () -> repo.apply(request, POLICY));
            rejected("Housing property unavailable", () -> quote(RESIDENT, PROPERTY, 3));
            execute("UPDATE npc_properties SET enabled=TRUE,capacity=4 WHERE property_id=?", PROPERTY);
        }
        execute("UPDATE npc_properties SET owner_uuid=NULL WHERE property_id=?", PROPERTY);
        rejected("Housing property changed", () -> repo.apply(request, POLICY));
        rejected("Housing property unavailable", () -> quote(RESIDENT, PROPERTY, 3));
        execute("DELETE FROM npc_properties WHERE property_id=?", PROPERTY);
        rejected("Housing property missing", () -> repo.apply(request, POLICY));
        rejected("Housing property missing", () -> quote(RESIDENT, PROPERTY, 3));
    }

    @Test
    void missingProfileAndMissingMembershipFailClosedWithoutFabrication() throws SQLException {
        UUID other = new UUID(0, 18);
        resident(other, STANDARD);
        HousingMoveRequest request = quote(other, PROPERTY, 0);
        // Simulate an out-of-band orphan without disabling FK checks in any other pooled session.
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION foreign_key_checks=OFF");
            try { execute(c, "DELETE FROM npc_resident_profiles WHERE resident_id=?", other); }
            finally { execute(c, "SET SESSION foreign_key_checks=ON"); }
        }
        rejected("Resident profile missing", () -> repo.apply(request, POLICY));
        rejected("Resident profile missing", () -> quote(other, PROPERTY, 0));
        execute("DELETE FROM npc_housing_members WHERE resident_id=?", other);
        rejected("Resident not admitted to housing", () -> repo.apply(request, POLICY));
        rejected("Resident not admitted to housing", () -> quote(other, PROPERTY, 0));
        assertTrue(new ResidentRepository(database).find(other).isEmpty());
    }

    @Test
    void missingSingletonRejectsQuotesFreshApplyHistoricalFindAndExactReplay() throws SQLException {
        HousingMoveRequest applied = quote(RESIDENT, PROPERTY, 0);
        repo.apply(applied, POLICY);
        UUID other = new UUID(0, 18);
        resident(other, STANDARD);
        HousingMoveRequest fresh = quote(other, PROPERTY, 1);
        execute("DELETE FROM npc_housing_registry_lock");
        rejected("Housing admission lock missing", () -> repo.apply(fresh, POLICY));
        rejected("Housing admission lock missing", () -> quote(other, PROPERTY, 1));
        rejected("Housing admission lock missing", () -> repo.apply(applied, POLICY));
        rejected("Housing admission lock missing", () -> repo.find(applied.requestId()));
        rejected("Housing admission lock missing", () -> repo.find(UUID.randomUUID()));
        assertEquals(0, count("npc_housing_registry_lock"));
    }

    @Test
    void premiumFailsClosedEvenWithFavorableLegacyOperatorDefaultsAndHistory() throws SQLException {
        HousingMoveRequest standardQuote = quote(RESIDENT, PROPERTY, 0);
        execute("UPDATE npc_housing_members SET tier='PREMIUM' WHERE resident_id=?", RESIDENT);
        execute("INSERT INTO npc_benefit_units(benefit_unit_id,note) VALUES (?,'legacy fixture')", OWNER);
        execute("INSERT INTO npc_benefit_unit_members(member_uuid,benefit_unit_id) VALUES (?,?)", OWNER, OWNER);
        execute("INSERT INTO npc_operator_reputation(benefit_unit_id,operator_score,observations) VALUES (?,100,100)", OWNER);
        for (boolean legacyRowPresent : List.of(false, true)) {
            if (legacyRowPresent) execute("""
                    INSERT INTO npc_operator_daily(benefit_unit_id,economy_day,utilities_on_time,incidents_total,
                      incidents_in_sla,tenants_total,tenants_retained) VALUES (?, ?, TRUE, 20, 20, 20, 20)
                    """, OWNER, DAY.toString());
            rejected("Premium housing policy is not enabled", () -> quote(RESIDENT, PROPERTY, 0));
            rejected("Housing membership changed", () -> repo.apply(standardQuote, POLICY));
        }
        assertQuotas(DAY, 0, 0);
        assertEquals(0, count("npc_tenants"));
    }

    @Test
    void currentEconomyDayUsesChicagoFourAmNotMidnightOrTheClocksOwnZone() throws SQLException {
        HousingMoveRequest oldQuote = quote(RESIDENT, PROPERTY, 0);
        for (LocalDate wrong : List.of(DAY.minusDays(1), DAY.plusDays(1))) {
            rejected("Housing requires current economy day",
                    () -> repo.quote(UUID.randomUUID(), RESIDENT, PROPERTY, 0, wrong));
        }
        Instant reset = DAY.plusDays(1).atTime(4, 0).atZone(ZONE).toInstant();
        clock.set(reset.minusMillis(1));
        HousingMoveRequest lastMillisecond = quote(RESIDENT, PROPERTY, 0);
        assertEquals(DAY, lastMillisecond.day());
        clock.set(reset);
        rejected("Housing requires current economy day", () -> repo.apply(oldQuote, POLICY));
        rejected("Housing requires current economy day", () -> repo.apply(lastMillisecond, POLICY));
        HousingMoveRequest newDay = repo.quote(UUID.randomUUID(), RESIDENT, PROPERTY, 0, DAY.plusDays(1));
        assertReceipt(repo.apply(newDay, POLICY), newDay, POLICY, 0);
        assertQuotas(DAY, 0, 0);
        assertQuotas(DAY.plusDays(1), 1, 1);
    }

    @Test
    void serverQuotaCountsOtherMarketsAndSurvivesRetryAndPoolRestart() throws SQLException {
        Policy limit = new Policy(1, 5, 2, 7);
        HousingMoveRequest first = quote(RESIDENT, PROPERTY, 0);
        Receipt receipt = repo.apply(first, limit).receipt();
        UUID other = new UUID(0, 18), otherHome = new UUID(0, 801);
        resident(other, housing(WORLD, "uptown", "room"));
        property(otherHome, WORLD, "uptown", "room");
        HousingMoveRequest next = quote(other, otherHome, 0);
        var before = snapshot();
        database.close();
        database.connect(settings);
        repo = reopened();
        assertEquals(new Result(false, receipt), repo.apply(first, limit));
        rejected("Housing daily quota reached", () -> repo.apply(next, limit));
        assertEquals(before, snapshot());
        assertQuotas(DAY, 1, 1);
    }

    @Test
    void marketQuotaSpansPropertiesButSeparatesWorldAndMarket() throws SQLException {
        Policy limit = new Policy(10, 1, 4, 7);
        repo.apply(quote(RESIDENT, PROPERTY, 0), limit);
        UUID sameMarket = new UUID(0, 801), otherMarket = new UUID(0, 802), otherWorld = new UUID(0, 803);
        UUID secondWorld = new UUID(0, 2);
        property(sameMarket, WORLD, "downtown", "apartment");
        property(otherMarket, WORLD, "uptown", "room");
        property(otherWorld, secondWorld, "downtown", "room");
        resident(new UUID(0, 18), housing(WORLD, "downtown", "apartment"));
        resident(new UUID(0, 19), housing(WORLD, "uptown", "room"));
        resident(new UUID(0, 20), housing(secondWorld, "downtown", "room"));
        HousingMoveRequest blocked = quote(new UUID(0, 18), sameMarket, 0);
        rejected("Housing daily quota reached", () -> reopened().apply(blocked, limit));
        assertTrue(repo.apply(quote(new UUID(0, 19), otherMarket, 0), limit).newlyApplied());
        assertTrue(repo.apply(quote(new UUID(0, 20), otherWorld, 0), limit).newlyApplied());
        assertQuotas(DAY, 3, 1);
        assertEquals(1, rows("SELECT request_id FROM npc_housing_moves WHERE world_uuid=? AND market_id='downtown'", WORLD).size());
    }

    @Test
    void propertyQuotaCountsDepartedResidentsButDoesNotConsumeAnotherPropertysAllowance() throws SQLException {
        Policy limit = new Policy(10, 5, 1, 7);
        repo.apply(quote(RESIDENT, PROPERTY, 0), limit);
        vacate(RESIDENT);
        UUID other = new UUID(0, 18), second = new UUID(0, 801);
        resident(other, STANDARD);
        property(second, WORLD, "downtown", "room");
        HousingMoveRequest blocked = quote(other, PROPERTY, 0);
        rejected("Housing daily quota reached", () -> reopened().apply(blocked, limit));
        assertTrue(repo.apply(quote(other, second, 0), limit).newlyApplied());
        assertQuotas(DAY, 2, 1);
        assertNull(tenant(RESIDENT).get("property_id"));
    }
    @Test
    void sameResidentCannotReadmitTwiceInOneDayEvenAfterVacancyAndClearedCooldown() throws SQLException {
        repo.apply(quote(RESIDENT, PROPERTY, 0), POLICY);
        vacate(RESIDENT);
        execute("UPDATE npc_tenants SET move_cooldown_until_day=NULL WHERE tenant_id=?", RESIDENT);
        UUID otherHome = new UUID(0, 801);
        property(otherHome, WORLD, "downtown", "room");
        rejected("Housing move cooldown regressed", () -> quote(RESIDENT, otherHome, 0));
        HousingMoveRequest again = new HousingMoveRequest(UUID.randomUUID(), RESIDENT, otherHome, OWNER,
                WORLD, "downtown", "room", 3, 11, 0, 1, 0, DAY);
        rejected("Housing move cooldown regressed", () -> reopened().apply(again, POLICY));
        assertNull(tenant(RESIDENT).get("property_id"));
        assertQuotas(DAY, 1, 1);
    }

    @Test
    void dayPolicyIsImmutableAcrossAllFieldsButANewDayMayChooseAnotherPolicy() throws SQLException {
        HousingMoveRequest first = quote(RESIDENT, PROPERTY, 0);
        for (Policy zero : List.of(new Policy(0, 5, 2, 7), new Policy(10, 0, 2, 7), new Policy(10, 5, 0, 7))) {
            rejected("Housing daily quota reached", () -> repo.apply(first, zero));
            assertQuotas(DAY, 0, 0);
        }
        repo.apply(first, POLICY);
        UUID other = new UUID(0, 18);
        resident(other, STANDARD);
        HousingMoveRequest next = quote(other, PROPERTY, 1);
        for (Policy conflict : policyConflicts()) {
            rejected("Housing day policy conflict", () -> reopened().apply(next, conflict));
        }
        assertTrue(repo.apply(next, POLICY).newlyApplied());
        clock.set(NOW.plusSeconds(86400));
        UUID tomorrowResident = new UUID(0, 19);
        resident(tomorrowResident, STANDARD);
        HousingMoveRequest tomorrow = repo.quote(UUID.randomUUID(), tomorrowResident, PROPERTY, 2, DAY.plusDays(1));
        Policy changed = new Policy(9, 4, 1, 8);
        assertTrue(reopened().apply(tomorrow, changed).newlyApplied());
        assertQuotas(DAY, 2, 1);
        assertQuotas(DAY.plusDays(1), 1, 1);
        assertEquals(List.of(Map.of("server_limit", "10", "market_limit", "5", "property_limit", "2", "cooldown_days", "7")),
                rows("SELECT server_limit,market_limit,property_limit,cooldown_days FROM npc_housing_days WHERE economy_day=?", DAY.toString()));
    }

    @Test
    void concurrentDifferentResidentsSerializeTheLastServerMarketAndPropertyAllowances() throws Exception {
        UUID other = new UUID(0, 18), otherHome = new UUID(0, 801);
        resident(other, STANDARD);
        property(otherHome, WORLD, "downtown", "room");
        for (String scope : List.of("server", "market", "property")) {
            execute("DELETE FROM npc_housing_moves");
            execute("DELETE FROM npc_housing_days");
            execute("DELETE FROM npc_tenants");
            String market = scope.equals("server") ? "uptown" : "downtown";
            execute("UPDATE npc_housing_members SET market_id=? WHERE resident_id=?", market, other);
            execute("UPDATE npc_properties SET market_id=? WHERE property_id=?", market, otherHome);
            boolean sameProperty = scope.equals("property");
            Policy limit = switch (scope) {
                case "server" -> new Policy(1, 5, 2, 7);
                case "market" -> new Policy(10, 1, 2, 7);
                default -> new Policy(10, 5, 1, 7);
            };
            assertQuotaRace(quote(RESIDENT, PROPERTY, 0), quote(other, sameProperty ? PROPERTY : otherHome,
                    sameProperty ? 1 : 0), limit);
        }
    }

    @Test
    void laterDayMissingTenancyAndRawRevisionResetRequireReconciliationButMonotonicReturnSucceeds() throws SQLException {
        HousingMoveRequest first = quote(RESIDENT, PROPERTY, 0);
        assertReceipt(repo.apply(first, POLICY), first, POLICY, 0);
        vacate(RESIDENT);
        clock.set(NOW.plusSeconds(7 * 86400L));
        LocalDate later = DAY.plusDays(7);
        HousingMoveRequest returning = repo.quote(UUID.randomUUID(), RESIDENT, PROPERTY, 1, later);
        assertEquals(1, returning.tenancyRevision());
        Map<String, String> preserved = new LinkedHashMap<>(tenant(RESIDENT));
        execute("DELETE FROM npc_tenants WHERE tenant_id=?", RESIDENT);
        rejected("Housing tenancy history requires reconciliation",
                () -> repo.quote(UUID.randomUUID(), RESIDENT, PROPERTY, 1, later));
        rejected("Housing tenancy history requires reconciliation", () -> repo.apply(returning, POLICY));
        execute("""
                INSERT INTO npc_tenants(tenant_id,display_name,property_id,slot,tier,archetype,mood,satisfaction,
                  move_cooldown_until_day,schedule_template,state,row_version)
                VALUES (?,'Authored Resident',NULL,NULL,'STANDARD','CARPENTER','NEUTRAL',50,?,'DEFAULT','IDLE',1)
                """, RESIDENT, later.toString());
        assertEquals(preserved, tenant(RESIDENT), "restore exactly the legitimately departed fixture row");
        execute("UPDATE npc_tenants SET row_version=0 WHERE tenant_id=?", RESIDENT);
        rejected("Housing tenancy revision regressed",
                () -> repo.quote(UUID.randomUUID(), RESIDENT, PROPERTY, 1, later));
        rejected("Housing tenancy revision regressed", () -> repo.apply(returning, POLICY));
        execute("UPDATE npc_tenants SET row_version=1 WHERE tenant_id=?", RESIDENT);
        execute("UPDATE npc_housing_moves SET payload_hash=? WHERE request_id=?", "0".repeat(64), first.requestId());
        rejected("Housing receipt binding mismatch",
                () -> repo.quote(UUID.randomUUID(), RESIDENT, PROPERTY, 1, later));
        rejected("Housing receipt binding mismatch", () -> repo.apply(returning, POLICY));
        execute("UPDATE npc_housing_moves SET payload_hash=? WHERE request_id=?", first.fingerprint(POLICY), first.requestId());
        var sides = sideRows();
        assertReceipt(reopened().apply(returning, POLICY), returning, POLICY, 2);
        preserved.put("property_id", hex(PROPERTY));
        preserved.put("slot", "1");
        preserved.put("row_version", "2");
        preserved.put("move_cooldown_until_day", later.plusDays(7).toString());
        assertEquals(preserved, tenant(RESIDENT));
        assertEquals(sides, sideRows());
        assertQuotas(DAY, 1, 1);
        assertQuotas(later, 1, 1);
    }

    @Test
    void concurrentExactRequestHasOneApplyOneReplayAndOneDurableReceipt() throws Exception {
        HousingMoveRequest request = quote(RESIDENT, PROPERTY, 0);
        var sides = sideRows();
        List<Result> results = behindRegistryLock(() -> reopened().apply(request, POLICY),
                () -> reopened().apply(request, POLICY));
        assertEquals(1, results.stream().filter(Result::newlyApplied).count());
        assertEquals(results.getFirst().receipt(), results.getLast().receipt());
        assertEquals(results.getFirst().receipt(), reopened().find(request.requestId()).orElseThrow());
        assertQuotas(DAY, 1, 1);
        assertEquals(1, count("npc_tenants"));
        assertEquals(sides, sideRows());
    }

    @Test
    void corruptedReceiptHashOutcomeAndPinnedPolicyFailOnFindReplayAndQuotaScan() throws SQLException {
        HousingMoveRequest request = quote(RESIDENT, PROPERTY, 0);
        Receipt original = repo.apply(request, POLICY).receipt();
        UUID other = new UUID(0, 18);
        resident(other, STANDARD);
        HousingMoveRequest next = quote(other, PROPERTY, 1);
        Map<String, String> corruptions = new LinkedHashMap<>();
        corruptions.put("payload_hash='" + "0".repeat(64) + "'", "Housing receipt binding mismatch");
        corruptions.put("tenancy_revision_after=9", "Invalid housing receipt outcome");
        corruptions.put("cooldown_until='2026-09-15'", "Invalid housing receipt outcome");
        corruptions.put("applied_at_millis=" + NOW.minusSeconds(86400).toEpochMilli(), "Invalid housing receipt outcome");
        corruptions.put("applied_at_millis=" + NOW.plusMillis(1).toEpochMilli(), "Invalid housing receipt outcome");
        for (var corruption : corruptions.entrySet()) {
            uncheckedUpdate("UPDATE npc_housing_moves SET " + corruption.getKey() + " WHERE request_id=?", request.requestId());
            rejected(corruption.getValue(), () -> reopened().find(request.requestId()));
            rejected(corruption.getValue(), () -> reopened().apply(request, POLICY));
            rejected(corruption.getValue(), () -> reopened().apply(next, POLICY));
            execute("UPDATE npc_housing_moves SET payload_hash=?,tenancy_revision_after=0,cooldown_until=?,applied_at_millis=? WHERE request_id=?",
                    request.fingerprint(POLICY), DAY.plusDays(7).toString(), NOW.toEpochMilli(), request.requestId());
        }
        execute("UPDATE npc_housing_days SET server_limit=9");
        rejected("Housing receipt binding mismatch", () -> reopened().find(request.requestId()));
        rejected("Housing receipt binding mismatch", () -> reopened().apply(request, POLICY));
        rejected("Housing receipt binding mismatch", () -> reopened().apply(next, new Policy(9, 5, 2, 7)));
        execute("UPDATE npc_housing_days SET server_limit=10");
        assertEquals(original, reopened().find(request.requestId()).orElseThrow());
        assertTrue(repo.apply(next, POLICY).newlyApplied());
    }

    @Test
    void rolloverAfterTenantOrReceiptWriteRollsBackCustodyQuotaAndNewDayPolicy() throws Exception {
        for (boolean returning : List.of(false, true)) {
            if (returning) vacant(RESIDENT, 19, DAY);
            clock.set(DAY.plusDays(1).atTime(4, 0).atZone(ZONE).toInstant().minusMillis(1));
            HousingMoveRequest request = quote(RESIDENT, PROPERTY, 0);
            var before = snapshot();
            afterActionRollover(request, returning ? "UPDATE" : "INSERT", "npc_tenants");
            assertEquals(before, snapshot(), "post-action rollover must roll back even the newly pinned policy");
            assertQuotas(DAY, 0, 0);
            assertTrue(repo.find(request.requestId()).isEmpty());
            if (returning) assertNull(tenant(RESIDENT).get("property_id"));
            else assertEquals(0, count("npc_tenants"));
        }
        execute("DELETE FROM npc_tenants");
        clock.set(DAY.plusDays(1).atTime(4, 0).atZone(ZONE).toInstant().minusMillis(1));
        HousingMoveRequest request = quote(RESIDENT, PROPERTY, 0);
        var before = snapshot();
        afterActionRollover(request, "INSERT", "npc_housing_moves");
        assertEquals(before, snapshot(), "the receipt INSERT must not escape the pre-commit day recheck");
        assertQuotas(DAY, 0, 0);
    }

    @Test
    void forcedReceiptInsertFailureLeavesNoOccupancyQuotaOrDayRowAndAllowsExactRetry() throws SQLException {
        for (boolean returning : List.of(false, true)) {
            execute("DELETE FROM npc_housing_moves");
            execute("DELETE FROM npc_housing_days");
            execute("DELETE FROM npc_tenants");
            if (returning) vacant(RESIDENT, 19, DAY);
            HousingMoveRequest request = quote(RESIDENT, PROPERTY, 0);
            var before = snapshot();
            String trigger = "hmove_receipt_failure";
            createTrigger(trigger, "BEFORE INSERT ON npc_housing_moves FOR EACH ROW "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced housing receipt INSERT failure'");
            try {
                SQLException failure = assertThrows(SQLException.class, () -> repo.apply(request, POLICY));
                assertNotInfrastructure(failure);
                assertEquals("45000", failure.getSQLState());
                assertTrue(failure.getMessage().contains("forced housing receipt INSERT failure"));
                assertEquals(before, snapshot());
                assertQuotas(DAY, 0, 0);
                assertEquals(0, rows("SELECT tenant_id FROM npc_tenants WHERE property_id IS NOT NULL").size());
                assertTrue(repo.find(request.requestId()).isEmpty());
            } finally { dropTrigger(trigger); }
            assertReceipt(reopened().apply(request, POLICY), request, POLICY, returning ? 20 : 0);
        }
    }

    @Test
    void v10RepeatPreservesDurableReceiptsPolicyCustodyAndSideTables() throws Exception {
        HousingMoveRequest request = quote(RESIDENT, PROPERTY, 0);
        Receipt receipt = repo.apply(request, POLICY).receipt();
        var before = snapshot();
        applyResource(V10);
        applyResource(V10);
        assertEquals(before, snapshot());
        assertEquals(new Result(false, receipt), reopened().apply(request, POLICY));
        assertEquals(receipt, reopened().find(request.requestId()).orElseThrow());
        for (String column : List.of("payload_hash", "economy_day", "market_id", "class_key", "cooldown_until")) {
            assertEquals(List.of(Map.of("charset_name", "ascii", "collation_name", "ascii_bin")), rows(
                    "SELECT CHARACTER_SET_NAME AS charset_name,COLLATION_NAME AS collation_name FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA=? AND TABLE_NAME='npc_housing_moves' AND COLUMN_NAME=?", schemaName, column));
        }
    }

    @Test
    void v10ResumesAfterCommittedDayDdlWithoutDiscardingPreexistingPolicyOrLegacyRows() throws Exception {
        var sides = sideRows();
        List<String> statements = resourceStatements(V10);
        // Rehearse the older V10 boundary without its newer, empty V16 dependent table.
        assertEquals(0,count("npc_chronicle_outbox"));
        execute("DROP TABLE npc_chronicle_outbox");
        execute("DROP TABLE npc_housing_moves");
        execute("DROP TABLE npc_housing_days");
        try {
            execute(statements.getFirst());
            execute("INSERT INTO npc_housing_days VALUES ('2026-09-06',9,4,1,8)");
            assertEquals(0, rows("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? "
                    + "AND TABLE_NAME='npc_housing_moves'", schemaName).size());
            // A new connection sees the first DDL and day row despite the interrupted migration.
            assertEquals(1, count("npc_housing_days"));
            applyResource(V10);
            applyResource(V10);
            applyResource("/db/migration/V16__chronicle_outbox.sql");
            assertEquals(List.of(Map.of("economy_day", "2026-09-06", "server_limit", "9", "market_limit", "4",
                    "property_limit", "1", "cooldown_days", "8")), rows("SELECT * FROM npc_housing_days"));
            assertEquals(sides, sideRows());
            assertEquals(0, count("npc_tenants"));
            HousingMoveRequest request = quote(RESIDENT, PROPERTY, 0);
            assertReceipt(repo.apply(request, POLICY), request, POLICY, 0);
            assertQuotas(DAY.minusDays(1), 0, 1);
        } finally { applyResource(V10); applyResource("/db/migration/V16__chronicle_outbox.sql"); }
    }
    private HousingMoveRepository reopened() {
        return new HousingMoveRepository(database, clock, ZONE, 4);
    }

    private HousingMoveRequest quote(UUID resident, UUID property, int slot) throws SQLException {
        return repo.quote(UUID.randomUUID(), resident, property, slot, DAY);
    }

    private static HousingPolicy housing(UUID world, String market, String classKey) {
        return new HousingPolicy(world, market, classKey, HousingPolicy.Tier.STANDARD, 1200, 40);
    }

    private static void resident(UUID resident, HousingPolicy policy) throws SQLException {
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(resident, "Resident " + resident)));
        assertTrue(new HousingRegistry(database).admit(resident, policy, DAY));
    }

    private static void property(UUID property, UUID world, String market, String classKey) throws SQLException {
        execute("""
                INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,
                  reference_rent_cents,asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled,row_version)
                VALUES (?,?,?,?,?,4,1000,1200,?,3,40,TRUE,11)
                """, property, world, "fixture-" + property, market, classKey, OWNER);
    }

    private static void vacant(UUID resident, long revision, LocalDate cooldown) throws SQLException {
        execute("""
                INSERT INTO npc_tenants(tenant_id,display_name,property_id,slot,tier,archetype,mood,satisfaction,
                  move_cooldown_until_day,schedule_template,state,row_version)
                VALUES (?,'Preserved Tenant',NULL,NULL,'STANDARD','RESTORER','HAPPY',73,?,'NIGHT_SHIFT','WORKING',?)
                """, resident, cooldown.toString(), revision);
    }

    private static void occupant(UUID resident, UUID property, Integer slot) throws SQLException {
        execute("INSERT INTO npc_tenants(tenant_id,display_name,property_id,slot,archetype) VALUES (?,'Existing occupant',?,?,'COOK')",
                resident, property, slot);
    }

    private static void vacate(UUID resident) throws SQLException {
        execute("UPDATE npc_tenants SET property_id=NULL,slot=NULL,row_version=row_version+1 WHERE tenant_id=?", resident);
    }

    private static Map<String, String> tenant(UUID resident) throws SQLException {
        List<Map<String, String>> result = rows("SELECT * FROM npc_tenants WHERE tenant_id=?", resident);
        assertEquals(1, result.size());
        return result.getFirst();
    }

    private static HousingMoveRequest copy(HousingMoveRequest original, UUID resident, UUID property, UUID owner,
                                           UUID world, String market, String classKey, int epoch, long propertyRevision,
                                           long membershipRevision, long tenancyRevision, int slot, LocalDate day) {
        return new HousingMoveRequest(original.requestId(), resident, property, owner, world, market, classKey,
                epoch, propertyRevision, membershipRevision, tenancyRevision, slot, day);
    }

    private static List<Policy> policyConflicts() {
        return List.of(new Policy(9, 5, 2, 7), new Policy(10, 4, 2, 7),
                new Policy(10, 5, 1, 7), new Policy(10, 5, 2, 8));
    }

    private void assertReceipt(Result result, HousingMoveRequest request, Policy policy, long after) throws SQLException {
        assertTrue(result.newlyApplied());
        Receipt expected = new Receipt(request, policy, after, request.day().plusDays(policy.cooldownDays()), clock.instant());
        assertEquals(expected, result.receipt());
        assertEquals(expected, reopened().find(request.requestId()).orElseThrow());
        assertEquals(List.of(Map.of("payload_hash", request.fingerprint(policy))),
                rows("SELECT payload_hash FROM npc_housing_moves WHERE request_id=?", request.requestId()));
    }

    private static void assertQuotas(LocalDate day, int receipts, int policies) throws SQLException {
        assertEquals(receipts, rows("SELECT request_id FROM npc_housing_moves WHERE economy_day=?", day.toString()).size());
        assertEquals(policies, rows("SELECT economy_day FROM npc_housing_days WHERE economy_day=?", day.toString()).size());
    }

    private void assertQuotaRace(HousingMoveRequest left, HousingMoveRequest right, Policy policy) throws Exception {
        var sides = sideRows();
        List<Boolean> results = behindRegistryLock(() -> applyOrQuota(left, policy), () -> applyOrQuota(right, policy));
        assertNotEquals(results.getFirst(), results.getLast(), "exactly one distinct resident must consume the last allowance");
        HousingMoveRequest winner = results.getFirst() ? left : right;
        HousingMoveRequest loser = results.getFirst() ? right : left;
        assertEquals(winner, reopened().find(winner.requestId()).orElseThrow().request());
        assertTrue(reopened().find(loser.requestId()).isEmpty());
        assertEquals(hex(winner.propertyId()), tenant(winner.residentId()).get("property_id"));
        assertEquals(Integer.toString(winner.slot()), tenant(winner.residentId()).get("slot"));
        assertEquals(0, rows("SELECT tenant_id FROM npc_tenants WHERE tenant_id=?", loser.residentId()).size());
        assertEquals(1, count("npc_tenants"));
        assertQuotas(DAY, 1, 1);
        var beforeRetry = snapshot();
        assertFalse(reopened().apply(winner, policy).newlyApplied());
        rejected("Housing daily quota reached", () -> reopened().apply(loser, policy));
        assertEquals(beforeRetry, snapshot());
        assertEquals(sides, sideRows());
    }

    private boolean applyOrQuota(HousingMoveRequest request, Policy policy) throws SQLException {
        try {
            assertTrue(reopened().apply(request, policy).newlyApplied());
            return true;
        } catch (SQLException rejection) {
            assertDomainSql(rejection, "Housing daily quota reached");
            return false;
        }
    }

    private static <T> List<T> behindRegistryLock(Callable<T> left, Callable<T> right) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<T> a;
            Future<T> b;
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    lockRow(blocker, "npc_housing_registry_lock");
                    long blockerId = connectionId(blocker);
                    a = workers.submit(left);
                    b = workers.submit(right);
                    List<Map<String, String>> waiters = awaitWaiters("npc_housing_registry_lock", blockerId, 2);
                    for (var waiter : waiters) {
                        String sql = waiter.get("query_text").toLowerCase(Locale.ROOT);
                        assertTrue(sql.contains("npc_housing_registry_lock") && sql.contains("for update"), sql);
                    }
                    assertFalse(a.isDone(), "left actually waits for the held InnoDB singleton row");
                    assertFalse(b.isDone(), "right actually waits for the held InnoDB singleton row");
                } finally { blocker.rollback(); }
            }
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "housing race workers terminated");
        }
    }

    private void afterActionRollover(HousingMoveRequest request, String action, String table) throws Exception {
        execute("CREATE TABLE housing_it_barrier(singleton_id TINYINT PRIMARY KEY,hits INT NOT NULL) ENGINE=InnoDB");
        execute("INSERT INTO housing_it_barrier VALUES (1,0)");
        String trigger = "hmove_after_action_barrier";
        String gate = "housing-after-" + UUID.randomUUID().toString();
        createTrigger(trigger, "AFTER " + action + " ON " + table + " FOR EACH ROW "
                + "BEGIN UPDATE housing_it_barrier SET hits=hits+1 WHERE singleton_id=1; "
                + "IF GET_LOCK('" + gate + "',5)<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test gate timeout'; END IF; "
                + "DO RELEASE_LOCK('" + gate + "'); END");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<SQLException> result;
            try (Connection blocker = database.getConnection()) {
                assertEquals(1, namedLock(blocker, "GET_LOCK", gate));
                try {
                    long blockerId = connectionId(blocker);
                    result = worker.submit(() -> assertThrows(SQLException.class, () -> repo.apply(request, POLICY)));
                    // The only GET_LOCK in this worker is after the real row mutation, inside its AFTER trigger.
                    awaitAfterTriggerGate(gate, blockerId);
                    assertFalse(result.isDone());
                    clock.set(DAY.plusDays(1).atTime(4, 0).atZone(ZONE).toInstant());
                } finally { assertEquals(1, namedLock(blocker, "RELEASE_LOCK", gate)); }
            }
            assertDomainSql(result.get(15, TimeUnit.SECONDS), "Housing economy day changed before commit");
            assertEquals(List.of(Map.of("singleton_id", "1", "hits", "0")), rows("SELECT * FROM housing_it_barrier"));
        } finally {
            worker.shutdownNow();
            try { assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS), "rollover worker terminated"); }
            finally {
                dropTrigger(trigger);
                execute("DROP TABLE housing_it_barrier");
            }
        }
    }

    private static List<Map<String, String>> awaitWaiters(String table, long blockerId, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<Map<String, String>> waiters = List.of();
        while (System.nanoTime() < deadline) {
            // Embedded Windows MariaDB reports these blocked PK lookups as Statistics, with empty
            // INNODB_LOCK_WAITS. Observe both actual SQL calls pending behind our held singleton;
            // callers also require unresolved Futures and exact post-release transaction outcomes.
            waiters = rows("SELECT ID AS connection_id,INFO AS query_text FROM information_schema.PROCESSLIST "
                    + "WHERE DB=? AND ID<>CONNECTION_ID() AND ID<>? AND INFO LIKE ?", schemaName, blockerId, "%" + table + "%");
            if (waiters.size() == expected) return waiters;
            Thread.sleep(10);
        }
        fail("Expected " + expected + " pending singleton SQL readers on " + table + ", saw " + waiters);
        return waiters;
    }

    private static void awaitAfterTriggerGate(String gate, long blockerId) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<Map<String, String>> waiters = List.of();
        while (System.nanoTime() < deadline) {
            assertEquals(List.of(Map.of("holder", Long.toString(blockerId))), rows("SELECT IS_USED_LOCK(?) AS holder", gate));
            waiters = rows("SELECT ID,STATE,INFO FROM information_schema.PROCESSLIST WHERE DB=? "
                    + "AND ID<>CONNECTION_ID() AND ID<>? AND LOWER(STATE)='user lock'", schemaName, blockerId);
            if (waiters.size() == 1) return;
            Thread.sleep(10);
        }
        fail("Worker did not reach the AFTER-trigger advisory gate: " + waiters);
    }

    private static int namedLock(Connection c, String function, String gate) throws SQLException {
        assertTrue(function.equals("GET_LOCK") || function.equals("RELEASE_LOCK"));
        try (PreparedStatement ps = c.prepareStatement("SELECT " + function + "(?" + (function.equals("GET_LOCK") ? ",0" : "") + ")")) {
            ps.setString(1, gate); ps.setQueryTimeout(3);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next()); int result = rs.getInt(1); assertFalse(rs.wasNull()); assertFalse(rs.next()); return result;
            }
        }
    }

    private static void lockRow(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT singleton_id FROM " + table + " WHERE singleton_id=1 FOR UPDATE")) {
            ps.setQueryTimeout(3);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertFalse(rs.next());
            }
        }
    }

    private static long connectionId(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT CONNECTION_ID()")) {
            ps.setQueryTimeout(3);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getLong(1); }
        }
    }
    private void createTrigger(String name, String body) throws SQLException {
        triggers.add(name);
        execute("CREATE TRIGGER " + name + " " + body);
    }

    private void dropTrigger(String name) throws SQLException {
        execute("DROP TRIGGER IF EXISTS " + name);
        triggers.remove(name);
    }

    private void guardSideWrites() throws SQLException {
        guardWrites(tables.stream().filter(table -> !CHANGED_TABLES.contains(table)).toList());
    }

    private void guardAllWrites() throws SQLException {
        guardWrites(tables);
    }

    private void guardWrites(List<String> guarded) throws SQLException {
        int index = 0;
        for (String table : guarded) {
            for (String action : List.of("INSERT", "UPDATE", "DELETE")) {
                createTrigger("hmove_write_guard_" + index++, "BEFORE " + action + " ON " + table
                        + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forbidden housing side write'");
            }
        }
    }

    private static void rejected(String message, Executable operation) throws SQLException {
        var before = snapshot();
        assertDomainSql(assertThrows(SQLException.class, operation), message);
        assertEquals(before, snapshot(), "rejection must preserve custody, receipts, quota policy and every side table");
    }

    private static void assertDomainSql(SQLException rejection, String message) {
        assertNotInfrastructure(rejection);
        assertEquals(message, rejection.getMessage());
        assertEquals(0, rejection.getErrorCode(), "a driver/constraint error is not a repository domain rejection");
    }

    private static void assertNotInfrastructure(SQLException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            assertFalse(cause instanceof SQLTimeoutException, "timeout is not a domain result");
            if (cause instanceof SQLException sql) {
                assertNotEquals(1205, sql.getErrorCode(), "lock wait timeout is not a domain result");
                assertNotEquals(1213, sql.getErrorCode(), "deadlock is not a domain result");
                assertNotEquals(1317, sql.getErrorCode(), "query interruption is not a domain result");
                assertNotEquals(1969, sql.getErrorCode(), "statement timeout is not a domain result");
            }
        }
    }

    private static Map<String, List<Map<String, String>>> snapshot() throws SQLException {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
        for (String table : tables) result.put(table, rows("SELECT * FROM " + table));
        return result;
    }

    private static Map<String, List<Map<String, String>>> sideRows() throws SQLException {
        var result = snapshot();
        for (String table : CHANGED_TABLES) result.remove(table);
        return result;
    }

    private static int count(String table) throws SQLException {
        return Integer.parseInt(rows("SELECT COUNT(*) AS n FROM " + table).getFirst().get("n"));
    }

    private static String hex(UUID uuid) {
        return HexFormat.of().formatHex(Uuids.toBytes(uuid));
    }

    private static List<Map<String, String>> rows(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(3);
            bind(ps, values);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, String>> result = new ArrayList<>();
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        row.put(rs.getMetaData().getColumnLabel(i), value instanceof byte[] bytes
                                ? HexFormat.of().formatHex(bytes) : rs.getString(i));
                    }
                    result.add(row);
                }
                result.sort(Comparator.comparing(Map::toString));
                return result;
            }
        }
    }

    private static void uncheckedUpdate(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION check_constraint_checks=OFF");
            try { execute(c, sql, values); }
            finally { execute(c, "SET SESSION check_constraint_checks=ON"); }
        }
    }

    private static List<String> resourceStatements(String path) throws Exception {
        try (InputStream in = HousingMoveRepositoryIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void applyResource(String path) throws Exception {
        for (String sql : resourceStatements(path)) execute(sql);
    }

    private static void execute(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection()) { execute(c, sql, values); }
    }

    private static void execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(3);
            bind(ps, values);
            ps.execute();
        }
    }

    private static void bind(PreparedStatement ps, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof UUID uuid) ps.setBytes(i + 1, Uuids.toBytes(uuid));
            else ps.setObject(i + 1, values[i]);
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        private MutableClock(Instant now, ZoneId zone) { this(new AtomicReference<>(now), zone); }
        private MutableClock(AtomicReference<Instant> now, ZoneId zone) { this.now = now; this.zone = zone; }
        void set(Instant instant) { now.set(instant); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(now, zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
