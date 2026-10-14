package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.ReadSnapshot;
import com.prosperitysmp.npc.economy.UtilityServiceRepository;
import java.sql.SQLException;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/** One read-only snapshot for all three service components. No financial or premium authorization. */
public final class ServiceAssessmentRepository {
    private final Database database;
    private final Clock clock;
    private final UtilityServiceRepository utilities;
    private final IncidentServiceRepository incidents;
    private final RetentionRepository retention;

    public ServiceAssessmentRepository(Database database, Clock clock, ZoneId zone, int resetHour) {
        this.database = Objects.requireNonNull(database);
        this.clock = Objects.requireNonNull(clock);
        utilities = new UtilityServiceRepository(database, clock);
        incidents = new IncidentServiceRepository(database, clock);
        retention = new RetentionRepository(database, clock, zone, resetHour);
    }

    /**
     * Worker-only. From must be an exact millisecond, within the preceding 30 days.
     * Utilities/incidents select due dates; retention selects admissions and only scores mature episodes.
     * Equal window starts do not imply interchangeable cohorts or denominators.
     */
    public ServiceAssessment read(UUID owner, Instant from, int retentionHorizonDays) throws SQLException {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(from);
        try (ReadSnapshot snapshot = ReadSnapshot.open(database, clock)) {
            return read(snapshot, owner, from, retentionHorizonDays);
        }
    }

    /** Borrow only; the outer composition owns rollback and close, including on failure. */
    public ServiceAssessment read(ReadSnapshot snapshot, UUID owner, Instant from, int retentionHorizonDays) throws SQLException {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(from);
        try {
            snapshot.connection(database);
            return new ServiceAssessment(utilities.read(snapshot, owner, from),
                    incidents.read(snapshot, owner, from), retention.read(snapshot, owner, from, retentionHorizonDays));
        } catch (RuntimeException failure) {
            throw new SQLException("Invalid combined service evidence", failure);
        }
    }

    /** Recheck recorded service in the admission transaction; never opens or ends a second view. */
    public ServiceAssessment readInTransaction(Connection c, Instant at, UUID owner, Instant from, int retentionHorizonDays)
            throws SQLException {
        ReadSnapshot.requireTransaction(c);
        try {
            return new ServiceAssessment(utilities.readInTransaction(c, at, owner, from),
                    incidents.readInTransaction(c, at, owner, from),
                    retention.readInTransaction(c, at, owner, from, retentionHorizonDays));
        } catch (RuntimeException failure) {
            throw new SQLException("Invalid transactional service evidence", failure);
        }
    }
}
