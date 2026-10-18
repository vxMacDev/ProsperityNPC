package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.economy.EconomyEvent;
import com.prosperitysmp.npc.economy.EconomyEventRepository;
import com.prosperitysmp.npc.economy.EventSource;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.property.IncidentRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.Tenant;
import com.prosperitysmp.npc.property.TenantRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repository integration tests against a REAL MariaDB (MariaDB4j). These exercise
 * the money-critical and MariaDB-specific SQL that pure unit tests can't reach:
 * economy-event idempotency / atomic claim / transactional finalize+funding /
 * crash-recovery, and the premium tenant move persistence. The real MariaDB engine runs our exact production SQL.
 */
class RepositoriesIT {

    private static final UUID WORLD = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
    private static final String DAY = "2026-07-19";

    private static DB mariaDb;
    private static Database database;

    private PropertyRepository properties;
    private TenantRepository tenants;
    private IncidentRepository incidents;
    private EconomyEventRepository events;

    // Child → parent order so plain DELETEs respect foreign keys (no FK-checks toggling).
    private static final List<String> TABLES_CHILD_FIRST = List.of(
            "npc_nav_tags", "npc_nav_edges", "npc_nav_nodes", "npc_nav_graphs",
            "npc_property_ownership", "npc_upgrades", "npc_tenants", "npc_incidents",
            "npc_property_funding", "npc_properties",
            "npc_benefit_unit_members", "npc_benefit_units",
            "npc_economy_events", "npc_operator_daily", "npc_operator_reputation", "npc_audit_log");

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        mariaDb.start();
        mariaDb.createDB("prosperity_npc");

        database = new Database();
        database.connect(new DatabaseSettings("localhost", cfg.getPort(), "prosperity_npc",
                "root", "", 4, 10_000L));

        String schema = readResource("/db/migration/V1__initial_schema.sql") + "\n" + readResource("/db/migration/V45__property_backend.sql");
        try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
            for (String stmt : MigrationRunner.splitStatements(schema)) {
                st.execute(stmt);
            }
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (database != null) {
            database.close();
        }
        if (mariaDb != null) {
            mariaDb.stop();
        }
    }

    @BeforeEach
    void freshTables() throws SQLException {
        try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
            for (String t : TABLES_CHILD_FIRST) {
                st.executeUpdate("DELETE FROM " + t);
            }
        }
        properties = new PropertyRepository(database);
        tenants = new TenantRepository(database);
        incidents = new IncidentRepository(database);
        events = new EconomyEventRepository(database);
    }

    // ---- Schema -------------------------------------------------------------

    @Test
    void schemaApplied() throws SQLException {
        Database.Status s = database.status();
        assertTrue(s.connected());
        // 16 domain tables + npc_schema_migrations is not created by V1 here (MigrationRunner
        // owns it), so just assert the core tables exist by a successful enrolled listing.
        assertEquals(0, properties.listEnrolled().size());
    }

    // ---- Economy ledger (money-critical) ------------------------------------

    @Test
    void economyEvent_insertIsIdempotent() throws SQLException {
        UUID p = UUID.randomUUID();
        String key = "rent|" + p + "|" + DAY;
        long id1 = events.insertPending(EconomyEvent.pending(key, 500, EventSource.RENT, p, p, 0, p, DAY));
        assertTrue(id1 > 0);
        long id2 = events.insertPending(EconomyEvent.pending(key, 500, EventSource.RENT, p, p, 0, p, DAY));
        assertEquals(-1L, id2, "duplicate idempotency key must be a no-op");
        assertTrue(events.existsByIdempotencyKey(key));
    }

    @Test
    void economyEvent_claimIsAtomic() throws SQLException {
        UUID p = UUID.randomUUID();
        long id = events.insertPending(EconomyEvent.pending("rent|" + p, 500, EventSource.RENT, p, p, 0, p, DAY));
        assertTrue(events.claim(id), "first claim wins");
        assertFalse(events.claim(id), "second claim must fail (already CLAIMED)");
    }

    @Test
    void economyEvent_finalizePaidUpdatesFundingTransactionally() throws SQLException {
        UUID owner = UUID.randomUUID();
        UUID p = enrolProperty("R-FUND", owner, 2, 1000, 1000, 80, 5_000);

        long rentId = events.insertPending(EconomyEvent.pending("rent|" + p, 500, EventSource.RENT, p, owner, 0, owner, DAY));
        assertTrue(events.claim(rentId));
        events.finalizePaid(rentId, 500);

        long utilId = events.insertPending(EconomyEvent.pending("util|" + p, 300, EventSource.UTILITY, p, owner, 0, owner, DAY));
        assertTrue(events.claim(utilId));
        events.finalizePaid(utilId, 300);

        long[] funding = properties.fundingOf(p); // {seed, sinks, payouts}
        assertEquals(5_000, funding[0]);
        assertEquals(300, funding[1], "FROM_PUBLIC utility increments sinks");
        assertEquals(500, funding[2], "TO_PUBLIC rent increments payouts");
        assertEquals(500, events.sumPaidToPublicForDay(DAY));
    }

    @Test
    void economyEvent_crashRecoveryMarksClaimedAsAmbiguous() throws SQLException {
        UUID p = UUID.randomUUID();
        long id = events.insertPending(EconomyEvent.pending("rent|" + p, 500, EventSource.RENT, p, p, 0, p, DAY));
        assertTrue(events.claim(id)); // crash simulated: CLAIMED but never finalized
        assertEquals(1, events.markClaimedAsAmbiguousOnStartup());
        assertFalse(events.claim(id), "an AMBIGUOUS event is never re-claimable");
        assertEquals(0, events.markClaimedAsAmbiguousOnStartup(), "recovery is idempotent");
    }

    // ---- Property lifecycle -------------------------------------------------

    @Test
    void property_enrollFindAndOwnerChange() throws SQLException {
        UUID owner = UUID.randomUUID();
        UUID p = enrolProperty("R-1", owner, 2, 1000, 1200, 80, 5_000);

        Optional<Property> found = properties.findByWorldRegion(WORLD, "R-1");
        assertTrue(found.isPresent());
        assertEquals("downtown", found.get().marketId());
        assertEquals(1200, found.get().askingRentCents());
        assertEquals(5_000, properties.fundingOf(p)[0]);
        assertEquals(1, properties.listEnrolled().size());

        UUID newOwner = UUID.randomUUID();
        properties.applyOwnerChange(p, owner, newOwner, 1, "arm");
        Property after = properties.findByWorldRegion(WORLD, "R-1").orElseThrow();
        assertEquals(newOwner, after.ownerUuid());
        assertEquals(1, after.ownershipEpoch(), "owner change bumps the epoch");
    }

    // ---- Incidents ----------------------------------------------------------

    @Test
    void incident_openVersusOverdue() throws SQLException {
        UUID owner = UUID.randomUUID();
        UUID p = enrolProperty("R-INC", owner, 2, 1000, 1000, 80, 0);
        incidents.create(p, IncidentType.PLUMBING, null, DAY, System.currentTimeMillis() + 3_600_000L, false);
        assertTrue(incidents.hasOpenIncident(p));
        assertFalse(incidents.hasOverdueIncident(p), "due in the future is not overdue");
        assertTrue(incidents.hasRollForDay(p, DAY));

        UUID owner2 = UUID.randomUUID();
        UUID p2 = enrolProperty("R-INC2", owner2, 2, 1000, 1000, 80, 0);
        incidents.create(p2, IncidentType.PESTS, null, DAY, System.currentTimeMillis() - 1_000L, false);
        assertTrue(incidents.hasOverdueIncident(p2), "past due date is overdue");
    }

    // ---- Premium tenant moves ----------------------------------------------

    @Test
    void tenant_premiumMovePersistsAndRespectsCooldown() throws SQLException {
        UUID owner = UUID.randomUUID();
        UUID a = enrolProperty("A", owner, 2, 1000, 1000, 80, 0);
        UUID b = enrolProperty("B", owner, 2, 1000, 1000, 80, 0);

        UUID tenant = UUID.randomUUID();
        tenants.insert(new Tenant(tenant, "Ava", a, 0, "PREMIUM", "GENERIC", 50));
        assertEquals(1, tenants.movablePremiumTenants(a, DAY).size());

        tenants.moveTenant(tenant, b, 0, "2026-07-26"); // 7-day cooldown
        assertEquals(0, tenants.countByProperty(a));
        assertEquals(1, tenants.countByProperty(b));
        assertTrue(tenants.movablePremiumTenants(a, DAY).isEmpty(), "tenant left A");
        assertTrue(tenants.movablePremiumTenants(b, "2026-07-25").isEmpty(), "cooldown blocks a same-week move");
        assertEquals(1, tenants.movablePremiumTenants(b, "2026-07-26").size(), "movable once the cooldown day is reached");
    }

    // ---- Helpers ------------------------------------------------------------

    private UUID enrolProperty(String region, UUID owner, int capacity, int refRent, int askRent,
                               int condition, long seed) throws SQLException {
        UUID id = UUID.randomUUID();
        properties.insertEnrollment(new Property(id, WORLD, region, "downtown", "room", capacity,
                refRent, askRent, owner, 0, condition, true), seed);
        return id;
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = RepositoriesIT.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
