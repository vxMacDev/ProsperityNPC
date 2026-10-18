package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.market.HousingMoveRepository;
import com.prosperitysmp.npc.market.HousingMoveRepository.Receipt;
import com.prosperitysmp.npc.market.HousingMoveRequest;
import com.prosperitysmp.npc.market.HousingMoveRequest.Policy;
import com.prosperitysmp.npc.market.HousingPolicy;
import com.prosperitysmp.npc.market.HousingRegistry;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.RetentionAssessment;
import com.prosperitysmp.npc.property.RetentionAssessment.Case;
import com.prosperitysmp.npc.property.RetentionAssessment.Outcome;
import com.prosperitysmp.npc.property.RetentionRepository;
import com.prosperitysmp.npc.property.TenancyDeparture;
import com.prosperitysmp.npc.property.TenantRepository;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real, isolated MariaDB lifecycle evidence; no runtime, Vault, or fabricated normal-path admissions. */
@Timeout(45)
class RetentionRepositoryIT {
    private static final Instant NOW = Instant.parse("2026-09-27T17:00:00.123Z");
    private static final Instant FROM = NOW.minus(Duration.ofDays(30));
    private static final Instant ADMITTED = NOW.minus(Duration.ofDays(10));
    private static final int HORIZON = 7;
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final int RESET = 4;
    private static final UUID WORLD = new UUID(0, 2800);
    private static final UUID OWNER = new UUID(0, 2801);
    private static final UUID BUYER = new UUID(0, 2802);
    private static final Policy POLICY = new Policy(512, 512, 4, 1);
    private static final HousingPolicy HOUSING = new HousingPolicy(
            WORLD, "downtown", "room", HousingPolicy.Tier.STANDARD, 1200, 40);
    private static DB mariaDb;
    private static Database database;
    private static List<String> tables;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            String schema = "npc_retention_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            Field migrations = MigrationRunner.class.getDeclaredField("MIGRATIONS");
            migrations.setAccessible(true);
            boolean registered = false;
            for (Object migration : (List<?>) migrations.get(null)) {
                Method version = migration.getClass().getDeclaredMethod("version");
                Method resource = migration.getClass().getDeclaredMethod("resource");
                version.setAccessible(true);
                resource.setAccessible(true);
                int number = (Integer) version.invoke(migration);
                if (number > 15 && number != 45) continue;
                applyResource("/" + resource.invoke(migration));
                if (number == 15) registered = true;
            }
            assertTrue(registered, "V15 must be registered, not just present on disk");
            tables = rows("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME").stream().map(List::getFirst).toList();
            assertTrue(tables.containsAll(List.of("npc_housing_moves", "npc_tenancy_departures")));
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
    void cleanAllTables() throws SQLException {
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION foreign_key_checks=0");
            try { for (String table : tables) execute(c, "DELETE FROM " + table); }
            finally { execute(c, "SET SESSION foreign_key_checks=1"); }
        }
        execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (1)");
    }

    @Test
    void emptyAndUnrelatedOwnersAreUnknownWithoutWritingAnyTable() throws SQLException {
        RetentionAssessment empty = read(OWNER);
        assertEquals(OWNER, empty.ownerId());
        assertEquals(NOW, empty.capturedAt());
        assertEquals(FROM, empty.admittedFrom());
        assertEquals(HORIZON, empty.horizonDays());
        assertTrue(empty.cases().isEmpty());
        assertEquals(0, empty.maturedCount());
        assertTrue(empty.retentionRate().isEmpty());
        admit(ADMITTED);
        assertTrue(read(BUYER).cases().isEmpty());
        assertTrue(read(BUYER).retentionRate().isEmpty());
    }

    @Test
    void unchangedOccupancyRevisionProvesRetentionAndRepeatedReadsAreIdentical() throws SQLException {
        Receipt admission = admit(ADMITTED);
        RetentionAssessment result = read(OWNER);
        Case retained = only(result, admission, Outcome.RETAINED);
        assertNull(retained.departure());
        assertNull(retained.handoverAt());
        assertEquals(new RetentionAssessment.Occupancy(admission.request().propertyId(),
                admission.request().slot(), admission.tenancyRevisionAfter()), retained.occupancy());
        assertEquals(1, result.maturedCount());
        assertEquals(1.0, result.retentionRate().orElseThrow(), 0.0);
        assertEquals(result, read(OWNER));
    }

    @Test
    void realSuccessorUnderAnotherOwnerPassesButCannotPrecedeTheOriginalDeparture() throws SQLException {
        Receipt admission = admit(ADMITTED);
        TenancyDeparture departure = depart(admission, ADMITTED.plus(Duration.ofDays(1)));
        UUID nextHome = enroll(ADMITTED.minusSeconds(1));
        new PropertyRepository(database).applyOwnerChange(nextHome, OWNER, BUYER, 1, "successor IT fixture");
        execute("UPDATE npc_property_ownership SET observed_at=? WHERE property_id=? AND ownership_epoch=1", ADMITTED, nextHome);
        Instant movedAt = ADMITTED.plus(Duration.ofDays(2));
        HousingMoveRepository moves = new HousingMoveRepository(database, fixed(movedAt), ZONE, RESET);
        var successor = moves.apply(moves.quote(UUID.randomUUID(), admission.request().residentId(), nextHome, 0, day(movedAt)), POLICY);
        assertTrue(successor.newlyApplied());
        assertEquals(BUYER, successor.receipt().request().ownerId());
        RetentionAssessment result = read(OWNER);
        assertEquals(departure, only(result, admission, Outcome.DEPARTED).departure());
        assertEquals(1, result.maturedCount());
        assertEquals(0.0, result.retentionRate().orElseThrow(), 0.0);
        only(read(BUYER), successor.receipt(), Outcome.RETAINED);

        TenancyDeparture overlapping = new TenancyDeparture(departure.residentId(), departure.propertyId(),
                departure.ownerAtDeparture(), departure.ownershipEpoch(), departure.occupancyRevisionBefore(), departure.slot(),
                ADMITTED.plus(Duration.ofDays(8)), departure.cause(), departure.purchaseEventId());
        execute("UPDATE npc_tenancy_departures SET departed_at=?,payload_sha256=? WHERE resident_id=? AND occupancy_revision_before=?",
                overlapping.departedAt(), overlapping.fingerprint(), overlapping.residentId(), overlapping.occupancyRevisionBefore());
        rejected("Retention departure overlaps successor admission", () -> read(OWNER));
    }

    @Test
    void immatureEarlyDepartureWaitsForTheSameHorizonAndNeverEntersTheRateEarly() throws SQLException {
        Receipt immature = admit(NOW.minus(Duration.ofDays(2)));
        depart(immature, NOW.minus(Duration.ofDays(1)));
        RetentionAssessment alone = read(OWNER);
        only(alone, immature, Outcome.NOT_MATURED);
        assertEquals(0, alone.maturedCount());
        assertTrue(alone.retentionRate().isEmpty());
        Receipt retained = admit(ADMITTED);
        RetentionAssessment mixed = read(OWNER);
        assertEquals(2, mixed.cases().size());
        assertEquals(1, mixed.count(Outcome.NOT_MATURED));
        assertEquals(1, mixed.maturedCount());
        assertEquals(1.0, mixed.retentionRate().orElseThrow(), 0.0);
        assertEquals(retained, mixed.cases().getFirst().admission());
    }

    @Test
    void maturityUsesTheExactElapsedHorizonRatherThanEconomyDayRollover() throws SQLException {
        Receipt admission = admit(ADMITTED);
        Instant deadline = ADMITTED.plus(Duration.ofDays(HORIZON));
        RetentionAssessment early = read(OWNER, deadline.minusMillis(1), FROM, HORIZON);
        only(early, admission, Outcome.NOT_MATURED);
        assertTrue(early.retentionRate().isEmpty());
        RetentionAssessment exact = read(OWNER, deadline, FROM, HORIZON);
        only(exact, admission, Outcome.RETAINED);
        assertEquals(1.0, exact.retentionRate().orElseThrow(), 0.0);
    }

    @ParameterizedTest
    @CsvSource({"-1,DEPARTED", "0,RETAINED", "1,RETAINED"})
    void departureAtTheHorizonIsRetainedWithMillisecondEdges(long offset, Outcome expected) throws SQLException {
        Receipt admission = admit(ADMITTED);
        TenancyDeparture departure = depart(admission, ADMITTED.plus(Duration.ofDays(HORIZON)).plusMillis(offset));
        RetentionAssessment result = read(OWNER);
        assertEquals(departure, only(result, admission, expected).departure());
        assertEquals(expected == Outcome.RETAINED ? 1.0 : 0.0, result.retentionRate().orElseThrow(), 0.0);
    }

    @Test
    void laterDepartureAndSaleCannotTransferTheOriginalOwnersMaturedCredit() throws SQLException {
        Receipt admission = admit(ADMITTED);
        Instant sold = ADMITTED.plus(Duration.ofDays(HORIZON)).plusMillis(1);
        ownerChange(admission, OWNER, BUYER, 1, sold);
        TenancyDeparture departure = depart(admission, sold.plus(Duration.ofDays(1)));
        assertEquals(BUYER, departure.ownerAtDeparture());
        RetentionAssessment result = read(OWNER);
        Case retained = only(result, admission, Outcome.RETAINED);
        assertEquals(departure, retained.departure());
        assertEquals(sold, retained.handoverAt());
        assertEquals(1.0, result.retentionRate().orElseThrow(), 0.0);
        assertTrue(read(BUYER).cases().isEmpty());
        ownerChange(admission, BUYER, OWNER, 2, sold.plus(Duration.ofDays(2)));
        only(read(OWNER), admission, Outcome.RETAINED);
    }

    @Test
    void saleAfterAnEarlyDepartureCannotEraseTheOriginalOwnersFailure() throws SQLException {
        Receipt admission = admit(ADMITTED);
        TenancyDeparture departure = depart(admission, ADMITTED.plus(Duration.ofDays(1)));
        ownerChange(admission, OWNER, BUYER, 1, ADMITTED.plus(Duration.ofDays(2)));
        RetentionAssessment result = read(OWNER);
        assertEquals(departure, only(result, admission, Outcome.DEPARTED).departure());
        assertEquals(0.0, result.retentionRate().orElseThrow(), 0.0);
        assertTrue(read(BUYER).cases().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void handoverBeforeOutcomeRequiresReviewEvenWhenOwnershipReturns(boolean returns) throws SQLException {
        Receipt admission = admit(ADMITTED);
        Instant sold = ADMITTED.plus(Duration.ofDays(1));
        ownerChange(admission, OWNER, BUYER, 1, sold);
        if (returns) ownerChange(admission, BUYER, OWNER, 2, sold.plusSeconds(1));
        depart(admission, sold.plus(Duration.ofDays(1)));
        RetentionAssessment result = read(OWNER);
        assertEquals(sold, only(result, admission, Outcome.HANDOVER_REVIEW).handoverAt());
        assertEquals(1, result.maturedCount());
        assertTrue(result.retentionRate().isEmpty());
        assertTrue(read(BUYER).cases().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"0,HANDOVER_REVIEW", "1,RETAINED"})
    void handoverExactlyAtTheHorizonIsNotAnUnqualifiedSuccess(long offset, Outcome expected) throws SQLException {
        Receipt admission = admit(ADMITTED);
        ownerChange(admission, OWNER, BUYER, 1, ADMITTED.plus(Duration.ofDays(HORIZON)).plusMillis(offset));
        RetentionAssessment result = read(OWNER);
        only(result, admission, expected);
        assertEquals(expected == Outcome.HANDOVER_REVIEW, result.retentionRate().isEmpty());
    }

    @Test
    void missingDepartureAndAdvancedRevisionAreAGapEvenAfterReturnToTheSameSlot() throws SQLException {
        Receipt original = admit(ADMITTED);
        depart(original, ADMITTED.plus(Duration.ofDays(1)));
        Receipt returned = admitAgain(original, ADMITTED.plus(Duration.ofDays(2)));
        execute("DELETE FROM npc_tenancy_departures WHERE resident_id=?", original.request().residentId());
        RetentionAssessment result = read(OWNER);
        assertEquals(2, result.cases().size());
        assertEquals(original, result.cases().getFirst().admission());
        assertEquals(Outcome.EVIDENCE_GAP, result.cases().getFirst().outcome());
        assertEquals(returned, result.cases().getLast().admission());
        assertEquals(Outcome.RETAINED, result.cases().getLast().outcome());
        assertEquals(2, result.maturedCount());
        assertTrue(result.retentionRate().isEmpty(), "a gap must not selectively shrink the denominator");
    }

    @Test
    void missingOwnershipHistoryOrInitialEpochMakesTheWholeMaturedRateUnknown() throws SQLException {
        Receipt admission = admit(ADMITTED);
        admit(ADMITTED.plusSeconds(1));
        execute("DELETE FROM npc_property_ownership WHERE property_id=?", admission.request().propertyId());
        RetentionAssessment missing = read(OWNER);
        assertEquals(1, missing.count(Outcome.EVIDENCE_GAP));
        assertEquals(1, missing.count(Outcome.RETAINED));
        assertTrue(missing.retentionRate().isEmpty());
        ownerChange(admission, OWNER, OWNER, 1, ADMITTED.plusSeconds(2));
        RetentionAssessment incomplete = read(OWNER);
        assertEquals(1, incomplete.count(Outcome.EVIDENCE_GAP));
        assertTrue(incomplete.retentionRate().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"chain-owner", "chronology", "current-tip", "duplicate-epoch"})
    void malformedOwnershipChainAndCurrentTipRejectTheAssessment(String fault) throws SQLException {
        Receipt admission = admit(ADMITTED);
        ownerChange(admission, OWNER, BUYER, 1, ADMITTED.plusSeconds(10));
        UUID home = admission.request().propertyId();
        switch (fault) {
            case "chain-owner" -> execute("UPDATE npc_property_ownership SET old_owner_uuid=? WHERE property_id=? AND ownership_epoch=1", BUYER, home);
            case "chronology" -> execute("UPDATE npc_property_ownership SET observed_at=? WHERE property_id=? AND ownership_epoch=1", ADMITTED.minusSeconds(2), home);
            case "current-tip" -> execute("UPDATE npc_properties SET owner_uuid=?,ownership_epoch=2 WHERE property_id=?", OWNER, home);
            case "duplicate-epoch" -> execute("INSERT INTO npc_property_ownership(property_id,old_owner_uuid,new_owner_uuid,source_snapshot,ownership_epoch,observed_at) "
                    + "SELECT property_id,old_owner_uuid,new_owner_uuid,source_snapshot,ownership_epoch,observed_at FROM npc_property_ownership "
                    + "WHERE property_id=? AND ownership_epoch=1", home);
            default -> throw new AssertionError(fault);
        }
        rejected("ownership", () -> read(OWNER));
    }

    @Test
    void admissionAndDepartureHashesRejectIncludingCandidatesBeforeTheExactLowerCutoff() throws SQLException {
        Receipt admission = admit(ADMITTED);
        execute("UPDATE npc_housing_moves SET payload_hash=? WHERE request_id=?", "0".repeat(64), admission.request().requestId());
        rejected("binding", () -> read(OWNER));
        rejected("binding", () -> read(OWNER, NOW, ADMITTED.plusMillis(1), HORIZON));
        execute("UPDATE npc_housing_moves SET payload_hash=? WHERE request_id=?",
                admission.request().fingerprint(POLICY), admission.request().requestId());
        depart(admission, ADMITTED.plusSeconds(1));
        execute("UPDATE npc_tenancy_departures SET payload_sha256=? WHERE resident_id=?", "0".repeat(64), admission.request().residentId());
        rejected("fingerprint", () -> read(OWNER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"slot", "property", "before-admission", "future", "owner", "epoch"})
    void correctlyHashedDepartureStillMustBindToAdmissionTimeSlotAndOwnership(String fault) throws SQLException {
        Receipt admission = admit(ADMITTED);
        TenancyDeparture original = depart(admission, ADMITTED.plus(Duration.ofDays(2)));
        UUID home = fault.equals("property") ? enroll(ADMITTED) : original.propertyId();
        Instant at = switch (fault) {
            case "before-admission" -> ADMITTED.minusMillis(1);
            case "future" -> NOW.plusMillis(1);
            default -> original.departedAt();
        };
        TenancyDeparture invalid = new TenancyDeparture(original.residentId(), home,
                fault.equals("owner") ? BUYER : original.ownerAtDeparture(),
                fault.equals("epoch") ? 1 : original.ownershipEpoch(), original.occupancyRevisionBefore(),
                fault.equals("slot") ? 1 : original.slot(), at, original.cause(), original.purchaseEventId());
        execute("UPDATE npc_tenancy_departures SET property_id=?,owner_at_departure=?,ownership_epoch=?,slot=?,departed_at=?,payload_sha256=? "
                        + "WHERE resident_id=? AND occupancy_revision_before=?", invalid.propertyId(), invalid.ownerAtDeparture(),
                invalid.ownershipEpoch(), invalid.slot(), invalid.departedAt(), invalid.fingerprint(),
                invalid.residentId(), invalid.occupancyRevisionBefore());
        rejected("departure", () -> read(OWNER));
    }

    @Test
    void duplicateEpisodeAcrossDifferentDaysAndRequestIdsRejectsEvenWithValidHashes() throws SQLException {
        Receipt admission = admit(ADMITTED);
        LocalDate nextDay = admission.request().day().plusDays(1);
        pinDay(nextDay);
        HousingMoveRequest r = admission.request();
        HousingMoveRequest duplicate = new HousingMoveRequest(UUID.randomUUID(), r.residentId(), r.propertyId(),
                r.ownerId(), r.worldId(), r.marketId(), r.classKey(), r.ownershipEpoch(), r.propertyRevision(),
                r.membershipRevision(), r.tenancyRevision(), r.slot(), nextDay);
        try (Connection c = database.getConnection()) { insertReceipt(c, duplicate, ADMITTED.plus(Duration.ofDays(1))); }
        rejected("Duplicate retention episode", () -> read(OWNER));
    }

    @Test
    void futureAdmissionInTheSameEconomyDayAndFutureOwnershipObservationFailClosed() throws SQLException {
        Receipt admission = admit(NOW.plusMillis(1));
        rejected("housing receipt outcome", () -> read(OWNER));
        execute("UPDATE npc_housing_moves SET applied_at_millis=? WHERE request_id=?", NOW.toEpochMilli(), admission.request().requestId());
        only(read(OWNER), new Receipt(admission.request(), POLICY, admission.tenancyRevisionAfter(),
                admission.cooldownUntil(), NOW), Outcome.NOT_MATURED);
        execute("UPDATE npc_property_ownership SET observed_at=? WHERE property_id=?", NOW.plusMillis(1), admission.request().propertyId());
        rejected("future", () -> read(OWNER));
    }

    @Test
    void cohortIncludesBothExactEndpointsAndExcludesTheLowerMillisecondNeighbor() throws SQLException {
        Receipt before = admit(FROM.minusMillis(1));
        Receipt first = admit(FROM);
        Receipt last = admit(NOW);
        RetentionAssessment result = read(OWNER);
        assertEquals(List.of(first, last), result.cases().stream().map(Case::admission).toList());
        assertFalse(result.cases().stream().anyMatch(c -> c.admission().equals(before)));
        assertEquals(1, result.count(Outcome.RETAINED));
        assertEquals(1, result.count(Outcome.NOT_MATURED));
        assertEquals(1.0, result.retentionRate().orElseThrow(), 0.0);
        only(read(OWNER, NOW, FROM.plusMillis(1), HORIZON), last, Outcome.NOT_MATURED);
    }

    @Test
    void invalidWindowsHorizonsAndNonMillisecondOrOutOfRangeTimesRejectWithoutWrites() throws SQLException {
        for (Instant from : List.of(NOW, NOW.plusMillis(1), FROM.minusMillis(1), FROM.plusNanos(1)))
            rejected("retention", () -> read(OWNER, NOW, from, HORIZON));
        for (int horizon : List.of(0, -1, 31, Integer.MAX_VALUE))
            rejected("retention", () -> read(OWNER, NOW, FROM, horizon));
        for (Instant capture : List.of(Instant.parse("0000-12-31T23:59:59Z"), Instant.parse("+10000-01-01T00:00:00Z")))
            rejected("retention", () -> read(OWNER, capture, capture.minusMillis(1), 1));
        assertTrue(read(OWNER, NOW, NOW.minusMillis(1), 1).retentionRate().isEmpty());
        assertTrue(read(OWNER, NOW, FROM, 30).retentionRate().isEmpty());
    }

    @Test
    void missingPinnedPolicyAndMalformedAdmissionOutcomesCannotBecomeAnEmptySuccess() throws SQLException {
        Receipt admission = admit(ADMITTED);
        unchecked("DELETE FROM npc_housing_days WHERE economy_day=?", admission.request().day());
        rejected("retention", () -> read(OWNER));
        pinDay(admission.request().day());
        unchecked("UPDATE npc_housing_moves SET slot=4 WHERE request_id=?", admission.request().requestId());
        rejected("housing move quote", () -> read(OWNER));
        execute("UPDATE npc_housing_moves SET slot=? WHERE request_id=?", admission.request().slot(), admission.request().requestId());
        execute("UPDATE npc_housing_moves SET applied_at_millis=? WHERE request_id=?", Long.MAX_VALUE, admission.request().requestId());
        rejected("housing receipt outcome", () -> read(OWNER));
    }

    @Test
    @Timeout(90)
    void actual4097RowCohortRejectsRatherThanReturningTheFirst4096() throws SQLException {
        Receipt seed = admit(NOW.minus(Duration.ofDays(20)));
        cloneAdmissions(seed, 1, 4095);
        assertEquals("4096", rows("SELECT COUNT(*) FROM npc_housing_moves").getFirst().getFirst());
        RetentionAssessment full = read(OWNER);
        assertEquals(4096, full.cases().size());
        assertEquals(4096, full.count(Outcome.RETAINED));
        assertEquals(1.0, full.retentionRate().orElseThrow(), 0.0);
        cloneAdmissions(seed, 4096, 4096);
        assertEquals("4097", rows("SELECT COUNT(*) FROM npc_housing_moves").getFirst().getFirst());
        rejected("cohort exceeds bound", () -> read(OWNER));
    }

    @Test
    void vacateAfterFirstCohortReadCannotTearTheOriginalConsistentSnapshot() throws Exception {
        Receipt admission = admit(ADMITTED);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ReadProbe probe = new ReadProbe(false)) {
            Future<RetentionAssessment> pending = worker.submit(() ->
                    new RetentionRepository(probe.routed, fixed(NOW), ZONE, RESET).read(OWNER, FROM, HORIZON));
            try {
                probe.awaitGate();
                TenancyDeparture departure = depart(admission, ADMITTED.plus(Duration.ofDays(1)));
                List<List<String>> afterWriter = data();
                probe.release();
                RetentionAssessment original = pending.get(15, TimeUnit.SECONDS);
                assertEquals(NOW, original.capturedAt());
                assertNull(only(original, admission, Outcome.RETAINED).departure());
                assertEquals(1.0, original.retentionRate().orElseThrow(), 0.0);
                assertEquals(afterWriter, data(), "only the real vacate may write during the paused read");
                probe.assertReadOnlySnapshot();
                assertEquals(departure, only(read(OWNER), admission, Outcome.DEPARTED).departure());
            } finally { probe.release(); worker.shutdownNow(); assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS)); }
        } finally { worker.shutdownNow(); }
    }

    @Test
    void cutoffCapturedBeforeSnapshotCannotMatureOldOccupancyWhileStartingTransaction() throws Exception {
        Receipt admission = admit(ADMITTED);
        Instant deadline = ADMITTED.plus(Duration.ofDays(HORIZON));
        Instant before = deadline.minusMillis(1);
        AtomicReference<Instant> time = new AtomicReference<>(before);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return RetentionRepositoryIT.fixed(instant()).withZone(zone); }
            @Override public Instant instant() { return time.get(); }
        };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ReadProbe probe = new ReadProbe(true)) {
            Future<RetentionAssessment> pending = worker.submit(() ->
                    new RetentionRepository(probe.routed, clock, ZONE, RESET).read(OWNER, FROM, HORIZON));
            try {
                probe.awaitGate();
                depart(admission, before);
                time.set(deadline);
                List<List<String>> afterWriter = data();
                probe.release();
                RetentionAssessment original = pending.get(15, TimeUnit.SECONDS);
                assertEquals(before, original.capturedAt(), "capture must precede real START, not follow its delayed return");
                assertNull(only(original, admission, Outcome.NOT_MATURED).departure());
                assertEquals(0, original.maturedCount());
                assertTrue(original.retentionRate().isEmpty());
                assertEquals(afterWriter, data());
                probe.assertReadOnlySnapshot();
                RetentionAssessment fresh = read(OWNER, deadline, FROM, HORIZON);
                only(fresh, admission, Outcome.DEPARTED);
                assertEquals(0.0, fresh.retentionRate().orElseThrow(), 0.0);
            } finally { probe.release(); worker.shutdownNow(); assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS)); }
        } finally { worker.shutdownNow(); }
    }

    private static Receipt admit(Instant at) throws SQLException {
        UUID resident = UUID.randomUUID();
        UUID property = enroll(at.minusSeconds(1));
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(resident, "Retention " + resident)));
        assertTrue(new HousingRegistry(database).admit(resident, HOUSING, day(at)));
        HousingMoveRepository moves = new HousingMoveRepository(database, fixed(at), ZONE, RESET);
        var applied = moves.apply(moves.quote(UUID.randomUUID(), resident, property, 0, day(at)), POLICY);
        assertTrue(applied.newlyApplied());
        assertEquals(at, applied.receipt().appliedAt());
        return applied.receipt();
    }

    private static Receipt admitAgain(Receipt previous, Instant at) throws SQLException {
        HousingMoveRepository moves = new HousingMoveRepository(database, fixed(at), ZONE, RESET);
        var r = previous.request();
        var applied = moves.apply(moves.quote(UUID.randomUUID(), r.residentId(), r.propertyId(), r.slot(), day(at)), POLICY);
        assertTrue(applied.newlyApplied());
        assertEquals(previous.tenancyRevisionAfter() + 2, applied.receipt().tenancyRevisionAfter());
        return applied.receipt();
    }

    private static UUID enroll(Instant observedAt) throws SQLException {
        UUID property = UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(property, WORLD, "retention-" + property,
                "downtown", "room", 4, 1000, 1200, OWNER, 0, 80, true), 0);
        // PropertyRepository uses SQL wall time; align only the produced fixture observation with its test clock.
        execute("UPDATE npc_property_ownership SET observed_at=? WHERE property_id=? AND ownership_epoch=0", observedAt, property);
        return property;
    }

    private static void ownerChange(Receipt admission, UUID before, UUID after, int epoch, Instant at) throws SQLException {
        UUID property = admission.request().propertyId();
        new PropertyRepository(database).applyOwnerChange(property, before, after, epoch, "retention IT fixture");
        execute("UPDATE npc_property_ownership SET observed_at=? WHERE property_id=? AND ownership_epoch=?", at, property, epoch);
    }

    private static TenancyDeparture depart(Receipt admission, Instant at) throws SQLException {
        TenantRepository tenants = new TenantRepository(database, fixed(at));
        assertTrue(tenants.vacate(admission.request().residentId()));
        TenancyDeparture result = tenants.departure(admission.request().residentId(), admission.tenancyRevisionAfter()).orElseThrow();
        assertEquals(at, result.departedAt());
        assertEquals(admission.request().propertyId(), result.propertyId());
        assertEquals(admission.request().slot(), result.slot());
        assertEquals(admission.tenancyRevisionAfter() + 1, result.occupancyRevisionAfter());
        assertEquals(TenancyDeparture.Cause.DEPARTURE, result.cause());
        return result;
    }

    private static RetentionAssessment read(UUID owner) throws SQLException { return read(owner, NOW, FROM, HORIZON); }

    private static RetentionAssessment read(UUID owner, Instant at, Instant from, int horizon) throws SQLException {
        List<List<String>> before = data();
        try { return new RetentionRepository(database, fixed(at), ZONE, RESET).read(owner, from, horizon); }
        finally { assertEquals(before, data(), "assessment, including rejected evidence, must not write any SQL row"); }
    }

    private static Case only(RetentionAssessment result, Receipt admission, Outcome outcome) {
        assertEquals(1, result.cases().size());
        Case c = result.cases().getFirst();
        assertEquals(admission, c.admission());
        assertEquals(outcome, c.outcome());
        assertEquals(1, result.count(outcome));
        return c;
    }

    @FunctionalInterface
    private interface Read { RetentionAssessment run() throws SQLException; }

    private static void rejected(String message, Read action) {
        SQLException failure = assertThrows(SQLException.class, action::run);
        StringBuilder causes = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) causes.append(cause.getMessage()).append('\n');
        assertTrue(causes.toString().toLowerCase(Locale.ROOT).contains(message.toLowerCase(Locale.ROOT)), causes::toString);
    }

    private static void pinDay(LocalDate day) throws SQLException {
        execute("INSERT IGNORE INTO npc_housing_days(economy_day,server_limit,market_limit,property_limit,cooldown_days) VALUES (?,?,?,?,?)",
                day, POLICY.serverLimit(), POLICY.marketLimit(), POLICY.propertyLimit(), POLICY.cooldownDays());
    }

    private static final String INSERT_RECEIPT = """
            INSERT INTO npc_housing_moves(request_id,payload_hash,economy_day,resident_id,property_id,owner_uuid,
              ownership_epoch,property_revision,membership_revision,tenancy_revision_before,tenancy_revision_after,
              slot,world_uuid,market_id,class_key,cooldown_until,applied_at_millis)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private static Object[] receiptValues(HousingMoveRequest r, Instant at) {
        return new Object[]{r.requestId(), r.fingerprint(POLICY), r.day(), r.residentId(), r.propertyId(), r.ownerId(),
                r.ownershipEpoch(), r.propertyRevision(), r.membershipRevision(), r.tenancyRevision(), r.tenancyRevision() + 1,
                r.slot(), r.worldId(), r.marketId(), r.classKey(), r.day().plusDays(POLICY.cooldownDays()), at.toEpochMilli()};
    }

    private static void insertReceipt(Connection c, HousingMoveRequest request, Instant at) throws SQLException {
        execute(c, INSERT_RECEIPT, receiptValues(request, at));
    }

    /** Batch copies of one real admission, with distinct identities/homes, valid hashes, and <=512 admissions/day. */
    private static void cloneAdmissions(Receipt seed, int first, int last) throws SQLException {
        for (int offset = first / 512; offset <= last / 512; offset++) pinDay(seed.request().day().plusDays(offset));
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement profiles = c.prepareStatement("""
                    INSERT INTO npc_resident_profiles(resident_id,catalogue_version,name,household,generation,occupation,
                      means,life_stage,temperament,preference,wardrobe_everyday,wardrobe_work,wardrobe_leisure)
                    SELECT ?,catalogue_version,name,household,generation,occupation,means,life_stage,temperament,preference,
                      wardrobe_everyday,wardrobe_work,wardrobe_leisure FROM npc_resident_profiles WHERE resident_id=?
                    """);
                 PreparedStatement homes = c.prepareStatement("""
                    INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,
                      reference_rent_cents,asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled,row_version)
                    SELECT ?,world_uuid,?,market_id,class_key,capacity,reference_rent_cents,asking_rent_cents,owner_uuid,
                      ownership_epoch,condition_score,enabled,row_version FROM npc_properties WHERE property_id=?
                    """);
                 PreparedStatement funding = c.prepareStatement("""
                    INSERT INTO npc_property_funding(property_id,one_time_seed_cents)
                    SELECT ?,one_time_seed_cents FROM npc_property_funding WHERE property_id=?
                    """);
                 PreparedStatement ownership = c.prepareStatement("""
                    INSERT INTO npc_property_ownership(property_id,old_owner_uuid,new_owner_uuid,source_snapshot,ownership_epoch,observed_at)
                    SELECT ?,old_owner_uuid,new_owner_uuid,source_snapshot,ownership_epoch,observed_at
                      FROM npc_property_ownership WHERE property_id=?
                    """);
                 PreparedStatement members = c.prepareStatement("""
                    INSERT INTO npc_housing_members(resident_id,world_uuid,market_id,class_key,tier,max_daily_rent_cents,
                      minimum_condition,available_since,row_version,enabled)
                    SELECT ?,world_uuid,market_id,class_key,tier,max_daily_rent_cents,minimum_condition,available_since,
                      row_version,enabled FROM npc_housing_members WHERE resident_id=?
                    """);
                 PreparedStatement tenants = c.prepareStatement("""
                    INSERT INTO npc_tenants(tenant_id,display_name,property_id,slot,tier,archetype,move_cooldown_until_day,row_version)
                    SELECT ?,display_name,?,slot,tier,archetype,?,row_version FROM npc_tenants WHERE tenant_id=?
                    """);
                 PreparedStatement moves = c.prepareStatement(INSERT_RECEIPT)) {
                var statements = List.of(profiles, homes, funding, ownership, members, tenants, moves);
                for (PreparedStatement ps : statements) ps.setQueryTimeout(10);
                for (int i = first; i <= last; i++) {
                    UUID resident = new UUID(2810, i), home = new UUID(2811, i), request = new UUID(2812, i);
                    Instant at = seed.appliedAt().plus(Duration.ofDays(i / 512));
                    HousingMoveRequest r = seed.request();
                    HousingMoveRequest copy = new HousingMoveRequest(request, resident, home, r.ownerId(), r.worldId(),
                            r.marketId(), r.classKey(), r.ownershipEpoch(), r.propertyRevision(), r.membershipRevision(),
                            r.tenancyRevision(), r.slot(), day(at));
                    cloneRow(profiles, resident, r.residentId());
                    cloneRow(homes, home, "retention-clone-" + i, r.propertyId());
                    cloneRow(funding, home, r.propertyId());
                    cloneRow(ownership, home, r.propertyId());
                    cloneRow(members, resident, r.residentId());
                    cloneRow(tenants, resident, home, copy.day().plusDays(POLICY.cooldownDays()), r.residentId());
                    cloneRow(moves, receiptValues(copy, at));
                }
                c.commit();
            } catch (SQLException | RuntimeException failure) { c.rollback(); throw failure; }
        }
    }

    // MariaDB's bulk prepared protocol does not support these INSERT ... SELECT fixtures.
    private static void cloneRow(PreparedStatement ps, Object... values) throws SQLException {
        bind(ps, values);
        assertEquals(1, ps.executeUpdate());
    }

    /** Forward real JDBC only; pause after START returns from MariaDB or after the first cohort is consumed. */
    private static final class ReadProbe implements AutoCloseable {
        private final Database routed = new Database();
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private final AtomicBoolean armed = new AtomicBoolean(true);
        private final List<String> queries = new ArrayList<>();
        private int connections;
        private int observedIsolation;

        private ReadProbe(boolean afterBegin) throws ReflectiveOperationException {
            HikariDataSource forwarding = new HikariDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    connections++;
                    Connection actual = database.getConnection();
                    return proxy(Connection.class, (ignored, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) {
                            String normalized = sql.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
                            PreparedStatement statement = (PreparedStatement) invoke(actual, method, args);
                            return proxy(PreparedStatement.class, (unused, operation, values) -> {
                                boolean execute = operation.getName().startsWith("execute");
                                if (execute) {
                                    assertTrue(statement.getQueryTimeout() > 0 && statement.getQueryTimeout() <= 5,
                                            "bounded JDBC deadline: " + normalized);
                                    queries.add(normalized);
                                }
                                Object result = invoke(statement, operation, values);
                                boolean begin = execute && normalized.startsWith("START TRANSACTION");
                                boolean cohortClosed = operation.getName().equals("close") && normalized.startsWith("SELECT")
                                        && normalized.contains("FROM NPC_HOUSING_MOVES");
                                if ((afterBegin ? begin : cohortClosed) && armed.compareAndSet(true, false)) {
                                    assertFalse(actual.getAutoCommit());
                                    observedIsolation = actual.getTransactionIsolation();
                                    reached.countDown();
                                    try { assertTrue(resume.await(15, TimeUnit.SECONDS), "retention phase gate timed out"); }
                                    catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                        throw new AssertionError("retention phase gate interrupted", interrupted);
                                    }
                                }
                                return result;
                            });
                        }
                        return invoke(actual, method, args);
                    });
                }
            };
            // Never replace or close the shared fixture pool; this facade owns only its forwarding source.
            Field source = Database.class.getDeclaredField("dataSource");
            source.setAccessible(true);
            source.set(routed, forwarding);
        }

        private void awaitGate() throws InterruptedException {
            assertTrue(reached.await(10, TimeUnit.SECONDS), "reader did not reach the real JDBC phase gate");
        }

        private void assertReadOnlySnapshot() {
            assertEquals(Connection.TRANSACTION_REPEATABLE_READ, observedIsolation);
            assertEquals(1, connections, "the entire assessment must use one connection");
            List<String> starts = queries.stream().filter(sql -> sql.startsWith("START TRANSACTION")).toList();
            assertEquals(1, starts.size());
            assertTrue(starts.getFirst().contains("WITH CONSISTENT SNAPSHOT"));
            assertTrue(starts.getFirst().contains("READ ONLY"));
            assertTrue(queries.stream().allMatch(sql -> sql.startsWith("SELECT") || sql.startsWith("START TRANSACTION")
                    || sql.startsWith("SET ") || sql.equals("ROLLBACK")), queries::toString);
            assertTrue(queries.stream().noneMatch(sql -> sql.contains("FOR UPDATE") || sql.contains("LOCK IN SHARE MODE")));
            assertEquals(6, queries.stream().filter(sql -> sql.startsWith("SELECT")).count(), "batched reads, no per-resident queries");
            assertEquals(1, queries.stream().filter(sql -> sql.startsWith("SELECT") && sql.contains("FROM NPC_HOUSING_MOVES")
                    && sql.contains("M.OWNER_UUID=?")).count(), "one bounded owner cohort, separate from cross-owner resident history");
        }

        private void release() { resume.countDown(); }
        @Override public void close() { release(); routed.close(); }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
    }

    private static Clock fixed(Instant at) { return Clock.fixed(at, ZoneOffset.UTC); }
    private static LocalDate day(Instant at) { return new EconomyClock(fixed(at), ZONE, RESET).dayAt(at); }

    private static List<List<String>> data() throws SQLException {
        List<List<String>> result = new ArrayList<>();
        try (Connection c = database.getConnection()) {
            for (String table : tables) {
                result.add(List.of(table));
                List<List<String>> contents = rows(c, "SELECT * FROM " + table);
                contents.sort(Comparator.comparing(Object::toString));
                result.addAll(contents);
            }
        }
        return result;
    }

    private static List<List<String>> rows(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection()) { return rows(c, sql, values); }
    }

    private static List<List<String>> rows(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, values);
            try (ResultSet rs = ps.executeQuery()) {
                List<List<String>> result = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        Object value = rs.getMetaData().getColumnType(i) == java.sql.Types.TIMESTAMP
                                ? rs.getObject(i, LocalDateTime.class) : rs.getObject(i);
                        row.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes)
                                : value == null ? null : value.toString());
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }

    private static void unchecked(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION foreign_key_checks=0");
            execute(c, "SET SESSION check_constraint_checks=0");
            try { execute(c, sql, values); }
            finally {
                execute(c, "SET SESSION check_constraint_checks=1");
                execute(c, "SET SESSION foreign_key_checks=1");
            }
        }
    }

    private static void execute(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection()) { execute(c, sql, values); }
    }

    private static void execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.setQueryTimeout(5); bind(ps, values); ps.execute(); }
    }

    private static void bind(PreparedStatement ps, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else if (values[i] instanceof Instant at) ps.setObject(i + 1, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
            else if (values[i] instanceof LocalDate date) ps.setString(i + 1, date.toString());
            else ps.setObject(i + 1, values[i]);
        }
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = RetentionRepositoryIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
        }
    }
}
