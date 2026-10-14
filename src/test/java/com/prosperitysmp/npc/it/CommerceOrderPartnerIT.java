package com.prosperitysmp.npc.it;

import com.prosperitysmp.npc.commerce.CommerceCatalogue;
import com.prosperitysmp.npc.commerce.CommerceOrder;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository;
import com.prosperitysmp.npc.commerce.CommerceRuntime;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.town.ContractCatalogue;
import com.prosperitysmp.npc.town.PartnerRateReader;
import com.prosperitysmp.npc.town.Partnership;
import com.prosperitysmp.npc.town.TownContractRepository.Code;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The partner rate on real SQL: a quote at the partner rate is re-checked against the CURRENT partnership before any
 * payment. After the partnership ends the same quote is refused and no money row is written. While the partnership is
 * active the same quote funds through the order repository, so the refusal is real.
 */
@Timeout(120)
class CommerceOrderPartnerIT extends ContractItBase {
    private static final long UNIT = 1_000;
    private static final CommerceOrderRepository.Budgets BUDGETS = new CommerceOrderRepository.Budgets(100, 6400, 6400, 1_000_000);

    private UUID owner;
    private CommerceCatalogue.Entry entry;

    private PartnerRateReader rates(ContractCatalogue catalogue, boolean on) {
        return new PartnerRateReader(partnerships, () -> catalogue, () -> on, () -> NOW);
    }

    private void partnerWithEllis() throws Exception {
        owner = UUID.randomUUID();
        trusted(ELLIS, owner); kept(ELLIS, owner, 2); home(owner);
        assertEquals(Code.PARTNER_STARTED, partnerStart(owner, ELLIS, NOW.minusSeconds(600), DAY).code());
        assertEquals(ResidentProfile.Occupation.SHOPKEEPER, residents.find(ELLIS).orElseThrow().occupation());
        entry = CommerceCatalogue.entries(ResidentProfile.Occupation.SHOPKEEPER).getFirst();
    }

    private CommerceOrder quote(long cost, LocalDate day) {
        UUID nonce = UUID.randomUUID();
        return new CommerceOrder(CommerceOrder.idFor(nonce, owner, entry.source(), day), nonce, owner, ELLIS, entry.source(),
                entry.id(), entry.material(), entry.quantity(), cost, day, NOW, entry.delaySeconds());
    }

    @Test void thePartnerQuoteIsRefusedOnceThePartnershipEndsAndNothingIsPaid() throws Exception {
        partnerWithEllis();
        var reader = rates(ON, true);
        int pct = reader.feeDiscountPct(owner, ELLIS, DAY);
        assertEquals(40, pct);
        CommerceOrder partnerQuote = quote(entry.cost(UNIT, pct), DAY);
        assertTrue(partnerQuote.costCents() < entry.cost(UNIT), "the partner rate is lower");
        assertTrue(partnerQuote.costCents() >= UNIT * entry.quantity() + 100, "never below materials plus $1");
        assertTrue(CommerceRuntime.quoteStillValid(entry, partnerQuote, reader.feeDiscountPct(owner, ELLIS, DAY), OptionalLong.of(UNIT)));

        partnerships.end(owner, Partnership.EndReason.OWNER_ENDED, NOW.plusSeconds(10), DAY);
        var before = data();
        int now = reader.feeDiscountPct(owner, ELLIS, DAY);
        assertEquals(0, now);
        // Without the re-validation the stale partner quote would pass.
        assertFalse(CommerceRuntime.quoteStillValid(entry, partnerQuote, now, OptionalLong.of(UNIT)),
                "That quote expired. No payment started; ask for a fresh quote.");
        assertEquals(before, data(), "the refusal writes nothing");
        assertEquals(0, count("npc_commerce_orders"));
        assertEquals(0, count("npc_commerce_money_evidence"));
        assertEquals(0, count("npc_transfer_attempts"));
        // A fresh full-fee quote is valid again.
        assertTrue(CommerceRuntime.quoteStillValid(entry, quote(entry.cost(UNIT), DAY), now, OptionalLong.of(UNIT)));
    }

    @Test void theSameQuoteFundsNormallyWhileThePartnershipIsActive() throws Exception {
        partnerWithEllis();
        var reader = rates(ON, true);
        int pct = reader.feeDiscountPct(owner, ELLIS, DAY);
        assertEquals(40, pct, "Ellis's partner rate");
        CommerceOrder partnerQuote = quote(entry.cost(UNIT, pct), DAY);
        assertTrue(partnerQuote.costCents() < entry.cost(UNIT));
        assertTrue(CommerceRuntime.quoteStillValid(entry, partnerQuote, pct, OptionalLong.of(UNIT)));
        UUID reserve = UUID.randomUUID();
        var orders = new CommerceOrderRepository(database, reserve);
        double dollars = partnerQuote.costCents() / 100.0;
        var handle = reserved(orders, partnerQuote, reserve);
        // The debit intent re-checks the partnership in its own transaction; it is still active, so the debit starts.
        assertTrue(orders.beginDebit(partnerQuote, handle, stillPartner(reader, pct)).newlyStarted());
        orders.recordDebit(handle, new Observation(true, true, dollars, 100.0, 100.0 - dollars, false));
        orders.beginCredit(handle);
        var funded = orders.recordCredit(handle, new Observation(true, true, dollars, 20.0, 20.0 + dollars, false), NOW);
        // The order funds and writes money evidence, so the counters can move. Caps and journal work as for any order.
        assertEquals(CommerceOrderRepository.State.FUNDED, funded.state());
        assertTrue(count("npc_commerce_money_evidence") > 0);
        assertEquals(partnerQuote.costCents(), Long.parseLong(scalar("SELECT cost_cents FROM npc_commerce_orders")));
    }

    /** Built exactly as CommerceRuntime.order builds the check it hands to payments.request. */
    private CommerceOrderRepository.DebitCheck stillPartner(PartnerRateReader reader, int pct) {
        return c -> reader.stillGrants(c, owner, ELLIS, DAY, pct);
    }

    private CommerceOrderRepository.Handle reserved(CommerceOrderRepository orders, CommerceOrder quote, UUID reserve) throws Exception {
        orders.reserve(quote, BUDGETS);
        return new CommerceOrderRepository.Handle(quote.orderId(), UUID.randomUUID(), owner, reserve, "a".repeat(64));
    }

    private void refusedAtTheDebit(CommerceOrderRepository orders, CommerceOrder quote, CommerceOrderRepository.Handle handle,
                                   CommerceOrderRepository.DebitCheck check) throws Exception {
        var start = orders.beginDebit(quote, handle, check);
        assertFalse(start.newlyStarted(), "no debit intent at the partner rate");
        assertEquals(CommerceOrderRepository.State.CANCELLED, start.receipt().state());
        assertEquals(CommerceOrderRepository.QUOTE_EXPIRED, start.receipt().outcome());
        assertEquals("CANCELLED", scalar("SELECT state FROM npc_commerce_orders WHERE order_id=?", quote.orderId()));
        assertEquals(0, count("npc_commerce_money_evidence"));
        assertEquals(0, count("npc_transfer_attempts"));
        // A replay finds the cancelled order and still starts nothing.
        assertFalse(orders.beginDebit(quote, handle, check).newlyStarted());
    }

    /**
     * The worker reads a 40% partner rate; staff end the partnership before the main-thread callback runs; the
     * callback checks the quote against the captured 40%, so it passes. The debit intent re-checks the partnership in
     * its own transaction, so the discounted purchase is refused there and nothing can be debited.
     */
    @Test void aStaffEndAfterTheRateReadRefusesTheDiscountedDebit() throws Exception {
        partnerWithEllis();
        var reader = rates(ON, true);
        int pct = reader.feeDiscountPct(owner, ELLIS, DAY);
        assertEquals(40, pct);
        CommerceOrder partnerQuote = quote(entry.cost(UNIT, pct), DAY);
        assertTrue(partnerships.end(owner, Partnership.EndReason.STAFF_ENDED, NOW.plusSeconds(1), DAY).isPresent());
        assertTrue(CommerceRuntime.quoteStillValid(entry, partnerQuote, pct, OptionalLong.of(UNIT)),
                "the callback reuses the captured 40%, so its own check cannot see the end");
        UUID reserve = UUID.randomUUID();
        var orders = new CommerceOrderRepository(database, reserve);
        refusedAtTheDebit(orders, partnerQuote, reserved(orders, partnerQuote, reserve), stillPartner(reader, pct));
    }

    /** A perk lowered in config, or partnerships turned off, after the rate read: the debit is refused the same way. */
    @Test void aLoweredPerkOrPartnershipsOffAfterTheRateReadRefusesTheDebit() throws Exception {
        partnerWithEllis();
        int pct = rates(ON, true).feeDiscountPct(owner, ELLIS, DAY);
        assertEquals(40, pct);
        var lowered = ContractCatalogue.load(Map.of("town.partnerships.enabled", true, "town.partnerships.offers",
                List.of(Map.of("resident", ELLIS.toString(), "material", "IRON_INGOT", "quantity", 16,
                        "fee-discount-percent", 10, "rank-bonus-points", 0, "pitch", "Lower.")))::get, CAST, name -> true);
        UUID reserve = UUID.randomUUID();
        var orders = new CommerceOrderRepository(database, reserve);
        CommerceOrder first = quote(entry.cost(UNIT, pct), DAY);
        refusedAtTheDebit(orders, first, reserved(orders, first, reserve), stillPartner(rates(lowered, true), pct));
        CommerceOrder second = quote(entry.cost(UNIT, pct), DAY);
        refusedAtTheDebit(orders, second, reserved(orders, second, reserve), stillPartner(rates(ON, false), pct));
        // The same check at the lowered rate the player would now be quoted lets the debit start, so the refusals above are real.
        CommerceOrder fresh = quote(entry.cost(UNIT, 10), DAY);
        assertTrue(orders.beginDebit(fresh, reserved(orders, fresh, reserve), stillPartner(rates(lowered, true), 10)).newlyStarted());
    }

    /**
     * The debit re-check is for the resident the order is with. The player is Ellis's partner, active at debit time, and
     * Mara offers the same 40%. An order with Mara quoted at 40% is refused, because the partnership is not with her.
     */
    @Test void aPartnershipWithAnotherResidentRefusesTheDiscountedDebit() throws Exception {
        partnerWithEllis();
        var both = ContractCatalogue.load(Map.of("town.partnerships.enabled", true, "town.partnerships.offers", List.of(
                Map.of("resident", ELLIS.toString(), "material", "IRON_INGOT", "quantity", 16,
                        "fee-discount-percent", 40, "rank-bonus-points", 0, "pitch", "Ellis."),
                Map.of("resident", MARA.toString(), "material", "IRON_INGOT", "quantity", 16,
                        "fee-discount-percent", 40, "rank-bonus-points", 0, "pitch", "Mara.")))::get, CAST, name -> true);
        var reader = rates(both, true);
        assertEquals(40, reader.feeDiscountPct(owner, ELLIS, DAY), "the Ellis partnership is active");
        assertEquals(new com.prosperitysmp.npc.town.PartnerPerks(40, 0), both.configPerks(MARA), "Mara offers the same rate");
        var mara = CommerceCatalogue.entries(residents.find(MARA).orElseThrow().occupation()).getFirst();
        UUID nonce = UUID.randomUUID();
        CommerceOrder maraQuote = new CommerceOrder(CommerceOrder.idFor(nonce, owner, mara.source(), DAY), nonce, owner, MARA,
                mara.source(), mara.id(), mara.material(), mara.quantity(), mara.cost(UNIT, 40), DAY, NOW, mara.delaySeconds());
        UUID reserve = UUID.randomUUID();
        var orders = new CommerceOrderRepository(database, reserve);
        // Built as CommerceRuntime.order builds it: the order's own resident.
        refusedAtTheDebit(orders, maraQuote, reserved(orders, maraQuote, reserve),
                c -> reader.stillGrants(c, maraQuote.playerId(), maraQuote.residentId(), maraQuote.economyDay(), 40));
        // The same reader lets Ellis's order at 40% start, so the refusal above comes from the resident check.
        CommerceOrder ellisQuote = quote(entry.cost(UNIT, 40), DAY);
        assertTrue(orders.beginDebit(ellisQuote, reserved(orders, ellisQuote, reserve), stillPartner(reader, 40)).newlyStarted());
    }

    /** The check locks the partnership row: a staff end that arrives during the debit decision waits for it. */
    @Test void aStaffEndWaitsForTheDebitDecision() throws Exception {
        partnerWithEllis();
        var reader = rates(ON, true);
        int pct = reader.feeDiscountPct(owner, ELLIS, DAY);
        CommerceOrder partnerQuote = quote(entry.cost(UNIT, pct), DAY);
        UUID reserve = UUID.randomUUID();
        var orders = new CommerceOrderRepository(database, reserve);
        var handle = reserved(orders, partnerQuote, reserve);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var ending = new AtomicReference<java.util.concurrent.Future<java.util.Optional<Partnership>>>();
            var waited = new java.util.concurrent.atomic.AtomicBoolean();
            var start = orders.beginDebit(partnerQuote, handle, c -> {
                boolean holds = reader.stillGrants(c, owner, ELLIS, DAY, pct);
                ending.set(pool.submit(() -> partnerships.end(owner, Partnership.EndReason.STAFF_ENDED, NOW.plusSeconds(2), DAY)));
                try { ending.get().get(1, java.util.concurrent.TimeUnit.SECONDS); }
                catch (java.util.concurrent.TimeoutException blocked) { waited.set(true); }
                catch (Exception other) { throw new java.sql.SQLException(other); }
                return holds;
            });
            assertTrue(waited.get(), "the end waited on the row the debit check read");
            assertTrue(start.newlyStarted(), "the partnership was active when the debit was decided");
            assertTrue(ending.get().get(10, java.util.concurrent.TimeUnit.SECONDS).isPresent(), "the end goes through right after");
        } finally { pool.shutdownNow(); }
    }

    @Test void configLoweredMidTermAppliesAtOnceAndRaisedNeverDoes() throws Exception {
        partnerWithEllis();
        var lowered = ContractCatalogue.load(Map.of("town.partnerships.enabled", true, "town.partnerships.offers",
                List.of(Map.of("resident", ELLIS.toString(), "material", "IRON_INGOT", "quantity", 16,
                        "fee-discount-percent", 10, "rank-bonus-points", 0, "pitch", "Lower.")))::get, CAST, name -> true);
        assertEquals(10, rates(lowered, true).feeDiscountPct(owner, ELLIS, DAY));
        var raised = ContractCatalogue.load(Map.of("town.partnerships.enabled", true, "town.partnerships.offers",
                List.of(Map.of("resident", ELLIS.toString(), "material", "IRON_INGOT", "quantity", 16,
                        "fee-discount-percent", 60, "rank-bonus-points", 0, "pitch", "Higher.")))::get, CAST, name -> true);
        assertEquals(40, rates(raised, true).feeDiscountPct(owner, ELLIS, DAY), "a running term keeps its snapshot");
        // Partnerships off: no read, no perk, the full price.
        var off = new AtomicReference<>(ON);
        var reader = new PartnerRateReader(partnerships, off::get, () -> false, () -> NOW);
        assertFalse(reader.enabled());
        assertEquals(0, reader.feeDiscountPct(owner, ELLIS, DAY));
        // Another resident's orders are not discounted.
        assertEquals(0, rates(ON, true).feeDiscountPct(owner, MARA, DAY));
    }
}
