package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Data access for enrolled properties, their funding rows, and ownership history. */
public final class PropertyRepository {

    public static final class ResumeRefused extends SQLException {
        public ResumeRefused(String reason) { super(reason); }
    }

    private final Database database;

    public PropertyRepository(Database database) {
        this.database = database;
    }

    /** Enrolls a property + its funding row + the initial ownership record, atomically. */
    public void insertEnrollment(Property p, long seedCents) throws SQLException {
        insertEnrollment(p, seedCents, null);
    }

    /** Seed is a lifetime payout allowance, not a Vault deposit. Exhaustion grants no new allowance. */
    public void insertEnrollmentWithSeedPool(Property p, long seedCents, long poolCents) throws SQLException {
        if (seedCents < 0 || seedCents > 5000 || poolCents < 0 || poolCents > 50000)
            throw new IllegalArgumentException("Seed policy outside approved release envelope");
        insertEnrollment(p, seedCents, poolCents);
    }

    private void insertEnrollment(Property p, long seedCents, Long poolCents) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (poolCents != null) {
                    try (PreparedStatement lock = c.prepareStatement(
                            "SELECT singleton FROM npc_rent_mutex WHERE singleton=1 FOR UPDATE");
                         ResultSet row = lock.executeQuery()) {
                        if (!row.next() || row.getInt(1) != 1) throw new SQLException("Seed mutex unavailable");
                    }
                    // Funding rows survive disable/owner changes; neither action replenishes the pool.
                    // A locking read sees the latest committed allocation after acquiring the mutex.
                    long allocated = 0;
                    try (PreparedStatement scan = c.prepareStatement(
                            "SELECT one_time_seed_cents FROM npc_property_funding FOR UPDATE");
                         ResultSet rows = scan.executeQuery()) {
                        while (rows.next()) {
                            long value = rows.getLong(1);
                            if (rows.wasNull() || value < 0) throw new SQLException("Invalid seed funding");
                            allocated = Math.addExact(allocated, value);
                        }
                    }
                    // No fractional starter grant: a property gets the configured allowance or zero.
                    if (allocated > poolCents || seedCents > poolCents - allocated) seedCents = 0;
                }
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO npc_properties
                          (property_id, world_uuid, region_id, market_id, class_key, capacity,
                           reference_rent_cents, asking_rent_cents, owner_uuid, ownership_epoch,
                           condition_score, enabled, row_version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                        """)) {
                    ps.setBytes(1, Uuids.toBytes(p.propertyId()));
                    ps.setBytes(2, Uuids.toBytes(p.worldUuid()));
                    ps.setString(3, p.regionId());
                    ps.setString(4, p.marketId());
                    ps.setString(5, p.classKey());
                    ps.setInt(6, p.capacity());
                    ps.setInt(7, p.referenceRentCents());
                    ps.setInt(8, p.askingRentCents());
                    setNullableUuid(ps, 9, p.ownerUuid());
                    ps.setInt(10, p.ownershipEpoch());
                    ps.setInt(11, p.conditionScore());
                    ps.setBoolean(12, p.enabled());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO npc_property_funding (property_id, one_time_seed_cents) VALUES (?, ?)")) {
                    ps.setBytes(1, Uuids.toBytes(p.propertyId()));
                    ps.setLong(2, seedCents);
                    ps.executeUpdate();
                }
                insertOwnershipRow(c, p.propertyId(), null, p.ownerUuid(), p.ownershipEpoch(), "enroll");
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** Applies exactly the next ownership epoch. A stale caller cannot overwrite a successor. */
    public void applyOwnerChange(UUID propertyId, UUID oldOwner, UUID newOwner, int newEpoch, String source)
            throws SQLException {
        Objects.requireNonNull(propertyId);
        if (newEpoch < 1) throw new SQLException("Owner change requires a positive next epoch");
        ownerTransaction(c -> {
            OwnerRow row = ownerRow(c, propertyId);
            if (!Objects.equals(row.property().ownerUuid(), oldOwner) || row.property().ownershipEpoch() != newEpoch - 1)
                throw new SQLException("Stale ownership change");
            writeOwner(c, row, newOwner, newEpoch, source);
            return null;
        });
    }

    /** Worker-only compare-and-set for an observed ARM owner; returns fresh SQL terms, never ARM authority. */
    public Property reconcileOwner(Property expected, UUID observedOwner) throws SQLException {
        Objects.requireNonNull(expected);
        return ownerTransaction(c -> {
            OwnerRow row = ownerRow(c, expected.propertyId());
            Property actual = row.property();
            if (!expected.enabled() || !actual.enabled() || !actual.worldUuid().equals(expected.worldUuid())
                    || !actual.regionId().equals(expected.regionId())
                    || !Objects.equals(actual.ownerUuid(), expected.ownerUuid())
                    || actual.ownershipEpoch() != expected.ownershipEpoch())
                throw new SQLException("Stale property ownership observation");
            if (Objects.equals(actual.ownerUuid(), observedOwner)) return actual;
            if (actual.ownershipEpoch() == Integer.MAX_VALUE) throw new SQLException("Ownership epoch exhausted");
            int nextEpoch = actual.ownershipEpoch() + 1;
            writeOwner(c, row, observedOwner, nextEpoch, "arm-reconcile");
            return actual.withOwner(observedOwner, nextEpoch);
        });
    }

    /** Fresh worker-side cache rebase after a rejected observation; not verification of ARM ownership. */
    public Optional<Property> findById(UUID propertyId) throws SQLException {
        Objects.requireNonNull(propertyId);
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM npc_properties WHERE property_id=?")) {
            ps.setQueryTimeout(5); ps.setBytes(1, Uuids.toBytes(propertyId));
            try (ResultSet rs = ps.executeQuery()) {
                Optional<Property> result = rs.next() ? Optional.of(checkedOwnerRow(rs).property()) : Optional.empty();
                checkWarnings(c, ps, rs);
                return result;
            }
        }
    }

    private record OwnerRow(Property property, long revision) { }
    @FunctionalInterface private interface OwnerWork<T> { T run(Connection c) throws SQLException; }

    private <T> T ownerTransaction(OwnerWork<T> work) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try { c.rollback(); }
                catch (SQLException | RuntimeException | Error cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
    }

    private static OwnerRow ownerRow(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM npc_properties WHERE property_id=? FOR UPDATE")) {
            ps.setQueryTimeout(5); ps.setBytes(1, Uuids.toBytes(id));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Ownership property missing");
                OwnerRow row = checkedOwnerRow(rs);
                checkWarnings(c, ps, rs);
                return row;
            }
        }
    }

    private static OwnerRow checkedOwnerRow(ResultSet rs) throws SQLException {
        long revision = rs.getLong("row_version");
        if (rs.wasNull() || revision < 0) throw new SQLException("Invalid ownership row revision");
        int epoch = rs.getInt("ownership_epoch");
        if (rs.wasNull() || epoch < 0) throw new SQLException("Invalid ownership epoch");
        int enabled = rs.getInt("enabled");
        if (rs.wasNull() || enabled < 0 || enabled > 1) throw new SQLException("Invalid ownership enabled state");
        return new OwnerRow(map(rs), revision);
    }

    private void writeOwner(Connection c, OwnerRow row, UUID newOwner, int newEpoch, String source) throws SQLException {
        if (source == null || source.isBlank() || source.length() > 255) throw new SQLException("Invalid ownership source");
        if (row.revision() == Long.MAX_VALUE) throw new SQLException("Ownership revision exhausted");
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE npc_properties SET owner_uuid=?,ownership_epoch=?,row_version=row_version+1
                WHERE property_id=? AND ownership_epoch=? AND owner_uuid <=> ? AND row_version=?
                """)) {
            ps.setQueryTimeout(5);
            setNullableUuid(ps, 1, newOwner); ps.setInt(2, newEpoch);
            ps.setBytes(3, Uuids.toBytes(row.property().propertyId()));
            ps.setInt(4, row.property().ownershipEpoch()); setNullableUuid(ps, 5, row.property().ownerUuid());
            ps.setLong(6, row.revision());
            if (ps.executeUpdate() != 1) throw new SQLException("Ownership compare-and-set lost");
            checkWarnings(c, ps, null);
        }
        insertOwnershipRow(c, row.property().propertyId(), row.property().ownerUuid(), newOwner, newEpoch, source);
    }

    private static void checkWarnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        if (c.getWarnings() != null || ps.getWarnings() != null || rs != null && rs.getWarnings() != null)
            throw new SQLException("Ownership SQL warning");
    }

    private void insertOwnershipRow(Connection c, UUID propertyId, UUID oldOwner, UUID newOwner,
                                    int epoch, String source) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO npc_property_ownership
                  (property_id, old_owner_uuid, new_owner_uuid, source_snapshot, ownership_epoch)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            ps.setBytes(1, Uuids.toBytes(propertyId));
            setNullableUuid(ps, 2, oldOwner);
            setNullableUuid(ps, 3, newOwner);
            ps.setString(4, source);
            ps.setInt(5, epoch);
            ps.setQueryTimeout(5);
            if (ps.executeUpdate() != 1) throw new SQLException("Ownership history not inserted");
            checkWarnings(c, ps, null);
        }
    }

    /** Soft-enable/disable a property (disabled = fail-closed, kept for audit). */
    public void setEnabled(UUID propertyId, boolean enabled) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE npc_properties SET enabled = ?, row_version = row_version + 1 WHERE property_id = ?")) {
            ps.setBoolean(1, enabled);
            ps.setBytes(2, Uuids.toBytes(propertyId));
            ps.executeUpdate();
        }
    }

    /** Caller holds the live ownership lease. Never disable a newer owner's incarnation. */
    public boolean disableExpected(Property expected) throws SQLException {
        try(Connection c=database.getConnection();PreparedStatement ps=c.prepareStatement(
                "UPDATE npc_properties SET enabled=0,row_version=row_version+1 WHERE property_id=? AND owner_uuid=? AND ownership_epoch=? AND enabled=1")) {
            ps.setQueryTimeout(10);
            ps.setBytes(1,Uuids.toBytes(expected.propertyId()));ps.setBytes(2,Uuids.toBytes(expected.ownerUuid()));
            ps.setInt(3,expected.ownershipEpoch());
            return ps.executeUpdate()==1;
        }
    }

    /**
     * Two-phase work inside the resume transaction (see resumeExpected). lockFirst runs before any property or
     * event lock and may take only locks that precede the property lock in the global order (the rent mutex).
     * prepare runs after the property row lock and after any resume owner write, so it sees the ARM owner and
     * epoch this resume is about to commit, and before the unsettled-event check.
     */
    public interface ResumePreparation {
        void lockFirst(Connection c) throws SQLException;
        void prepare(Connection c) throws SQLException;
    }

    /** Caller holds the current ARM lease. Pausing never resets funding or tenancy history. */
    public Property resumeExpected(Property expected, UUID observedOwner) throws SQLException {
        return resumeExpected(expected, observedOwner, null);
    }

    /**
     * Same as resumeExpected, with a two-phase preparation in the same transaction. Order: preparation.lockFirst
     * (the rent mutex, which precedes the property lock in the global order), property row lock and CAS check,
     * the resume owner write when ARM shows a new owner (epoch + 1), preparation.prepare, then the unsettled-event
     * check and the enable CAS. Payout retirement uses prepare to settle this home's own never-attempted
     * reservations that can no longer be paid; because it runs after the owner write it sees the ARM sale that
     * paused homes never reconcile, so an old owner's never-attempted current-day reservation retires as
     * OWNER_CHANGED instead of refusing the resume. A refused resume rolls back the owner write and every
     * retirement together, so a retirement is only durable with the epoch bump that makes the row unpayable.
     * Null preparation: exactly the two-argument form, unsettled check before the owner write.
     */
    public Property resumeExpected(Property expected, UUID observedOwner, ResumePreparation preparation) throws SQLException {
        Objects.requireNonNull(observedOwner);
        return ownerTransaction(c -> {
            if (preparation != null) preparation.lockFirst(c);
            OwnerRow row = ownerRow(c, expected.propertyId());
            if (!row.property().equals(expected) || expected.enabled()) throw new ResumeRefused("Paused property changed");
            if (preparation == null) refuseUnsettled(c, expected.propertyId());
            int epoch = expected.ownershipEpoch();
            if (!Objects.equals(expected.ownerUuid(), observedOwner)) {
                epoch = Math.incrementExact(epoch);
                writeOwner(c, row, observedOwner, epoch, "resume-current-arm-owner");
            }
            if (preparation != null) {
                preparation.prepare(c);
                refuseUnsettled(c, expected.propertyId());
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE npc_properties SET enabled=1,row_version=row_version+1 WHERE property_id=? AND enabled=0 AND owner_uuid=? AND ownership_epoch=?")) {
                ps.setBytes(1, Uuids.toBytes(expected.propertyId())); ps.setBytes(2, Uuids.toBytes(observedOwner));
                ps.setInt(3, epoch); ps.setQueryTimeout(5);
                if (ps.executeUpdate() != 1) throw new SQLException("Resume compare-and-set lost");
                checkWarnings(c, ps, null);
            }
            return ownerRow(c, expected.propertyId()).property();
        });
    }

    /** Resume refuses while any of the property's events is neither PAID nor FAILED; locks what it finds. */
    private static void refuseUnsettled(Connection c, UUID propertyId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT event_id FROM npc_economy_events WHERE property_id=? AND state NOT IN ('PAID','FAILED') LIMIT 1 FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(propertyId)); ps.setQueryTimeout(5);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) throw new ResumeRefused("Unsettled property event requires review before resume");
            }
        }
    }

    /**
     * Checks current ownership snapshot and rent bounds under the property row lock, then sets the asking
     * rent now. Pending state is read from SQL under the same lock, never assumed: with nothing pending this
     * is exactly the legacy write; a pending change is resolved and its columns cleared in this transaction.
     */
    public Property setAskingRentExpected(Property expected, long cents) throws SQLException {
        return ownerTransaction(c -> setRentNow(c, expected, cents));
    }

    /**
     * Immediate rent under the row lock. No pending change: the pre-V31 UPDATE (asking_rent_cents and
     * row_version only). A pending change of the same owner and epoch is REPLACED, an earlier owner's is
     * CANCELLED; either way it is resolved and the pending columns cleared before this transaction commits,
     * so no older schedule can reappear and overwrite this rent.
     */
    private static Property setRentNow(Connection c, Property expected, long cents) throws SQLException {
        OwnerRow row = ownerRow(c, expected.propertyId());
        Property current = row.property();
        if (!current.equals(expected) || !current.enabled() || !rentInRange(current, cents))
            throw new ResumeRefused("Property changed or rent outside allowed range");
        PendingRent pending = pendingRent(c, current.propertyId());
        if (pending == null) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE npc_properties SET asking_rent_cents=?,row_version=row_version+1 WHERE property_id=? AND row_version=?")) {
                ps.setLong(1, cents); ps.setBytes(2, Uuids.toBytes(current.propertyId()));
                ps.setLong(3, row.revision()); ps.setQueryTimeout(5);
                if (ps.executeUpdate() != 1) throw new SQLException("Rent compare-and-set lost");
                checkWarnings(c, ps, null);
            }
        } else {
            // An earlier owner's change never binds this owner; this owner's own change is superseded.
            resolveRentChange(c, pending.changeId(), pendingOwnedBy(c, pending, current) ? "REPLACED" : "CANCELLED");
            writeRent(c, current, row.revision(), cents);
        }
        return ownerRow(c, expected.propertyId()).property();
    }

    /** Most economy days an owner can be asked to wait (housing.rent-change-delay-days). */
    public static final int MAX_RENT_DELAY_DAYS = 30;
    /** Most properties one daily step applies; matches the enrolled-property limit. */
    public static final int MAX_DUE_RENT_CHANGES = 512;

    /** Outcome of one owner rent command. property is the row after this transaction. */
    public record RentChange(Outcome outcome, Property property, long changeId, long cents,
                             java.time.LocalDate effectiveDay, int notices) {
        public enum Outcome { APPLIED_NOW, SCHEDULED, ALREADY_SCHEDULED, CANCELLED, UNCHANGED }
    }
    /**
     * One pending change reached its day. APPLIED: the new rent took effect. CANCELLED: owner or terms changed.
     * REPLACED: the asking rent no longer equals the change's from_rent (a newer rent was set), so it is kept.
     */
    public record DueRent(Property before, Property after, long changeId, Resolution resolution) {
        public enum Resolution { APPLIED, CANCELLED, REPLACED }
        public DueRent { Objects.requireNonNull(resolution); }
        public boolean applied() { return resolution == Resolution.APPLIED; }
    }
    /** deferred: due changes skipped because the caller excluded their property; they stay PENDING and count in stillPending. */
    public record DueRentResult(List<DueRent> changes, int stillPending, List<UUID> deferred) {
        public DueRentResult { changes = List.copyOf(changes); deferred = List.copyOf(deferred); }
        public DueRentResult(List<DueRent> changes, int stillPending) { this(changes, stillPending, List.of()); }
    }
    private record PendingRent(long changeId, int cents, java.time.LocalDate effectiveDay) { }

    /**
     * Delay 0 is setAskingRentExpected: applies now, and any pending change is resolved in the same transaction
     * (read from SQL under the row lock; no caller-side flag can skip it). Otherwise, under the same row lock,
     * bounds and compare-and-set: delay N stores one pending change effective on today+N, replacing an older
     * one, and writes one notice per current resident.
     * The same amount again keeps the pending change (no new notices); the current amount cancels it.
     */
    public RentChange changeAskingRent(Property expected, long cents, java.time.LocalDate today, int delayDays)
            throws SQLException {
        Objects.requireNonNull(expected); Objects.requireNonNull(today);
        if (delayDays < 0 || delayDays > MAX_RENT_DELAY_DAYS || today.getYear() < 1 || today.getYear() > 9000)
            throw new IllegalArgumentException("Invalid rent change delay");
        if (delayDays == 0) {
            Property updated = ownerTransaction(c -> setRentNow(c, expected, cents));
            return new RentChange(RentChange.Outcome.APPLIED_NOW, updated, 0, cents, today, 0);
        }
        return ownerTransaction(c -> {
            OwnerRow row = ownerRow(c, expected.propertyId());
            Property current = row.property();
            if (!current.equals(expected) || !current.enabled() || !rentInRange(current, cents))
                throw new ResumeRefused("Property changed or rent outside allowed range");
            PendingRent pending = pendingRent(c, current.propertyId());
            if (pending != null && !pendingOwnedBy(c, pending, current)) {
                // An earlier owner's change never binds this owner: cancel it before deciding anything.
                resolveRentChange(c, pending.changeId(), "CANCELLED");
                writeRent(c, current, row.revision(), current.askingRentCents());
                row = ownerRow(c, current.propertyId());
                if (!row.property().equals(current)) throw new SQLException("Rent row changed under lock");
                pending = null;
            }
            if (pending != null && pending.cents() == cents)
                return new RentChange(RentChange.Outcome.ALREADY_SCHEDULED, current, pending.changeId(), cents,
                        pending.effectiveDay(), notices(c, pending.changeId()));
            if (cents == current.askingRentCents()) {
                if (pending == null) return new RentChange(RentChange.Outcome.UNCHANGED, current, 0, cents, today, 0);
                resolveRentChange(c, pending.changeId(), "CANCELLED");
                writeRent(c, current, row.revision(), current.askingRentCents());
                return new RentChange(RentChange.Outcome.CANCELLED, current, pending.changeId(), cents, today, 0);
            }
            if (current.ownerUuid() == null) throw new ResumeRefused("Rent change needs an owner");
            java.time.LocalDate effective = today.plusDays(delayDays);
            if (pending != null) resolveRentChange(c, pending.changeId(), "REPLACED");
            long changeId;
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO npc_rent_changes (property_id, owner_uuid, ownership_epoch, from_rent_cents, to_rent_cents,
                      requested_day, effective_day) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setQueryTimeout(5);
                ps.setBytes(1, Uuids.toBytes(current.propertyId())); ps.setBytes(2, Uuids.toBytes(current.ownerUuid()));
                ps.setInt(3, current.ownershipEpoch()); ps.setInt(4, current.askingRentCents()); ps.setLong(5, cents);
                ps.setString(6, today.toString()); ps.setString(7, effective.toString());
                if (ps.executeUpdate() != 1) throw new SQLException("Rent change not recorded");
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next() || (changeId = keys.getLong(1)) <= 0) throw new SQLException("Rent change id missing");
                }
                checkWarnings(c, ps, null);
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE npc_properties SET pending_rent_change_id=?, pending_asking_rent_cents=?,
                      pending_rent_effective_day=?, row_version=row_version+1 WHERE property_id=? AND row_version=?
                    """)) {
                ps.setQueryTimeout(5);
                ps.setLong(1, changeId); ps.setLong(2, cents); ps.setString(3, effective.toString());
                ps.setBytes(4, Uuids.toBytes(current.propertyId())); ps.setLong(5, row.revision());
                if (ps.executeUpdate() != 1) throw new SQLException("Rent compare-and-set lost");
                checkWarnings(c, ps, null);
            }
            int notices;
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO npc_rent_change_notices (change_id, resident_id, property_id, tenancy_revision, economy_day)
                    SELECT ?, tenant_id, property_id, row_version, ? FROM npc_tenants WHERE property_id=? ORDER BY tenant_id LIMIT 5
                    """)) {
                ps.setQueryTimeout(5);
                ps.setLong(1, changeId); ps.setString(2, today.toString()); ps.setBytes(3, Uuids.toBytes(current.propertyId()));
                notices = ps.executeUpdate();
                checkWarnings(c, ps, null);
            }
            if (notices < 0 || notices > current.capacity()) throw new SQLException("Rent notice occupancy exceeds capacity");
            return new RentChange(RentChange.Outcome.SCHEDULED, ownerRow(c, current.propertyId()).property(),
                    changeId, cents, effective, notices);
        });
    }

    /**
     * Start of the day's cycle: every enabled property whose pending change is due (effective day on or
     * before today) gets its new asking rent in one transaction. A change whose owner snapshot or bounds no
     * longer hold is cancelled instead; one whose from_rent is no longer the asking rent (a newer rent was set)
     * is marked REPLACED and the current rent kept. Nothing else is written, so a replay on the same day changes nothing.
     */
    public DueRentResult applyDueRentChanges(java.time.LocalDate today, int limit) throws SQLException {
        return applyDueRentChanges(today, limit, Set.of());
    }

    /**
     * As above, except that a due change on an excluded property (one a payment or other ownership lease holds
     * right now) is neither locked nor written: it stays PENDING with its effective day, counts in stillPending
     * and is listed in deferred, so a later step applies it. An empty set is exactly the call above.
     */
    public DueRentResult applyDueRentChanges(java.time.LocalDate today, int limit, Set<UUID> excluded) throws SQLException {
        Objects.requireNonNull(today); Objects.requireNonNull(excluded);
        if (limit < 1 || limit > MAX_DUE_RENT_CHANGES) throw new IllegalArgumentException("Rent change batch");
        final Set<UUID> skip = Set.copyOf(excluded);
        return ownerTransaction(c -> {
            var ids = new ArrayList<UUID>();
            // Plain read for candidates; each row is then locked and re-checked, so other rows stay unlocked.
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT property_id FROM npc_properties WHERE pending_rent_change_id IS NOT NULL AND enabled=TRUE
                      AND pending_rent_effective_day<=? ORDER BY property_id LIMIT ?
                    """)) {
                ps.setQueryTimeout(5); ps.setString(1, today.toString()); ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) ids.add(Uuids.fromBytes(rs.getBytes(1)));
                    checkWarnings(c, ps, rs);
                }
            }
            var out = new ArrayList<DueRent>();
            var deferred = new ArrayList<UUID>();
            for (UUID id : ids) {
                if (skip.contains(id)) { deferred.add(id); continue; } // Held by a lease: untouched, applies later.
                OwnerRow row = ownerRow(c, id);
                Property before = row.property();
                PendingRent pending = pendingRent(c, id);
                if (!before.enabled() || pending == null || pending.effectiveDay().isAfter(today)) continue; // changed meanwhile
                UUID owner; int epoch; long fromRent;
                try (PreparedStatement ps = c.prepareStatement("""
                        SELECT property_id, owner_uuid, ownership_epoch, from_rent_cents, to_rent_cents, effective_day, state
                        FROM npc_rent_changes WHERE change_id=? FOR UPDATE
                        """)) {
                    ps.setQueryTimeout(5); ps.setLong(1, pending.changeId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !id.equals(Uuids.fromBytes(rs.getBytes("property_id")))
                                || rs.getInt("to_rent_cents") != pending.cents()
                                || !pending.effectiveDay().toString().equals(rs.getString("effective_day"))
                                || !"PENDING".equals(rs.getString("state")))
                            throw new SQLException("Rent change evidence mismatch");
                        owner = Uuids.fromBytes(rs.getBytes("owner_uuid")); epoch = rs.getInt("ownership_epoch");
                        fromRent = rs.getLong("from_rent_cents");
                        if (rs.wasNull()) throw new SQLException("Rent change evidence mismatch");
                        checkWarnings(c, ps, rs);
                    }
                }
                boolean valid = before.enabled() && owner.equals(before.ownerUuid()) && before.ownershipEpoch() == epoch
                        && rentInRange(before, pending.cents());
                // Compare-and-set on the starting rent: a rent set after this change was scheduled is newer and wins.
                DueRent.Resolution resolution = !valid ? DueRent.Resolution.CANCELLED
                        : before.askingRentCents() != fromRent ? DueRent.Resolution.REPLACED : DueRent.Resolution.APPLIED;
                resolveRentChange(c, pending.changeId(), resolution.name());
                writeRent(c, before, row.revision(),
                        resolution == DueRent.Resolution.APPLIED ? pending.cents() : before.askingRentCents());
                out.add(new DueRent(before, ownerRow(c, id).property(), pending.changeId(), resolution));
            }
            return new DueRentResult(out, pendingCount(c), deferred);
        });
    }

    /** due: enabled properties whose pending change is due on or before the day (what applyDueRentChanges would list). */
    public record DueRentStatus(int due, int pending) {
        public DueRentStatus {
            if (due < 0 || pending < 0 || due > pending) throw new IllegalArgumentException("Rent change status");
        }
    }

    /**
     * Read-only: how many due changes applyDueRentChanges would find for this day, and how many are pending at all.
     * Takes no row lock and writes nothing. The guarded rent step uses it so it takes no ownership leases on a day
     * when nothing is due.
     */
    public DueRentStatus dueRentStatus(java.time.LocalDate today) throws SQLException {
        Objects.requireNonNull(today);
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT COALESCE(SUM(enabled=TRUE AND pending_rent_effective_day<=?), 0), COUNT(*)
                FROM npc_properties WHERE pending_rent_change_id IS NOT NULL
                """)) {
            ps.setQueryTimeout(5); ps.setString(1, today.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Rent change status missing");
                var status = new DueRentStatus(rs.getInt(1), rs.getInt(2));
                checkWarnings(c, ps, rs);
                return status;
            }
        }
    }

    /** Properties with a change still pending, any day. Startup uses it to keep applying after the delay is set to 0. */
    public int countPendingRentChanges() throws SQLException {
        try (Connection c = database.getConnection()) { return pendingCount(c); }
    }

    private static int pendingCount(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM npc_properties WHERE pending_rent_change_id IS NOT NULL")) {
            ps.setQueryTimeout(5);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Pending rent count missing");
                int count = rs.getInt(1);
                checkWarnings(c, ps, rs);
                return count;
            }
        }
    }

    private static boolean rentInRange(Property p, long cents) {
        return cents > 0 && cents <= Integer.MAX_VALUE && cents >= (long) p.referenceRentCents() * 50 / 100
                && cents <= (long) p.referenceRentCents() * 150 / 100;
    }

    private static PendingRent pendingRent(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT pending_rent_change_id, pending_asking_rent_cents, pending_rent_effective_day
                FROM npc_properties WHERE property_id=? FOR UPDATE
                """)) {
            ps.setQueryTimeout(5); ps.setBytes(1, Uuids.toBytes(id));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Rent property missing");
                long change = rs.getLong(1);
                boolean none = rs.wasNull();
                int cents = rs.getInt(2);
                String day = rs.getString(3);
                checkWarnings(c, ps, rs);
                if (none) return null;
                if (change <= 0 || cents <= 0 || day == null) throw new SQLException("Invalid pending rent");
                return new PendingRent(change, cents, java.time.LocalDate.parse(day));
            }
        }
    }

    private static boolean pendingOwnedBy(Connection c, PendingRent pending, Property current) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT property_id, owner_uuid, ownership_epoch, state FROM npc_rent_changes WHERE change_id=? FOR UPDATE")) {
            ps.setQueryTimeout(5); ps.setLong(1, pending.changeId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !current.propertyId().equals(Uuids.fromBytes(rs.getBytes("property_id")))
                        || !"PENDING".equals(rs.getString("state")))
                    throw new SQLException("Rent change evidence mismatch");
                boolean owned = Uuids.fromBytes(rs.getBytes("owner_uuid")).equals(current.ownerUuid())
                        && rs.getInt("ownership_epoch") == current.ownershipEpoch();
                checkWarnings(c, ps, rs);
                return owned;
            }
        }
    }

    private static int notices(Connection c, long changeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM npc_rent_change_notices WHERE change_id=?")) {
            ps.setQueryTimeout(5); ps.setLong(1, changeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Rent notice count missing");
                return rs.getInt(1);
            }
        }
    }

    private static void resolveRentChange(Connection c, long changeId, String state) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE npc_rent_changes SET state=?, resolved_at=CURRENT_TIMESTAMP(3) WHERE change_id=? AND state='PENDING'")) {
            ps.setQueryTimeout(5); ps.setString(1, state); ps.setLong(2, changeId);
            if (ps.executeUpdate() != 1) throw new SQLException("Rent change already resolved");
            checkWarnings(c, ps, null);
        }
    }

    /** Sets asking rent and clears any pending change, compare-and-set on the locked revision. */
    private static void writeRent(Connection c, Property current, long revision, long cents) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE npc_properties SET asking_rent_cents=?, pending_rent_change_id=NULL, pending_asking_rent_cents=NULL,
                  pending_rent_effective_day=NULL, row_version=row_version+1 WHERE property_id=? AND row_version=?
                """)) {
            ps.setQueryTimeout(5);
            ps.setLong(1, cents); ps.setBytes(2, Uuids.toBytes(current.propertyId())); ps.setLong(3, revision);
            if (ps.executeUpdate() != 1) throw new SQLException("Rent compare-and-set lost");
            checkWarnings(c, ps, null);
        }
    }

    /**
     * Legacy fixture helper; live callers use setAskingRentExpected under an ARM lease. It leaves any pending
     * change in place; applyDueRentChanges then marks that change REPLACED because from_rent no longer matches.
     */
    public void setAskingRent(UUID propertyId, int askingRentCents) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE npc_properties SET asking_rent_cents = ?, row_version = row_version + 1 WHERE property_id = ?")) {
            ps.setInt(1, askingRentCents);
            ps.setBytes(2, Uuids.toBytes(propertyId));
            ps.executeUpdate();
        }
    }

    /** Clamps and sets a property's condition score (0-100). */
    public void setCondition(UUID propertyId, int condition) throws SQLException {
        int clamped = Math.max(0, Math.min(100, condition));
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE npc_properties SET condition_score = ?, row_version = row_version + 1 WHERE property_id = ?")) {
            ps.setInt(1, clamped);
            ps.setBytes(2, Uuids.toBytes(propertyId));
            ps.executeUpdate();
        }
    }

    /** Returns {seed, sinks, payouts} cents for the property's lifetime funding. */
    public long[] fundingOf(UUID propertyId) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT one_time_seed_cents, successful_cash_sinks_cents, successful_cash_payouts_cents "
                             + "FROM npc_property_funding WHERE property_id = ?")) {
            ps.setBytes(1, Uuids.toBytes(propertyId));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3)};
                }
            }
        }
        return new long[]{0L, 0L, 0L};
    }

    public List<Property> listEnrolled() throws SQLException {
        List<Property> out = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM npc_properties WHERE enabled = TRUE ORDER BY market_id, region_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(map(rs));
            }
        }
        return out;
    }

    public Optional<Property> findByWorldRegion(UUID worldUuid, String regionId) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM npc_properties WHERE world_uuid = ? AND region_id = ?")) {
            ps.setBytes(1, Uuids.toBytes(worldUuid));
            ps.setString(2, regionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static Property map(ResultSet rs) throws SQLException {
        return new Property(
                Uuids.fromBytes(rs.getBytes("property_id")),
                Uuids.fromBytes(rs.getBytes("world_uuid")),
                rs.getString("region_id"),
                rs.getString("market_id"),
                rs.getString("class_key"),
                rs.getInt("capacity"),
                rs.getInt("reference_rent_cents"),
                rs.getInt("asking_rent_cents"),
                readNullableUuid(rs, "owner_uuid"),
                rs.getInt("ownership_epoch"),
                rs.getInt("condition_score"),
                rs.getBoolean("enabled"));
    }

    private static void setNullableUuid(PreparedStatement ps, int idx, UUID uuid) throws SQLException {
        if (uuid == null) {
            ps.setNull(idx, java.sql.Types.BINARY);
        } else {
            ps.setBytes(idx, Uuids.toBytes(uuid));
        }
    }

    private static UUID readNullableUuid(ResultSet rs, String col) throws SQLException {
        byte[] b = rs.getBytes(col);
        return b == null ? null : Uuids.fromBytes(b);
    }
}
