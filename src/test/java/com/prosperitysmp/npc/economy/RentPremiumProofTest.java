package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.formulas.PropertyFormulas;
import com.prosperitysmp.npc.market.HousingMoveRepository;
import com.prosperitysmp.npc.market.HousingMoveRequest;
import com.prosperitysmp.npc.market.HousingPolicy;
import com.prosperitysmp.npc.property.Property;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Stored-receipt fixtures and the real decoder; no SQL engine, admission or financial dispatch. */
class RentPremiumProofTest {
    private static final Database DATABASE = new Database();
    private static final UUID OWNER = id(1), RESERVE = id(2), RESIDENT = id(3), WORLD = id(4), PROPERTY = id(5);
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final int RESET = 4;
    // Before Chicago's reset: the correct receipt label is yesterday, not its UTC date.
    private static final Instant NOW = Instant.parse("2026-09-30T08:30:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);
    private static final Clock CLOCK = Clock.fixed(NOW, ZONE);
    private static final HousingMoveRequest.Policy POLICY = new HousingMoveRequest.Policy(3, 1, 1, 7);
    private static final Property HOME = new Property(PROPERTY, WORLD, "rent-proof", "town", "flat",
            4, 1000, 1300, OWNER, 6, 100, true);
    private static final Set<String> BINARY = Set.of("request_id", "resident_id", "property_id", "owner_uuid",
            "world_uuid", "premium_evidence", "origin_property_id", "origin_owner_uuid", "origin_admission_id");
    private static final Set<String> NUMERIC = Set.of("ownership_epoch", "property_revision", "membership_revision",
            "tenancy_revision_before", "tenancy_revision_after", "slot", "applied_at_millis", "server_limit",
            "market_limit", "property_limit", "cooldown_days", "origin_ownership_epoch", "origin_property_revision", "origin_slot");

    @Test
    void verifiedPremiumUsesExistingMultiplierAndStillHonorsTenantAndAskingCaps() throws Exception {
        try(var fixture = new Fixture(List.of(row(premium(), 5)))) {
            int premium = percent(repository(true), fixture, "PREMIUM", 1, 5);
            assertEquals(125, premium);
            assertEquals(1250, PropertyFormulas.tenantRentCents(HOME.askingRentCents(), HOME.referenceRentCents(), premium, 2000));
            assertEquals(1100, PropertyFormulas.tenantRentCents(HOME.askingRentCents(), HOME.referenceRentCents(), premium, 1100));
            assertEquals(900, PropertyFormulas.tenantRentCents(900, HOME.referenceRentCents(), premium, 2000));
        }
        try(var fixture = new Fixture(List.of())) {
            int standard = percent(repository(true), fixture, "STANDARD", 1, 5);
            assertEquals(100, standard);
            assertEquals(1000, PropertyFormulas.tenantRentCents(HOME.askingRentCents(), HOME.referenceRentCents(), standard, 2000));
            assertFalse(fixture.queried, "STANDARD retains its existing path without a premium receipt read");
        }
    }

    @Test
    void oldConstructorAndExplicitFalseKeepPremiumHeld() throws Exception {
        for(var repository : List.of(new RentReservationRepository(DATABASE, RESERVE), repository(false))) {
            try(var fixture = new Fixture(List.of(row(premium(), 5)))) {
                assertThrows(SQLException.class, () -> percent(repository, fixture, "PREMIUM", 1, 5));
                assertFalse(fixture.queried);
                assertEquals(100, percent(repository, fixture, "STANDARD", 1, 5));
            }
        }
    }

    @Test
    void manualPremiumWithoutAnAdmissionReceiptIsRejected() throws Exception {
        try(var fixture = new Fixture(List.of())) {
            assertThrows(SQLException.class, () -> percent(repository(true), fixture, "PREMIUM", 1, 5));
            assertTrue(fixture.queried);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-policy", "payload-hash", "missing-evidence", "evidence-hash", "evidence-header", "future"})
    void changedOrIncompleteStoredProofIsRejected(String defect) throws Exception {
        var row = row(premium(), 5);
        switch(defect) {
            case "missing-policy" -> row.put("server_limit", null);
            case "payload-hash" -> row.put("payload_hash", "0".repeat(64));
            case "missing-evidence" -> row.put("premium_evidence", null);
            case "evidence-hash" -> row.put("premium_evidence_sha256", "0".repeat(64));
            case "evidence-header" -> {
                byte[] changed = "fixture with no bound premium header".getBytes(StandardCharsets.UTF_8);
                row.put("premium_evidence", changed); row.put("premium_evidence_sha256", sha(changed));
            }
            case "future" -> row.put("applied_at_millis", NOW.plusMillis(1).toEpochMilli());
            default -> fail("Unknown fixture defect");
        }
        try(var fixture = new Fixture(List.of(row))) {
            assertThrows(SQLException.class, () -> percent(repository(true), fixture, "PREMIUM", 1, 5));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"resident", "property", "owner", "epoch", "slot", "revision", "standard-receipt"})
    void decodedAdmissionMustBindExactlyToTheCurrentPremiumTenancy(String mismatch) throws Exception {
        long after = mismatch.equals("revision") ? 6 : 5;
        var request = request(mismatch.equals("resident") ? id(99) : RESIDENT,
                mismatch.equals("property") ? id(99) : PROPERTY,
                mismatch.equals("owner") ? id(99) : OWNER, mismatch.equals("epoch") ? 7 : 6,
                mismatch.equals("slot") ? 2 : 1, after - 1,
                mismatch.equals("standard-receipt") ? HousingPolicy.Tier.STANDARD : HousingPolicy.Tier.PREMIUM, null);
        try(var fixture = new Fixture(List.of(row(request, after)))) {
            assertThrows(SQLException.class, () -> percent(repository(true), fixture, "PREMIUM", 1, 5));
        }
    }

    @Test
    void legacyReceiptWithoutExplicitTierCannotBackAManualPremiumFlag() throws Exception {
        var legacy = row(request(RESIDENT, PROPERTY, OWNER, 6, 1, 4, HousingPolicy.Tier.STANDARD, null), 5);
        legacy.remove("tier"); legacy.remove("premium_evidence"); legacy.remove("premium_evidence_sha256");
        try(var fixture = new Fixture(List.of(legacy))) {
            assertThrows(SQLException.class, () -> percent(repository(true), fixture, "PREMIUM", 1, 5));
        }
    }

    @Test
    void latestRevisionCannotFallBackToAnOlderMatchingDestinationOrMissingPolicy() throws Exception {
        var older = row(premium(), 5);
        var latest = row(request(RESIDENT, id(99), OWNER, 6, 1, 5, HousingPolicy.Tier.PREMIUM, null), 6);
        try(var fixture = new Fixture(List.of(latest, older))) {
            assertThrows(SQLException.class, () -> percent(repository(true), fixture, "PREMIUM", 1, 5));
        }
        latest = row(request(RESIDENT, PROPERTY, OWNER, 6, 1, 5, HousingPolicy.Tier.PREMIUM, null), 6);
        latest.put("server_limit", null);
        try(var fixture = new Fixture(List.of(latest, older))) {
            assertThrows(SQLException.class, () -> percent(repository(true), fixture, "PREMIUM", 1, 6));
        }
    }

    @Test
    void twoReceiptsAtTheLatestRevisionAreAmbiguous() throws Exception {
        var first = row(premium(), 5);
        var duplicateRevision = new LinkedHashMap<>(first);
        duplicateRevision.put("request_id", Uuids.toBytes(id(999)));
        try(var fixture = new Fixture(List.of(first, duplicateRevision))) {
            assertThrows(SQLException.class, () -> percent(repository(true), fixture, "PREMIUM", 1, 5));
        }
    }

    @Test
    void relocationReceiptUsesItsDecodedAfterRevisionRatherThanAssumingPlusOne() throws Exception {
        var origin = new HousingMoveRequest.Origin(id(700), id(701), 8, 9, 0, id(702));
        var relocation = request(RESIDENT, PROPERTY, OWNER, 6, 1, 3, HousingPolicy.Tier.PREMIUM, origin);
        try(var fixture = new Fixture(List.of(row(relocation, 5)))) {
            assertEquals(125, percent(repository(true), fixture, "PREMIUM", 1, 5));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"zone", "reset"})
    void receiptChronologyUsesTheActualSuppliedPolicyClock(String mismatch) throws Exception {
        try(var fixture = new Fixture(List.of(row(premium(), 5)))) {
            var wrong = new HousingMoveRepository(DATABASE, CLOCK,
                    mismatch.equals("zone") ? ZoneId.of("UTC") : ZONE, mismatch.equals("reset") ? 0 : RESET);
            assertThrows(SQLException.class, () -> repository(true).tenantTierPercent(fixture.connection, HOME,
                    RESIDENT, "PREMIUM", 1, 5, wrong));
        }
    }

    @Test
    void warningsInvalidSlotsRevisionsAndUnsupportedTiersFailClosed() throws Exception {
        try(var fixture = new Fixture(List.of(row(premium(), 5)))) {
            fixture.warning = true;
            assertThrows(SQLException.class, () -> percent(repository(true), fixture, "PREMIUM", 1, 5));
        }
        try(var fixture = new Fixture(List.of())) {
            assertThrows(SQLException.class, () -> percent(repository(true), fixture, "PREMIUM", 4, 5));
            assertThrows(SQLException.class, () -> percent(repository(true), fixture, "PREMIUM", 1, -1));
            assertThrows(SQLException.class, () -> percent(repository(true), fixture, "MANUAL", 1, 5));
            assertFalse(fixture.queried);
        }
    }

    private static RentReservationRepository repository(boolean enabled) {
        return new RentReservationRepository(DATABASE, RESERVE, CLOCK, ZONE, RESET, enabled);
    }

    private static int percent(RentReservationRepository repository, Fixture fixture, String tier, int slot, long revision)
            throws SQLException {
        return repository.tenantTierPercent(fixture.connection, HOME, RESIDENT, tier, slot, revision,
                new HousingMoveRepository(DATABASE, CLOCK, ZONE, RESET));
    }

    private static HousingMoveRequest premium() {
        return request(RESIDENT, PROPERTY, OWNER, 6, 1, 4, HousingPolicy.Tier.PREMIUM, null);
    }

    private static HousingMoveRequest request(UUID resident, UUID property, UUID owner, int epoch, int slot,
                                              long before, HousingPolicy.Tier tier, HousingMoveRequest.Origin origin) {
        return new HousingMoveRequest(id(300 + before), resident, property, owner, WORLD, "town", "flat",
                epoch, 7, 8, before, slot, DAY, tier, origin);
    }

    private static Map<String,Object> row(HousingMoveRequest request, long after) throws Exception {
        var row = new LinkedHashMap<String,Object>();
        row.put("request_id", Uuids.toBytes(request.requestId())); row.put("resident_id", Uuids.toBytes(request.residentId()));
        row.put("property_id", Uuids.toBytes(request.propertyId())); row.put("owner_uuid", Uuids.toBytes(request.ownerId()));
        row.put("world_uuid", Uuids.toBytes(request.worldId())); row.put("economy_day", request.day().toString());
        row.put("market_id", request.marketId()); row.put("class_key", request.classKey());
        row.put("ownership_epoch", request.ownershipEpoch()); row.put("property_revision", request.propertyRevision());
        row.put("membership_revision", request.membershipRevision()); row.put("tenancy_revision_before", request.tenancyRevision());
        row.put("tenancy_revision_after", after); row.put("slot", request.slot());
        row.put("cooldown_until", request.day().plusDays(POLICY.cooldownDays()).toString());
        row.put("applied_at_millis", NOW.toEpochMilli()); row.put("payload_hash", request.fingerprint(POLICY));
        row.put("tier", request.tier().name());
        byte[] proof = request.tier() == HousingPolicy.Tier.PREMIUM
                ? ("premium-proof-v1|" + request.fingerprint(POLICY) + "\nsynthetic stored-proof fixture")
                .getBytes(StandardCharsets.UTF_8) : null;
        row.put("premium_evidence", proof); row.put("premium_evidence_sha256", proof == null ? null : sha(proof));
        var origin = request.origin();
        row.put("origin_property_id", origin == null ? null : Uuids.toBytes(origin.propertyId()));
        row.put("origin_owner_uuid", origin == null ? null : Uuids.toBytes(origin.ownerId()));
        row.put("origin_ownership_epoch", origin == null ? null : origin.ownershipEpoch());
        row.put("origin_property_revision", origin == null ? null : origin.propertyRevision());
        row.put("origin_slot", origin == null ? null : origin.slot());
        row.put("origin_admission_id", origin == null ? null : Uuids.toBytes(origin.admissionId()));
        row.put("server_limit", POLICY.serverLimit()); row.put("market_limit", POLICY.marketLimit());
        row.put("property_limit", POLICY.propertyLimit()); row.put("cooldown_days", POLICY.cooldownDays());
        return row;
    }

    private static final class Fixture implements AutoCloseable {
        final CachedRowSet rows;
        final Connection connection;
        boolean queried, warning;

        Fixture(List<Map<String,Object>> values) throws Exception {
            rows = RowSetProvider.newFactory().createCachedRowSet();
            var columns = List.copyOf(values.isEmpty() ? row(premium(), 5).keySet() : values.getFirst().keySet());
            var metadata = new RowSetMetaDataImpl(); metadata.setColumnCount(columns.size());
            for(int index = 0; index < columns.size(); index++) {
                String name = columns.get(index);
                metadata.setColumnName(index + 1, name); metadata.setColumnLabel(index + 1, name);
                metadata.setColumnType(index + 1, BINARY.contains(name) ? Types.VARBINARY
                        : NUMERIC.contains(name) ? Types.BIGINT : Types.VARCHAR);
            }
            rows.setMetaData(metadata);
            for(var value : values) {
                rows.moveToInsertRow();
                for(int index = 0; index < columns.size(); index++) rows.updateObject(index + 1, value.get(columns.get(index)));
                rows.insertRow(); rows.moveToCurrentRow(); rows.last();
            }
            rows.beforeFirst();
            // CachedRowSetImpl starts with an empty SQLWarning unrelated to a query.
            rows.clearWarnings();
            assertNull(rows.getWarnings());
            connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> switch(method.getName()) {
                        case "prepareStatement" -> statement((String) args[0]);
                        case "getWarnings" -> warning ? new SQLWarning("fixture warning") : null;
                        case "close" -> null;
                        default -> throw new AssertionError("Unexpected connection call: " + method.getName());
                    });
        }

        private PreparedStatement statement(String sql) {
            assertTrue(sql.contains("LEFT JOIN npc_housing_days d ON d.economy_day=m.economy_day"));
            assertTrue(sql.contains("WHERE m.resident_id=? ORDER BY m.tenancy_revision_after DESC,m.request_id LIMIT 2"));
            assertFalse(sql.contains("WHERE m.property_id"), "Do not filter away a newer receipt for another destination");
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                    (proxy, method, args) -> switch(method.getName()) {
                        case "setBytes" -> { assertEquals(1, args[0]); assertArrayEquals(Uuids.toBytes(RESIDENT), (byte[]) args[1]); yield null; }
                        case "setQueryTimeout" -> { assertEquals(15, args[0]); yield null; }
                        case "executeQuery" -> { queried = true; yield rows; }
                        case "getWarnings", "close" -> null;
                        default -> throw new AssertionError("Unexpected statement call: " + method.getName());
                    });
        }

        @Override public void close() throws SQLException { rows.close(); }
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static UUID id(long number) { return new UUID(0, number); }
}
