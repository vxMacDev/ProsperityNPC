package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.prosperitysmp.npc.resident.RelationshipMemory.Kind;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Standalone V2 tests against real MariaDB; no Bukkit, Vault, source adapter, or production database. */
class ResidentRepositoryIT {
    private static final Instant AT = Instant.parse("2026-09-06T12:00:00.123456789Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);
    private static DB mariaDb;
    private static Database database;
    private ResidentRepository residents;
    private ResidentProfile profile;
    private UUID player;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        mariaDb.start();
        mariaDb.createDB("prosperity_npc_residents");
        database = new Database();
        database.connect(new DatabaseSettings("localhost", cfg.getPort(), "prosperity_npc_residents",
                "root", "", 6, 10_000L));
        applyResource("/db/migration/V2__resident_identity.sql");
        applyResource("/db/migration/V2__resident_identity.sql");
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (database != null) database.close();
        if (mariaDb != null) mariaDb.stop();
    }

    @BeforeEach
    void freshTables() throws SQLException {
        dropFaultTrigger();
        execute("DELETE FROM npc_resident_memory");
        execute("DELETE FROM npc_resident_profiles");
        residents = new ResidentRepository(database);
        profile = ResidentCatalogue.draft(UUID.randomUUID(), "Mara Vale");
        player = UUID.randomUUID();
    }

    @AfterEach
    void dropFaultTrigger() throws SQLException {
        if (database != null && database.isConnected()) {
            execute("DROP TRIGGER IF EXISTS resident_memory_failure");
        }
    }

    @Test
    void renderProfileBatchIsExactBoundedAndDoesNotInventMissingProfiles() throws Exception {
        var expected = new java.util.HashMap<UUID, ResidentProfile>();
        for (int i=0; i<130; i++) {
            var entry = ResidentCatalogue.draft(UUID.randomUUID(), "Resident " + i);
            residents.register(entry);
            expected.put(entry.id(),entry);
        }
        var wanted = new java.util.HashSet<>(expected.keySet());
        wanted.add(UUID.randomUUID());
        assertEquals(expected,residents.findMany(wanted));
        assertTrue(residents.findMany(java.util.Set.of()).isEmpty());
        var withNull = new java.util.HashSet<UUID>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> residents.findMany(withNull));
        var oversized = new java.util.HashSet<UUID>();
        for (int i=0; i<2049; i++) oversized.add(new UUID(0,i));
        assertThrows(IllegalArgumentException.class, () -> residents.findMany(oversized));
        assertEquals(130,residents.findMany(expected.keySet()).size());
    }

    @Test
    void blockedResidentLockTimesOutWithoutMemoryAndNextEncounterRecovers() throws Exception {
        residents.register(profile);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                try (PreparedStatement lock = blocker.prepareStatement(
                        "SELECT resident_id FROM npc_resident_profiles WHERE resident_id=? FOR UPDATE")) {
                    lock.setBytes(1, Uuids.toBytes(profile.id()));
                    try (ResultSet result = lock.executeQuery()) { assertTrue(result.next()); }
                }
                Future<RelationshipMemory.State> request = worker.submit(() -> residents.introduce(profile.id(), player, AT, DAY));
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> request.get(8, TimeUnit.SECONDS));
                assertTrue(failure.getCause() instanceof java.sql.SQLTimeoutException, "server-side query deadline fired");
                assertEquals(0, memoryCount(player));
                blocker.rollback();
            }
            assertEquals(RelationshipMemory.Stage.ACQUAINTANCE, residents.introduce(profile.id(), player, AT, DAY).stage());
            assertEquals(1, memoryCount(player));
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void connectionsHaveBoundedNetworkReadTimeout() throws SQLException {
        try (Connection connection = database.getConnection()) {
            assertEquals(10_000, connection.getNetworkTimeout());
        }
    }

    @Test
    void profileAndMemorySurviveNewRepositoryWithoutRerolling() throws SQLException {
        assertTrue(residents.register(profile));
        assertFalse(residents.register(profile));
        RelationshipMemory.State first = residents.introduce(profile.id(), player, AT, DAY);
        assertEquals(RelationshipMemory.Stage.ACQUAINTANCE, first.stage());
        assertTrue(residents.appendVerified(event("repair/1", Kind.REPAIR_COMPLETED, AT.plusSeconds(1), DAY)));
        RelationshipMemory.State expected = residents.readRelationship(profile.id(), player);

        ResidentRepository reloaded = new ResidentRepository(database);
        assertEquals(profile, reloaded.find(profile.id()).orElseThrow());
        assertEquals(List.of(profile), reloaded.list(100));
        assertEquals(expected, reloaded.readRelationship(profile.id(), player));
        assertFalse(reloaded.register(profile));
        assertEquals(2, memoryCount(player));
        try (Connection c = database.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT catalogue_version, wardrobe_everyday, wardrobe_work, wardrobe_leisure "
                     + "FROM npc_resident_profiles")) {
            assertTrue(rs.next());
            assertEquals(ResidentCatalogue.VERSION, rs.getInt("catalogue_version"));
            assertEquals(profile.wardrobe().everyday(), rs.getString("wardrobe_everyday"));
            assertEquals(profile.wardrobe().work(), rs.getString("wardrobe_work"));
            assertEquals(profile.wardrobe().leisure(), rs.getString("wardrobe_leisure"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "household", "generation", "occupation", "means", "life-stage",
            "temperament", "preference", "everyday", "work", "leisure"})
    void sameUuidDifferentProfileIsAnError(String field) throws SQLException {
        assertTrue(residents.register(profile));
        residents.introduce(profile.id(), player, AT, DAY);
        ResidentProfile other = new ResidentProfile(profile.id(),
                field.equals("name") ? "mara Vale" : profile.name(),
                field.equals("household") ? profile.household() + "-other" : profile.household(),
                field.equals("generation") ? profile.generation() % 8 + 1 : profile.generation(),
                field.equals("occupation") ? next(ResidentProfile.Occupation.values(), profile.occupation()) : profile.occupation(),
                field.equals("means") ? next(ResidentProfile.Means.values(), profile.means()) : profile.means(),
                field.equals("life-stage") ? next(ResidentProfile.LifeStage.values(), profile.lifeStage()) : profile.lifeStage(),
                field.equals("temperament") ? next(ResidentProfile.Temperament.values(), profile.temperament()) : profile.temperament(),
                field.equals("preference") ? next(ResidentProfile.Preference.values(), profile.preference()) : profile.preference(),
                new ResidentProfile.Wardrobe(field.equals("everyday") ? "other/everyday" : profile.wardrobe().everyday(),
                        field.equals("work") ? "other/work" : profile.wardrobe().work(),
                        field.equals("leisure") ? "other/leisure" : profile.wardrobe().leisure()));

        assertThrows(SQLException.class, () -> residents.register(other));
        assertEquals(profile, residents.find(profile.id()).orElseThrow());
        assertFalse(residents.register(profile));
        assertEquals(1, memoryCount(player));
    }

    @Test
    void catalogueVersionIsPreservedAndParticipatesInRegistrationConflict() throws SQLException {
        assertTrue(residents.register(profile));
        execute("UPDATE npc_resident_profiles SET catalogue_version=" + (ResidentCatalogue.VERSION + 1));
        assertEquals(profile, residents.find(profile.id()).orElseThrow(), "loading must not reroll a stored profile");
        assertThrows(SQLException.class, () -> residents.register(profile));
    }

    @Test
    void absentProfileCannotAcquireMemory() throws SQLException {
        assertTrue(residents.find(profile.id()).isEmpty());
        assertTrue(residents.list(100).isEmpty());
        assertThrows(SQLException.class, () -> residents.readRelationship(profile.id(), player));
        assertThrows(SQLException.class, () -> residents.introduce(profile.id(), player, AT, DAY));
        assertThrows(SQLException.class, () -> residents.appendVerified(event("repair/1", Kind.REPAIR_COMPLETED, AT, DAY)));
        assertEquals(0, memoryCount(player));
        assertTrue(residents.find(profile.id()).isEmpty());
    }

    @Test
    void introductionRetriesRetainOriginalEvidenceAndDoNotRaiseReliability() throws SQLException {
        assertTrue(residents.register(profile));
        assertEquals(RelationshipMemory.Stage.STRANGER, residents.readRelationship(profile.id(), player).stage());
        RelationshipMemory.State first = residents.introduce(profile.id(), player, AT, DAY);
        for (int i = 1; i <= 10; i++) {
            assertEquals(first, residents.introduce(profile.id(), player, AT.plusSeconds(i * 86_400L), DAY.plusDays(i)));
        }
        assertEquals(0, first.reliability());
        assertEquals(0, first.distinctPositiveDays());
        assertEquals(1, first.recent().size());
        assertEquals(Instant.ofEpochMilli(AT.toEpochMilli()), first.recent().getFirst().occurredAt());
        assertEquals(DAY, first.recent().getFirst().economyDay());
        assertEquals("introduction", first.recent().getFirst().sourceRef());
        assertEquals(1, memoryCount(player));
    }

    @Test
    void concurrentIntroductionsProduceExactlyOneOriginalRow() throws Exception {
        assertTrue(residents.register(profile));
        List<RelationshipMemory.State> results = concurrently(
                () -> residents.introduce(profile.id(), player, AT, DAY),
                () -> new ResidentRepository(database).introduce(profile.id(), player, AT.plusSeconds(86_400), DAY.plusDays(1)));
        assertEquals(results.getFirst(), results.getLast());
        RelationshipMemory.Event original = results.getFirst().recent().getFirst();
        assertTrue(original.occurredAt().equals(Instant.ofEpochMilli(AT.toEpochMilli()))
                || original.occurredAt().equals(Instant.ofEpochMilli(AT.plusSeconds(86_400).toEpochMilli())));
        assertEquals(results.getFirst(), residents.readRelationship(profile.id(), player));
        assertEquals(1, memoryCount(player));
    }

    @Test
    void concurrentRegistrationAndExactEvidenceRetriesAreIdempotent() throws Exception {
        List<Boolean> registered = concurrently(() -> residents.register(profile),
                () -> new ResidentRepository(database).register(profile));
        assertEquals(1, registered.stream().filter(Boolean::booleanValue).count());
        RelationshipMemory.Event evidence = event("repair/1", Kind.REPAIR_COMPLETED, AT, DAY);
        List<Boolean> appended = concurrently(() -> residents.appendVerified(evidence),
                () -> new ResidentRepository(database).appendVerified(evidence));
        assertEquals(1, appended.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, memoryCount(player));
        assertEquals(4, residents.readRelationship(profile.id(), player).reliability());
    }

    @Test
    void differentPlayersAndResidentsHaveIndependentEvidence() throws SQLException {
        assertTrue(residents.register(profile));
        ResidentProfile other = ResidentCatalogue.draft(UUID.randomUUID(), "Ellis Ward");
        assertTrue(residents.register(other));
        UUID otherPlayer = UUID.randomUUID();
        residents.introduce(profile.id(), player, AT, DAY);
        assertEquals(RelationshipMemory.Stage.STRANGER, residents.readRelationship(profile.id(), otherPlayer).stage());
        assertEquals(RelationshipMemory.Stage.STRANGER, residents.readRelationship(other.id(), player).stage());
        assertTrue(residents.appendVerified(event("repair/shared", Kind.REPAIR_COMPLETED, AT.plusSeconds(1), DAY)));
        assertTrue(residents.appendVerified(new RelationshipMemory.Event(profile.id(), otherPlayer, "repair/shared",
                Kind.OBLIGATION_BROKEN, AT.plusSeconds(1), DAY)));
        assertTrue(residents.appendVerified(new RelationshipMemory.Event(other.id(), player, "repair/shared",
                Kind.OBLIGATION_KEPT, AT.plusSeconds(1), DAY)));
        assertEquals(4, residents.readRelationship(profile.id(), player).reliability());
        assertEquals(0, residents.readRelationship(profile.id(), otherPlayer).reliability());
        assertEquals(3, residents.readRelationship(other.id(), player).reliability());
        assertEquals(2, memoryCount(player));
        assertEquals(1, memoryCount(otherPlayer));
    }

    @ParameterizedTest
    @ValueSource(strings = {"kind", "time", "day"})
    void sourcePayloadCannotBeOverwritten(String field) throws SQLException {
        assertTrue(residents.register(profile));
        RelationshipMemory.Event original = event("repair/1", Kind.REPAIR_COMPLETED, AT, DAY);
        assertTrue(residents.appendVerified(original));
        RelationshipMemory.State before = residents.readRelationship(profile.id(), player);
        RelationshipMemory.Event conflict = event(original.sourceRef(),
                field.equals("kind") ? Kind.OBLIGATION_BROKEN : original.kind(),
                field.equals("time") ? AT.plusMillis(1) : AT, field.equals("day") ? DAY.plusDays(1) : DAY);
        assertThrows(SQLException.class, () -> residents.appendVerified(conflict));
        assertEquals(before, residents.readRelationship(profile.id(), player));
        assertFalse(residents.appendVerified(original));
        assertEquals(1, memoryCount(player));
    }

    @Test
    void millisecondNormalizationIsConsistentForRetriesIncludingBeforeEpoch() throws SQLException {
        assertTrue(residents.register(profile));
        Instant beforeEpoch = Instant.parse("1969-12-31T23:59:59.999999999Z");
        LocalDate beforeDay = LocalDate.of(1969, 12, 31);
        RelationshipMemory.Event original = event("repair/epoch", Kind.REPAIR_COMPLETED, beforeEpoch, beforeDay);
        assertTrue(residents.appendVerified(original));
        assertFalse(residents.appendVerified(event(original.sourceRef(), original.kind(),
                Instant.ofEpochMilli(-1), beforeDay)));
        assertEquals(Instant.ofEpochMilli(-1), residents.readRelationship(profile.id(), player).recent().getFirst().occurredAt());
        assertThrows(IllegalArgumentException.class,
                () -> residents.appendVerified(event("repair/overflow", Kind.REPAIR_COMPLETED, Instant.MAX, DAY)));
        assertEquals(1, memoryCount(player));
    }

    @Test
    void regressingDayIsRejectedWithoutPoisoningStoredReplay() throws SQLException {
        assertTrue(residents.register(profile));
        RelationshipMemory.State before = residents.introduce(profile.id(), player, AT, DAY);
        assertThrows(SQLException.class,
                () -> residents.appendVerified(event("repair/regressed", Kind.REPAIR_COMPLETED, AT.plusSeconds(1), DAY.minusDays(1))));
        assertEquals(before, residents.readRelationship(profile.id(), player));
        assertEquals(1, memoryCount(player));
    }

    @Test
    void insertionFailureRollsBackEvidenceAndAnyTriggerWork() throws SQLException {
        assertTrue(residents.register(profile));
        RelationshipMemory.State before = residents.introduce(profile.id(), player, AT, DAY);
        execute("""
                CREATE TRIGGER resident_memory_failure AFTER INSERT ON npc_resident_memory FOR EACH ROW
                BEGIN
                  UPDATE npc_resident_profiles SET generation=8 WHERE resident_id=NEW.resident_id;
                  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='resident memory rollback fixture';
                END
                """);
        SQLException failure = assertThrows(SQLException.class,
                () -> residents.appendVerified(event("repair/1", Kind.REPAIR_COMPLETED, AT.plusSeconds(1), DAY)));
        assertEquals("45000", failure.getSQLState());
        assertEquals(before, residents.readRelationship(profile.id(), player));
        assertEquals(profile, residents.find(profile.id()).orElseThrow());
        assertEquals(1, memoryCount(player));
        UUID newPlayer = UUID.randomUUID();
        assertThrows(SQLException.class, () -> residents.introduce(profile.id(), newPlayer, AT, DAY));
        assertEquals(0, memoryCount(newPlayer));
        execute("DROP TRIGGER resident_memory_failure");
        assertTrue(residents.appendVerified(event("repair/1", Kind.REPAIR_COMPLETED, AT.plusSeconds(1), DAY)));
        try (Connection c = database.getConnection()) {
            assertTrue(c.getAutoCommit(), "pool remains usable after failure");
        }
    }

    @Test
    void fullPairRetainsRetriesButRejectsAdditionalEvents() throws SQLException {
        assertTrue(residents.register(profile));
        seedHistory(RelationshipMemory.MAX_EVENTS - 1);
        RelationshipMemory.Event last = event("repair/last", Kind.REPAIR_COMPLETED, AT.plusSeconds(60), DAY);
        assertTrue(residents.appendVerified(last));
        RelationshipMemory.State before = residents.readRelationship(profile.id(), player);
        assertEquals(RelationshipMemory.MAX_VISIBLE_MEMORIES, before.recent().size());
        assertFalse(residents.appendVerified(last));
        assertEquals(before, residents.introduce(profile.id(), player, AT.plusSeconds(86_400), DAY.plusDays(1)));
        assertThrows(SQLException.class,
                () -> residents.appendVerified(event("repair/extra", Kind.REPAIR_COMPLETED, AT.plusSeconds(61), DAY)));
        assertEquals(before, residents.readRelationship(profile.id(), player));
        assertEquals(RelationshipMemory.MAX_EVENTS, memoryCount(player));
    }

    @Test
    void concurrentAppendsCannotExceedPairCapacity() throws Exception {
        assertTrue(residents.register(profile));
        seedHistory(RelationshipMemory.MAX_EVENTS - 1);
        List<Boolean> inserted = concurrently(
                () -> appendOrFull(event("repair/last-a", Kind.REPAIR_COMPLETED, AT.plusSeconds(60), DAY)),
                () -> appendOrFull(event("repair/last-b", Kind.REPAIR_COMPLETED, AT.plusSeconds(60), DAY)));
        assertEquals(1, inserted.stream().filter(Boolean::booleanValue).count());
        assertEquals(RelationshipMemory.MAX_EVENTS, memoryCount(player));
        assertEquals(RelationshipMemory.MAX_VISIBLE_MEMORIES, residents.readRelationship(profile.id(), player).recent().size());
    }

    @Test
    void outOfBandOverflowIsNotSilentlyTruncated() throws SQLException {
        assertTrue(residents.register(profile));
        seedHistory(RelationshipMemory.MAX_EVENTS + 1);
        assertThrows(SQLException.class, () -> residents.readRelationship(profile.id(), player));
        assertThrows(SQLException.class, () -> residents.introduce(profile.id(), player, AT, DAY));
        assertThrows(SQLException.class,
                () -> residents.appendVerified(event("repair/extra", Kind.REPAIR_COMPLETED, AT.plusSeconds(60), DAY)));
        assertEquals(RelationshipMemory.MAX_EVENTS + 1, memoryCount(player));
    }

    @Test
    void profileListingIsHardBoundedAndDeterministic() throws SQLException {
        List<ResidentProfile> expected = new ArrayList<>();
        for (int i = 1; i <= 105; i++) {
            ResidentProfile p = ResidentCatalogue.draft(new UUID(0, i), "Resident " + i);
            assertTrue(residents.register(p));
            expected.add(p);
        }
        for (int max : new int[]{Integer.MIN_VALUE, -1, 0}) assertTrue(residents.list(max).isEmpty());
        assertEquals(expected.subList(0, 1), residents.list(1));
        assertEquals(expected.subList(0, 20), residents.list(20));
        assertEquals(expected.subList(0, 100), residents.list(100));
        assertEquals(expected.subList(0, 100), residents.list(Integer.MAX_VALUE));
        assertEquals(expected.getLast(), residents.find(expected.getLast().id()).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secret", "/absolute", "resident/../work", "resident\\work", "file:/work",
            "resident//work", "resident/work/", "Resident/work", "resident/_work", "resident/-work"})
    void invalidWardrobePathsAreRejected(String invalid) throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> residents.register(withWardrobe(invalid)));
        assertTrue(residents.find(profile.id()).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"occupation", "means", "life_stage", "temperament", "preference"})
    void invalidStoredCodesAreRejectedBySchema(String column) throws SQLException {
        assertTrue(residents.register(profile));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_resident_profiles SET " + column + "='UNKNOWN'"));
        assertEquals(profile, residents.find(profile.id()).orElseThrow());
    }

    @Test
    void invalidStoredPathAndGenerationAreRejectedAndBadTextFailsClosedOnRead() throws SQLException {
        assertTrue(residents.register(profile));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_resident_profiles SET wardrobe_work='resident/../work'"));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_resident_profiles SET generation=0"));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_resident_profiles SET occupation='cook'"));
        execute("UPDATE npc_resident_profiles SET name=''");
        assertThrows(SQLException.class, () -> residents.find(profile.id()));
        assertThrows(SQLException.class, () -> residents.list(10));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Repair/a", "repair a", "repair|a", "../a", "repair\\a"})
    void invalidSourceShapeIsRejected(String source) throws SQLException {
        assertTrue(residents.register(profile));
        assertThrows(IllegalArgumentException.class,
                () -> residents.appendVerified(event(source, Kind.REPAIR_COMPLETED, AT, DAY)));
        assertEquals(0, memoryCount(player));
    }

    @Test
    void sourceIsCaseSensitiveAndIntroductionNamespaceCannotBeSpoofed() throws SQLException {
        assertTrue(residents.register(profile));
        residents.introduce(profile.id(), player, AT, DAY);
        assertFalse(residents.appendVerified(event("introduction", Kind.INTRODUCTION, AT, DAY)));
        assertThrows(SQLException.class,
                () -> residents.appendVerified(event("introduction", Kind.INTRODUCTION, AT.plusMillis(1), DAY)));
        assertThrows(IllegalArgumentException.class,
                () -> residents.appendVerified(event("introduction", Kind.REPAIR_COMPLETED, AT, DAY)));
        assertThrows(IllegalArgumentException.class,
                () -> residents.appendVerified(event("introduction/again", Kind.INTRODUCTION, AT, DAY)));
        try (Connection c = database.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COLLATION(source_ref) FROM npc_resident_memory LIMIT 1")) {
            assertTrue(rs.next());
            assertEquals("ascii_bin", rs.getString(1));
        }
        assertThrows(SQLException.class, () -> execute("UPDATE npc_resident_memory SET source_ref='Introduction'"));
        assertEquals(1, memoryCount(player));
    }

    @Test
    void tenantDeletionDoesNotDeleteResidentOrRelationships() throws Exception {
        applyResource("/db/migration/V1__initial_schema.sql");
        assertTrue(residents.register(profile));
        RelationshipMemory.State memory = residents.introduce(profile.id(), player, AT, DAY);
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO npc_tenants (tenant_id, display_name, tier, archetype) VALUES (?, 'Mara Vale', 'STANDARD', 'GENERIC')")) {
            ps.setBytes(1, Uuids.toBytes(profile.id()));
            assertEquals(1, ps.executeUpdate());
        }
        execute("DELETE FROM npc_tenants");
        assertEquals(profile, residents.find(profile.id()).orElseThrow());
        assertEquals(memory, new ResidentRepository(database).readRelationship(profile.id(), player));
        assertThrows(SQLException.class, () -> execute("DELETE FROM npc_resident_profiles"),
                "resident deletion itself must not cascade through evidence");
    }

    private static <E extends Enum<E>> E next(E[] values, E current) {
        return values[(current.ordinal() + 1) % values.length];
    }

    private ResidentProfile withWardrobe(String everyday) {
        return new ResidentProfile(profile.id(), profile.name(), profile.household(), profile.generation(),
                profile.occupation(), profile.means(), profile.lifeStage(), profile.temperament(), profile.preference(),
                new ResidentProfile.Wardrobe(everyday, profile.wardrobe().work(), profile.wardrobe().leisure()));
    }

    private RelationshipMemory.Event event(String source, Kind kind, Instant at, LocalDate day) {
        return new RelationshipMemory.Event(profile.id(), player, source, kind, at, day);
    }

    private boolean appendOrFull(RelationshipMemory.Event event) throws SQLException {
        try {
            return residents.appendVerified(event);
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("memory is full"), "only the capacity rejection is expected: " + e);
            return false;
        }
    }

    private long memoryCount(UUID targetPlayer) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM npc_resident_memory WHERE resident_id=? AND player_id=?")) {
            ps.setBytes(1, Uuids.toBytes(profile.id()));
            ps.setBytes(2, Uuids.toBytes(targetPlayer));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private void seedHistory(int count) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO npc_resident_memory (resident_id, player_id, source_ref, kind, occurred_at_millis, economy_day)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            c.setAutoCommit(false);
            try {
                for (int i = 0; i < count; i++) {
                    ps.setBytes(1, Uuids.toBytes(profile.id()));
                    ps.setBytes(2, Uuids.toBytes(player));
                    ps.setString(3, i == 0 ? "introduction" : "fixture/" + i);
                    ps.setString(4, i == 0 ? "INTRODUCTION" : "REPAIR_COMPLETED");
                    ps.setLong(5, AT.toEpochMilli() + i);
                    ps.setString(6, DAY.toString());
                    ps.addBatch();
                }
                assertEquals(count, ps.executeBatch().length);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static <T> List<T> concurrently(Callable<T> left, Callable<T> right) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<T> a = workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return left.call();
            });
            Future<T> b = workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return right.call();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS), "resident workers terminated");
        }
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = ResidentRepositoryIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            String schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
                for (String sql : MigrationRunner.splitStatements(schema)) st.execute(sql);
            }
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}
