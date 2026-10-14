package com.prosperitysmp.npc.market;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.command.NpcTownCommand;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentCastFile;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** residents.yml seeding and the housing roster against real MariaDB. No Bukkit, Vault or money. */
class ResidentCastIT {
    private static final UUID WORLD = UUID.fromString("fedcba98-7654-3210-89ab-cdef01234567");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);
    private static final HousingPolicy POLICY = new HousingPolicy(WORLD, "market", "starter",
            HousingPolicy.Tier.STANDARD, 1_000, 60);
    private static DB mariaDb;
    private static Database database;
    private ResidentRepository residents;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            String schema = "npc_cast_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            applyResource("/db/migration/V1__initial_schema.sql");
            applyResource("/db/migration/V2__resident_identity.sql");
            applyResource("/db/migration/V9__housing_registry.sql");
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
            if (mariaDb != null) { DB stopping = mariaDb; mariaDb = null; stopping.stop(); }
        }
    }

    @BeforeEach
    void freshTables() throws SQLException {
        for (String table : List.of("npc_housing_members", "npc_resident_memory", "npc_resident_profiles", "npc_housing_registry_lock"))
            execute("DELETE FROM " + table);
        execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (1)");
        residents = new ResidentRepository(database);
    }

    private static ResidentCast extended() throws Exception {
        try (InputStream in = Files.newInputStream(Path.of("examples/residents.extended.yml"))) {
            return ResidentCastFile.read(in);
        }
    }

    private List<String> seed(ResidentCast cast) throws SQLException {
        var tally = new NpcTownCommand.SeedTally();
        for (ResidentProfile person : cast.profiles()) tally.record(person, () -> residents.register(person));
        return tally.lines();
    }

    @Test void seedingTheNineResidentCastRegistersNine() throws Exception {
        assertEquals(List.of("Resident cast: 9 registered, 0 unchanged."), seed(extended()));
        assertEquals(9, count("SELECT COUNT(*) FROM npc_resident_profiles"));
        assertEquals(List.of("Resident cast: 0 registered, 9 unchanged."), seed(extended()), "reseeding is a no-op");
    }

    @Test void theBuiltInRowsStillMatchAfterSwitchingToTheFile() throws Exception {
        for (ResidentProfile person : ResidentCatalogue.betaCast()) assertTrue(residents.register(person));
        Map<UUID, ResidentProfile> before = residents.findMany(ResidentCast.builtIn().profiles().stream()
                .map(ResidentProfile::id).collect(Collectors.toSet()));
        assertEquals(List.of("Resident cast: 6 registered, 3 unchanged."), seed(extended()));
        for (ResidentProfile person : ResidentCatalogue.betaCast())
            assertEquals(before.get(person.id()), residents.find(person.id()).orElseThrow(), person.name());
    }

    @Test void anEditedPersonalityIsKeptAsSavedAndNamed() throws Exception {
        var cast = extended();
        seed(cast);
        var petra = cast.byFirstName("petra").orElseThrow();
        var saved = residents.find(petra.id()).orElseThrow();
        var edited = new ArrayList<ResidentProfile>();
        for (ResidentProfile person : cast.profiles()) edited.add(person.id().equals(petra.id())
                ? new ResidentProfile(person.id(), person.name(), person.household(), person.generation(), person.occupation(),
                        person.means(), person.lifeStage(), ResidentProfile.Temperament.WARM, person.preference(), person.wardrobe())
                : person);
        var reseeded = new ResidentCast(edited, Map.of(), ResidentCast.Source.FILE);
        assertEquals(List.of("Resident cast: 0 registered, 8 unchanged.",
                "Petra Lind is already saved with different details. The saved version is kept."), seed(reseeded));
        assertEquals(saved, residents.find(petra.id()).orElseThrow());
        assertEquals(ResidentProfile.Temperament.RESERVED, residents.find(petra.id()).orElseThrow().temperament());
    }

    @Test void withTheCastFileOnResidentsWithoutASkinAreSkippedAndCounted() throws Exception {
        var cast = extended();
        Set<UUID> skinned = ResidentCatalogue.betaCast().stream().map(ResidentProfile::id).collect(Collectors.toSet());
        boolean required = HousingRuntime.skinRequired(cast, () -> true);
        assertTrue(required);
        var result = HousingRuntime.rosterCast(database, POLICY, DAY, cast,
                required ? person -> skinned.contains(person.id()) : person -> true);
        assertEquals(new HousingRuntime.RosterResult(3, 6), result);
        assertEquals(List.of("Housing roster ready: 3 residents added. Existing records preserved.",
                "Roster: 6 residents skipped until they have a skin."), result.messages());
        assertEquals(skinned, new HousingRegistry(database).list().stream().map(HousingRegistry.Member::residentId)
                .collect(Collectors.toSet()));
        assertEquals(3, count("SELECT COUNT(*) FROM npc_resident_profiles"), "skipped residents are not even registered");
    }

    @Test void withTheCastFileOffAllThreeRegisterExactlyAsBefore() throws Exception {
        var builtIn = ResidentCast.builtIn();
        boolean required = HousingRuntime.skinRequired(builtIn, () -> { throw new AssertionError("flag read while the cast file is off"); });
        assertFalse(required);
        var result = HousingRuntime.rosterCast(database, POLICY, DAY, builtIn, required ? person -> false : person -> true);
        assertEquals(List.of("Housing roster ready: 3 residents added. Existing records preserved."), result.messages());
        assertEquals(3, new HousingRegistry(database).list().size());
        assertFalse(HousingRuntime.skinRequired(ResidentCastFile.load(Path.of("no-such-dir"), true), () -> false),
                "require-skin-for-roster off: nobody is skipped");
    }

    @Test void rosterOneNeverAdmitsAFileResidentWithoutASkin() throws Exception {
        var cast = extended();
        var otto = cast.byFirstName("otto").orElseThrow();
        var mara = cast.byFirstName("mara").orElseThrow();
        java.util.function.Predicate<ResidentProfile> onlyMara = person -> person.id().equals(mara.id());
        assertTrue(HousingRuntime.heldForSkin(otto.id(), cast, onlyMara, () -> true));
        assertFalse(HousingRuntime.heldForSkin(mara.id(), cast, onlyMara, () -> true), "has a skin");
        assertFalse(HousingRuntime.heldForSkin(otto.id(), cast, onlyMara, () -> false), "check turned off");
        assertFalse(HousingRuntime.heldForSkin(UUID.randomUUID(), cast, onlyMara, () -> true), "not a cast member");
        assertFalse(HousingRuntime.heldForSkin(mara.id(), ResidentCast.builtIn(), person -> false,
                () -> { throw new AssertionError("flag read while the cast file is off"); }));

        residents.register(otto);
        assertEquals(HousingRuntime.RosterOne.HELD_FOR_SKIN, HousingRuntime.rosterOne(database, otto.id(), POLICY, DAY, true));
        assertTrue(new HousingRegistry(database).find(otto.id()).isEmpty(), "no invisible tenant");
        assertEquals(HousingRuntime.RosterOne.ADMITTED, HousingRuntime.rosterOne(database, otto.id(), POLICY, DAY, false));
        assertTrue(new HousingRegistry(database).find(otto.id()).isPresent());
        var premium = new HousingPolicy(WORLD, "market", "starter", HousingPolicy.Tier.PREMIUM, 2_000, 80);
        assertEquals(HousingRuntime.RosterOne.UPDATED, HousingRuntime.rosterOne(database, otto.id(), premium, DAY, true),
                "a policy change on an enrolled resident adds no tenant");
        assertEquals(premium, new HousingRegistry(database).find(otto.id()).orElseThrow().policy());
    }

    @Test void aFileCastKeepsADifferingSavedProfileAndStillEnrollsIt() throws Exception {
        var cast = extended();
        var petra = cast.byFirstName("petra").orElseThrow();
        residents.register(new ResidentProfile(petra.id(), petra.name(), petra.household(), petra.generation(),
                petra.occupation(), petra.means(), petra.lifeStage(), ResidentProfile.Temperament.WARM, petra.preference(),
                petra.wardrobe()));
        var result = HousingRuntime.rosterCast(database, POLICY, DAY, cast, person -> true);
        assertEquals(9, result.added());
        assertEquals(ResidentProfile.Temperament.WARM, residents.find(petra.id()).orElseThrow().temperament());
        // The built-in cast keeps today's rule: a differing saved row stops the import.
        execute("DELETE FROM npc_housing_members");
        var mara = ResidentCatalogue.betaCast().getFirst();
        execute("UPDATE npc_resident_profiles SET temperament='RESERVED' WHERE resident_id=?", mara.id());
        assertThrows(ResidentRepository.ProfileMismatchException.class,
                () -> HousingRuntime.rosterCast(database, POLICY, DAY, ResidentCast.builtIn(), person -> true));
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = ResidentCastIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
        }
    }

    private static void execute(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(3);
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof UUID uuid) ps.setBytes(i + 1, com.prosperitysmp.npc.db.Uuids.toBytes(uuid));
                else ps.setObject(i + 1, values[i]);
            }
            ps.execute();
        }
    }
}
