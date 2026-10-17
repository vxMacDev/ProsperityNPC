package com.prosperitysmp.npc.economy;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.commerce.CommerceOrder;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository;
import com.prosperitysmp.npc.commerce.ItemCustody.Source;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountingProjectionCommerceIT {
    private static final Instant AT=Instant.parse("2026-09-29T16:00:00Z");
    private static final Instant FROM=Instant.parse("2026-09-23T00:00:00Z");
    private static final ZoneId ZONE=ZoneId.of("UTC");
    private static DB mariaDb;
    private static Database database;
    private static UUID resident;
    private UUID reserve,run;
    private CommerceOrderRepository commerce;
    private TransferJournal transfers;

    @BeforeAll static void boot() throws Exception {
        DBConfigurationBuilder cfg=DBConfigurationBuilder.newBuilder(); cfg.setPort(0);
        mariaDb=DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build()); mariaDb.start();
        String schema="npc_projection_"+UUID.randomUUID().toString().replace("-",""); mariaDb.createDB(schema);
        database=new Database(); database.connect(new DatabaseSettings("localhost",cfg.getPort(),schema,"root","",6,10_000L));
        for(String name:List.of("V1__initial_schema.sql","V2__resident_identity.sql","V3__purchase_receipts.sql",
                "V4__transfer_journal.sql","V17__transfer_resolutions.sql","V21__item_custody.sql","V22__commerce_orders.sql")) {
            try(InputStream input=AccountingProjectionCommerceIT.class.getResourceAsStream("/db/migration/"+name)) {
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
        for(String table:List.of("npc_commerce_money_evidence","npc_commerce_orders","npc_commerce_material_days","npc_commerce_days",
                "npc_commerce_players","npc_transfer_resolutions","npc_transfer_observations","npc_transfer_attempts","npc_economy_events",
                "npc_property_ownership","npc_property_funding","npc_properties")) execute("DELETE FROM "+table);
        reserve=UUID.randomUUID(); run=UUID.randomUUID(); commerce=new CommerceOrderRepository(database,reserve); transfers=new TransferJournal(database,reserve);
    }

    @Test void mixedSnapshotKeepsSeparateReceiptsAndChargesOnlyPublicOutflowWithinSevenDays() throws SQLException {
        long event=paidProperty();
        CommerceOrder worker=paid(Source.WORKER_OUTPUT,1500,AT);
        CommerceOrder merchant=paid(Source.MERCHANT_GOODS,1000,AT);
        CommerceOrder boundary=paid(Source.MERCHANT_GOODS,1000,FROM);
        paid(Source.MERCHANT_GOODS,1000,FROM.minusMillis(1)); paid(Source.MERCHANT_GOODS,1000,AT.plusMillis(1));
        var snapshot=read(reserve);
        assertEquals(14,snapshot.groups().size()); assertEquals(1,snapshot.receipts().size());
        assertEquals(event,snapshot.receipts().getFirst().eventId()); assertEquals(EventSource.RENT.key(),snapshot.receipts().getFirst().source());
        assertEquals(3,snapshot.commerceReceipts().size());
        assertEquals(List.of(boundary.orderId()),snapshot.commerceReceipts().stream().filter(r -> r.fundedAt().equals(FROM)).map(AccountingProjection.CommerceReceipt::orderId).toList());
        assertTrue(snapshot.commerceReceipts().stream().allMatch(r -> r.reserve().equals(reserve) && !r.payer().equals(reserve)));
        assertTrue(snapshot.commerceReceipts().stream().anyMatch(r -> r.orderId().equals(worker.orderId()) && r.source().equals("npc-worker-wages")));
        assertTrue(snapshot.commerceReceipts().stream().anyMatch(r -> r.orderId().equals(merchant.orderId()) && r.source().equals("npc-merchant-goods")));
        assertEquals(1500,total(snapshot,"2026-09-29","xfer-from-public","npc-worker-wages"));
        assertEquals(1000,total(snapshot,"2026-09-29","xfer-from-public","npc-merchant-goods"));
        assertEquals(1000,total(snapshot,"2026-09-23","xfer-from-public","npc-merchant-goods"));
        assertEquals(1000,total(snapshot,"2026-09-29","xfer-to-public",EventSource.RENT.key()));
        for(var group:snapshot.groups()) {
            assertEquals(EventSource.values().length+2,group.sources().size());
            if(group.category().equals("xfer-to-public")) {
                assertEquals(0,total(snapshot,group.day(),group.category(),"npc-worker-wages"));
                assertEquals(0,total(snapshot,group.day(),group.category(),"npc-merchant-goods"));
            }
        }
        assertEquals(0,count("npc_item_custody"));
    }

    @Test void emptyAndUnfundedOrdersPublishAllFourteenExplicitZeroSetsWithoutWrites() throws SQLException {
        CommerceOrder unstarted=order(Source.WORKER_OUTPUT,1000,AT); commerce.reserve(unstarted,budgets());
        CommerceOrder declined=order(Source.MERCHANT_GOODS,1000,AT); commerce.reserve(declined,budgets());
        var handle=handle(declined); commerce.beginDebit(declined,handle);
        commerce.recordDebit(handle,new TransferProof.Observation(true,false,0.0,1000.0,1000.0,false));
        try(Connection c=database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); c.setAutoCommit(false);
            var savepoint=c.setSavepoint();
            var snapshot=AccountingProjection.readSnapshot(c,transfers,commerce,reserve,1,AT,ZONE,System.nanoTime());
            assertEquals(14,snapshot.groups().size()); assertTrue(snapshot.commerceReceipts().isEmpty());
            for(var group:snapshot.groups()) {
                assertEquals(0,total(snapshot,group.day(),group.category(),"npc-worker-wages"));
                assertEquals(0,total(snapshot,group.day(),group.category(),"npc-merchant-goods"));
            }
            c.rollback(savepoint); c.rollback();
        }
        assertEquals(2,count("npc_commerce_orders")); assertEquals(1,count("npc_commerce_money_evidence"));
        assertEquals(CommerceOrderRepository.State.RESERVED,commerce.find(unstarted.playerId(),unstarted.orderId()).orElseThrow().state());
        assertEquals(CommerceOrderRepository.State.DECLINED,commerce.find(declined.playerId(),declined.orderId()).orElseThrow().state());
    }

    @Test void wrongReserveOrCorruptCommerceProofRejectsWholeSnapshotEvenWithValidPropertyReceipts() throws SQLException {
        paidProperty(); CommerceOrder order=paid(Source.WORKER_OUTPUT,1000,AT);
        assertThrows(SQLException.class,() -> read(UUID.randomUUID()));
        try(Connection c=database.getConnection(); var ps=c.prepareStatement(
                "UPDATE npc_commerce_money_evidence SET after_bits=? WHERE order_id=? AND leg='CREDIT'")) {
            ps.setQueryTimeout(5); ps.setLong(1,Double.doubleToRawLongBits(200.0)); ps.setBytes(2,Uuids.toBytes(order.orderId()));
            assertEquals(1,ps.executeUpdate());
        }
        assertThrows(SQLException.class,() -> read(reserve));
        assertEquals(1,count("npc_economy_events")); assertEquals(1,count("npc_commerce_orders"));
    }

    private AccountingProjection.Snapshot read(UUID expectedReserve) throws SQLException {
        try(Connection c=database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); c.setAutoCommit(false);
            try { return AccountingProjection.readSnapshot(c,transfers,commerce,expectedReserve,1,AT,ZONE,System.nanoTime()); }
            finally { c.rollback(); }
        }
    }
    private CommerceOrder order(Source source,long cents,Instant fundedAt) {
        UUID nonce=UUID.randomUUID(),player=UUID.randomUUID(); var day=fundedAt.atZone(ZONE).toLocalDate();
        return new CommerceOrder(CommerceOrder.idFor(nonce,player,source,day),nonce,player,resident,source,"projection.stone","STONE",4,
                cents,day,fundedAt.minusSeconds(1),source==Source.WORKER_OUTPUT?300:0);
    }
    private CommerceOrder paid(Source source,long cents,Instant at) throws SQLException {
        CommerceOrder order=order(source,cents,at); commerce.reserve(order,budgets()); var handle=handle(order);
        commerce.beginDebit(order,handle); commerce.recordDebit(handle,debit(cents)); commerce.beginCredit(handle); commerce.recordCredit(handle,credit(cents),at);
        return order;
    }
    private CommerceOrderRepository.Handle handle(CommerceOrder order) {
        return new CommerceOrderRepository.Handle(order.orderId(),run,order.playerId(),reserve,"a".repeat(64));
    }
    private static CommerceOrderRepository.Budgets budgets() { return new CommerceOrderRepository.Budgets(64,512,128,1_000_000); }
    private long paidProperty() throws SQLException {
        UUID property=UUID.randomUUID(),owner=UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(property,UUID.randomUUID(),"projection-"+property,"downtown","room",
                2,1000,1000,owner,0,80,true),50_000);
        long event=new EconomyEventRepository(database).insertPending(EconomyEvent.pending("projection|"+UUID.randomUUID(),1000,EventSource.RENT,
                property,owner,0,owner,"2026-09-29"));
        var handle=new TransferJournal.Handle(UUID.randomUUID(),event,run,"a".repeat(64));
        transfers.beginDebit(handle); transfers.recordDebit(handle,debit(1000)); transfers.beginCredit(handle); transfers.recordCredit(handle,credit(1000));
        try(Connection c=database.getConnection(); var ps=c.prepareStatement("UPDATE npc_economy_events SET finalized_at=? WHERE event_id=?")) {
            ps.setQueryTimeout(5); ps.setObject(1,LocalDateTime.ofInstant(AT,ZoneOffset.UTC)); ps.setLong(2,event); assertEquals(1,ps.executeUpdate());
        }
        return event;
    }
    private static TransferProof.Observation debit(long cents) { return new TransferProof.Observation(true,true,cents/100.0,1000.0,1000.0-cents/100.0,false); }
    private static TransferProof.Observation credit(long cents) { return new TransferProof.Observation(true,true,cents/100.0,200.0,200.0+cents/100.0,false); }
    private static long total(AccountingProjection.Snapshot snapshot,String day,String category,String source) {
        return snapshot.groups().stream().filter(g -> g.day().equals(day) && g.category().equals(category)).findFirst().orElseThrow().sources().stream()
                .filter(s -> s.source().equals(source)).findFirst().orElseThrow().cents();
    }
    private static void execute(String sql) throws SQLException {
        try(Connection c=database.getConnection(); var ps=c.prepareStatement(sql)) { ps.setQueryTimeout(5); ps.execute(); }
    }
    private static int count(String table) throws SQLException {
        try(Connection c=database.getConnection(); var ps=c.prepareStatement("SELECT COUNT(*) FROM "+table)) {
            ps.setQueryTimeout(5); try(ResultSet rs=ps.executeQuery()) { assertTrue(rs.next()); return rs.getInt(1); }
        }
    }
}
