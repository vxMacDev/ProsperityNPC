package com.prosperitysmp.npc.publication;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.prosperitysmp.npc.property.Property;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Pure operator-whitelisted projection, not privacy approval or proof of freshness. */
public final class PublicOccupancySnapshot {
    private static final int MAX_INPUTS = 512;
    private static final Pattern REGION = Pattern.compile("[a-z0-9_-]{1,128}");
    private static final Pattern SOURCE_REGION = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern PUBLIC_WORLD = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private final String json;
    private final Instant capturedAt;
    private final boolean empty;

    public record Row(Property property, int occupied) {
        public Row {
            if (property == null || property.propertyId() == null || property.worldUuid() == null
                    || property.regionId() == null
                    || !SOURCE_REGION.matcher(property.regionId()).matches()
                    || property.capacity() < 1 || property.capacity() > 4
                    || occupied < 0 || occupied > property.capacity()) {
                throw invalid();
            }
        }
    }

    /** Exact internal binding supplied by an operator; not an authorization mechanism. */
    public record Approval(UUID propertyId, UUID worldId, String regionId, String publicWorldKey) {
        public Approval {
            if (propertyId == null || worldId == null || regionId == null || publicWorldKey == null
                    || !REGION.matcher(regionId).matches()
                    || !PUBLIC_WORLD.matcher(publicWorldKey).matches()) throw invalid();
        }
    }

    private record PublicKey(String world, String region) { }
    private record SourceKey(UUID world, String region) { }

    private PublicOccupancySnapshot(String json, Instant capturedAt, boolean empty) {
        this.json = json;
        this.capturedAt = capturedAt;
        this.empty = empty;
    }

    public String json() { return json; }
    public Instant capturedAt() { return capturedAt; }
    public boolean isEmpty() { return empty; }

    /** Millisecond-aligned capture metadata, matching repository snapshots; not a freshness check. */
    public static PublicOccupancySnapshot build(Instant capturedAt, List<Row> rows, List<Approval> approvals) {
        if (capturedAt == null || capturedAt.getNano() % 1_000_000 != 0
                || capturedAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                || !capturedAt.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))
                || rows == null || approvals == null
                || rows.size() > MAX_INPUTS || approvals.size() > MAX_INPUTS) throw invalid();
        if (rows.stream().anyMatch(Objects::isNull)
                || approvals.stream().anyMatch(Objects::isNull)) throw invalid();
        var rowCopy = List.copyOf(rows);
        var approvalCopy = List.copyOf(approvals);
        var byId = new HashMap<UUID, Row>();
        var sourceKeys = new HashSet<SourceKey>();
        for (Row row : rowCopy) {
            Property property = row.property();
            if (byId.putIfAbsent(property.propertyId(), row) != null
                    || !sourceKeys.add(new SourceKey(property.worldUuid(),
                    property.regionId().toLowerCase(Locale.ROOT)))) throw invalid();
        }
        var approvedIds = new HashSet<UUID>();
        var publicKeys = new HashSet<PublicKey>();
        var publicByWorld = new HashMap<UUID, String>();
        var worldByPublic = new HashMap<String, UUID>();
        for (Approval approval : approvalCopy) {
            if (!approvedIds.add(approval.propertyId())
                    || !publicKeys.add(new PublicKey(approval.publicWorldKey(), approval.regionId()))) throw invalid();
            Row row = byId.get(approval.propertyId());
            if (row == null || !row.property().worldUuid().equals(approval.worldId())
                    || !row.property().regionId().equalsIgnoreCase(approval.regionId())) throw invalid();
            String priorPublic = publicByWorld.putIfAbsent(approval.worldId(), approval.publicWorldKey());
            UUID priorWorld = worldByPublic.putIfAbsent(approval.publicWorldKey(), approval.worldId());
            if ((priorPublic != null && !priorPublic.equals(approval.publicWorldKey()))
                    || (priorWorld != null && !priorWorld.equals(approval.worldId()))) throw invalid();
        }
        var properties = new JsonArray();
        approvalCopy.stream().sorted(Comparator.comparing(Approval::publicWorldKey)
                .thenComparing(Approval::regionId)).forEach(approval -> {
                    Row row = byId.get(approval.propertyId());
                    if (!row.property().enabled()) return;
                    var entry = new JsonObject();
                    entry.addProperty("world", approval.publicWorldKey());
                    entry.addProperty("region", approval.regionId());
                    entry.addProperty("occupancy", row.occupied() == 0 ? "EMPTY"
                            : row.occupied() == row.property().capacity() ? "FULL" : "PARTIAL");
                    properties.add(entry);
                });
        var root = new JsonObject();
        root.addProperty("version", "npc-public-occupancy-v1");
        root.addProperty("capturedAt", capturedAt.toString());
        root.add("properties", properties);
        return new PublicOccupancySnapshot(root.toString(), capturedAt, properties.isEmpty());
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid public occupancy snapshot input");
    }
}
