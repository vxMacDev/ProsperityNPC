package com.prosperitysmp.npc.it;

import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.town.ContractCatalogue;
import com.prosperitysmp.npc.town.PartnerPerks;
import com.prosperitysmp.npc.town.Partnership;
import com.prosperitysmp.npc.town.PartnershipRepository;
import com.prosperitysmp.npc.town.TownContractRepository;
import com.prosperitysmp.npc.town.TownContractRepository.Code;
import com.prosperitysmp.npc.town.TownContractRepository.PartnerTerms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Partnerships against real MariaDB: eligibility, one per owner, the seat cap under a race, lapse, renewal and end. */
@Timeout(180)
class PartnershipRepositoryIT extends ContractItBase {

    private UUID eligible(UUID resident) throws SQLException {
        UUID player = UUID.randomUUID();
        trusted(resident, player);
        kept(resident, player, 2);
        home(player);
        return player;
    }

    @Test void aStartNeedsTheHandOverAndGivesTheSnapshotPerkForTheTerm() throws SQLException {
        UUID owner = eligible(ELLIS);
        var started = partnerStart(owner, ELLIS, NOW, DAY);
        assertEquals(Code.PARTNER_STARTED, started.code());
        Partnership p = started.partnership().orElseThrow();
        assertEquals(DAY, p.startedDay());
        assertEquals(DAY.plusDays(6), p.lastActiveDay());
        assertEquals(new PartnerPerks(40, 0), p.perks());
        assertEquals(p, partnerships.current(owner, DAY, NOW).orElseThrow());
        assertEquals(1, count("npc_partnerships"));
        assertEquals("COMPLETED", scalar("SELECT status FROM npc_town_contracts WHERE kind='PARTNERSHIP'"));
        assertEquals("0", scalar("SELECT day_slot FROM npc_town_contracts WHERE kind='PARTNERSHIP'"));
        assertEquals(1, Integer.parseInt(scalar("SELECT COUNT(*) FROM npc_resident_memory WHERE player_id=? AND source_ref LIKE 'town-partner:%'", owner)));
        // Partnership hand-overs use no contract slot, and only one a day.
        assertEquals(0, contracts.slotsUsed(owner, DAY));
        assertEquals(Code.PARTNER_DAILY_LIMIT, contracts.beginPartnership(false, owner, offer(ELLIS), ON.terms(offer(ELLIS)),
                UUID.randomUUID(), NOW.plusSeconds(60), DAY).code());
        // No partnership without the take: begin alone writes only the intent row.
        UUID other = eligible(MARA);
        assertEquals(Code.HANDOVER_STARTED, contracts.beginPartnership(true, other, offer(MARA), ON.terms(offer(MARA)),
                UUID.randomUUID(), NOW, DAY).code());
        assertTrue(partnerships.current(other, DAY, NOW).isEmpty());
    }

    @Test void eachConditionIsCheckedAndNamed() throws SQLException {
        UUID nobody = UUID.randomUUID();
        home(nobody);
        kept(ELLIS, nobody, 2);
        assertEquals(Code.PARTNER_STAGE, begin(nobody, ELLIS).code());
        UUID fewContracts = UUID.randomUUID();
        trusted(ELLIS, fewContracts); home(fewContracts); kept(ELLIS, fewContracts, 1);
        var few = begin(fewContracts, ELLIS);
        assertEquals(Code.PARTNER_CONTRACTS, few.code());
        assertEquals(1, few.detail());
        UUID homeless = UUID.randomUUID();
        trusted(ELLIS, homeless); kept(ELLIS, homeless, 2);
        assertEquals(Code.PARTNER_HOME, begin(homeless, ELLIS).code());
        // A paused (disabled) home does not count.
        execute("UPDATE npc_properties SET enabled=FALSE WHERE property_id=?", home(homeless));
        assertEquals(Code.PARTNER_HOME, begin(homeless, ELLIS).code());
        // Kept contracts older than 30 economy days do not count.
        UUID old = UUID.randomUUID();
        trusted(ELLIS, old); home(old);
        execute("INSERT INTO npc_town_contract_players(player_id) VALUES (?)", old);
        for (int back : new int[] {31, 40}) {
            long at = NOW.minusSeconds(86_400L * back).toEpochMilli();
            execute("INSERT INTO npc_town_contracts (contract_id, player_id, kind, definition_id, title, description, giver_id, "
                    + "recipient_id, material, quantity, minimum_stage, duration_minutes, accepted_at_millis, accepted_economy_day, "
                    + "day_slot, expires_at_millis, status, handover_run_id, closed_at_millis, completed_economy_day) "
                    + "VALUES (?,?,'SUPPLY','seed','Seed','Seed.',?,?,'COAL',1,'STRANGER',30,?,?,1,?,'COMPLETED',?,?,?)",
                    UUID.randomUUID(), old, ELLIS, ELLIS, at, DAY.minusDays(back).toString(), at + 1_800_000L, UUID.randomUUID(),
                    at + 60_000L, DAY.minusDays(back).toString());
        }
        assertEquals(Code.PARTNER_CONTRACTS, begin(old, ELLIS).code());
        assertEquals(0, count("npc_partnerships"));
        assertEquals("0", scalar("SELECT COUNT(*) FROM npc_town_contracts WHERE kind='PARTNERSHIP'"));
    }

    @Test void partnershipHandOversNeverCountAsKeptContracts() throws SQLException {
        UUID owner = eligible(ELLIS);
        assertEquals(Code.PARTNER_STARTED, partnerStart(owner, ELLIS, NOW, DAY).code());
        // The start hand-over is a completed PARTNERSHIP row for Ellis; only the 2 supply contracts count.
        assertEquals(2, contracts.keptFor(ELLIS, owner, TownContractRepository.keptSince(DAY)));
        assertTrue(partnerships.end(owner, Partnership.EndReason.OWNER_ENDED, NOW.plusSeconds(60), DAY).isPresent());
        execute("DELETE FROM npc_town_contracts WHERE player_id=? AND kind='SUPPLY' LIMIT 1", owner);
        assertEquals(1, contracts.keptFor(ELLIS, owner, TownContractRepository.keptSince(DAY)));
        // A returning ex-partner with 1 kept contract plus their own past hand-over is still 1 short.
        var again = contracts.beginPartnership(true, owner, offer(ELLIS), ON.terms(offer(ELLIS)), UUID.randomUUID(),
                NOW.plusSeconds(86_400), DAY.plusDays(1));
        assertEquals(Code.PARTNER_CONTRACTS, again.code());
        assertEquals(1, again.detail());
    }

    @Test void oneActivePartnershipPerOwner() throws SQLException {
        UUID owner = eligible(ELLIS);
        trusted(MARA, owner); kept(MARA, owner, 2);
        assertEquals(Code.PARTNER_STARTED, partnerStart(owner, ELLIS, NOW, DAY).code());
        var second = contracts.beginPartnership(true, owner, offer(MARA), ON.terms(offer(MARA)), UUID.randomUUID(),
                NOW.plusSeconds(86_400), DAY.plusDays(1));
        assertEquals(Code.PARTNER_HAS_ONE, second.code());
        assertEquals(ELLIS, second.partnership().orElseThrow().residentId());
        // The database refuses a second ACTIVE row for one owner too. The second row has its own start contract (one
        // of the owner's kept Mara contracts), so uq_partnership_start_contract cannot be what stops it.
        UUID otherStart = Uuids.fromBytes(HexFormat.of().parseHex(rows(
                "SELECT contract_id FROM npc_town_contracts WHERE player_id=? AND giver_id=? AND kind='SUPPLY'", owner, MARA).getFirst().getFirst()));
        String secondRow = "INSERT INTO npc_partnerships (partnership_id, owner_id, resident_id, started_economy_day, last_active_day, "
                + "fee_discount_pct, rank_bonus_points, status, start_contract_id, revision) VALUES (?,?,?,?,?,10,0,'ACTIVE',?,1)";
        var refused = assertThrows(SQLException.class, () -> execute(secondRow, UUID.randomUUID(), owner, MARA, DAY.toString(),
                DAY.plusDays(6).toString(), otherStart));
        assertTrue(refused.getMessage().contains("uq_partnership_active_owner"), refused.getMessage());
        // The same row for another owner is accepted, so only the owner key refused it.
        execute(secondRow, UUID.randomUUID(), UUID.randomUUID(), MARA, DAY.toString(), DAY.plusDays(6).toString(), otherStart);
        assertEquals(2, count("npc_partnerships"));
    }

    /** Repeated: a deadlock or over-allocation shows up as a thrown exception or a wrong count in any round. */
    @org.junit.jupiter.api.RepeatedTest(8) void sixOwnersRaceForFourSeatsAndExactlyFourStart() throws Exception {
        var owners = new ArrayList<UUID>();
        for (int i = 0; i < 6; i++) owners.add(eligible(NICO));
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(6);
        try {
            var futures = new ArrayList<Future<Code>>();
            for (UUID owner : owners) futures.add(pool.submit((Callable<Code>) () -> {
                gate.await(10, TimeUnit.SECONDS);
                return begin(owner, NICO).code();
            }));
            gate.countDown();
            var codes = new ArrayList<Code>();
            // Without the resident lock the race would over-allocate or deadlock.
            for (var future : futures) codes.add(future.get(60, TimeUnit.SECONDS));
            assertEquals(4, codes.stream().filter(c -> c == Code.HANDOVER_STARTED).count(), codes.toString());
            assertEquals(2, codes.stream().filter(c -> c == Code.PARTNER_SEATS_FULL).count(), codes.toString());
        } finally { pool.shutdownNow(); }
        assertEquals(4, contracts.seatsUsed(NICO, DAY));
    }

    @Test void aLapsedPartnershipFreesItsSeat() throws SQLException {
        UUID early = eligible(ELLIS);
        var oneSeat = new PartnerTerms(offer(ELLIS).perks(), 7, 1, 2, RelationshipMemory.Stage.TRUSTED, 2,
                offer(ELLIS).material(), offer(ELLIS).quantity());
        LocalDate then = DAY.minusDays(7);
        Instant thenAt = NOW.minusSeconds(86_400L * 7);
        UUID run = UUID.randomUUID();
        var begun = contracts.beginPartnership(true, early, offer(ELLIS), oneSeat, run, thenAt, then);
        assertEquals(Code.HANDOVER_STARTED, begun.code());
        assertEquals(Code.PARTNER_STARTED, contracts.finishHandover(begun.contract().orElseThrow().contractId(), early, run, 16,
                taken(early, run, 16, 16), thenAt.plusSeconds(5), then, oneSeat).code());
        UUID late = eligible(ELLIS);
        // The first term ended yesterday: the seat is free today and that row lapses as it is read.
        var today = contracts.beginPartnership(true, late, offer(ELLIS), oneSeat, UUID.randomUUID(), NOW, DAY);
        assertEquals(Code.HANDOVER_STARTED, today.code());
        assertEquals(List.of(List.of("ENDED", "LAPSED")), rows("SELECT status, end_reason FROM npc_partnerships WHERE owner_id=?", early));
        assertTrue(partnerships.current(early, DAY, NOW).isEmpty());
    }

    @Test void renewalOnlyInsideTheWindowAndFromTheOldEnd() throws SQLException {
        UUID owner = eligible(ELLIS);
        assertEquals(Code.PARTNER_STARTED, partnerStart(owner, ELLIS, NOW, DAY).code());
        // Day 0 of a 7-day term with a 2-day window: too early.
        LocalDate d3 = DAY.plusDays(3);
        assertEquals(Code.PARTNER_NOT_RENEWABLE, contracts.beginPartnership(false, owner, offer(ELLIS), ON.terms(offer(ELLIS)),
                UUID.randomUUID(), NOW.plusSeconds(86_400L * 3), d3).code());
        LocalDate d5 = DAY.plusDays(5);
        Instant at5 = NOW.plusSeconds(86_400L * 5);
        UUID run = UUID.randomUUID();
        var begun = contracts.beginPartnership(false, owner, offer(ELLIS), ON.terms(offer(ELLIS)), run, at5, d5);
        assertEquals(Code.HANDOVER_STARTED, begun.code());
        var renewed = contracts.finishHandover(begun.contract().orElseThrow().contractId(), owner, run, 20, taken(owner, run, 20, 16),
                at5.plusSeconds(5), d5, ON.terms(offer(ELLIS)));
        assertEquals(Code.PARTNER_RENEWED, renewed.code());
        // Extending from today would give d5 + 7 = DAY + 12. A renewal extends from the current end: DAY + 6 + 7 = DAY + 13.
        assertEquals(DAY.plusDays(13), renewed.partnership().orElseThrow().lastActiveDay());
        assertEquals(List.of(List.of(DAY.plusDays(6).toString(), DAY.plusDays(13).toString())),
                rows("SELECT previous_last_day, new_last_day FROM npc_partnership_renewals"));
        assertEquals(1, count("npc_partnerships"));
        // No stacking: the next day is outside the new window.
        assertEquals(Code.PARTNER_NOT_RENEWABLE, contracts.beginPartnership(false, owner, offer(ELLIS), ON.terms(offer(ELLIS)),
                UUID.randomUUID(), NOW.plusSeconds(86_400L * 6), DAY.plusDays(6)).code());
    }

    @Test void endingEarlyRefundsNothingAndFreesTheSeat() throws SQLException {
        UUID owner = eligible(ELLIS);
        assertEquals(Code.PARTNER_STARTED, partnerStart(owner, ELLIS, NOW, DAY).code());
        var before = data();
        var ended = partnerships.end(owner, Partnership.EndReason.OWNER_ENDED, NOW.plusSeconds(60), DAY).orElseThrow();
        assertEquals(Partnership.Status.ENDED, ended.status());
        assertEquals(Partnership.EndReason.OWNER_ENDED, ended.endReason());
        var after = data();
        // Only the partnership row changed: no contract, memory, custody or money row moved.
        for (String table : tables) if (!table.equals("npc_partnerships")) assertEquals(before.get(table), after.get(table), table);
        assertEquals(0, contracts.seatsUsed(ELLIS, DAY));
        assertTrue(partnerships.end(owner, Partnership.EndReason.STAFF_ENDED, NOW.plusSeconds(61), DAY).isEmpty());
    }

    @Test void theRankReadUsesTheLowerOfSnapshotAndConfig() throws SQLException {
        UUID owner = eligible(NICO);
        assertEquals(Code.PARTNER_STARTED, partnerStart(owner, NICO, NOW, DAY).code());
        try (var c = database.getConnection()) {
            assertEquals(Map.of(owner, 2), PartnershipRepository.rankBonusByOwner(c, DAY, Map.of(NICO, 4)));
            assertEquals(Map.of(owner, 1), PartnershipRepository.rankBonusByOwner(c, DAY, Map.of(NICO, 1)), "config lowered at once");
            assertEquals(Map.of(), PartnershipRepository.rankBonusByOwner(c, DAY, Map.of(ELLIS, 4)), "no offer, no perk");
            assertEquals(Map.of(), PartnershipRepository.rankBonusByOwner(c, DAY.plusDays(7), Map.of(NICO, 4)), "after the term");
        }
        assertEquals(new PartnerPerks(25, 0), PartnerPerks.effective(new PartnerPerks(40, 0), new PartnerPerks(25, 0)));
    }

    /** The catalogue after staff reload Ellis's offer to quantity of material at pct off, with term-days of termDays. */
    private static ContractCatalogue reloaded(String material, int quantity, int pct, int termDays) {
        return ContractCatalogue.load(Map.of("town.contracts.enabled", true, "town.partnerships.enabled", true,
                "town.partnerships.term-days", termDays, "town.partnerships.offers",
                List.of(Map.of("resident", ELLIS.toString(), "material", material, "quantity", quantity,
                        "fee-discount-percent", pct, "rank-bonus-points", 0, "pitch", "Reloaded.")))::get, CAST, name -> true);
    }

    /** What ContractRuntime.currentTerms gives at settle time: the current offer, looked up by resident alone. */
    private static PartnerTerms settleTerms(ContractCatalogue catalogue) {
        return catalogue.terms(catalogue.partnerOffer(ELLIS).orElseThrow());
    }

    /**
     * Confirm 16 iron for 40% over 7 days; while the begin runs, staff reload the offer to 64 iron for 60% over
     * 30 days; the take collects the 16 iron captured at begin; settlement fetches the current terms by resident. The
     * cheaper hand-in must never settle at the reloaded perks: it is held for staff, and a staff resolve with the
     * reloaded offer records it consumed with no partnership.
     */
    @Test void aReloadDuringTheHandInNeverSettlesTheOldMaterialsAtTheNewPerks() throws SQLException {
        UUID owner = eligible(ELLIS);
        UUID run = UUID.randomUUID();
        assertEquals("IRON_INGOT", offer(ELLIS).material());
        assertEquals(16, offer(ELLIS).quantity());
        assertEquals(new PartnerPerks(40, 0), offer(ELLIS).perks());
        var begun = contracts.beginPartnership(true, owner, offer(ELLIS), ON.terms(offer(ELLIS)), run, NOW, DAY);
        assertEquals(Code.HANDOVER_STARTED, begun.code());
        UUID id = begun.contract().orElseThrow().contractId();
        var current = settleTerms(reloaded("IRON_INGOT", 64, 60, 30));
        assertThrows(SQLException.class, () -> contracts.finishHandover(id, owner, run, 20, taken(owner, run, 20, 16),
                NOW.plusSeconds(5), DAY, current), "a raised cost holds the hand-over for staff");
        assertEquals(0, count("npc_partnerships"), "no partnership for the cheaper hand-in");
        // ContractRuntime then holds it; a staff resolve as consumed with the reloaded offer still starts nothing.
        assertEquals(com.prosperitysmp.npc.town.TownContract.Status.AMBIGUOUS, contracts.markAmbiguous(id, owner, run).orElseThrow().status());
        var resolved = contracts.resolve(id, TownContractRepository.Decision.VERIFIED_CONSUMED, "staff-1", "case-1",
                NOW.plusSeconds(20), DAY, current);
        assertEquals(Code.RESOLVED_CONSUMED, resolved.code());
        assertTrue(resolved.partnership().isEmpty());
        assertEquals(0, count("npc_partnerships"));
        assertEquals("0", scalar("SELECT partnership_started FROM npc_contract_handover_resolutions WHERE contract_id=?", id));
    }

    /** The perks and term are kept with the hand-in row at begin, and only the material and quantity were before. */
    @Test void theBeginKeepsThePerksAndTermWithTheHandInRow() throws SQLException {
        UUID owner = eligible(ELLIS);
        var begun = contracts.beginPartnership(true, owner, offer(ELLIS), ON.terms(offer(ELLIS)), UUID.randomUUID(), NOW, DAY);
        assertEquals(Code.HANDOVER_STARTED, begun.code());
        assertEquals(List.of(List.of("IRON_INGOT", "16", "40", "0", "7")), rows("SELECT material, quantity, partner_fee_discount_pct, "
                + "partner_rank_bonus_points, partner_term_days FROM npc_town_contracts WHERE contract_id=?",
                begun.contract().orElseThrow().contractId()));
    }

    /** Same materials, perks and term raised mid-hand-in: the start and a renewal settle at the confirmed snapshot. */
    @Test void raisedPerksDuringTheHandInSettleAtTheConfirmedSnapshot() throws SQLException {
        UUID owner = eligible(ELLIS);
        UUID run = UUID.randomUUID();
        var begun = contracts.beginPartnership(true, owner, offer(ELLIS), ON.terms(offer(ELLIS)), run, NOW, DAY);
        var raised = settleTerms(reloaded("IRON_INGOT", 16, 60, 30));
        var started = contracts.finishHandover(begun.contract().orElseThrow().contractId(), owner, run, 20, taken(owner, run, 20, 16),
                NOW.plusSeconds(5), DAY, raised);
        assertEquals(Code.PARTNER_STARTED, started.code());
        assertEquals(new PartnerPerks(40, 0), started.partnership().orElseThrow().perks());
        assertEquals(DAY.plusDays(6), started.partnership().orElseThrow().lastActiveDay());
        assertEquals(List.of(List.of("40", DAY.plusDays(6).toString())),
                rows("SELECT fee_discount_pct, last_active_day FROM npc_partnerships WHERE owner_id=?", owner));
        // A renewal confirmed at 7 days and settled after a reload to 30 adds 7 days, from the current end.
        LocalDate d5 = DAY.plusDays(5);
        Instant at5 = NOW.plusSeconds(86_400L * 5);
        UUID renewRun = UUID.randomUUID();
        var renewBegun = contracts.beginPartnership(false, owner, offer(ELLIS), ON.terms(offer(ELLIS)), renewRun, at5, d5);
        assertEquals(Code.HANDOVER_STARTED, renewBegun.code());
        var renewed = contracts.finishHandover(renewBegun.contract().orElseThrow().contractId(), owner, renewRun, 20,
                taken(owner, renewRun, 20, 16), at5.plusSeconds(5), d5, raised);
        assertEquals(Code.PARTNER_RENEWED, renewed.code());
        assertEquals(DAY.plusDays(13), renewed.partnership().orElseThrow().lastActiveDay());
    }

    /** Lowered perks, term and quantity apply at once: the lower of the kept terms and the current config wins. */
    @Test void loweredTermsDuringTheHandInApplyAtOnce() throws SQLException {
        UUID owner = eligible(ELLIS);
        UUID run = UUID.randomUUID();
        var begun = contracts.beginPartnership(true, owner, offer(ELLIS), ON.terms(offer(ELLIS)), run, NOW, DAY);
        var started = contracts.finishHandover(begun.contract().orElseThrow().contractId(), owner, run, 20, taken(owner, run, 20, 16),
                NOW.plusSeconds(5), DAY, settleTerms(reloaded("IRON_INGOT", 8, 10, 3)));
        assertEquals(Code.PARTNER_STARTED, started.code());
        assertEquals(new PartnerPerks(10, 0), started.partnership().orElseThrow().perks());
        assertEquals(DAY.plusDays(2), started.partnership().orElseThrow().lastActiveDay());
    }

    /** A changed material, or a hand-in row with no kept terms, holds for staff and starts nothing. */
    @Test void aChangedMaterialOrMissingSnapshotHoldsTheHandIn() throws SQLException {
        UUID owner = eligible(ELLIS);
        UUID run = UUID.randomUUID();
        var begun = contracts.beginPartnership(true, owner, offer(ELLIS), ON.terms(offer(ELLIS)), run, NOW, DAY);
        UUID id = begun.contract().orElseThrow().contractId();
        assertThrows(SQLException.class, () -> contracts.finishHandover(id, owner, run, 20, taken(owner, run, 20, 16),
                NOW.plusSeconds(5), DAY, settleTerms(reloaded("COPPER_INGOT", 16, 40, 7))));
        execute("UPDATE npc_town_contracts SET partner_fee_discount_pct=NULL, partner_rank_bonus_points=NULL, "
                + "partner_term_days=NULL WHERE contract_id=?", id);
        assertThrows(SQLException.class, () -> contracts.finishHandover(id, owner, run, 20, taken(owner, run, 20, 16),
                NOW.plusSeconds(5), DAY, ON.terms(offer(ELLIS))), "no kept terms: hold, never guess");
        assertEquals(0, count("npc_partnerships"));
        assertEquals("HANDOVER_INTENT", scalar("SELECT status FROM npc_town_contracts WHERE contract_id=?", id));
    }

    /** A held partnership start for an eligible owner, as ContractRuntime leaves it after an unclear take. */
    private UUID heldStart(UUID owner) throws SQLException {
        UUID run = UUID.randomUUID();
        var begun = contracts.beginPartnership(true, owner, offer(ELLIS), ON.terms(offer(ELLIS)), run, NOW, DAY);
        assertEquals(Code.HANDOVER_STARTED, begun.code());
        UUID id = begun.contract().orElseThrow().contractId();
        assertEquals(com.prosperitysmp.npc.town.TownContract.Status.AMBIGUOUS, contracts.markAmbiguous(id, owner, run).orElseThrow().status());
        return id;
    }

    /**
     * A staff resolve as consumed that starts no partnership says why. A row from before V37 has no kept terms; kept
     * perks of 40% fee and no rank, against a current offer of no fee and 2 rank points, leave no perk above 0. Neither
     * ran a seat or home check, so neither may be reported as one.
     */
    @Test void aStaffResolveThatStartsNothingGivesTheTrueReason() throws SQLException {
        var current = ON.terms(offer(ELLIS));
        UUID old = heldStart(eligible(ELLIS));
        execute("UPDATE npc_town_contracts SET partner_fee_discount_pct=NULL, partner_rank_bonus_points=NULL, "
                + "partner_term_days=NULL WHERE contract_id=?", old);
        var noTerms = contracts.resolve(old, TownContractRepository.Decision.VERIFIED_CONSUMED, "staff-1", "case-1",
                NOW.plusSeconds(20), DAY, current);
        assertEquals(Code.RESOLVED_CONSUMED, noTerms.code());
        assertTrue(noTerms.partnership().isEmpty());
        assertEquals(TownContractRepository.NoPartnership.NO_KEPT_TERMS, noTerms.noPartnership());

        var rankOnly = new PartnerTerms(new PartnerPerks(0, 2), current.termDays(), current.maxSeats(), current.renewWindowDays(),
                current.minimumStage(), current.minContracts(), current.material(), current.quantity());
        UUID zero = heldStart(eligible(ELLIS));
        var noPerk = contracts.resolve(zero, TownContractRepository.Decision.VERIFIED_CONSUMED, "staff-1", "case-2",
                NOW.plusSeconds(20), DAY, rankOnly);
        assertTrue(noPerk.partnership().isEmpty());
        assertEquals(TownContractRepository.NoPartnership.NO_PERK_LEFT, noPerk.noPartnership());

        // The other reasons, then the same held start with the current offer, which does start the partnership.
        UUID off = heldStart(eligible(ELLIS));
        assertEquals(TownContractRepository.NoPartnership.NO_OFFER, contracts.resolve(off, TownContractRepository.Decision.VERIFIED_CONSUMED,
                "staff-1", "case-3", NOW.plusSeconds(20), DAY, null).noPartnership());
        UUID changed = heldStart(eligible(ELLIS));
        assertEquals(TownContractRepository.NoPartnership.OFFER_CHANGED, contracts.resolve(changed,
                TownContractRepository.Decision.VERIFIED_CONSUMED, "staff-1", "case-4", NOW.plusSeconds(20), DAY,
                settleTerms(reloaded("COPPER_INGOT", 16, 40, 7))).noPartnership());
        UUID homeless = eligible(ELLIS);
        UUID noHome = heldStart(homeless);
        execute("UPDATE npc_properties SET enabled=FALSE WHERE owner_uuid=?", homeless);
        assertEquals(TownContractRepository.NoPartnership.CHECK_FAILED, contracts.resolve(noHome,
                TownContractRepository.Decision.VERIFIED_CONSUMED, "staff-1", "case-5", NOW.plusSeconds(20), DAY, current).noPartnership());
        UUID good = heldStart(eligible(ELLIS));
        var started = contracts.resolve(good, TownContractRepository.Decision.VERIFIED_CONSUMED, "staff-1", "case-6",
                NOW.plusSeconds(20), DAY, current);
        assertTrue(started.partnership().isPresent(), "the held start with the current offer starts a partnership");
        assertNull(started.noPartnership());
        assertNull(contracts.resolve(good, TownContractRepository.Decision.VERIFIED_CONSUMED, "staff-1", "case-6",
                NOW.plusSeconds(20), DAY, current).noPartnership(), "a repeat decides nothing, so it gives no reason");
    }

    /**
     * The two refusals a staff resolve can meet besides a seat or home check: the owner already has a partnership, and a
     * held renewal with nothing left to renew. Each gives its own reason, never "a seat or home check failed".
     */
    @Test void aStaffResolveNamesAnOwnerWithAPartnershipAndARenewalWithNothingToRenew() throws SQLException {
        var current = ON.terms(offer(ELLIS));
        // A held start, and since then the owner got a partnership with Mara (one of the owner's kept Ellis contracts
        // stands in as its start contract).
        UUID owner = eligible(ELLIS);
        UUID held = heldStart(owner);
        UUID otherStart = Uuids.fromBytes(HexFormat.of().parseHex(rows(
                "SELECT contract_id FROM npc_town_contracts WHERE player_id=? AND kind='SUPPLY'", owner).getFirst().getFirst()));
        execute("INSERT INTO npc_partnerships (partnership_id, owner_id, resident_id, started_economy_day, last_active_day, "
                + "fee_discount_pct, rank_bonus_points, status, start_contract_id, revision) VALUES (?,?,?,?,?,10,0,'ACTIVE',?,1)",
                UUID.randomUUID(), owner, MARA, DAY.toString(), DAY.plusDays(6).toString(), otherStart);
        var hasOne = contracts.resolve(held, TownContractRepository.Decision.VERIFIED_CONSUMED, "staff-1", "case-7",
                NOW.plusSeconds(20), DAY, current);
        assertTrue(hasOne.partnership().isEmpty());
        assertEquals("HAS_PARTNERSHIP", hasOne.noPartnership().name());

        // A held renewal, and the partnership it renews ended before staff resolved it.
        UUID renewer = eligible(ELLIS);
        assertEquals(Code.PARTNER_STARTED, partnerStart(renewer, ELLIS, NOW, DAY).code());
        LocalDate d5 = DAY.plusDays(5);
        Instant at5 = NOW.plusSeconds(86_400L * 5);
        UUID run = UUID.randomUUID();
        var begun = contracts.beginPartnership(false, renewer, offer(ELLIS), current, run, at5, d5);
        assertEquals(Code.HANDOVER_STARTED, begun.code());
        UUID renewal = begun.contract().orElseThrow().contractId();
        assertEquals(com.prosperitysmp.npc.town.TownContract.Status.AMBIGUOUS, contracts.markAmbiguous(renewal, renewer, run).orElseThrow().status());
        assertTrue(partnerships.end(renewer, Partnership.EndReason.OWNER_ENDED, at5.plusSeconds(10), d5).isPresent());
        var nothingToRenew = contracts.resolve(renewal, TownContractRepository.Decision.VERIFIED_CONSUMED, "staff-1", "case-8",
                at5.plusSeconds(20), d5, current);
        assertTrue(nothingToRenew.partnership().isEmpty());
        assertEquals("NOT_RENEWABLE", nothingToRenew.noPartnership().name());
    }

    private com.prosperitysmp.npc.town.TownContractRepository.Result begin(UUID owner, UUID resident) throws SQLException {
        return contracts.beginPartnership(true, owner, offer(resident), ON.terms(offer(resident)), UUID.randomUUID(), NOW, DAY);
    }
}
