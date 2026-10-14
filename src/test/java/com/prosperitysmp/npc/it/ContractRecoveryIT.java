package com.prosperitysmp.npc.it;

import com.prosperitysmp.npc.town.Partnership;
import com.prosperitysmp.npc.town.TownContract;
import com.prosperitysmp.npc.town.TownContractRepository.Code;
import com.prosperitysmp.npc.town.TownContractRepository.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.sql.SQLException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** A hand-over cut off by a stop is held at the next start, blocks new contracts, and only staff settle it. */
@Timeout(120)
class ContractRecoveryIT extends ContractItBase {

    private UUID startedHandover(UUID player, String id, UUID resident) throws SQLException {
        var accepted = contracts.accept(contract(id), player, NOW, DAY, 1);
        assertEquals(Code.ACCEPTED, accepted.code());
        UUID contractId = accepted.contract().orElseThrow().contractId();
        assertEquals(Code.HANDOVER_STARTED, contracts.beginHandover(contractId, player, resident, UUID.randomUUID(), NOW.plusSeconds(5)).code());
        return contractId;
    }

    @Test void recoveryHoldsEveryIntentAndTheHeldRowBlocksTheNextContract() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = startedHandover(player, "nico-coal-run", MARA);
        var held = contracts.recoverStaleIntents(100);
        assertEquals(1, held.size());
        assertEquals(id, held.getFirst().contractId());
        // Mutation guard: recovering to ACTIVE instead would let the player retry and the next accept conflict instead.
        assertEquals(TownContract.Status.AMBIGUOUS, contracts.find(id, player).orElseThrow().status());
        assertTrue(contracts.recoverStaleIntents(100).isEmpty(), "a second sweep finds nothing");
        var next = contracts.accept(contract("mara-wall-stone"), player, NOW.plusSeconds(60), DAY.plusDays(1), 1);
        assertEquals(Code.HELD_FOR_REVIEW, next.code());
        assertEquals(id, next.contract().orElseThrow().contractId());
        // The held run never settles by itself, even with good evidence arriving late.
        assertEquals(Code.HANDOVER_HELD, contracts.beginHandover(id, player, MARA, UUID.randomUUID(), NOW.plusSeconds(70)).code());
        assertEquals(0, count("npc_resident_memory"));
    }

    @Test void consumedWritesTheMemoryAndNotConsumedWritesNothing() throws SQLException {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        UUID first = startedHandover(a, "nico-coal-run", MARA);
        UUID second = startedHandover(b, "mara-wall-stone", MARA);
        contracts.recoverStaleIntents(100);
        var consumed = contracts.resolve(first, Decision.VERIFIED_CONSUMED, "staff-1", "case-77", NOW.plusSeconds(100), DAY, null);
        assertEquals(Code.RESOLVED_CONSUMED, consumed.code());
        assertEquals(TownContract.Status.RESOLVED_CONSUMED, contracts.find(first, a).orElseThrow().status());
        assertEquals(2, Integer.parseInt(scalar("SELECT COUNT(*) FROM npc_resident_memory WHERE player_id=?", a)));
        var notConsumed = contracts.resolve(second, Decision.VERIFIED_NOT_CONSUMED, "staff-1", "case-78", NOW.plusSeconds(100), DAY, null);
        assertEquals(Code.RESOLVED_NOT_CONSUMED, notConsumed.code());
        assertEquals(0, Integer.parseInt(scalar("SELECT COUNT(*) FROM npc_resident_memory WHERE player_id=?", b)));
        assertEquals(2, count("npc_contract_handover_resolutions"));
        assertEquals("case-77", scalar("SELECT case_ref FROM npc_contract_handover_resolutions WHERE contract_id=?", first));
        // The same decision again is a no-op; a different one is refused; a settled row unblocks the player.
        assertEquals(Code.RESOLVED_CONSUMED, contracts.resolve(first, Decision.VERIFIED_CONSUMED, "staff-1", "case-77",
                NOW.plusSeconds(200), DAY, null).code());
        assertEquals(2, Integer.parseInt(scalar("SELECT COUNT(*) FROM npc_resident_memory WHERE player_id=?", a)));
        assertThrows(SQLException.class, () -> contracts.resolve(first, Decision.VERIFIED_NOT_CONSUMED, "staff-1", "case-77",
                NOW.plusSeconds(200), DAY, null));
        assertEquals(Code.ACCEPTED, contracts.accept(contract("mara-wall-stone"), a, NOW.plusSeconds(86_400), DAY.plusDays(1), 1).code());
        // Staff cannot settle a row that is not held, and an unknown ID is reported.
        UUID live = contracts.current(a, NOW.plusSeconds(86_401)).orElseThrow().contractId();
        assertEquals(Code.NOT_HELD, contracts.resolve(live, Decision.VERIFIED_CONSUMED, "staff-1", "case-79", NOW.plusSeconds(86_402),
                DAY.plusDays(1), null).code());
        assertEquals(Code.NOT_FOUND, contracts.resolve(UUID.randomUUID(), Decision.VERIFIED_CONSUMED, "staff-1", "case-80",
                NOW, DAY, null).code());
        assertThrows(IllegalArgumentException.class, () -> contracts.resolve(first, Decision.VERIFIED_CONSUMED, "staff 1", "case",
                NOW, DAY, null));
    }

    @Test void aHeldPartnershipStartsOnConsumedOnlyWhileTheSeatAndHomeChecksPass() throws SQLException {
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
        for (UUID player : new UUID[] {owner, other}) { trusted(ELLIS, player); kept(ELLIS, player, 2); home(player); }
        var begun = contracts.beginPartnership(true, owner, offer(ELLIS), ON.terms(offer(ELLIS)), UUID.randomUUID(), NOW, DAY);
        assertEquals(Code.HANDOVER_STARTED, begun.code());
        UUID held = begun.contract().orElseThrow().contractId();
        assertEquals(1, contracts.recoverStaleIntents(100).size());
        // The held start keeps its seat: with one seat, nobody else can start.
        var oneSeat = new com.prosperitysmp.npc.town.TownContractRepository.PartnerTerms(offer(ELLIS).perks(), 7, 1, 2,
                com.prosperitysmp.npc.resident.RelationshipMemory.Stage.TRUSTED, 2, offer(ELLIS).material(), offer(ELLIS).quantity());
        assertEquals(Code.PARTNER_SEATS_FULL, contracts.beginPartnership(true, other, offer(ELLIS), oneSeat, UUID.randomUUID(),
                NOW.plusSeconds(10), DAY).code());
        var resolved = contracts.resolve(held, Decision.VERIFIED_CONSUMED, "staff-1", "case-1", NOW.plusSeconds(20), DAY, oneSeat);
        assertEquals(Code.RESOLVED_CONSUMED, resolved.code());
        Partnership started = resolved.partnership().orElseThrow();
        assertEquals(DAY.plusDays(6), started.lastActiveDay());
        assertEquals("1", scalar("SELECT partnership_started FROM npc_contract_handover_resolutions WHERE contract_id=?", held));
        assertEquals(1, Integer.parseInt(scalar("SELECT COUNT(*) FROM npc_resident_memory WHERE player_id=? AND source_ref LIKE 'town-partner:%'", owner)));

        // Without a home any more: consumed is recorded, with no partnership.
        UUID third = UUID.randomUUID();
        trusted(ELLIS, third); kept(ELLIS, third, 2); UUID home = home(third);
        var begun2 = contracts.beginPartnership(true, third, offer(ELLIS), ON.terms(offer(ELLIS)), UUID.randomUUID(), NOW, DAY);
        assertEquals(Code.HANDOVER_STARTED, begun2.code());
        contracts.recoverStaleIntents(100);
        execute("UPDATE npc_properties SET enabled=FALSE WHERE property_id=?", home);
        UUID heldId = begun2.contract().orElseThrow().contractId();
        var noHome = contracts.resolve(heldId, Decision.VERIFIED_CONSUMED, "staff-1", "case-2", NOW.plusSeconds(30), DAY, ON.terms(offer(ELLIS)));
        assertEquals(Code.RESOLVED_CONSUMED, noHome.code());
        assertTrue(noHome.partnership().isEmpty());
        assertEquals("0", scalar("SELECT partnership_started FROM npc_contract_handover_resolutions WHERE contract_id=?", heldId));
    }
}
