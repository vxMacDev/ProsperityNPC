package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** Direct refund claims against a real MariaDB: debit timing, resolution and evidence uniqueness, and the report. */
@Timeout(300)
class RefundClaimsIT {
    private static final String PROVIDER = "a".repeat(64);
    private static final String REUSED = "This refund was already used to resolve another held payment. If staff made a"
            + " second, separate refund for this held payment, run the tool again with --apply --distinct-refund";
    private static final String EVIDENCE_REUSED = "This refund evidence file was already used to resolve another held payment";
    private static final AtomicLong EVIDENCE = new AtomicLong(1);
    private static EconomyTestDatabase db;
    private static UUID reserve;
    private static TransferJournal journal;
    private static EconomyEventRepository events;

    @BeforeAll
    static void boot() throws Exception {
        db = EconomyTestDatabase.start("npc_refund_claims");
        reserve = UUID.randomUUID();
        journal = new TransferJournal(db.database, reserve);
        events = new EconomyEventRepository(db.database);
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) db.close();
    }

    private static String evidence() { return String.format("%064x", EVIDENCE.incrementAndGet()); }

    private static long bits(double value) { return Double.doubleToRawLongBits(value); }

    /** A $10 upgrade whose debit intent exists, so its attempt has a debit time. Returns the attempt id. */
    private static UUID startedDebit(UUID owner) throws SQLException {
        UUID property = db.enroll(owner, 50_000);
        long event = events.insertPending(EconomyEvent.pending("refund-it|" + UUID.randomUUID(), 1_000, EventSource.UPGRADE,
                property, owner, 0, owner, "2026-10-14"));
        Handle handle = new Handle(UUID.randomUUID(), event, UUID.randomUUID(), PROVIDER);
        assertTrue(journal.beginDebit(handle).newlyStarted());
        return handle.attemptId();
    }

    private static void claim(RefundClaims.Source source, UUID payment, UUID resolution, UUID payer, double before,
                              double after, double debitAfter, String hash, boolean distinct) throws SQLException {
        try (Connection c = db.database.getConnection()) {
            c.setAutoCommit(false);
            try {
                RefundClaims.claim(c, source, payment, resolution, payer, bits(before), bits(after), bits(debitAfter), hash, distinct);
                c.commit();
            } catch (SQLException | RuntimeException failure) {
                c.rollback();
                throw failure;
            }
        }
    }

    private static long claims(UUID resolution) throws SQLException {
        return db.scalarLong("SELECT COUNT(*) FROM npc_refund_claims WHERE resolution_id=?", resolution);
    }

    @Test
    void aClaimForAPaymentWithNoDebitTimeIsRefusedAndWritesNoClaim() throws SQLException {
        UUID resolution = UUID.randomUUID();
        for (RefundClaims.Source source : RefundClaims.Source.values()) {
            SQLException refused = assertThrows(SQLException.class, () -> claim(source, UUID.randomUUID(), resolution,
                    UUID.randomUUID(), 80.0, 90.0, 80.0, evidence(), false));
            assertEquals("Refund claim has no debit time", refused.getMessage(), source.name());
        }
        assertEquals(0, claims(resolution));
    }

    @Test
    void aFirstClaimIsRecordedWithItsSourceBalancesEvidenceAndFlag() throws SQLException {
        UUID owner = UUID.randomUUID(), resolution = UUID.randomUUID();
        UUID attempt = startedDebit(owner);
        String hash = evidence();
        claim(RefundClaims.Source.TRANSFER, attempt, resolution, owner, 80.0, 90.0, 80.0, hash, false);
        assertEquals("TRANSFER", db.scalarString("SELECT source FROM npc_refund_claims WHERE resolution_id=?", resolution));
        assertEquals(1, db.scalarLong("SELECT COUNT(*) FROM npc_refund_claims WHERE resolution_id=? AND payer_id=?",
                resolution, owner));
        assertEquals(bits(80.0), db.scalarLong("SELECT refund_before_bits FROM npc_refund_claims WHERE resolution_id=?", resolution));
        assertEquals(bits(90.0), db.scalarLong("SELECT refund_after_bits FROM npc_refund_claims WHERE resolution_id=?", resolution));
        assertEquals(hash, db.scalarString("SELECT evidence_sha256 FROM npc_refund_claims WHERE resolution_id=?", resolution));
        assertEquals(0, db.scalarLong("SELECT distinct_override FROM npc_refund_claims WHERE resolution_id=?", resolution));
        assertEquals(1, db.scalarLong("SELECT COUNT(*) FROM npc_refund_claim_payers WHERE payer_id=?", owner));
    }

    @Test
    void oneResolutionIdCannotClaimTwoRefundsInOneTable() throws SQLException {
        UUID resolution = UUID.randomUUID();
        UUID firstOwner = UUID.randomUUID(), secondOwner = UUID.randomUUID();
        claim(RefundClaims.Source.TRANSFER, startedDebit(firstOwner), resolution, firstOwner, 10.0, 20.0, 10.0, evidence(), false);
        UUID secondAttempt = startedDebit(secondOwner);
        SQLException refused = assertThrows(SQLException.class, () -> claim(RefundClaims.Source.TRANSFER, secondAttempt,
                resolution, secondOwner, 30.0, 40.0, 30.0, evidence(), false));
        assertEquals("This resolution id already claimed a refund", refused.getMessage());
        assertEquals(1062, ((SQLException) refused.getCause()).getErrorCode());
        assertEquals(1, claims(resolution));
    }

    @Test
    void oneEvidenceFileClosesOnlyOnePaymentWhoeverPaid() throws SQLException {
        String hash = evidence();
        UUID firstOwner = UUID.randomUUID(), secondOwner = UUID.randomUUID();
        UUID firstResolution = UUID.randomUUID(), secondResolution = UUID.randomUUID();
        claim(RefundClaims.Source.TRANSFER, startedDebit(firstOwner), firstResolution, firstOwner, 10.0, 20.0, 10.0, hash, false);
        UUID secondAttempt = startedDebit(secondOwner);
        SQLException refused = assertThrows(SQLException.class, () -> claim(RefundClaims.Source.TRANSFER, secondAttempt,
                secondResolution, secondOwner, 55.0, 65.0, 55.0, hash, true));
        assertEquals(EVIDENCE_REUSED, refused.getMessage());
        assertEquals(0, claims(secondResolution));
    }

    @Test
    void aSecondRefundWithTheSameBalancesAfterTheDebitNeedsTheDistinctFlag() throws SQLException {
        UUID owner = UUID.randomUUID();
        UUID attempt = startedDebit(owner);
        UUID earlier = UUID.randomUUID();
        claim(RefundClaims.Source.TRANSFER, attempt, earlier, owner, 70.0, 80.0, 70.0, evidence(), false);
        UUID other = startedDebit(owner);
        // The earlier claim is recorded after the other payment's debit began (times compared in the database).
        db.execute("UPDATE npc_refund_claims SET recorded_at='2099-01-01 00:00:00.000' WHERE resolution_id=?", earlier);
        UUID refusedResolution = UUID.randomUUID();
        assertEquals(REUSED, assertThrows(SQLException.class, () -> claim(RefundClaims.Source.TRANSFER, other, refusedResolution,
                owner, 70.0, 99.0, 70.0, evidence(), false)).getMessage(), "the same before balance");
        assertEquals(REUSED, assertThrows(SQLException.class, () -> claim(RefundClaims.Source.TRANSFER, other, refusedResolution,
                owner, 11.0, 80.0, 11.0, evidence(), false)).getMessage(), "the same after balance");
        assertEquals(0, claims(refusedResolution));
        UUID distinct = UUID.randomUUID();
        claim(RefundClaims.Source.TRANSFER, other, distinct, owner, 70.0, 80.0, 70.5, evidence(), true);
        assertEquals(1, db.scalarLong("SELECT distinct_override FROM npc_refund_claims WHERE resolution_id=?", distinct));
    }

    @Test
    void aClaimRecordedBeforeTheDebitBeganDoesNotBlockIt() throws SQLException {
        UUID owner = UUID.randomUUID(), old = UUID.randomUUID();
        claim(RefundClaims.Source.TRANSFER, startedDebit(owner), old, owner, 61.0, 71.0, 61.0, evidence(), false);
        db.execute("UPDATE npc_refund_claims SET recorded_at='2000-01-01 00:00:00.000' WHERE resolution_id=?", old);
        UUID resolution = UUID.randomUUID();
        claim(RefundClaims.Source.TRANSFER, startedDebit(owner), resolution, owner, 61.0, 71.0, 61.0, evidence(), false);
        assertEquals(0, db.scalarLong("SELECT distinct_override FROM npc_refund_claims WHERE resolution_id=?", resolution));
    }

    @Test
    void anotherPayersClaimWithTheSameBalancesDoesNotBlock() throws SQLException {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        UUID firstResolution = UUID.randomUUID();
        claim(RefundClaims.Source.TRANSFER, startedDebit(first), firstResolution, first, 42.0, 52.0, 42.0, evidence(), false);
        db.execute("UPDATE npc_refund_claims SET recorded_at='2099-01-01 00:00:00.000' WHERE resolution_id=?", firstResolution);
        UUID resolution = UUID.randomUUID();
        claim(RefundClaims.Source.TRANSFER, startedDebit(second), resolution, second, 42.0, 52.0, 42.0, evidence(), false);
        assertEquals(1, claims(resolution));
    }

    @Test
    void theDistinctReportListsAtMostFiveOtherClaimsInRecordedOrder() throws SQLException {
        UUID owner = UUID.randomUUID();
        List<UUID> resolutions = new ArrayList<>();
        List<UUID> attempts = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            UUID attempt = startedDebit(owner);
            UUID resolution = UUID.randomUUID();
            claim(RefundClaims.Source.TRANSFER, attempt, resolution, owner, 33.0, 43.0 + i, 33.0, evidence(), i > 0);
            attempts.add(attempt);
            resolutions.add(resolution);
        }
        // Recorded times set so the newest claims read as the earliest: the report must follow recorded_at.
        for (int i = 0; i < 7; i++)
            db.execute("UPDATE npc_refund_claims SET recorded_at=? WHERE resolution_id=?",
                    java.sql.Timestamp.valueOf("2026-10-14 12:00:0" + (6 - i) + ".000"), resolutions.get(i));
        UUID asked = resolutions.get(3);
        String report = RefundClaims.distinctRefundReport(db.database, RefundClaims.Source.TRANSFER, attempts.get(3), asked,
                "staff:test");
        String prefix = RefundClaims.distinctRefundNotice(attempts.get(3), "staff:test") + " Claims with the same balances: ";
        assertTrue(report.startsWith(prefix), report);
        assertTrue(report.endsWith("."), report);
        String listed = report.substring(prefix.length());
        assertEquals(5, listed.split("; ").length, report);
        assertFalse(report.contains(asked.toString()), "the asked resolution is not listed among the others");
        assertFalse(report.contains(resolutions.get(0).toString()), "the latest-recorded other claim falls past the limit of five");
        int previous = -1;
        for (int i : new int[]{6, 5, 4, 2, 1}) {
            int at = report.indexOf("TRANSFER resolution " + resolutions.get(i));
            assertTrue(at > previous, "resolution " + i + " out of recorded order in " + report);
            previous = at;
        }
    }

    @Test
    void theDistinctReportSaysNoneWhenNoOtherClaimShares() throws SQLException {
        UUID owner = UUID.randomUUID(), resolution = UUID.randomUUID();
        UUID attempt = startedDebit(owner);
        claim(RefundClaims.Source.TRANSFER, attempt, resolution, owner, 12.25, 22.25, 12.25, evidence(), true);
        assertEquals(RefundClaims.distinctRefundNotice(attempt, "op") + " Claims with the same balances: none.",
                RefundClaims.distinctRefundReport(db.database, RefundClaims.Source.TRANSFER, attempt, resolution, "op"));
    }

    @Test
    void theDistinctReportLooksUpTheResolutionOnlyInItsOwnTable() throws SQLException {
        UUID owner = UUID.randomUUID(), resolution = UUID.randomUUID();
        UUID attempt = startedDebit(owner);
        claim(RefundClaims.Source.TRANSFER, attempt, resolution, owner, 1.0, 2.0, 1.0, evidence(), true);
        assertEquals("--distinct-refund was given for payment " + attempt + ", but resolution " + resolution
                        + " has no refund claim.",
                RefundClaims.distinctRefundReport(db.database, RefundClaims.Source.COMMERCE, attempt, resolution, "op"));
    }
}
