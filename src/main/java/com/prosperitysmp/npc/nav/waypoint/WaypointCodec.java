package com.prosperitysmp.npc.nav.waypoint;

import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.nav.StrictYaml;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.EditRefused;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Link;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.LinkKind;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Point;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.PointKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The text form of a waypoint set ({@code format: prosperity-nav-waypoints/1}). The canonical text is what
 * is stored and hashed: points sorted by name, links by (a, b), one per line, so the same set always has
 * the same sha whatever order it was edited in. The reader accepts any file in the same format.
 */
public final class WaypointCodec {

    private static final Set<String> TOP = Set.of("format", "points", "links");
    private static final Set<String> POINT_KEYS = Set.of("name", "at", "kind", "tags");
    private static final Set<String> LINK_KEYS = Set.of("a", "b", "kind");
    private static final Pattern PLAIN = Pattern.compile("[a-z][a-z0-9_-]*");
    private static final Set<String> RESERVED = Set.of("y", "n", "yes", "no", "on", "off", "true", "false", "null");

    private WaypointCodec() {
    }

    public static String canonical(WaypointSet set) {
        StringBuilder out = new StringBuilder("format: ").append(WaypointSet.FORMAT).append('\n');
        if (set.points().isEmpty()) {
            out.append("points: []\n");
        } else {
            out.append("points:\n");
            for (Point p : set.points()) {
                out.append("  - {name: ").append(scalar(p.name())).append(", at: [").append(p.x()).append(", ")
                        .append(p.y()).append(", ").append(p.z()).append("], kind: ").append(p.kind().word());
                if (!p.tags().isEmpty()) {
                    out.append(", tags: [");
                    boolean first = true;
                    for (String tag : p.tags()) {
                        out.append(first ? "" : ", ").append(scalar(tag));
                        first = false;
                    }
                    out.append(']');
                }
                out.append("}\n");
            }
        }
        if (set.links().isEmpty()) {
            out.append("links: []\n");
        } else {
            out.append("links:\n");
            for (Link l : set.links()) {
                out.append("  - {a: ").append(scalar(l.a())).append(", b: ").append(scalar(l.b()))
                        .append(", kind: ").append(l.kind().word()).append("}\n");
            }
        }
        return out.toString();
    }

    public static String sha(WaypointSet set) {
        return NavGraphRepository.documentSha(canonical(set));
    }

    /**
     * The canonical text of a set that is about to be stored, after reading it back: a set whose text would not
     * read back as the same set is refused, so a stored set can always be read, edited and replaced again.
     */
    public static String storable(WaypointSet set) {
        String text = canonical(set);
        WaypointSet back;
        try {
            back = parse(text);
        } catch (EditRefused refused) {
            throw new EditRefused("WP_FORMAT", "The set would not read back after saving (" + refused.code()
                    + "); nothing was saved.");
        }
        if (!back.equals(set)) {
            throw new EditRefused("WP_FORMAT", "The set would not read back the same after saving; nothing was saved.");
        }
        return text;
    }

    /** A name or tag needs quotes when YAML would read it as anything but that string. */
    private static String scalar(String text) {
        return PLAIN.matcher(text).matches() && !RESERVED.contains(text) ? text : "\"" + text + "\"";
    }

    /**
     * Reads a waypoint file. Structure errors, bad names, repeated names and repeated links are refused here
     * with their {@link WaypointSetCheck} code; the geometry rules are left to the check.
     */
    public static WaypointSet parse(String text) {
        Object document;
        try {
            document = StrictYaml.load(text);
        } catch (StrictYaml.Refused refused) {
            throw new EditRefused("WP_FORMAT", refused.getMessage());
        }
        Map<?, ?> top = map(document, "the file", TOP);
        if (!WaypointSet.FORMAT.equals(top.get("format"))) {
            throw new EditRefused("WP_FORMAT", "The file must start with format: " + WaypointSet.FORMAT);
        }
        List<Point> points = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int index = 0;
        for (Object raw : list(top.get("points"), "points")) {
            String where = "point " + index++;
            Map<?, ?> entry = map(raw, where, POINT_KEYS);
            String name = string(entry.get("name"), where + " name");
            if (!WaypointSet.NAME.matcher(name).matches()) {
                throw new EditRefused("WP_NAME", where + ": names are 1 to 32 of a-z, 0-9, _ and -.");
            }
            if (!names.add(name)) throw new EditRefused("WP_DUP_NAME", "Point " + name + " appears twice.");
            List<?> at = list(entry.get("at"), name + " at");
            if (at.size() != 3) throw new EditRefused("WP_FORMAT", "Point " + name + ": at must be [x, y, z].");
            PointKind kind = PointKind.SIDEWALK;
            if (entry.containsKey("kind")) {
                try {
                    kind = PointKind.parse(string(entry.get("kind"), name + " kind"));
                } catch (IllegalArgumentException bad) {
                    throw new EditRefused("WP_FORMAT", "Point " + name + ": kind is sidewalk or access.");
                }
            }
            TreeSet<String> tags = new TreeSet<>();
            if (entry.containsKey("tags")) {
                for (Object tag : list(entry.get("tags"), name + " tags")) {
                    String value = string(tag, name + " tag");
                    if (!NavGraphRepository.isValidDraftTag(value)) {
                        throw new EditRefused("WP_TAG", "Point " + name + " has a tag that is not a route tag.");
                    }
                    tags.add(value);
                }
                if (tags.size() > WaypointSet.MAX_TAGS) {
                    throw new EditRefused("WP_TAG_CAP", "Point " + name + " has more than " + WaypointSet.MAX_TAGS + " tags.");
                }
            }
            points.add(new Point(name, integer(at.get(0), name), integer(at.get(1), name), integer(at.get(2), name), kind, tags));
        }
        List<Link> links = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        index = 0;
        for (Object raw : list(top.get("links"), "links")) {
            String where = "link " + index++;
            Map<?, ?> entry = map(raw, where, LINK_KEYS);
            String a = string(entry.get("a"), where + " a");
            String b = string(entry.get("b"), where + " b");
            // Checked before anything quotes them: a name outside NAME would make canonical text that no longer reads back.
            if (!WaypointSet.NAME.matcher(a).matches() || !WaypointSet.NAME.matcher(b).matches()) {
                throw new EditRefused("WP_NAME", where + ": link ends are point names, 1 to 32 of a-z, 0-9, _ and -.");
            }
            if (a.equals(b)) throw new EditRefused("WP_LINK_SELF", where + " links " + a + " to itself.");
            if (!names.contains(a) || !names.contains(b)) {
                throw new EditRefused("WP_LINK_MISSING", where + " names no point " + (names.contains(a) ? b : a) + ".");
            }
            LinkKind kind = LinkKind.WALK;
            if (entry.containsKey("kind")) {
                try {
                    kind = LinkKind.parse(string(entry.get("kind"), where + " kind"));
                } catch (IllegalArgumentException bad) {
                    throw new EditRefused("WP_FORMAT", where + ": kind is walk, cross or crosswalk.");
                }
            }
            Link link = new Link(a, b, kind);
            if (!seen.add(link.key())) throw new EditRefused("WP_LINK_DUP", "Link " + link.label() + " appears twice.");
            links.add(link);
        }
        return WaypointSet.of(points, links);
    }

    private static Map<?, ?> map(Object raw, String where, Set<String> allowed) {
        if (!(raw instanceof Map<?, ?> map)) throw new EditRefused("WP_FORMAT", where + " must be a map.");
        for (Object key : map.keySet()) {
            if (!(key instanceof String text) || !allowed.contains(text)) {
                throw new EditRefused("WP_FORMAT", where + " has an unknown key.");
            }
        }
        return map;
    }

    private static List<?> list(Object raw, String where) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw new EditRefused("WP_FORMAT", where + " must be a list.");
        return list;
    }

    private static String string(Object raw, String where) {
        if (raw instanceof String text) return text;
        throw new EditRefused("WP_FORMAT", where + " must be text.");
    }

    private static int integer(Object raw, String where) {
        if (raw instanceof Integer value) return value;
        throw new EditRefused("WP_FORMAT", where + ": coordinates must be whole numbers.");
    }
}
