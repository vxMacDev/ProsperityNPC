package com.prosperitysmp.npc.property;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real isolated MariaDB with homes enrolled under several property backends (V45). The enrolled list, the health
 * line, the backend written at enrollment and the lookup staff resume uses all respect property.backend.
 */
@Timeout(60)
class PropertyBackendExclusionIT {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-00000000b045");
    private static DB maria;
    private static Database db;
    private PropertyRepository repo;

    @BeforeAll static void boot() throws Exception {
        var cfg = DBConfigurationBuilder.newBuilder(); cfg.setPort(0);
        cfg.setBaseDir(Files.createTempDirectory("npc-backend-maria-").toFile());
        maria = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            maria.start();
            String schema = "npc_backend_" + UUID.randomUUID().toString().replace("-", "");
            maria.createDB(schema);
            db = new Database(); db.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 4, 10_000));
            applyVersion(1);
            applyVersion(45);
        } catch (Exception | AssertionError failure) {
            try { stop(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll static void stop() throws Exception {
        try { if (db != null) db.close(); }
        finally { if (maria != null) { DB old = maria; maria = null; old.stop(); } }
    }

    @BeforeEach void reset() throws SQLException {
        try (Connection c = db.getConnection()) {
            for (String t : List.of("npc_property_ownership", "npc_property_funding", "npc_properties")) exec(c, "DELETE FROM " + t);
        }
        repo = new PropertyRepository(db);
    }

    private Property enroll(String backend, String region, boolean enabled) throws SQLException {
        repo.backendId(backend);
        var p = new Property(UUID.randomUUID(), WORLD, region, "downtown", "room", 2, 1000, 1000, UUID.randomUUID(), 0, 80, enabled);
        repo.insertEnrollment(p, 0);
        return p;
    }

    private static String backendOf(Property p) throws SQLException {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT backend_id FROM npc_properties WHERE property_id=?")) {
            ps.setBytes(1, Uuids.toBytes(p.propertyId()));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private static List<String> regions(List<Property> rows) {
        return rows.stream().map(Property::regionId).sorted().toList();
    }

    @Test void enrollmentWritesTheBackendAndALegacyRowIsArm() throws Exception {
        var arm = enroll("arm", "arm-1", true);
        var listed = enroll("worldguard-listed", "wg-1", true);
        var adapter = enroll("griefprevention", "gp-1", true);
        assertEquals("arm", backendOf(arm));
        assertEquals("worldguard-listed", backendOf(listed));
        assertEquals("griefprevention", backendOf(adapter));
        UUID legacy = UUID.randomUUID();
        try (Connection c = db.getConnection()) {
            exec(c, "INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,reference_rent_cents,"
                    + "asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    legacy, WORLD, "legacy-1", "downtown", "room", 2, 1000, 1000, UUID.randomUUID(), 0, 80, true);
        }
        assertEquals("arm", backendOf(repo.findById(legacy).orElseThrow()), "rows from before V45 were all enrolled under ARM");
    }

    @Test void theEnrolledListHoldsOnlyTheConfiguredBackendsHomes() throws Exception {
        enroll("arm", "arm-1", true);
        enroll("arm", "arm-2", true);
        enroll("arm", "arm-paused", false);
        enroll("worldguard-listed", "wg-1", true);
        enroll("griefprevention", "gp-1", true);
        repo.backendId("arm");
        assertEquals(List.of("arm-1", "arm-2"), regions(repo.listEnrolled()));
        repo.backendId("worldguard-listed");
        assertEquals(List.of("wg-1"), regions(repo.listEnrolled()));
        repo.backendId("lands");
        assertEquals(List.of(), regions(repo.listEnrolled()), "a backend with no homes sees none of the others");
    }

    @Test void theHealthLineReportsHomesUnderOtherBackends() throws Exception {
        enroll("arm", "arm-1", true);
        enroll("arm", "arm-2", true);
        enroll("arm", "arm-paused", false);
        enroll("worldguard-listed", "wg-1", true);
        repo.backendId("worldguard-listed");
        var other = repo.enrolledUnderOtherBackends();
        assertEquals(new TreeMap<>(Map.of("arm", 2)), other, "enabled homes only");
        assertEquals("2 homes were enrolled under arm; the configured backend is worldguard-listed. They stay out of "
                + "payments until property.backend is arm again.", PropertyRepository.otherBackendsText(other, repo.backendId()));
        repo.backendId("arm");
        assertEquals("1 home was enrolled under worldguard-listed; the configured backend is arm. It stays out of "
                + "payments until property.backend is worldguard-listed again.",
                PropertyRepository.otherBackendsText(repo.enrolledUnderOtherBackends(), repo.backendId()));
        enroll("griefprevention", "gp-1", true);
        repo.backendId("lands");
        assertEquals("4 homes were enrolled under other backends {arm=2, griefprevention=1, worldguard-listed=1}; the "
                + "configured backend is lands. They stay out of payments until property.backend is their backend again.",
                PropertyRepository.otherBackendsText(repo.enrolledUnderOtherBackends(), repo.backendId()));
        assertEquals("", PropertyRepository.otherBackendsText(new TreeMap<>(), "arm"), "nothing to report");
    }

    @Test void resumeRefusesAHomeEnrolledUnderAnotherBackend() throws Exception {
        var paused = enroll("arm", "arm-paused", false);
        var mine = enroll("worldguard-listed", "wg-paused", false);
        var refused = assertThrows(PropertyRepository.EnrolledUnderOtherBackend.class,
                () -> repo.findByWorldRegion(WORLD, paused.regionId(), "worldguard-listed"));
        assertEquals("arm", refused.backendId());
        assertEquals("Enrolled under arm; set property.backend back to arm to manage it.", refused.getMessage());
        assertEquals(mine, repo.findByWorldRegion(WORLD, mine.regionId(), "worldguard-listed").orElseThrow());
        assertEquals(paused, repo.findByWorldRegion(WORLD, paused.regionId(), "arm").orElseThrow(), "its own backend can manage it");
        assertTrue(repo.findByWorldRegion(WORLD, "no-such-home", "worldguard-listed").isEmpty());
        assertEquals(paused, repo.findByWorldRegion(WORLD, paused.regionId()).orElseThrow(), "the unfiltered read is unchanged");
    }

    @Test void staffResumeUsesTheBackendLookupAndRepliesWithItsRefusal() throws Exception {
        String service = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/property/PropertyService.java"));
        String resume = service.substring(service.indexOf("public boolean resumeCoordinated("));
        resume = resume.substring(0, resume.indexOf("\n    public ", 1));
        assertTrue(resume.contains("paused = repo.findByWorldRegion(world, region, repo.backendId()).orElse(null);"));
        assertFalse(resume.contains("repo.findByWorldRegion(world, region).orElse(null)"), "no unfiltered read in resume");
        int caught = resume.indexOf("catch (PropertyRepository.EnrolledUnderOtherBackend elsewhere) {");
        int generic = resume.indexOf("catch (SQLException | RuntimeException failure) {");
        assertTrue(caught > 0 && caught < generic, "the refusal is caught before the generic lookup failure");
        assertTrue(resume.substring(caught, generic).contains("done.accept(elsewhere.getMessage());"));
        assertTrue(resume.substring(caught, generic).contains("admissionPending = false;"));
    }

    @Test void enrollingARegionThatAlreadyHasARowIsRefusedBeforeTheInsert() throws Exception {
        var armHome = enroll("arm", "shared-region", true);
        var paused = enroll("worldguard-listed", "wg-paused", false);
        var live = enroll("worldguard-listed", "wg-live", true);
        repo.backendId("worldguard-listed");
        assertEquals("Enrolled under arm; set property.backend back to arm to manage it.",
                PropertyService.enrollmentRefusal(repo, WORLD, armHome.regionId()));
        assertEquals("That region is enrolled but paused; use /npcproperty resume.",
                PropertyService.enrollmentRefusal(repo, WORLD, paused.regionId()));
        assertEquals("That region is already enrolled.", PropertyService.enrollmentRefusal(repo, WORLD, live.regionId()));
        assertNull(PropertyService.enrollmentRefusal(repo, WORLD, "fresh-region"), "a region with no row can be enrolled");

        // If a row appears between the check and the insert, the database refuses it outright: a definite
        // non-commit, so the enrollment is refused instead of held for recovery.
        var duplicate = new Property(UUID.randomUUID(), WORLD, armHome.regionId(), "downtown", "room", 2, 1000, 1000,
                UUID.randomUUID(), 0, 80, true);
        var refused = assertThrows(SQLException.class, () -> repo.insertEnrollment(duplicate, 0));
        assertTrue(PropertyService.certainlyNotCommitted(refused), refused.getClass().getName());
        assertTrue(repo.findById(duplicate.propertyId()).isEmpty(), "nothing was written");
        assertFalse(PropertyService.certainlyNotCommitted(new SQLException("connection reset")), "unknown stays uncertain");
    }

    @Test void enrollmentChecksSqlFirstAndAKnownRefusalFreesTheLeaseAndTheNextEnrollment() throws Exception {
        String service = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/property/PropertyService.java"));
        String enroll = service.substring(service.indexOf("public boolean enrollCoordinated("));
        enroll = enroll.substring(0, enroll.indexOf("\n    public ", 1));
        int check = enroll.indexOf("String refusal = enrollmentRefusal(repo, property.worldUuid(), property.regionId());");
        int insert = enroll.indexOf("repo.insertEnrollmentWithSeedPool(property, seed, seedPool);");
        assertTrue(check > 0 && check < insert, "the SQL check runs before the insert");
        assertTrue(enroll.contains("if (certainlyNotCommitted(failure)) refusal ="));
        int branch = enroll.indexOf("if (refused != null) {");
        assertTrue(branch > 0);
        String refusedBranch = enroll.substring(branch, enroll.indexOf("return;", branch));
        assertTrue(refusedBranch.contains("lease.releaseReconciled();"));
        assertTrue(refusedBranch.contains("admissionPending = false;"));
        assertTrue(refusedBranch.contains("done.accept(EnrollResult.refused(refused));"));
    }

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
                else ps.setObject(i + 1, args[i]);
            }
            ps.execute();
        }
    }

    private static void applyVersion(int version) throws Exception {
        var dir = Path.of("src/main/resources/db/migration");
        try (var files = Files.list(dir)) {
            var file = files.filter(f -> f.getFileName().toString().startsWith("V" + version + "__")).findFirst().orElseThrow();
            try (Connection c = db.getConnection()) {
                for (String sql : MigrationRunner.splitStatements(Files.readString(file, StandardCharsets.UTF_8))) exec(c, sql);
            }
        }
    }
}
