package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * SQL only, for linked accounts (linked-accounts.enabled). Saves pairs of accounts that Floodgate says
 * belong to one person, splits them on staff request from the next economy day, and builds the owner groups
 * the rent and appreciation cap uses. Every write here only adds a pair, re-activates a pair or schedules a
 * split for a later day. Nothing here moves money or changes a ledger row. Callers decide whether the flag
 * is on; the rent path passes it to {@link #unitsFor}.
 */
public final class AccountLinkRepository {
    /** More active pairs than this fails the batch closed, like the 10000-row ledger bound. */
    public static final int MAX_ACTIVE_LINKS = 10_000;
    private static final int QUERY_SECONDS = 15;

    public enum Source { FLOODGATE_LOGIN, FLOODGATE_LOOKUP, STAFF }
    /** NEW: first save. SEEN: already saved and active. REJOINED: a split pair was seen linked again and is active at once. */
    public enum Observed { NEW, SEEN, REJOINED }
    public enum SplitOutcome { SAVED, NOT_LINKED, ALREADY_SPLIT }
    /** effectiveDay is the day the split starts (SAVED), or the one already saved (ALREADY_SPLIT); null when not linked. */
    public record Split(SplitOutcome outcome, LocalDate effectiveDay) { }
    /** One saved pair, ordered low < high by unsigned byte order. splitEffectiveDay is null while it was never split. */
    public record Link(UUID low, UUID high, UUID java, UUID bedrock, Source source, LocalDate firstSeenDay,
                       LocalDate splitEffectiveDay) {
        public boolean activeOn(LocalDate day) { return splitEffectiveDay == null || splitEffectiveDay.isAfter(day); }
    }
    /** The account's group for a day plus every saved pair that touches the group, for staff and the desk. */
    public record Group(UUID account, List<UUID> members, List<Link> links) {
        public Group { members = List.copyOf(members); links = List.copyOf(links); }
    }

    private final Database database;

    public AccountLinkRepository(Database database) { this.database = Objects.requireNonNull(database); }

    /** Floodgate's own rule: a Bedrock account's UUID has most significant bits 0. */
    public static boolean isFloodgateId(UUID id) { return id != null && id.getMostSignificantBits() == 0; }

    /**
     * Saves that java and bedrock belong to one person, as seen by Floodgate at login or by the owner lookup.
     * Refuses (IllegalArgumentException) a STAFF source, the same account twice, the reserve account on either
     * side, a bedrock UUID that is not a Floodgate ID and a java UUID that is one. A pair seen again after a
     * staff split is active again at once (stricter) and writes LINK_REJOINED. reserve may be null when no
     * reserve account is configured.
     */
    public Observed observe(UUID java, UUID bedrock, Source source, Instant at, LocalDate day, UUID reserve) throws SQLException {
        Objects.requireNonNull(java); Objects.requireNonNull(bedrock); Objects.requireNonNull(source);
        Objects.requireNonNull(at); Objects.requireNonNull(day);
        if (source == Source.STAFF) throw new IllegalArgumentException("Staff links are not saved through observe");
        if (java.equals(bedrock)) throw new IllegalArgumentException("Java and Bedrock accounts must differ");
        if (reserve != null && (reserve.equals(java) || reserve.equals(bedrock)))
            throw new IllegalArgumentException("The reserve account cannot be linked");
        if (!isFloodgateId(bedrock)) throw new IllegalArgumentException("Bedrock account is not a Floodgate ID");
        if (isFloodgateId(java)) throw new IllegalArgumentException("Java account is a Floodgate ID");
        UUID low = OwnerUnits.BYTE_ORDER.compare(java, bedrock) < 0 ? java : bedrock;
        UUID high = low.equals(java) ? bedrock : java;
        Timestamp stamp = stamp(at);
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                Observed result;
                try (PreparedStatement ps = prepare(c, "SELECT link_id,split_effective_day FROM npc_account_links "
                        + "WHERE account_low=? AND account_high=? FOR UPDATE", low, high); ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) result = null;
                    else result = rs.getObject("split_effective_day") == null ? Observed.SEEN : Observed.REJOINED;
                }
                if (result == null) {
                    try (PreparedStatement ps = prepare(c, "INSERT INTO npc_account_links(account_low,account_high,java_uuid,bedrock_uuid,"
                            + "source,first_seen_at,first_seen_day,last_seen_at) VALUES (?,?,?,?,?,?,?,?)",
                            low, high, java, bedrock, source.name(), stamp, day.toString(), stamp)) {
                        if (ps.executeUpdate() != 1) throw new SQLException("Account link not saved");
                    }
                    audit(c, actor(source), "LINK_SAVED", detail(java, bedrock, source, day));
                    result = Observed.NEW;
                } else if (result == Observed.REJOINED) {
                    try (PreparedStatement ps = prepare(c, "UPDATE npc_account_links SET split_effective_day=NULL,split_at=NULL,"
                            + "split_by=NULL,split_reason=NULL,last_seen_at=GREATEST(last_seen_at,?) WHERE account_low=? AND account_high=?",
                            stamp, low, high)) {
                        if (ps.executeUpdate() != 1) throw new SQLException("Account link not rejoined");
                    }
                    audit(c, actor(source), "LINK_REJOINED", detail(java, bedrock, source, day));
                } else {
                    try (PreparedStatement ps = prepare(c, "UPDATE npc_account_links SET last_seen_at=GREATEST(last_seen_at,?) "
                            + "WHERE account_low=? AND account_high=?", stamp, low, high)) {
                        ps.executeUpdate();
                    }
                }
                c.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    /**
     * Staff split. The pair stays saved, and from the next economy day it no longer joins the two accounts, so
     * today keeps one shared limit. Refused when the pair was never saved or a split is already saved.
     * The start day is decided inside the save, after the pair's row and npc_rent_mutex are held: the day after
     * the clock's day at that moment, and never on or before a day already pinned merged. Pins are only written
     * under the same mutex, so a split that stalls across the daily reset cannot reopen a day that already
     * shared one limit. Lock order is the pair's row, then the mutex; the rent path takes the mutex and no row.
     */
    public Split staffSplit(UUID a, UUID b, String actor, String reason, EconomyClock days) throws SQLException {
        Objects.requireNonNull(a); Objects.requireNonNull(b); Objects.requireNonNull(days);
        if (actor == null || actor.isBlank() || actor.length() > 64) throw new IllegalArgumentException("Invalid split actor");
        if (reason == null || reason.isBlank() || reason.length() > 160) throw new IllegalArgumentException("Invalid split reason");
        if (a.equals(b)) return new Split(SplitOutcome.NOT_LINKED, null);
        UUID low = OwnerUnits.BYTE_ORDER.compare(a, b) < 0 ? a : b, high = low.equals(a) ? b : a;
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                Split result;
                try (PreparedStatement ps = prepare(c, "SELECT split_effective_day FROM npc_account_links "
                        + "WHERE account_low=? AND account_high=? FOR UPDATE", low, high); ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) result = new Split(SplitOutcome.NOT_LINKED, null);
                    else if (rs.getString(1) != null) result = new Split(SplitOutcome.ALREADY_SPLIT, LocalDate.parse(rs.getString(1)));
                    else result = null;
                }
                if (result == null) {
                    try (PreparedStatement ps = prepare(c, "SELECT singleton FROM npc_rent_mutex WHERE singleton=1 FOR UPDATE");
                         ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new SQLException("Rent mutex missing");
                    }
                    LocalDate pinned = null;
                    try (PreparedStatement ps = prepare(c, "SELECT MAX(economy_day) FROM npc_linked_account_days FOR UPDATE");
                         ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getString(1) != null) pinned = LocalDate.parse(rs.getString(1));
                    }
                    Instant at = days.instant();
                    LocalDate effective = days.dayAt(at).plusDays(1);
                    if (pinned != null && !effective.isAfter(pinned)) effective = pinned.plusDays(1);
                    try (PreparedStatement ps = prepare(c, "UPDATE npc_account_links SET split_effective_day=?,split_at=?,split_by=?,split_reason=? "
                            + "WHERE account_low=? AND account_high=? AND split_effective_day IS NULL",
                            effective.toString(), stamp(at), actor, reason, low, high)) {
                        if (ps.executeUpdate() != 1) throw new SQLException("Account split not saved");
                    }
                    audit(c, actor, "LINK_SPLIT", "a=" + low + " b=" + high + " starts=" + effective + " reason=" + reason);
                    result = new Split(SplitOutcome.SAVED, effective);
                }
                c.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    /**
     * Owner groups for the rent or appreciation allocation of day, inside the CALLER's transaction (autocommit
     * off), after it took npc_rent_mutex. flagOn pins day as merged (INSERT IGNORE, never deleted). Then the pin
     * decides: no pin means every owner is its own group, the old behavior, and nothing else is read. A pin
     * means the groups of every pair active on day. So turning the flag off mid-day keeps that day merged, and
     * turning it on merges from the next batch. Appreciation passes the closed day it allocates: with the flag
     * on during D + 1, D's appreciation pins D and is merged even when D's rent was not. That is stricter.
     * More than MAX_ACTIVE_LINKS active pairs throws, and the caller's batch reserves nothing.
     */
    public OwnerUnits unitsFor(Connection c, LocalDate day, boolean flagOn) throws SQLException {
        Objects.requireNonNull(c); Objects.requireNonNull(day);
        if (c.getAutoCommit()) throw new SQLException("Account links require caller-owned transaction");
        if (flagOn) {
            try (PreparedStatement ps = prepare(c, "INSERT IGNORE INTO npc_linked_account_days(economy_day) VALUES (?)", day.toString())) {
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = prepare(c, "SELECT 1 FROM npc_linked_account_days WHERE economy_day=?", day.toString());
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return OwnerUnits.identity();
        }
        return OwnerUnits.of(activeEdges(c, day));
    }

    /**
     * Every pair active on day, bounded: never split, or split from a later day. A split saved after day was
     * pinned always starts after day (staffSplit), so a pinned day's grouping cannot change after the pin.
     */
    static List<OwnerUnits.Edge> activeEdges(Connection c, LocalDate day) throws SQLException {
        var edges = new ArrayList<OwnerUnits.Edge>();
        try (PreparedStatement ps = prepare(c, "SELECT account_low,account_high FROM npc_account_links "
                + "WHERE split_effective_day IS NULL OR split_effective_day > ? ORDER BY link_id LIMIT " + (MAX_ACTIVE_LINKS + 1),
                day.toString()); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (edges.size() >= MAX_ACTIVE_LINKS) throw new SQLException("Account links require bounded maintenance");
                edges.add(new OwnerUnits.Edge(uuid(rs, "account_low"), uuid(rs, "account_high")));
            }
        }
        return edges;
    }

    /** Desk read: how many accounts share account's limit on day (1 with no active links). Caller's connection. */
    public static int groupSize(Connection c, UUID account, LocalDate day) throws SQLException {
        Objects.requireNonNull(account); Objects.requireNonNull(day);
        return OwnerUnits.of(activeEdges(c, day)).members(account).size();
    }

    /** True when account is in a group of two or more on day. Worker only. */
    public boolean inSavedGroup(UUID account, LocalDate day) throws SQLException {
        try (Connection c = database.getConnection()) { return groupSize(c, account, day) > 1; }
    }

    /** The account's group on day, and every saved pair touching it (split ones too). Read only. Worker only. */
    public Group show(UUID account, LocalDate day) throws SQLException {
        Objects.requireNonNull(account); Objects.requireNonNull(day);
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                var members = OwnerUnits.of(activeEdges(c, day)).members(account);
                var touching = new LinkedHashSet<>(members);
                var links = new ArrayList<Link>();
                for (UUID member : List.copyOf(touching)) {
                    try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_account_links WHERE account_low=? OR account_high=? "
                            + "ORDER BY link_id LIMIT 64", member, member); ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            var link = link(rs);
                            if (links.stream().noneMatch(seen -> seen.low().equals(link.low()) && seen.high().equals(link.high())))
                                links.add(link);
                        }
                    }
                }
                return new Group(account, members, links);
            } finally {
                c.rollback();
            }
        }
    }

    private static Link link(ResultSet rs) throws SQLException {
        byte[] java = rs.getBytes("java_uuid"), bedrock = rs.getBytes("bedrock_uuid");
        String split = rs.getString("split_effective_day");
        return new Link(uuid(rs, "account_low"), uuid(rs, "account_high"), java == null ? null : Uuids.fromBytes(java),
                bedrock == null ? null : Uuids.fromBytes(bedrock), Source.valueOf(rs.getString("source")),
                LocalDate.parse(rs.getString("first_seen_day")), split == null ? null : LocalDate.parse(split));
    }

    private static String actor(Source source) { return source == Source.FLOODGATE_LOOKUP ? "lookup" : "floodgate"; }
    private static String detail(UUID java, UUID bedrock, Source source, LocalDate day) {
        return "java=" + java + " bedrock=" + bedrock + " source=" + source.name() + " day=" + day;
    }
    private static void audit(Connection c, String actor, String action, String detail) throws SQLException {
        try (PreparedStatement ps = prepare(c, "INSERT INTO npc_audit_log(actor,action,detail) VALUES (?,?,?)", actor, action, detail)) {
            if (ps.executeUpdate() != 1) throw new SQLException("Account link audit not saved");
        }
    }
    private static Timestamp stamp(Instant at) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(at, ZoneOffset.UTC));
    }
    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        byte[] value = rs.getBytes(column);
        if (value == null || value.length != 16) throw new SQLException("Missing account link identity");
        return Uuids.fromBytes(value);
    }
    private static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(QUERY_SECONDS);
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
                else ps.setObject(i + 1, args[i]);
            }
            return ps;
        } catch (SQLException | RuntimeException failure) {
            ps.close();
            throw failure;
        }
    }
}
