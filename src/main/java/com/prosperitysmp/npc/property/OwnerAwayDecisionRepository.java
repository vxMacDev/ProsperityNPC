package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Worker-only. Saves the first owner-away decision per property, owner and economy day ({@code npc_owner_away_decisions})
 * and hands back what is stored, so the first decision of the day wins across restarts. A stored row is never changed.
 * Only used while housing.vacation-days is above 0.
 */
public final class OwnerAwayDecisionRepository {
    /** Most decisions one save may carry; matches the in-memory cap so a day always fits. */
    public static final int MAX_ROWS = OwnerAway.MAX_CACHED;
    /** Days of decisions kept; older rows are pruned in bounded batches. */
    public static final int RETAIN_DAYS = 14;
    public static final int MAX_PRUNE = 1000;
    private static final int CHUNK = 200;
    private static final int QUERY_SECONDS = 5;
    private final Database database;

    public OwnerAwayDecisionRepository(Database database) { this.database = Objects.requireNonNull(database); }

    private record Key(UUID property, UUID owner) { }

    /**
     * Inserts each decision that has no row yet for its day, property and owner, then returns the stored decision for
     * every one of them (an earlier row wins over the one offered). Throws if any stored row cannot be read back, so a
     * caller never treats an unsaved decision as saved.
     */
    public List<OwnerAway.Choice> recordFirst(LocalDate day, Collection<OwnerAway.Choice> choices) throws SQLException {
        Objects.requireNonNull(day);
        Objects.requireNonNull(choices);
        if (choices.size() > MAX_ROWS) throw new IllegalArgumentException("Too many owner-away decisions");
        if (choices.isEmpty()) return List.of();
        String today = day.toString();
        var unique = new LinkedHashSet<Key>();
        Map<Key, Boolean> offered = new HashMap<>();
        for (var c : choices) {
            Key k = new Key(c.propertyId(), c.ownerUuid());
            if (unique.add(k)) offered.put(k, c.away());
        }
        var keys = List.copyOf(unique);
        Map<Key, Boolean> stored = new HashMap<>();
        try (Connection c = database.getConnection()) {
            for (int from = 0; from < keys.size(); from += CHUNK) {
                var part = keys.subList(from, Math.min(keys.size(), from + CHUNK));
                var args = new ArrayList<Object>();
                for (Key k : part) {
                    args.add(today);
                    args.add(k.property());
                    args.add(k.owner());
                    args.add(offered.get(k));
                }
                // away=away keeps the stored value: the first decision of the day is never overwritten.
                try (PreparedStatement ps = prepare(c,
                        "INSERT INTO npc_owner_away_decisions (economy_day,property_id,owner_uuid,away) VALUES "
                        + "(?,?,?,?),".repeat(part.size() - 1) + "(?,?,?,?) ON DUPLICATE KEY UPDATE away=away", args.toArray())) {
                    ps.executeUpdate();
                }
                var read = new ArrayList<Object>();
                read.add(today);
                for (Key k : part) read.add(k.property());
                try (PreparedStatement ps = prepare(c, "SELECT property_id,owner_uuid,away FROM npc_owner_away_decisions "
                        + "WHERE economy_day=? AND property_id IN (" + "?,".repeat(part.size() - 1) + "?)", read.toArray());
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Key k = new Key(Uuids.fromBytes(rs.getBytes("property_id")), Uuids.fromBytes(rs.getBytes("owner_uuid")));
                        if (offered.containsKey(k)) stored.put(k, rs.getBoolean("away"));
                    }
                }
            }
        }
        var out = new ArrayList<OwnerAway.Choice>(keys.size());
        for (Key k : keys) {
            Boolean away = stored.get(k);
            if (away == null) throw new SQLException("Owner-away decision not readable after save");
            out.add(new OwnerAway.Choice(k.property(), k.owner(), away));
        }
        return List.copyOf(out);
    }

    /** Deletes at most {@code limit} rows older than {@link #RETAIN_DAYS} before {@code today}. Returns how many. */
    public int prune(LocalDate today, int limit) throws SQLException {
        Objects.requireNonNull(today);
        if (limit < 1 || limit > MAX_PRUNE) throw new IllegalArgumentException("Invalid prune size");
        try (Connection c = database.getConnection();
             PreparedStatement ps = prepare(c, "DELETE FROM npc_owner_away_decisions WHERE economy_day<? ORDER BY economy_day LIMIT ?",
                     today.minusDays(RETAIN_DAYS).toString(), limit)) {
            return ps.executeUpdate();
        }
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
}
