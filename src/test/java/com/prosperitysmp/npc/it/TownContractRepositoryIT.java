package com.prosperitysmp.npc.it;

import com.prosperitysmp.npc.commerce.CustodyBytes;
import com.prosperitysmp.npc.commerce.ItemCollectionEvidence;
import com.prosperitysmp.npc.commerce.ItemCustody;
import com.prosperitysmp.npc.commerce.ItemCustodyRepository;
import com.prosperitysmp.npc.town.TownContract;
import com.prosperitysmp.npc.town.TownContractRepository.Code;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Contracts against real MariaDB: daily slots, lazy expiry, the hand-over state machine and its cross-checks. */
@Timeout(120)
class TownContractRepositoryIT extends ContractItBase {

    private UUID accept(UUID player, String id) throws SQLException {
        var result = contracts.accept(contract(id), player, NOW, DAY, 1);
        assertEquals(Code.ACCEPTED, result.code());
        return result.contract().orElseThrow().contractId();
    }

    @Test void acceptUsesTodaysSlotAndCancellingKeepsItAndWritesNoMemory() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID first = accept(player, "mara-wall-stone");
        assertEquals(1, Integer.parseInt(scalar("SELECT day_slot FROM npc_town_contracts WHERE contract_id=?", first)));
        // A second contract while one is open, then a retry of the same one.
        assertEquals(Code.ACTIVE_CONFLICT, contracts.accept(contract("nico-coal-run"), player, NOW, DAY, 1).code());
        assertEquals(Code.ALREADY_ACTIVE, contracts.accept(contract("mara-wall-stone"), player, NOW, DAY, 1).code());
        assertEquals(Code.CANCELLED, contracts.cancel(first, player, NOW.plusSeconds(10)).code());
        assertEquals(Code.ALREADY_CANCELLED, contracts.cancel(first, player, NOW.plusSeconds(11)).code());
        assertEquals(0, count("npc_resident_memory"));
        assertEquals(Code.DAILY_LIMIT, contracts.accept(contract("nico-coal-run"), player, NOW.plusSeconds(20), DAY, 1).code());
        // With two a day the next slot opens.
        var second = contracts.accept(contract("nico-coal-run"), player, NOW.plusSeconds(20), DAY, 2);
        assertEquals(Code.ACCEPTED, second.code());
        assertEquals(2, second.contract().orElseThrow().daySlot());
        assertEquals(2, contracts.slotsUsed(player, DAY));
        assertEquals(Code.CANCELLED, contracts.cancel(second.contract().orElseThrow().contractId(), player, NOW.plusSeconds(25)).code());
        assertEquals(Code.DAILY_LIMIT, contracts.accept(contract("mara-wall-stone"), player, NOW.plusSeconds(30), DAY, 2).code());
        // The next economy day opens a fresh slot.
        assertEquals(Code.ACCEPTED, contracts.accept(contract("mara-wall-stone"), player, NOW.plusSeconds(86_400), DAY.plusDays(1), 1).code());
    }

    @Test void aContractAboveTheStageIsRefusedAndWritesNothing() throws SQLException {
        UUID player = UUID.randomUUID();
        assertEquals(Code.RELATIONSHIP_TOO_LOW, contracts.accept(contract("ellis-hinge-iron"), player, NOW, DAY, 1).code());
        assertEquals(0, count("npc_town_contracts"));
        trusted(ELLIS, player);
        assertEquals(Code.ACCEPTED, contracts.accept(contract("ellis-hinge-iron"), player, NOW, DAY, 1).code());
    }

    @Test void expiryIsLazyAtTheDeadlineAndCostsNoTrust() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "nico-coal-run");
        var expires = contracts.find(id, player).orElseThrow().expiresAt();
        assertTrue(contracts.current(player, expires.minusMillis(1)).isPresent());
        assertTrue(contracts.current(player, expires).isEmpty());
        var stored = contracts.find(id, player).orElseThrow();
        assertEquals(TownContract.Status.EXPIRED, stored.status());
        assertEquals(expires, stored.closedAt());
        assertEquals(0, count("npc_resident_memory"));
        assertEquals(Code.EXPIRED, contracts.beginHandover(id, player, MARA, UUID.randomUUID(), expires.plusSeconds(1)).code());
    }

    @Test void aDeliveryCompletesWithExactEvidenceAndWritesBothMemoriesOnce() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "nico-coal-run");
        UUID run = UUID.randomUUID();
        // Only the recipient's avatar can take it.
        assertEquals(Code.WRONG_RECIPIENT, contracts.beginHandover(id, player, NICO, run, NOW.plusSeconds(5)).code());
        assertEquals(Code.HANDOVER_STARTED, contracts.beginHandover(id, player, MARA, run, NOW.plusSeconds(5)).code());
        assertEquals(Code.BUSY, contracts.beginHandover(id, player, MARA, UUID.randomUUID(), NOW.plusSeconds(6)).code());
        ItemCollectionEvidence evidence = taken(player, run, 20, 16);
        var done = contracts.finishHandover(id, player, run, 20, evidence, NOW.plusSeconds(8), DAY, null);
        assertEquals(Code.COMPLETED, done.code());
        assertEquals(TownContract.Status.COMPLETED, contracts.find(id, player).orElseThrow().status());
        // Without the memory insert there would be 0 rows here.
        assertEquals(2, count("npc_resident_memory"));
        assertEquals(List.of(List.of("OBLIGATION_KEPT", "town-contract:" + id)), rows(
                "SELECT DISTINCT kind, source_ref FROM npc_resident_memory WHERE player_id=?", player));
        assertEquals("1", scalar("SELECT disk_proof FROM npc_contract_handover_evidence WHERE contract_id=?", id));
        // An exact replay is a no-op; different evidence is refused.
        assertEquals(Code.ALREADY_COMPLETED, contracts.finishHandover(id, player, run, 20, evidence, NOW.plusSeconds(9), DAY, null).code());
        assertEquals(2, count("npc_resident_memory"));
        assertThrows(SQLException.class, () -> contracts.finishHandover(id, player, run, 20, taken(player, run, 21, 16),
                NOW.plusSeconds(9), DAY, null));
        assertEquals(Code.ALREADY_COMPLETED, contracts.beginHandover(id, player, MARA, UUID.randomUUID(), NOW.plusSeconds(10)).code());
        assertEquals(1, contracts.keptFor(NICO, player, DAY.minusDays(29)));
    }

    @Test void aSupplyContractWritesOneMemory() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "mara-wall-stone");
        UUID run = UUID.randomUUID();
        assertEquals(Code.HANDOVER_STARTED, contracts.beginHandover(id, player, MARA, run, NOW.plusSeconds(5)).code());
        assertEquals(Code.COMPLETED, contracts.finishHandover(id, player, run, 64, taken(player, run, 64, 64), NOW.plusSeconds(6), DAY, null).code());
        assertEquals(1, count("npc_resident_memory"));
    }

    @Test void oneUnitShortIsHeldForStaffAndBlocksTheNextContract() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "nico-coal-run");
        UUID run = UUID.randomUUID();
        contracts.beginHandover(id, player, MARA, run, NOW.plusSeconds(5));
        // after == before - (N-1) never completes: one unit short is held.
        var short1 = new ItemCollectionEvidence(player, run, "a".repeat(64), "b".repeat(64), List.of(20), List.of(5), true, true);
        assertEquals(Code.HANDOVER_HELD, contracts.finishHandover(id, player, run, 20, short1, NOW.plusSeconds(6), DAY, null).code());
        assertEquals(TownContract.Status.AMBIGUOUS, contracts.find(id, player).orElseThrow().status());
        assertEquals(0, count("npc_resident_memory"));
        // Not proven saved: also held.
        UUID other = UUID.randomUUID();
        var next = contracts.accept(contract("mara-wall-stone"), other, NOW, DAY, 1).contract().orElseThrow();
        UUID run2 = UUID.randomUUID();
        contracts.beginHandover(next.contractId(), other, MARA, run2, NOW.plusSeconds(5));
        var unproven = new ItemCollectionEvidence(other, run2, "a".repeat(64), "b".repeat(64), List.of(64), List.of(0), true, false);
        assertEquals(Code.HANDOVER_HELD, contracts.finishHandover(next.contractId(), other, run2, 64, unproven, NOW.plusSeconds(6), DAY, null).code());
        // The held row blocks only because AMBIGUOUS counts in open_player_id.
        var blocked = contracts.accept(contract("mara-wall-stone"), player, NOW.plusSeconds(30), DAY.plusDays(1), 1);
        assertEquals(Code.HELD_FOR_REVIEW, blocked.code());
        assertEquals(id, blocked.contract().orElseThrow().contractId());
        assertEquals(2, contracts.heldCount());
        long later = NOW.plusSeconds(86_400).toEpochMilli();
        assertThrows(SQLException.class, () -> execute("INSERT INTO npc_town_contracts (contract_id, player_id, kind, definition_id, "
                + "title, description, giver_id, recipient_id, material, quantity, minimum_stage, duration_minutes, "
                + "accepted_at_millis, accepted_economy_day, day_slot, expires_at_millis, status) "
                + "VALUES (?,?,'SUPPLY','x','X','X.',?,?,'COAL',1,'STRANGER',30,?,?,1,?,'ACTIVE')",
                UUID.randomUUID(), player, MARA, MARA, later, DAY.plusDays(1).toString(), later + 1_800_000L),
                "uq_contract_open_player allows one open row, a held one included");
    }

    @Test void theCompletionAndItsMemoriesCommitTogether() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "nico-coal-run");
        UUID run = UUID.randomUUID();
        contracts.beginHandover(id, player, MARA, run, NOW.plusSeconds(5));
        // Fault after the status update: the memory insert fails, so the whole settle rolls back.
        execute("CREATE TRIGGER contract_memory_failure BEFORE INSERT ON npc_resident_memory FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected memory failure'");
        assertThrows(SQLException.class, () -> contracts.finishHandover(id, player, run, 20, taken(player, run, 20, 16),
                NOW.plusSeconds(6), DAY, null));
        assertEquals(TownContract.Status.HANDOVER_INTENT, contracts.find(id, player).orElseThrow().status());
        assertEquals(0, count("npc_contract_handover_evidence"));
        assertEquals(0, count("npc_resident_memory"));
        execute("DROP TRIGGER contract_memory_failure");
        // The run can still settle once (the runtime would hold it instead; this proves nothing half-committed).
        assertEquals(Code.COMPLETED, contracts.finishHandover(id, player, run, 20, taken(player, run, 20, 16),
                NOW.plusSeconds(7), DAY, null).code());
    }

    @Test void aPartnershipHandOverIsRefusedWhileAContractIsOpen() throws SQLException {
        // Checked before the insert, so the unique open key is never hit.
        UUID player = UUID.randomUUID();
        trusted(ELLIS, player);
        kept(ELLIS, player, 2);
        home(player);
        UUID id = accept(player, "mara-wall-stone");
        var refused = contracts.beginPartnership(true, player, offer(ELLIS), ON.terms(offer(ELLIS)), UUID.randomUUID(), NOW, DAY);
        assertEquals(Code.ACTIVE_CONFLICT, refused.code());
        assertEquals(id, refused.contract().orElseThrow().contractId());
        assertEquals("0", scalar("SELECT COUNT(*) FROM npc_town_contracts WHERE kind='PARTNERSHIP'"));
    }

    @Test void aDeliveryOrItemRepairInFlightBlocksTheHandOver() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "mara-wall-stone");
        execute("INSERT IGNORE INTO npc_item_custody_players(player_id) VALUES (?)", player);
        UUID nonce = UUID.randomUUID();
        execute("INSERT INTO npc_item_custody(request_nonce,player_id,offer_id,source,quantity,payload_bytes,payload_sha256,"
                + "reserved_at_millis,expires_at_millis,state,delivery_run_id) VALUES(?,?,?,'MERCHANT_GOODS',1,'x',SHA2('x',256),0,900000,'DELIVERY_INTENT',?)",
                nonce, player, UUID.randomUUID(), UUID.randomUUID());
        assertEquals(Code.ITEMS_BUSY, contracts.beginHandover(id, player, MARA, UUID.randomUUID(), NOW.plusSeconds(5)).code());
        assertEquals(TownContract.Status.ACTIVE, contracts.find(id, player).orElseThrow().status());
        execute("UPDATE npc_item_custody SET state='DELIVERED',outcome_code='INVENTORY_CONFIRMED' WHERE request_nonce=?", nonce);
        assertEquals(Code.HANDOVER_STARTED, contracts.beginHandover(id, player, MARA, UUID.randomUUID(), NOW.plusSeconds(6)).code());
    }

    /** Writes the repair collection row with foreign keys off: only its player and state matter here. */
    private static void repairCollection(UUID collection, UUID player, String state) throws SQLException {
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION foreign_key_checks=0");
            try {
                execute(c, "DELETE FROM npc_item_repair_collections WHERE collection_id=?", collection);
                execute(c, "INSERT INTO npc_item_repair_collections(collection_id,player_id,property_id,incident_id,ownership_epoch,"
                        + "incident_type,repair_snapshot_sha256,quote_day,cash_cents,value_ratio_bps,required_value_cents,"
                        + "recipe_value_cents,price_version,quote_sha256,state,run_id) "
                        + "VALUES (?,?,?,1,0,'PESTS',?,?,100,10000,100,100,'v1',?,?,?)",
                        collection, player, UUID.randomUUID(), "a".repeat(64), DAY.toString(), "b".repeat(64), state, UUID.randomUUID());
            } finally { execute(c, "SET SESSION foreign_key_checks=1"); }
        }
    }

    @Test void anItemRepairRunningOrHeldBlocksBothHandOvers() throws SQLException {
        // An item-repair collection in INTENT or AMBIGUOUS owns the inventory.
        UUID player = UUID.randomUUID();
        trusted(ELLIS, player); kept(ELLIS, player, 2); home(player);
        execute("INSERT IGNORE INTO npc_item_custody_players(player_id) VALUES (?)", player);
        UUID collection = UUID.randomUUID();
        for (String state : List.of("INTENT", "AMBIGUOUS")) {
            repairCollection(collection, player, state);
            assertEquals(Code.ITEMS_BUSY, contracts.beginPartnership(true, player, offer(ELLIS), ON.terms(offer(ELLIS)),
                    UUID.randomUUID(), NOW, DAY).code(), state);
            assertEquals("0", scalar("SELECT COUNT(*) FROM npc_town_contracts WHERE kind='PARTNERSHIP'"), state);
        }
        UUID id = accept(player, "mara-wall-stone");
        for (String state : List.of("INTENT", "AMBIGUOUS")) {
            repairCollection(collection, player, state);
            assertEquals(Code.ITEMS_BUSY, contracts.beginHandover(id, player, MARA, UUID.randomUUID(), NOW.plusSeconds(5)).code(), state);
            assertEquals(TownContract.Status.ACTIVE, contracts.find(id, player).orElseThrow().status(), state);
        }
        // Once the repair is settled both hand-overs start, so the refusals above are real.
        repairCollection(collection, player, "CANCELLED");
        UUID run = UUID.randomUUID();
        assertEquals(Code.HANDOVER_STARTED, contracts.beginHandover(id, player, MARA, run, NOW.plusSeconds(6)).code());
        contracts.cancelBeforeTake(id, player, run);
        assertEquals(Code.CANCELLED, contracts.cancel(id, player, NOW.plusSeconds(7)).code());
        assertEquals(Code.HANDOVER_STARTED, contracts.beginPartnership(true, player, offer(ELLIS), ON.terms(offer(ELLIS)),
                UUID.randomUUID(), NOW.plusSeconds(8), DAY).code());
    }

    @Test void anOpenHandOverBlocksADelivery() throws Exception {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "mara-wall-stone");
        UUID run = UUID.randomUUID();
        assertEquals(Code.HANDOVER_STARTED, contracts.beginHandover(id, player, MARA, run, NOW.plusSeconds(5)).code());
        var custody = new ItemCustodyRepository(database);
        var reserved = new ItemCustody(UUID.randomUUID(), player, UUID.randomUUID(), ItemCustody.Source.MERCHANT_GOODS, 1,
                new CustodyBytes(new byte[] {1, 2, 3}), NOW, NOW.plusSeconds(600));
        execute("INSERT INTO npc_item_custody(request_nonce,player_id,offer_id,source,quantity,payload_bytes,payload_sha256,"
                + "reserved_at_millis,expires_at_millis,state) VALUES(?,?,?,'MERCHANT_GOODS',1,?,?,?,?,'RESERVED')",
                reserved.requestNonce(), player, reserved.offerId(), reserved.payload().bytes(), reserved.payload().sha256(),
                NOW.toEpochMilli(), NOW.plusSeconds(600).toEpochMilli());
        assertFalse(custody.beginDelivery(reserved, UUID.randomUUID(), NOW.plusSeconds(10)).newlyStarted());
        contracts.finishHandover(id, player, run, 64, taken(player, run, 64, 64), NOW.plusSeconds(11), DAY, null);
        assertTrue(custody.beginDelivery(reserved, UUID.randomUUID(), NOW.plusSeconds(12)).newlyStarted());
    }

    @Test void cancellingBeforeTheTakeRestoresTheContractAndRemovesAPartnershipIntent() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "mara-wall-stone");
        UUID run = UUID.randomUUID();
        contracts.beginHandover(id, player, MARA, run, NOW.plusSeconds(5));
        assertEquals(TownContract.Status.ACTIVE, contracts.cancelBeforeTake(id, player, run).orElseThrow().status());
        assertNull(contracts.find(id, player).orElseThrow().runId());
        assertEquals(Code.CANCELLED, contracts.cancel(id, player, NOW.plusSeconds(10)).code());
        UUID owner = UUID.randomUUID();
        trusted(ELLIS, owner); kept(ELLIS, owner, 2); home(owner);
        UUID prun = UUID.randomUUID();
        var begun = contracts.beginPartnership(true, owner, offer(ELLIS), ON.terms(offer(ELLIS)), prun, NOW, DAY);
        assertEquals(Code.HANDOVER_STARTED, begun.code());
        assertTrue(contracts.cancelBeforeTake(begun.contract().orElseThrow().contractId(), owner, prun).isEmpty());
        assertEquals("0", scalar("SELECT COUNT(*) FROM npc_town_contracts WHERE kind='PARTNERSHIP'"));
        // Nothing was used up: the same day's partnership hand-over can start again.
        assertEquals(Code.HANDOVER_STARTED, contracts.beginPartnership(true, owner, offer(ELLIS), ON.terms(offer(ELLIS)),
                UUID.randomUUID(), NOW.plusSeconds(5), DAY).code());
    }

    @Test void aBeginWhoseAnswerWasLostIsUndoneByItsRun() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "mara-wall-stone");
        UUID run = UUID.randomUUID();
        assertEquals(Code.HANDOVER_STARTED, contracts.beginHandover(id, player, MARA, run, NOW.plusSeconds(5)).code());
        // Another run's lost answer touches nothing.
        assertTrue(contracts.cancelUnconfirmedBegin(player, UUID.randomUUID()).isEmpty());
        assertEquals(TownContract.Status.HANDOVER_INTENT, contracts.find(id, player).orElseThrow().status());
        assertEquals(TownContract.Status.ACTIVE, contracts.cancelUnconfirmedBegin(player, run).orElseThrow().status());
        assertTrue(contracts.recoverStaleIntents(100).isEmpty(), "nothing left for startup to hold");
        // Once evidence exists the take happened: it is never undone.
        UUID run2 = UUID.randomUUID();
        contracts.beginHandover(id, player, MARA, run2, NOW.plusSeconds(6));
        contracts.finishHandover(id, player, run2, 64, taken(player, run2, 64, 63), NOW.plusSeconds(7), DAY, null);
        assertEquals(TownContract.Status.AMBIGUOUS, contracts.find(id, player).orElseThrow().status());
        assertTrue(contracts.cancelUnconfirmedBegin(player, run2).isEmpty());
        assertEquals(TownContract.Status.AMBIGUOUS, contracts.find(id, player).orElseThrow().status());
    }

    @Test void theTableChecksRefuseImpossibleRows() throws SQLException {
        UUID player = UUID.randomUUID();
        UUID id = accept(player, "mara-wall-stone");
        // COMPLETED needs a run ID and a completion day; a supply contract cannot name another recipient.
        assertThrows(SQLException.class, () -> execute("UPDATE npc_town_contracts SET status='COMPLETED', closed_at_millis=1 WHERE contract_id=?", id));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_town_contracts SET recipient_id=? WHERE contract_id=?", ELLIS, id));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_town_contracts SET day_slot=0 WHERE contract_id=?", id));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_town_contracts SET accepted_economy_day='2026-10-14T1' WHERE contract_id=?", id));
    }
}
