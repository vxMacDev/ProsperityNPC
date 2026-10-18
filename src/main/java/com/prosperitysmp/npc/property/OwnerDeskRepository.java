package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.AccountLinkRepository;
import com.prosperitysmp.npc.economy.EventSource;
import com.prosperitysmp.npc.economy.EventState;
import com.prosperitysmp.npc.economy.PurchasePrices;
import com.prosperitysmp.npc.economy.UtilityBill;
import com.prosperitysmp.npc.economy.UtilityRepository;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.market.OwnerHousingView;
import com.prosperitysmp.npc.town.PartnerPerks;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Worker-only SQL reader; callers own live ARM/session authorization before delivery. */
public final class OwnerDeskRepository {
    private static final int QUERY_SECONDS = 2;
    private static final int MAX_ISSUES = 16;
    private static final int MAX_EVENTS = 1024;
    private final Database database;
    private final PurchasePrices prices;
    private final ServiceAssessmentRepository services;
    private final Clock clock;
    private final ZoneId zone;
    private final int resetHour;
    // Read live per snapshot: the pending-rent line needs V31 columns and housing.rent-change-delay-days above 0.
    private final BooleanSupplier pendingRent;
    // linked-accounts.enabled and benefit-unit-daily-cap-cents, read live per snapshot. Off: no link table is read.
    private final BooleanSupplier linkedEnabled;
    private final LongSupplier linkedLimitCents;
    // town.partnerships, read live per snapshot. Off: no partnership table is read.
    private final Supplier<PartnerConfig> partners;
    // The configured property backend (V45). Null: any backend's row is read (callers without a backend).
    private volatile String backendId;

    /**
     * Live partnership settings for the desk line: enabled (town.enabled and town.partnerships.enabled), rankLive
     * (the rank perk acts on matching) and the current config perks per resident, for min(snapshot, config).
     */
    public record PartnerConfig(boolean enabled, boolean rankLive,
                                Function<UUID, PartnerPerks> configPerks) {
        public static final PartnerConfig OFF = new PartnerConfig(false, false, id -> PartnerPerks.NONE);
        public PartnerConfig { Objects.requireNonNull(configPerks); }
    }

    public OwnerDeskRepository(Database database) { this(database, PurchasePrices.DEFAULT); }

    public OwnerDeskRepository(Database database, PurchasePrices prices) {
        this.prices = Objects.requireNonNull(prices);
        this.database = Objects.requireNonNull(database);
        pendingRent = () -> false;
        linkedEnabled = () -> false;
        linkedLimitCents = () -> 0L;
        partners = () -> PartnerConfig.OFF;
        services = null;
        clock = null;
        zone = ZoneOffset.UTC;
        resetHour = 0;
    }

    public OwnerDeskRepository(Database database, Clock clock, ZoneId zone, int resetHour) {
        this(database, clock, zone, resetHour, () -> false);
    }

    public OwnerDeskRepository(Database database, Clock clock, ZoneId zone, int resetHour, PurchasePrices prices) {
        this(database, clock, zone, resetHour, () -> false, () -> false, () -> 0L, () -> PartnerConfig.OFF, prices);
    }

    public OwnerDeskRepository(Database database, Clock clock, ZoneId zone, int resetHour,
                               BooleanSupplier pendingRent) {
        this(database, clock, zone, resetHour, pendingRent, () -> false, () -> 0L);
    }

    /** linkedEnabled on: the desk shows one line when the owner shares the daily rent limit with linked accounts. */
    public OwnerDeskRepository(Database database, Clock clock, ZoneId zone, int resetHour,
                               BooleanSupplier pendingRent,
                               BooleanSupplier linkedEnabled, LongSupplier linkedLimitCents) {
        this(database, clock, zone, resetHour, pendingRent, linkedEnabled, linkedLimitCents, () -> PartnerConfig.OFF);
    }

    /** partners on: the desk shows the owner's active partnership line. */
    public OwnerDeskRepository(Database database, Clock clock, ZoneId zone, int resetHour,
                               BooleanSupplier pendingRent,
                               BooleanSupplier linkedEnabled, LongSupplier linkedLimitCents,
                               Supplier<PartnerConfig> partners) {
        this(database, clock, zone, resetHour, pendingRent, linkedEnabled, linkedLimitCents, partners, PurchasePrices.DEFAULT);
    }

    public OwnerDeskRepository(Database database, Clock clock, ZoneId zone, int resetHour,
                               BooleanSupplier pendingRent, BooleanSupplier linkedEnabled, LongSupplier linkedLimitCents,
                               Supplier<PartnerConfig> partners, PurchasePrices prices) {
        this.prices = Objects.requireNonNull(prices);
        this.partners = Objects.requireNonNull(partners);
        this.linkedEnabled = Objects.requireNonNull(linkedEnabled);
        this.linkedLimitCents = Objects.requireNonNull(linkedLimitCents);
        this.pendingRent = Objects.requireNonNull(pendingRent);
        this.database = Objects.requireNonNull(database);
        this.zone = Objects.requireNonNull(zone);
        this.clock = Objects.requireNonNull(clock);
        this.resetHour = resetHour;
        services = new ServiceAssessmentRepository(database, clock, zone, resetHour);
    }

    /** Only homes enrolled under this property backend are read; a home under another backend reads as absent. */
    public OwnerDeskRepository backendId(String id) {
        backendId = id;
        return this;
    }

    /** Empty means absent, disabled or not owned; contradictory/overflowing data throws, never zero. */
    public Optional<OwnerDeskData> read(UUID world, String region, UUID owner, LocalDate endDay)
            throws SQLException {
        return read(world, region, owner, endDay, false);
    }

    public Optional<OwnerDeskData> read(UUID world, String region, UUID owner, LocalDate endDay, boolean includeBills)
            throws SQLException {
        return read(world, region, owner, endDay, includeBills, false);
    }

    public Optional<OwnerDeskData> read(UUID world, String region, UUID owner, LocalDate endDay,
                                        boolean includeBills, boolean includeUtilityBill) throws SQLException {
        Objects.requireNonNull(world);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(endDay);
        LocalDate fromDay = endDay.minusDays(6);
        if (region == null || region.isBlank() || region.length() > 128
                || fromDay.getYear() < 1 || endDay.getYear() > 9999)
            throw new IllegalArgumentException("invalid owner desk scope");
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                Instant at = clock == null ? null : clock.instant().truncatedTo(ChronoUnit.MILLIS);
                // JDBC setReadOnly can be only a routing hint. Enforce read-only on MariaDB itself.
                try (PreparedStatement begin = prepare(c,
                        "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")) {
                    begin.execute();
                    require(c.getWarnings() == null && begin.getWarnings() == null, "owner snapshot incomplete");
                }
                Optional<OwnerDeskData> result = snapshot(c, world, region, owner, fromDay, endDay, at, includeBills, includeUtilityBill);
                c.rollback();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                if (failure instanceof SQLException sql) throw sql;
                throw new SQLException("Invalid owner desk snapshot", failure);
            }
        }
    }

    private Optional<OwnerDeskData> snapshot(Connection c, UUID world, String region, UUID owner,
                                           LocalDate from, LocalDate through, Instant at, boolean includeBills,
                                           boolean includeUtilityBill) throws SQLException {
        Property property;
        long revision;
        Instant captured;
        String backend = backendId;
        String sql = """
                SELECT property_id, world_uuid, region_id, market_id, class_key, capacity,
                       reference_rent_cents, asking_rent_cents, owner_uuid, ownership_epoch,
                       condition_score, enabled, row_version, UTC_TIMESTAMP(3) AS captured_at
                FROM npc_properties WHERE world_uuid=? AND BINARY region_id=? AND owner_uuid=?
                  AND enabled=TRUE""" + (backend == null ? "" : " AND backend_id=?") + " LIMIT 2";
        Object[] values = backend == null ? new Object[]{world, region, owner} : new Object[]{world, region, owner, backend};
        try (PreparedStatement ps = prepare(c, sql, values); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return Optional.empty();
            property = new Property(Uuids.fromBytes(rs.getBytes("property_id")), world, region,
                    rs.getString("market_id"), rs.getString("class_key"), rs.getInt("capacity"),
                    rs.getInt("reference_rent_cents"), rs.getInt("asking_rent_cents"), owner,
                    rs.getInt("ownership_epoch"), rs.getInt("condition_score"), true);
            revision = rs.getLong("row_version");
            captured = at == null ? instant(rs, "captured_at") : at;
            require(property.capacity() >= 1 && property.capacity() <= 4 && property.ownershipEpoch() >= 0
                    && property.conditionScore() >= 0 && property.conditionScore() <= 100
                    && property.referenceRentCents() >= 0 && property.askingRentCents() >= 0
                    && revision >= 0 && captured != null && !rs.next(), "invalid property");
        }
        var upgrades = upgrades(c, property.propertyId());
        Optional<OwnerHousingView> housing = Optional.empty();
        if (services != null) {
            var assessment = services.readInTransaction(c, captured, owner, captured.minus(Duration.ofDays(29)), 7);
            housing = Optional.of(OwnerHousingView.from(property, revision, upgrades,
                    assessment, zone, resetHour));
        }
        return Optional.of(new OwnerDeskData(property, revision, captured, residents(c, property),
                issues(c, property.propertyId()), upgrades,
                statement(c, property, from, through, captured), insurance(c, property, through, captured), housing,
                pendingRent.getAsBoolean() ? pendingRent(c, property) : Optional.empty(),
                linkedEnabled.getAsBoolean() ? linked(c, owner, through) : Optional.empty(), partner(c, owner, through),
                includeBills ? bills(c, property, from, through) : Optional.empty(),
                includeUtilityBill ? utilityBill(c, property.propertyId(), through) : Optional.empty()));
    }

    private static Optional<UtilityBill> utilityBill(Connection c, UUID property, LocalDate day) throws SQLException {
        try (var ps = prepare(c, "SELECT * FROM npc_utility_bills WHERE property_id=? AND economy_day=?", property, day.toString());
             var rs = ps.executeQuery()) {
            boolean present = rs.next();
            require(c.getWarnings() == null && ps.getWarnings() == null && rs.getWarnings() == null, "Utility desk read incomplete");
            if (!present) return Optional.empty();
            UtilityBill bill = UtilityRepository.readBill(rs);
            require(property.equals(bill.propertyId()) && day.equals(bill.day()) && !rs.next(), "Utility desk scope differs");
            require(c.getWarnings() == null && ps.getWarnings() == null && rs.getWarnings() == null, "Utility desk read incomplete");
            return Optional.of(bill);
        }
    }

    private static Optional<OwnerDeskData.Bills> bills(Connection c, Property property, LocalDate from, LocalDate through)
            throws SQLException {
        int paid = 0, unpaid = 0, expired = 0;
        try (PreparedStatement ps = prepare(c, """
                SELECT state,payable_until_day FROM npc_charge_invoices
                WHERE property_id=? AND owner_uuid=? AND ownership_epoch=? AND economy_day BETWEEN ? AND ?
                ORDER BY economy_day LIMIT 8
                """, property.propertyId(), property.ownerUuid(), property.ownershipEpoch(), from.toString(), through.toString());
             ResultSet rows = ps.executeQuery()) {
            while (rows.next()) {
                require(paid + unpaid + expired < 7, "too many invoices in seven-day period");
                switch (rows.getString("state")) {
                    case "PAID" -> paid++;
                    case "EXPIRED" -> expired++;
                    case "ISSUED" -> {
                        if (through.isAfter(LocalDate.parse(rows.getString("payable_until_day")))) expired++;
                        else unpaid++;
                    }
                    default -> throw new SQLException("Invalid bill summary state");
                }
            }
            require(ps.getWarnings() == null, "bill summary incomplete");
        }
        return paid + unpaid + expired == 0 ? Optional.empty()
                : Optional.of(new OwnerDeskData.Bills(paid, unpaid, expired));
    }

    /** The owner's partnership active today, from the same snapshot. No read at all while partnerships are off. */
    private Optional<OwnerDeskData.Partner> partner(Connection c, UUID owner, LocalDate day) throws SQLException {
        PartnerConfig config = partners.get();
        if (config == null || !config.enabled()) return Optional.empty();
        UUID resident;
        String name;
        LocalDate last;
        PartnerPerks stored;
        try (PreparedStatement ps = prepare(c, """
                SELECT p.resident_id, r.name, p.last_active_day, p.fee_discount_pct, p.rank_bonus_points
                FROM npc_partnerships p JOIN npc_resident_profiles r ON r.resident_id=p.resident_id
                WHERE p.active_owner_id=? AND p.last_active_day>=? AND p.started_economy_day<=?
                """, owner, day.toString(), day.toString()); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return Optional.empty();
            resident = Uuids.fromBytes(rs.getBytes("resident_id"));
            name = rs.getString("name");
            last = LocalDate.parse(rs.getString("last_active_day"));
            stored = new PartnerPerks(rs.getInt("fee_discount_pct"), rs.getInt("rank_bonus_points"));
            require(!rs.next(), "two active partnerships");
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw new SQLException("Invalid stored partnership", invalid);
        }
        var perks = PartnerPerks.effective(stored, config.configPerks().apply(resident));
        boolean rank = perks.rankBonusPoints() > 0 && config.rankLive();
        boolean top = false;
        if (rank) try (PreparedStatement ps = prepare(c, """
                SELECT COUNT(*), COALESCE(SUM(CASE WHEN u.level=3 AND u.disabled_incident_id IS NULL THEN 1 ELSE 0 END),0)
                FROM npc_properties p LEFT JOIN npc_upgrades u ON u.property_id=p.property_id AND u.upgrade_type='ADVERTISING'
                WHERE p.owner_uuid=? AND p.enabled=TRUE
                """, owner); ResultSet rs = ps.executeQuery()) {
            rs.next();
            top = rs.getInt(1) > 0 && rs.getInt(2) == rs.getInt(1);
        }
        return Optional.of(new OwnerDeskData.Partner(name, last, perks.feeDiscountPct(), rank, top));
    }

    /** Today's active links in the same snapshot; empty for an owner alone in its group. */
    private Optional<OwnerDeskData.Linked> linked(Connection c, UUID owner, LocalDate day) throws SQLException {
        int accounts = AccountLinkRepository.groupSize(c, owner, day);
        return accounts > 1 ? Optional.of(new OwnerDeskData.Linked(accounts, linkedLimitCents.getAsLong())) : Optional.empty();
    }

    /** Only a PENDING change recorded by this owner incarnation; a sold home never shows the old owner's change. */
    private static Optional<OwnerDeskData.PendingRent> pendingRent(Connection c, Property property) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT r.to_rent_cents, r.effective_day,
                       (SELECT COUNT(*) FROM npc_rent_change_notices n WHERE n.change_id=r.change_id) AS notices
                FROM npc_properties p JOIN npc_rent_changes r ON r.change_id=p.pending_rent_change_id
                WHERE p.property_id=? AND r.property_id=p.property_id AND r.state='PENDING'
                  AND r.owner_uuid=? AND r.ownership_epoch=? AND r.to_rent_cents=p.pending_asking_rent_cents
                  AND r.effective_day=p.pending_rent_effective_day
                """, property.propertyId(), property.ownerUuid(), property.ownershipEpoch()); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return Optional.empty();
            var pending = new OwnerDeskData.PendingRent(rs.getLong(1), LocalDate.parse(rs.getString(2)), rs.getInt(3));
            require(!rs.next(), "duplicate pending rent");
            return Optional.of(pending);
        }
    }

    private OwnerDeskData.Insurance insurance(Connection c, Property p, LocalDate day, Instant at)
            throws SQLException {
        var view = new InsuranceRepository().snapshot(c, p, at, day);
        long premium = prices.insuranceCents(p.capacity());
        if (view.isEmpty()) return new OwnerDeskData.Insurance(premium, null, null, false, true);
        var term = view.get().receipt();
        return new OwnerDeskData.Insurance(premium, term.startDay(), term.endDayExclusive(), view.get().used(),
                p.ownerUuid().equals(term.ownerUuid()) && p.ownershipEpoch() == term.ownershipEpoch(),
                view.get().nextTerm().isPresent());
    }

    private static List<OwnerDeskData.Resident> residents(Connection c, Property property) throws SQLException {
        List<OwnerDeskData.Resident> rows = new ArrayList<>();
        var slots = new HashSet<Integer>();
        try (PreparedStatement ps = prepare(c, """
                SELECT tenant_id, display_name, slot, tier, mood, satisfaction FROM npc_tenants
                WHERE property_id=? ORDER BY slot, tenant_id LIMIT 5
                """, property.propertyId()); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int slot = rs.getInt("slot");
                require(!rs.wasNull() && slot >= 0 && slot < property.capacity() && slots.add(slot)
                        && rows.size() < property.capacity(), "invalid tenant slots");
                rows.add(new OwnerDeskData.Resident(Uuids.fromBytes(rs.getBytes("tenant_id")),
                        rs.getString("display_name"), slot, rs.getString("tier"), rs.getString("mood"),
                        rs.getInt("satisfaction")));
            }
        }
        return rows;
    }

    private static List<OwnerDeskData.Issue> issues(Connection c, UUID property) throws SQLException {
        List<OwnerDeskData.Issue> rows = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, """
                SELECT incident_id, incident_type, cash_repair_cents, due_at FROM npc_incidents
                WHERE property_id=? AND resolved_at IS NULL AND prevented=FALSE
                ORDER BY due_at, incident_id LIMIT ?
                """, property, MAX_ISSUES + 1); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                require(rows.size() < MAX_ISSUES, "too many active issues");
                rows.add(new OwnerDeskData.Issue(rs.getLong("incident_id"), rs.getString("incident_type"),
                        rs.getLong("cash_repair_cents"), instant(rs, "due_at")));
            }
        }
        return rows;
    }

    private static List<OwnerDeskData.Upgrade> upgrades(Connection c, UUID property) throws SQLException {
        List<OwnerDeskData.Upgrade> rows = new ArrayList<>();
        var types = new HashSet<UpgradeType>();
        try (PreparedStatement ps = prepare(c, """
                SELECT upgrade_type, level, disabled_incident_id FROM npc_upgrades
                WHERE property_id=? ORDER BY upgrade_type LIMIT ?
                """, property, UpgradeType.values().length + 1); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                UpgradeType type = UpgradeType.valueOf(rs.getString("upgrade_type"));
                require(types.add(type) && rows.size() < UpgradeType.values().length, "invalid upgrades");
                rows.add(new OwnerDeskData.Upgrade(type.name(), rs.getInt("level"),
                        rs.getObject("disabled_incident_id") != null));
            }
        }
        return rows;
    }

    private static OwnerDeskData.Statement statement(Connection c, Property p, LocalDate from,
                                                      LocalDate through, Instant captured) throws SQLException {
        long gross = 0, expense = 0;
        int pending = 0, review = 0, count = 0;
        try (PreparedStatement ps = prepare(c, """
                SELECT requested_amount_cents, paid_amount_cents, source, direction, economy_day,
                       state, created_at, claimed_at, finalized_at FROM npc_economy_events
                WHERE property_id=? AND counterparty_uuid=? AND ownership_epoch=?
                  AND economy_day>=? AND economy_day<=? ORDER BY event_id LIMIT ? ROWS EXAMINED 8192
                """, p.propertyId(), p.ownerUuid(), p.ownershipEpoch(), from.toString(), through.toString(),
                MAX_EVENTS + 1); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                require(++count <= MAX_EVENTS, "too many statement events");
                String dayText = rs.getString("economy_day");
                LocalDate day = LocalDate.parse(dayText);
                require(day.toString().equals(dayText) && !day.isBefore(from) && !day.isAfter(through),
                        "invalid event day");
                EventSource source = source(rs.getString("source"), rs.getString("direction"));
                EventState state = EventState.valueOf(rs.getString("state"));
                long requested = rs.getLong("requested_amount_cents");
                require(!rs.wasNull() && requested > 0, "invalid requested amount");
                long amount = rs.getLong("paid_amount_cents");
                Long paid = rs.wasNull() ? null : amount;
                Instant created = instant(rs, "created_at"), claimed = instant(rs, "claimed_at");
                Instant finalized = instant(rs, "finalized_at");
                require(created != null && !created.isAfter(captured)
                        && (claimed == null || (!claimed.isBefore(created) && !claimed.isAfter(captured)))
                        && (finalized == null || (claimed != null && !finalized.isBefore(claimed)
                            && !finalized.isAfter(captured))), "invalid event timestamps");
                require((state == EventState.PENDING) == (claimed == null), "invalid event claim");
                switch (state) {
                    case PAID -> {
                        require(finalized != null && paid != null && paid > 0 && paid <= requested,
                                "invalid paid receipt");
                        if (source.isPayout()) gross = Math.addExact(gross, paid);
                        else expense = Math.addExact(expense, paid);
                    }
                    case FAILED -> require(finalized != null && (paid == null || paid == 0), "invalid failure");
                    case PENDING, CLAIMED -> {
                        require(finalized == null && paid == null, "invalid unconfirmed receipt");
                        if (state == EventState.PENDING) pending++;
                        else review++;
                    }
                    case AMBIGUOUS -> {
                        require(paid == null, "invalid ambiguous receipt");
                        review++;
                    }
                }
            }
            // MariaDB may return an incomplete result with a warning at the examined-row budget.
            // A partial statement is unavailable, never a smaller but plausible total.
            require(ps.getWarnings() == null, "statement query incomplete");
        }
        return new OwnerDeskData.Statement(from, through, gross, expense, pending, review);
    }

    private static EventSource source(String key, String direction) throws SQLException {
        for (EventSource source : EventSource.values()) {
            if (source.key().equals(key) && source.direction().moneyFlowKey().equals(direction)) return source;
        }
        throw new SQLException("Invalid statement source/direction");
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... values) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(QUERY_SECONDS);
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof UUID uuid) ps.setBytes(i + 1, Uuids.toBytes(uuid));
                else ps.setObject(i + 1, values[i]);
            }
            return ps;
        } catch (SQLException failure) {
            try {
                ps.close();
            } catch (SQLException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }

    private static void require(boolean valid, String message) throws SQLException {
        if (!valid) throw new SQLException(message);
    }
}
