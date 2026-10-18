package com.prosperitysmp.npc.pieces;

import com.prosperitysmp.npc.api.MoneySource;
import com.prosperitysmp.npc.api.Piece;
import com.prosperitysmp.npc.api.ProsperityNpcApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Picks the one piece a config id names. Pure: no Bukkit calls, so every rule is unit-tested.
 *
 * <p>Rules: built-in ids are reserved and a third-party piece using one is refused. A third-party piece with a bad
 * id, an API major other than the core's, or an API minor above the core's is refused with the numbers. A money
 * source whose scale is not "2", or that cannot be hashed, is refused. Two third-party pieces with the configured id
 * select nothing. A configured id nobody has selects nothing ("not found"). Registration priority is never used and
 * there is no fallback to a built-in piece.
 */
public final class PieceSelector<T extends Piece> {
    /** Every id the plugin uses or will use for its own pieces. A third-party piece may not take one. */
    public static final Set<String> RESERVED = Set.of("vault", "arm", "worldguard-listed", "skript", "internal", "flat",
            "derived", "scan", "waypoints", "file");
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{1,31}");

    /** One third-party registration. The priority is carried only so tests can prove it is ignored. */
    public record Candidate<T>(T piece, String pluginName, String pluginVersion, int priority) {
        public Candidate {
            Objects.requireNonNull(piece);
            Objects.requireNonNull(pluginName);
            Objects.requireNonNull(pluginVersion);
        }
        String from() { return pluginName + " " + pluginVersion; }
    }

    /** A third-party piece that cannot be used, and why. */
    public record Refused(String id, String from, String reason) { }

    /**
     * The outcome. {@code selected} is null when nothing is selected, and {@code reason} then says why.
     * {@code unused} lists acceptable third-party pieces that the config does not name.
     */
    public record Selection<T>(T selected, String configuredId, boolean builtIn, String reason, String owner, String from,
                               List<Candidate<T>> unused, List<Refused> refused) {
        public Selection {
            unused = List.copyOf(unused);
            refused = List.copyOf(refused);
        }
        public boolean found() { return selected != null; }
    }

    private final Class<T> type;

    public PieceSelector(Class<T> type) {
        this.type = Objects.requireNonNull(type);
    }

    /**
     * @param configuredId the id from the config, already trimmed and lowercased by the caller
     * @param builtIns the plugin's own pieces by id; a configured built-in id is selected from here only
     * @param registrations third-party pieces, in any order
     */
    public Selection<T> select(String configuredId, Map<String, ? extends T> builtIns, List<Candidate<T>> registrations) {
        Objects.requireNonNull(configuredId);
        var accepted = new ArrayList<Candidate<T>>();
        var refused = new ArrayList<Refused>();
        for (Candidate<T> candidate : registrations) {
            String reason = refusal(candidate.piece());
            if (reason == null) accepted.add(candidate);
            else refused.add(new Refused(safeId(candidate.piece()), candidate.from(), reason));
        }
        T builtIn = builtIns.get(configuredId);
        if (builtIn != null || RESERVED.contains(configuredId)) {
            var unused = accepted;
            if (builtIn == null)
                return new Selection<>(null, configuredId, true, "built-in " + configuredId + " is not available in this build",
                        "", "", unused, refused);
            return new Selection<>(builtIn, configuredId, true, "", "ProsperityNPC", "ProsperityNPC", unused, refused);
        }
        var named = new ArrayList<Candidate<T>>();
        for (Candidate<T> candidate : registrations)
            if (configuredId.equals(safeId(candidate.piece()))) named.add(candidate);
        var unused = new ArrayList<Candidate<T>>();
        for (Candidate<T> candidate : accepted) if (!named.contains(candidate)) unused.add(candidate);
        if (named.size() > 1)
            return new Selection<>(null, configuredId, false, "duplicate id " + configuredId + " from "
                    + named.get(0).from() + " and " + named.get(1).from(), "", "", unused, refused);
        if (named.isEmpty()) return new Selection<>(null, configuredId, false, "not found", "", "", unused, refused);
        Candidate<T> only = named.getFirst();
        if (!accepted.contains(only)) {
            String reason = refused.stream().filter(r -> r.id().equals(configuredId)).map(Refused::reason).findFirst()
                    .orElse("refused");
            return new Selection<>(null, configuredId, false, reason, only.pluginName(), only.from(), unused, refused);
        }
        return new Selection<>(only.piece(), configuredId, false, "", only.pluginName(), only.from(), unused, refused);
    }

    /** Why a third-party piece cannot be used at all, or null when it can. */
    String refusal(T piece) {
        String id = safeId(piece);
        if (id.isEmpty() || !ID.matcher(id).matches()) return "invalid id '" + id + "'";
        if (RESERVED.contains(id)) return "reserved id";
        int major, minor;
        try {
            major = piece.apiMajor();
            minor = piece.apiMinor();
        } catch (RuntimeException | LinkageError broken) {
            return "API version unreadable";
        }
        if (major != ProsperityNpcApi.MAJOR || minor > ProsperityNpcApi.MINOR)
            return "API " + major + "." + minor + ", ProsperityNPC supports " + ProsperityNpcApi.MAJOR + "."
                    + ProsperityNpcApi.MINOR;
        if (MoneySource.class.isAssignableFrom(type)) {
            String scale;
            boolean hashable;
            try {
                scale = piece.capabilities().value("scale");
                hashable = piece.capabilities().flag("artifact-hashable");
            } catch (RuntimeException | LinkageError broken) {
                return "capabilities unreadable";
            }
            if (!"2".equals(scale)) return "scale '" + scale + "' (only 2 decimal places are supported)";
            if (!hashable) return "artifact-hashable is required";
        }
        return null;
    }

    private static String safeId(Piece piece) {
        try {
            String id = piece.id();
            return id == null ? "" : id;
        } catch (RuntimeException | LinkageError broken) {
            return "";
        }
    }
}
