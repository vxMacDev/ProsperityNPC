package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.commerce.CommerceRuntime;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The partner rate for commerce: percent off one resident's service fee for one player today. It is the player's
 * current partnership with that resident (lazily lapsed), at min(snapshot, current config). 0 with no partnership,
 * a partnership with someone else, or partnerships off (then nothing is read). Worker threads only.
 */
public final class PartnerRateReader implements CommerceRuntime.PartnerRates {
    private final PartnershipRepository partnerships;
    private final Supplier<ContractCatalogue> catalogue;
    private final BooleanSupplier on;
    private final Supplier<Instant> clock;

    public PartnerRateReader(PartnershipRepository partnerships, Supplier<ContractCatalogue> catalogue, BooleanSupplier on,
                             Supplier<Instant> clock) {
        this.partnerships = Objects.requireNonNull(partnerships);
        this.catalogue = Objects.requireNonNull(catalogue);
        this.on = Objects.requireNonNull(on);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public boolean enabled() { return on.getAsBoolean(); }

    @Override public int feeDiscountPct(UUID player, UUID resident, LocalDate day) throws Exception {
        if (!enabled()) return 0;
        var current = partnerships.current(player, day, clock.get());
        if (current.isEmpty() || !current.get().residentId().equals(resident)) return 0;
        return PartnerPerks.effective(current.get().perks(), catalogue.get().configPerks(resident)).feeDiscountPct();
    }

    /**
     * Inside the payment transaction, just before the debit intent: true only while partnerships are on and this
     * player's partnership with this resident is active on day at a rate of at least pct (min(snapshot, current
     * config)). The row is share-locked, so a staff end waits for this decision instead of slipping past it.
     */
    @Override public boolean stillGrants(Connection c, UUID player, UUID resident, LocalDate day, int pct)
            throws SQLException {
        Objects.requireNonNull(c);
        Objects.requireNonNull(player);
        Objects.requireNonNull(resident);
        Objects.requireNonNull(day);
        if (pct < 1 || pct > PartnerOffer.MAX_FEE_DISCOUNT || !enabled()) return false;
        var current = PartnershipRepository.activeForDebit(c, player);
        if (current.isEmpty() || !current.get().activeOn(day) || current.get().startedDay().isAfter(day)
                || !current.get().residentId().equals(resident)) return false;
        return PartnerPerks.effective(current.get().perks(), catalogue.get().configPerks(resident)).feeDiscountPct() >= pct;
    }
}
