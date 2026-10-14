package com.prosperitysmp.npc.economy;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository;
import com.prosperitysmp.npc.config.EconomyLimits;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.RentReservationRepository.AppreciationScore;
import com.prosperitysmp.npc.economy.RentReservationRepository.Reserved;
import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.Tenant;
import com.prosperitysmp.npc.property.TenantRepository;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cash appreciation on a real MariaDB: the real rent and appreciation reservation SQL, the real
 * transfer journal and the real accounting projection. Vault is replaced by fixture observations
 * and a simulated reserve balance; nothing here can move money.
 */
@Timeout(120)
class AppreciationIT {
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00.001Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    /** The next economy day: DAY is closed, so its rent is final and its appreciation may be reserved. */
    private static final Instant NEXT_NOW = NOW.plusSeconds(86_400);
    private static final LocalDate NEXT = DAY.plusDays(1);
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final int RESET = 4;
    private static final UUID WORLD = new UUID(0, 3100);
    private static final UUID RESERVE = new UUID(0, 3102);
    private static final long FLOOR = 3_500_000L;
    private static final String APPRECIATION = "npc_economy_events WHERE source='npc-property-appreciation'";
    /** Score 100: the formula entitlement is the full $3 appreciation cap. */
    private static final AppreciationScore TOP = (c, p, at) -> OptionalDouble.of(100.0);
    private static DB mariaDb;
    private static Database database;
    private static List<String> tables;
    private MutableClock clock;
    private List<UUID> properties;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            String schema = "npc_appreciation_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            var migrations = MigrationRunner.class.getDeclaredField("MIGRATIONS");
            migrations.setAccessible(true);
            for (Object migration : (List<?>) migrations.get(null)) {
                var resource = migration.getClass().getDeclaredMethod("resource");
                resource.setAccessible(true);
                applyResource("/" + resource.invoke(migration));
            }
            tables = rows("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME").stream().map(List::getFirst).toList();
            assertTrue(tables.containsAll(List.of("npc_appreciation_reservations", "npc_rent_reservations",
                    "npc_transfer_attempts", "npc_economy_events")));
        } catch (Exception | AssertionError failure) {
            try { stopDatabase(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        try { if (database != null) database.close(); }
        finally {
            if (mariaDb != null) {
                DB stopping = mariaDb;
                mariaDb = null;
                stopping.stop();
            }
        }
    }

    @BeforeEach
    void freshFixture() throws SQLException {
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION foreign_key_checks=0");
            try { for (String table : tables) execute(c, "DELETE FROM " + table); }
            finally { execute(c, "SET SESSION foreign_key_checks=1"); }
        }
        execute("INSERT INTO npc_rent_mutex(singleton) VALUES (1)");
        execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (1)");
        execute("INSERT INTO npc_chronicle_recovery_lock(singleton_id) VALUES (1)");
        clock = new MutableClock(NOW);
        var ids = new ArrayList<UUID>();
        // Four properties, four owners, one tenant each, each with today's utility bill verified paid.
        for (int i = 0; i < 4; i++) ids.add(operating(new UUID(0, 3110 + i)));
        properties = ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        assertEquals(0, count("npc_appreciation_reservations"));
    }

    @Test
    void shippedConfigKeepsTheRuntimeGateClosedSoTheBatchWritesNoAppreciation() throws Exception {
        final Object shipped;
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
            assertEquals("economy.appreciation-enabled", UtilityPaymentRuntime.APPRECIATION);
            shipped = config.get(UtilityPaymentRuntime.APPRECIATION);
            assertEquals(Boolean.FALSE, shipped);
            assertEquals(300, config.getLong("economy.appreciation-daily-cap-cents"));
        }
        var limits = limits(15000, 4500, 1500);
        assertEquals(4, payRent(limits));
        closeDay();
        // The runtime's own gate and rent-then-appreciation composition, fed the shipped value. The
        // appreciation phase below is real: if the gate opened it would reserve and pay on this database.
        int[] counted = {-1};
        var batch = UtilityPaymentRuntime.thenAppreciation(CompletableFuture.completedFuture(4),
                UtilityPaymentRuntime.appreciationEnabled(shipped), appreciationPhase(limits), n -> counted[0] = n, Runnable::run);
        assertEquals(4, batch.join());
        assertEquals(-1, counted[0], "flag off: the appreciation phase never ran");
        assertEquals(2400, sum("npc_economy_events WHERE source='npc-property-rent' AND state='PAID'"));
        assertEquals(0, count("npc_appreciation_reservations"));
        assertEquals(0, count(APPRECIATION));
        assertEquals(0, count("npc_transfer_attempts a JOIN " + APPRECIATION.replace("WHERE", "e ON e.event_id=a.event_id WHERE")));
        // Positive control: the same composition with the gate open reserves and pays on this database.
        var open = UtilityPaymentRuntime.thenAppreciation(CompletableFuture.completedFuture(0),
                UtilityPaymentRuntime.appreciationEnabled(Boolean.TRUE), appreciationPhase(limits), n -> counted[0] = n, Runnable::run);
        assertEquals(0, open.join());
        assertEquals(4, counted[0]);
        assertEquals(4, count("npc_appreciation_reservations WHERE economy_day=?", DAY));
        assertEquals(1200, sum(APPRECIATION + " AND state='PAID' AND economy_day=?", DAY));
    }

    @Test
    void openDayCannotReserveAppreciationOnlyTheDayThatJustClosed() throws Exception {
        var limits = limits(15000, 4500, 1500);
        assertEquals(4, payRent(limits));
        var before = snapshot();
        assertThrows(IllegalArgumentException.class, () -> rents().reserveAppreciation(DAY, enabled(), limits, TOP),
                "rent for the open day is not final");
        clock.set(NEXT_NOW.plusSeconds(86_400));
        assertThrows(IllegalArgumentException.class, () -> rents().reserveAppreciation(DAY, enabled(), limits, TOP),
                "a day older than the one that just closed is never paid");
        assertEquals(before, snapshot(), "a refused day writes nothing");
        // Positive control: the same call once DAY has just closed.
        closeDay();
        assertEquals(4, rents().reserveAppreciation(DAY, enabled(), limits, TOP).size());
    }

    @Test
    void aLaterRentBatchIsNeverReducedByAppreciation() throws Exception {
        // $33 server cap: four $6 rents early, one $6 rent from a later batch, $3 left for appreciation.
        var limits = limits(3300, 4500, 1500);
        assertEquals(4, payRent(limits));
        UUID late = operating(new UUID(0, 3199));
        assertThrows(IllegalArgumentException.class, () -> rents().reserveAppreciation(DAY, enabled(), limits, TOP));
        var lateRent = rents().reserve(DAY, List.of(property(late)), limits);
        assertEquals(List.of(600L), lateRent.stream().map(Reserved::cents).toList(), "a later batch's rent is paid in full");
        assertTrue(pay(lateRent.getFirst()));
        closeDay();
        var all = new ArrayList<>(enabled());
        all.add(property(late));
        var appreciation = rents().reserveAppreciation(DAY, all, limits, TOP);
        assertEquals(List.of(60L, 60L, 60L, 60L, 60L), appreciation.stream().map(Reserved::cents).toList(),
                "appreciation shares only what rent left of DAY's caps");
        for (var row : appreciation) assertTrue(pay(row));
        assertEquals(3300, sum("npc_economy_events WHERE direction='TRANSFER_TO_PUBLIC' AND economy_day=?", DAY));
        // The open day's rent sees none of the closed day's appreciation, even reserved after it.
        UtilityRepository.Receipt utility = utilities().preparePayment(UUID.randomUUID(), late, new UUID(0, 3199), 0, NEXT).receipt();
        payUtility(utility, clock.instant().plusSeconds(1));
        assertEquals(UtilityRepository.State.PAID, utilities().reconcile(utility.eventId()).orElseThrow().state());
        var nextRent = rents().reserve(NEXT, List.of(property(late)), limits);
        assertEquals(List.of(600L), nextRent.stream().map(Reserved::cents).toList());
        assertEquals(0, count(APPRECIATION + " AND economy_day=?", NEXT));
    }

    /** server, benefit unit, property cap; expected appreciation per property after $6 rent took its share first. */
    @ParameterizedTest
    @CsvSource({"2600,4500,1500,50", "15000,700,1500,100", "15000,4500,700,100", "15000,4500,1500,300", "2400,4500,1500,0"})
    void rentIsAllocatedFirstAndAppreciationSharesOnlyTheRemainingCaps(long server, long owner, long property, long each)
            throws Exception {
        var limits = limits(server, owner, property);
        assertEquals(4, payRent(limits));
        for (UUID id : properties) assertEquals(600, sum("npc_economy_events WHERE source='npc-property-rent' AND property_id=?", id),
                "rent is reserved before appreciation and never squeezed by it");
        closeDay();
        var reserved = rents().reserveAppreciation(DAY, enabled(), limits, TOP);
        assertEquals(each == 0 ? 0 : 4, reserved.size());
        for (var row : reserved) assertEquals(each, row.cents());
        assertEquals(reserved, rents().pendingAppreciation(DAY, enabled()));
        for (var row : reserved) assertTrue(pay(row));
        assertEquals(4 * each, sum(APPRECIATION + " AND state='PAID'"));
        long closed = sum("npc_economy_events WHERE direction='TRANSFER_TO_PUBLIC' AND economy_day=?", DAY);
        assertTrue(closed <= server, "server cap holds across rent and appreciation");
        for (UUID id : properties) {
            long total = sum("npc_economy_events WHERE direction='TRANSFER_TO_PUBLIC' AND property_id=?", id);
            assertTrue(total <= property && total <= owner, "property and benefit-unit caps hold across both sources");
            assertEquals(each == 0 ? 0 : 1, count("npc_appreciation_reservations WHERE property_id=? AND score_tenths=1000 AND entitlement_cents=300", id),
                    "the recorded inputs are the actual score and the pre-cap entitlement");
        }
        // Replay of the fully settled day reserves, attempts and pays nothing.
        var settled = snapshot();
        assertEquals(0, payRent(limits));
        assertTrue(rents().reserveAppreciation(DAY, enabled(), limits, TOP).isEmpty());
        assertTrue(rents().pendingAppreciation(DAY, enabled()).isEmpty());
        assertEquals(settled, snapshot(), "replay must not write");
    }

    @Test
    void lifetimeFundingRuleLimitsAndThenExhaustsAppreciation() throws Exception {
        // Leave 720, 600 and 610 cents of lifetime funding: $6 rent first, then whatever is left.
        long[] headroom = {720, 600, 610};
        for (int i = 0; i < headroom.length; i++)
            execute("UPDATE npc_property_funding SET successful_cash_payouts_cents="
                    + "one_time_seed_cents+FLOOR(successful_cash_sinks_cents*0.9)-? WHERE property_id=?", headroom[i], properties.get(i));
        var limits = limits(15000, 4500, 1500);
        assertEquals(4, payRent(limits));
        closeDay();
        var reserved = rents().reserveAppreciation(DAY, enabled(), limits, TOP);
        assertEquals(List.of(120L, 10L, 300L), reserved.stream().map(Reserved::cents).toList());
        assertEquals(List.of(properties.get(0), properties.get(2), properties.get(3)),
                reserved.stream().map(r -> r.property().propertyId()).toList());
        assertEquals(0, count("npc_appreciation_reservations WHERE property_id=?", properties.get(1)), "no funding left after rent");
        for (var row : reserved) assertTrue(pay(row));
        // Each funded-out property has paid exactly its lifetime allowance, never more.
        assertEquals(3, count("npc_property_funding WHERE property_id IN (?,?,?) AND successful_cash_payouts_cents="
                + "one_time_seed_cents+FLOOR(successful_cash_sinks_cents*0.9)", properties.get(0), properties.get(1), properties.get(2)));
        assertTrue(rents().reserveAppreciation(DAY, enabled(), limits, TOP).isEmpty());
    }

    @Test
    void centralBankFloorDefersAppreciationWithoutLosingOrDoublingIt() throws Exception {
        assertTrue(UtilityPaymentRuntime.floorAllows(FLOOR + 300, 300, FLOOR));
        assertFalse(UtilityPaymentRuntime.floorAllows(FLOOR + 299, 300, FLOOR));
        assertFalse(UtilityPaymentRuntime.floorAllows(200, 300, 0));
        var limits = limits(15000, 4500, 1500);
        long[] balance = {FLOOR + 2400 + 100};
        assertEquals(4, dispatch(rents().reserve(DAY, enabled(), limits), balance), "all rent fits above the floor");
        closeDay();
        rents().reserveAppreciation(DAY, enabled(), limits, TOP);
        assertEquals(0, dispatch(rents().pendingAppreciation(DAY, enabled()), balance), "no appreciation may breach the floor");
        assertEquals(FLOOR + 100, balance[0]);
        assertEquals(4, count(APPRECIATION + " AND state='PENDING'"));
        assertEquals(0, count("npc_transfer_attempts a JOIN " + APPRECIATION.replace("WHERE", "e ON e.event_id=a.event_id WHERE")));
        // A later batch on the same (next) economy day: no second reservation, deferred rows paid as the floor allows.
        assertTrue(rents().reserveAppreciation(DAY, enabled(), limits, TOP).isEmpty());
        balance[0] = FLOOR + 600;
        assertEquals(2, dispatch(rents().pendingAppreciation(DAY, enabled()), balance));
        balance[0] = FLOOR + 100_000;
        assertEquals(2, dispatch(rents().pendingAppreciation(DAY, enabled()), balance));
        assertEquals(0, dispatch(rents().pendingAppreciation(DAY, enabled()), balance));
        assertEquals(4, count("npc_appreciation_reservations"));
        for (UUID id : properties) assertEquals(300, sum(APPRECIATION + " AND state='PAID' AND property_id=?", id));
    }

    @Test
    void crashBetweenLegsIsHeldAmbiguousNeverRetriedAndStillCounted() throws Exception {
        // $44 server cap: five $6 rents and four $3 appreciations leave exactly $2 of DAY's cap.
        var limits = limits(4400, 4500, 1500);
        assertEquals(4, payRent(limits));
        UUID fifth = operating(new UUID(0, 3199));
        var fifthRent = rents().reserve(DAY, List.of(property(fifth)), limits);
        assertTrue(pay(fifthRent.getFirst()));
        closeDay();
        var reserved = rents().reserveAppreciation(DAY, enabled(), limits, TOP);
        assertEquals(4, reserved.size());
        var crashed = reserved.getFirst();
        Handle handle = new Handle(UUID.randomUUID(), crashed.eventId(), UUID.randomUUID(), "c".repeat(64));
        assertTrue(journal().beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.CREDIT_READY, journal().recordDebit(handle,
                new Observation(true, true, 3.0, 100000.0, 99997.0, false)).state());
        // The host dies before the credit leg. The next host's bounded recovery holds it.
        assertEquals(1, journal().recoverOtherRuns(UUID.randomUUID(), 100));
        assertEquals(TransferJournal.State.AMBIGUOUS, journal().find(crashed.eventId()).orElseThrow().state());
        assertEquals(1, count(APPRECIATION + " AND state='AMBIGUOUS' AND event_id=?", crashed.eventId()));
        var pending = rents().pendingAppreciation(DAY, enabled());
        assertEquals(3, pending.size());
        assertTrue(pending.stream().noneMatch(r -> r.eventId() == crashed.eventId()), "ambiguous appreciation is never re-queued");
        // A new handle is refused outright; the original handle reads back the held state, never a new debit.
        assertThrows(SQLException.class, () -> journal().beginDebit(
                new Handle(UUID.randomUUID(), crashed.eventId(), UUID.randomUUID(), "d".repeat(64))));
        var retry = journal().beginDebit(handle);
        assertFalse(retry.newlyStarted());
        assertEquals(TransferJournal.State.AMBIGUOUS, retry.receipt().state());
        assertEquals(1, count("npc_transfer_attempts WHERE event_id=?", crashed.eventId()));
        for (var row : pending) assertTrue(pay(row));
        assertTrue(rents().reserveAppreciation(DAY, enabled(), limits, TOP).isEmpty(), "replay reserves nothing");
        // The ambiguous $3 still holds its cap share: 3000 rent + 1200 appreciation count, so $2 is left, not $5.
        var last = rents().reserveAppreciation(DAY, List.of(property(fifth)), limits, TOP);
        assertEquals(List.of(200L), last.stream().map(Reserved::cents).toList());
        assertEquals(1, count(APPRECIATION + " AND state='AMBIGUOUS'"), "still held, never retried");
    }

    @Test
    void heldPausedOwnerlessReserveOwnedOrUnratedPropertiesNeverGetAppreciation() throws Exception {
        var limits = limits(15000, 4500, 1500);
        var rent = rents().reserve(DAY, enabled(), limits);
        assertEquals(4, rent.size());
        UUID unrated = properties.get(0), paused = properties.get(1), deferred = properties.get(2), ambiguous = properties.get(3);
        for (var row : rent) {
            UUID id = row.property().propertyId();
            if (id.equals(unrated) || id.equals(paused)) assertTrue(pay(row));
            if (id.equals(ambiguous)) {
                Handle handle = new Handle(UUID.randomUUID(), row.eventId(), UUID.randomUUID(), "e".repeat(64));
                assertTrue(journal().beginDebit(handle).newlyStarted());
                journal().recordDebit(handle, new Observation(true, true, 6.0, 100000.0, 99994.0, false));
                assertEquals(1, journal().recoverOtherRuns(UUID.randomUUID(), 100));
            }
            // The deferred property's rent stays PENDING, as after a reserve-floor deferral.
        }
        execute("UPDATE npc_properties SET enabled=FALSE WHERE property_id=?", paused);
        closeDay();
        AppreciationScore onlyRated = (c, p, at) -> p.propertyId().equals(unrated) ? OptionalDouble.empty() : OptionalDouble.of(100.0);
        assertTrue(rents().reserveAppreciation(DAY, enabled(), limits, onlyRated).isEmpty());
        var first = enabled().getFirst();
        var reserveOwned = new Property(first.propertyId(), WORLD, first.regionId(), "test", "room", 4, 1000, 1000, RESERVE, 0, 80, true);
        var ownerless = new Property(first.propertyId(), WORLD, first.regionId(), "test", "room", 4, 1000, 1000, null, 0, 80, true);
        assertTrue(rents().reserveAppreciation(DAY, List.of(reserveOwned), limits, TOP).isEmpty());
        assertTrue(rents().reserveAppreciation(DAY, List.of(ownerless), limits, TOP).isEmpty());
        assertEquals(0, count("npc_appreciation_reservations"));
        assertEquals(0, count(APPRECIATION));
        // Positive control: the same gate admits a rated, enabled property whose rent is proved paid.
        execute("UPDATE npc_properties SET enabled=TRUE WHERE property_id=?", paused);
        var admitted = rents().reserveAppreciation(DAY, enabled(), limits, TOP);
        assertEquals(List.of(unrated, paused).stream().sorted(Comparator.comparing(UUID::toString)).toList(),
                admitted.stream().map(r -> r.property().propertyId()).toList());
    }

    @Test
    void productionScorerPaysNothingWithoutARecordedRating() throws Exception {
        var limits = limits(15000, 4500, 1500);
        assertEquals(4, payRent(limits));
        closeDay();
        var scorer = rents().ratedScore();
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try { assertEquals(OptionalDouble.empty(), scorer.score(c, enabled().getFirst(), clock.instant())); }
            finally { c.rollback(); }
        }
        assertTrue(rents().reserveAppreciation(DAY, enabled(), limits, scorer).isEmpty(), "no invented history");
        assertEquals(0, count("npc_appreciation_reservations"));
    }

    @Test
    void accountingProjectionPublishesAppreciationAsItsOwnSource() throws Exception {
        var limits = limits(15000, 4500, 1500);
        assertEquals(4, payRent(limits));
        closeDay();
        for (var row : rents().reserveAppreciation(DAY, enabled(), limits, TOP)) assertTrue(pay(row));
        // Rent settles on DAY; DAY's appreciation is paid on the next economy day and is reported then.
        execute("UPDATE npc_economy_events SET finalized_at=? WHERE source='npc-property-rent'", NOW.plusSeconds(60));
        execute("UPDATE npc_economy_events SET finalized_at=? WHERE source='npc-property-appreciation'", NEXT_NOW.plusSeconds(60));
        AccountingProjection.Snapshot snapshot;
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                snapshot = AccountingProjection.readSnapshot(c, journal(), new CommerceOrderRepository(database, RESERVE), RESERVE,
                        1, NEXT_NOW.plusSeconds(3600), ZONE, System.nanoTime());
            } finally { c.rollback(); }
        }
        var group = snapshot.groups().stream().filter(g -> g.day().equals(DAY.toString()) && g.category().equals("xfer-to-public"))
                .findFirst().orElseThrow();
        assertTrue(group.sources().contains(new AccountingProjection.SourceTotal("npc-property-rent", 2400)));
        assertTrue(group.sources().contains(new AccountingProjection.SourceTotal("npc-property-appreciation", 0)));
        var next = snapshot.groups().stream().filter(g -> g.day().equals(NEXT.toString()) && g.category().equals("xfer-to-public"))
                .findFirst().orElseThrow();
        assertTrue(next.sources().contains(new AccountingProjection.SourceTotal("npc-property-rent", 0)));
        assertTrue(next.sources().contains(new AccountingProjection.SourceTotal("npc-property-appreciation", 1200)));
        var items = snapshot.receipts().stream().filter(i -> i.source().equals("npc-property-appreciation")).toList();
        assertEquals(4, items.size());
        for (var item : items) { assertEquals(RESERVE, item.payer()); assertEquals(300, item.cents()); }
    }

    /**
     * Regression: the staff cap alert's read went through an autocommit connection, so any historical
     * FAILED payout made the proof reader throw and SERVER_CAP_NEAR went quiet for good.
     */
    @Test
    void serverCapReadSurvivesAHistoricalProvedDeclinedPayout() throws Exception {
        var limits = limits(15000, 4500, 1500);
        var rent = rents().reserve(DAY, enabled(), limits);
        assertEquals(4, rent.size());
        var declined = rent.getFirst();
        Handle handle = new Handle(UUID.randomUUID(), declined.eventId(), UUID.randomUUID(), "f".repeat(64));
        assertTrue(journal().beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.DECLINED, journal().recordDebit(handle,
                new Observation(true, false, 6.0, 100000.0, 100000.0, false)).state());
        assertEquals(1, count("npc_economy_events WHERE event_id=? AND state='FAILED'", declined.eventId()));
        for (var row : rent.subList(1, rent.size())) assertTrue(pay(row));
        var before = snapshot();
        // The proved decline releases its share: three $6 rents count, not four.
        assertEquals(1800, rents().serverSpentCents(DAY));
        assertEquals(before, snapshot(), "the cap read is rollback-only and writes nothing");
        // The next economy day: the FAILED row is now historical but is still scanned as outstanding.
        closeDay();
        var later = rent.get(1).property();
        UtilityRepository.Receipt utility = utilities().preparePayment(UUID.randomUUID(), later.propertyId(),
                later.ownerUuid(), 0, NEXT).receipt();
        payUtility(utility, clock.instant().plusSeconds(1));
        assertEquals(UtilityRepository.State.PAID, utilities().reconcile(utility.eventId()).orElseThrow().state());
        var nextRent = rents().reserve(NEXT, List.of(property(later.propertyId())), limits);
        assertEquals(List.of(600L), nextRent.stream().map(Reserved::cents).toList());
        assertTrue(pay(nextRent.getFirst()));
        assertEquals(600, rents().serverSpentCents(NEXT), "only today's paid rent counts");
        assertEquals(1800, rents().serverSpentCents(DAY));
        assertEquals(1, count("npc_economy_events WHERE event_id=? AND state='FAILED'", declined.eventId()),
                "the failed row is kept, never erased");
        // The worker half of the alert: 600 of a $6.50 cap is 92 percent, so it must fire.
        var alerts = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        var warned = new java.util.concurrent.atomic.AtomicReference<LocalDate>();
        assertTrue(UtilityPaymentRuntime.serverCapCheck(() -> rents().serverSpentCents(NEXT), 650, NEXT,
                alerts::add, warnings::add, warned));
        assertEquals(List.of("Server daily payout cap is 92 percent used."), alerts);
        assertTrue(warnings.isEmpty());
        // Negative control: below 90 percent nothing is raised.
        assertFalse(UtilityPaymentRuntime.serverCapCheck(() -> rents().serverSpentCents(NEXT), 15000, NEXT,
                alerts::add, warnings::add, warned));
        assertEquals(1, alerts.size());
    }

    @Test
    void anUnprovedFailedPayoutStillHoldsItsServerCapShare() throws Exception {
        var limits = limits(15000, 4500, 1500);
        var rent = rents().reserve(DAY, enabled(), limits);
        assertEquals(4, rent.size());
        // A FAILED ledger label with no journal attempt proves no movement, so it keeps its cap share.
        execute("UPDATE npc_economy_events SET state='FAILED' WHERE event_id=?", rent.getFirst().eventId());
        for (var row : rent.subList(1, rent.size())) assertTrue(pay(row));
        assertEquals(2400, rents().serverSpentCents(DAY));
        closeDay();
        assertEquals(0, rents().serverSpentCents(NEXT), "a held FAILED row from another day is not today's spend");
    }

    @Test
    void rentLedgerReadRefusesAnAutocommitConnectionEvenWithoutFailedRows() throws Exception {
        var limits = limits(15000, 4500, 1500);
        assertEquals(4, payRent(limits));
        assertEquals(0, count("npc_economy_events WHERE state='FAILED'"));
        var spent = RentReservationRepository.class.getDeclaredMethod("spent", Connection.class, LocalDate.class);
        spent.setAccessible(true);
        try (Connection c = database.getConnection()) {
            assertTrue(c.getAutoCommit());
            var thrown = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> spent.invoke(rents(), c, DAY));
            assertInstanceOf(SQLException.class, thrown.getCause());
            assertEquals("Rent ledger read requires caller-owned transaction", thrown.getCause().getMessage());
        }
        // Positive control: the same read inside a caller-owned transaction.
        assertEquals(2400, rents().serverSpentCents(DAY));
    }

    /** DAY closes: the clock moves to the same time on the next economy day. */
    private void closeDay() { clock.set(NEXT_NOW); }

    /** Mirrors payAppreciation on the next day's batch: reserve the closed day, then pay what is queued. */
    private Supplier<CompletableFuture<Integer>> appreciationPhase(EconomyLimits limits) {
        return () -> {
            try {
                LocalDate closed = UtilityPaymentRuntime.appreciationDay(NEXT);
                assertEquals(DAY, closed);
                rents().reserveAppreciation(closed, enabled(), limits, TOP);
                int paid = 0;
                for (var row : rents().pendingAppreciation(closed, enabled())) if (pay(row)) paid++;
                return CompletableFuture.completedFuture(paid);
            } catch (SQLException failure) { throw new IllegalStateException(failure); }
        };
    }

    private static Property property(UUID id) throws SQLException {
        return new PropertyRepository(database).findById(id).orElseThrow();
    }

    /** Mirrors the rent half of requestRentBatch with unlimited reserve balance. */
    private int payRent(EconomyLimits limits) throws SQLException {
        rents().reserve(DAY, enabled(), limits);
        int paid = 0;
        for (var row : rents().pending(DAY, enabled())) if (pay(row)) paid++;
        return paid;
    }

    /** Mirrors payRent's pre-dispatch floor deferral against a simulated reserve balance. */
    private int dispatch(List<Reserved> rows, long[] balance) throws SQLException {
        int paid = 0;
        for (var row : rows) {
            if (!UtilityPaymentRuntime.floorAllows(balance[0], row.cents(), FLOOR)) continue;
            assertTrue(pay(row));
            balance[0] -= row.cents();
            paid++;
        }
        return paid;
    }

    /** One complete reserve-to-owner journaled transfer, as the runtime records it. */
    private static boolean pay(Reserved row) throws SQLException {
        Handle handle = new Handle(UUID.randomUUID(), row.eventId(), UUID.randomUUID(), "b".repeat(64));
        var start = journal().beginDebit(handle);
        if (!start.newlyStarted()) return false;
        assertEquals(RESERVE, start.receipt().payer());
        assertEquals(row.property().ownerUuid(), start.receipt().payee());
        assertEquals(row.cents(), start.receipt().cents());
        double dollars = row.cents() / 100.0;
        assertEquals(TransferJournal.State.CREDIT_READY, journal().recordDebit(handle,
                new Observation(true, true, dollars, 100000.0, 100000.0 - dollars, false)).state());
        assertTrue(journal().beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID, journal().recordCredit(handle,
                new Observation(true, true, dollars, 50.0, 50.0 + dollars, false)).state());
        return true;
    }

    private List<Property> enabled() {
        var repo = new PropertyRepository(database);
        return properties.stream().map(id -> {
            try { return repo.findById(id).orElseThrow(); }
            catch (SQLException failure) { throw new IllegalStateException(failure); }
        }).toList();
    }

    private static EconomyLimits limits(long server, long owner, long property) throws SQLException {
        try {
            var constructor = EconomyLimits.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            // Shipped tenant $6, appreciation $3, floor $35,000, seed $50, pool $500, ratio 0.9.
            return (EconomyLimits) constructor.newInstance(server, owner, property, 600L, 300L, FLOOR, 5000L, 50000L, 0.9);
        } catch (ReflectiveOperationException failure) { throw new SQLException(failure); }
    }

    private RentReservationRepository rents() { return new RentReservationRepository(database, RESERVE, clock, ZONE, RESET, false); }
    private UtilityRepository utilities() { return new UtilityRepository(database, RESERVE, clock, ZONE, RESET); }
    private static TransferJournal journal() { return new TransferJournal(database, RESERVE); }

    /** Enrolled, one tenant, today's utility verified paid. */
    private UUID operating(UUID owner) throws SQLException {
        UUID id = UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(id, WORLD, "appr-" + id, "test", "room",
                4, 1000, 1000, owner, 0, 80, true), 5000);
        UUID tenant = UUID.randomUUID();
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(tenant, "Mara Vale")));
        new TenantRepository(database).insert(new Tenant(tenant, "Mara Vale", id, 0, "STANDARD", "COOK", 50));
        UtilityRepository.Receipt utility = utilities().preparePayment(UUID.randomUUID(), id, owner, 0, DAY).receipt();
        payUtility(utility, clock.instant().plusSeconds(1));
        assertEquals(UtilityRepository.State.PAID, utilities().reconcile(utility.eventId()).orElseThrow().state());
        return id;
    }

    /** Real journal proofs, then fixture chronology pinned the same way RentIsolationIT does. */
    private void payUtility(UtilityRepository.Receipt receipt, Instant at) throws SQLException {
        Handle handle = new Handle(UUID.randomUUID(), receipt.eventId(), UUID.randomUUID(), "a".repeat(64));
        double dollars = receipt.bill().cents() / 100.0;
        assertTrue(journal().beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.CREDIT_READY,
                journal().recordDebit(handle, new Observation(true, true, dollars, 1000.0, 1000.0 - dollars, false)).state());
        assertTrue(journal().beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID,
                journal().recordCredit(handle, new Observation(true, true, dollars, 2000.0, 2000.0 + dollars, false)).state());
        execute("UPDATE npc_economy_events SET created_at=?,claimed_at=?,finalized_at=? WHERE event_id=?",
                receipt.bill().issuedAt(), at.minusMillis(4), at, receipt.eventId());
        execute("UPDATE npc_transfer_attempts SET created_at=?,credit_intent_at=?,updated_at=? WHERE event_id=?",
                at.minusMillis(4), at.minusMillis(2), at, receipt.eventId());
        execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                        + "SET o.observed_at=CASE WHEN o.leg='DEBIT' THEN ? ELSE ? END WHERE a.event_id=?",
                at.minusMillis(3), at.minusMillis(1), receipt.eventId());
        clock.set(at.plusMillis(1));
    }

    private static List<List<String>> snapshot() throws SQLException {
        var result = new ArrayList<List<String>>();
        for (String table : List.of("npc_appreciation_reservations", "npc_rent_reservations", "npc_rent_cohort",
                "npc_economy_events", "npc_transfer_attempts", "npc_transfer_observations", "npc_property_funding")) {
            result.add(List.of(table));
            var contents = rows("SELECT * FROM " + table);
            contents.sort(Comparator.comparing(Object::toString));
            result.addAll(contents);
        }
        return result;
    }

    private static long count(String tableAndWhere, Object... args) throws SQLException {
        return Long.parseLong(rows("SELECT COUNT(*) FROM " + tableAndWhere, args).getFirst().getFirst());
    }

    private static long sum(String tableAndWhere, Object... args) throws SQLException {
        return Long.parseLong(rows("SELECT COALESCE(SUM(requested_amount_cents),0) FROM " + tableAndWhere, args).getFirst().getFirst());
    }

    private static List<List<String>> rows(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<List<String>> result = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        int type = rs.getMetaData().getColumnType(i);
                        Object value = type == Types.TIMESTAMP ? rs.getObject(i, LocalDateTime.class) : rs.getObject(i);
                        row.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value == null ? null : value.toString());
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }

    private static void execute(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection()) { execute(c, sql, args); }
    }

    private static void execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, args);
            ps.execute();
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else if (args[i] instanceof Instant at) ps.setObject(i + 1, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
            else if (args[i] instanceof LocalDate day) ps.setString(i + 1, day.toString());
            else ps.setObject(i + 1, args[i]);
        }
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = AppreciationIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void set(Instant at) { now = at; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            return new Clock() {
                @Override public ZoneId getZone() { return zone; }
                @Override public Clock withZone(ZoneId next) { return MutableClock.this.withZone(next); }
                @Override public Instant instant() { return now; }
            };
        }
        @Override public Instant instant() { return now; }
    }
}
