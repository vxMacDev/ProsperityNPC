package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.AccountLinkRepository.Observed;
import com.prosperitysmp.npc.economy.AccountLinkRepository.Source;
import com.prosperitysmp.npc.economy.AccountLinkRepository.SplitOutcome;
import com.prosperitysmp.npc.property.OwnerDeskRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static com.prosperitysmp.npc.economy.LinkedAccountsHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** linked-accounts storage on a real MariaDB: pair order, refusals, rejoin, split day, day pin and the desk read. */
@Timeout(300)
class AccountLinkRepositoryIT {
    private static LinkedAccountsHarness db;

    @BeforeAll static void boot() throws Exception { db = LinkedAccountsHarness.boot("npc_linked_repo"); }
    @AfterAll static void stop() throws Exception { if (db != null) db.close(); }
    @BeforeEach void fresh() throws SQLException { db.reset(); }

    @Test void aPairIsOneOrderedRowWhicheverWayItIsNamed() throws Exception {
        assertEquals(Observed.NEW, db.link(JAVA, BEDROCK, DAY));
        assertEquals(Observed.SEEN, db.link(JAVA, BEDROCK, DAY.plusDays(1)));
        assertEquals(1, db.count("npc_account_links"));
        var row = db.rows("SELECT HEX(account_low),HEX(account_high),HEX(java_uuid),HEX(bedrock_uuid),source,first_seen_day FROM npc_account_links").getFirst();
        assertEquals(List.of(hex(BEDROCK).toUpperCase(), hex(JAVA).toUpperCase(), hex(JAVA).toUpperCase(), hex(BEDROCK).toUpperCase(),
                "FLOODGATE_LOGIN", DAY.toString()), row, "low is the smaller in byte order; first seen day never moves");
        // Splitting by either order finds the same row.
        assertEquals(SplitOutcome.SAVED, db.links().staffSplit(JAVA, BEDROCK, "staff", "test", db.days()).outcome());
        assertEquals(SplitOutcome.ALREADY_SPLIT, db.links().staffSplit(BEDROCK, JAVA, "staff", "test", db.days()).outcome());
        // The table itself refuses an unordered pair.
        assertThrows(SQLException.class, () -> db.execute("INSERT INTO npc_account_links(account_low,account_high,java_uuid,bedrock_uuid,source,"
                + "first_seen_at,first_seen_day,last_seen_at) VALUES (?,?,?,?,'FLOODGATE_LOGIN',?,?,?)",
                STRANGER, new UUID(0, 9), STRANGER, new UUID(0, 9), db.clock.instant(), DAY, db.clock.instant()));
        assertEquals(1, db.count("npc_audit_log WHERE action='LINK_SAVED' AND actor='floodgate'"));
    }

    @Test void refusesTheReserveTheSameAccountAndWrongFloodgateRoles() throws Exception {
        var links = db.links();
        var at = db.clock.instant();
        // The reserve account on the Java side, and on the Bedrock side.
        assertThrows(IllegalArgumentException.class, () -> links.observe(JAVA, BEDROCK, Source.FLOODGATE_LOGIN, at, DAY, JAVA));
        assertThrows(IllegalArgumentException.class, () -> links.observe(JAVA, BEDROCK, Source.FLOODGATE_LOOKUP, at, DAY, BEDROCK));
        assertThrows(IllegalArgumentException.class, () -> links.observe(BEDROCK, BEDROCK, Source.FLOODGATE_LOGIN, at, DAY, RESERVE));
        // A Bedrock side that is not a Floodgate ID, and a Java side that is one.
        assertThrows(IllegalArgumentException.class, () -> links.observe(JAVA, STRANGER, Source.FLOODGATE_LOGIN, at, DAY, RESERVE));
        assertThrows(IllegalArgumentException.class, () -> links.observe(new UUID(0, 0x55), BEDROCK, Source.FLOODGATE_LOGIN, at, DAY, RESERVE));
        // Staff rows are never written through observe in this release.
        assertThrows(IllegalArgumentException.class, () -> links.observe(JAVA, BEDROCK, Source.STAFF, at, DAY, RESERVE));
        assertEquals(0, db.count("npc_account_links"));
        assertEquals(0, db.count("npc_audit_log"));
        // The table refuses a Floodgate row naming one account twice.
        assertThrows(SQLException.class, () -> db.execute("INSERT INTO npc_account_links(account_low,account_high,java_uuid,bedrock_uuid,source,"
                + "first_seen_at,first_seen_day,last_seen_at) VALUES (?,?,?,?,'FLOODGATE_LOGIN',?,?,?)",
                BEDROCK, JAVA, JAVA, JAVA, at, DAY, at));
        // Positive control: the same call with a real pair and no reserve configured saves.
        assertEquals(Observed.NEW, links.observe(JAVA, BEDROCK, Source.FLOODGATE_LOOKUP, at, DAY, null));
        assertEquals(1, db.count("npc_audit_log WHERE action='LINK_SAVED' AND actor='lookup'"));
    }

    @Test void seeingASplitPairLinkedAgainRejoinsItAtOnce() throws Exception {
        db.link(JAVA, BEDROCK, DAY);
        var split = db.links().staffSplit(JAVA, BEDROCK, "Mod_1", "wrong match", db.days());
        assertEquals(new AccountLinkRepository.Split(SplitOutcome.SAVED, DAY.plusDays(1)), split);
        assertEquals(Observed.REJOINED, db.link(JAVA, BEDROCK, DAY));
        assertEquals(List.of(List.of("1")), db.rows("SELECT COUNT(*) FROM npc_account_links WHERE split_effective_day IS NULL "
                + "AND split_at IS NULL AND split_by IS NULL AND split_reason IS NULL"));
        assertEquals(List.of(List.of("floodgate", "LINK_SAVED"), List.of("Mod_1", "LINK_SPLIT"), List.of("floodgate", "LINK_REJOINED")),
                db.rows("SELECT actor,action FROM npc_audit_log ORDER BY id"));
        try (Connection c = tx()) {
            assertEquals(BEDROCK, db.links().unitsFor(c, DAY.plusDays(1), true).unitOf(JAVA), "active again on the day the split would have started");
            c.rollback();
        }
    }

    @Test void aStaffSplitStartsTomorrowAndASecondSplitIsRefused() throws Exception {
        db.link(JAVA, BEDROCK, DAY);
        var links = db.links();
        assertEquals(new AccountLinkRepository.Split(SplitOutcome.SAVED, DAY.plusDays(1)),
                links.staffSplit(BEDROCK, JAVA, "staff", "test", db.days()));
        assertEquals(new AccountLinkRepository.Split(SplitOutcome.ALREADY_SPLIT, DAY.plusDays(1)),
                links.staffSplit(JAVA, BEDROCK, "staff", "again", db.days()));
        assertEquals(new AccountLinkRepository.Split(SplitOutcome.NOT_LINKED, null),
                links.staffSplit(JAVA, STRANGER, "staff", "test", db.days()));
        assertEquals(List.of(List.of(DAY.plusDays(1).toString(), "staff", "test")),
                db.rows("SELECT split_effective_day,split_by,split_reason FROM npc_account_links"));
        assertEquals(1, db.count("npc_audit_log WHERE action='LINK_SPLIT'"));
    }

    @Test void aSplitStartingTomorrowStillMergesTodayAndNotTomorrow() throws Exception {
        db.link(JAVA, BEDROCK, DAY.minusDays(3));
        db.links().staffSplit(JAVA, BEDROCK, "staff", "test", db.days());
        try (Connection c = tx()) {
            var today = db.links().unitsFor(c, DAY, true);
            assertEquals(BEDROCK, today.unitOf(JAVA));
            assertEquals(List.of(BEDROCK, JAVA), today.members(JAVA));
            var tomorrow = db.links().unitsFor(c, DAY.plusDays(1), true);
            assertEquals(JAVA, tomorrow.unitOf(JAVA));
            assertEquals(BEDROCK, tomorrow.unitOf(BEDROCK));
            c.commit();
        }
        try (Connection c = db.database.getConnection()) {
            assertEquals(2, AccountLinkRepository.groupSize(c, JAVA, DAY));
            assertEquals(1, AccountLinkRepository.groupSize(c, JAVA, DAY.plusDays(1)));
        }
    }

    @Test void aSplitStalledAcrossTheResetStartsTheDayAfterItsSave() throws Exception {
        // No pins at all, so only the clock read inside the save decides. Reset is 04:00 Chicago = 09:00Z on DAY.
        db.link(JAVA, BEDROCK, DAY.minusDays(3));
        var afterReset = db.clock.instant();
        db.clock.set(java.time.Instant.parse("2026-09-07T08:59:59.000Z"));
        assertEquals(DAY.minusDays(1), db.days().today(), "fixture: the worker starts before the reset");
        java.util.concurrent.CompletableFuture<AccountLinkRepository.Split> split;
        try (Connection stall = db.database.getConnection()) {
            stall.setAutoCommit(false);
            try {
                LinkedAccountsHarness.execute(stall, "SELECT link_id FROM npc_account_links FOR UPDATE");
                var days = db.days();
                split = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try { return db.links().staffSplit(JAVA, BEDROCK, "staff", "test", days); }
                    catch (SQLException failure) { throw new java.util.concurrent.CompletionException(failure); }
                });
                db.awaitLockWait(split, "%FROM npc_account_links%FOR UPDATE%", "the pair's row");
                db.clock.set(afterReset);
                stall.commit();
            } catch (Exception | AssertionError failure) { stall.rollback(); throw failure; }
        }
        assertEquals(new AccountLinkRepository.Split(SplitOutcome.SAVED, DAY.plusDays(1)), split.get(60, java.util.concurrent.TimeUnit.SECONDS),
                "saved on DAY, so it starts on DAY + 1 and DAY keeps one limit");
        assertEquals(List.of(List.of(DAY.plusDays(1).toString(), "2026-09-07T12:00:00.001")),
                db.rows("SELECT split_effective_day,split_at FROM npc_account_links"), "split_at is the save time too");
    }

    @Test void aSplitNeverStartsOnOrBeforeADayAlreadyPinnedMerged() throws Exception {
        db.link(JAVA, BEDROCK, DAY.minusDays(3));
        db.execute("INSERT INTO npc_linked_account_days(economy_day) VALUES (?),(?)", DAY, DAY.plusDays(2));
        assertEquals(new AccountLinkRepository.Split(SplitOutcome.SAVED, DAY.plusDays(3)), db.links().staffSplit(JAVA, BEDROCK, "staff", "test", db.days()));
        assertEquals(1, db.count("npc_audit_log WHERE action='LINK_SPLIT' AND detail LIKE ?", "%starts=" + DAY.plusDays(3) + "%"));
        try (Connection c = tx()) {
            // Every pinned day keeps the grouping it was merged with; the first day after the last pin is split.
            assertEquals(BEDROCK, db.links().unitsFor(c, DAY, false).unitOf(JAVA));
            assertEquals(BEDROCK, db.links().unitsFor(c, DAY.plusDays(2), false).unitOf(JAVA));
            assertEquals(JAVA, db.links().unitsFor(c, DAY.plusDays(3), true).unitOf(JAVA));
            c.rollback();
        }
    }

    @Test void aSplitWaitsForTheRentMutexAndSeesAPinCommittedWhileItWaited() throws Exception {
        db.link(JAVA, BEDROCK, DAY.minusDays(3));
        java.util.concurrent.CompletableFuture<AccountLinkRepository.Split> split;
        try (Connection holder = db.database.getConnection()) {
            holder.setAutoCommit(false);
            try {
                LinkedAccountsHarness.execute(holder, "SELECT singleton FROM npc_rent_mutex WHERE singleton=1 FOR UPDATE");
                split = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try { return db.links().staffSplit(JAVA, BEDROCK, "staff", "test", db.days()); }
                    catch (SQLException failure) { throw new java.util.concurrent.CompletionException(failure); }
                });
                db.awaitMutexWait(split);
                // The holder pins DAY + 1 the way a batch does, under the mutex, while the split waits.
                LinkedAccountsHarness.execute(holder, "INSERT IGNORE INTO npc_linked_account_days(economy_day) VALUES (?)", DAY.plusDays(1));
                assertFalse(split.isDone());
                holder.commit();
            } catch (Exception | AssertionError failure) { holder.rollback(); throw failure; }
        }
        assertEquals(new AccountLinkRepository.Split(SplitOutcome.SAVED, DAY.plusDays(2)), split.get(60, java.util.concurrent.TimeUnit.SECONDS),
                "DAY + 1 was pinned merged before the split saved, so it starts after it");
    }

    @Test void theDayPinDecidesAndIsOnlyWrittenWhileTheFlagIsOn() throws Exception {
        db.link(JAVA, BEDROCK, DAY);
        LocalDate other = DAY.plusDays(5);
        try (Connection c = tx()) {
            // Flag off, no pin: every owner alone, and nothing is written.
            assertTrue(db.links().unitsFor(c, other, false).isIdentity());
            c.commit();
        }
        assertEquals(0, db.count("npc_linked_account_days"));
        try (Connection c = tx()) {
            // Flag on: the day is pinned and merged.
            assertEquals(BEDROCK, db.links().unitsFor(c, DAY, true).unitOf(JAVA));
            c.commit();
        }
        assertEquals(List.of(List.of(DAY.toString())), db.rows("SELECT economy_day FROM npc_linked_account_days"));
        try (Connection c = tx()) {
            // Flag off later the same day: the pin keeps the day merged.
            assertEquals(BEDROCK, db.links().unitsFor(c, DAY, false).unitOf(JAVA));
            // Another day with the flag off is still per account.
            assertTrue(db.links().unitsFor(c, other, false).isIdentity());
            c.commit();
        }
        assertEquals(1, db.count("npc_linked_account_days"));
        try (Connection c = db.database.getConnection()) {
            assertThrows(SQLException.class, () -> db.links().unitsFor(c, DAY, true), "needs the caller's transaction");
        }
    }

    @Test void showListsTheGroupAndItsSplitsForStaff() throws Exception {
        db.link(JAVA, BEDROCK, DAY);
        UUID otherBedrock = new UUID(0, 0x0009_0001_00ffL);
        db.link(JAVA, otherBedrock, DAY.plusDays(1));
        db.clock.set(NOW.plusSeconds(86_400));
        db.links().staffSplit(JAVA, otherBedrock, "staff", "test", db.days());
        var group = db.links().show(BEDROCK, DAY.plusDays(2));
        assertEquals(List.of(BEDROCK, JAVA), group.members());
        assertEquals(2, group.links().size(), "the split pair touches the group and is listed too");
        var lines = LinkedAccounts.showLines(group, "Ann", id -> id.equals(JAVA) ? "Ann" : id.equals(BEDROCK) ? "AnnBE" : "Old");
        assertEquals(List.of("Linked group: AnnBE (Bedrock), Ann (Java). Since " + DAY + ". Source: Floodgate.",
                "Split: Old and Ann. Separate from " + DAY.plusDays(2) + "."), lines);
        assertEquals(List.of("No saved links for Zed."), LinkedAccounts.showLines(db.links().show(STRANGER, DAY), "Zed", UUID::toString));
        assertTrue(db.links().inSavedGroup(BEDROCK, DAY));
        assertFalse(db.links().inSavedGroup(STRANGER, DAY));
    }

    @Test void theDeskShowsTheLineOnlyWithTheFlagOnAndALink() throws Exception {
        db.operating(JAVA, 1500, "desk-j");
        boolean[] flag = {true};
        var desk = new OwnerDeskRepository(db.database, db.clock, ZONE, RESET, () -> false, () -> flag[0], () -> 4500L);
        assertTrue(desk.read(WORLD, "desk-j", JAVA, DAY).orElseThrow().linked().isEmpty(), "alone: no line");
        db.link(JAVA, BEDROCK, DAY);
        var linked = desk.read(WORLD, "desk-j", JAVA, DAY).orElseThrow().linked().orElseThrow();
        assertEquals(2, linked.accounts());
        assertEquals("Shares a $45 daily rent limit with your linked account.", linked.line());
        flag[0] = false;
        assertTrue(desk.read(WORLD, "desk-j", JAVA, DAY).orElseThrow().linked().isEmpty(), "flag off: no line");
    }

    @Test void staffShapedRowsAreReadAsEdgesToo() throws Exception {
        try (Connection c = db.database.getConnection()) {
            StringBuilder sql = new StringBuilder("INSERT INTO npc_account_links(account_low,account_high,java_uuid,bedrock_uuid,source,"
                    + "first_seen_at,first_seen_day,last_seen_at) VALUES ");
            var args = new java.util.ArrayList<Object>();
            for (int i = 0; i < 3; i++) {
                sql.append(i == 0 ? "" : ",").append("(?,?,NULL,NULL,'STAFF',?,?,?)");
                args.addAll(List.of(new UUID(0, i + 1), new UUID(1, i + 1), db.clock.instant(), DAY, db.clock.instant()));
            }
            LinkedAccountsHarness.execute(c, sql.toString(), args.toArray());
        }
        try (Connection c = tx()) {
            assertEquals(3, AccountLinkRepository.activeEdges(c, DAY).size());
            c.rollback();
        }
    }

    private static Connection tx() throws SQLException {
        Connection c = db.database.getConnection();
        c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        c.setAutoCommit(false);
        return c;
    }
}
