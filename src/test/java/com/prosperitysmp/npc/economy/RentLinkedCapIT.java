package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.RentReservationRepository.AppreciationScore;
import com.prosperitysmp.npc.economy.RentReservationRepository.Reserved;
import com.prosperitysmp.npc.property.Property;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static com.prosperitysmp.npc.economy.LinkedAccountsHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * linked-accounts on the real rent and appreciation reservation SQL. A Java owner (JAVA) and a Bedrock owner
 * (BEDROCK) each own homes. Linked, their homes share one benefit-unit limit; every event still names its own
 * home's owner. Covers the spec's RentLinkedCapIT, RentLinkedSpentIT, AppreciationLinkedIT, ProofInvarianceIT,
 * FlagOffGoldenIT, BoundedLinksIT and ConcurrentLinkIT on one database.
 */
@Timeout(300)
class RentLinkedCapIT {
    private static final AppreciationScore TOP = (c, p, at) -> OptionalDouble.of(100.0);
    private static final String RENT = "npc_economy_events WHERE source='npc-property-rent'";
    private static LinkedAccountsHarness db;

    @BeforeAll static void boot() throws Exception { db = LinkedAccountsHarness.boot("npc_linked_rent"); }
    @AfterAll static void stop() throws Exception { if (db != null) db.close(); }
    @BeforeEach void fresh() throws SQLException { db.reset(); }

    // ---- RentLinkedCapIT

    @Test void linkedOwnersShareOneCapSplitPerHomeAndEachHomePaysItsOwnOwner() throws Exception {
        var limits = limits(15000, 4500, 1500, 1500);
        var homes = fourHomes(1500);
        // Positive control: not linked, each owner has its own $45, so all four $15 homes are paid in full.
        assertEquals(List.of(1500L, 1500L, 1500L, 1500L), amounts(db.rents(() -> true).reserve(DAY, db.properties(homes), limits)));
        clearRent();
        assertEquals(AccountLinkRepository.Observed.NEW, db.link(JAVA, BEDROCK, DAY));
        var reserved = db.rents(() -> true).reserve(DAY, db.properties(homes), limits);
        assertEquals(List.of(1125L, 1125L, 1125L, 1125L), amounts(reserved));
        assertEquals(4500, reserved.stream().mapToLong(Reserved::cents).sum(), "exactly min(requested, $45)");
        for (var row : reserved) {
            var event = db.rows("SELECT HEX(counterparty_uuid),HEX(benefit_unit_id) FROM npc_economy_events WHERE event_id=?", row.eventId()).getFirst();
            assertEquals(hex(row.property().ownerUuid()).toUpperCase(), event.get(0), "rent goes to the home's own ARM owner");
            assertEquals(hex(row.property().ownerUuid()).toUpperCase(), event.get(1), "benefit_unit_id stays the owner");
        }
    }

    @Test void largestRemainderOrderInsideTheGroupFollowsTheBatchOrder() throws Exception {
        var limits = limits(15000, 4000, 1500, 1500);
        var homes = new ArrayList<UUID>();
        homes.add(db.operating(JAVA, 1500, "j-1"));
        homes.add(db.operating(JAVA, 1500, "j-2"));
        homes.add(db.operating(BEDROCK, 1500, "b-1"));
        db.link(JAVA, BEDROCK, DAY);
        var reserved = db.rents(() -> true).reserve(DAY, db.properties(homes), limits);
        // $45 requested against a $40 group limit: 1333.33 each, and the one leftover cent goes to the first home
        // in the batch's own order (property id text), the CapAllocator tie rule.
        var order = homes.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        assertEquals(order, reserved.stream().map(r -> r.property().propertyId()).toList());
        assertEquals(List.of(1334L, 1333L, 1333L), amounts(reserved));
    }

    // ---- RentLinkedSpentIT

    @Test void moneyOneAccountAlreadyGotTodayCountsAgainstTheOther() throws Exception {
        var limits = limits(15000, 4500, 1500, 1500);
        var java = List.of(db.operating(JAVA, 1500, "j-1"), db.operating(JAVA, 1500, "j-2"), db.operating(JAVA, 1500, "j-3"));
        var bedrock = List.of(db.operating(BEDROCK, 1500, "b-1"), db.operating(BEDROCK, 1500, "b-2"));
        var first = db.rents(() -> true).reserve(DAY, db.properties(java), limits);
        assertEquals(4500, first.stream().mapToLong(Reserved::cents).sum());
        assertTrue(db.pay(first.get(0)));
        assertTrue(db.pay(first.get(1)));
        // The third is FAILED with no journal proof that nothing moved, so it still holds its cap share.
        db.execute("UPDATE npc_economy_events SET state='FAILED',paid_amount_cents=0,claimed_at=?,finalized_at=? WHERE event_id=?",
                db.clock.instant(), db.clock.instant(), first.get(2).eventId());
        db.link(JAVA, BEDROCK, DAY);
        assertEquals(List.of(), db.rents(() -> true).reserve(DAY, db.properties(bedrock), limits), "the group's $45 is used up");
        // Positive control: the same batch without the link pays Bedrock's own $30.
        db.execute("UPDATE npc_account_links SET split_effective_day=?,split_at=?,split_by='test',first_seen_day=? ",
                DAY, db.clock.instant(), DAY.minusDays(1));
        assertEquals(List.of(1500L, 1500L), amounts(db.rents(() -> true).reserve(DAY, db.properties(bedrock), limits)));
    }

    @Test void aJavaOnlyBatchAfterTheBedrockSideWasPaidGetsNothing() throws Exception {
        // The reverse of the test above. BEDROCK is the smaller UUID, so it is the group key: a batch of Java homes
        // only must find the group's spend under that key, not under its own owner.
        assertTrue(OwnerUnits.BYTE_ORDER.compare(BEDROCK, JAVA) < 0, "fixture: the Bedrock account is the group key");
        var limits = limits(15000, 4500, 1500, 1500);
        var bedrock = List.of(db.operating(BEDROCK, 1500, "b-1"), db.operating(BEDROCK, 1500, "b-2"), db.operating(BEDROCK, 1500, "b-3"));
        var java = List.of(db.operating(JAVA, 1500, "j-1"), db.operating(JAVA, 1500, "j-2"));
        db.link(JAVA, BEDROCK, DAY);
        var first = db.rents(() -> true).reserve(DAY, db.properties(bedrock), limits);
        assertEquals(List.of(1500L, 1500L, 1500L), amounts(first));
        for (var row : first) assertTrue(db.pay(row));
        assertEquals(List.of(), db.rents(() -> true).reserve(DAY, db.properties(java), limits), "the group's $45 went to Bedrock");
        // Positive control: split as of today (test only), Java gets its own $30.
        db.execute("UPDATE npc_account_links SET split_effective_day=?,split_at=?,split_by='test',first_seen_day=? ",
                DAY, db.clock.instant(), DAY.minusDays(1));
        assertEquals(List.of(1500L, 1500L), amounts(db.rents(() -> true).reserve(DAY, db.properties(java), limits)));
    }

    @Test void turningTheFlagOffMidDayKeepsTheDayMerged() throws Exception {
        var limits = limits(15000, 4500, 1500, 1500);
        var java = List.of(db.operating(JAVA, 1500, "j-1"), db.operating(JAVA, 1500, "j-2"));
        var bedrock = List.of(db.operating(BEDROCK, 1500, "b-1"), db.operating(BEDROCK, 1500, "b-2"));
        db.link(JAVA, BEDROCK, DAY);
        assertEquals(List.of(1500L, 1500L), amounts(db.rents(() -> true).reserve(DAY, db.properties(java), limits)));
        assertEquals(1, db.count("npc_linked_account_days WHERE economy_day=?", DAY));
        // Reload turns the flag off: today stays merged, so Bedrock gets only the $15 left of the shared $45.
        assertEquals(1500, db.rents(() -> false).reserve(DAY, db.properties(bedrock), limits).stream().mapToLong(Reserved::cents).sum());
    }

    // ---- AppreciationLinkedIT

    @Test void appreciationForTheClosedDayIsMergedWhenTheLinkIsSavedTheNextDay() throws Exception {
        var homes = appreciationDay(true, false);
        db.clock.set(NOW.plusSeconds(86_400));
        db.link(JAVA, BEDROCK, DAY.plusDays(1));
        assertEquals(List.of(150L, 150L, 150L, 150L), appreciate(homes, true));
    }

    @Test void appreciationStaysMergedAfterASplitThatStartsTheNextDay() throws Exception {
        var homes = appreciationDay(false, true);
        // Staff split on D: it starts on D + 1, so D, including D's appreciation paid on D + 1, stays merged.
        assertEquals(AccountLinkRepository.SplitOutcome.SAVED,
                db.links().staffSplit(JAVA, BEDROCK, "staff", "test", db.days()).outcome());
        db.clock.set(NOW.plusSeconds(86_400));
        assertEquals(List.of(150L, 150L, 150L, 150L), appreciate(homes, true));
    }

    @Test void turningTheFlagOnDuringTheNextDayMergesTheClosedDaysAppreciation() throws Exception {
        // D's rent ran with the flag off (no pin, separate caps). The flag is on during D + 1: D is pinned then.
        var homes = appreciationDay(false, true);
        assertEquals(0, db.count("npc_linked_account_days"));
        db.clock.set(NOW.plusSeconds(86_400));
        assertEquals(List.of(150L, 150L, 150L, 150L), appreciate(homes, true));
        assertEquals(1, db.count("npc_linked_account_days WHERE economy_day=?", DAY), "the closed day is pinned");
    }

    @Test void appreciationWithTheFlagOffAndNoPinIsPerAccount() throws Exception {
        // Positive control for the three cases above: same fixture, flag off on both days.
        var homes = appreciationDay(false, true);
        db.clock.set(NOW.plusSeconds(86_400));
        assertEquals(List.of(300L, 300L, 300L, 300L), appreciate(homes, false));
        assertEquals(0, db.count("npc_linked_account_days"));
    }

    // ---- ProofInvarianceIT

    @Test void linkedOwnersKeepEveryProofBoundToTheHomesOwnOwner() throws Exception {
        var limits = limits(15000, 4500, 1500, 1500);
        var homes = fourHomes(1500);
        db.link(JAVA, BEDROCK, DAY);
        var reserved = db.rents(() -> true).reserve(DAY, db.properties(homes), limits);
        assertEquals(4, reserved.size());
        // pending() rebinds each queued row to its leased owner; a unit key in benefit_unit_id would throw here.
        var pending = db.rents(() -> true).pending(DAY, db.properties(homes));
        assertEquals(reserved.stream().map(Reserved::eventId).sorted().toList(), pending.stream().map(Reserved::eventId).sorted().toList());
        for (var row : pending) assertTrue(db.pay(row), "journal accepts the owner-bound event");
        assertEquals(4, db.count(RENT + " AND state='PAID' AND counterparty_uuid=benefit_unit_id"));
        assertEquals(2, db.count(RENT + " AND benefit_unit_id=?", JAVA));
        assertEquals(2, db.count(RENT + " AND benefit_unit_id=?", BEDROCK));
    }

    // ---- FlagOffGoldenIT

    @Test void flagOffWithLinkRowsIsByteIdenticalToTheOldRepository() throws Exception {
        var limits = limits(15000, 4000, 1500, 1500);
        UUID s1 = UUID.fromString("6a000000-0000-4000-8000-000000000001"), s2 = new UUID(0, 0x77L);
        UUID s3 = UUID.fromString("6b000000-0000-4000-8000-000000000002"), s4 = UUID.fromString("6d000000-0000-4000-8000-000000000003");
        var homes = new ArrayList<UUID>();
        for (int i = 0; i < 3; i++) homes.add(db.operating(JAVA, 1500, "golden-j-" + i));
        for (int i = 0; i < 3; i++) homes.add(db.operating(BEDROCK, 1500, "golden-b-" + i));
        for (UUID owner : List.of(s1, s2, s3)) for (int i = 0; i < 2; i++) homes.add(db.operating(owner, 1500, "golden-" + owner + "-" + i));
        assertEquals(12, homes.size());
        var properties = db.properties(homes);

        var legacy = golden(db.legacyRents().reserve(DAY, properties, limits));
        // Golden amounts: J and B are each cut to $40 (1334/1333/1333), the three others keep $30, then the $150
        // server limit cuts the $170 total pro rata. A fixed list, checked by hand against the dev.58 rule (owner
        // pro rata, then server pro rata, largest remainder, ties to the lower index), so a change to the old path
        // fails here too. Property ids come from the region names, so the keys are fixed as well.
        var expected = new java.util.TreeMap<String, Long>(java.util.Map.ofEntries(
                java.util.Map.entry("golden-j-0", 1176L), java.util.Map.entry("golden-j-1", 1177L), java.util.Map.entry("golden-j-2", 1176L),
                java.util.Map.entry("golden-b-0", 1176L), java.util.Map.entry("golden-b-1", 1176L), java.util.Map.entry("golden-b-2", 1177L),
                java.util.Map.entry("golden-" + s1 + "-0", 1323L), java.util.Map.entry("golden-" + s1 + "-1", 1324L),
                java.util.Map.entry("golden-" + s2 + "-0", 1324L), java.util.Map.entry("golden-" + s2 + "-1", 1324L),
                java.util.Map.entry("golden-" + s3 + "-0", 1324L), java.util.Map.entry("golden-" + s3 + "-1", 1323L)));
        var actual = new java.util.TreeMap<String, Long>();
        for (var g : legacy) {
            String key = "rent-v1|" + DAY + "|" + UUID.nameUUIDFromBytes(("linked-" + region(g)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(key, g.key());
            actual.put(region(g), g.cents());
        }
        assertEquals(expected, actual);
        assertEquals(15000, legacy.stream().mapToLong(Golden::cents).sum());
        clearRent();
        db.link(JAVA, BEDROCK, DAY);
        db.link(s3, s2, DAY);
        db.execute("INSERT INTO npc_account_links(account_low,account_high,java_uuid,bedrock_uuid,source,first_seen_at,first_seen_day,last_seen_at) "
                + "VALUES (?,?,NULL,NULL,'STAFF',?,?,?)", s1, s4, db.clock.instant(), DAY, db.clock.instant());
        var off = golden(db.rents(() -> false).reserve(DAY, properties, limits));
        assertEquals(legacy, off, "flag off with link rows: same keys, amounts, owners and units");
        assertEquals(0, db.count("npc_linked_account_days"), "flag off never writes a pin");
        // Positive control: the flag on with the same rows does change the result.
        clearRent();
        var on = golden(db.rents(() -> true).reserve(DAY, properties, limits));
        assertNotEquals(legacy, on);
        long javaAndBedrock = on.stream().filter(g -> g.owner().equals(JAVA) || g.owner().equals(BEDROCK)).mapToLong(Golden::cents).sum();
        assertTrue(javaAndBedrock <= 4000, "linked pair shares one $40 limit: " + javaAndBedrock);
    }

    // ---- BoundedLinksIT

    @Test void moreThanTenThousandActiveLinksFailsTheBatchClosed() throws Exception {
        var limits = limits(15000, 4500, 1500, 1500);
        var homes = fourHomes(1500);
        insertLinks(AccountLinkRepository.MAX_ACTIVE_LINKS + 1);
        var failure = assertThrows(SQLException.class, () -> db.rents(() -> true).reserve(DAY, db.properties(homes), limits));
        assertEquals("Account links require bounded maintenance", failure.getMessage());
        assertEquals(0, db.count("npc_rent_reservations"));
        assertEquals(0, db.count(RENT));
        assertEquals(0, db.count("npc_linked_account_days"), "the pin rolled back with the batch");
        // Positive control: exactly 10000 active rows is allowed.
        db.execute("DELETE FROM npc_account_links ORDER BY link_id DESC LIMIT 1");
        assertEquals(4, db.rents(() -> true).reserve(DAY, db.properties(homes), limits).size());
    }

    // ---- ConcurrentLinkIT

    @Test void aLinkSavedDuringABatchWaitsForTheNextBatch() throws Exception {
        var limits = limits(15000, 6300, 1800, 1500);
        var homes = appreciationDay(false, false, limits);
        db.clock.set(NOW.plusSeconds(86_400));
        var java = db.properties(homes.subList(0, 2));
        var bedrock = db.properties(homes.subList(2, 4));
        AtomicBoolean linked = new AtomicBoolean();
        AppreciationScore linkMidBatch = (c, p, at) -> {
            // Another connection saves and commits the link after this batch read its groups.
            if (linked.compareAndSet(false, true)) {
                try { db.link(JAVA, BEDROCK, DAY.plusDays(1)); }
                catch (SQLException failure) { throw new IllegalStateException(failure); }
            }
            return OptionalDouble.of(100.0);
        };
        var rents = db.rents(() -> true);
        var first = rents.reserveAppreciation(DAY, java, limits, linkMidBatch);
        assertTrue(linked.get());
        assertEquals(1, db.count("npc_account_links"));
        assertEquals(List.of(300L, 300L), amounts(first), "this batch kept its own view: Java alone had room");
        // The next batch sees the group: $60 + $3 + $3 is already at the shared $63, so Bedrock gets nothing.
        assertEquals(List.of(), rents.reserveAppreciation(DAY, bedrock, limits, TOP));
    }

    @Test void aRentBatchWaitingForTheMutexSeesMoneyCommittedWhileItWaited() throws Exception {
        // The groups and the ledger snapshot must be read after the mutex, never before it. Here a holder takes the
        // mutex, a Java batch starts and waits, and the holder commits $45 of Bedrock rent the way a batch does.
        var limits = limits(15000, 4500, 1500, 1500);
        var java = db.properties(List.of(db.operating(JAVA, 1500, "j-1")));
        var bedrock = db.properties(List.of(db.operating(BEDROCK, 1500, "b-1"), db.operating(BEDROCK, 1500, "b-2"),
                db.operating(BEDROCK, 1500, "b-3")));
        db.link(JAVA, BEDROCK, DAY);
        var rents = db.rents(() -> true);
        CompletableFuture<List<Reserved>> waiting;
        try (Connection holder = db.database.getConnection()) {
            holder.setAutoCommit(false);
            try {
                LinkedAccountsHarness.execute(holder, "SELECT singleton FROM npc_rent_mutex WHERE singleton=1 FOR UPDATE");
                waiting = CompletableFuture.supplyAsync(() -> {
                    try { return rents.reserve(DAY, java, limits); }
                    catch (SQLException failure) { throw new CompletionException(failure); }
                });
                db.awaitMutexWait(waiting);
                for (var p : bedrock)
                    EconomyEventRepository.insertPending(holder, EconomyEvent.pending("rent-v1|" + DAY + "|" + p.propertyId(), 1500,
                            EventSource.RENT, p.propertyId(), p.ownerUuid(), p.ownershipEpoch(), p.ownerUuid(), DAY.toString()));
                assertFalse(waiting.isDone(), "still waiting when the Bedrock money commits");
                holder.commit();
            } catch (Exception | AssertionError failure) { holder.rollback(); throw failure; }
        }
        assertEquals(List.of(), waiting.get(60, TimeUnit.SECONDS), "the group's $45 was spent while the batch waited");
        assertEquals(0, db.count(RENT + " AND benefit_unit_id=?", JAVA));
        // Positive control: with no Bedrock money the same Java home is paid.
        db.execute("DELETE FROM npc_economy_events WHERE source='npc-property-rent'");
        assertEquals(List.of(1500L), amounts(rents.reserve(DAY, java, limits)));
    }

    @Test void anAppreciationBatchWaitingForTheMutexSeesTheBatchBeforeIt() throws Exception {
        // Same rule for appreciation, with two real batches. D is pinned by its rent run (flag on), linked after rent.
        // Group: $60 rent paid, $66 limit, so $6 is left. Bedrock's batch takes it while Java's batch waits.
        var homes = appreciationDay(true, true);
        assertEquals(1, db.count("npc_linked_account_days WHERE economy_day=?", DAY), "D is pinned before either batch");
        db.clock.set(NOW.plusSeconds(86_400));
        var limits = limits(15000, 6600, 1800, 1500);
        var java = db.properties(homes.subList(0, 2));
        var bedrock = db.properties(homes.subList(2, 4));
        var rents = db.rents(() -> true);
        AtomicReference<CompletableFuture<List<Reserved>>> second = new AtomicReference<>();
        AppreciationScore startJavaWhileHolding = (c, p, at) -> {
            if (second.get() == null) {
                second.set(CompletableFuture.supplyAsync(() -> {
                    try { return rents.reserveAppreciation(DAY, java, limits, TOP); }
                    catch (SQLException failure) { throw new CompletionException(failure); }
                }));
                try { db.awaitMutexWait(second.get()); }
                catch (Exception failure) { throw new SQLException(failure); }
            }
            return OptionalDouble.of(100.0);
        };
        assertEquals(List.of(300L, 300L), amounts(rents.reserveAppreciation(DAY, bedrock, limits, startJavaWhileHolding)));
        assertNotNull(second.get(), "the Java batch started while Bedrock's batch held the mutex");
        assertEquals(List.of(), second.get().get(60, TimeUnit.SECONDS), "Bedrock's $6 used up the group while Java waited");
        assertEquals(0, db.count("npc_economy_events WHERE source='npc-property-appreciation' AND benefit_unit_id=?", JAVA));
        // Positive control: with no Bedrock appreciation the same Java batch takes the group's $6.
        db.execute("DELETE FROM npc_appreciation_reservations");
        db.execute("DELETE FROM npc_economy_events WHERE source='npc-property-appreciation'");
        assertEquals(List.of(300L, 300L), amounts(rents.reserveAppreciation(DAY, java, limits, TOP)));
    }

    // ---- SplitAcrossResetIT (Codex P1-C)

    @Test void aSplitStalledAcrossTheResetCannotReopenTheMergedDay() throws Exception {
        // D = DAY. The pair has been linked for days. Reset is 04:00 Chicago = 09:00Z on DAY.
        var limits = limits(15000, 4500, 1500, 1500);
        var javaHomes = List.of(db.operating(JAVA, 1500, "j-1"), db.operating(JAVA, 1500, "j-2"), db.operating(JAVA, 1500, "j-3"));
        db.link(JAVA, BEDROCK, DAY.minusDays(3));
        var afterReset = db.clock.instant();
        // 1. Just before the reset the split worker starts and reads the clock (still D - 1)...
        db.clock.set(Instant.parse("2026-09-07T08:59:59.000Z"));
        var days = db.days();
        assertEquals(DAY.minusDays(1), days.today(), "fixture: the worker starts before the reset");
        CompletableFuture<AccountLinkRepository.Split> split;
        try (Connection stall = db.database.getConnection()) {
            stall.setAutoCommit(false);
            try {
                // ...then stalls: this session holds the pair's row, so the split waits inside its save.
                LinkedAccountsHarness.execute(stall, "SELECT link_id FROM npc_account_links FOR UPDATE");
                split = CompletableFuture.supplyAsync(() -> {
                    try { return db.links().staffSplit(JAVA, BEDROCK, "staff", "test", days); }
                    catch (SQLException failure) { throw new CompletionException(failure); }
                });
                db.awaitLockWait(split, "%FROM npc_account_links%FOR UPDATE%", "the pair's row");
                // 2. The reset passes. Java's homes reserve the group's $45 under D's merged pin.
                db.clock.set(afterReset);
                assertEquals(DAY, days.today());
                assertEquals(List.of(1500L, 1500L, 1500L), amounts(db.rents(() -> true).reserve(DAY, db.properties(javaHomes), limits)));
                assertEquals(1, db.count("npc_linked_account_days WHERE economy_day=?", DAY), "D is pinned merged");
                assertFalse(split.isDone(), "the split is still stalled");
                stall.commit();
            } catch (Exception | AssertionError failure) { stall.rollback(); throw failure; }
        }
        // 3. The split commits after the reset.
        assertEquals(AccountLinkRepository.SplitOutcome.SAVED, split.get(60, TimeUnit.SECONDS).outcome());
        // 4. The Bedrock account pays its utilities (operating pays them) and its homes ask for rent on D.
        var bedrock = List.of(db.operating(BEDROCK, 1500, "b-1"), db.operating(BEDROCK, 1500, "b-2"), db.operating(BEDROCK, 1500, "b-3"));
        var second = db.rents(() -> true).reserve(DAY, db.properties(bedrock), limits);
        long pairTotal = db.rows("SELECT COALESCE(SUM(requested_amount_cents),0) FROM " + RENT + " AND economy_day=?", DAY).stream()
                .mapToLong(r -> Long.parseLong(r.getFirst())).sum();
        assertEquals(List.of(), amounts(second), "D was merged before the split, so D stays merged");
        assertEquals(4500, pairTotal, "one $45 across the pair on D");
        assertEquals(List.of(List.of(DAY.plusDays(1).toString())), db.rows("SELECT split_effective_day FROM npc_account_links"),
                "the split starts after the merged day");
        try (Connection c = db.database.getConnection()) {
            c.setAutoCommit(false);
            try { assertEquals(BEDROCK, db.links().unitsFor(c, DAY, false).unitOf(JAVA), "D's grouping is frozen"); }
            finally { c.rollback(); }
        }
        // Positive control: without Java's reservations the same Bedrock batch is paid, so the empty result is the cap.
        clearRent();
        assertEquals(List.of(1500L, 1500L, 1500L), amounts(db.rents(() -> true).reserve(DAY, db.properties(bedrock), limits)));
    }

    // ---- fixtures

    private List<UUID> fourHomes(int rent) throws SQLException {
        var homes = new ArrayList<UUID>();
        homes.add(db.operating(JAVA, rent, "j-1"));
        homes.add(db.operating(JAVA, rent, "j-2"));
        homes.add(db.operating(BEDROCK, rent, "b-1"));
        homes.add(db.operating(BEDROCK, rent, "b-2"));
        return homes;
    }

    /** D: four $15 homes, rent paid per account. Appreciation room: property $18, benefit $66. */
    private List<UUID> appreciationDay(boolean flagOnDuringRent, boolean linkAfterRent) throws Exception {
        return appreciationDay(flagOnDuringRent, linkAfterRent, limits(15000, 6600, 1800, 1500));
    }

    private List<UUID> appreciationDay(boolean flagOnDuringRent, boolean linkAfterRent,
                                       com.prosperitysmp.npc.config.EconomyLimits limits) throws Exception {
        var homes = fourHomes(1500);
        var rent = db.rents(() -> flagOnDuringRent).reserve(DAY, db.properties(homes), limits);
        assertEquals(List.of(1500L, 1500L, 1500L, 1500L), amounts(rent), "D's rent is per account");
        for (var row : rent) assertTrue(db.pay(row));
        if (linkAfterRent) db.link(JAVA, BEDROCK, DAY);
        return homes;
    }

    private List<Long> appreciate(List<UUID> homes, boolean flag) throws Exception {
        var limits = limits(15000, 6600, 1800, 1500);
        var rents = db.rents(() -> flag);
        rents.reserveAppreciation(DAY, db.properties(homes), limits, TOP);
        var queued = rents.pendingAppreciation(DAY, db.properties(homes));
        return queued.stream().sorted(Comparator.comparing(r -> r.property().propertyId().toString())).map(Reserved::cents).toList();
    }

    private record Golden(String key, long cents, UUID owner, UUID unit) { }

    private List<Golden> golden(List<Reserved> reserved) throws SQLException {
        var out = new ArrayList<Golden>();
        for (var row : reserved) {
            var event = db.rows("SELECT idempotency_key,requested_amount_cents,HEX(counterparty_uuid),HEX(benefit_unit_id) "
                    + "FROM npc_economy_events WHERE event_id=?", row.eventId()).getFirst();
            assertEquals(row.cents(), Long.parseLong(event.get(1)));
            out.add(new Golden(event.get(0), row.cents(), row.property().ownerUuid(), uuidFromHex(event.get(3))));
            assertEquals(event.get(2), event.get(3));
        }
        out.sort(Comparator.comparing(Golden::key));
        return out;
    }

    private static String region(Golden g) throws SQLException {
        UUID property = UUID.fromString(g.key().substring(g.key().lastIndexOf('|') + 1));
        return db.rows("SELECT region_id FROM npc_properties WHERE property_id=?", property).getFirst().getFirst();
    }

    private static UUID uuidFromHex(String hex) {
        return com.prosperitysmp.npc.db.Uuids.fromBytes(java.util.HexFormat.of().parseHex(hex.toLowerCase()));
    }

    private void clearRent() throws SQLException {
        db.execute("DELETE FROM npc_rent_cohort");
        db.execute("DELETE FROM npc_rent_reservations");
        db.execute("DELETE FROM npc_economy_events WHERE source='npc-property-rent'");
    }

    private void insertLinks(int count) throws SQLException {
        try (Connection c = db.database.getConnection()) {
            for (int from = 0; from < count; from += 1000) {
                int size = Math.min(1000, count - from);
                var sql = new StringBuilder("INSERT INTO npc_account_links(account_low,account_high,java_uuid,bedrock_uuid,source,"
                        + "first_seen_at,first_seen_day,last_seen_at) VALUES ");
                var args = new ArrayList<Object>();
                for (int i = 0; i < size; i++) {
                    UUID low = new UUID(0, 1_000_000L + from + i), high = new UUID(0x4000_0000_0000_0000L, 1_000_000L + from + i);
                    sql.append(i == 0 ? "" : ",").append("(?,?,?,?,'FLOODGATE_LOGIN',?,?,?)");
                    args.addAll(List.of(low, high, high, low, db.clock.instant(), DAY, db.clock.instant()));
                }
                LinkedAccountsHarness.execute(c, sql.toString(), args.toArray());
            }
        }
        assertEquals(count, db.count("npc_account_links"));
    }

    private static List<Long> amounts(List<Reserved> reserved) {
        return reserved.stream().map(Reserved::cents).toList();
    }
}
