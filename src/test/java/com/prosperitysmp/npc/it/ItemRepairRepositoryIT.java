package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.commerce.CustodyBytes;
import com.prosperitysmp.npc.commerce.ItemCollectionEvidence;
import com.prosperitysmp.npc.commerce.ItemCustody;
import com.prosperitysmp.npc.commerce.ItemCustodyRepository;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.ItemRepairQuote;
import com.prosperitysmp.npc.economy.ItemRepairRepository;
import com.prosperitysmp.npc.economy.ItemRepairRepository.Decision;
import com.prosperitysmp.npc.economy.ItemRepairRepository.OperatorResolution;
import com.prosperitysmp.npc.economy.ItemRepairRepository.Refusal;
import com.prosperitysmp.npc.economy.ItemRepairRepository.State;
import com.prosperitysmp.npc.economy.ItemRepairRepository.Uncertainty;
import com.prosperitysmp.npc.economy.PurchaseRepository;
import com.prosperitysmp.npc.economy.PurchaseRequest;
import com.prosperitysmp.npc.economy.RepairQuote;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.property.IncidentRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.Tenant;
import com.prosperitysmp.npc.property.TenantRepository;
import com.prosperitysmp.npc.property.UpgradeRepository;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real MariaDB, every registered migration. Item repairs move no money. The same IT runs a cash repair to show every
 * ledger counter can move, so the zero counts mean something. No Bukkit, Vault or inventory; evidence is supplied as
 * the runtime would supply it.
 */
@Timeout(60)
class ItemRepairRepositoryIT {
    static final LocalDate DAY = LocalDate.of(2026, 9, 6);
    static final LocalDate COMPLETION = DAY.plusDays(1);
    static final Instant AT = Instant.parse("2026-09-07T09:00:00.123Z");
    static final Instant CREATED = Instant.parse("2026-09-06T10:00:00Z");
    static final Instant DUE = Instant.parse("2026-09-08T10:00:00Z");
    static final UUID WORLD = new UUID(0, 3500);
    static final long SEED = 5_000;
    private static final List<String> TABLES = List.of("npc_contract_handover_evidence", "npc_town_contracts",
            "npc_town_contract_players", "npc_item_repair_resolutions", "npc_item_repair_returns",
            "npc_item_repair_evidence", "npc_incident_item_repair_receipts", "npc_item_repair_return_plans",
            "npc_item_repair_lines", "npc_item_repair_collections", "npc_item_delivery_evidence",
            "npc_item_custody_resolutions", "npc_item_custody", "npc_item_custody_players",
            "npc_incident_service_receipts", "npc_transfer_observations", "npc_transfer_attempts", "npc_purchase_intents",
            "npc_economy_events", "npc_resident_memory", "npc_tenants", "npc_resident_profiles", "npc_upgrades",
            "npc_incidents", "npc_property_ownership", "npc_property_funding", "npc_properties");
    private static DB mariaDb;
    private static Database database;
    private ItemRepairRepository repository;
    private PropertyRepository properties;
    private UUID owner, property, run;
    private List<UUID> occupants;
    private long incident;

    @BeforeAll static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder(); cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            String schema = "npc_items_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            var field = MigrationRunner.class.getDeclaredField("MIGRATIONS");
            field.setAccessible(true);
            for (Object migration : (List<?>) field.get(null)) {
                var resource = migration.getClass().getDeclaredMethod("resource");
                resource.setAccessible(true);
                String path = "/" + resource.invoke(migration);
                try (InputStream in = ItemRepairRepositoryIT.class.getResourceAsStream(path)) {
                    assertNotNull(in, path);
                    for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
                }
            }
        } catch (Exception | AssertionError failure) {
            try { stop(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll static void stop() throws Exception {
        try { if (database != null) database.close(); }
        finally { if (mariaDb != null) { DB stopping = mariaDb; mariaDb = null; stopping.stop(); } }
    }

    @BeforeEach void fixture() throws SQLException {
        for (String table : TABLES) execute("DELETE FROM " + table);
        repository = new ItemRepairRepository(database);
        properties = new PropertyRepository(database);
        owner = UUID.randomUUID(); property = UUID.randomUUID(); run = UUID.randomUUID();
        incident = home(property, owner);
        occupants = List.of(new UUID(0, 11), new UUID(Long.MIN_VALUE, 12));
        var residents = new ResidentRepository(database);
        var tenants = new TenantRepository(database);
        int slot = 0;
        for (UUID id : occupants) {
            String name = slot == 0 ? "Mara Vale" : "Ellis Ward";
            assertTrue(residents.register(ResidentCatalogue.draft(id, name)));
            tenants.insert(new Tenant(id, name, property, slot++, "STANDARD", "GENERIC", 50));
        }
    }

    /** One enrolled home at condition 80 with both upgrades, advertising switched off by an open PESTS incident. */
    long home(UUID id, UUID landlord) throws SQLException {
        properties.insertEnrollment(new Property(id, WORLD, "items-" + id, "downtown", "room", 4, 1_000, 1_000,
                landlord, 0, 80, true), SEED);
        UpgradeRepository upgrades = new UpgradeRepository(database);
        upgrades.setLevel(id, "ADVERTISING", 1, UpgradeType.ADVERTISING.costToLevelCents(1));
        upgrades.setLevel(id, "FACILITIES", 1, UpgradeType.FACILITIES.costToLevelCents(1));
        new IncidentRepository(database).create(id, IncidentType.PESTS, "ADVERTISING", DAY.toString(), DUE.toEpochMilli(), false);
        long created = Long.parseLong(scalar("SELECT incident_id FROM npc_incidents WHERE property_id=?", id));
        execute("UPDATE npc_incidents SET created_at=? WHERE incident_id=?", LocalDateTime.ofInstant(CREATED, ZoneOffset.UTC), created);
        execute("UPDATE npc_upgrades SET disabled_incident_id=? WHERE property_id=? AND upgrade_type='ADVERTISING'", created, id);
        return created;
    }

    RepairQuote repair() throws SQLException {
        return repository.currentRepair(property, owner, 0, incident, AT, COMPLETION, DAY).orElseThrow();
    }

    /** PESTS ($20): 16 paper at $1 + 8 baked potato at $2 = $32, above the $30 floor. */
    ItemRepairQuote quote(UUID collection, int paper) throws SQLException {
        var plan = new ArrayList<ItemRepairQuote.ReturnStack>();
        for (int amount : com.prosperitysmp.npc.economy.ItemRepairRecipes.chunks(paper, 64))
            plan.add(new ItemRepairQuote.ReturnStack(0, amount, new CustodyBytes(("paper-" + amount).getBytes())));
        plan.add(new ItemRepairQuote.ReturnStack(1, 8, new CustodyBytes("potato-8".getBytes())));
        long value = paper * 100L + 8 * 200L;
        return new ItemRepairQuote(collection, property, owner, 0, DAY, repair(), 2_000, 15_000, 3_000, value, "price-v1",
                List.of(new ItemRepairQuote.Line("PAPER", paper, 100, new CustodyBytes("paper".getBytes()), paper + 4),
                        new ItemRepairQuote.Line("BAKED_POTATO", 8, 200, new CustodyBytes("potato".getBytes()), 8)), plan);
    }
    ItemRepairQuote quote() throws SQLException { return quote(UUID.randomUUID(), 16); }

    static ItemCollectionEvidence confirmed(ItemRepairQuote q) {
        var after = new ArrayList<Integer>();
        for (var line : q.lines()) after.add(line.beforeUnits() - line.quantity());
        return new ItemCollectionEvidence(q.playerId(), q.collectionId(), "a".repeat(64), "b".repeat(64), q.beforeUnits(), after, true, true);
    }

    ItemRepairQuote started() throws SQLException {
        var q = quote();
        assertTrue(repository.begin(q, run, AT, COMPLETION).started());
        return q;
    }

    // ---- begin ---------------------------------------------------------------------------------------------

    @Test void beginWritesIntentAndOneOpenHandInPerPlayerAndIncident() throws SQLException {
        var q = started();
        assertEquals(State.INTENT, repository.find(q.collectionId(), owner).orElseThrow().state());
        assertEquals(q, repository.find(q.collectionId(), owner).orElseThrow().quote());
        assertEquals(1, count("npc_item_repair_collections"));
        assertEquals(2, count("npc_item_repair_lines"));
        assertEquals(2, count("npc_item_repair_return_plans"));
        assertEquals("INTENT", scalar("SELECT state FROM npc_item_repair_collections"));
        var second = repository.begin(quote(), run, AT, COMPLETION);
        assertFalse(second.started());
        assertEquals(Refusal.COLLECTION_OPEN, second.refusal());
        assertEquals(1, count("npc_item_repair_collections"));
        // The database also refuses a second open row for the same incident or the same player.
        assertThrows(SQLException.class, () -> execute("INSERT INTO npc_item_repair_collections SELECT ?,player_id,property_id,"
                + "incident_id,ownership_epoch,incident_type,repair_snapshot_sha256,quote_day,cash_cents,value_ratio_bps,"
                + "required_value_cents,recipe_value_cents,price_version,quote_sha256,'INTENT',run_id,NULL,DEFAULT,DEFAULT,"
                + "created_at,updated_at FROM npc_item_repair_collections", UUID.randomUUID()));
        assertNull(repository.find(UUID.randomUUID(), owner).orElse(null));
        assertTrue(repository.find(q.collectionId(), UUID.randomUUID()).isEmpty(), "only the player sees their hand-in");
    }

    @Test void beginIsRefusedWhileTheSameInventoryHasADeliveryRunning() throws SQLException {
        var custody = new ItemCustodyRepository(database);
        var delivery = new ItemCustody(UUID.randomUUID(), owner, UUID.randomUUID(), ItemCustody.Source.MERCHANT_GOODS, 4,
                new CustodyBytes(new byte[]{1, 2}), AT, AT.plusSeconds(900));
        custody.reserve(delivery, AT);
        assertTrue(repository.begin(quote(), run, AT, COMPLETION).started(), "a RESERVED offer does not block");
        execute("DELETE FROM npc_item_repair_return_plans"); execute("DELETE FROM npc_item_repair_lines");
        execute("DELETE FROM npc_item_repair_collections");
        assertTrue(custody.beginDelivery(delivery, run, AT).newlyStarted());
        var refused = repository.begin(quote(), run, AT, COMPLETION);
        assertEquals(Refusal.PLAYER_ITEMS_HELD, refused.refusal());
        assertEquals(0, count("npc_item_repair_collections"));
    }

    @Test void beginIsRefusedWhileAContractHandOverIsRunningOrHeld() throws SQLException {
        // contracts-partnerships: a hand-over that is running or held owns the same inventory.
        execute("INSERT INTO npc_town_contract_players(player_id) VALUES (?)", owner);
        UUID contract = UUID.randomUUID(), giver = occupants.get(0);
        long at = AT.toEpochMilli();
        execute("INSERT INTO npc_town_contracts (contract_id, player_id, kind, definition_id, title, description, giver_id, "
                + "recipient_id, material, quantity, minimum_stage, duration_minutes, accepted_at_millis, accepted_economy_day, "
                + "day_slot, expires_at_millis, status, handover_run_id) VALUES (?,?,'SUPPLY','wall','Wall','Wall.',?,?,'COAL',1,"
                + "'STRANGER',30,?,?,1,?,'HANDOVER_INTENT',?)", contract, owner, giver, giver, at, DAY.toString(), at + 1_800_000L, run);
        assertEquals(Refusal.PLAYER_ITEMS_HELD, repository.begin(quote(), run, AT, COMPLETION).refusal());
        execute("UPDATE npc_town_contracts SET status='AMBIGUOUS' WHERE contract_id=?", contract);
        assertEquals(Refusal.PLAYER_ITEMS_HELD, repository.begin(quote(), run, AT, COMPLETION).refusal());
        assertEquals(0, count("npc_item_repair_collections"));
        // Once settled, the same hand-in starts.
        execute("UPDATE npc_town_contracts SET status='RESOLVED_NOT_CONSUMED', closed_at_millis=? WHERE contract_id=?", at + 5, contract);
        assertTrue(repository.begin(quote(), run, AT, COMPLETION).started());
    }

    @Test void deliveriesWaitWhileAHandInIsOpenOrHeld() throws SQLException {
        var custody = new ItemCustodyRepository(database);
        var q = started();
        var goods = new ItemCustody(UUID.randomUUID(), owner, UUID.randomUUID(), ItemCustody.Source.MERCHANT_GOODS, 4,
                new CustodyBytes(new byte[]{3}), AT, AT.plusSeconds(900));
        custody.reserve(goods, AT);
        assertFalse(custody.beginDelivery(goods, run, AT).newlyStarted(), "INTENT holds the inventory");
        repository.markAmbiguous(q.collectionId(), owner, run, Uncertainty.TAKE_FAILED);
        assertFalse(custody.beginDelivery(goods, run, AT).newlyStarted(), "AMBIGUOUS holds it too");
        execute("UPDATE npc_item_repair_collections SET state='CANCELLED'");
        assertTrue(custody.beginDelivery(goods, run, AT).newlyStarted(), "a closed hand-in releases it");
    }

    @Test void beginIsRefusedWithReturnsWaitingOrACashRepairInProgress() throws SQLException {
        // Undelivered returns first.
        var q = started();
        repository.settle(q.collectionId(), owner, run, confirmed(q), false, AT, COMPLETION);
        var refused = repository.begin(quote(), run, AT, COMPLETION);
        assertEquals(Refusal.RETURNS_PENDING, refused.refusal());
        // A waiting cash repair for the same incident.
        fixture();
        var purchases = purchases(() -> false);
        purchases.enqueue(purchases.quoteRepair(UUID.randomUUID(), property, owner, 0, incident, DAY));
        assertEquals(Refusal.CASH_PENDING, repository.begin(quote(), run, AT, COMPLETION).refusal());
        assertEquals(0, count("npc_item_repair_collections"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "epoch", "disabled", "resolved", "tenant-version"})
    void beginIsRefusedWhenTheHomeOrProblemChanged(String change) throws SQLException {
        var q = quote();
        mutate(change);
        var refused = repository.begin(q, run, AT, COMPLETION);
        assertFalse(refused.started());
        assertEquals(change.equals("resolved") || change.equals("tenant-version") ? Refusal.TARGET_CHANGED : Refusal.OWNERSHIP_CHANGED,
                refused.refusal());
        assertEquals(0, count("npc_item_repair_collections"));
    }

    // ---- settle ---------------------------------------------------------------------------------------------

    @Test void provenTakeAppliesTheSameRepairAsCashWithoutMoney() throws SQLException {
        long[] funding = properties.fundingOf(property);
        var ledger = ledgerCounts();
        var q = started();
        var settled = repository.settle(q.collectionId(), owner, run, confirmed(q), true, AT, COMPLETION);
        assertEquals(State.APPLIED, settled.state());
        assertEquals("ITEMS_APPLIED", settled.outcome());
        assertTrue(settled.returns().isEmpty());
        assertEquals("85", scalar("SELECT condition_score FROM npc_properties WHERE property_id=?", property));
        assertEquals(hex(owner), scalar("SELECT HEX(resolver_uuid) FROM npc_incidents WHERE incident_id=?", incident).toLowerCase());
        assertEquals(LocalDateTime.ofInstant(AT, ZoneOffset.UTC).toString().replace('T', ' '),
                scalar("SELECT CAST(resolved_at AS CHAR) FROM npc_incidents WHERE incident_id=?", incident));
        assertEquals("0", scalar("SELECT COUNT(*) FROM npc_upgrades WHERE property_id=? AND disabled_incident_id IS NOT NULL", property));
        String source = "repair:" + incident + ":items:" + q.collectionId();
        assertEquals("2", scalar("SELECT COUNT(*) FROM npc_resident_memory WHERE source_ref=? AND kind='REPAIR_COMPLETED' AND player_id=?",
                source, owner));
        for (UUID resident : occupants)
            assertEquals(4, new ResidentRepository(database).readRelationship(resident, owner).reliability());
        assertEquals(1, count("npc_incident_item_repair_receipts"));
        assertEquals(hex(q.collectionId()), scalar("SELECT HEX(collection_id) FROM npc_incident_item_repair_receipts").toLowerCase());
        assertEquals("2000", scalar("SELECT cash_equivalent_cents FROM npc_incident_item_repair_receipts"));
        assertEquals(0, count("npc_incident_service_receipts"), "the cash service receipt table is untouched");
        assertEquals(ledger, ledgerCounts(), "no event, intent, transfer or observation row");
        assertArrayEquals(funding, properties.fundingOf(property), "no sink credit: lifetime funding unchanged");
        // Exact replay is a no-op; a different take for the same hand-in is a conflict.
        var before = rows("SELECT * FROM npc_item_repair_collections");
        assertEquals(settled, repository.settle(q.collectionId(), owner, run, confirmed(q), true, AT, COMPLETION));
        assertEquals(before, rows("SELECT * FROM npc_item_repair_collections"));
        var conflicting = new ItemCollectionEvidence(owner, q.collectionId(), "a".repeat(64), "c".repeat(64), q.beforeUnits(),
                confirmed(q).afterUnits(), true, true);
        assertThrows(SQLException.class, () -> repository.settle(q.collectionId(), owner, run, conflicting, true, AT, COMPLETION));
        assertThrows(SQLException.class, () -> repository.settle(q.collectionId(), owner, UUID.randomUUID(), confirmed(q), true, AT, COMPLETION));
    }

    @ParameterizedTest
    @EnumSource(IncidentType.class)
    void conditionAllowanceIsCappedAtOneHundred(IncidentType type) throws SQLException {
        execute("UPDATE npc_incidents SET incident_type=?,cash_repair_cents=?,condition_damage=?,item_repair_spec=? WHERE incident_id=?",
                type.name(), type.cashRepairCents(), type.conditionDamage(), type.itemRepairSpec(), incident);
        properties.setCondition(property, 99);
        var repair = repair();
        long cash = type.cashRepairCents();
        long required = com.prosperitysmp.npc.economy.ItemRepairRecipes.requiredCents(cash, 15_000);
        int paper = (int) Math.ceilDiv(required, 100L);
        var q = new ItemRepairQuote(UUID.randomUUID(), property, owner, 0, DAY, repair, cash, 15_000, required, paper * 100L,
                "price-v1", List.of(new ItemRepairQuote.Line("PAPER", paper, 100, new CustodyBytes("paper".getBytes()), paper)),
                com.prosperitysmp.npc.economy.ItemRepairRecipes.chunks(paper, 64).stream()
                        .map(n -> new ItemRepairQuote.ReturnStack(0, n, new CustodyBytes(("p" + n).getBytes()))).toList());
        assertTrue(repository.begin(q, run, AT, COMPLETION).started());
        assertEquals(State.APPLIED, repository.settle(q.collectionId(), owner, run, confirmed(q), true, AT, COMPLETION).state());
        assertEquals("100", scalar("SELECT condition_score FROM npc_properties WHERE property_id=?", property));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant-version", "epoch", "disabled", "owner", "lease", "resolved"})
    void provenTakeOnAChangedHomeGivesBackExactlyWhatWasTaken(String change) throws SQLException {
        var q = quote(UUID.randomUUID(), 100);
        assertTrue(repository.begin(q, run, AT, COMPLETION).started());
        if (!change.equals("lease")) mutate(change);
        var benefits = rows("SELECT condition_score,row_version FROM npc_properties WHERE property_id=?", property);
        var ledger = ledgerCounts();
        var settled = repository.settle(q.collectionId(), owner, run, confirmed(q), !change.equals("lease"), AT, COMPLETION);
        assertEquals(State.RETURN_ISSUED, settled.state());
        assertEquals(switch (change) {
            case "tenant-version" -> "REPAIR_TARGET_CHANGED";
            case "resolved" -> "INCIDENT_RESOLVED";
            default -> "OWNERSHIP_CHANGED";
        }, settled.outcome());
        assertEquals(benefits, rows("SELECT condition_score,row_version FROM npc_properties WHERE property_id=?", property));
        assertEquals("0", scalar("SELECT COUNT(*) FROM npc_resident_memory WHERE source_ref LIKE 'repair:%:items:%'"));
        assertEquals(0, count("npc_incident_item_repair_receipts"));
        if (!change.equals("resolved")) assertNull(scalarOrNull("SELECT resolved_at FROM npc_incidents WHERE incident_id=?", incident));
        assertEquals(ledger, ledgerCounts());
        // 100 paper comes back as 64 + 36, and 8 potatoes as one stack: exactly what was taken, never more.
        assertEquals(List.of(64, 36, 8), settled.returns().stream().map(ItemCustody::quantity).toList());
        assertTrue(settled.returns().stream().allMatch(c -> c.source() == ItemCustody.Source.REPAIR_RETURN && c.playerId().equals(owner)));
        assertEquals(List.of(List.of("REPAIR_RETURN", "RESERVED", "108")),
                rows("SELECT source,state,SUM(quantity) FROM npc_item_custody GROUP BY source,state"));
        assertEquals(3, count("npc_item_repair_returns"));
        assertEquals(settled.returns(), repository.pendingReturns(owner));
        assertEquals(List.of(owner), repository.playersWithPendingReturns(10));
        // Replay returns the same rows and issues nothing new.
        assertEquals(settled, repository.settle(q.collectionId(), owner, run, confirmed(q), true, AT, COMPLETION));
        assertEquals(3, count("npc_item_custody"));
        // Each return is a one-shot nonce through the normal delivery, still deliverable after its nominal expiry.
        var custody = new ItemCustodyRepository(database);
        Instant late = AT.plusSeconds(3_600);
        assertEquals(0, custody.expireReservations(late, 100));
        var start = custody.beginDelivery(settled.returns().getFirst(), run, late);
        assertTrue(start.newlyStarted());
        assertFalse(custody.beginDelivery(settled.returns().getFirst(), run, late).newlyStarted());
    }

    @Test void returnsOnlyCountAsWaitingWhileTheyCanStillBeDelivered() throws SQLException {
        var q = started();
        var settled = repository.settle(q.collectionId(), owner, run, confirmed(q), false, AT, COMPLETION);
        assertEquals(List.of(16, 8), settled.returns().stream().map(ItemCustody::quantity).toList());
        assertTrue(repository.hasOpenWork(), "items still to give back");
        assertEquals(List.of(owner), repository.playersWithPendingReturns(10));
        assertTrue(repository.heldDelivery(owner).isEmpty());
        // The first stack's delivery becomes unclear: every delivery for this player now waits for staff.
        var custody = new ItemCustodyRepository(database);
        var first = settled.returns().getFirst();
        assertTrue(custody.beginDelivery(first, run, AT).newlyStarted());
        custody.markAmbiguous(first, run, ItemCustodyRepository.Uncertainty.DELIVERY_UNCERTAIN);
        assertEquals(java.util.Optional.of(first.requestNonce()), repository.heldDelivery(owner));
        assertTrue(repository.heldDelivery(UUID.randomUUID()).isEmpty());
        assertFalse(custody.beginDelivery(settled.returns().get(1), run, AT).newlyStarted(), "the held stack blocks the rest");
        assertEquals(List.of(), repository.playersWithPendingReturns(10), "not offered while nothing can be delivered");
        assertTrue(repository.hasOpenWork(), "a held return still needs the runtime for its alert");
        // Staff settle it as delivered: the second stack is collectable again, then delivered.
        custody.reconcile(first, run, new ItemCustodyRepository.OperatorResolution(UUID.randomUUID(), "staff:case-9",
                ItemCustodyRepository.Decision.VERIFIED_DELIVERED, new CustodyBytes(new byte[]{9})));
        assertTrue(repository.heldDelivery(owner).isEmpty());
        assertEquals(List.of(owner), repository.playersWithPendingReturns(10));
        var second = settled.returns().get(1);
        assertTrue(custody.beginDelivery(second, run, AT).newlyStarted());
        assertEquals(ItemCustodyRepository.State.DELIVERED, custody.recordDelivery(second, run, new com.prosperitysmp.npc.commerce.ItemDeliveryEvidence(
                owner, second.payload().sha256(), "b".repeat(64), "c".repeat(64), 0, second.quantity(), 0, true)).state());
        assertEquals("RETURN_ISSUED", scalar("SELECT state FROM npc_item_repair_collections"));
        assertFalse(repository.hasOpenWork(), "every return given back: nothing left for a runtime to do");
        assertEquals(List.of(), repository.playersWithPendingReturns(10));
    }

    // ---- ambiguity and recovery -----------------------------------------------------------------------------

    @Test void unclearEvidenceIsHeldAndLateEvidenceNeverReleasesIt() throws SQLException {
        var q = started();
        var unclear = new ItemCollectionEvidence(owner, q.collectionId(), "a".repeat(64), "b".repeat(64), q.beforeUnits(),
                confirmed(q).afterUnits(), true, false);
        var settled = repository.settle(q.collectionId(), owner, run, unclear, true, AT, COMPLETION);
        assertEquals(State.AMBIGUOUS, settled.state());
        assertEquals("EVIDENCE_UNCERTAIN", settled.outcome());
        assertNull(scalarOrNull("SELECT resolved_at FROM npc_incidents WHERE incident_id=?", incident));
        assertEquals(0, count("npc_item_custody"));
        assertEquals(Refusal.COLLECTION_OPEN, repository.begin(quote(), run, AT, COMPLETION).refusal(), "held blocks a new hand-in");
        // Recovery held a different hand-in before its evidence arrived: the evidence is kept, the hold stays.
        fixture();
        var other = started();
        assertEquals(1, repository.recoverOtherRuns(UUID.randomUUID(), 100));
        assertEquals(State.AMBIGUOUS, repository.settle(other.collectionId(), owner, run, confirmed(other), true, AT, COMPLETION).state());
        assertEquals(1, count("npc_item_repair_evidence"));
    }

    @Test void recoveryHoldsOnlyOtherRunsAndIsBounded() throws SQLException {
        var mine = started();
        assertEquals(0, repository.recoverOtherRuns(run, 100), "its own run is left alone");
        assertEquals(State.INTENT, repository.find(mine.collectionId(), owner).orElseThrow().state());
        assertEquals(0, repository.recoverOtherRuns(UUID.randomUUID(), 0));
        assertEquals(1, repository.recoverOtherRuns(UUID.randomUUID(), 100));
        var held = repository.find(mine.collectionId(), owner).orElseThrow();
        assertEquals(State.AMBIGUOUS, held.state());
        assertEquals("INTERRUPTED_RUN", held.outcome());
        assertEquals(0, repository.recoverOtherRuns(UUID.randomUUID(), 100));
        assertEquals(List.of(held.quote().collectionId()), repository.listOpen(10).stream().map(ItemRepairRepository.Summary::collectionId).toList());
        assertTrue(repository.hasOpenWork());
    }

    @Test void ownRunNeverCrowdsOutAnotherRunInABoundedSweep() throws SQLException {
        // Two players, two homes: this run's INTENT sorts first, another run's INTENT sorts last.
        UUID ownId = new UUID(0, 1), otherId = new UUID(-1L, -1L);
        var own = quote(ownId, 16);
        assertTrue(repository.begin(own, run, AT, COMPLETION).started());
        UUID secondOwner = UUID.randomUUID(), secondHome = UUID.randomUUID();
        long secondIncident = home(secondHome, secondOwner);
        var repair = repository.currentRepair(secondHome, secondOwner, 0, secondIncident, AT, COMPLETION, DAY).orElseThrow();
        var other = new ItemRepairQuote(otherId, secondHome, secondOwner, 0, DAY, repair, 2_000, 15_000, 3_000, 3_200, "price-v1",
                List.of(new ItemRepairQuote.Line("PAPER", 32, 100, new CustodyBytes("paper".getBytes()), 32)),
                List.of(new ItemRepairQuote.ReturnStack(0, 32, new CustodyBytes("p32".getBytes()))));
        UUID oldRun = UUID.randomUUID();
        assertTrue(repository.begin(other, oldRun, AT, COMPLETION).started());
        assertEquals(1, repository.recoverOtherRuns(run, 1), "a one-row sweep still reaches the other run");
        assertEquals(State.AMBIGUOUS, repository.find(otherId, secondOwner).orElseThrow().state());
        assertEquals(State.INTENT, repository.find(ownId, owner).orElseThrow().state());
    }

    @Test void cancelBeforeTakeClosesWithNothingToReturn() throws SQLException {
        var q = started();
        assertThrows(SQLException.class, () -> repository.cancelBeforeTake(q.collectionId(), owner, UUID.randomUUID(), "PRICES_CHANGED"));
        assertEquals(State.CANCELLED, repository.cancelBeforeTake(q.collectionId(), owner, run, "PRICES_CHANGED").state());
        assertFalse(repository.hasOpenWork());
        assertTrue(repository.begin(quote(), run, AT, COMPLETION).started(), "a cancelled hand-in frees the incident");
    }

    @Test void staffDecisionsCloseHeldHandIns() throws SQLException {
        // Not collected: closed, nothing returned, nothing repaired.
        var q = started();
        repository.recoverOtherRuns(UUID.randomUUID(), 100);
        var notCollected = new OperatorResolution(UUID.randomUUID(), "staff:case-1", Decision.VERIFIED_NOT_COLLECTED, new CustodyBytes(new byte[]{1}));
        var closed = repository.reconcile(q.collectionId(), owner, notCollected, AT, COMPLETION);
        assertEquals(State.RECONCILED_NOT_COLLECTED, closed.state());
        assertTrue(closed.returns().isEmpty());
        assertEquals(closed, repository.reconcile(q.collectionId(), owner, notCollected, AT, COMPLETION), "exact replay");
        assertThrows(SQLException.class, () -> repository.reconcile(q.collectionId(), owner,
                new OperatorResolution(UUID.randomUUID(), "staff:case-1", Decision.VERIFIED_COLLECTED, new CustodyBytes(new byte[]{1})), AT, COMPLETION));
        assertNull(scalarOrNull("SELECT resolved_at FROM npc_incidents WHERE incident_id=?", incident));
        assertTrue(repository.begin(quote(), run, AT, COMPLETION).started(), "closed: the problem is free for a new hand-in");
    }

    @Test void staffCollectedDecisionAppliesOrReturns() throws SQLException {
        var q = started();
        repository.markAmbiguous(q.collectionId(), owner, run, Uncertainty.SETTLE_FAILED);
        var collected = new OperatorResolution(UUID.randomUUID(), "staff:case-3", Decision.VERIFIED_COLLECTED, new CustodyBytes(new byte[]{3}));
        assertEquals(State.APPLIED, repository.reconcile(q.collectionId(), owner, collected, AT, COMPLETION).state());
        assertEquals(1, count("npc_incident_item_repair_receipts"));
        assertEquals(1, count("npc_item_repair_resolutions"));
        fixture();
        var moved = started();
        repository.markAmbiguous(moved.collectionId(), owner, run, Uncertainty.SETTLE_FAILED);
        mutate("epoch");
        var returned = repository.reconcile(moved.collectionId(), owner, new OperatorResolution(UUID.randomUUID(), "staff:case-4",
                Decision.VERIFIED_COLLECTED, new CustodyBytes(new byte[]{4})), AT, COMPLETION);
        assertEquals(State.RETURN_ISSUED, returned.state());
        assertEquals(List.of(16, 8), returned.returns().stream().map(ItemCustody::quantity).toList());
    }

    // ---- held return deliveries (ItemRepairRecoveryTool --resolve-return, server stopped) ---------------------

    /** A RETURN_ISSUED hand-in (16 paper, 8 potato) whose first stack's delivery is held as DELIVERY_UNCERTAIN. */
    private ItemCustody heldFirstReturn(ItemCustodyRepository custody) throws SQLException {
        var q = started();
        var settled = repository.settle(q.collectionId(), owner, run, confirmed(q), false, AT, COMPLETION);
        assertEquals(List.of(16, 8), settled.returns().stream().map(ItemCustody::quantity).toList());
        var first = settled.returns().getFirst();
        assertTrue(custody.beginDelivery(first, run, AT).newlyStarted());
        custody.markAmbiguous(first, run, ItemCustodyRepository.Uncertainty.DELIVERY_UNCERTAIN);
        return first;
    }
    private static ItemCustodyRepository.OperatorResolution staff(ItemCustodyRepository.Decision decision, int tag) {
        return new ItemCustodyRepository.OperatorResolution(new UUID(77, tag), "staff:case-" + tag, decision,
                new CustodyBytes(new byte[]{(byte) tag}));
    }
    private ItemCustody goods(int tag) {
        return new ItemCustody(new UUID(88, tag), owner, new UUID(89, tag), ItemCustody.Source.MERCHANT_GOODS, 4,
                new CustodyBytes(new byte[]{(byte) tag}), AT, AT.plusSeconds(900));
    }
    private void deliverAll(ItemCustodyRepository custody, Instant at) throws SQLException {
        for (var stack : repository.pendingReturns(owner)) {
            assertTrue(custody.beginDelivery(stack, run, at).newlyStarted());
            assertEquals(ItemCustodyRepository.State.DELIVERED, custody.recordDelivery(stack, run,
                    new com.prosperitysmp.npc.commerce.ItemDeliveryEvidence(owner, stack.payload().sha256(), "b".repeat(64),
                            "c".repeat(64), 0, stack.quantity(), 0, true)).state());
        }
    }

    @Test void theOfflineToolReadsTheRecordedTakeCountsOnly() throws SQLException {
        var q = started();
        assertTrue(repository.takeEvidence(q.collectionId()).isEmpty(), "nothing recorded before the take");
        var unclear = new ItemCollectionEvidence(owner, q.collectionId(), "a".repeat(64), "b".repeat(64), q.beforeUnits(),
                confirmed(q).afterUnits(), true, false);
        assertEquals(State.AMBIGUOUS, repository.settle(q.collectionId(), owner, run, unclear, true, AT, COMPLETION).state());
        assertEquals(java.util.Optional.of(unclear), repository.takeEvidence(q.collectionId()), "the counts as written");
        assertTrue(repository.takeEvidence(UUID.randomUUID()).isEmpty(), "another hand-in has none");
    }

    @Test void staffListAndStartupAlertNameAHeldOrInterruptedReturn() throws SQLException {
        var custody = new ItemCustodyRepository(database);
        var q = started();
        var first = repository.settle(q.collectionId(), owner, run, confirmed(q), false, AT, COMPLETION).returns().getFirst();
        var waiting = repository.listOpen(10).getFirst();
        assertEquals(2, waiting.undeliveredReturns(), "the row is listed");
        assertNull(waiting.heldReturn(), "stacks only waiting to be collected are not held");
        assertTrue(custody.beginDelivery(first, run, AT).newlyStarted());
        var started = repository.listOpen(10).getFirst();
        assertEquals(List.of(first.requestNonce(), "DELIVERY_INTENT"), List.of(started.heldReturn(), started.heldReturnState()));
        // The run dies; the next start holds it as INTERRUPTED_RUN.
        assertEquals(1, custody.recoverOtherRuns(UUID.randomUUID(), 100));
        var held = repository.listOpen(10).getFirst();
        assertEquals(List.of(first.requestNonce(), "AMBIGUOUS"), List.of(held.heldReturn(), held.heldReturnState()));
        assertEquals("INTERRUPTED_RUN", scalar("SELECT outcome_code FROM npc_item_custody WHERE request_nonce=?", first.requestNonce()));
    }

    @Test void staffDeliveredClosesAHeldReturnAndFreesEveryDelivery() throws SQLException {
        var custody = new ItemCustodyRepository(database);
        var first = heldFirstReturn(custody);
        var shop = goods(1);
        custody.reserve(shop, AT);
        assertFalse(custody.beginDelivery(shop, run, AT).newlyStarted(), "the held return stops every delivery");
        assertEquals(java.util.Optional.of(first.requestNonce()), repository.heldDelivery(owner));
        var binding = repository.returnBinding(first.requestNonce()).orElseThrow();
        assertEquals(0, binding.stackNo());
        var decision = staff(ItemCustodyRepository.Decision.VERIFIED_DELIVERED, 1);
        var resolved = repository.resolveReturn(first, run, decision, AT.plusSeconds(60));
        assertEquals(ItemCustodyRepository.State.DELIVERED, resolved.state());
        assertNull(resolved.reissued(), "the player has the items: nothing is issued again");
        assertEquals(3, count("npc_item_custody"), "two return stacks and the shop order, nothing new");
        assertEquals(binding, repository.returnBinding(first.requestNonce()).orElseThrow(), "the hand-in still points at the delivered stack");
        assertEquals(resolved, repository.resolveReturn(first, run, decision, AT.plusSeconds(90)), "exact replay");
        assertThrows(SQLException.class, () -> repository.resolveReturn(first, run,
                staff(ItemCustodyRepository.Decision.VERIFIED_NOT_DELIVERED, 1), AT), "a different decision is refused");
        assertEquals(1, count("npc_item_custody_resolutions"));
        assertTrue(repository.heldDelivery(owner).isEmpty());
        assertEquals(List.of(8), repository.pendingReturns(owner).stream().map(ItemCustody::quantity).toList());
        assertTrue(repository.hasOpenWork(), "the potato stack is still owed");
        assertTrue(custody.beginDelivery(shop, run, AT).newlyStarted(), "deliveries of any source start again");
    }

    @Test void staffNotDeliveredIssuesTheSameStackOnceAndItIsGivenBackOnce() throws SQLException {
        var custody = new ItemCustodyRepository(database);
        var first = heldFirstReturn(custody);
        var binding = repository.returnBinding(first.requestNonce()).orElseThrow();
        Instant later = AT.plusSeconds(7_200);
        var decision = staff(ItemCustodyRepository.Decision.VERIFIED_NOT_DELIVERED, 2);
        var resolved = repository.resolveReturn(first, run, decision, later);
        assertEquals(ItemCustodyRepository.State.RECONCILED_NOT_DELIVERED, resolved.state());
        var again = resolved.reissued();
        assertNotNull(again, "the items are still owed");
        assertNotEquals(first.requestNonce(), again.requestNonce(), "a fresh one-shot nonce");
        assertEquals(first.requestNonce(), again.offerId(), "the new row names the held one");
        assertEquals(List.of(ItemCustody.Source.REPAIR_RETURN, owner, first.quantity(), first.payload()),
                List.of(again.source(), again.playerId(), again.quantity(), again.payload()));
        assertEquals("RECONCILED_NOT_DELIVERED", scalar("SELECT state FROM npc_item_custody WHERE request_nonce=?", first.requestNonce()));
        assertEquals(binding, repository.returnBinding(again.requestNonce()).orElseThrow(), "the hand-in now points at the new row");
        assertTrue(repository.returnBinding(first.requestNonce()).isEmpty());
        assertEquals(List.of(again.requestNonce()), repository.pendingReturns(owner).stream().limit(1).map(ItemCustody::requestNonce).toList());
        assertEquals(List.of(16, 8), repository.pendingReturns(owner).stream().map(ItemCustody::quantity).toList());
        assertEquals(List.of(owner), repository.playersWithPendingReturns(10));
        assertTrue(repository.heldDelivery(owner).isEmpty());
        assertTrue(repository.hasOpenWork());
        assertEquals(List.of(List.of("RETURN_ISSUED", "2")), rows("""
                SELECT k.state,(SELECT COUNT(*) FROM npc_item_repair_returns r JOIN npc_item_custody i ON i.request_nonce=r.request_nonce
                WHERE r.collection_id=k.collection_id AND i.state='RESERVED') FROM npc_item_repair_collections k"""));
        // Never twice: the same decision replays without a new row; a second decision is refused.
        assertEquals(resolved, repository.resolveReturn(first, run, decision, later.plusSeconds(5)));
        assertThrows(SQLException.class, () -> repository.resolveReturn(first, run, staff(ItemCustodyRepository.Decision.VERIFIED_NOT_DELIVERED, 3), later));
        assertThrows(SQLException.class, () -> repository.resolveReturn(first, run, staff(ItemCustodyRepository.Decision.VERIFIED_DELIVERED, 2), later));
        assertEquals(3, count("npc_item_custody"));
        // The reissued stack never expires and is given back through the normal delivery, once.
        assertEquals(0, custody.expireReservations(later.plusSeconds(86_400), 100));
        deliverAll(custody, later.plusSeconds(86_400));
        assertEquals("24", scalar("SELECT SUM(quantity) FROM npc_item_custody WHERE state='DELIVERED'"), "16 paper and 8 potato, exactly once");
        assertFalse(repository.hasOpenWork(), "everything given back: nothing left for a runtime to do");
        assertEquals(List.of(), repository.pendingReturns(owner));
    }

    @Test void anInterruptedReturnDeliveryIsSettledWithTheServerStopped() throws SQLException {
        var custody = new ItemCustodyRepository(database);
        var q = started();
        var returns = repository.settle(q.collectionId(), owner, run, confirmed(q), false, AT, COMPLETION).returns();
        var first = returns.getFirst();
        assertTrue(custody.beginDelivery(first, run, AT).newlyStarted());
        // The runtime path still needs held ambiguity; only the offline return path holds a dead run's intent itself.
        var runtimePath = staff(ItemCustodyRepository.Decision.VERIFIED_NOT_DELIVERED, 4);
        assertThrows(SQLException.class, () -> custody.reconcile(first, run, runtimePath));
        var resolved = repository.resolveReturn(first, run, runtimePath, AT);
        assertEquals(ItemCustodyRepository.State.RECONCILED_NOT_DELIVERED, resolved.state());
        assertNotNull(resolved.reissued());
        assertEquals("INTERRUPTED_RUN", scalar("SELECT original_outcome FROM npc_item_custody_resolutions"));
        assertEquals(List.of(16, 8), repository.pendingReturns(owner).stream().map(ItemCustody::quantity).toList());
    }

    @Test void onlyAHeldBoundItemRepairReturnIsSettledThisWayAndRefusalsWriteNothing() throws SQLException {
        var custody = new ItemCustodyRepository(database);
        var first = heldFirstReturn(custody);
        var decision = staff(ItemCustodyRepository.Decision.VERIFIED_NOT_DELIVERED, 5);
        assertThrows(SQLException.class, () -> repository.resolveReturn(first, UUID.randomUUID(), decision, AT), "another run");
        var reserved = repository.pendingReturns(owner).getFirst();
        assertThrows(SQLException.class, () -> repository.resolveReturn(reserved, run, decision, AT), "a waiting stack is not held");
        // A shop delivery is never settled by the item repair tool, by either guard.
        var shop = goods(6);
        assertThrows(IllegalArgumentException.class, () -> repository.resolveReturn(shop, run, decision, AT));
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            assertThrows(IllegalArgumentException.class, () -> ItemCustodyRepository.reconcileHeldReturn(c, shop, run, decision));
            c.rollback();
        }
        // A held return row that no hand-in points at is refused, and the whole settle rolls back.
        UUID stray = new UUID(99, 1), strayRun = UUID.randomUUID();
        var strayRow = new ItemCustody(stray, UUID.randomUUID(), new UUID(99, 2), ItemCustody.Source.REPAIR_RETURN, 4,
                new CustodyBytes(new byte[]{4}), AT, AT.plusSeconds(900));
        execute("INSERT INTO npc_item_custody_players(player_id) VALUES(?)", strayRow.playerId());
        execute("""
                INSERT INTO npc_item_custody(request_nonce,player_id,offer_id,source,quantity,payload_bytes,payload_sha256,
                reserved_at_millis,expires_at_millis,state,delivery_run_id,outcome_code) VALUES(?,?,?,'REPAIR_RETURN',4,?,?,?,?,'AMBIGUOUS',?,'DELIVERY_UNCERTAIN')
                """, stray, strayRow.playerId(), strayRow.offerId(), strayRow.payload().bytes(), strayRow.payload().sha256(),
                AT.toEpochMilli(), AT.plusSeconds(900).toEpochMilli(), strayRun);
        assertThrows(SQLException.class, () -> repository.resolveReturn(strayRow, strayRun, decision, AT));
        assertEquals("AMBIGUOUS", scalar("SELECT state FROM npc_item_custody WHERE request_nonce=?", stray));
        assertEquals(0, count("npc_item_custody_resolutions"), "no refusal left a decision behind");
        assertEquals(3, count("npc_item_custody"), "two return stacks and the stray row: no stack was issued");
        // The real held stack does settle, so the refusals above are real.
        assertNotNull(repository.resolveReturn(first, run, decision, AT).reissued());
    }

    // ---- a return whose stored items cannot be read ----------------------------------------------------------

    /**
     * The runtime could not read the first stack's items back, so the return is held at once as AMBIGUOUS
     * (PAYLOAD_UNREADABLE). Every delivery for the player waits, and staff and the startup alert see it.
     * RETURNED_BY_STAFF closes it, records the decision and issues nothing again.
     */
    @Test void anUnreadableReturnIsHeldAndStaffCloseItAsReturnedByHand() throws SQLException {
        var custody = new ItemCustodyRepository(database);
        var q = started();
        var first = repository.settle(q.collectionId(), owner, run, confirmed(q), false, AT, COMPLETION).returns().getFirst();
        var shop = goods(9);
        custody.reserve(shop, AT);
        assertEquals(List.of(owner), repository.playersWithPendingReturns(10), "the returns are waiting");
        var held = custody.holdUnreadableReturn(first, run);
        assertEquals(ItemCustodyRepository.State.AMBIGUOUS, held.state());
        assertEquals(List.of("AMBIGUOUS", ItemCustodyRepository.PAYLOAD_UNREADABLE),
                rows("SELECT state,outcome_code FROM npc_item_custody WHERE request_nonce=?", first.requestNonce()).getFirst());
        assertEquals(java.util.Optional.of(first.requestNonce()), repository.heldDelivery(owner));
        assertFalse(custody.beginDelivery(shop, run, AT).newlyStarted(), "the held return stops every delivery");
        assertFalse(custody.beginDelivery(repository.pendingReturns(owner).getFirst(), run, AT).newlyStarted());
        assertEquals(List.of(), repository.playersWithPendingReturns(10), "the desk stops offering returns");
        var row = repository.listOpen(10).getFirst();
        assertEquals(List.of(first.requestNonce(), "AMBIGUOUS"), List.of(row.heldReturn(), row.heldReturnState()));
        assertEquals(held, custody.holdUnreadableReturn(first, run), "a second hold changes nothing");

        // Reissuing cannot help and the plugin never started it, so it cannot have been delivered.
        assertThrows(SQLException.class, () -> repository.resolveReturn(first, run, staff(ItemCustodyRepository.Decision.VERIFIED_DELIVERED, 20), AT));
        var byHand = staff(ItemCustodyRepository.Decision.RETURNED_BY_STAFF, 21);
        assertThrows(SQLException.class, () -> custody.reconcile(first, run, byHand), "only the offline return path closes it");
        assertEquals(0, count("npc_item_custody_resolutions"), "the refusals wrote nothing");
        var resolved = repository.resolveReturn(first, run, byHand, AT.plusSeconds(60));
        assertEquals(ItemCustodyRepository.State.DELIVERED, resolved.state());
        assertNull(resolved.reissued(), "staff gave the items back: nothing is issued again");
        assertEquals(List.of("DELIVERED", "RETURNED_BY_STAFF"),
                rows("SELECT state,outcome_code FROM npc_item_custody WHERE request_nonce=?", first.requestNonce()).getFirst());
        assertEquals(List.of(List.of("RETURNED_BY_STAFF", ItemCustodyRepository.PAYLOAD_UNREADABLE, "staff:case-21")),
                rows("SELECT decision,original_outcome,operator_ref FROM npc_item_custody_resolutions"));
        assertEquals(3, count("npc_item_custody"), "two return stacks and the shop order, nothing new");
        assertEquals(resolved, repository.resolveReturn(first, run, byHand, AT.plusSeconds(90)), "exact replay");
        assertThrows(SQLException.class, () -> repository.resolveReturn(first, run, staff(ItemCustodyRepository.Decision.RETURNED_BY_STAFF, 22), AT));
        assertTrue(repository.heldDelivery(owner).isEmpty());
        assertEquals(List.of(8), repository.pendingReturns(owner).stream().map(ItemCustody::quantity).toList(), "the potato stack is still owed");
        assertTrue(custody.beginDelivery(shop, run, AT).newlyStarted(), "deliveries of any source start again");
    }

    /** RETURNED_BY_STAFF never settles a return the plugin may have delivered, nor a shop delivery. */
    @Test void returnedByStaffIsOnlyForAReturnHeldAsUnreadable() throws SQLException {
        var custody = new ItemCustodyRepository(database);
        var first = heldFirstReturn(custody);
        var byHand = staff(ItemCustodyRepository.Decision.RETURNED_BY_STAFF, 23);
        assertThrows(SQLException.class, () -> repository.resolveReturn(first, run, byHand, AT), "held as DELIVERY_UNCERTAIN");
        assertThrows(SQLException.class, () -> custody.reconcile(first, run, byHand), "the commerce path never takes it");
        assertEquals(0, count("npc_item_custody_resolutions"));
        assertEquals("AMBIGUOUS", scalar("SELECT state FROM npc_item_custody WHERE request_nonce=?", first.requestNonce()));
        // The same held stack does settle with a decision that fits, so the refusals above are real.
        assertEquals(ItemCustodyRepository.State.DELIVERED,
                repository.resolveReturn(first, run, staff(ItemCustodyRepository.Decision.VERIFIED_DELIVERED, 24), AT).state());
    }

    /** Delivery counts on the row mean the plugin may have touched the inventory, so staff settle it from them instead. */
    @Test void returnedByStaffIsRefusedOnceDeliveryCountsExist() throws SQLException {
        var custody = new ItemCustodyRepository(database);
        var q = started();
        var first = repository.settle(q.collectionId(), owner, run, confirmed(q), false, AT, COMPLETION).returns().getFirst();
        assertEquals(ItemCustodyRepository.State.AMBIGUOUS, custody.holdUnreadableReturn(first, run).state());
        custody.recordDelivery(first, run, new com.prosperitysmp.npc.commerce.ItemDeliveryEvidence(owner, first.payload().sha256(),
                "b".repeat(64), "c".repeat(64), 0, first.quantity(), 0, true));
        assertThrows(SQLException.class, () -> repository.resolveReturn(first, run, staff(ItemCustodyRepository.Decision.RETURNED_BY_STAFF, 25), AT));
        assertEquals(0, count("npc_item_custody_resolutions"));
        assertEquals("AMBIGUOUS", scalar("SELECT state FROM npc_item_custody WHERE request_nonce=?", first.requestNonce()));
    }

    /** Only a waiting return is held this way, never a shop delivery, and never a second held row for one player. */
    @Test void onlyAWaitingReturnIsHeldAsUnreadable() throws SQLException {
        var custody = new ItemCustodyRepository(database);
        var q = started();
        var returns = repository.settle(q.collectionId(), owner, run, confirmed(q), false, AT, COMPLETION).returns();
        var shop = goods(10);
        custody.reserve(shop, AT);
        assertThrows(IllegalArgumentException.class, () -> custody.holdUnreadableReturn(shop, run));
        assertEquals("RESERVED", scalar("SELECT state FROM npc_item_custody WHERE request_nonce=?", shop.requestNonce()));
        // Another delivery for the player is running: it already holds the player, so this one stays waiting.
        assertTrue(custody.beginDelivery(returns.getFirst(), run, AT).newlyStarted());
        assertEquals(ItemCustodyRepository.State.RESERVED, custody.holdUnreadableReturn(returns.get(1), run).state());
        assertEquals("RESERVED", scalar("SELECT state FROM npc_item_custody WHERE request_nonce=?", returns.get(1).requestNonce()));
        // A running delivery is left as it is.
        assertEquals(ItemCustodyRepository.State.DELIVERY_INTENT, custody.holdUnreadableReturn(returns.getFirst(), run).state());
    }

    /** A return already given back is never held again: staff could then settle it a second time and issue it twice. */
    @Test void aDeliveredReturnIsNotHeldAgain() throws SQLException {
        var custody = new ItemCustodyRepository(database);
        var q = started();
        var returns = repository.settle(q.collectionId(), owner, run, confirmed(q), false, AT, COMPLETION).returns();
        var first = returns.getFirst();
        assertTrue(custody.beginDelivery(first, run, AT).newlyStarted());
        assertEquals(ItemCustodyRepository.State.DELIVERED, custody.recordDelivery(first, run,
                new com.prosperitysmp.npc.commerce.ItemDeliveryEvidence(owner, first.payload().sha256(), "b".repeat(64),
                        "c".repeat(64), 0, first.quantity(), 0, true)).state());
        var delivered = rows("SELECT state,outcome_code FROM npc_item_custody WHERE request_nonce=?", first.requestNonce()).getFirst();
        assertEquals(ItemCustodyRepository.State.DELIVERED, custody.holdUnreadableReturn(first, run).state());
        assertEquals(delivered, rows("SELECT state,outcome_code FROM npc_item_custody WHERE request_nonce=?", first.requestNonce()).getFirst());
        assertTrue(repository.heldDelivery(owner).isEmpty());
        // The stack still waiting is held by the same call.
        assertEquals(ItemCustodyRepository.State.AMBIGUOUS, custody.holdUnreadableReturn(returns.get(1), run).state());
    }

    /**
     * The last waiting return's start commits but the runtime never hears back. A bare started intent is neither
     * waiting nor held, so the desk would show nothing while every delivery stays blocked. The runtime reads the row
     * back, sees it is no longer RESERVED, and holds it as uncertain so the desk and staff list it.
     */
    @Test void aReturnWhoseStartCommittedUnheardIsHeldWhereTheDeskAndStaffSeeIt() throws SQLException {
        var custody = new ItemCustodyRepository(database);
        var q = started();
        var returns = repository.settle(q.collectionId(), owner, run, confirmed(q), false, AT, COMPLETION).returns();
        var first = returns.getFirst(); var last = returns.get(1);
        assertTrue(custody.beginDelivery(first, run, AT).newlyStarted());
        custody.recordDelivery(first, run, new com.prosperitysmp.npc.commerce.ItemDeliveryEvidence(owner, first.payload().sha256(),
                "b".repeat(64), "c".repeat(64), 0, first.quantity(), 0, true));
        // Before the start, the last stack reads back RESERVED and is offered.
        assertEquals(ItemCustodyRepository.State.RESERVED, custody.find(owner, last.requestNonce(), AT).orElseThrow().state());
        assertEquals(List.of(last.requestNonce()), repository.pendingReturns(owner).stream().map(ItemCustody::requestNonce).toList());
        assertTrue(custody.beginDelivery(last, run, AT).newlyStarted(), "committed; the runtime never heard");
        assertEquals(ItemCustodyRepository.State.DELIVERY_INTENT, custody.find(owner, last.requestNonce(), AT).orElseThrow().state());
        assertTrue(repository.pendingReturns(owner).isEmpty(), "the desk would say nothing is waiting");
        assertTrue(repository.heldDelivery(owner).isEmpty(), "a bare intent is not listed as held");
        assertEquals(ItemCustodyRepository.State.AMBIGUOUS,
                custody.markAmbiguous(last, run, ItemCustodyRepository.Uncertainty.DELIVERY_UNCERTAIN).state());
        assertEquals(java.util.Optional.of(last.requestNonce()), repository.heldDelivery(owner), "now held where staff see it");
    }

    // ---- money: zero rows, with a cash repair showing the counters can move -----------------------------------

    @Test void itemRepairWritesNoMoneyRowsWhileACashRepairWritesOneOfEach() throws Exception {
        var before = ledgerCounts();
        long[] funding = properties.fundingOf(property);
        var q = started();
        assertEquals(State.APPLIED, repository.settle(q.collectionId(), owner, run, confirmed(q), true, AT, COMPLETION).state());
        assertEquals(before, ledgerCounts(), "item repair: no economy event, intent, transfer attempt or observation");
        assertArrayEquals(funding, properties.fundingOf(property));
        // A cash repair on a second home: the same counters do move.
        UUID cashHome = UUID.randomUUID();
        long cashIncident = home(cashHome, owner);
        var purchases = purchases(() -> true);
        var waiting = purchases.enqueue(purchases.quoteRepair(UUID.randomUUID(), cashHome, owner, 0, cashIncident, DAY));
        pay(waiting.eventId(), waiting.quotedCents());
        var after = ledgerCounts();
        assertEquals(before.get(0) + 1, after.get(0), "economy events");
        assertEquals(before.get(1) + 1, after.get(1), "purchase intents");
        assertEquals(before.get(2) + 1, after.get(2), "transfer attempts");
        assertEquals(before.get(3) + 2, after.get(3), "transfer observations");
        assertEquals(PurchaseRepository.State.APPLIED, purchases.complete(waiting.eventId()).orElseThrow().state());
        assertEquals(SEED, properties.fundingOf(cashHome)[0]);
        assertEquals(2_000, properties.fundingOf(cashHome)[1], "only cash adds a sink credit");
        assertEquals(0, properties.fundingOf(property)[1]);
    }

    // ---- the cash block (item-repairs.block-cash-while-items-held) -------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cashRepairWaitsForAHeldHandInBeforeAnyMoney(boolean held) throws SQLException {
        var blocking = purchases(() -> true);
        PurchaseRequest earlier = blocking.quoteRepair(UUID.randomUUID(), property, owner, 0, incident, DAY);
        var q = started();
        if (held) repository.markAmbiguous(q.collectionId(), owner, run, Uncertainty.TAKE_FAILED);
        var ledger = ledgerCounts();
        assertThrows(PurchaseRepository.ItemsPendingException.class,
                () -> blocking.quoteRepair(UUID.randomUUID(), property, owner, 0, incident, DAY));
        assertThrows(PurchaseRepository.ItemsPendingException.class, () -> blocking.enqueue(earlier));
        assertEquals(ledger, ledgerCounts(), "refused before any intent or debit");
        // Flag off: cash may go ahead.
        var open = purchases(() -> false);
        assertDoesNotThrow(() -> open.enqueue(open.quoteRepair(UUID.randomUUID(), property, owner, 0, incident, DAY)));
        assertEquals(ledger.get(1) + 1, ledgerCounts().get(1));
    }

    @Test void cashBlockIsInertWithNoHandInRows() throws SQLException {
        var blocking = purchases(() -> true);
        var plain = purchases(() -> false);
        var a = blocking.quoteRepair(UUID.randomUUID(), property, owner, 0, incident, DAY);
        var b = plain.quoteRepair(UUID.randomUUID(), property, owner, 0, incident, DAY);
        assertEquals(a.repair(), b.repair());
        assertEquals(PurchaseRepository.State.WAITING, blocking.enqueue(a).state());
        // A finished hand-in on another incident does not block either.
        assertEquals(0, count("npc_item_repair_collections"));
    }

    // ---- fixtures --------------------------------------------------------------------------------------------

    private PurchaseRepository purchases(java.util.function.BooleanSupplier block) {
        return new PurchaseRepository(database, new UUID(0, 3599), Clock.fixed(AT, ZoneOffset.UTC), ZoneId.of("America/Chicago"), 4, block);
    }

    private void pay(long eventId, long cents) throws SQLException {
        var journal = new TransferJournal(database, new UUID(0, 3599));
        var handle = new TransferJournal.Handle(UUID.randomUUID(), eventId, UUID.randomUUID(), "a".repeat(64));
        assertTrue(journal.beginDebit(handle).newlyStarted());
        double dollars = cents / 100.0;
        journal.recordDebit(handle, new Observation(true, true, dollars, 1_000.0, 1_000.0 - dollars, false));
        assertTrue(journal.beginCredit(handle).newlyStarted());
        journal.recordCredit(handle, new Observation(true, true, dollars, 2_000.0, 2_000.0 + dollars, false));
        LocalDateTime paid = LocalDateTime.ofInstant(AT.minusSeconds(120), ZoneOffset.UTC);
        execute("UPDATE npc_economy_events SET created_at=?,claimed_at=?,finalized_at=? WHERE event_id=?",
                paid.minusSeconds(5), paid.minusSeconds(4), paid, eventId);
        execute("UPDATE npc_purchase_intents SET created_at=? WHERE event_id=?", paid.minusSeconds(5), eventId);
        execute("UPDATE npc_transfer_attempts SET created_at=?,credit_intent_at=?,updated_at=? WHERE event_id=?",
                paid.minusSeconds(4), paid.minusSeconds(2), paid, eventId);
        execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                + "SET o.observed_at=CASE WHEN o.leg='DEBIT' THEN ? ELSE ? END WHERE a.event_id=?",
                paid.minusSeconds(3), paid.minusSeconds(1), eventId);
    }

    private void mutate(String change) throws SQLException {
        switch (change) {
            case "owner" -> execute("UPDATE npc_properties SET owner_uuid=? WHERE property_id=?", UUID.randomUUID(), property);
            case "epoch" -> execute("UPDATE npc_properties SET ownership_epoch=ownership_epoch+1 WHERE property_id=?", property);
            case "disabled" -> properties.setEnabled(property, false);
            case "resolved" -> execute("UPDATE npc_incidents SET resolved_at=?,resolver_uuid=? WHERE incident_id=?",
                    LocalDateTime.ofInstant(CREATED.plusSeconds(60), ZoneOffset.UTC), owner, incident);
            case "tenant-version" -> execute("UPDATE npc_tenants SET row_version=row_version+1 WHERE tenant_id=?", occupants.getFirst());
            default -> throw new AssertionError(change);
        }
    }

    /** events, intents, transfer attempts, transfer observations. */
    private static List<Long> ledgerCounts() throws SQLException {
        return List.of(count("npc_economy_events"), count("npc_purchase_intents"), count("npc_transfer_attempts"),
                count("npc_transfer_observations"));
    }
    private static String hex(UUID id) { return HexFormat.of().formatHex(Uuids.toBytes(id)); }
    static long count(String table) throws SQLException { return Long.parseLong(scalar("SELECT COUNT(*) FROM " + table)); }
    static String scalar(String sql, Object... values) throws SQLException {
        var result = rows(sql, values);
        assertEquals(1, result.size(), sql);
        return result.getFirst().getFirst();
    }
    static String scalarOrNull(String sql, Object... values) throws SQLException { return scalar(sql, values); }
    static List<List<String>> rows(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5); bind(ps, values);
            try (ResultSet rs = ps.executeQuery()) {
                var result = new ArrayList<List<String>>();
                while (rs.next()) {
                    var row = new ArrayList<String>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        row.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value == null ? null : rs.getString(i));
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }
    static void execute(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5); bind(ps, values); ps.execute();
        }
    }
    static void bind(PreparedStatement ps, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else ps.setObject(i + 1, values[i]);
        }
    }
}
