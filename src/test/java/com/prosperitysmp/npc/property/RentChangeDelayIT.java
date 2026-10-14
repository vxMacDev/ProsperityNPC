package com.prosperitysmp.npc.property;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.property.PropertyRepository.RentChange.Outcome;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated MariaDB. housing.rent-change-delay-days: pending asking rent, its effective day and tenant notices. */
@Timeout(60)
class RentChangeDelayIT {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static DB maria;
    private static Database db;
    private PropertyRepository repo;
    private UUID owner;

    @BeforeAll static void boot() throws Exception {
        var cfg = DBConfigurationBuilder.newBuilder(); cfg.setPort(0);
        cfg.setBaseDir(java.nio.file.Files.createTempDirectory("npc-rent-delay-maria-").toFile());
        maria = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            maria.start();
            String schema = "npc_rent_delay_" + UUID.randomUUID().toString().replace("-", "");
            maria.createDB(schema);
            db = new Database(); db.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 8, 10_000));
            for (int v = 1; v <= 31; v++) applyVersion(v);
            applyVersion(35); // item-repairs: the owner desk's service read checks item-repair receipts
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
            exec(c, "SET FOREIGN_KEY_CHECKS=0");
            for (String t : List.of("npc_rent_change_notices", "npc_rent_changes", "npc_tenants", "npc_properties"))
                exec(c, "DELETE FROM " + t);
            exec(c, "SET FOREIGN_KEY_CHECKS=1");
        }
        repo = new PropertyRepository(db);
        owner = UUID.randomUUID();
    }

    private Property property(int reference, int asking) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = db.getConnection()) {
            exec(c, "INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,reference_rent_cents,"
                    + "asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, WORLD, "r-" + id, "test", "small", 3, reference, asking, owner, 0, 80, true);
        }
        return repo.findById(id).orElseThrow();
    }
    private UUID tenant(Property p, int slot) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = db.getConnection()) {
            exec(c, "INSERT INTO npc_tenants(tenant_id,display_name,property_id,slot,archetype,row_version) VALUES(?,?,?,?,?,?)",
                    id, "Resident", p.propertyId(), slot, "COOK", 7 + slot);
        }
        return id;
    }
    private int asking(Property p) throws SQLException {
        return ((Number) scalar("SELECT asking_rent_cents FROM npc_properties WHERE property_id=?", p.propertyId())).intValue();
    }
    private long count(String where, Object... args) throws SQLException {
        return ((Number) scalar("SELECT COUNT(*) FROM " + where, args)).longValue();
    }
    private String state(long changeId) throws SQLException {
        return String.valueOf(scalar("SELECT state FROM npc_rent_changes WHERE change_id=?", changeId));
    }

    @Test void delayZeroIsImmediateAndRecordsNothingNew() throws Exception {
        Property p = property(1000, 1000);
        tenant(p, 0);
        var change = repo.changeAskingRent(p, 1200, DAY, 0);
        assertEquals(Outcome.APPLIED_NOW, change.outcome());
        assertEquals(1200, asking(p));
        assertEquals(1200, change.property().askingRentCents());
        assertEquals(p.withAskingRent(1200), change.property());
        assertEquals(0, count("npc_rent_changes"));
        assertEquals(0, count("npc_rent_change_notices"));
        assertEquals(0, repo.countPendingRentChanges());
        // A second immediate change with nothing pending is also the plain write.
        var again = repo.changeAskingRent(change.property(), 1100, DAY, 0);
        assertEquals(Outcome.APPLIED_NOW, again.outcome());
        assertEquals(1100, asking(p));
        assertEquals(0, count("npc_rent_changes"));
    }

    @Test void pendingRentAppliesOnItsEffectiveDayAndNotBefore() throws Exception {
        Property p = property(1000, 1000);
        var change = repo.changeAskingRent(p, 1300, DAY, 2);
        assertEquals(Outcome.SCHEDULED, change.outcome());
        assertEquals(DAY.plusDays(2), change.effectiveDay());
        assertEquals(p, change.property(), "asking rent itself is unchanged while pending");
        assertEquals(1000, asking(p), "matching and rent read asking_rent_cents: still the old value");
        assertEquals(1, repo.countPendingRentChanges());

        assertTrue(repo.applyDueRentChanges(DAY, 512).changes().isEmpty());
        assertTrue(repo.applyDueRentChanges(DAY.plusDays(1), 512).changes().isEmpty());
        assertEquals(1000, asking(p));
        assertEquals("PENDING", state(change.changeId()));

        var due = repo.applyDueRentChanges(DAY.plusDays(2), 512);
        assertEquals(1, due.changes().size());
        var applied = due.changes().getFirst();
        assertTrue(applied.applied());
        assertEquals(p, applied.before());
        assertEquals(p.withAskingRent(1300), applied.after());
        assertEquals(0, due.stillPending());
        assertEquals(1300, asking(p));
        assertEquals(1300, repo.findById(p.propertyId()).orElseThrow().askingRentCents());
        assertEquals("APPLIED", state(change.changeId()));
        assertEquals(0, count("npc_properties WHERE pending_rent_change_id IS NOT NULL OR pending_asking_rent_cents IS NOT NULL"
                + " OR pending_rent_effective_day IS NOT NULL"));

        // Replay of the same day's step changes nothing.
        assertTrue(repo.applyDueRentChanges(DAY.plusDays(2), 512).changes().isEmpty());
        assertEquals(1300, asking(p));
    }

    @Test void missedEffectiveDayAppliesAtTheNextCycle() throws Exception {
        Property p = property(1000, 1000);
        repo.changeAskingRent(p, 800, DAY, 1);
        var due = repo.applyDueRentChanges(DAY.plusDays(5), 512);
        assertEquals(1, due.changes().size());
        assertEquals(800, asking(p));
    }

    @Test void newerChangeReplacesThePendingOne() throws Exception {
        Property p = property(1000, 1000);
        tenant(p, 0); tenant(p, 1);
        var first = repo.changeAskingRent(p, 1200, DAY, 3);
        var second = repo.changeAskingRent(p, 900, DAY.plusDays(1), 3);
        assertEquals(Outcome.SCHEDULED, second.outcome());
        assertNotEquals(first.changeId(), second.changeId());
        assertEquals(DAY.plusDays(4), second.effectiveDay());
        assertEquals("REPLACED", state(first.changeId()));
        assertEquals("PENDING", state(second.changeId()));
        assertEquals(1, count("npc_rent_changes WHERE property_id=? AND state='PENDING'", p.propertyId()));
        assertEquals(second.changeId(), ((Number) scalar("SELECT pending_rent_change_id FROM npc_properties WHERE property_id=?",
                p.propertyId())).longValue());

        assertTrue(repo.applyDueRentChanges(DAY.plusDays(3), 512).changes().isEmpty(), "the replaced day does nothing");
        assertEquals(1000, asking(p));
        assertEquals(1, repo.applyDueRentChanges(DAY.plusDays(4), 512).changes().size());
        assertEquals(900, asking(p));
        assertEquals("REPLACED", state(first.changeId()));
        assertEquals("APPLIED", state(second.changeId()));
        // Each announced change kept its own notices.
        assertEquals(2, count("npc_rent_change_notices WHERE change_id=?", first.changeId()));
        assertEquals(2, count("npc_rent_change_notices WHERE change_id=?", second.changeId()));
    }

    @Test void eachCurrentTenantIsNoticedExactlyOnce() throws Exception {
        Property p = property(1000, 1000);
        UUID a = tenant(p, 0), b = tenant(p, 1);
        Property other = property(1000, 1000);
        tenant(other, 0);
        var change = repo.changeAskingRent(p, 1400, DAY, 2);
        assertEquals(2, change.notices());
        assertEquals(2, count("npc_rent_change_notices"));
        assertEquals(1, count("npc_rent_change_notices WHERE change_id=? AND resident_id=? AND property_id=? AND tenancy_revision=7 AND economy_day=?",
                change.changeId(), a, p.propertyId(), DAY.toString()));
        assertEquals(1, count("npc_rent_change_notices WHERE change_id=? AND resident_id=? AND tenancy_revision=8",
                change.changeId(), b));

        // Same command again: the pending change stays, no second notice.
        var replay = repo.changeAskingRent(p, 1400, DAY.plusDays(1), 2);
        assertEquals(Outcome.ALREADY_SCHEDULED, replay.outcome());
        assertEquals(change.changeId(), replay.changeId());
        assertEquals(DAY.plusDays(2), replay.effectiveDay(), "a replay never pushes the day back");
        assertEquals(2, replay.notices());
        assertEquals(1, count("npc_rent_changes"));
        assertEquals(2, count("npc_rent_change_notices"));

        // A resident who moves in after the announcement has no invented notice.
        UUID late = tenant(p, 2);
        repo.applyDueRentChanges(DAY.plusDays(2), 512);
        repo.applyDueRentChanges(DAY.plusDays(2), 512);
        assertEquals(1400, asking(p));
        assertEquals(2, count("npc_rent_change_notices"));
        assertEquals(0, count("npc_rent_change_notices WHERE resident_id=?", late));
    }

    @Test void currentRentCancelsAndUnchangedWritesNothing() throws Exception {
        Property p = property(1000, 1000);
        var unchanged = repo.changeAskingRent(p, 1000, DAY, 2);
        assertEquals(Outcome.UNCHANGED, unchanged.outcome());
        assertEquals(0, count("npc_rent_changes"));
        var change = repo.changeAskingRent(p, 1200, DAY, 2);
        var cancel = repo.changeAskingRent(p, 1000, DAY, 2);
        assertEquals(Outcome.CANCELLED, cancel.outcome());
        assertEquals("CANCELLED", state(change.changeId()));
        assertEquals(0, repo.countPendingRentChanges());
        assertTrue(repo.applyDueRentChanges(DAY.plusDays(2), 512).changes().isEmpty());
        assertEquals(1000, asking(p));
    }

    @Test void immediateChangeReplacesAPendingOne() throws Exception {
        Property p = property(1000, 1000);
        var pending = repo.changeAskingRent(p, 1200, DAY, 2);
        var now = repo.changeAskingRent(p, 900, DAY, 0);
        assertEquals(Outcome.APPLIED_NOW, now.outcome());
        assertEquals(900, asking(p));
        assertEquals("REPLACED", state(pending.changeId()));
        assertEquals(0, repo.countPendingRentChanges());
        assertTrue(repo.applyDueRentChanges(DAY.plusDays(2), 512).changes().isEmpty());
        assertEquals(900, asking(p));
    }

    @Test void boundsAndStaleSnapshotsStillRefuse() throws Exception {
        Property p = property(1000, 1000);
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.changeAskingRent(p, 1501, DAY, 2));
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.changeAskingRent(p, 499, DAY, 2));
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.changeAskingRent(p.withAskingRent(1100), 1200, DAY, 2));
        assertThrows(IllegalArgumentException.class, () -> repo.changeAskingRent(p, 1200, DAY, 31));
        assertThrows(IllegalArgumentException.class, () -> repo.changeAskingRent(p, 1200, DAY, -1));
        assertEquals(0, count("npc_rent_changes"));
        assertEquals(1000, asking(p));
    }

    @Test void ownerChangeBeforeTheDayCancelsInsteadOfApplying() throws Exception {
        Property p = property(1000, 1000);
        var change = repo.changeAskingRent(p, 1200, DAY, 1);
        try (Connection c = db.getConnection()) {
            exec(c, "UPDATE npc_properties SET owner_uuid=?,ownership_epoch=1,row_version=row_version+1 WHERE property_id=?",
                    UUID.randomUUID(), p.propertyId());
        }
        var due = repo.applyDueRentChanges(DAY.plusDays(1), 512);
        assertEquals(1, due.changes().size());
        assertFalse(due.changes().getFirst().applied());
        assertEquals(1000, asking(p));
        assertEquals("CANCELLED", state(change.changeId()));
        assertEquals(0, due.stillPending());
    }

    @Test void newOwnerNeverInheritsTheOldOwnersPendingChange() throws Exception {
        Property p = property(1000, 1000);
        tenant(p, 0);
        var old = repo.changeAskingRent(p, 1200, DAY, 2);
        UUID buyer = UUID.randomUUID();
        try (Connection c = db.getConnection()) {
            exec(c, "UPDATE npc_properties SET owner_uuid=?,ownership_epoch=1,row_version=row_version+1 WHERE property_id=?",
                    buyer, p.propertyId());
        }
        Property sold = repo.findById(p.propertyId()).orElseThrow();
        var mine = repo.changeAskingRent(sold, 1200, DAY.plusDays(1), 2);
        assertEquals(Outcome.SCHEDULED, mine.outcome(), "same amount, but a different owner: a new announcement");
        assertEquals("CANCELLED", state(old.changeId()));
        assertEquals(DAY.plusDays(3), mine.effectiveDay());
        assertEquals(1, mine.notices());
        assertEquals(1, repo.applyDueRentChanges(DAY.plusDays(3), 512).changes().size());
        assertEquals(1200, asking(p));
        assertEquals("APPLIED", state(mine.changeId()));
    }

    @Test void pausedPropertyKeepsItsChangeUntilResumed() throws Exception {
        Property p = property(1000, 1000);
        repo.changeAskingRent(p, 1200, DAY, 1);
        try (Connection c = db.getConnection()) { exec(c, "UPDATE npc_properties SET enabled=0 WHERE property_id=?", p.propertyId()); }
        var due = repo.applyDueRentChanges(DAY.plusDays(1), 512);
        assertTrue(due.changes().isEmpty());
        assertEquals(1, due.stillPending());
        try (Connection c = db.getConnection()) { exec(c, "UPDATE npc_properties SET enabled=1 WHERE property_id=?", p.propertyId()); }
        assertEquals(1, repo.applyDueRentChanges(DAY.plusDays(2), 512).changes().size());
        assertEquals(1200, asking(p));
    }

    @Test void ownerDeskShowsThePendingLineOnlyWhenAskedAndOnlyToTheSameOwner() throws Exception {
        Property p = property(1000, 1000);
        tenant(p, 0); tenant(p, 1);
        repo.changeAskingRent(p, 1250, DAY, 2);
        var clock = Clock.fixed(DAY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        var shown = new OwnerDeskRepository(db, clock, ZoneOffset.UTC, 4, () -> true)
                .read(WORLD, p.regionId(), owner, DAY).orElseThrow();
        var pending = shown.pendingRent().orElseThrow();
        assertEquals(1250, pending.cents());
        assertEquals(DAY.plusDays(2), pending.effectiveDay());
        assertEquals(2, pending.notices());
        assertEquals("Rent changes to $12.50 on 2026-10-15. 2 residents were told.", pending.line());
        assertFalse(pending.line().contains("—"));
        assertEquals(1000, shown.property().askingRentCents());
        assertTrue(new OwnerDeskRepository(db, clock, ZoneOffset.UTC, 4).read(WORLD, p.regionId(), owner, DAY)
                .orElseThrow().pendingRent().isEmpty(), "flag off: no pending line");
        repo.applyDueRentChanges(DAY.plusDays(2), 512);
        assertTrue(new OwnerDeskRepository(db, clock, ZoneOffset.UTC, 4, () -> true)
                .read(WORLD, p.regionId(), owner, DAY).orElseThrow().pendingRent().isEmpty());
    }

    // --- A stale schedule must never overwrite a newer immediate rent. ---

    private long revision(Property p) throws SQLException {
        return ((Number) scalar("SELECT row_version FROM npc_properties WHERE property_id=?", p.propertyId())).longValue();
    }
    private long pendingColumnsSet(Property p) throws SQLException {
        return count("npc_properties WHERE property_id=? AND (pending_rent_change_id IS NOT NULL"
                + " OR pending_asking_rent_cents IS NOT NULL OR pending_rent_effective_day IS NOT NULL)", p.propertyId());
    }

    @Test void immediateChangeResolvesAPendingScheduleWithoutAnyCallerFlag() throws Exception {
        Property p = property(1000, 1000);
        tenant(p, 0);
        var scheduled = repo.changeAskingRent(p, 1200, DAY, 2);
        assertEquals(Outcome.SCHEDULED, scheduled.outcome());
        assertEquals(1, pendingColumnsSet(p), "the schedule is visible in SQL");
        // Delay 0 with a schedule pending in SQL: no caller flag tells the repository about it.
        var now = repo.changeAskingRent(scheduled.property(), 900, DAY, 0);
        assertEquals(Outcome.APPLIED_NOW, now.outcome());
        assertEquals(900, asking(p));
        assertEquals(p.withAskingRent(900), now.property());
        assertEquals("REPLACED", state(scheduled.changeId()), "replaced in the same transaction as the new rent");
        assertEquals(0, pendingColumnsSet(p));
        assertEquals(0, repo.countPendingRentChanges());
        assertEquals(0, count("npc_rent_changes WHERE state='PENDING'"));
        // A later restart and every later daily step see nothing to apply: the newer rent stays.
        for (int d = 0; d <= 5; d++) assertTrue(repo.applyDueRentChanges(DAY.plusDays(d), 512).changes().isEmpty());
        assertEquals(900, asking(p));
        // The owner desk no longer shows the overridden "rent changes to" line.
        var clock = Clock.fixed(DAY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        assertTrue(new OwnerDeskRepository(db, clock, ZoneOffset.UTC, 4, () -> true)
                .read(WORLD, p.regionId(), owner, DAY).orElseThrow().pendingRent().isEmpty());
    }

    @Test void publicImmediateSetterAlsoResolvesPendingAndCancelsAnEarlierOwnersChange() throws Exception {
        Property p = property(1000, 1000);
        var mine = repo.changeAskingRent(p, 1200, DAY, 2);
        Property current = repo.findById(p.propertyId()).orElseThrow();
        assertEquals(p.withAskingRent(1100), repo.setAskingRentExpected(current, 1100));
        assertEquals("REPLACED", state(mine.changeId()));
        assertEquals(0, pendingColumnsSet(p));

        var old = repo.changeAskingRent(repo.findById(p.propertyId()).orElseThrow(), 1300, DAY, 2);
        try (Connection c = db.getConnection()) {
            exec(c, "UPDATE npc_properties SET owner_uuid=?,ownership_epoch=1,row_version=row_version+1 WHERE property_id=?",
                    UUID.randomUUID(), p.propertyId());
        }
        Property sold = repo.findById(p.propertyId()).orElseThrow();
        assertEquals(sold.withAskingRent(900), repo.setAskingRentExpected(sold, 900));
        assertEquals("CANCELLED", state(old.changeId()), "an earlier owner's change is cancelled, not replaced");
        assertEquals(0, pendingColumnsSet(p));
        assertTrue(repo.applyDueRentChanges(DAY.plusDays(5), 512).changes().isEmpty());
        assertEquals(900, asking(p));
    }

    @Test void immediateChangeWithNothingPendingIsExactlyTheLegacyWrite() throws Exception {
        Property p = property(1000, 1000);
        long rev = revision(p);
        var change = repo.changeAskingRent(p, 1200, DAY, 0);
        assertEquals(Outcome.APPLIED_NOW, change.outcome());
        assertEquals(p.withAskingRent(1200), change.property(), "only asking rent changed");
        assertEquals(rev + 1, revision(p), "one write, row_version +1");
        assertEquals(0, count("npc_rent_changes"));
        assertEquals(0, count("npc_rent_change_notices"));
        assertEquals(0, pendingColumnsSet(p));
        // Same for the public setter, and stale or out-of-range requests still refuse.
        assertEquals(p.withAskingRent(1300), repo.setAskingRentExpected(change.property(), 1300));
        assertEquals(rev + 2, revision(p));
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.changeAskingRent(p, 1100, DAY, 0), "stale snapshot");
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.changeAskingRent(p.withAskingRent(1300), 1501, DAY, 0));
        assertEquals(1300, asking(p));
        assertEquals(rev + 2, revision(p));
    }

    @Test void applierReplacesInsteadOfOverwritingANewerRent() throws Exception {
        Property p = property(1000, 1000);
        var stale = repo.changeAskingRent(p, 1200, DAY, 1);
        // A rent written by the legacy setter: asking rent moves, pending columns stay.
        repo.setAskingRent(p.propertyId(), 1100);
        assertEquals(1, pendingColumnsSet(p), "the stale schedule is still visible");
        var due = repo.applyDueRentChanges(DAY.plusDays(3), 512);
        assertEquals(1, due.changes().size());
        var r = due.changes().getFirst();
        assertEquals(PropertyRepository.DueRent.Resolution.REPLACED, r.resolution());
        assertFalse(r.applied());
        assertEquals(1100, r.before().askingRentCents());
        assertEquals(1100, r.after().askingRentCents());
        assertEquals(1100, asking(p), "the newer rent is kept");
        assertEquals("REPLACED", state(stale.changeId()));
        assertEquals(0, pendingColumnsSet(p));
        assertEquals(0, due.stillPending());
        assertTrue(repo.applyDueRentChanges(DAY.plusDays(3), 512).changes().isEmpty(), "replay changes nothing");
        assertEquals(1100, asking(p));
    }

    @Test void applierAppliesWhenTheStartingRentStillMatches() throws Exception {
        Property p = property(1000, 1000);
        var change = repo.changeAskingRent(p, 1200, DAY, 1);
        assertEquals(1000, ((Number) scalar("SELECT from_rent_cents FROM npc_rent_changes WHERE change_id=?",
                change.changeId())).intValue());
        var due = repo.applyDueRentChanges(DAY.plusDays(1), 512);
        assertEquals(1, due.changes().size());
        assertEquals(PropertyRepository.DueRent.Resolution.APPLIED, due.changes().getFirst().resolution());
        assertTrue(due.changes().getFirst().applied());
        assertEquals(1200, asking(p));
        assertEquals("APPLIED", state(change.changeId()));
    }

    @Test void ownerChangeOutranksRentDriftAsTheCancelReason() throws Exception {
        Property p = property(1000, 1000);
        var change = repo.changeAskingRent(p, 1200, DAY, 1);
        try (Connection c = db.getConnection()) {
            exec(c, "UPDATE npc_properties SET asking_rent_cents=1100,owner_uuid=?,ownership_epoch=1,row_version=row_version+1 WHERE property_id=?",
                    UUID.randomUUID(), p.propertyId());
        }
        var due = repo.applyDueRentChanges(DAY.plusDays(1), 512);
        assertEquals(PropertyRepository.DueRent.Resolution.CANCELLED, due.changes().getFirst().resolution());
        assertEquals("CANCELLED", state(change.changeId()));
        assertEquals(1100, asking(p));
    }

    private static Object scalar(String sql, Object... args) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getObject(1); }
        }
    }
    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.setQueryTimeout(5); bind(ps, args); ps.execute(); }
    }
    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else ps.setObject(i + 1, args[i]);
        }
    }
    /** Same resources MigrationRunner applies; V16 is the rent-reservation file (the V16 chronicle file is not registered). */
    private static void applyVersion(int version) throws Exception {
        var dir = java.nio.file.Path.of("src/main/resources/db/migration");
        String prefix = version == 16 ? "V16__rent_reservations" : "V" + version + "__";
        try (var files = java.nio.file.Files.list(dir)) {
            var file = files.filter(f -> f.getFileName().toString().startsWith(prefix)).findFirst().orElseThrow();
            try (Connection c = db.getConnection()) {
                for (String sql : MigrationRunner.splitStatements(java.nio.file.Files.readString(file, StandardCharsets.UTF_8))) exec(c, sql);
            }
        }
    }
}
