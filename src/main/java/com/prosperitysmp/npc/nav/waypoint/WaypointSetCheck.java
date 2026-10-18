package com.prosperitysmp.npc.nav.waypoint;

import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.nav.StreetRules;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.EditRefused;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Link;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.Point;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.PointKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rules a waypoint set must keep before it is built. Pure: no world is read. Every problem is reported
 * with its code and the points or link it names, so staff can fix them all in one go.
 */
public final class WaypointSetCheck {

    public enum Severity { ERROR, WARNING }

    public record Issue(Severity severity, String code, String message) {
        public String line() {
            return code + " " + message;
        }
    }

    /** {@code cells} and {@code edges} are the densified node and directed edge counts (0 when not built). */
    public record Report(List<Issue> issues, int points, int links, int cells, int edges) {
        public Report {
            issues = List.copyOf(issues);
        }

        public long errors() {
            return issues.stream().filter(i -> i.severity() == Severity.ERROR).count();
        }

        public long warnings() {
            return issues.stream().filter(i -> i.severity() == Severity.WARNING).count();
        }

        public boolean ok() {
            return errors() == 0;
        }
    }

    private WaypointSetCheck() {
    }

    public static Report check(WaypointSet set, StreetRules rules, int maxStep, WaypointDensifier.Limits limits) {
        List<Issue> issues = new ArrayList<>();
        Map<String, String> pointAt = new HashMap<>();
        for (Point p : set.points()) {
            if (!WaypointSet.NAME.matcher(p.name()).matches()) {
                error(issues, "WP_NAME", "point " + p.name() + ": names are 1 to 32 of a-z, 0-9, _ and -");
            }
            String other = pointAt.putIfAbsent(p.cell(), p.name());
            if (other != null) {
                error(issues, "WP_DUP_CELL", "points " + other + " and " + p.name() + " both stand at " + p.cell());
            }
            for (String tag : p.tags()) {
                if (!NavGraphRepository.isValidDraftTag(tag)) {
                    error(issues, "WP_TAG", "point " + p.name() + " has a tag that is not a route tag");
                }
            }
            if (p.tags().size() > WaypointSet.MAX_TAGS) {
                error(issues, "WP_TAG_CAP", "point " + p.name() + " has more than " + WaypointSet.MAX_TAGS + " tags");
            }
        }
        // Street cells of every crossing, to find crossings that meet, points on a road and walks over one.
        Map<String, String> streetCells = new HashMap<>();
        List<Link> walks = new ArrayList<>();
        for (Link link : set.links()) {
            Point a = set.point(link.a());
            Point b = set.point(link.b());
            if (link.a().equals(link.b())) {
                error(issues, "WP_LINK_SELF", "link " + link.label() + " joins a point to itself");
                continue;
            }
            if (a == null || b == null) {
                error(issues, "WP_LINK_MISSING", "link " + link.label() + " names no point " + (a == null ? link.a() : link.b()));
                continue;
            }
            List<int[]> cells;
            try {
                cells = WaypointDensifier.cells(a, b, limits.maxNodes());
            } catch (EditRefused refused) {
                error(issues, refused.code(), refused.getMessage());
                continue;
            }
            if (cells == null) {
                error(issues, "WP_NOT_AXIS", "link " + link.label() + " does not run straight along x or z; add a point at the bend");
                continue;
            }
            int length = cells.size() - 1;
            long dy = Math.abs((long) a.y() - b.y());
            if (dy > (long) length * maxStep) {
                error(issues, "WP_TOO_STEEP", "link " + link.label() + " climbs " + dy + " over " + length + " blocks");
                continue;
            }
            if (!link.kind().crossing()) {
                walks.add(link);
                continue;
            }
            boolean usable = true;
            if (a.kind() != PointKind.SIDEWALK || b.kind() != PointKind.SIDEWALK) {
                error(issues, "WP_CROSS_ENDS", "crossing " + link.label() + " must start and end on sidewalk points");
                usable = false;
            }
            if (dy != 0) {
                error(issues, "WP_CROSS_LEVEL", "crossing " + link.label() + " is not level");
                usable = false;
            }
            int interior = length - 1;
            if (interior < 1 || interior > rules.maxCrossingLength()) {
                error(issues, "WP_CROSS_LEN", "crossing " + link.label() + " has " + interior + " road blocks; it needs 1 to "
                        + rules.maxCrossingLength());
                usable = false;
            }
            if (!usable) continue;
            for (int i = 1; i < cells.size() - 1; i++) {
                int[] c = cells.get(i);
                String at = WaypointDensifier.key(c[0], c[1], c[2]);
                String onPoint = pointAt.get(at);
                if (onPoint != null) {
                    error(issues, "WP_CROSS_ON_POINT", "point " + onPoint + " stands on crossing " + link.label() + " at " + at);
                }
                String earlier = streetCells.putIfAbsent(at, link.label());
                if (earlier != null) {
                    error(issues, "WP_CROSS_JUNCTION", "crossings " + earlier + " and " + link.label() + " meet at " + at);
                }
            }
        }
        for (Link link : walks) {
            List<int[]> cells = WaypointDensifier.cells(set.point(link.a()), set.point(link.b()), limits.maxNodes());
            for (int i = 1; i < cells.size() - 1; i++) {
                int[] c = cells.get(i);
                String at = WaypointDensifier.key(c[0], c[1], c[2]);
                String crossing = streetCells.get(at);
                if (crossing != null) {
                    error(issues, "WP_WALK_ON_STREET", "walk link " + link.label() + " runs over crossing " + crossing + " at " + at);
                    break;
                }
            }
        }
        int cells = 0;
        int edges = 0;
        if (issues.isEmpty()) {
            try {
                WaypointDensifier.Result built = WaypointDensifier.densify(set, rules, maxStep, limits);
                cells = built.graph().nodeCount();
                edges = built.graph().edgeCount();
            } catch (EditRefused refused) {
                error(issues, refused.code(), refused.getMessage());
            }
        }
        warnings(set, issues);
        return new Report(issues, set.points().size(), set.links().size(), cells, edges);
    }

    private static void warnings(WaypointSet set, List<Issue> issues) {
        Map<String, Set<String>> neighbours = new HashMap<>();
        for (Link link : set.links()) {
            if (set.point(link.a()) == null || set.point(link.b()) == null) continue;
            neighbours.computeIfAbsent(link.a(), k -> new HashSet<>()).add(link.b());
            neighbours.computeIfAbsent(link.b(), k -> new HashSet<>()).add(link.a());
        }
        for (Point p : set.points()) {
            if (!neighbours.containsKey(p.name())) {
                issues.add(new Issue(Severity.WARNING, "WP_ISOLATED", "point " + p.name() + " has no links"));
            }
        }
        Set<String> seen = new HashSet<>();
        int groups = 0;
        for (String start : neighbours.keySet().stream().sorted().toList()) {
            if (!seen.add(start)) continue;
            groups++;
            List<String> queue = new ArrayList<>(List.of(start));
            for (int head = 0; head < queue.size(); head++) {
                for (String next : neighbours.getOrDefault(queue.get(head), Set.of())) {
                    if (seen.add(next)) queue.add(next);
                }
            }
        }
        if (groups > 1) {
            issues.add(new Issue(Severity.WARNING, "WP_DISCONNECTED", groups + " separate groups of linked points"));
        }
        if (set.points().stream().allMatch(p -> p.tags().isEmpty())) {
            issues.add(new Issue(Severity.WARNING, "WP_NO_STOPS", "no point has a tag, so residents have nowhere to go"));
        }
    }

    private static void error(List<Issue> issues, String code, String message) {
        issues.add(new Issue(Severity.ERROR, code, message));
    }
}
