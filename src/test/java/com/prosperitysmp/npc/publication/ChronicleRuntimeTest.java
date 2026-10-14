package com.prosperitysmp.npc.publication;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.market.HousingMoveRepository;
import com.prosperitysmp.npc.market.HousingMoveRequest;
import com.prosperitysmp.npc.market.HousingPolicy;
import com.prosperitysmp.npc.market.HousingRegistry;
import com.prosperitysmp.npc.publication.ChronicleRuntime.*;
import com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real admission/outbox/journal SQL; explicitly synthetic credentials, bootstrap evidence, and network replies. */
@Timeout(30)
class ChronicleRuntimeTest {
    private static final Instant NOW=Instant.parse("2026-09-29T12:00:00Z");
    private static final LocalDate DAY=LocalDate.of(2026,9,29);
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private static final UUID WORLD=UUID.randomUUID(),PROPERTY=UUID.randomUUID(),OWNER=UUID.randomUUID();
    private static final Approval SCOPE=new Approval(PROPERTY,WORLD,"fixture-room","main");
    private static final Destination DESTINATION=new Destination("123","456");
    private static DB maria;
    private static Database database;
    private static DatabaseSettings connection;
    private final List<ChronicleRuntime> runtimes=new ArrayList<>();
    private final AtomicLong monotonic=new AtomicLong();
    private final AtomicInteger credentials=new AtomicInteger(),factories=new AtomicInteger();
    private UUID generation;

    @BeforeAll static void boot() throws Exception {
        var cfg=DBConfigurationBuilder.newBuilder(); cfg.setPort(0); cfg.setBaseDir(Files.createTempDirectory("npc-chronicle-runtime-").toFile());
        maria=DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build()); maria.start();
        String schema="npc_chruntime_"+UUID.randomUUID().toString().replace("-",""); maria.createDB(schema);
        connection=new DatabaseSettings("localhost",cfg.getPort(),schema,"root","",6,10000);
        database=new Database(); database.connect(connection);
        for(String name:List.of("V1__initial_schema.sql","V2__resident_identity.sql","V9__housing_registry.sql","V10__housing_moves.sql","V26__chronicle_outbox.sql"))
            for(String sql:MigrationRunner.splitStatements(new String(resource(name),StandardCharsets.UTF_8))) execute(sql);
    }
    @AfterAll static void stop() throws Exception { try { if(database!=null) database.close(); } finally { if(maria!=null) maria.stop(); } }
    @BeforeEach void reset() throws Exception {
        for(String table:List.of("npc_chronicle_send_intents","npc_chronicle_attempts","npc_chronicle_progress","npc_chronicle_outbox",
                "npc_housing_moves","npc_housing_days","npc_housing_members","npc_resident_memory","npc_resident_profiles","npc_tenants","npc_properties"))
            execute("DELETE FROM "+table);
        generation=UUID.randomUUID(); property(PROPERTY,"fixture-room");
    }
    @AfterEach void drain() throws Exception { for(var runtime:runtimes) runtime.shutdown().toCompletableFuture().get(10,TimeUnit.SECONDS); }

    @Test void forwardSchemaIsExactAndDisabledRuntimeDoesNoIoOrCredentialWork() throws Exception {
        assertArrayEquals(resource("V16__chronicle_outbox.sql"),resource("V26__chronicle_outbox.sql"));
        var disconnected=new Database();
        var runtime=new ChronicleRuntime(disconnected,CLOCK,ZoneOffset.UTC,4,Settings.disabled(),monotonic::get,
                path -> { fail("Disabled credential read"); return ""; },(destination,token) -> { fail("Disabled HTTP construction"); return null; });
        runtimes.add(runtime); runtime.tick(); assertEquals(Status.DISABLED,runtime.status()); assertTrue(runtime.heldAdmissions().isEmpty());
        assertTrue(runtime.recoveryHealth().isEmpty()); runtime.shutdown().toCompletableFuture().get(1,TimeUnit.SECONDS);
        assertEquals(Status.STOPPED,runtime.status()); runtime.tick();
        assertThrows(IllegalArgumentException.class,() -> new Settings(true,generation,List.of(SCOPE,SCOPE),Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,() -> new Settings(true,generation,List.of(SCOPE),Duration.ZERO));
        try(var pool=Executors.newSingleThreadExecutor()) {
            assertTrue(pool.submit(() -> assertThrows(IllegalStateException.class,runtime::tick)).get(2,TimeUnit.SECONDS) instanceof IllegalStateException);
        }
    }

    @Test void enableReadsHeldBeforeRecoveryStagesOnlyApprovedScopeAndRestartRetainsExactDrafts() throws Exception {
        HousingMoveRequest first=admit(PROPERTY,0),second=admit(PROPERTY,1);
        UUID foreign=UUID.randomUUID(); property(foreign,"private-room"); admit(foreign,0);
        var original=outbox().stage(first.requestId(),SCOPE,generation);
        var runtime=runtime(new FakeTransport(false)); runtime.tick();
        assertEquals(ChronicleRecoveryHost.Disposition.NOT_SCANNED,runtime.recoveryHealth().getFirst().disposition());
        await(runtime,() -> count("npc_chronicle_outbox")==2 && runtime.heldAdmissions().size()==2);
        assertEquals(Status.HELD_ONLY,runtime.status()); assertEquals(0,credentials.get()); assertEquals(0,factories.get());
        assertTrue(runtime.heldAdmissions().stream().anyMatch(row -> row.requestId().equals(first.requestId()) && row.held().equals(original)));
        assertTrue(runtime.heldAdmissions().stream().anyMatch(row -> row.requestId().equals(second.requestId())));
        assertEquals(3,count("npc_housing_moves")); assertEquals(3,count("npc_tenants")); assertEquals(0,count("npc_chronicle_send_intents"));
        runtime.shutdown().toCompletableFuture().get(10,TimeUnit.SECONDS);
        database.close(); database=new Database(); database.connect(connection);
        var reopened=runtime(new FakeTransport(false)); await(reopened,() -> reopened.heldAdmissions().size()==2);
        assertEquals(original,outbox().stage(first.requestId(),SCOPE,generation)); assertEquals(2,count("npc_chronicle_outbox"));
        assertEquals(3,count("npc_housing_moves")); assertEquals(0,factories.get());
    }

    @Test void exactApprovalAndCurrentProcessEvidenceGateCredentialReadAndDrainAcknowledgmentBeforeClose() throws Exception {
        var request=admit(PROPERTY,0); outbox().stage(request.requestId(),SCOPE,generation);
        FakeTransport transport=new FakeTransport(true); var runtime=runtime(transport);
        var exact=new StoryApproval(request.requestId(),SCOPE,outbox().stage(request.requestId(),SCOPE,generation));
        var approval=new DeliveryApproval("owner-case-50",generation,DESTINATION,List.of(exact));
        assertFalse(runtime.enableDispatch(Path.of("fixture-only"),approval,ChronicleRuntimeTest::proof)); assertEquals(0,credentials.get());
        await(runtime,() -> runtime.heldAdmissions().size()==1);
        assertFalse(runtime.enableDispatch(Path.of("fixture-only"),approval,process -> Optional.empty()));
        assertFalse(runtime.enableDispatch(Path.of("fixture-only"),approval,process -> Optional.of(new CleanStartEvidence(
                new ProcessIdentity(process.pid()+1,process.startedAt()),"a".repeat(64)))));
        assertEquals(0,credentials.get()); assertEquals(0,factories.get());
        assertTrue(runtime.enableDispatch(Path.of("fixture-only"),approval,ChronicleRuntimeTest::proof));
        assertEquals(1,credentials.get()); assertEquals(1,factories.get());
        try {
            runtime.tick(); assertTrue(transport.entered.await(5,TimeUnit.SECONDS));
            assertEquals(1,count("npc_chronicle_send_intents"));
            var drain=runtime.shutdown(); assertFalse(drain.toCompletableFuture().isDone());
            drain.toCompletableFuture().complete(null); assertFalse(runtime.shutdown().toCompletableFuture().isDone());
            assertEquals(0,transport.closes.get()); assertEquals(Status.STOPPING,runtime.status());
        } finally { transport.release.countDown(); }
        runtime.shutdown().toCompletableFuture().get(10,TimeUnit.SECONDS);
        assertEquals(1,transport.posts.get()); assertEquals(1,transport.closes.get()); assertEquals("ACKNOWLEDGED",text("SELECT state FROM npc_chronicle_send_intents"));
        assertEquals(Status.STOPPED,runtime.status());
        var restarted=runtime(new FakeTransport(false)); await(restarted,() -> restarted.status()!=Status.STARTING);
        assertTrue(restarted.heldAdmissions().isEmpty()); assertEquals(1,count("npc_chronicle_send_intents"));
    }

    @Test void unconfirmedAttemptStopsFurtherSendingAndNeverRetriesItsDurableIntent() throws Exception {
        var first=admit(PROPERTY,0); var second=admit(PROPERTY,1);
        outbox().stage(first.requestId(),SCOPE,generation); outbox().stage(second.requestId(),SCOPE,generation);
        FakeTransport transport=new FakeTransport(false); transport.unconfirmed=true; var runtime=runtime(transport);
        await(runtime,() -> runtime.heldAdmissions().size()==2);
        assertTrue(runtime.enableDispatch(Path.of("fixture-only"),approval(runtime.heldAdmissions()),ChronicleRuntimeTest::proof));
        await(runtime,() -> runtime.lastDispatchOutcome().isPresent());
        assertEquals(Status.HELD_DISPATCH,runtime.status());
        for(int i=0;i<10;i++) { monotonic.addAndGet(TimeUnit.SECONDS.toNanos(1)); runtime.tick(); Thread.sleep(10); }
        assertEquals(1,transport.posts.get()); assertEquals(1,count("npc_chronicle_send_intents"));
        assertEquals("UNCERTAIN",text("SELECT state FROM npc_chronicle_send_intents"));
        assertFalse(runtime.enableDispatch(Path.of("fixture-only"),approval(runtime.heldAdmissions()),ChronicleRuntimeTest::proof));
    }

    @Test void revocationAndCorruptImmutableSourceNeverCreateSendPermission() throws Exception {
        var request=admit(PROPERTY,0); outbox().stage(request.requestId(),SCOPE,generation);
        FakeTransport transport=new FakeTransport(false); var runtime=runtime(transport);
        await(runtime,() -> runtime.heldAdmissions().size()==1);
        DeliveryApproval approval=approval(runtime.heldAdmissions());
        var valid=new java.util.concurrent.atomic.AtomicBoolean(true);
        assertTrue(runtime.enableDispatch(Path.of("fixture-only"),approval,process -> valid.get()?proof(process):Optional.empty()));
        valid.set(false); await(runtime,() -> runtime.lastDispatchOutcome().isPresent());
        assertEquals(ChronicleDispatcher.Result.NOT_AUTHORIZED,runtime.lastDispatchOutcome().orElseThrow().result());
        assertEquals(Status.HELD_AUTHORIZATION,runtime.status()); assertEquals(0,transport.posts.get()); assertEquals(0,count("npc_chronicle_send_intents"));
        runtime.shutdown().toCompletableFuture().get(10,TimeUnit.SECONDS);
        execute("UPDATE npc_chronicle_outbox SET source_sha256=?","f".repeat(64));
        var corrupt=runtime(new FakeTransport(false)); await(corrupt,() -> corrupt.status()==Status.HELD_READ);
        assertTrue(corrupt.heldAdmissions().isEmpty()); assertEquals(0,count("npc_chronicle_send_intents"));
        assertEquals(1,count("npc_housing_moves")); assertEquals(1,count("npc_tenants"));
    }

    private ChronicleRuntime runtime(FakeTransport transport) {
        var runtime=new ChronicleRuntime(database,CLOCK,ZoneOffset.UTC,4,new Settings(true,generation,List.of(SCOPE),Duration.ofSeconds(1)),
                monotonic::get,path -> { credentials.incrementAndGet(); return "synthetic-not-a-secret"; },
                (destination,token) -> { factories.incrementAndGet(); return transport; });
        runtimes.add(runtime); return runtime;
    }
    private DeliveryApproval approval(List<HeldAdmission> rows) {
        return new DeliveryApproval("owner-case-50",generation,DESTINATION,rows.stream().map(row -> new StoryApproval(row.requestId(),row.scope(),row.held())).toList());
    }
    private static Optional<CleanStartEvidence> proof(ProcessIdentity process) {
        // Fixture authority only: NOT verified clean-start evidence for this machine or any deployment.
        return Optional.of(new CleanStartEvidence(process,"a".repeat(64)));
    }
    private void await(ChronicleRuntime runtime,BooleanSupplier done) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<deadline) { monotonic.addAndGet(TimeUnit.SECONDS.toNanos(1)); runtime.tick(); if(done.getAsBoolean()) return; Thread.sleep(10); }
        fail("Chronicle fixture did not reach expected bounded state: "+runtime.status());
    }
    private static HousingMoveRequest admit(UUID property,int slot) throws Exception {
        UUID resident=UUID.randomUUID(); new ResidentRepository(database).register(ResidentCatalogue.draft(resident,"Fixture Resident"));
        new HousingRegistry(database).admit(resident,new HousingPolicy(WORLD,"downtown","room",HousingPolicy.Tier.STANDARD,1200,40),DAY);
        var housing=new HousingMoveRepository(database,CLOCK,ZoneOffset.UTC,4);
        var request=housing.quote(UUID.randomUUID(),resident,property,slot,DAY);
        housing.apply(request,new HousingMoveRequest.Policy(100,100,4,7)); return request;
    }
    private static void property(UUID id,String region) throws Exception {
        execute("INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,reference_rent_cents,"
                +"asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled,row_version) VALUES (?,?,?,'downtown','room',4,1000,1200,?,3,40,1,11)",id,WORLD,region,OWNER);
    }
    private ChronicleOutboxRepository outbox() { return new ChronicleOutboxRepository(database,CLOCK,ZoneOffset.UTC,4); }
    private static byte[] resource(String name) throws Exception {
        try(var input=ChronicleRuntimeTest.class.getResourceAsStream("/db/migration/"+name)) { assertNotNull(input); return input.readAllBytes(); }
    }
    private static void execute(String sql,Object... values) throws Exception {
        try(var c=database.getConnection();var ps=c.prepareStatement(sql)) {
            ps.setQueryTimeout(5); for(int i=0;i<values.length;i++) { if(values[i] instanceof UUID id) ps.setBytes(i+1,Uuids.toBytes(id)); else ps.setObject(i+1,values[i]); }
            ps.execute();
        }
    }
    private static int count(String table) { try { return Integer.parseInt(text("SELECT COUNT(*) FROM "+table)); } catch(Exception error) { throw new AssertionError(error); } }
    private static String text(String sql) throws Exception {
        try(var c=database.getConnection();var ps=c.prepareStatement(sql)) { ps.setQueryTimeout(5); try(var rs=ps.executeQuery()) { assertTrue(rs.next()); return rs.getString(1); } }
    }
    private static final class FakeTransport implements ChronicleDispatcher.Transport,AutoCloseable {
        final AtomicInteger posts=new AtomicInteger(),closes=new AtomicInteger();
        final CountDownLatch entered=new CountDownLatch(1),release;
        volatile boolean unconfirmed;
        FakeTransport(boolean block) { release=new CountDownLatch(block?1:0); }
        @Override public ChronicleDispatcher.Reply post(Destination destination,ChronicleWebhookProtocol.Message message) throws InterruptedException {
            posts.incrementAndGet(); entered.countDown(); assertTrue(release.await(8,TimeUnit.SECONDS));
            if(unconfirmed) return new ChronicleDispatcher.Reply(429,new byte[0]);
            JsonObject response=new JsonObject(); response.addProperty("id","789"); response.addProperty("webhook_id",destination.webhookId());
            response.addProperty("channel_id",destination.channelId()); response.addProperty("content",""); response.addProperty("tts",false); response.addProperty("mention_everyone",false);
            for(String field:List.of("mentions","mention_roles","attachments")) response.add(field,new JsonArray());
            response.add("embeds",JsonParser.parseString(message.json()).getAsJsonObject().get("embeds"));
            return new ChronicleDispatcher.Reply(200,response.toString().getBytes(StandardCharsets.UTF_8));
        }
        @Override public void close() throws Exception {
            if(posts.get()>0 && !unconfirmed) assertEquals("ACKNOWLEDGED",text("SELECT state FROM npc_chronicle_send_intents"));
            closes.incrementAndGet();
        }
    }
}
