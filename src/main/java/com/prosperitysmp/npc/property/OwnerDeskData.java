package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.economy.LinkedAccounts;
import com.prosperitysmp.npc.economy.PurchasePrices;
import com.prosperitysmp.npc.economy.UtilityBill;
import com.prosperitysmp.npc.economy.UtilityPrices;
import com.prosperitysmp.npc.formulas.PropertyFormulas;
import com.prosperitysmp.npc.market.OwnerHousingView;
import com.prosperitysmp.npc.util.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable private snapshot. Revision is the property's row_version, not a global change token. */
public record OwnerDeskData(Property property, long revision, Instant capturedAt,
                            List<Resident> residents, List<Issue> issues, List<Upgrade> upgrades,
                            Statement statement, Insurance insurance, Optional<OwnerHousingView> housing,
                            Optional<PendingRent> pendingRent, Optional<Linked> linked, Optional<Partner> partner,
                            Optional<Bills> bills, Optional<UtilityBill> utilityBill) {
    public OwnerDeskData(Property property, long revision, Instant capturedAt,
                         List<Resident> residents, List<Issue> issues, List<Upgrade> upgrades,
                         Statement statement, Insurance insurance, Optional<OwnerHousingView> housing,
                         Optional<PendingRent> pendingRent, Optional<Linked> linked, Optional<Partner> partner,
                         Optional<Bills> bills) {
        this(property, revision, capturedAt, residents, issues, upgrades, statement, insurance, housing, pendingRent,
                linked, partner, bills, Optional.empty());
    }
    public OwnerDeskData(Property property, long revision, Instant capturedAt,
                         List<Resident> residents, List<Issue> issues, List<Upgrade> upgrades,
                         Statement statement, Insurance insurance, Optional<OwnerHousingView> housing,
                         Optional<PendingRent> pendingRent, Optional<Linked> linked, Optional<Partner> partner) {
        this(property, revision, capturedAt, residents, issues, upgrades, statement, insurance, housing, pendingRent,
                linked, partner, Optional.empty());
    }
    public OwnerDeskData(Property property, long revision, Instant capturedAt,
                         List<Resident> residents, List<Issue> issues, List<Upgrade> upgrades,
                         Statement statement, Insurance insurance, Optional<OwnerHousingView> housing,
                         Optional<PendingRent> pendingRent, Optional<Linked> linked) {
        this(property, revision, capturedAt, residents, issues, upgrades, statement, insurance, housing, pendingRent, linked,
                Optional.empty());
    }
    public OwnerDeskData(Property property, long revision, Instant capturedAt,
                         List<Resident> residents, List<Issue> issues, List<Upgrade> upgrades,
                         Statement statement, Insurance insurance, Optional<OwnerHousingView> housing,
                         Optional<PendingRent> pendingRent) {
        this(property, revision, capturedAt, residents, issues, upgrades, statement, insurance, housing, pendingRent, Optional.empty());
    }
    public OwnerDeskData(Property property, long revision, Instant capturedAt,
                         List<Resident> residents, List<Issue> issues, List<Upgrade> upgrades,
                         Statement statement, Insurance insurance, Optional<OwnerHousingView> housing) {
        this(property, revision, capturedAt, residents, issues, upgrades, statement, insurance, housing, Optional.empty());
    }
    public OwnerDeskData(Property property, long revision, Instant capturedAt,
                         List<Resident> residents, List<Issue> issues, List<Upgrade> upgrades,
                         Statement statement, Insurance insurance) {
        this(property, revision, capturedAt, residents, issues, upgrades, statement, insurance, Optional.empty());
    }
    public OwnerDeskData(Property property, long revision, Instant capturedAt,
                         List<Resident> residents, List<Issue> issues, List<Upgrade> upgrades, Statement statement) {
        this(property, revision, capturedAt, residents, issues, upgrades, statement,
                new Insurance(PropertyFormulas.insurancePriceCents(property.capacity()),
                        null, null, false, true));
    }
    public OwnerDeskData {
        Objects.requireNonNull(property);
        Objects.requireNonNull(capturedAt);
        Objects.requireNonNull(statement);
        Objects.requireNonNull(insurance);
        Objects.requireNonNull(housing);
        Objects.requireNonNull(pendingRent);
        Objects.requireNonNull(linked);
        Objects.requireNonNull(partner);
        Objects.requireNonNull(bills);
        Objects.requireNonNull(utilityBill);
        utilityBill.ifPresent(bill -> {
            if (!bill.propertyId().equals(property.propertyId()) || !bill.day().equals(statement.throughDay())
                    || bill.issuedAt().isAfter(capturedAt))
                throw new IllegalArgumentException("Utility bill snapshot scope differs");
        });

        if (revision < 0) throw new IllegalArgumentException("negative revision");
        housing.ifPresent(view -> {
            if (!view.propertyId().equals(property.propertyId()) || !view.ownerId().equals(property.ownerUuid())
                    || !view.capturedAt().equals(capturedAt))
                throw new IllegalArgumentException("Housing view differs from owner desk scope");
        });
        residents = List.copyOf(residents);
        issues = List.copyOf(issues);
        upgrades = List.copyOf(upgrades);
    }

    /** Invoice counts for the same seven days as the statement. */
    public record Bills(int paid, int unpaid, int expired) {
        public Bills {
            if (paid < 0 || unpaid < 0 || expired < 0 || paid > 7 || unpaid > 7 || expired > 7
                    || paid + unpaid + expired < 1 || paid + unpaid + expired > 7)
                throw new IllegalArgumentException("Invalid bill summary");
        }
        public String line() { return "Bills: " + paid + " paid | " + unpaid + " unpaid | " + expired + " expired"; }
    }

    /** A delayed asking-rent change recorded in SQL (housing.rent-change-delay-days). notices = residents told. */
    public record PendingRent(long cents, LocalDate effectiveDay, int notices) {
        public PendingRent {
            Objects.requireNonNull(effectiveDay);
            if (cents <= 0 || notices < 0 || notices > 4) throw new IllegalArgumentException("invalid pending rent");
        }
        /** Plain desk line, e.g. "Rent changes to $12.00 on 2026-10-15. 2 residents were told." */
        public String line() {
            return "Rent changes to " + Money.format(cents) + " on " + effectiveDay + ". "
                    + (notices == 1 ? "1 resident was told." : notices + " residents were told.");
        }
    }

    /**
     * linked-accounts: this home's owner shares the benefit-unit limit with other accounts (accounts is the group
     * size, 2 or more). Display only; shown while linked-accounts.enabled is on.
     */
    public record Linked(int accounts, long dailyLimitCents) {
        public Linked {
            if (accounts < 2 || dailyLimitCents < 0) throw new IllegalArgumentException("invalid linked accounts line");
        }
        /** Plain desk line, e.g. "Shares a $45 daily rent limit with your linked account." */
        public String line() { return LinkedAccounts.deskLine(dailyLimitCents); }
    }

    /**
     * town.partnerships: the viewing owner's active partnership (owner level, not this home's record). Display only;
     * shown while partnerships are on. feeDiscountPct and rank are the effective perks today; advertisingTop means
     * every enrolled home already has the top advertising level, so the rank perk adds nothing.
     */
    public record Partner(String residentName, LocalDate lastActiveDay, int feeDiscountPct, boolean rank, boolean advertisingTop) {
        public Partner {
            requireText(residentName);
            Objects.requireNonNull(lastActiveDay);
            if (feeDiscountPct < 0 || feeDiscountPct > 60) throw new IllegalArgumentException("invalid partner line");
        }
        /** "Partner: Ellis Ward until 2026-10-20. Their fee is 40% lower for you." */
        public String line() {
            var out = new StringBuilder("Partner: ").append(residentName).append(" until ").append(lastActiveDay).append('.');
            if (feeDiscountPct > 0) out.append(" Their fee is ").append(feeDiscountPct).append("% lower for you.");
            if (rank) out.append(advertisingTop ? " Your advertising is already at the top." : " New tenants hear about your homes.");
            return out.toString();
        }
    }

    public boolean canPurchaseInsurance() { return insurance.canPurchase(statement.throughDay()); }

    public long utilityMaximum(UtilityPrices prices) {
        Objects.requireNonNull(prices);
        return utilityBill.map(UtilityBill::cents).orElseGet(() -> prices.cents(residents.size(),
                upgrades.stream().mapToInt(Upgrade::level).sum()));
    }

    public boolean canPayUtility() {
        return utilityBill.map(bill -> bill.ownerId().equals(property.ownerUuid())
                && bill.ownershipEpoch() == property.ownershipEpoch()).orElse(true);
    }

    /** Active coverage is shown even when the next adjacent term is already reserved. */
    public record Insurance(long premiumCents, LocalDate startDay, LocalDate endDayExclusive,
                            boolean used, boolean currentIncarnation, boolean nextTermReserved) {
        public Insurance(long premiumCents, LocalDate startDay, LocalDate endDayExclusive,
                         boolean used, boolean currentIncarnation) {
            this(premiumCents, startDay, endDayExclusive, used, currentIncarnation, false);
        }
        public Insurance {
            if (premiumCents <= 0 || premiumCents > PurchasePrices.MAX_CENTS
                    || (startDay == null) != (endDayExclusive == null)
                    || (startDay == null && (used || nextTermReserved))
                    || (nextTermReserved && !currentIncarnation)
                    || (startDay != null && !startDay.plusDays(7).equals(endDayExclusive)))
                throw new IllegalArgumentException("Invalid insurance display");
        }
        public boolean canPurchase() { return !nextTermReserved && (startDay == null || !currentIncarnation); }
        public boolean canPurchase(LocalDate applicationDay) {
            Objects.requireNonNull(applicationDay);
            return !nextTermReserved && (startDay == null || !currentIncarnation
                    || !endDayExclusive.isAfter(applicationDay.plusDays(1)));
        }
    }

    public record Resident(UUID id, String name, int slot, String tier, String mood, int satisfaction) {
        public Resident {
            Objects.requireNonNull(id);
            requireText(name);
            requireText(tier);
            requireText(mood);
            if (slot < 0 || slot > 3 || satisfaction < 0 || satisfaction > 100)
                throw new IllegalArgumentException("invalid resident");
        }
    }

    public record Issue(long id, String type, long repairCents, Instant dueAt) {
        public Issue {
            requireText(type);
            Objects.requireNonNull(dueAt);
            if (id <= 0 || repairCents < 0) throw new IllegalArgumentException("invalid issue");
        }
    }

    public record Upgrade(String type, int level, boolean disabled) {
        public Upgrade {
            requireText(type);
            if (level < 0 || level > 3) throw new IllegalArgumentException("invalid upgrade");
        }
    }

    /** Settled money only; pending/review counts are events, never estimated cents. */
    public record Statement(LocalDate fromDay, LocalDate throughDay, long grossCents,
                            long expenseCents, int pendingCount, int reviewCount) {
        public Statement {
            Objects.requireNonNull(fromDay);
            Objects.requireNonNull(throughDay);
            if (!fromDay.plusDays(6).equals(throughDay) || grossCents < 0 || expenseCents < 0
                    || pendingCount < 0 || reviewCount < 0)
                throw new IllegalArgumentException("invalid seven-day statement");
        }

        public long netCents() {
            return Math.subtractExact(grossCents, expenseCents);
        }
    }

    private static void requireText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing display text");
    }
}
