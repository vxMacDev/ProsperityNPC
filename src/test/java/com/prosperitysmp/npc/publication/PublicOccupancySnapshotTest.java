package com.prosperitysmp.npc.publication;

import com.google.gson.JsonParser;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Row;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PublicOccupancySnapshotTest {
    private static final Instant CAPTURED = Instant.parse("2026-09-08T06:00:00.123Z");
    private static final UUID WORLD = UUID.fromString("d2600000-0000-4000-8000-000000000001");
    private static final UUID OWNER = UUID.fromString("d2600000-0000-4000-8000-000000000002");

    private static Property property(int id, String region, int capacity, boolean enabled) {
        return property(id, WORLD, region, capacity, enabled);
    }

    private static Property property(int id, UUID world, String region, int capacity, boolean enabled) {
        return new Property(new UUID(0, id), world, region, "PRIVATE_MARKET", "PRIVATE_CLASS",
                capacity, 918273, 827364, OWNER, 73, 42, enabled);
    }

    private static Approval approval(Property p, String world) {
        return new Approval(p.propertyId(), p.worldUuid(), p.regionId().toLowerCase(java.util.Locale.ROOT), world);
    }

    @Test void exactWhitelistAllStatesAndNoPrivateSentinels() {
        var rows = List.of(new Row(property(1, "C001", 3, true), 0),
                new Row(property(2, "c002", 3, true), 2), new Row(property(3, "c003", 3, true), 3));
        String json = PublicOccupancySnapshot.build(CAPTURED, rows,
                rows.stream().map(r -> approval(r.property(), "main")).toList()).json();
        var root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(Set.of("version", "capturedAt", "properties"), root.keySet());
        assertEquals("npc-public-occupancy-v1", root.get("version").getAsString());
        assertEquals(CAPTURED.toString(), root.get("capturedAt").getAsString());
        var exported = root.getAsJsonArray("properties");
        assertEquals(3, exported.size());
        for (int i = 0; i < 3; i++) {
            var entry = exported.get(i).getAsJsonObject();
            assertEquals(Set.of("world", "region", "occupancy"), entry.keySet());
            assertEquals("main", entry.get("world").getAsString());
            assertEquals("c00" + (i + 1), entry.get("region").getAsString());
            assertEquals(List.of("EMPTY", "PARTIAL", "FULL").get(i), entry.get("occupancy").getAsString());
        }
        for (String secret : List.of(WORLD.toString(), OWNER.toString(), "PRIVATE_MARKET", "PRIVATE_CLASS", "918273", "827364"))
            assertFalse(json.contains(secret));
        for (Row row : rows) assertFalse(json.contains(row.property().propertyId().toString()));
    }

    @Test void disabledAndUnapprovedRowsAreExcluded() {
        var disabled = property(1, "c001", 1, false);
        var privateRow = property(2, "c002", 1, true);
        var snapshot = PublicOccupancySnapshot.build(CAPTURED,
                List.of(new Row(disabled, 0), new Row(privateRow, 1)), List.of(approval(disabled, "main")));
        assertEquals(0, JsonParser.parseString(snapshot.json()).getAsJsonObject().getAsJsonArray("properties").size());
    }

    @Test void emptyHasNoInventedCounts() {
        assertEquals("{\"version\":\"npc-public-occupancy-v1\",\"capturedAt\":\"2026-09-08T06:00:00.123Z\",\"properties\":[]}",
                PublicOccupancySnapshot.build(CAPTURED, List.of(), List.of()).json());
    }

    @Test void malformedCountsAndRequiredMetadataReject() {
        assertThrows(IllegalArgumentException.class, () -> new Row(null, 0));
        for (int capacity : new int[]{0, -1, 5, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new Row(property(1, "c1", capacity, true), 0));
        assertDoesNotThrow(() -> new Row(property(1, "c1", 4, true), 4));
        for (int occupied : new int[]{-1, 2, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new Row(property(1, "c1", 1, true), occupied));
        assertThrows(IllegalArgumentException.class, () -> new Row(property(1, "", 1, true), 0));
        assertThrows(IllegalArgumentException.class, () -> new Row(new Property(null, WORLD, "c1", null, null,
                1, 0, 0, null, 0, 0, true), 0));
        assertThrows(IllegalArgumentException.class, () -> new Row(new Property(new UUID(0, 1), null, "c1", null, null,
                1, 0, 0, null, 0, 0, true), 0));
    }

    @Test void approvalRequiresCanonicalRegionAndPublicSlug() {
        var p = property(1, "c1", 1, true);
        for (String region : new String[]{"C1", "", " c1", "c/1"})
            assertThrows(IllegalArgumentException.class, () -> new Approval(p.propertyId(), WORLD, region, "main"));
        for (String slug : new String[]{"", "Main", "_main", "main.world", "main/world", "a".repeat(65)})
            assertThrows(IllegalArgumentException.class, () -> new Approval(p.propertyId(), WORLD, "c1", slug));
        assertDoesNotThrow(() -> new Approval(p.propertyId(), WORLD, "c1", "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new Approval(null, WORLD, "c1", "main"));
        assertThrows(IllegalArgumentException.class, () -> new Approval(p.propertyId(), null, "c1", "main"));
    }

    @Test void repositoryRegionBoundAllowsLeadingUnderscore() {
        for (String region : List.of("_estate", "_" + "a".repeat(127))) {
            var p = property(1, region, 4, true);
            assertDoesNotThrow(() -> PublicOccupancySnapshot.build(CAPTURED,
                    List.of(new Row(p, 4)), List.of(approval(p, "main"))));
        }
        assertThrows(IllegalArgumentException.class, () -> new Row(property(1, "a".repeat(129), 4, true), 0));
        assertThrows(IllegalArgumentException.class, () -> new Approval(new UUID(0, 1), WORLD,
                "a".repeat(129), "main"));
    }

    @Test void sourceRegionMustBeAsciiBeforeCaseNormalization() {
        for (String region : List.of("\u212A1", "c\u0130", "caf\u00e9")) {
            assertThrows(IllegalArgumentException.class, () -> new Row(property(1, region, 1, true), 0));
        }
        assertDoesNotThrow(() -> new Row(property(1, "K1", 1, true), 0));
    }

    @Test void duplicatePhysicalScopeRejectsEvenDifferentUnapprovedIds() {
        var rows = List.of(new Row(property(1, "C1", 1, true), 0),
                new Row(property(2, "c1", 1, false), 0));
        assertThrows(IllegalArgumentException.class, () -> PublicOccupancySnapshot.build(CAPTURED, rows, List.of()));
    }

    @Test void distinctWorldsCannotSharePublicAliasEvenWithDistinctRegions() {
        var first = property(1, WORLD, "c1", 1, true);
        var second = property(2, OWNER, "c2", 1, true);
        assertThrows(IllegalArgumentException.class, () -> PublicOccupancySnapshot.build(CAPTURED,
                List.of(new Row(first, 0), new Row(second, 0)),
                List.of(approval(first, "main"), approval(second, "main"))));
    }

    @Test void oneWorldCannotAcquireTwoAliasesEvenWithDistinctRegions() {
        var first = property(1, "c1", 1, true);
        var second = property(2, "c2", 1, true);
        assertThrows(IllegalArgumentException.class, () -> PublicOccupancySnapshot.build(CAPTURED,
                List.of(new Row(first, 0), new Row(second, 0)),
                List.of(approval(first, "main"), approval(second, "other"))));
    }

    @Test void duplicatesRejectIncludingUnapprovedRowsAndDisabledPublicCollisions() {
        var p = property(1, "c1", 1, false);
        var row = new Row(p, 0);
        var a = approval(p, "main");
        assertThrows(IllegalArgumentException.class, () -> PublicOccupancySnapshot.build(CAPTURED, List.of(row, row), List.of()));
        assertThrows(IllegalArgumentException.class, () -> PublicOccupancySnapshot.build(CAPTURED, List.of(row), List.of(a, a)));
        var other = property(2, OWNER, "c1", 1, false);
        assertThrows(IllegalArgumentException.class, () -> PublicOccupancySnapshot.build(CAPTURED,
                List.of(row, new Row(other, 0)), List.of(a, approval(other, "main"))));
    }

    @Test void absentAndMismatchedBindingsRejectWholeBuild() {
        var p = property(1, "c1", 1, true);
        var good = approval(p, "main");
        for (Approval bad : List.of(new Approval(new UUID(0, 2), WORLD, "c2", "main"),
                new Approval(p.propertyId(), OWNER, "c1", "main"),
                new Approval(p.propertyId(), WORLD, "c2", "main"))) {
            assertThrows(IllegalArgumentException.class, () -> PublicOccupancySnapshot.build(CAPTURED,
                    List.of(new Row(p, 0)), List.of(bad)));
        }
        assertThrows(IllegalArgumentException.class, () -> PublicOccupancySnapshot.build(CAPTURED,
                List.of(new Row(p, 0)), List.of(good, new Approval(new UUID(0, 2), WORLD, "c2", "main"))));
    }

    @Test void exactBoundAcceptedAndOverflowNeverTruncated() {
        var rows = new ArrayList<Row>();
        var approvals = new ArrayList<Approval>();
        for (int i = 1; i <= 512; i++) {
            var p = property(i, "c" + i, 1, true);
            rows.add(new Row(p, 0)); approvals.add(approval(p, "main"));
        }
        assertEquals(512, JsonParser.parseString(PublicOccupancySnapshot.build(CAPTURED, rows, approvals).json())
                .getAsJsonObject().getAsJsonArray("properties").size());
        var extra = property(513, "c513", 1, true);
        rows.add(new Row(extra, 0));
        assertThrows(IllegalArgumentException.class, () -> PublicOccupancySnapshot.build(CAPTURED, rows, List.of()));
        rows.removeLast(); approvals.add(approval(extra, "main"));
        assertThrows(IllegalArgumentException.class, () -> PublicOccupancySnapshot.build(CAPTURED, rows, approvals));
    }

    @Test void stableWorldRegionSortAndDefensiveInputs() {
        var rows = new ArrayList<>(List.of(new Row(property(1, OWNER, "z", 1, true), 0),
                new Row(property(2, "b", 1, true), 0), new Row(property(3, "a", 1, true), 0)));
        var approvals = new ArrayList<>(List.of(approval(rows.get(0).property(), "alpha"),
                approval(rows.get(1).property(), "beta"), approval(rows.get(2).property(), "beta")));
        var snapshot = PublicOccupancySnapshot.build(CAPTURED, rows, approvals);
        String before = snapshot.json();
        Collections.reverse(rows); Collections.reverse(approvals);
        assertEquals(before, PublicOccupancySnapshot.build(CAPTURED, rows, approvals).json());
        var entries = JsonParser.parseString(before).getAsJsonObject().getAsJsonArray("properties");
        assertEquals("z", entries.get(0).getAsJsonObject().get("region").getAsString());
        assertEquals("a", entries.get(1).getAsJsonObject().get("region").getAsString());
        assertEquals("b", entries.get(2).getAsJsonObject().get("region").getAsString());
        rows.clear(); approvals.clear(); entries.remove(0);
        assertEquals(before, snapshot.json());
    }

    @Test void timestampsAndNullInputsAreBoundedWithoutFreshnessClaims() {
        for (Instant timestamp : List.of(Instant.MIN, Instant.MAX, Instant.parse("0000-12-31T23:59:59Z"),
                Instant.parse("+10000-01-01T00:00:00Z"), CAPTURED.plusNanos(1)))
            assertThrows(IllegalArgumentException.class, () -> PublicOccupancySnapshot.build(timestamp, List.of(), List.of()));
        for (Instant timestamp : List.of(Instant.parse("0001-01-01T00:00:00Z"),
                Instant.parse("9999-12-31T23:59:59.999Z")))
            assertDoesNotThrow(() -> PublicOccupancySnapshot.build(timestamp, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> PublicOccupancySnapshot.build(null, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> PublicOccupancySnapshot.build(CAPTURED, null, List.of()));
        assertThrows(IllegalArgumentException.class, () -> PublicOccupancySnapshot.build(CAPTURED, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> PublicOccupancySnapshot.build(CAPTURED, Collections.singletonList(null), List.of()));
        assertThrows(IllegalArgumentException.class, () -> PublicOccupancySnapshot.build(CAPTURED, List.of(), Collections.singletonList(null)));
    }
}
