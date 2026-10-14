package com.prosperitysmp.npc.it;

import static org.junit.jupiter.api.Assertions.*;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.*;
import com.prosperitysmp.npc.market.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** The broadcast name lookup binds tenant_id as BINARY(16), against real V1.. SQL. */
@Timeout(120)
class PremiumBroadcastLookupIT {
    private static DB server;
    private static Database db;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @BeforeAll static void start() throws Exception {
        var config = DBConfigurationBuilder.newBuilder(); config.setPort(0);
        server = DB.newEmbeddedDB(config.addArg("--bind-address=127.0.0.1").build()); server.start();
        String schema = "npc_pbl_" + UUID.randomUUID().toString().replace("-", ""); server.createDB(schema);
        db = new Database(); db.connect(new DatabaseSettings("localhost", config.getPort(), schema, "root", "", 6, 10000));
        var migrations = MigrationRunner.class.getDeclaredField("MIGRATIONS"); migrations.setAccessible(true);
        for (Object migration : (List<?>) migrations.get(null)) {
            var resource = migration.getClass().getDeclaredMethod("resource"); resource.setAccessible(true);
            try (var in = PremiumBroadcastLookupIT.class.getResourceAsStream("/" + resource.invoke(migration))) {
                assertNotNull(in);
                for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) {
                    try (var c = db.getConnection(); var st = c.createStatement()) { st.execute(sql); }
                }
            }
        }
    }
    @AfterAll static void stop() throws Exception { try { if (db != null) db.close(); } finally { if (server != null) server.stop(); } }

    private static HousingMoveRepository.Result result(UUID resident, HousingPolicy.Tier tier, boolean origin, boolean applied) {
        var from = origin ? new HousingMoveRequest.Origin(UUID.randomUUID(), UUID.randomUUID(), 0, 0, 0, UUID.randomUUID()) : null;
        var q = new HousingMoveRequest(UUID.randomUUID(), resident, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "old_harbor", "room", 0, 0, 0, origin ? 1 : 0, 0, DAY, tier, from);
        var receipt = new HousingMoveRepository.Receipt(q, new HousingMoveRequest.Policy(3, 1, 1, 7), 3, DAY.plusDays(7), Instant.now());
        return new HousingMoveRepository.Result(applied, receipt);
    }

    private static void tenant(UUID id, String name) throws Exception {
        try (var c = db.getConnection();
             var ps = c.prepareStatement("INSERT INTO npc_tenants(tenant_id,display_name,tier,archetype) VALUES (?,?,'PREMIUM','FIXTURE')")) {
            ps.setBytes(1, Uuids.toBytes(id)); ps.setString(2, name); ps.executeUpdate();
        }
    }

    @Test void committedPremiumRelocationYieldsNameAndMarket() throws Exception {
        UUID person = UUID.randomUUID(), other = UUID.randomUUID();
        tenant(person, "Mara Voss"); tenant(other, "Plain Tenant");
        var moved = PremiumMoveBroadcast.relocations(db, List.of(
                result(person, HousingPolicy.Tier.PREMIUM, true, true),
                result(other, HousingPolicy.Tier.PREMIUM, false, true),
                result(other, HousingPolicy.Tier.PREMIUM, true, false)));
        assertEquals(1, moved.size());
        assertEquals("Mara Voss", moved.getFirst()[0]);
        assertEquals("old_harbor", moved.getFirst()[1]);
        assertEquals("Mara Voss moved to a better-run home in old harbor.",
                PremiumMoveBroadcast.line(moved.getFirst()[0], moved.getFirst()[1]));
    }

    @Test void unknownResidentIsSkippedNotFatal() {
        assertTrue(PremiumMoveBroadcast.relocations(db, List.of(
                result(UUID.randomUUID(), HousingPolicy.Tier.PREMIUM, true, true))).isEmpty());
    }
}
