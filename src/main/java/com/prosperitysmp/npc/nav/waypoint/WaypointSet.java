package com.prosperitysmp.npc.nav.waypoint;

import com.prosperitysmp.npc.nav.NavGraphRepository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A region's staff-placed waypoints: named points and the links between them. Pure and immutable: every
 * edit returns a new set or throws {@link EditRefused}. Nothing here touches a world or a database; the
 * set is stored as its canonical text ({@link WaypointCodec}) and built into a graph by
 * {@link WaypointDensifier}.
 */
public final class WaypointSet {

    public static final String FORMAT = "prosperity-nav-waypoints/1";
    public static final int MAX_TAGS = 8;
    public static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    /** What a point stands on. A crossing may only start and end on a sidewalk point. */
    public enum PointKind {
        SIDEWALK, ACCESS;

        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static PointKind parse(String word) {
            for (PointKind kind : values()) {
                if (kind.word().equals(word)) return kind;
            }
            throw new IllegalArgumentException("unknown point kind");
        }
    }

    /** {@code walk} runs along a sidewalk; {@code cross} and {@code crosswalk} go straight over a road. */
    public enum LinkKind {
        WALK, CROSS, CROSSWALK;

        public boolean crossing() {
            return this != WALK;
        }

        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static LinkKind parse(String word) {
            for (LinkKind kind : values()) {
                if (kind.word().equals(word)) return kind;
            }
            throw new IllegalArgumentException("unknown link kind");
        }
    }

    /** A point at the block a resident stands in (support Y + 1). */
    public record Point(String name, int x, int y, int z, PointKind kind, SortedSet<String> tags) {
        public Point {
            Objects.requireNonNull(name);
            Objects.requireNonNull(kind);
            tags = Collections.unmodifiableSortedSet(new TreeSet<>(tags == null ? Set.of() : tags));
        }

        public Point moved(int nx, int ny, int nz) {
            return new Point(name, nx, ny, nz, kind, tags);
        }

        public Point withTags(Collection<String> other) {
            return new Point(name, x, y, z, kind, new TreeSet<>(other));
        }

        String cell() {
            return x + "," + y + "," + z;
        }
    }

    /** An unordered link: {@code a} always sorts before {@code b}. Both ends must be valid point names. */
    public record Link(String a, String b, LinkKind kind) {
        public Link {
            Objects.requireNonNull(a);
            Objects.requireNonNull(b);
            Objects.requireNonNull(kind);
            if (!NAME.matcher(a).matches() || !NAME.matcher(b).matches()) {
                throw new EditRefused("WP_NAME", "Link ends are point names: 1 to 32 of a-z, 0-9, _ and -.");
            }
            if (a.compareTo(b) > 0) {
                String swap = a;
                a = b;
                b = swap;
            }
        }

        public String label() {
            return a + "-" + b;
        }

        String key() {
            return a + "\u0000" + b;
        }
    }

    /** Why an edit was refused: a code from {@link WaypointSetCheck} or a cap, and a message for staff. */
    public static final class EditRefused extends IllegalArgumentException {
        private final String code;

        public EditRefused(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public record Limits(int maxPoints, int maxLinks) {
        public static final Limits DEFAULT = new Limits(600, 1200);
    }

    public static final WaypointSet EMPTY = new WaypointSet(new TreeMap<>(), new TreeMap<>());

    private final SortedMap<String, Point> points;
    private final SortedMap<String, Link> links;

    private WaypointSet(SortedMap<String, Point> points, SortedMap<String, Link> links) {
        this.points = Collections.unmodifiableSortedMap(points);
        this.links = Collections.unmodifiableSortedMap(links);
    }

    /**
     * Builds a set as read from a file. Only link ends are checked here (by {@link Link}); the reader checks names
     * and repeats, and {@link WaypointSetCheck} the geometry.
     */
    public static WaypointSet of(Collection<Point> points, Collection<Link> links) {
        SortedMap<String, Point> p = new TreeMap<>();
        for (Point point : points) p.put(point.name(), point);
        SortedMap<String, Link> l = new TreeMap<>();
        for (Link link : links) l.put(link.key(), link);
        return new WaypointSet(p, l);
    }

    /** Points sorted by name. */
    public List<Point> points() {
        return List.copyOf(points.values());
    }

    /** Links sorted by (a, b). */
    public List<Link> links() {
        return List.copyOf(links.values());
    }

    public Point point(String name) {
        return points.get(name);
    }

    public Link link(String a, String b) {
        return links.get(new Link(a, b, LinkKind.WALK).key());
    }

    public List<Link> linksOf(String name) {
        List<Link> out = new ArrayList<>();
        for (Link link : links.values()) {
            if (link.a().equals(name) || link.b().equals(name)) out.add(link);
        }
        return out;
    }

    public WaypointSet addPoint(Point point, Limits limits) {
        requireName(point.name());
        if (points.containsKey(point.name())) {
            throw new EditRefused("WP_DUP_NAME", "There is already a point named " + point.name() + ".");
        }
        for (Point other : points.values()) {
            if (other.cell().equals(point.cell())) {
                throw new EditRefused("WP_DUP_CELL", "Point " + other.name() + " already stands at " + point.cell() + ".");
            }
        }
        if (points.size() >= limits.maxPoints()) {
            throw new EditRefused("WP_CAP_POINTS", "This region already has " + limits.maxPoints()
                    + " points (nav.waypoints.max-points). Split this district into two regions.");
        }
        checkTags(point.name(), point.tags());
        SortedMap<String, Point> next = new TreeMap<>(points);
        next.put(point.name(), point);
        return new WaypointSet(next, new TreeMap<>(links));
    }

    public WaypointSet movePoint(String name, int x, int y, int z) {
        Point point = require(name);
        for (Point other : points.values()) {
            if (!other.name().equals(name) && other.x() == x && other.y() == y && other.z() == z) {
                throw new EditRefused("WP_DUP_CELL", "Point " + other.name() + " already stands at " + x + "," + y + "," + z + ".");
            }
        }
        SortedMap<String, Point> next = new TreeMap<>(points);
        next.put(name, point.moved(x, y, z));
        return new WaypointSet(next, new TreeMap<>(links));
    }

    /** Removes a point and every link that touches it. */
    public WaypointSet removePoint(String name) {
        require(name);
        SortedMap<String, Point> next = new TreeMap<>(points);
        next.remove(name);
        SortedMap<String, Link> nextLinks = new TreeMap<>();
        for (Map.Entry<String, Link> entry : links.entrySet()) {
            Link link = entry.getValue();
            if (!link.a().equals(name) && !link.b().equals(name)) nextLinks.put(entry.getKey(), link);
        }
        return new WaypointSet(next, nextLinks);
    }

    /** Adds a link, or changes the kind of an existing one. */
    public WaypointSet link(String a, String b, LinkKind kind, Limits limits) {
        if (a.equals(b)) {
            throw new EditRefused("WP_LINK_SELF", "A point cannot be linked to itself (" + a + ").");
        }
        if (!points.containsKey(a) || !points.containsKey(b)) {
            throw new EditRefused("WP_LINK_MISSING", "No point named " + (points.containsKey(a) ? b : a) + ".");
        }
        Link link = new Link(a, b, kind);
        if (!links.containsKey(link.key()) && links.size() >= limits.maxLinks()) {
            throw new EditRefused("WP_CAP_LINKS", "This region already has " + limits.maxLinks()
                    + " links (nav.waypoints.max-links). Split this district into two regions.");
        }
        SortedMap<String, Link> next = new TreeMap<>(links);
        next.put(link.key(), link);
        return new WaypointSet(new TreeMap<>(points), next);
    }

    public WaypointSet unlink(String a, String b) {
        Link key = new Link(a, b, LinkKind.WALK);
        if (!links.containsKey(key.key())) {
            throw new EditRefused("WP_LINK_MISSING", "There is no link " + key.label() + ".");
        }
        SortedMap<String, Link> next = new TreeMap<>(links);
        next.remove(key.key());
        return new WaypointSet(new TreeMap<>(points), next);
    }

    public WaypointSet tag(String name, String tag, boolean remove) {
        Point point = require(name);
        TreeSet<String> tags = new TreeSet<>(point.tags());
        if (remove) {
            tags.remove(tag);
        } else {
            if (!NavGraphRepository.isValidDraftTag(tag)) {
                throw new EditRefused("WP_TAG", "Not a route tag: use home, work, market, leisure, spawn, "
                        + "home/work/market/leisure:<residentUUID>, or work:<occupation>.");
            }
            tags.add(tag);
        }
        checkTags(name, tags);
        SortedMap<String, Point> next = new TreeMap<>(points);
        next.put(name, point.withTags(tags));
        return new WaypointSet(next, new TreeMap<>(links));
    }

    private Point require(String name) {
        Point point = points.get(name);
        if (point == null) throw new EditRefused("WP_LINK_MISSING", "No point named " + name + ".");
        return point;
    }

    private static void requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new EditRefused("WP_NAME", "Point names are 1 to 32 of a-z, 0-9, _ and -.");
        }
    }

    private static void checkTags(String name, Set<String> tags) {
        for (String tag : tags) {
            if (!NavGraphRepository.isValidDraftTag(tag)) {
                throw new EditRefused("WP_TAG", "Point " + name + " has a tag that is not a route tag.");
            }
        }
        if (tags.size() > MAX_TAGS) {
            throw new EditRefused("WP_TAG_CAP", "Point " + name + " would have more than " + MAX_TAGS + " tags.");
        }
    }

    /** Points in (x, y, z) order, the order the densifier works in. */
    static Comparator<Point> byCell() {
        return Comparator.comparingInt(Point::x).thenComparingInt(Point::y).thenComparingInt(Point::z);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WaypointSet set && points.equals(set.points) && links.equals(set.links);
    }

    @Override
    public int hashCode() {
        return Objects.hash(points, links);
    }
}
