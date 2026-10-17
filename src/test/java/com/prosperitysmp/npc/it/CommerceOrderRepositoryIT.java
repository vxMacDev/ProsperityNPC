package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.commerce.CommerceOrder;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository.*;
import com.prosperitysmp.npc.commerce.CustodyBytes;
import com.prosperitysmp.npc.commerce.ItemCustody;
import com.prosperitysmp.npc.commerce.ItemCustody.Source;
import com.prosperitysmp.npc.commerce.ItemCustodyRepository;
import com.prosperitysmp.npc.commerce.ItemDeliveryEvidence;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded scenarios against actual MariaDB; never Vault, Bukkit, or fabricated property events. */
class CommerceOrderRepositoryIT {
    private static final Instant NOW=Instant.parse("2026-09-29T12:00:00Z");
    private static final LocalDate DAY=LocalDate.of(2026,9,29);
    private static final Budgets BUDGETS=new Budgets(100,6400,6400,1_000_000);
    private static final Observation DEBIT=new Observation(true,true,10.0,100.0,90.0,false);
    private static final Observation CREDIT=new Observation(true,true,10.0,20.0,30.0,false);
    private static final CustodyBytes PAYLOAD=new CustodyBytes(new byte[]{0,1,-1,42});
    private static DB mariaDb;
    private static Database database;
    private static DatabaseSettings settings;
    private static UUID resident;
    private CommerceOrderRepository repository;
    private UUID reserve;
    private UUID run;

    @BeforeAll static void boot() throws Exception {
        DBConfigurationBuilder cfg=DBConfigurationBuilder.newBuilder(); cfg.setPort(0);
        cfg.setBaseDir(Files.createTempDirectory("npc-commerce-it-").toFile());
        mariaDb=DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build()); mariaDb.start();
        String schema="npc_commerce_"+UUID.randomUUID().toString().replace("-",""); mariaDb.createDB(schema);
        settings=new DatabaseSettings("localhost",cfg.getPort(),schema,"root","",6,10_000L);
        database=new Database(); database.connect(settings);
        // V1, V35 and V36: beginDelivery also checks open item-repair hand-ins and contract hand-overs (empty here).
        for(String migration:List.of("V1__initial_schema.sql","V2__resident_identity.sql","V21__item_custody.sql","V22__commerce_orders.sql","V25__commerce_resolution.sql","V35__item_repairs.sql","V36__town_contracts_partnerships.sql")) {
            try(InputStream input=CommerceOrderRepositoryIT.class.getResourceAsStream("/db/migration/"+migration)) {
                assertNotNull(input);
                for(String sql:MigrationRunner.splitStatements(new String(input.readAllBytes(),StandardCharsets.UTF_8))) execute(sql);
            }
        }
        // V40: the refusal columns the transfer and commerce readers expect, on the tables this schema has.
        try (var v40 = database.getConnection()) { com.prosperitysmp.npc.db.V40Fixture.apply(v40); }
        var profile=ResidentCatalogue.betaCast().getFirst(); new ResidentRepository(database).register(profile); resident=profile.id();
    }
    @AfterAll static void stop() throws Exception {
        try { if(database!=null) database.close(); } finally { if(mariaDb!=null) mariaDb.stop(); }
    }
    @BeforeEach void reset() throws SQLException {
        execute("DROP TRIGGER IF EXISTS commerce_fixture_failure");
        for(String table:List.of("npc_commerce_payment_resolutions","npc_commerce_money_evidence","npc_commerce_orders","npc_commerce_material_days","npc_commerce_days",
                "npc_commerce_players","npc_item_custody_resolutions","npc_item_delivery_evidence","npc_item_custody","npc_item_custody_players")) execute("DELETE FROM "+table);
        reserve=UUID.randomUUID(); run=UUID.randomUUID(); repository=new CommerceOrderRepository(database,reserve);
    }
    @AfterEach void removeTrigger() throws SQLException { execute("DROP TRIGGER IF EXISTS commerce_fixture_failure"); }

    @Test void concurrentGlobalMaterialAndCostCapsArePermanentAcrossCancellation() throws Exception {
        Budgets stone=new Budgets(5,160,96,5000), oak=new Budgets(5,160,160,5000);
        List<Callable<Boolean>> calls=new ArrayList<>();
        for(int i=0;i<12;i++) {
            CommerceOrder order=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",32,DAY);
            calls.add(() -> admitted(order,stone));
        }
        assertEquals(3,race(calls).stream().filter(Boolean::booleanValue).count());
        calls.clear();
        for(int i=0;i<8;i++) {
            CommerceOrder order=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"OAK_LOG",32,DAY);
            calls.add(() -> admitted(order,oak));
        }
        assertEquals(2,race(calls).stream().filter(Boolean::booleanValue).count());
        assertEquals(5,count("npc_commerce_orders")); assertEquals(160,scalar("SELECT issued_units FROM npc_commerce_days"));
        assertEquals(5000,scalar("SELECT issued_cost_cents FROM npc_commerce_days"));
        CommerceOrder cancel=knownOrder(); repository.cancel(cancel);
        assertThrows(SQLException.class,() -> repository.reserve(order(UUID.randomUUID(),Source.MERCHANT_GOODS,"OAK_LOG",1,DAY),oak));
        assertEquals(5,scalar("SELECT issued_orders FROM npc_commerce_days"));
        assertThrows(SQLException.class,() -> repository.reserve(cancel,new Budgets(6,160,160,5000)));
    }

    @Test void deterministicReplayAndWorkerDaySlotSurviveCancelAndDecline() throws SQLException {
        UUID player=UUID.randomUUID(); CommerceOrder worker=order(player,Source.WORKER_OUTPUT,"STONE",4,DAY);
        assertEquals(worker.orderId(),CommerceOrder.idFor(worker.issuanceNonce(),player,worker.source(),DAY));
        assertTrue(repository.reserve(worker,BUDGETS).newlyReserved()); assertFalse(repository.reserve(worker,BUDGETS).newlyReserved());
        CommerceOrder conflict=new CommerceOrder(worker.orderId(),worker.issuanceNonce(),player,resident,worker.source(),worker.catalogueId(),
                "OAK_LOG",worker.quantity(),1000,DAY,NOW,60);
        assertThrows(SQLException.class,() -> repository.reserve(conflict,BUDGETS));
        assertThrows(IllegalArgumentException.class,() -> new CommerceOrder(worker.orderId(),worker.issuanceNonce(),player,resident,
                worker.source(),worker.catalogueId(),"STONE",65,1000,DAY,NOW,60));
        assertEquals(State.CANCELLED,repository.cancel(worker).state());
        assertThrows(IssuanceRefused.class,() -> repository.reserve(order(player,Source.WORKER_OUTPUT,"STONE",1,DAY),BUDGETS));
        CommerceOrder next=order(player,Source.WORKER_OUTPUT,"STONE",4,DAY.plusDays(1)); repository.reserve(next,BUDGETS);
        Handle h=handle(next); repository.beginDebit(next,h);
        assertEquals(State.DECLINED,repository.recordDebit(h,new Observation(true,false,0.0,100.0,100.0,false)).state());
        assertThrows(IssuanceRefused.class,() -> repository.reserve(order(player,Source.WORKER_OUTPUT,"STONE",1,DAY.plusDays(1)),BUDGETS));
        assertFalse(repository.beginDebit(next,h).newlyStarted());
        assertThrows(SQLException.class,() -> repository.claimItems(next,PAYLOAD,NOW.plusSeconds(100)));
        assertEquals(2,count("npc_commerce_orders")); assertEquals(2,scalar("SELECT SUM(issued_orders) FROM npc_commerce_days"));
    }

    @Test void workerDayUsedReflectsOnlyThatPlayersHiredJobForThatDay() throws SQLException {
        UUID player=UUID.randomUUID();
        assertFalse(repository.workerDayUsed(player,DAY));
        repository.reserve(order(player,Source.MERCHANT_GOODS,"STONE",1,DAY),BUDGETS);
        assertFalse(repository.workerDayUsed(player,DAY),"buying goods does not use the hired-job slot");
        CommerceOrder worker=order(player,Source.WORKER_OUTPUT,"STONE",4,DAY); repository.reserve(worker,BUDGETS);
        assertTrue(repository.workerDayUsed(player,DAY));
        assertFalse(repository.workerDayUsed(player,DAY.plusDays(1)));
        assertFalse(repository.workerDayUsed(UUID.randomUUID(),DAY));
        repository.cancel(worker);
        assertTrue(repository.workerDayUsed(player,DAY),"a cancelled job still consumed the day");
    }

    @Test void recentIsPlayerScopedBoundedImmutableAndIncludesCollectibleAndTerminalOrders() throws SQLException {
        UUID player=UUID.randomUUID(); List<CommerceOrder> orders=new ArrayList<>();
        for(int i=0;i<10;i++) {
            CommerceOrder base=order(player,Source.MERCHANT_GOODS,"STONE",1,DAY);
            CommerceOrder issued=new CommerceOrder(base.orderId(),base.issuanceNonce(),player,resident,base.source(),base.catalogueId(),
                    base.material(),base.quantity(),base.costCents(),DAY,NOW.plusMillis(i),0);
            orders.add(issued); repository.reserve(issued,BUDGETS);
            if (i<7 || i==8) repository.cancel(issued);
        }
        CommerceOrder collectible=orders.get(9); Handle h=handle(collectible);
        repository.beginDebit(collectible,h); repository.recordDebit(h,DEBIT); repository.beginCredit(h);
        repository.recordCredit(h,CREDIT,collectible.issuedAt()); repository.cancel(orders.get(8));
        Handle declined=handle(orders.get(7)); repository.beginDebit(orders.get(7),declined);
        repository.recordDebit(declined,new Observation(true,false,0.0,100.0,100.0,false));
        repository.reserve(order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY),BUDGETS);
        List<Receipt> recent=repository.recent(player,8);
        assertEquals(8,recent.size());
        assertEquals(orders.reversed().subList(0,8).stream().map(CommerceOrder::orderId).toList(),
                recent.stream().map(r -> r.order().orderId()).toList());
        assertTrue(recent.stream().allMatch(r -> r.order().playerId().equals(player)));
        assertEquals(State.FUNDED,recent.get(0).state()); assertNull(recent.get(0).custodyNonce());
        assertEquals(State.CANCELLED,recent.get(1).state()); assertEquals(State.DECLINED,recent.get(2).state());
        assertEquals(recent.subList(0,3),repository.recent(player,3));
        assertThrows(UnsupportedOperationException.class,() -> recent.add(recent.getFirst()));
        assertEquals(List.of(),repository.recent(player,0)); assertEquals(List.of(),repository.recent(UUID.randomUUID(),8));
        assertThrows(IllegalArgumentException.class,() -> repository.recent(player,-1));
        assertThrows(IllegalArgumentException.class,() -> repository.recent(player,9));
    }

    @Test void paidProofProjectionUsesOnlySuppliedSnapshotAndFailsClosedOnCapacityOrCorruptEvidence() throws SQLException {
        CommerceOrder worker=order(UUID.randomUUID(),Source.WORKER_OUTPUT,"STONE",4,DAY); fund(worker);
        CommerceOrder merchant=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",4,DAY); repository.reserve(merchant,BUDGETS);
        Handle h=handle(merchant); repository.beginDebit(merchant,h); repository.recordDebit(h,DEBIT); repository.beginCredit(h);
        repository.recordCredit(h,CREDIT,NOW.plusSeconds(1));
        repository.reserve(order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",4,DAY),BUDGETS);
        CommerceOrderRepository projection=new CommerceOrderRepository(new Database(),reserve);
        try(Connection c=database.getConnection()) {
            assertThrows(SQLException.class,() -> projection.paidProofs(c,NOW,NOW.plusSeconds(1),8));
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            assertThrows(SQLException.class,() -> projection.paidProofs(c,NOW,NOW.plusSeconds(1),8));
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            var savepoint=c.setSavepoint();
            List<PaidProof> paid=projection.paidProofs(c,NOW,NOW.plusSeconds(1),8);
            assertEquals(List.of(worker.orderId(),merchant.orderId()),paid.stream().map(p -> p.order().orderId()).toList());
            assertEquals(2000,paid.stream().mapToLong(p -> p.order().costCents()).sum());
            assertThrows(UnsupportedOperationException.class,() -> paid.add(paid.getFirst()));
            assertThrows(SQLException.class,() -> projection.paidProofs(c,NOW,NOW.plusSeconds(1),1));
            assertEquals(List.of(merchant.orderId()),projection.paidProofs(c,NOW.plusNanos(1),NOW.plusSeconds(1),8)
                    .stream().map(p -> p.order().orderId()).toList());
            assertThrows(IllegalArgumentException.class,() -> projection.paidProofs(c,NOW,NOW,0));
            assertThrows(IllegalArgumentException.class,() -> projection.paidProofs(c,NOW,NOW,10_001));
            assertThrows(IllegalArgumentException.class,() -> projection.paidProofs(c,NOW.plusSeconds(1),NOW,8));
            CommerceOrder later=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",4,DAY); fund(later);
            try(Connection update=database.getConnection(); var ps=update.prepareStatement(
                    "UPDATE npc_commerce_money_evidence SET after_bits=? WHERE order_id=? AND leg='CREDIT'")) {
                ps.setQueryTimeout(5); ps.setLong(1,Double.doubleToRawLongBits(20.0));
                ps.setBytes(2,com.prosperitysmp.npc.db.Uuids.toBytes(worker.orderId())); assertEquals(1,ps.executeUpdate());
            }
            assertEquals(paid,projection.paidProofs(c,NOW,NOW.plusSeconds(1),8));
            assertFalse(c.getAutoCommit()); assertEquals(Connection.TRANSACTION_REPEATABLE_READ,c.getTransactionIsolation());
            c.rollback(savepoint); c.rollback();
        }
        try(Connection c=database.getConnection()) {
            c.setReadOnly(true); c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); c.setAutoCommit(false);
            try {
                assertThrows(SQLException.class,() -> projection.paidProofs(c,NOW,NOW.plusSeconds(1),8));
                assertEquals(1,projection.paidProofs(c,NOW.plusNanos(1),NOW.plusSeconds(1),8).size());
            } finally { c.rollback(); }
        }
    }

    @Test void bothSourcesRequireExactTwoLegProofAndFundingDoesNotDeliverItems() throws SQLException {
        int claims=0;
        for(Source source:java.util.List.of(Source.WORKER_OUTPUT,Source.MERCHANT_GOODS)) {
            CommerceOrder order=order(UUID.randomUUID(),source,"STONE",4,DAY); repository.reserve(order,BUDGETS); Handle h=handle(order);
            assertTrue(repository.beginDebit(order,h).newlyStarted()); assertFalse(repository.beginDebit(order,h).newlyStarted());
            assertThrows(SQLException.class,() -> repository.beginDebit(order,new Handle(order.orderId(),UUID.randomUUID(),order.playerId(),reserve,h.providerSha256())));
            assertThrows(SQLException.class,() -> repository.recordDebit(new Handle(order.orderId(),run,order.playerId(),reserve,"b".repeat(64)),DEBIT));
            assertThrows(SQLException.class,() -> repository.recordDebit(new Handle(order.orderId(),run,order.playerId(),UUID.randomUUID(),h.providerSha256()),DEBIT));
            assertThrows(SQLException.class,() -> repository.recordCredit(h,CREDIT,NOW));
            assertFalse(repository.beginCredit(h).newlyStarted());
            assertEquals(State.CREDIT_READY,repository.recordDebit(h,DEBIT).state());
            assertTrue(repository.beginCredit(h).newlyStarted()); assertFalse(repository.beginCredit(h).newlyStarted());
            Receipt funded=repository.recordCredit(h,CREDIT,NOW);
            assertEquals(State.FUNDED,funded.state()); assertEquals(funded,repository.recordCredit(h,CREDIT,NOW.plusSeconds(1)));
            assertFalse(repository.beginDebit(order,h).newlyStarted()); assertFalse(repository.beginCredit(h).newlyStarted());
            assertTrue(repository.paidProof(order.playerId(),order.orderId()).isPresent());
            assertTrue(repository.paidProof(UUID.randomUUID(),order.orderId()).isEmpty()); assertNull(funded.custodyNonce());
            assertEquals(claims,count("npc_item_custody"));
            if(source==Source.WORKER_OUTPUT) assertThrows(SQLException.class,() -> repository.claimItems(order,PAYLOAD,NOW.plusSeconds(59)));
            var claim=repository.claimItems(order,PAYLOAD,funded.outputReadyAt());
            assertTrue(claim.newlyReserved()); assertEquals(ItemCustodyRepository.State.RESERVED,claim.custody().state());
            assertEquals(order.orderId(),claim.custody().request().requestNonce());
            assertEquals(State.FUNDED,repository.find(order.playerId(),order.orderId()).orElseThrow().state());
            assertEquals(order.outputDelaySeconds(),funded.outputReadyAt().getEpochSecond()-funded.fundedAt().getEpochSecond());
            assertEquals(++claims,count("npc_item_custody"));
        }
    }

    @Test void reopenedPoolFreezesAllOldMoneyPhasesAndLateProofNeverFunds() throws SQLException {
        CommerceOrder debit=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",4,DAY);
        CommerceOrder ready=order(UUID.randomUUID(),Source.WORKER_OUTPUT,"STONE",4,DAY);
        CommerceOrder credit=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",4,DAY);
        CommerceOrder current=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",4,DAY);
        CommerceOrder reserved=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",4,DAY);
        for(CommerceOrder order:List.of(debit,ready,credit,current,reserved)) repository.reserve(order,BUDGETS);
        for(CommerceOrder order:List.of(debit,ready,credit)) repository.beginDebit(order,handle(order));
        repository.recordDebit(handle(ready),DEBIT); repository.recordDebit(handle(credit),DEBIT); repository.beginCredit(handle(credit));
        UUID nextRun=UUID.randomUUID(); Handle currentHandle=new Handle(current.orderId(),nextRun,current.playerId(),reserve,"a".repeat(64));
        repository.beginDebit(current,currentHandle);
        database.close(); database=new Database(); database.connect(settings); repository=new CommerceOrderRepository(database,reserve);
        assertEquals(0,repository.recoverOtherRuns(nextRun,0)); assertEquals(2,repository.recoverOtherRuns(nextRun,2));
        assertEquals(1,repository.recoverOtherRuns(nextRun,100)); assertEquals(0,repository.recoverOtherRuns(nextRun,100));
        for(CommerceOrder order:List.of(debit,ready,credit)) {
            assertEquals(State.AMBIGUOUS,found(order).state()); assertFalse(repository.beginDebit(order,handle(order)).newlyStarted());
            assertFalse(repository.beginCredit(handle(order)).newlyStarted());
            assertThrows(SQLException.class,() -> repository.claimItems(order,PAYLOAD,NOW.plusSeconds(100)));
        }
        assertEquals(State.AMBIGUOUS,repository.recordCredit(handle(credit),CREDIT,NOW).state());
        assertEquals(State.DEBIT_INTENT,found(current).state()); assertEquals(State.RESERVED,found(reserved).state());
        CommerceOrder held=order(credit.playerId(),Source.MERCHANT_GOODS,"STONE",4,DAY); repository.reserve(held,BUDGETS);
        assertFalse(repository.beginDebit(held,handle(held)).newlyStarted()); assertEquals(0,count("npc_item_custody"));
    }

    @Test void uncertainMoneyAndFailedAcknowledgementKeepPermanentHoldsWithoutRefundOrRetry() throws SQLException {
        CommerceOrder thrown=order(UUID.randomUUID(),Source.WORKER_OUTPUT,"STONE",4,DAY); repository.reserve(thrown,BUDGETS);
        Handle thrownHandle=handle(thrown); repository.beginDebit(thrown,thrownHandle);
        assertEquals(State.AMBIGUOUS,repository.recordDebit(thrownHandle,new Observation(false,false,null,100.0,100.0,true)).state());
        CommerceOrder partial=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",4,DAY); repository.reserve(partial,BUDGETS);
        repository.beginDebit(partial,handle(partial)); repository.recordDebit(handle(partial),DEBIT); repository.beginCredit(handle(partial));
        assertEquals(State.AMBIGUOUS,repository.recordCredit(handle(partial),new Observation(true,true,10.0,20.0,25.0,false),NOW).state());
        CommerceOrder rollback=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",4,DAY); repository.reserve(rollback,BUDGETS);
        repository.beginDebit(rollback,handle(rollback)); repository.recordDebit(handle(rollback),DEBIT); repository.beginCredit(handle(rollback));
        execute("CREATE TRIGGER commerce_fixture_failure BEFORE UPDATE ON npc_commerce_orders FOR EACH ROW "
                +"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture acknowledgement failure'");
        assertThrows(SQLException.class,() -> repository.recordCredit(handle(rollback),CREDIT,NOW));
        assertEquals(State.CREDIT_INTENT,found(rollback).state()); assertFalse(repository.beginCredit(handle(rollback)).newlyStarted());
        assertEquals(4,count("npc_commerce_money_evidence"));
        execute("DROP TRIGGER commerce_fixture_failure");
        assertEquals(1,repository.recoverOtherRuns(UUID.randomUUID(),100)); assertEquals(State.AMBIGUOUS,found(rollback).state());
        assertEquals(State.AMBIGUOUS,repository.recordCredit(handle(rollback),CREDIT,NOW).state());
        assertTrue(repository.paidProof(rollback.playerId(),rollback.orderId()).isEmpty()); assertEquals(0,count("npc_item_custody"));
    }

    @Test void fundedCustodyClaimIsAtomicConcurrentByteBoundAndSurvivesExpiryWithoutRenewal() throws Exception {
        CommerceOrder order=order(UUID.randomUUID(),Source.WORKER_OUTPUT,"STONE",4,DAY); Receipt funded=fund(order);
        Instant ready=funded.outputReadyAt();
        execute("CREATE TRIGGER commerce_fixture_failure BEFORE UPDATE ON npc_commerce_orders FOR EACH ROW "
                +"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture custody link failure'");
        assertThrows(SQLException.class,() -> repository.claimItems(order,PAYLOAD,ready));
        assertEquals(0,count("npc_item_custody")); assertNull(found(order).custodyNonce());
        execute("DROP TRIGGER commerce_fixture_failure");
        List<Callable<Boolean>> calls=new ArrayList<>();
        for(int i=0;i<8;i++) calls.add(() -> new CommerceOrderRepository(database,reserve).claimItems(order,PAYLOAD,ready).newlyReserved());
        assertEquals(1,race(calls).stream().filter(Boolean::booleanValue).count()); assertEquals(1,count("npc_item_custody"));
        var old=repository.claimItems(order,PAYLOAD,ready.plusSeconds(1)); assertFalse(old.newlyReserved());
        assertArrayEquals(PAYLOAD.bytes(),old.custody().request().payload().bytes());
        assertThrows(SQLException.class,() -> repository.claimItems(order,new CustodyBytes(new byte[]{0,1,-1,43}),ready));
        ItemCustody request=old.custody().request(); ItemCustodyRepository custody=new ItemCustodyRepository(database);
        Instant muchLater=request.expiresAt().plusSeconds(86_400);
        var retained=repository.claimItems(order,PAYLOAD,muchLater);
        assertFalse(retained.newlyReserved()); assertEquals(old.custody(),retained.custody());
        assertEquals(ItemCustodyRepository.State.RESERVED,custody.find(order.playerId(),order.orderId(),muchLater).orElseThrow().state());
        UUID standalonePlayer=UUID.randomUUID();
        ItemCustody standalone=new ItemCustody(UUID.randomUUID(),standalonePlayer,UUID.randomUUID(),Source.MERCHANT_GOODS,1,
                PAYLOAD,NOW,NOW.plusSeconds(900));
        custody.reserve(standalone,NOW);
        assertEquals(1,custody.expireReservations(muchLater,1));
        assertEquals(ItemCustodyRepository.State.EXPIRED,custody.find(standalonePlayer,standalone.requestNonce(),muchLater).orElseThrow().state());
        assertEquals(0,custody.expireReservations(muchLater,100));
        database.close(); database=new Database(); database.connect(settings);
        repository=new CommerceOrderRepository(database,reserve); custody=new ItemCustodyRepository(database);
        assertEquals(request,repository.claimItems(order,PAYLOAD,muchLater).custody().request());
        UUID deliveryRun=UUID.randomUUID(); assertTrue(custody.beginDelivery(request,deliveryRun,muchLater).newlyStarted());
        assertFalse(custody.beginDelivery(request,deliveryRun,muchLater).newlyStarted());
        UUID nextRun=UUID.randomUUID(); assertEquals(1,custody.recoverOtherRuns(nextRun,100));
        assertEquals(ItemCustodyRepository.State.AMBIGUOUS,repository.claimItems(order,PAYLOAD,muchLater).custody().state());
        assertFalse(custody.beginDelivery(request,nextRun,muchLater).newlyStarted());
        assertEquals(ItemCustodyRepository.State.AMBIGUOUS,custody.recordDelivery(request,deliveryRun,confirmed(request)).state());
        assertEquals(2,count("npc_item_custody")); assertEquals(order.orderId(),found(order).custodyNonce());
        CommerceOrder orphan=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",4,DAY); fund(orphan);
        custody.reserve(new ItemCustody(orphan.orderId(),orphan.playerId(),orphan.orderId(),orphan.source(),4,PAYLOAD,NOW,NOW.plusSeconds(900)),NOW);
        assertThrows(SQLException.class,() -> repository.claimItems(orphan,PAYLOAD,NOW)); assertNull(found(orphan).custodyNonce());
    }

    @Test void commerceClaimsNeverExpireOrCountItemRepairReturns() throws SQLException {
        // Four item-repair return stacks wait past their nominal 15-minute expiry while the player claims eight paid orders.
        UUID player=UUID.randomUUID(); CommerceOrder first=order(player,Source.MERCHANT_GOODS,"STONE",1,DAY); fund(first);
        repository.claimItems(first,PAYLOAD,NOW); // creates the custody player guard
        List<ItemCustody> returns=new ArrayList<>();
        for(int i=0;i<ItemCustodyRepository.MAX_RETURN_STACKS;i++) returns.add(new ItemCustody(UUID.randomUUID(),player,UUID.randomUUID(),
                Source.REPAIR_RETURN,64,new CustodyBytes(new byte[]{9,(byte)i}),NOW,NOW.plusSeconds(900)));
        try(Connection c=database.getConnection()) {
            c.setAutoCommit(false);
            try { for(ItemCustody stack:returns) ItemCustodyRepository.reserveReturn(c,stack); c.commit(); }
            catch(SQLException|RuntimeException failure) { c.rollback(); throw failure; }
        }
        Instant late=NOW.plusSeconds(3_600);
        for(int i=1;i<ItemCustodyRepository.MAX_OPEN_REQUESTS_PER_PLAYER;i++) {
            CommerceOrder next=order(player,Source.MERCHANT_GOODS,"STONE",1,DAY.plusDays(i)); fund(next);
            int claim=i;
            assertDoesNotThrow(() -> repository.claimItems(next,PAYLOAD,late),"paid claim "+claim+" fits beside 4 waiting returns");
        }
        assertEquals(ItemCustodyRepository.MAX_OPEN_REQUESTS_PER_PLAYER,
                scalar("SELECT COUNT(*) FROM npc_item_custody WHERE source<>'REPAIR_RETURN' AND state='RESERVED'"),"all eight claims landed");
        ItemCustodyRepository custody=new ItemCustodyRepository(database);
        for(ItemCustody stack:returns)
            assertEquals(ItemCustodyRepository.State.RESERVED,custody.find(player,stack.requestNonce(),late).orElseThrow().state(),
                    "a commerce claim never expires items an item repair took");
        // Replaying a claim takes the same path and still leaves the returns alone.
        assertFalse(repository.claimItems(first,PAYLOAD,late).newlyReserved());
        assertEquals(0,scalar("SELECT COUNT(*) FROM npc_item_custody WHERE source='REPAIR_RETURN' AND state<>'RESERVED'"));
    }

    @Test void concurrentBacklogAcrossDaysAllowsEightReplaysAndDeliveryRestoresExactlyOneSlot() throws Exception {
        UUID player=UUID.randomUUID(); List<Callable<Boolean>> calls=new ArrayList<>();
        for(int i=0;i<12;i++) {
            CommerceOrder order=order(player,Source.MERCHANT_GOODS,"STONE",1,DAY.plusDays(i));
            calls.add(() -> {
                try { return new CommerceOrderRepository(database,reserve).reserve(order,BUDGETS).newlyReserved(); }
                catch(IssuanceRefused refused) { assertEquals(0,refused.getSuppressed().length); return false; }
            });
        }
        assertEquals(8,race(calls).stream().filter(Boolean::booleanValue).count());
        assertEquals(8,count("npc_commerce_orders")); assertEquals(8,scalar("SELECT SUM(issued_orders) FROM npc_commerce_days"));
        List<CommerceOrder> accepted=repository.recent(player,8).stream().map(Receipt::order).toList();
        calls.clear();
        for(int i=0;i<8;i++) calls.add(() -> repository.reserve(accepted.getFirst(),BUDGETS).newlyReserved());
        assertEquals(0,race(calls).stream().filter(Boolean::booleanValue).count());
        CommerceOrder refused=order(player,Source.MERCHANT_GOODS,"STONE",1,DAY.plusDays(20));
        assertThrows(IssuanceRefused.class,() -> repository.reserve(refused,BUDGETS));
        assertTrue(repository.find(player,refused.orderId()).isEmpty());
        CommerceOrder paid=accepted.getFirst(); fund(paid);
        var request=repository.claimItems(paid,PAYLOAD,NOW).custody().request();
        CommerceOrder uncertain=accepted.getLast(); repository.beginDebit(uncertain,handle(uncertain));
        assertEquals(State.AMBIGUOUS,repository.cancel(uncertain).state());
        assertThrows(IssuanceRefused.class,() -> repository.reserve(refused,BUDGETS));
        ItemCustodyRepository custody=new ItemCustodyRepository(database);
        assertTrue(custody.beginDelivery(request,run,NOW).newlyStarted());
        assertThrows(IssuanceRefused.class,() -> repository.reserve(refused,BUDGETS));
        assertEquals(ItemCustodyRepository.State.DELIVERED,custody.recordDelivery(request,run,confirmed(request)).state());
        assertTrue(repository.outstanding(player,8).isEmpty());
        assertTrue(repository.reserve(refused,BUDGETS).newlyReserved());
        assertThrows(IssuanceRefused.class,() -> repository.reserve(order(player,Source.MERCHANT_GOODS,"STONE",1,DAY.plusDays(21)),BUDGETS));
        assertEquals(9,count("npc_commerce_orders")); assertEquals(9,scalar("SELECT SUM(issued_orders) FROM npc_commerce_days"));
    }

    @Test void outstandingCannotBeBuriedByHistoryAndIncludesExpiredAndHeldPaidCustody() throws SQLException {
        UUID player=UUID.randomUUID(); CommerceOrder oldest=order(player,Source.MERCHANT_GOODS,"STONE",1,DAY); fund(oldest);
        CommerceOrder workerBase=order(player,Source.WORKER_OUTPUT,"STONE",1,DAY);
        CommerceOrder worker=new CommerceOrder(workerBase.orderId(),workerBase.issuanceNonce(),player,resident,workerBase.source(),
                workerBase.catalogueId(),workerBase.material(),1,1000,DAY,NOW.plusMillis(1),60);
        repository.reserve(worker,BUDGETS); Handle h=handle(worker);
        repository.beginDebit(worker,h); repository.recordDebit(h,DEBIT); repository.beginCredit(h); repository.recordCredit(h,CREDIT,NOW.plusSeconds(1));
        ItemCustodyRepository custody=new ItemCustodyRepository(database);
        ItemCustody expired=repository.claimItems(oldest,PAYLOAD,NOW).custody().request();
        try(Connection c=database.getConnection(); var ps=c.prepareStatement("UPDATE npc_item_custody SET state='EXPIRED' WHERE request_nonce=?")) {
            ps.setQueryTimeout(5); ps.setBytes(1,com.prosperitysmp.npc.db.Uuids.toBytes(expired.requestNonce())); assertEquals(1,ps.executeUpdate());
        }
        ItemCustody held=repository.claimItems(worker,PAYLOAD,NOW.plusSeconds(61)).custody().request();
        custody.beginDelivery(held,run,held.reservedAt()); custody.markAmbiguous(held,run,ItemCustodyRepository.Uncertainty.CANCELLED_AFTER_INTENT);
        for(int i=0;i<10;i++) {
            CommerceOrder base=order(player,Source.MERCHANT_GOODS,"STONE",1,DAY);
            CommerceOrder historical=new CommerceOrder(base.orderId(),base.issuanceNonce(),player,resident,base.source(),base.catalogueId(),
                    base.material(),1,1000,DAY,NOW.plusMillis(10+i),0);
            repository.reserve(historical,BUDGETS);
            if(i%2==0) repository.cancel(historical);
            else {
                Handle declined=handle(historical); repository.beginDebit(historical,declined);
                repository.recordDebit(declined,new Observation(true,false,0.0,100.0,100.0,false));
            }
        }
        fund(order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY));
        assertFalse(repository.recent(player,8).stream().anyMatch(r -> r.state()==State.FUNDED));
        List<Receipt> outstanding=repository.outstanding(player,8);
        assertEquals(List.of(oldest.orderId(),worker.orderId()),outstanding.stream().map(r -> r.order().orderId()).toList());
        assertEquals(outstanding.subList(0,1),repository.outstanding(player,1));
        assertThrows(UnsupportedOperationException.class,() -> outstanding.add(outstanding.getFirst()));
        assertEquals(List.of(),repository.outstanding(player,0)); assertEquals(List.of(),repository.outstanding(UUID.randomUUID(),8));
        assertThrows(IllegalArgumentException.class,() -> repository.outstanding(player,9));
        assertThrows(IllegalArgumentException.class,() -> repository.outstanding(player,-1));
        assertEquals(ItemCustodyRepository.State.EXPIRED,repository.claimItems(oldest,PAYLOAD,NOW.plusSeconds(1000)).custody().state());
        assertEquals(ItemCustodyRepository.State.AMBIGUOUS,repository.claimItems(worker,PAYLOAD,NOW.plusSeconds(1000)).custody().state());
    }

    @Test void typedMaterialBudgetRefusalRollsBackNewOrderAndGlobalCounters() throws SQLException {
        Budgets budget=new Budgets(10,100,1,10_000);
        CommerceOrder first=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(first,budget);
        CommerceOrder refused=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY);
        IssuanceRefused failure=assertThrows(IssuanceRefused.class,() -> repository.reserve(refused,budget));
        assertEquals(0,failure.getSuppressed().length); assertTrue(repository.find(refused.playerId(),refused.orderId()).isEmpty());
        assertEquals(1,count("npc_commerce_orders")); assertEquals(1,scalar("SELECT issued_orders FROM npc_commerce_days"));
        assertEquals(1,scalar("SELECT issued_units FROM npc_commerce_days")); assertEquals(1000,scalar("SELECT issued_cost_cents FROM npc_commerce_days"));
        assertEquals(1,scalar("SELECT issued_units FROM npc_commerce_material_days"));
        SQLException conflict=assertThrows(SQLException.class,() -> repository.reserve(first,new Budgets(11,100,1,10_000)));
        assertFalse(conflict instanceof IssuanceRefused);
    }

    @Test void cleanupIsBoundedStrictlyOlderAndExcludesAllEvidenceAndCustodyWithoutReturningBudgets() throws SQLException {
        CommerceOrder worker=order(UUID.randomUUID(),Source.WORKER_OUTPUT,"STONE",1,DAY); repository.reserve(worker,BUDGETS);
        CommerceOrder untouched=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(untouched,BUDGETS);
        CommerceOrder money=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(money,BUDGETS);
        Handle h=handle(money); repository.beginDebit(money,h);
        CommerceOrder paid=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); fund(paid);
        CommerceOrder orphan=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(orphan,BUDGETS);
        new ItemCustodyRepository(database).reserve(new ItemCustody(UUID.randomUUID(),orphan.playerId(),orphan.orderId(),orphan.source(),
                1,PAYLOAD,NOW,NOW.plusSeconds(60)),NOW);
        CommerceOrder evidence=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(evidence,BUDGETS);
        try(Connection c=database.getConnection();var ps=c.prepareStatement("INSERT INTO npc_commerce_money_evidence"
                +"(order_id,leg,response_present,response_success,threw) VALUES(?,'DEBIT',0,0,1)")) {
            ps.setBytes(1,com.prosperitysmp.npc.db.Uuids.toBytes(evidence.orderId())); assertEquals(1,ps.executeUpdate());
        }
        assertThrows(IllegalArgumentException.class,() -> repository.cancelUnstartedBefore(NOW,-1));
        assertEquals(0,repository.cancelUnstartedBefore(NOW,100));
        assertEquals(0,repository.cancelUnstartedBefore(NOW.plusMillis(1),0));
        assertEquals(1,repository.cancelUnstartedBefore(NOW.plusMillis(1),1));
        assertEquals(1,repository.cancelUnstartedBefore(NOW.plusMillis(1),Integer.MAX_VALUE));
        assertEquals(0,repository.cancelUnstartedBefore(NOW.plusSeconds(1000),100));
        assertEquals(State.CANCELLED,found(worker).state()); assertEquals(State.CANCELLED,found(untouched).state());
        assertEquals(State.DEBIT_INTENT,found(money).state()); assertEquals(State.FUNDED,found(paid).state());
        assertEquals(State.RESERVED,found(orphan).state()); assertEquals(State.RESERVED,found(evidence).state());
        assertEquals(6,scalar("SELECT issued_orders FROM npc_commerce_days"));
        assertEquals(6,scalar("SELECT issued_units FROM npc_commerce_material_days"));
        assertEquals(6000,scalar("SELECT issued_cost_cents FROM npc_commerce_days"));
        assertFalse(repository.reserve(worker,BUDGETS).newlyReserved());
        assertThrows(IssuanceRefused.class,() -> repository.reserve(order(worker.playerId(),Source.WORKER_OUTPUT,"STONE",1,DAY),BUDGETS));
    }

    @Test void cleanupAndDebitIntentSerializeWithoutCancellingAStartedPayment() throws Exception {
        CommerceOrder order=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(order,BUDGETS);
        Handle handle=handle(order);
        List<Object> results=race(List.of(() -> repository.cancelUnstartedBefore(NOW.plusMillis(1),1),
                () -> repository.beginDebit(order,handle)));
        Receipt receipt=found(order); Start start=(Start)results.get(1);
        if(start.newlyStarted()) { assertEquals(0,results.get(0)); assertEquals(State.DEBIT_INTENT,receipt.state()); }
        else { assertEquals(1,results.get(0)); assertEquals(State.CANCELLED,receipt.state()); }
        assertEquals(1,scalar("SELECT issued_orders FROM npc_commerce_days"));
        assertEquals(0,count("npc_commerce_money_evidence"));
    }

    @Test void exactPaidResolutionIsConcurrentReplayDurableAndPreservesFundedGoodsAndOriginalDelay() throws Exception {
        CommerceOrder order=order(UUID.randomUUID(),Source.WORKER_OUTPUT,"STONE",4,DAY); repository.reserve(order,BUDGETS);
        Handle handle=handle(order); repository.beginDebit(order,handle); repository.recordDebit(handle,DEBIT); repository.beginCredit(handle);
        repository.recoverOtherRuns(UUID.randomUUID(),100); repository.recordCredit(handle,CREDIT,NOW.plusSeconds(30));
        PaymentResolution resolution=resolution(order,PaymentDecision.VERIFIED_PAID);
        List<Receipt> receipts=race(List.of(() -> repository.reconcilePayment(order,handle,resolution),
                () -> repository.reconcilePayment(order,handle,resolution)));
        assertEquals(receipts.getFirst(),receipts.getLast()); Receipt funded=receipts.getFirst();
        assertEquals(State.FUNDED,funded.state()); assertEquals(resolution.resolvedAt(),funded.fundedAt());
        assertEquals(resolution.resolvedAt().plusSeconds(60),funded.outputReadyAt());
        assertEquals(1,count("npc_commerce_payment_resolutions")); assertEquals(2,count("npc_commerce_money_evidence"));
        assertEquals(0,count("npc_item_custody")); assertTrue(repository.paidProof(order.playerId(),order.orderId()).isPresent());
        database.close(); database=new Database(); database.connect(settings); repository=new CommerceOrderRepository(database,reserve);
        assertEquals(funded,repository.reconcilePayment(order,handle,resolution));
        assertThrows(SQLException.class,() -> repository.claimItems(order,PAYLOAD,resolution.resolvedAt()));
        repository.claimItems(order,PAYLOAD,funded.outputReadyAt());
        Receipt linked=repository.reconcilePayment(order,handle,resolution); assertEquals(order.orderId(),linked.custodyNonce());
        assertEquals(State.FUNDED,repository.cancel(order).state());
        assertEquals(0,repository.cancelUnstartedBefore(NOW.plusSeconds(10000),100));
        assertEquals(1,repository.outstanding(order.playerId(),8).size());
        PaymentResolution conflict=new PaymentResolution(resolution.resolutionId(),"another-actor",resolution.caseRef(),resolution.notes(),
                resolution.requestSha256(),resolution.decision(),resolution.caseEvidence(),null,null,null,resolution.resolvedAt());
        assertThrows(SQLException.class,() -> repository.reconcilePayment(order,handle,conflict));
        assertEquals(1,count("npc_commerce_payment_resolutions"));
    }

    @Test void noMovementResolutionNeedsExistingExactProofAndNeverClearsUnknownOrCreditedMoney() throws SQLException {
        CommerceOrder declined=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(declined,BUDGETS);
        Handle h=handle(declined); repository.beginDebit(declined,h); repository.cancel(declined);
        PaymentResolution resolution=resolution(declined,PaymentDecision.VERIFIED_NO_MOVEMENT);
        assertThrows(SQLException.class,() -> repository.reconcilePayment(declined,h,resolution));
        repository.recordDebit(h,new Observation(true,false,0d,100d,100d,false));
        assertEquals(State.DECLINED,repository.reconcilePayment(declined,h,resolution).state());
        assertFalse(repository.beginDebit(declined,h).newlyStarted());
        assertThrows(SQLException.class,() -> repository.claimItems(declined,PAYLOAD,NOW.plusSeconds(1000)));
        CommerceOrder credit=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(credit,BUDGETS);
        Handle ch=handle(credit); repository.beginDebit(credit,ch); repository.recordDebit(ch,DEBIT); repository.beginCredit(ch); repository.cancel(credit);
        assertThrows(SQLException.class,() -> repository.reconcilePayment(credit,ch,resolution(credit,PaymentDecision.VERIFIED_NO_MOVEMENT)));
        assertThrows(SQLException.class,() -> repository.reconcilePayment(credit,ch,refund(credit,90d,100d)));
        assertEquals(State.AMBIGUOUS,found(credit).state()); assertEquals(1,count("npc_commerce_payment_resolutions"));
    }

    @Test void recoveryBindsRequestAccountsProviderOriginalRunAndRollsBackResolutionWithReceipt() throws SQLException {
        CommerceOrder order=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(order,BUDGETS);
        Handle h=handle(order); repository.beginDebit(order,h); repository.recordDebit(h,DEBIT); repository.beginCredit(h);
        repository.cancel(order); repository.recordCredit(h,CREDIT,NOW);
        PaymentResolution resolution=resolution(order,PaymentDecision.VERIFIED_PAID);
        for(Handle wrong:List.of(new Handle(h.orderId(),UUID.randomUUID(),h.payerId(),h.reserveId(),h.providerSha256()),
                new Handle(h.orderId(),h.runId(),UUID.randomUUID(),h.reserveId(),h.providerSha256()),
                new Handle(h.orderId(),h.runId(),h.payerId(),UUID.randomUUID(),h.providerSha256()),
                new Handle(h.orderId(),h.runId(),h.payerId(),h.reserveId(),"f".repeat(64))))
            assertThrows(SQLException.class,() -> repository.reconcilePayment(order,wrong,resolution));
        assertThrows(SQLException.class,() -> new CommerceOrderRepository(database,UUID.randomUUID()).reconcilePayment(order,h,resolution));
        PaymentResolution wrongHash=new PaymentResolution(UUID.randomUUID(),"operator","case-50","Reviewed immutable evidence", "f".repeat(64),
                PaymentDecision.VERIFIED_PAID,PAYLOAD,null,null,null,resolution.resolvedAt());
        assertThrows(IllegalArgumentException.class,() -> repository.reconcilePayment(order,h,wrongHash));
        execute("CREATE TRIGGER commerce_fixture_failure BEFORE UPDATE ON npc_commerce_orders FOR EACH ROW "
                +"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture rollback'");
        assertThrows(SQLException.class,() -> repository.reconcilePayment(order,h,resolution));
        assertEquals(0,count("npc_commerce_payment_resolutions")); assertEquals(State.AMBIGUOUS,found(order).state());
        execute("DROP TRIGGER commerce_fixture_failure");
        assertEquals(State.FUNDED,repository.reconcilePayment(order,h,resolution).state());
    }

    @Test void recordedExternalRefundRequiresExactFullDebitRestorationAndIsNotADeclineOrPaidGrant() throws SQLException {
        CommerceOrder order=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(order,BUDGETS);
        Handle h=handle(order); repository.beginDebit(order,h);
        repository.recordDebit(h,new Observation(true,true,10d,5d,-5d,false));
        assertEquals(State.AMBIGUOUS,found(order).state());
        assertThrows(SQLException.class,() -> repository.reconcilePayment(order,h,refund(order,-5d,6d)));
        PaymentResolution refund=refund(order,-5d,5d);
        Receipt receipt=repository.reconcilePayment(order,h,refund); assertEquals(State.COMPENSATED,receipt.state());
        assertEquals(receipt,repository.reconcilePayment(order,h,refund));
        assertFalse(repository.beginDebit(order,h).newlyStarted()); assertTrue(repository.paidProof(order.playerId(),order.orderId()).isEmpty());
        assertThrows(SQLException.class,() -> repository.claimItems(order,PAYLOAD,NOW.plusSeconds(1000)));
        assertEquals(1,count("npc_commerce_money_evidence")); assertEquals(1,count("npc_commerce_payment_resolutions"));
        assertEquals(1,scalar("SELECT issued_orders FROM npc_commerce_days"));
        assertEquals(0,count("npc_item_custody"));
        CommerceOrder unknown=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(unknown,BUDGETS);
        Handle uh=handle(unknown); repository.beginDebit(unknown,uh);
        repository.recordDebit(uh,new Observation(false,false,null,100d,90d,true));
        assertThrows(SQLException.class,() -> repository.reconcilePayment(unknown,uh,refund(unknown,90d,100d)));
        assertEquals(State.AMBIGUOUS,found(unknown).state());
    }

    @Test void resolutionMigrationCanRepeatWithoutRemovingItsAuditOrWeakeningTerminalConstraints() throws Exception {
        CommerceOrder order=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(order,BUDGETS);
        Handle h=handle(order); repository.beginDebit(order,h); repository.recordDebit(h,DEBIT); repository.cancel(order);
        PaymentResolution refund=refund(order,90d,100d); repository.reconcilePayment(order,h,refund);
        try(InputStream input=getClass().getResourceAsStream("/db/migration/V25__commerce_resolution.sql")) {
            assertNotNull(input);
            for(String sql:MigrationRunner.splitStatements(new String(input.readAllBytes(),StandardCharsets.UTF_8))) execute(sql);
        }
        assertEquals(State.COMPENSATED,repository.reconcilePayment(order,h,refund).state());
        assertThrows(SQLException.class,() -> execute("UPDATE npc_commerce_orders SET credit_started=1 WHERE state='COMPENSATED'"));
        assertEquals(1,count("npc_commerce_payment_resolutions"));
    }

    // ---- Refusals before the money call, fractional refunds, refused-credit refunds and legacy refusals.

    private static Observation refused(com.prosperitysmp.npc.economy.TransferProof.Refusal reason) {
        return Observation.refused(reason, new IllegalStateException("private"), null, null);
    }
    private Receipt heldAfterRefusedCredit(CommerceOrder order) throws SQLException {
        repository.reserve(order,BUDGETS); Handle h=handle(order); repository.beginDebit(order,h); repository.recordDebit(h,DEBIT);
        repository.beginCredit(h);
        return repository.recordCredit(h,refused(com.prosperitysmp.npc.economy.TransferProof.Refusal.LEASE_REFUSED),NOW);
    }

    @Test void refusedDebitIsADeclineThatStillUsesTheDaysSlot() throws SQLException {
        CommerceOrder order=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(order,BUDGETS);
        Handle h=handle(order); repository.beginDebit(order,h);
        Observation evidence=refused(com.prosperitysmp.npc.economy.TransferProof.Refusal.INSUFFICIENT_FUNDS);
        Receipt declined=repository.recordDebit(h,evidence);
        assertEquals(State.DECLINED,declined.state()); assertEquals("DEBIT_REFUSED_BEFORE_CALL",declined.outcome());
        assertEquals(declined,repository.recordDebit(h,evidence));
        assertThrows(SQLException.class,() -> repository.recordDebit(h,refused(com.prosperitysmp.npc.economy.TransferProof.Refusal.LEASE_REFUSED)),
                "an acknowledgement differing only in the refusal conflicts");
        assertEquals(1,scalar("SELECT issued_orders FROM npc_commerce_days"),"the slot stays used");
        assertEquals(1,scalar("SELECT COUNT(*) FROM npc_commerce_money_evidence WHERE refused_before_call='INSUFFICIENT_FUNDS'"
                +" AND refusal_cause='java.lang.IllegalStateException'"));
    }

    @Test void aFractionalDebitCanBeRefundedExactly() throws SQLException {
        CommerceOrder order=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(order,BUDGETS);
        Handle h=handle(order); repository.beginDebit(order,h);
        repository.recordDebit(h,new Observation(true,true,10.0,1234.5678901,1224.5678901,false));
        repository.cancel(order);
        assertEquals(State.AMBIGUOUS,found(order).state());
        assertThrows(SQLException.class,() -> repository.reconcilePayment(order,h,refund(order,1224.5678901,1234.5778901)));
        assertEquals(State.COMPENSATED,repository.reconcilePayment(order,h,refund(order,1224.5678901,1234.5678901)).state());
    }

    @Test void aRefusedCreditIsRefundedAndStaysCompensated() throws SQLException {
        CommerceOrder order=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY);
        Receipt held=heldAfterRefusedCredit(order);
        assertEquals(State.AMBIGUOUS,held.state()); assertEquals("CREDIT_REFUSED_BEFORE_CALL",held.outcome());
        Handle h=handle(order);
        PaymentResolution refund=refund(order,90d,100d);
        // A dry run checks and writes everything, then rolls it back.
        assertEquals(State.COMPENSATED,repository.reconcilePayment(order,h,refund,false,false).state());
        assertEquals(State.AMBIGUOUS,found(order).state()); assertEquals(0,count("npc_commerce_payment_resolutions"));
        Receipt done=repository.reconcilePayment(order,h,refund);
        assertEquals(State.COMPENSATED,done.state()); assertTrue(done.creditStarted());
        assertEquals(done,repository.reconcilePayment(order,h,refund));
        assertEquals(1,scalar("SELECT credit_started FROM npc_commerce_orders WHERE state='COMPENSATED'"));
        assertTrue(repository.paidProof(order.playerId(),order.orderId()).isEmpty());
    }

    @Test void aCreditThatMayHaveBeenCalledIsNeverRefundedThisWay() throws SQLException {
        CommerceOrder order=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(order,BUDGETS);
        Handle h=handle(order); repository.beginDebit(order,h); repository.recordDebit(h,DEBIT); repository.beginCredit(h);
        repository.recordCredit(h,new Observation(false,false,null,20.0,20.0,true),NOW);
        assertThrows(SQLException.class,() -> repository.reconcilePayment(order,h,refund(order,90d,100d)));
        assertThrows(SQLException.class,() -> repository.reconcilePayment(order,h,refund(order,90d,100d),true,true),
                "whole-cent credit: the old gate would have let the call run");
        assertEquals(State.AMBIGUOUS,found(order).state());
    }

    @Test void legacyRefusalNeedsProvenanceAndTheOldGatesShape() throws SQLException {
        CommerceOrder order=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(order,BUDGETS);
        Handle h=handle(order); repository.beginDebit(order,h);
        repository.recordDebit(h,new Observation(false,false,null,100.001,100.001,true));
        PaymentResolution legacy=resolution(order,PaymentDecision.VERIFIED_LEGACY_REFUSAL);
        assertThrows(SQLException.class,() -> repository.reconcilePayment(order,h,legacy),"no provenance");
        Receipt declined=repository.reconcilePayment(order,h,legacy,true,true);
        assertEquals(State.DECLINED,declined.state()); assertEquals("VERIFIED_LEGACY_REFUSAL",declined.outcome());
        assertEquals(declined,repository.reconcilePayment(order,h,legacy,true,true));
        assertEquals(1,scalar("SELECT COUNT(*) FROM npc_commerce_payment_resolutions WHERE decision='VERIFIED_LEGACY_REFUSAL'"));

        CommerceOrder passed=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(passed,BUDGETS);
        Handle ph=handle(passed); repository.beginDebit(passed,ph);
        repository.recordDebit(ph,new Observation(false,false,null,100.0,100.0,true));
        assertThrows(SQLException.class,() -> repository.reconcilePayment(passed,ph,resolution(passed,PaymentDecision.VERIFIED_LEGACY_REFUSAL),true,true),
                "the old gate would have passed");
        CommerceOrder credited=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY);
        heldAfterRefusedCredit(credited);
        assertThrows(SQLException.class,() -> repository.reconcilePayment(credited,handle(credited),
                resolution(credited,PaymentDecision.VERIFIED_LEGACY_REFUSAL),true,true),"the credit has started");
    }

    @Test void aDryRunLeavesNothingBehindNotEvenTheCustodyGuard() throws SQLException {
        // A player whose first order is held has no custody guard row yet. A dry run must not leave one behind.
        CommerceOrder order=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY);
        heldAfterRefusedCredit(order);
        assertEquals(0,count("npc_item_custody_players"));
        PaymentResolution refund=refund(order,90d,100d);
        assertEquals(State.COMPENSATED,repository.reconcilePayment(order,handle(order),refund,false,false).state());
        assertEquals(0,count("npc_item_custody_players"),"a dry run writes nothing");
        assertEquals(State.AMBIGUOUS,found(order).state());
        // A dry run that fails its checks writes nothing either.
        assertThrows(SQLException.class,() -> repository.reconcilePayment(order,handle(order),refund(order,90d,100.01),false,false));
        assertEquals(0,count("npc_item_custody_players"));
        // Applied, the guard row commits together with the resolution.
        assertEquals(State.COMPENSATED,repository.reconcilePayment(order,handle(order),refund).state());
        assertEquals(1,count("npc_item_custody_players"));
        assertEquals(State.COMPENSATED,repository.reconcilePayment(order,handle(order),refund).state(),"exact replay");
    }

    @Test void anAppliedLegacyDecisionIsFoundAgainForItsReplay() throws Exception {
        // The tool reads the order's own creation time, so the same evidence replays after the order left the held list.
        CommerceOrder order=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(order,BUDGETS);
        Handle h=handle(order); repository.beginDebit(order,h);
        repository.recordDebit(h,new Observation(false,false,null,100.001,100.001,true));
        String created=com.prosperitysmp.npc.commerce.CommerceRecoveryTool.legacyRowTime(repository,order.playerId(),order.orderId());
        assertEquals(created,repository.held(100).getFirst().createdAt());
        PaymentResolution legacy=resolution(order,PaymentDecision.VERIFIED_LEGACY_REFUSAL);
        Receipt declined=repository.reconcilePayment(order,h,legacy,true,true);
        assertTrue(repository.held(100).isEmpty());
        assertEquals(created,com.prosperitysmp.npc.commerce.CommerceRecoveryTool.legacyRowTime(repository,order.playerId(),order.orderId()));
        assertEquals(declined,repository.reconcilePayment(order,h,legacy,true,true));
        assertThrows(IllegalStateException.class,
                () -> com.prosperitysmp.npc.commerce.CommerceRecoveryTool.legacyRowTime(repository,order.playerId(),UUID.randomUUID()));
    }

    @Test void heldOrdersAreListedAndSortedWithoutChangingThem() throws SQLException {
        CommerceOrder refusedCredit=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); heldAfterRefusedCredit(refusedCredit);
        CommerceOrder legacy=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(legacy,BUDGETS);
        repository.beginDebit(legacy,handle(legacy)); repository.recordDebit(handle(legacy),new Observation(false,false,null,100.001,100.001,true));
        CommerceOrder unknown=order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY); repository.reserve(unknown,BUDGETS);
        repository.beginDebit(unknown,handle(unknown)); repository.recordDebit(handle(unknown),new Observation(false,false,null,null,null,true));
        fund(order(UUID.randomUUID(),Source.MERCHANT_GOODS,"STONE",1,DAY));
        int evidence=count("npc_commerce_money_evidence");
        var held=repository.held(100);
        assertEquals(repository.heldCount(),held.size()); assertEquals(3,held.size());
        assertEquals(evidence,count("npc_commerce_money_evidence"));
        var classes=new java.util.HashMap<UUID,com.prosperitysmp.npc.economy.HeldClass>();
        for(var row:held) classes.put(row.orderId(),row.heldClass());
        assertEquals(com.prosperitysmp.npc.economy.HeldClass.R_REFUSED_CREDIT,classes.get(refusedCredit.orderId()));
        assertEquals(com.prosperitysmp.npc.economy.HeldClass.D_LEGACY_DEBIT_REFUSAL,classes.get(legacy.orderId()));
        assertEquals(com.prosperitysmp.npc.economy.HeldClass.U_UNPROVABLE,classes.get(unknown.orderId()));
    }

    private static PaymentResolution resolution(CommerceOrder order,PaymentDecision decision) {
        return new PaymentResolution(UUID.randomUUID(),"operator","case-50","Reviewed immutable SQL fixture evidence",order.requestSha256(),
                decision,PAYLOAD,null,null,null,NOW.plusSeconds(100));
    }
    private static PaymentResolution refund(CommerceOrder order,double before,double after) {
        return new PaymentResolution(UUID.randomUUID(),"operator","case-50","Independently verified existing external refund (SQL fixture)",
                order.requestSha256(),PaymentDecision.VERIFIED_REFUNDED_DEBIT,PAYLOAD,Double.doubleToRawLongBits(before),
                Double.doubleToRawLongBits(after),(UUID.randomUUID().toString()+UUID.randomUUID()).replace("-",""),NOW.plusSeconds(100));
    }

    private static ItemDeliveryEvidence confirmed(ItemCustody request) {
        return new ItemDeliveryEvidence(request.playerId(),request.payload().sha256(),"b".repeat(64),"c".repeat(64),0,request.quantity(),0,true);
    }

    private boolean admitted(CommerceOrder order,Budgets budgets) throws SQLException {
        try { return new CommerceOrderRepository(database,reserve).reserve(order,budgets).newlyReserved(); }
        catch(SQLException capped) { assertEquals("Commerce issuance budget exhausted",capped.getMessage()); return false; }
    }
    private CommerceOrder order(UUID player,Source source,String material,int quantity,LocalDate day) {
        UUID nonce=UUID.randomUUID(); return new CommerceOrder(CommerceOrder.idFor(nonce,player,source,day),nonce,player,resident,source,
                source==Source.WORKER_OUTPUT?"worker.stone":"merchant.stone",material,quantity,1000,day,NOW,source==Source.WORKER_OUTPUT?60:0);
    }
    private Handle handle(CommerceOrder order) { return new Handle(order.orderId(),run,order.playerId(),reserve,"a".repeat(64)); }
    private Receipt found(CommerceOrder order) throws SQLException { return repository.find(order.playerId(),order.orderId()).orElseThrow(); }
    private Receipt fund(CommerceOrder order) throws SQLException {
        repository.reserve(order,BUDGETS); Handle h=handle(order); repository.beginDebit(order,h); repository.recordDebit(h,DEBIT);
        repository.beginCredit(h); return repository.recordCredit(h,CREDIT,NOW);
    }
    private CommerceOrder knownOrder() throws SQLException {
        try(Connection c=database.getConnection(); var ps=c.prepareStatement("SELECT player_id,order_id FROM npc_commerce_orders LIMIT 1")) {
            ps.setQueryTimeout(5); try(ResultSet rs=ps.executeQuery()) {
                assertTrue(rs.next()); UUID player=com.prosperitysmp.npc.db.Uuids.fromBytes(rs.getBytes(1));
                UUID order=com.prosperitysmp.npc.db.Uuids.fromBytes(rs.getBytes(2)); return repository.find(player,order).orElseThrow().order();
            }
        }
    }
    private static <T> List<T> race(List<Callable<T>> calls) throws Exception {
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(calls.size())) {
            var futures=calls.stream().map(call -> pool.submit(() -> { assertTrue(start.await(3,TimeUnit.SECONDS)); return call.call(); })).toList();
            start.countDown(); List<T> values=new ArrayList<>(); for(var future:futures) values.add(future.get(15,TimeUnit.SECONDS)); return values;
        }
    }
    private static void execute(String sql) throws SQLException {
        try(Connection c=database.getConnection(); var ps=c.prepareStatement(sql)) { ps.setQueryTimeout(5); ps.execute(); }
    }
    private static int count(String table) throws SQLException { return scalar("SELECT COUNT(*) FROM "+table); }
    private static int scalar(String sql) throws SQLException {
        try(Connection c=database.getConnection(); var ps=c.prepareStatement(sql)) {
            ps.setQueryTimeout(5); try(ResultSet rs=ps.executeQuery()) { assertTrue(rs.next()); return rs.getInt(1); }
        }
    }
}
