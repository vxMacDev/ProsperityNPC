package com.prosperitysmp.npc.it;

import com.prosperitysmp.npc.commerce.ItemCollectionEvidence;
import com.prosperitysmp.npc.town.TownContractRepository.Code;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Shared seeding for the resident-area contract and partnership read tests. */
abstract class ResidentsContractItSupport extends ContractItBase {

    /** A player the partnership rules accept: Trusted with the resident, two kept contracts and an enrolled home. */
    UUID eligible(UUID resident) throws SQLException {
        UUID player = UUID.randomUUID();
        trusted(resident, player);
        kept(resident, player, 2);
        home(player);
        return player;
    }

    UUID accept(UUID player, String definition, Instant at) throws SQLException {
        var result = contracts.accept(contract(definition), player, at, DAY, 3);
        assertEquals(Code.ACCEPTED, result.code());
        return result.contract().orElseThrow().contractId();
    }

    /** A supply contract taken at the given time and held for staff after a take one unit short of nothing. */
    UUID hold(UUID player, String definition, Instant at) throws SQLException {
        UUID contractId = accept(player, definition, at);
        UUID run = UUID.randomUUID();
        assertEquals(Code.HANDOVER_STARTED, contracts.beginHandover(contractId, player,
                contract(definition).recipientId(), run, at.plusSeconds(1)).code());
        var shortTake = new ItemCollectionEvidence(player, run, "a".repeat(64), "b".repeat(64), List.of(64), List.of(30), true, true);
        assertEquals(Code.HANDOVER_HELD, contracts.finishHandover(contractId, player, run, 64, shortTake,
                at.plusSeconds(2), DAY, null).code());
        return contractId;
    }

    /**
     * One stored contract row. COMPLETED and RESOLVED statuses carry a run and the completion day; CANCELLED and
     * EXPIRED carry neither; the closing time is a minute after acceptance, or the deadline for EXPIRED.
     */
    UUID seedContract(UUID player, UUID giver, UUID recipient, String kind, String status, LocalDate acceptedDay,
                      int slot, LocalDate completedDay) throws SQLException {
        execute("INSERT IGNORE INTO npc_town_contract_players(player_id) VALUES (?)", player);
        UUID id = UUID.randomUUID();
        long accepted = acceptedDay.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli();
        long expires = accepted + 1_800_000L;
        boolean withRun = status.equals("COMPLETED") || status.startsWith("RESOLVED");
        Long closed = status.equals("ACTIVE") ? null : status.equals("EXPIRED") ? Long.valueOf(expires) : Long.valueOf(accepted + 60_000L);
        execute("""
                INSERT INTO npc_town_contracts (contract_id, player_id, kind, definition_id, title, description, giver_id,
                recipient_id, material, quantity, minimum_stage, duration_minutes, accepted_at_millis, accepted_economy_day,
                day_slot, expires_at_millis, status, handover_run_id, closed_at_millis, completed_economy_day)
                VALUES (?,?,?,'seed','Seed','Seed.',?,?,'COAL',1,'STRANGER',30,?,?,?,?,?,?,?,?)
                """, id, player, kind, giver, recipient, accepted, acceptedDay.toString(), slot, expires, status,
                withRun ? UUID.randomUUID() : null, closed, completedDay == null ? null : completedDay.toString());
        return id;
    }

    /** One stored partnership row that starts from its own seeded start contract. */
    UUID seedPartnership(UUID owner, UUID resident, LocalDate started, LocalDate last, int pct, int rank, String endReason)
            throws SQLException {
        UUID start = seedContract(owner, resident, resident, "SUPPLY", "COMPLETED", started.minusDays(1), 1, started.minusDays(1));
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO npc_partnerships (partnership_id, owner_id, resident_id, started_economy_day, last_active_day,
                fee_discount_pct, rank_bonus_points, status, end_reason, ended_at_millis, start_contract_id, revision)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,1)
                """, id, owner, resident, started.toString(), last.toString(), pct, rank,
                endReason == null ? "ACTIVE" : "ENDED", endReason, endReason == null ? null : NOW.toEpochMilli(), start);
        return id;
    }

    void upgrade(UUID property, String type, int level, Long disabledIncident) throws SQLException {
        execute("INSERT INTO npc_upgrades(property_id, upgrade_type, level, disabled_incident_id) VALUES (?,?,?,?)",
                property, type, level, disabledIncident);
    }
}
