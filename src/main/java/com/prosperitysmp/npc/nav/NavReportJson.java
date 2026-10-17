package com.prosperitysmp.npc.nav;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;

/**
 * Minimal JSON writer for the files {@code /npcnav} leaves in {@code nav-audit/}, because an RCON
 * caller only sees "queued" and a bot has to read the result from somewhere. Maps keep their order.
 */
public final class NavReportJson {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private NavReportJson() {
    }

    /** {@code <kind>-<graph>-<yyyyMMddHHmmss>.json}, restricted to safe file-name characters. */
    public static String fileName(String kind, String graph, Instant at) {
        return safe(kind) + "-" + safe(graph) + "-" + STAMP.format(at) + ".json";
    }

    private static String safe(String text) {
        return text.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    public static String toJson(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof Number number) {
            double d = number.doubleValue();
            out.append(Double.isFinite(d) ? number.toString() : "null");
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                string(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Collection<?> items) {
            out.append('[');
            boolean first = true;
            for (Object item : items) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(out, item);
            }
            out.append(']');
        } else if (value instanceof int[] ints) {
            out.append('[');
            for (int i = 0; i < ints.length; i++) {
                out.append(i == 0 ? "" : ",").append(ints[i]);
            }
            out.append(']');
        } else {
            string(out, value.toString());
        }
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
