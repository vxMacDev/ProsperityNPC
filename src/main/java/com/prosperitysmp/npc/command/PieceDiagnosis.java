package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.api.Piece;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import com.prosperitysmp.npc.pieces.PieceSelector;

import java.util.ArrayList;
import java.util.List;

/**
 * The text of /npcadmin diagnose. Pure, so the format is unit-tested. It names pieces, plugins, capabilities and
 * the first 8 characters of the provider jar hash; never a balance, a player UUID or a config secret.
 */
final class PieceDiagnosis {
    static final int MAX_LINES = 30;
    /** The most characters of one piece of adapter-supplied text that diagnose prints. */
    static final int MAX_FIELD = 80;

    /**
     * One setting: its key, the id in use since startup, whether it is usable, its one-line detail, the label of the
     * piece in use ("" for none) and the id in the live config ("" when not read). A live id that differs from the
     * one in use adds a "restart required" line.
     */
    record Setting(String key, String id, boolean inUse, String detail, String label, String configured) {
        Setting(String key, String id, boolean inUse, String detail) {
            this(key, id, inUse, detail, "", "");
        }

        String line() {
            String shownLabel = clean(label);
            return key + ": " + id + (inUse ? " [in use] " : " [not available] ") + detail
                    + (shownLabel.isEmpty() ? "" : "; label: " + shownLabel);
        }

        /** "key: configured X; restart required" when the live config names another piece, otherwise null. */
        String restartLine() {
            if (configured == null || configured.isEmpty() || configured.equals(id)) return null;
            return key + ": configured " + clean(configured) + "; restart required";
        }
    }

    private PieceDiagnosis() { }

    /** Adapter-supplied text for one diagnose field: no control or section characters, at most 80 characters. */
    static String clean(String text) {
        return clean(text, MAX_FIELD);
    }

    /** As clean(String), capped at {@code max}: for a line that joins our own words with adapter text. */
    static String clean(String text, int max) {
        if (text == null) return "";
        String shown = text.replaceAll("[\\p{Cntrl}\u00a7]", "");
        return shown.length() <= max ? shown : shown.substring(0, max);
    }

    static List<String> lines(List<Setting> settings, List<? extends PieceSelector.Selection<?>> listings) {
        var out = new ArrayList<String>();
        out.add("ProsperityNPC pieces (API " + ProsperityNpcApi.MAJOR + "." + ProsperityNpcApi.MINOR + ")");
        for (Setting setting : settings) {
            out.add(setting.line());
            String restart = setting.restartLine();
            if (restart != null) out.add(restart);
        }
        for (var listing : listings) {
            if (listing == null) continue;
            for (var unused : listing.unused()) out.add("found, not in use: " + describe(unused));
            for (var refused : listing.refused()) {
                String id = clean(refused.id());
                out.add("refused: " + (id.isEmpty() ? "(no id)" : id) + " (from " + clean(refused.from())
                        + "): " + clean(refused.reason(), 2 * MAX_FIELD));
            }
        }
        if (out.size() > MAX_LINES) {
            int hidden = out.size() - (MAX_LINES - 1);
            out.subList(MAX_LINES - 1, out.size()).clear();
            out.add("... " + hidden + " more line(s) not shown");
        }
        return out;
    }

    private static String describe(PieceSelector.Candidate<?> candidate) {
        if (!(candidate.piece() instanceof Piece piece)) return "(not a piece) (" + clean(candidate.pluginName()) + ")";
        String id, label;
        int major, minor;
        try {
            id = piece.id();
            major = piece.apiMajor();
            minor = piece.apiMinor();
            label = piece.label();
        } catch (RuntimeException | LinkageError broken) {
            return "(unreadable) (" + clean(candidate.pluginName()) + " " + clean(candidate.pluginVersion()) + ")";
        }
        label = clean(label);
        return clean(id) + " (" + clean(candidate.pluginName()) + " " + clean(candidate.pluginVersion()) + ", API " + major
                + "." + minor + (label.isBlank() ? "" : ", " + label) + ")";
    }
}
