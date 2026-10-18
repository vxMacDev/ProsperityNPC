package com.prosperitysmp.npc.charge;

import com.prosperitysmp.npc.charge.meter.MeterBaselines;
import com.prosperitysmp.npc.charge.meter.MeterRead;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.AccountLinkRepository;
import com.prosperitysmp.npc.economy.BillPayments;
import com.prosperitysmp.npc.economy.OwnerUnits;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The SQL side of configured bills: the policy record, the frozen invoices and their lines, expiry and the reads
 * the owner and staff views use. Worker thread only. Nothing here moves money; payment attempts live in
 * {@link BillPayments}.
 */
public final class ChargeInvoiceRepository {
    private static final int OWNER_PAGE_SIZE = 8;
    public enum State { ISSUED, PAID, EXPIRED }

    public record Line(int lineNo, String policyId, int version, String policySha, String meterId, Long meterUnits,
                       Boolean meterPowered, String meterDetailSha, String inputsJson, long rawCents, long finalCents,
                       boolean capped) { }

    public record Invoice(UUID invoiceId, UUID propertyId, LocalDate day, UUID ownerId, UUID benefitUnit, int epoch,
                          long revision, Instant issuedAt, LocalDate payableUntil, long totalCents, State state,
                          String payloadSha, List<Line> lines) { }

    /** A home as the issue step needs it, read on main from the cache; SQL re-checks every field under lock. */
    public record Home(UUID propertyId, UUID ownerId, int epoch, String classKey, String marketId) { }

    /** What one issue call did for one home. {@code invoice} is empty when no line was billed. */
    public record Issued(Optional<Invoice> invoice, boolean replay, InvoicePlanner.Plan plan, ChargeLimits.Outcome caps,
                         List<String> baselineResets) { }

    /** Today's totals for the staff status view. */
    public record Usage(int issued, int paid, int expired, long issuedCents, long paidCents) { }

    public record PaymentRef(UUID requestId, BillPayments.State state) { }

    /**
     * One page of a home's bills for its current owner. {@code holdRent} is true when the server holds rent for
     * unpaid bills ({@code on-unpaid: hold-rent}); the bill text then says which bill is pausing rent.
     */
    public record OwnerView(List<Invoice> invoices, Map<String, String> titles, boolean hasOlder,
                            Map<UUID, PaymentRef> payments, boolean holdRent) {
        public OwnerView(List<Invoice> invoices, Map<String, String> titles) { this(invoices, titles, false); }
        public OwnerView(List<Invoice> invoices, Map<String, String> titles, boolean hasOlder) {
            this(invoices, titles, hasOlder, Map.of());
        }
        public OwnerView(List<Invoice> invoices, Map<String, String> titles, boolean hasOlder,
                         Map<UUID, PaymentRef> payments) {
            this(invoices, titles, hasOlder, payments, false);
        }
        public OwnerView {
            invoices = List.copyOf(invoices);
            titles = Map.copyOf(titles);
            payments = Map.copyOf(payments);
        }
        public OwnerView withHoldRent(boolean on) {
            return on == holdRent ? this : new OwnerView(invoices, titles, hasOlder, payments, on);
        }
    }

    public record Period(ChargeCatalogue.Policy policy, LocalDate from, LocalDate until) {
        public boolean includes(LocalDate day) {
            return !day.isBefore(from) && (until == null || day.isBefore(until));
        }
    }

    private final Database database;
    private final BillPayments payments;
    private final AccountLinkRepository links;
    private final BooleanSupplier linksOn;
    private final Clock clock;

    public ChargeInvoiceRepository(Database database, BillPayments payments, AccountLinkRepository links,
                                   BooleanSupplier linksOn, Clock clock) {
        this.database = Objects.requireNonNull(database);
        this.payments = Objects.requireNonNull(payments);
        this.links = links;
        this.linksOn = Objects.requireNonNull(linksOn);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Deterministic from the home, owner, epoch and day, so a replay of the same day finds the same invoice. */
    public static UUID invoiceId(UUID property, UUID owner, int epoch, LocalDate day) {
        Objects.requireNonNull(property);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(day);
        return UUID.nameUUIDFromBytes(("bills-invoice-v1|" + property + "|" + owner + "|" + epoch + "|" + day)
                .getBytes(StandardCharsets.US_ASCII));
    }

    // ---- Policy record ------------------------------------------------------------------------------------------

    /** Every recorded policy version, as "policy@version" to its definition hash. */
    public Map<String, String> recordedPolicies() throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                "SELECT policy_id,version,definition_sha256 FROM npc_charge_policies ORDER BY policy_id,version LIMIT 10001");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.put(rs.getString(1) + "@" + rs.getInt(2), rs.getString(3));
        }
        return out;
    }

    /**
     * Records the catalogue's policies. A version seen for the first time takes effect on {@code effectiveDay} (the
     * next economy day). A recorded version that is no longer configured gets a retired day. Returns, for every
     * configured policy, the day it takes effect.
     */
    public Map<String, LocalDate> record(ChargeCatalogue.Catalogue catalogue, LocalDate today, LocalDate effectiveDay)
            throws SQLException {
        return record(catalogue, today, effectiveDay, () -> true);
    }

    Map<String, LocalDate> record(ChargeCatalogue.Catalogue catalogue, LocalDate today, LocalDate effectiveDay,
                                  BooleanSupplier current) throws SQLException {
        if (!effectiveDay.equals(today.plusDays(1))) throw new IllegalArgumentException("Policies start on the next day");
        Map<String, LocalDate> effective = new LinkedHashMap<>();
        transaction(c -> {
            if (!current.getAsBoolean()) throw new SQLException("Catalogue load was superseded");
            Set<String> configured = new HashSet<>();
            for (ChargeCatalogue.Policy p : catalogue.policies()) {
                String definition = ChargePolicyCodec.encode(p);
                configured.add(p.id() + "@" + p.version());
                try (PreparedStatement ps = prepare(c, """
                        INSERT IGNORE INTO npc_charge_policies(policy_id,version,definition_sha256,effective_day,definition_json)
                        VALUES (?,?,?,?,?)
                        """)) {
                    ps.setString(1, p.id());
                    ps.setInt(2, p.version());
                    ps.setString(3, p.sha256());
                    ps.setString(4, effectiveDay.toString());
                    ps.setString(5, definition);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = prepare(c, """
                        SELECT definition_sha256,effective_day,retired_day,definition_json FROM npc_charge_policies
                        WHERE policy_id=? AND version=? FOR UPDATE
                        """)) {
                    ps.setString(1, p.id());
                    ps.setInt(2, p.version());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !p.sha256().equals(rs.getString(1)))
                            throw new SQLException("Policy " + p.id() + " version " + p.version() + " was recorded with a different definition");
                        LocalDate day = LocalDate.parse(rs.getString(2));
                        String retired = rs.getString(3);
                        if (retired != null && !LocalDate.parse(retired).isAfter(today)) day = effectiveDay;
                        if (rs.getString(4) != null) {
                            try { ChargePolicyCodec.decode(p.id(), p.version(), p.sha256(), rs.getString(4)); }
                            catch (ChargeCatalogue.Rejected bad) { throw new SQLException(bad.getMessage()); }
                        }
                        effective.put(p.id(), day);
                    }
                }
                try (PreparedStatement ps = prepare(c, """
                        UPDATE npc_charge_policies SET retired_day=NULL,effective_day=?
                        WHERE policy_id=? AND version=? AND retired_day IS NOT NULL
                        """)) {
                    ps.setString(1, effective.get(p.id()).toString());
                    ps.setString(2, p.id());
                    ps.setInt(3, p.version());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = prepare(c, """
                        UPDATE npc_charge_policies SET definition_json=?
                        WHERE policy_id=? AND version=? AND definition_json IS NULL
                        """)) {
                    ps.setString(1, definition);
                    ps.setString(2, p.id());
                    ps.setInt(3, p.version());
                    ps.executeUpdate();
                }
            }
            List<String[]> open = new ArrayList<>();
            try (PreparedStatement ps = prepare(c,
                    "SELECT policy_id,version,effective_day FROM npc_charge_policies WHERE retired_day IS NULL LIMIT 10001");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) open.add(new String[]{rs.getString(1), Integer.toString(rs.getInt(2)), rs.getString(3)});
            }
            for (String[] row : open) {
                if (configured.contains(row[0] + "@" + row[1])) continue;
                LocalDate first = LocalDate.parse(row[2]);
                LocalDate retired = first.isAfter(effectiveDay) ? first : effectiveDay;
                try (PreparedStatement ps = prepare(c, """
                        UPDATE npc_charge_policies SET retired_day=? WHERE policy_id=? AND version=? AND retired_day IS NULL
                        """)) {
                    ps.setString(1, retired.toString());
                    ps.setString(2, row[0]);
                    ps.setInt(3, Integer.parseInt(row[1]));
                    ps.executeUpdate();
                }
            }
            if (!current.getAsBoolean()) throw new SQLException("Catalogue load was superseded");
            return null;
        });
        return effective;
    }

    /** Current and staged terms, including versions removed from the edited config for tomorrow. */
    public List<Period> policyPeriods(LocalDate today) throws SQLException, ChargeCatalogue.Rejected {
        List<Period> periods = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT policy_id,version,definition_sha256,definition_json,effective_day,retired_day
                FROM npc_charge_policies WHERE (retired_day IS NULL OR retired_day>?)
                  AND (retired_day IS NULL OR retired_day>effective_day)
                ORDER BY policy_id,version LIMIT 33
                """)) {
            ps.setString(1, today.toString());
            try (ResultSet rows = ps.executeQuery()) {
                while (rows.next()) {
                    if (periods.size() == 32) throw new SQLException("Too many current or staged bill policies");
                    try {
                        var policy = ChargePolicyCodec.decode(rows.getString(1), rows.getInt(2), rows.getString(3), rows.getString(4));
                        String retired = rows.getString(6);
                        periods.add(new Period(policy, LocalDate.parse(rows.getString(5)),
                                retired == null ? null : LocalDate.parse(retired)));
                    } catch (IllegalArgumentException bad) {
                        throw new SQLException("Recorded bill terms are unavailable", bad);
                    }
                }
            }
        }
        for (int i = 0; i < periods.size(); i++) {
            Period a = periods.get(i);
            for (int j = i + 1; j < periods.size(); j++) {
                Period b = periods.get(j);
                if (a.policy().id().equals(b.policy().id())
                        && (a.until() == null || b.from().isBefore(a.until()))
                        && (b.until() == null || a.from().isBefore(b.until())))
                    throw new SQLException("Overlapping recorded bill versions: " + a.policy().id());
            }
        }
        return List.copyOf(periods);
    }

    /** Terms for an old invoice line; a missing definition stays unknown. */
    public ChargeCatalogue.Policy policy(String id, int version, String hash) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT definition_sha256,definition_json FROM npc_charge_policies WHERE policy_id=? AND version=?
                """)) {
            ps.setString(1, id);
            ps.setInt(2, version);
            try (ResultSet rows = ps.executeQuery()) {
                if (!rows.next() || !hash.equals(rows.getString(1))) throw new SQLException("Recorded bill policy binding differs");
                try { return ChargePolicyCodec.decode(id, version, hash, rows.getString(2)); }
                catch (ChargeCatalogue.Rejected bad) { throw new SQLException(bad.getMessage()); }
            }
        }
    }

    // ---- Daily expiry -------------------------------------------------------------------------------------------

    /**
     * Marks ISSUED invoices whose last payable day is before {@code today} as EXPIRED. An invoice with a payment
     * still in flight or held for review is left alone. No money moves.
     */
    public int expire(LocalDate today) throws SQLException {
        return expire(today, () -> true);
    }

    int expire(LocalDate today, BooleanSupplier current) throws SQLException {
        return transaction(c -> {
            try (PreparedStatement ps = prepare(c, """
                    UPDATE npc_charge_invoices i SET state='EXPIRED'
                    WHERE i.state='ISSUED' AND i.payable_until_day<?
                      AND NOT EXISTS (SELECT 1 FROM npc_charge_payments p WHERE p.invoice_id=i.invoice_id
                                      AND p.state IN ('WAITING','REVIEW_REQUIRED'))
                    """)) {
                ps.setString(1, today.toString());
                return ps.executeUpdate();
            }
        }, current);
    }

    // ---- Issue --------------------------------------------------------------------------------------------------

    /**
     * Issues at most one invoice for one home and day, in one transaction. The home's row is locked and re-checked
     * (same owner, epoch, enabled), its facts are read, the lines are worked out, then the owner-unit and server
     * limits are re-checked in SQL against ISSUED and PAID invoices for the day. A second call for the same home and
     * day returns the stored invoice and writes nothing.
     */
    public Issued issue(Home home, LocalDate day, List<ChargeCatalogue.Policy> policies, Map<String, MeterRead.Frozen> readings,
                        Map<String, String> meterFailures, long ownerDailyMaxCents, long serverDailyMaxCents,
                        int payableDays, Set<String> cumulativeMeters) throws SQLException {
        return issue(home, day, policies, readings, meterFailures, ownerDailyMaxCents, serverDailyMaxCents,
                payableDays, cumulativeMeters, () -> true);
    }

    Issued issue(Home home, LocalDate day, List<ChargeCatalogue.Policy> policies, Map<String, MeterRead.Frozen> readings,
                 Map<String, String> meterFailures, long ownerDailyMaxCents, long serverDailyMaxCents,
                 int payableDays, Set<String> cumulativeMeters, BooleanSupplier current) throws SQLException {
        Objects.requireNonNull(home);
        Objects.requireNonNull(day);
        UUID id = invoiceId(home.propertyId(), home.ownerId(), home.epoch(), day);
        return transaction(c -> {
            // Share the cap and linked-account lock used by rent, before taking any home or invoice locks.
            try (PreparedStatement ps = prepare(c, "SELECT singleton FROM npc_rent_mutex WHERE singleton=1 FOR UPDATE");
                 ResultSet rows = ps.executeQuery()) {
                if (!rows.next()) throw new SQLException("Bill budget lock unavailable");
            }
            Optional<Invoice> existing = read(c, home.propertyId(), day, true);
            if (existing.isPresent()) return new Issued(existing, true, new InvoicePlanner.Plan(List.of(), List.of()),
                    ChargeLimits.apply(List.of(), 0, 0), List.of());
            Map<String, Long> facts = new LinkedHashMap<>();
            long revision = facts(c, home, day, facts);
            if (revision < 0) return new Issued(Optional.empty(), false, new InvoicePlanner.Plan(List.of(), List.of()),
                    ChargeLimits.apply(List.of(), 0, 0), List.of());
            OwnerUnits units = links == null ? OwnerUnits.identity() : links.unitsFor(c, day, linksOn.getAsBoolean());
            UUID unit = units.unitOf(home.ownerId());
            facts.put("owner_homes", ownerHomes(c, units.members(home.ownerId())));
            List<ChargeCatalogue.Policy> applicable = applicable(policies, home, facts);
            // Cumulative meters bill the increase over the stored baseline; the first reading and a reset bill nothing.
            Map<String, Long> cumulative = new HashMap<>();
            List<String> resets = new ArrayList<>();
            Map<String, Long> newBaselines = new LinkedHashMap<>();
            for (ChargeCatalogue.Policy p : applicable) {
                MeterRead.Frozen reading = readings.get(p.id());
                if (reading == null || !cumulativeMeters.contains(p.meter().id())) continue;
                var delta = MeterBaselines.delta(MeterBaselines.read(c, home.propertyId(), p.meter().id()), reading.units());
                newBaselines.put(p.meter().id(), reading.units());
                switch (delta.kind()) {
                    case FIRST -> { }
                    case RESET -> resets.add(p.id());
                    case INCREASE -> cumulative.put(p.id(), delta.units());
                }
            }
            Map<String, MeterRead.Frozen> usable = new LinkedHashMap<>(readings);
            for (ChargeCatalogue.Policy p : applicable)
                if (cumulativeMeters.contains(p.meter().id()) && !cumulative.containsKey(p.id())) usable.remove(p.id());
            Map<String, String> failures = new LinkedHashMap<>(meterFailures);
            for (String reset : resets) failures.put(reset, "meter reset; new baseline set");
            InvoicePlanner.Plan plan = InvoicePlanner.plan(applicable, facts, usable, failures, cumulative);
            for (var e : newBaselines.entrySet()) MeterBaselines.write(c, home.propertyId(), e.getKey(), e.getValue(), day);
            long ownerUsed = used(c, "benefit_unit_id=?", unit, day);
            long serverUsed = used(c, null, null, day);
            List<ChargeLimits.Candidate> candidates = new ArrayList<>();
            for (InvoicePlanner.Draft d : plan.drafts()) candidates.add(d.candidate());
            ChargeLimits.Outcome caps = ChargeLimits.apply(candidates,
                    Math.min(ownerDailyMaxCents, ChargeLimits.OWNER_DAILY_CEILING_CENTS) - ownerUsed,
                    Math.min(serverDailyMaxCents, ChargeLimits.SERVER_DAILY_CEILING_CENTS) - serverUsed);
            if (caps.lines().isEmpty()) return new Issued(Optional.empty(), false, plan, caps, resets);
            Map<String, InvoicePlanner.Draft> byPolicy = new HashMap<>();
            for (InvoicePlanner.Draft d : plan.drafts()) byPolicy.put(d.policy().id(), d);
            List<Line> lines = new ArrayList<>();
            int lineNo = 0;
            for (ChargeLimits.Line l : caps.lines()) {
                InvoicePlanner.Draft d = byPolicy.get(l.policyId());
                lines.add(new Line(++lineNo, d.policy().id(), d.policy().version(), d.policy().sha256(), d.meterId(),
                        d.meterUnits(), d.meterPowered(), d.meterDetailSha(), d.inputsJson(), l.rawCents(), l.finalCents(),
                        l.capped()));
            }
            Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            Invoice invoice = new Invoice(id, home.propertyId(), day, home.ownerId(), unit, home.epoch(), revision, issuedAt,
                    day.plusDays(Math.max(1, payableDays) - 1L), caps.total(), State.ISSUED, null, lines);
            invoice = withPayload(invoice);
            insert(c, invoice);
            Invoice stored = read(c, home.propertyId(), day, false).orElseThrow(() -> new SQLException("Invoice not stored"));
            if (stored.totalCents() != lines.stream().mapToLong(Line::finalCents).sum() || !stored.payloadSha().equals(invoice.payloadSha()))
                throw new SQLException("Invoice total does not equal its lines");
            return new Issued(Optional.of(stored), false, plan, caps, resets);
        }, current);
    }

    /** A dry run for staff: the lines this home would get today, with no write and no money. */
    public InvoicePlanner.Plan preview(Home home, LocalDate day, List<ChargeCatalogue.Policy> policies,
                                       Map<String, MeterRead.Frozen> readings, Map<String, String> failures) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                Map<String, Long> facts = new LinkedHashMap<>();
                if (facts(c, home, day, facts) < 0) return new InvoicePlanner.Plan(List.of(), List.of());
                OwnerUnits units = links == null ? OwnerUnits.identity() : links.unitsFor(c, day, false);
                facts.put("owner_homes", ownerHomes(c, units.members(home.ownerId())));
                return InvoicePlanner.plan(applicable(policies, home, facts), facts, readings, failures, Map.of());
            } finally {
                c.rollback();
            }
        }
    }

    /** The policies that cover this home, by occupancy, class, market and property list. */
    static List<ChargeCatalogue.Policy> applicable(List<ChargeCatalogue.Policy> policies, Home home, Map<String, Long> facts) {
        int tenants = facts.getOrDefault("tenants", 0L).intValue();
        List<ChargeCatalogue.Policy> out = new ArrayList<>();
        for (var p : policies) if (p.appliesTo(home.propertyId(), home.classKey(), home.marketId(), tenants)) out.add(p);
        return out;
    }

    /**
     * The home's facts for formulas. Returns the row version, or -1 when the home is no longer the same incarnation
     * (owner, epoch) or is disabled, in which case nothing is issued.
     */
    private long facts(Connection c, Home home, LocalDate day, Map<String, Long> out) throws SQLException {
        long revision;
        int capacity;
        try (PreparedStatement ps = prepare(c, """
                SELECT owner_uuid,ownership_epoch,class_key,market_id,row_version,capacity,enabled,condition_score,asking_rent_cents,
                       reference_rent_cents FROM npc_properties WHERE property_id=? FOR UPDATE
                """)) {
            id(ps, 1, home.propertyId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return -1;
                byte[] owner = rs.getBytes("owner_uuid");
                if (owner == null || !home.ownerId().equals(Uuids.fromBytes(owner)) || rs.getInt("ownership_epoch") != home.epoch()
                        || rs.getInt("enabled") != 1 || !home.classKey().equals(rs.getString("class_key"))
                        || !home.marketId().equals(rs.getString("market_id"))) return -1;
                revision = rs.getLong("row_version");
                capacity = rs.getInt("capacity");
                out.put("capacity", (long) capacity);
                out.put("condition", rs.getLong("condition_score"));
                out.put("asking_rent_cents", rs.getLong("asking_rent_cents"));
                out.put("reference_rent_cents", rs.getLong("reference_rent_cents"));
            }
        }
        Set<Integer> slots = new HashSet<>();
        try (PreparedStatement ps = prepare(c, """
                SELECT slot FROM npc_tenants WHERE property_id=? ORDER BY tenant_id LIMIT 5 ROWS EXAMINED 128 FOR UPDATE
                """)) {
            id(ps, 1, home.propertyId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int slot = rs.getInt("slot");
                    if (slot < 0 || slot >= capacity || !slots.add(slot)) throw new SQLException("Invalid bill tenancy slots");
                }
            }
        }
        out.put("tenants", (long) slots.size());
        long advertising = 0, facilities = 0;
        try (PreparedStatement ps = prepare(c, """
                SELECT upgrade_type,level FROM npc_upgrades WHERE property_id=? ORDER BY upgrade_type
                LIMIT 3 ROWS EXAMINED 128 FOR UPDATE
                """)) {
            id(ps, 1, home.propertyId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int level = rs.getInt("level");
                    if (level < 0 || level > 3) throw new SQLException("Invalid bill upgrade level");
                    switch (String.valueOf(rs.getString("upgrade_type"))) {
                        case "ADVERTISING" -> advertising = level;
                        case "FACILITIES" -> facilities = level;
                        default -> throw new SQLException("Invalid bill upgrade type");
                    }
                }
            }
        }
        out.put("advertising_level", advertising);
        out.put("facilities_level", facilities);
        out.put("upgrade_levels", advertising + facilities);
        // Rent proved for the previous completed economy day, so a tax on rent cannot be dodged by timing.
        out.put("rent_paid_cents", payments.provedRentPaidCents(c, home.propertyId(), day.minusDays(1)));
        return revision;
    }

    private static long ownerHomes(Connection c, List<UUID> members) throws SQLException {
        long count = 0;
        for (UUID member : members) {
            try (PreparedStatement ps = prepare(c, "SELECT COUNT(*) FROM npc_properties WHERE owner_uuid=? AND enabled=1")) {
                id(ps, 1, member);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) count += rs.getLong(1);
                }
            }
        }
        return count;
    }

    /** Cents already issued or paid on this day, for one unit or (with a null where) the whole server. Locks the rows. */
    private static long used(Connection c, String where, UUID unit, LocalDate day) throws SQLException {
        String sql = "SELECT COALESCE(SUM(total_cents),0) FROM npc_charge_invoices WHERE economy_day=? "
                + "AND state IN ('ISSUED','PAID')" + (where == null ? "" : " AND " + where) + " FOR UPDATE";
        try (PreparedStatement ps = prepare(c, sql)) {
            ps.setString(1, day.toString());
            if (where != null) id(ps, 2, unit);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    static Invoice withPayload(Invoice i) {
        StringBuilder text = new StringBuilder("bills-invoice-v1|").append(i.invoiceId()).append('|').append(i.propertyId())
                .append('|').append(i.day()).append('|').append(i.ownerId()).append('|').append(i.benefitUnit()).append('|')
                .append(i.epoch()).append('|').append(i.revision()).append('|').append(i.issuedAt().toEpochMilli()).append('|')
                .append(i.payableUntil()).append('|').append(i.totalCents());
        for (Line l : i.lines())
            text.append("|line|").append(l.lineNo()).append('|').append(l.policyId()).append('|').append(l.version()).append('|')
                    .append(l.policySha()).append('|').append(l.meterId()).append('|').append(l.meterUnits()).append('|')
                    .append(l.meterPowered()).append('|').append(l.meterDetailSha()).append('|').append(l.inputsJson()).append('|')
                    .append(l.rawCents()).append('|').append(l.finalCents()).append('|').append(l.capped());
        return new Invoice(i.invoiceId(), i.propertyId(), i.day(), i.ownerId(), i.benefitUnit(), i.epoch(), i.revision(),
                i.issuedAt(), i.payableUntil(), i.totalCents(), i.state(), sha256(text.toString()), i.lines());
    }

    private static void insert(Connection c, Invoice i) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                INSERT INTO npc_charge_invoices(invoice_id,property_id,economy_day,owner_uuid,benefit_unit_id,ownership_epoch,
                  property_revision,issued_at,payable_until_day,total_cents,state,payload_sha256)
                VALUES (?,?,?,?,?,?,?,?,?,?,'ISSUED',?)
                """)) {
            id(ps, 1, i.invoiceId());
            id(ps, 2, i.propertyId());
            ps.setString(3, i.day().toString());
            id(ps, 4, i.ownerId());
            id(ps, 5, i.benefitUnit());
            ps.setInt(6, i.epoch());
            ps.setLong(7, i.revision());
            ps.setObject(8, LocalDateTime.ofInstant(i.issuedAt(), ZoneOffset.UTC));
            ps.setString(9, i.payableUntil().toString());
            ps.setLong(10, i.totalCents());
            ps.setString(11, i.payloadSha());
            one(ps.executeUpdate());
        }
        for (Line l : i.lines()) {
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_charge_invoice_lines(invoice_id,line_no,policy_id,version,policy_sha256,meter_id,
                      meter_units,meter_powered,meter_detail_sha256,inputs_json,raw_cents,final_cents,capped)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                id(ps, 1, i.invoiceId());
                ps.setInt(2, l.lineNo());
                ps.setString(3, l.policyId());
                ps.setInt(4, l.version());
                ps.setString(5, l.policySha());
                ps.setString(6, l.meterId());
                ps.setObject(7, l.meterUnits());
                ps.setObject(8, l.meterPowered() == null ? null : (l.meterPowered() ? 1 : 0));
                ps.setString(9, l.meterDetailSha());
                ps.setString(10, l.inputsJson());
                ps.setLong(11, l.rawCents());
                ps.setLong(12, l.finalCents());
                ps.setInt(13, l.capped() ? 1 : 0);
                one(ps.executeUpdate());
            }
        }
    }

    // ---- Reads --------------------------------------------------------------------------------------------------

    public Optional<Invoice> find(UUID invoiceId) throws SQLException {
        try (Connection c = database.getConnection()) {
            return readWhere(c, "invoice_id=?", ps -> id(ps, 1, invoiceId), false).stream().findFirst();
        }
    }

    /** The home's most recent invoices, newest first. */
    public List<Invoice> recent(UUID propertyId, int limit) throws SQLException {
        try (Connection c = database.getConnection()) {
            return readWhere(c, "property_id=? ORDER BY economy_day DESC LIMIT " + Math.clamp(limit, 1, 50),
                    ps -> id(ps, 1, propertyId), false);
        }
    }

    /** Current ownership only. Titles come from the versions frozen into these invoices. */
    public OwnerView ownerView(UUID property, UUID owner, int epoch) throws SQLException {
        return ownerView(property, owner, epoch, null);
    }

    /** The next page starts before the last displayed economy day. */
    public OwnerView ownerView(UUID property, UUID owner, int epoch, LocalDate before) throws SQLException {
        return ownerView(property, owner, epoch, before, null);
    }

    public Optional<Invoice> ownerInvoice(UUID property, UUID owner, int epoch, UUID invoice) throws SQLException {
        Objects.requireNonNull(invoice);
        return ownerView(property, owner, epoch, null, invoice).invoices().stream().findFirst();
    }

    private OwnerView ownerView(UUID property, UUID owner, int epoch, LocalDate before, UUID invoice) throws SQLException {
        Objects.requireNonNull(property);
        Objects.requireNonNull(owner);
        if (epoch < 0) throw new IllegalArgumentException("Invalid owner epoch");
        if (before != null && (before.getYear() < 1 || before.getYear() > 9999))
            throw new IllegalArgumentException("Invalid bill page day");
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try (PreparedStatement begin = prepare(c, "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")) {
                begin.execute();
            }
            try {
                var headers = readHeaders(c, """
                        property_id=? AND owner_uuid=? AND ownership_epoch=? AND EXISTS (
                            SELECT 1 FROM npc_properties p WHERE p.property_id=npc_charge_invoices.property_id
                            AND p.owner_uuid=npc_charge_invoices.owner_uuid
                            AND p.ownership_epoch=npc_charge_invoices.ownership_epoch AND p.enabled=TRUE)
                        """ + (before == null ? "" : " AND economy_day<?") + (invoice == null ? "" : " AND invoice_id=?")
                                + " ORDER BY economy_day DESC LIMIT " + (OWNER_PAGE_SIZE + 1),
                        ps -> {
                            id(ps, 1, property); id(ps, 2, owner); ps.setInt(3, epoch);
                            if (before != null) ps.setString(4, before.toString());
                            else if (invoice != null) id(ps, 4, invoice);
                        }, false);
                boolean hasOlder = headers.size() > OWNER_PAGE_SIZE;
                var rows = withLines(c, headers.subList(0, Math.min(OWNER_PAGE_SIZE, headers.size())));
                return new OwnerView(rows, frozenTitles(c, rows), hasOlder, paymentRefs(c, rows));
            } finally { c.rollback(); }
        }
    }

    private static Map<UUID, PaymentRef> paymentRefs(Connection c, List<Invoice> invoices) throws SQLException {
        if (invoices.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(invoices.size(), "?"));
        Map<UUID, PaymentRef> payments = new HashMap<>();
        try (PreparedStatement ps = prepare(c, "SELECT invoice_id,request_id,state FROM npc_charge_payments WHERE invoice_id IN ("
                + placeholders + ") ORDER BY invoice_id,attempt_number DESC LIMIT 145")) {
            for (int i = 0; i < invoices.size(); i++) id(ps, i + 1, invoices.get(i).invoiceId());
            try (ResultSet rows = ps.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 144) throw new SQLException("Too many invoice attempts");
                    UUID invoice = Uuids.fromBytes(rows.getBytes(1));
                    try {
                        payments.putIfAbsent(invoice, new PaymentRef(Uuids.fromBytes(rows.getBytes(2)),
                                BillPayments.State.valueOf(rows.getString(3))));
                    } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid bill payment state", invalid); }
                }
            }
        }
        return payments;
    }

    private static Map<String, String> frozenTitles(Connection c, List<Invoice> invoices) throws SQLException {
        if (invoices.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(invoices.size(), "?"));
        Map<String, String> titles = new HashMap<>();
        try (PreparedStatement ps = prepare(c, """
                SELECT p.policy_id,p.version,p.definition_sha256,p.definition_json FROM npc_charge_policies p
                JOIN (SELECT DISTINCT policy_id,version FROM npc_charge_invoice_lines WHERE invoice_id IN (
                """ + placeholders + ")) used ON used.policy_id=p.policy_id AND used.version=p.version LIMIT 161")) {
            for (int i = 0; i < invoices.size(); i++) id(ps, i + 1, invoices.get(i).invoiceId());
            try (ResultSet rows = ps.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    if (++count > 160) throw new SQLException("Too many invoice policies");
                    try {
                        var policy = ChargePolicyCodec.decode(rows.getString(1), rows.getInt(2), rows.getString(3), rows.getString(4));
                        titles.put(policy.sha256(), policy.title());
                    } catch (ChargeCatalogue.Rejected unavailable) {
                        // Older invoices may have no recorded terms. Keep their id and version visible.
                    }
                }
            }
        }
        return titles;
    }

    /**
     * Every ISSUED invoice that still belongs to the home's current ownership: home, owner, epoch, day and last
     * payable day. An invoice from an earlier owner (or epoch), or for a home that is no longer enrolled, is left
     * out, so it can never hold the current owner's rent. The hold-rent check filters these for "an earlier day,
     * still payable" against the day it runs, and checks the owner and epoch again against the cached home.
     */
    public List<BillingRuntime.Open> openInvoices() throws SQLException {
        List<BillingRuntime.Open> out = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT i.property_id,i.owner_uuid,i.ownership_epoch,i.economy_day,i.payable_until_day
                FROM npc_charge_invoices i JOIN npc_properties p ON p.property_id=i.property_id
                WHERE i.state='ISSUED' AND p.owner_uuid=i.owner_uuid AND p.ownership_epoch=i.ownership_epoch
                  AND p.enabled=1
                ORDER BY i.economy_day LIMIT 100001
                """); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (out.size() >= 100_000) throw new SQLException("Too many open invoices");
                out.add(new BillingRuntime.Open(Uuids.fromBytes(rs.getBytes(1)), Uuids.fromBytes(rs.getBytes(2)),
                        rs.getInt(3), LocalDate.parse(rs.getString(4)), LocalDate.parse(rs.getString(5))));
            }
        }
        return out;
    }

    /** Whether this home has ever had an invoice; the owner desk shows a bills line only then. */
    public boolean everBilled(UUID propertyId) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                "SELECT 1 FROM npc_charge_invoices WHERE property_id=? LIMIT 1")) {
            id(ps, 1, propertyId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public Usage usage(LocalDate day) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT SUM(state='ISSUED'),SUM(state='PAID'),SUM(state='EXPIRED'),
                       COALESCE(SUM(CASE WHEN state IN ('ISSUED','PAID') THEN total_cents ELSE 0 END),0),
                       COALESCE(SUM(CASE WHEN state='PAID' THEN total_cents ELSE 0 END),0)
                FROM npc_charge_invoices WHERE economy_day=?
                """)) {
            ps.setString(1, day.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new Usage(0, 0, 0, 0, 0);
                return new Usage(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getLong(4), rs.getLong(5));
            }
        }
    }

    private Optional<Invoice> read(Connection c, UUID property, LocalDate day, boolean lock) throws SQLException {
        return readWhere(c, "property_id=? AND economy_day=?", ps -> {
            id(ps, 1, property);
            ps.setString(2, day.toString());
        }, lock).stream().findFirst();
    }

    @FunctionalInterface private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    private static List<Invoice> readWhere(Connection c, String where, Binder binder, boolean lock) throws SQLException {
        return withLines(c, readHeaders(c, where, binder, lock));
    }

    private static List<Invoice> readHeaders(Connection c, String where, Binder binder, boolean lock) throws SQLException {
        List<Invoice> out = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_charge_invoices WHERE " + where + (lock ? " FOR UPDATE" : ""))) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    State state;
                    try { state = State.valueOf(rs.getString("state")); }
                    catch (RuntimeException invalid) { throw new SQLException("Invalid invoice state", invalid); }
                    out.add(new Invoice(Uuids.fromBytes(rs.getBytes("invoice_id")), Uuids.fromBytes(rs.getBytes("property_id")),
                            LocalDate.parse(rs.getString("economy_day")), Uuids.fromBytes(rs.getBytes("owner_uuid")),
                            Uuids.fromBytes(rs.getBytes("benefit_unit_id")), rs.getInt("ownership_epoch"),
                            rs.getLong("property_revision"),
                            rs.getObject("issued_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                            LocalDate.parse(rs.getString("payable_until_day")), rs.getLong("total_cents"), state,
                            rs.getString("payload_sha256"), List.of()));
                }
            }
        }
        return out;
    }

    private static List<Invoice> withLines(Connection c, List<Invoice> out) throws SQLException {
        List<Invoice> withLines = new ArrayList<>();
        for (Invoice i : out) withLines.add(new Invoice(i.invoiceId(), i.propertyId(), i.day(), i.ownerId(), i.benefitUnit(),
                i.epoch(), i.revision(), i.issuedAt(), i.payableUntil(), i.totalCents(), i.state(), i.payloadSha(), lines(c, i.invoiceId())));
        return withLines;
    }

    private static List<Line> lines(Connection c, UUID invoice) throws SQLException {
        List<Line> out = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_charge_invoice_lines WHERE invoice_id=? ORDER BY line_no LIMIT 17")) {
            id(ps, 1, invoice);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Object units = rs.getObject("meter_units"), powered = rs.getObject("meter_powered");
                    out.add(new Line(rs.getInt("line_no"), rs.getString("policy_id"), rs.getInt("version"),
                            rs.getString("policy_sha256"), rs.getString("meter_id"),
                            units == null ? null : ((Number) units).longValue(),
                            powered == null ? null : ((Number) powered).intValue() == 1,
                            rs.getString("meter_detail_sha256"), rs.getString("inputs_json"), rs.getLong("raw_cents"),
                            rs.getLong("final_cents"), rs.getInt("capped") == 1));
                }
            }
        }
        return List.copyOf(out);
    }

    // ---- Helpers ------------------------------------------------------------------------------------------------

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void id(PreparedStatement ps, int index, UUID id) throws SQLException { ps.setBytes(index, Uuids.toBytes(id)); }

    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            return ps;
        } catch (SQLException failure) {
            ps.close();
            throw failure;
        }
    }

    private static void one(int count) throws SQLException { if (count != 1) throw new SQLException("Invoice write count mismatch"); }

    @FunctionalInterface private interface Transaction<T> { T run(Connection c) throws SQLException; }

    private <T> T transaction(Transaction<T> work) throws SQLException {
        return transaction(work, () -> true);
    }

    private <T> T transaction(Transaction<T> work, BooleanSupplier current) throws SQLException {
        Objects.requireNonNull(current);
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                if (!current.getAsBoolean()) throw new SQLException("Bill work was superseded");
                T result = work.run(c);
                // A lock wait can outlast a reload, shutdown or day reset.
                if (!current.getAsBoolean()) throw new SQLException("Bill work was superseded");
                c.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

}
