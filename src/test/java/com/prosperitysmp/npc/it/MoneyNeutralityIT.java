package com.prosperitysmp.npc.it;

import com.prosperitysmp.npc.commerce.CommerceCatalogue;
import com.prosperitysmp.npc.commerce.CommerceOrder;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.town.TownContractRepository.Code;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Contracts and partnerships move no money: accept, hand over, start and renew leave every money table as it was.
 * One commerce order in the same run does write money rows, so the counters can move.
 */
@Timeout(120)
class MoneyNeutralityIT extends ContractItBase {
    /** Every table that records money, a transfer, a cap, a payout or a training grant. */
    private static final List<String> MONEY = List.of("npc_transfer_attempts", "npc_transfer_observations", "npc_economy_events",
            "npc_purchase_intents", "npc_commerce_orders", "npc_commerce_money_evidence", "npc_commerce_days",
            "npc_rent_reservations", "npc_property_funding", "npc_item_custody", "npc_transfer_resolutions",
            "npc_commerce_payment_resolutions", "npc_appreciation_reservations", "npc_utility_payments",
            // No training perk: a partnership never grants training.
            "npc_training_players", "npc_training_grants");

    private static Map<String, Integer> money() throws SQLException {
        var out = new LinkedHashMap<String, Integer>();
        for (String table : MONEY) out.put(table, count(table));
        return out;
    }

    @Test void acceptHandOverStartAndRenewWriteNoMoneyRows() throws Exception {
        UUID player = UUID.randomUUID();
        trusted(ELLIS, player); kept(ELLIS, player, 2); home(player);
        var before = money();
        var fundingBefore = rows("SELECT * FROM npc_property_funding");
        // A contract, handed over.
        var accepted = contracts.accept(contract("mara-wall-stone"), player, NOW.minusSeconds(86_400L * 2), DAY.minusDays(2), 1);
        assertEquals(Code.ACCEPTED, accepted.code());
        UUID run = UUID.randomUUID();
        UUID id = accepted.contract().orElseThrow().contractId();
        assertEquals(Code.HANDOVER_STARTED, contracts.beginHandover(id, player, MARA, run, NOW.minusSeconds(86_400L * 2 - 5)).code());
        assertEquals(Code.COMPLETED, contracts.finishHandover(id, player, run, 64, taken(player, run, 64, 64),
                NOW.minusSeconds(86_400L * 2 - 6), DAY.minusDays(2), null).code());
        // A partnership started, then renewed inside its window.
        assertEquals(Code.PARTNER_STARTED, partnerStart(player, ELLIS, NOW.minusSeconds(86_400), DAY.minusDays(1)).code());
        var renewAt = NOW.plusSeconds(86_400L * 4);
        UUID renewRun = UUID.randomUUID();
        var begun = contracts.beginPartnership(false, player, offer(ELLIS), ON.terms(offer(ELLIS)), renewRun, renewAt, DAY.plusDays(4));
        assertEquals(Code.HANDOVER_STARTED, begun.code());
        assertEquals(Code.PARTNER_RENEWED, contracts.finishHandover(begun.contract().orElseThrow().contractId(), player, renewRun, 16,
                taken(player, renewRun, 16, 16), renewAt.plusSeconds(5), DAY.plusDays(4), ON.terms(offer(ELLIS))).code());
        assertEquals(before, money(), "no money, transfer, cap or custody row was written");
        assertEquals(fundingBefore, rows("SELECT * FROM npc_property_funding"), "home funding untouched");
        assertTrue(count("npc_resident_memory") > 3, "the reward is memory only");

        // A commerce order in the same database does move the counters, so the unchanged counts above are real.
        var entry = CommerceCatalogue.entries(ResidentProfile.Occupation.SHOPKEEPER).getFirst();
        UUID nonce = UUID.randomUUID(), reserve = UUID.randomUUID();
        var order = new CommerceOrder(CommerceOrder.idFor(nonce, player, entry.source(), DAY), nonce, player, ELLIS, entry.source(),
                entry.id(), entry.material(), entry.quantity(), entry.cost(1_000), DAY, NOW, entry.delaySeconds());
        var orders = new CommerceOrderRepository(database, reserve);
        orders.reserve(order, new CommerceOrderRepository.Budgets(100, 6400, 6400, 1_000_000));
        var handle = new CommerceOrderRepository.Handle(order.orderId(), UUID.randomUUID(), player, reserve, "a".repeat(64));
        orders.beginDebit(order, handle);
        double dollars = order.costCents() / 100.0;
        orders.recordDebit(handle, new Observation(true, true, dollars, 100.0, 100.0 - dollars, false));
        assertNotEquals(before, money(), "the counter can move");
        assertTrue(count("npc_commerce_money_evidence") > 0);
    }
}
