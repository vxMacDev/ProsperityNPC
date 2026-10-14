package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Wording and server-wide rate limit for the premium relocation broadcast. Pure logic, main thread only.
 * Names no owners, regions or money. A move inside the quiet window is logged by the caller, not broadcast.
 */
public final class PremiumMoveBroadcast {
    public static final long WINDOW_MILLIS = 30L * 60_000L;
    private long lastMillis;
    private boolean any;

    /** Returns the line to broadcast, or empty when rate limited or the inputs are unusable. */
    public Optional<String> offer(String residentName, String marketId, long nowMillis) {
        String line = line(residentName, marketId);
        if (line == null) return Optional.empty();
        if (any && nowMillis - lastMillis < WINDOW_MILLIS && nowMillis >= lastMillis) return Optional.empty();
        any = true; lastMillis = nowMillis;
        return Optional.of(line);
    }

    /**
     * Committed premium relocations only, as {name, market} pairs. The resident name is read after the commit.
     * Worker thread. A failed lookup skips that row; the move is committed and the broadcast is cosmetic.
     */
    public static List<String[]> relocations(Database database, List<HousingMoveRepository.Result> results) {
        var out = new ArrayList<String[]>();
        for (var r : results) {
            var q = r.receipt().request();
            if (!r.newlyApplied() || q.origin() == null || q.tier() != HousingPolicy.Tier.PREMIUM) continue;
            try (var c = database.getConnection();
                 var ps = c.prepareStatement("SELECT display_name FROM npc_tenants WHERE tenant_id=?")) {
                ps.setBytes(1, Uuids.toBytes(q.residentId()));
                try (var rs = ps.executeQuery()) { if (rs.next()) out.add(new String[] {rs.getString(1), q.marketId()}); }
            } catch (Exception ignored) { /* cosmetic */ }
        }
        return out;
    }

    public static String line(String residentName, String marketId) {
        String name = clean(residentName), market = clean(marketId == null ? null : marketId.replace('_', ' ').replace('-', ' '));
        if (name.isEmpty() || market.isEmpty()) return null;
        return name + " moved to a better-run home in " + market.toLowerCase(Locale.ROOT) + ".";
    }

    private static String clean(String s) {
        if (s == null) return "";
        var b = new StringBuilder();
        for (char ch : s.toCharArray()) if (ch >= 32 && ch != 127 && ch != '§') b.append(ch);
        String t = b.toString().trim();
        return t.length() > 40 ? t.substring(0, 40).trim() : t;
    }
}
