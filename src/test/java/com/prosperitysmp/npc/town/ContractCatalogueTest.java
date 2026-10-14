package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.resident.RelationshipMemory.Stage;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

class ContractCatalogueTest {
    private static final ResidentCast CAST = ResidentCast.builtIn();
    private static final Predicate<String> ITEMS = name -> !name.equals("NOT_AN_ITEM");
    private static final UUID MARA = ResidentCatalogue.idFor("mara-vale");
    private static final UUID ELLIS = ResidentCatalogue.idFor("ellis-ward");
    private static final UUID NICO = ResidentCatalogue.idFor("nico-reed");

    private static YamlConfiguration shipped() throws Exception {
        try (var in = ContractCatalogueTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in);
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static ContractCatalogue load(Map<String, Object> values) {
        return ContractCatalogue.load(values::get, CAST, ITEMS);
    }

    private static Map<String, Object> entry(String id, UUID giver) {
        var map = new HashMap<String, Object>(Map.of("id", id, "giver", giver.toString(), "title", "Wall stone",
                "description", "Bring me cobblestone.", "material", "COBBLESTONE", "quantity", 64,
                "minimum-stage", "STRANGER", "duration-minutes", 1440));
        return map;
    }

    @Test void theShippedConfigLoadsSixContractsAndThreeOffersAllOff() throws Exception {
        var config = shipped();
        var catalogue = ContractCatalogue.load(config::get, CAST, ITEMS);
        // All 6 shipped contracts and 3 offers load, with nothing skipped.
        assertEquals(6, catalogue.definitions().size());
        assertEquals(3, catalogue.offersByResident().size());
        assertEquals(0, catalogue.skippedCount());
        assertEquals(List.of(), catalogue.warnings());
        assertFalse(catalogue.contractsEnabled());
        assertFalse(catalogue.partnershipsEnabled());
        assertFalse(catalogue.rankBonusEnabled());
        assertTrue(catalogue.requireSurvival());
        assertEquals(1, catalogue.perPlayerPerDay());
        assertEquals(7, catalogue.termDays());
        assertEquals(2, catalogue.renewWindowDays());
        assertEquals(4, catalogue.maxPartners());
        assertEquals(Stage.TRUSTED, catalogue.partnerMinimumStage());
        assertEquals(2, catalogue.minContracts());
        // The shipped file and the built-in defaults are the same content.
        var defaults = load(Map.of());
        assertEquals(defaults.definitions(), catalogue.definitions());
        assertEquals(defaults.offersByResident(), catalogue.offersByResident());
        // The defaults use the three built-in cast UUIDs.
        assertEquals("f23586b0-11ff-320e-874e-3bb29065d402", MARA.toString());
        assertEquals("739e2063-a802-3e5a-8dc1-a9c03ed761a8", ELLIS.toString());
        assertEquals("716af3b1-66c6-3d62-b6fe-601e2b013ad9", NICO.toString());
        assertEquals(ContractDefinition.Kind.DELIVER, catalogue.find("nico-coal-run").orElseThrow().kind());
        assertEquals(MARA, catalogue.find("nico-coal-run").orElseThrow().recipientId());
        assertEquals(ContractDefinition.Kind.SUPPLY, catalogue.find("mara-wall-stone").orElseThrow().kind());
        assertEquals(40, catalogue.partnerOffer(ELLIS).orElseThrow().feeDiscountPct());
        assertEquals(2, catalogue.partnerOffer(NICO).orElseThrow().rankBonusPoints());
        assertEquals(2, catalogue.offers(MARA).size());
    }

    @Test void anUnknownResidentIsSkippedWithOneWarning() {
        var list = List.of(entry("good", MARA), entry("stranger", UUID.randomUUID()));
        var catalogue = load(Map.of("town.contracts.definitions", list));
        assertEquals(1, catalogue.definitions().size());
        assertEquals(1, catalogue.skippedCount());
        assertEquals(1, catalogue.warnings().size());
        assertTrue(catalogue.warnings().getFirst().contains("not a resident on this server"));
    }

    @Test void aDuplicateIdIsRejected() {
        // Without the duplicate check both entries would load.
        var catalogue = load(Map.of("town.contracts.definitions", List.of(entry("same", MARA), entry("same", ELLIS))));
        assertEquals(1, catalogue.definitions().size());
        assertEquals(MARA, catalogue.definitions().getFirst().giverId());
        assertEquals(1, catalogue.skippedCount());
    }

    @Test void atMostSixtyFourContractsLoad() {
        var list = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 66; i++) list.add(entry("c" + i, MARA));
        var catalogue = load(Map.of("town.contracts.definitions", list));
        assertEquals(64, catalogue.definitions().size());
        assertEquals(2, catalogue.skippedCount());
    }

    @Test void badEntriesAreSkippedOneByOne() {
        var badMaterial = entry("bad-material", MARA); badMaterial.put("material", "NOT_AN_ITEM");
        var shortDuration = entry("short", MARA); shortDuration.put("duration-minutes", 20);
        var bigQuantity = entry("big", MARA); bigQuantity.put("quantity", 300);
        var stringQuantity = entry("text", MARA); stringQuantity.put("quantity", "64");
        var stage = entry("stage", MARA); stage.put("minimum-stage", "BEST_FRIEND");
        var catalogue = load(Map.of("town.contracts.definitions",
                List.of(badMaterial, shortDuration, bigQuantity, stringQuantity, stage, "not a map", entry("ok", NICO))));
        assertEquals(List.of("ok"), catalogue.definitions().stream().map(ContractDefinition::id).toList());
        assertEquals(6, catalogue.skippedCount());
    }

    @Test void oneOfferPerResident() {
        var first = new HashMap<String, Object>(Map.of("resident", ELLIS.toString(), "material", "IRON_INGOT", "quantity", 16,
                "fee-discount-percent", 40, "rank-bonus-points", 0, "pitch", "First."));
        var second = new HashMap<String, Object>(first); second.put("pitch", "Second.");
        var tooMuch = new HashMap<String, Object>(first); tooMuch.put("resident", MARA.toString()); tooMuch.put("fee-discount-percent", 61);
        var catalogue = load(Map.of("town.partnerships.offers", List.of(first, second, tooMuch)));
        assertEquals(1, catalogue.offersByResident().size());
        assertEquals("First.", catalogue.partnerOffer(ELLIS).orElseThrow().pitch());
        assertEquals(2, catalogue.skippedCount());
    }

    @Test void aBadLimitHoldsThatFeatureOff() {
        var held = load(Map.of("town.contracts.enabled", true, "town.partnerships.enabled", true,
                "town.contracts.per-player-per-day", 4, "town.partnerships.term-days", 31));
        assertFalse(held.contractsEnabled());
        assertFalse(held.partnershipsEnabled());
        assertEquals(2, held.warnings().size());
        var window = load(Map.of("town.partnerships.enabled", true, "town.partnerships.term-days", 3,
                "town.partnerships.renew-window-days", 4));
        assertFalse(window.partnershipsEnabled());
        var stranger = load(Map.of("town.partnerships.enabled", true, "town.partnerships.minimum-stage", "STRANGER"));
        assertFalse(stranger.partnershipsEnabled());
        var worlds = load(Map.of("town.contracts.enabled", true, "town.contracts.handover-worlds", "world"));
        assertFalse(worlds.contractsEnabled());
        var ok = load(Map.of("town.contracts.enabled", true, "town.partnerships.enabled", true,
                "town.contracts.handover-worlds", List.of("world", "world_nether")));
        assertTrue(ok.contractsEnabled());
        assertTrue(ok.partnershipsEnabled());
        assertTrue(ok.worldAllowed("world"));
        assertFalse(ok.worldAllowed("build"));
        assertTrue(load(Map.of()).worldAllowed("anything"));
    }

    @Test void safetyCheckFailsTowardOnAndFlagsOnlyTurnOnWithTrue() {
        assertTrue(load(Map.of("town.contracts.require-survival", "no")).requireSurvival());
        assertFalse(load(Map.of("town.contracts.require-survival", false)).requireSurvival());
        assertFalse(load(Map.of("town.contracts.enabled", "true")).contractsEnabled());
    }

    @Test void perksAndRankCapsFollowTheFlags() {
        var off = load(Map.of());
        assertEquals(PartnerPerks.NONE, off.configPerks(ELLIS));
        assertEquals(Map.of(), off.rankCaps());
        var on = load(Map.of("town.partnerships.enabled", true));
        assertEquals(new PartnerPerks(40, 0), on.configPerks(ELLIS));
        assertEquals(Map.of(), on.rankCaps(), "rank bonus still off");
        var rank = load(Map.of("town.partnerships.enabled", true, "town.partnerships.rank-bonus-enabled", true));
        assertEquals(Map.of(NICO, 2), rank.rankCaps());
    }

    @Test void effectivePerkIsTheLowerOfSnapshotAndConfig() {
        assertEquals(new PartnerPerks(25, 1), PartnerPerks.effective(new PartnerPerks(40, 1), new PartnerPerks(25, 4)));
        assertEquals(PartnerPerks.NONE, PartnerPerks.effective(new PartnerPerks(40, 2), PartnerPerks.NONE));
        assertTrue(new PartnerPerks(0, 2).live(true));
        assertFalse(new PartnerPerks(0, 2).live(false));
        assertTrue(new PartnerPerks(10, 0).live(false));
    }
}
