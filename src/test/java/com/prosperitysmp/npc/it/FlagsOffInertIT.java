package com.prosperitysmp.npc.it;

import com.prosperitysmp.npc.commerce.CommerceCatalogue;
import com.prosperitysmp.npc.market.HousingDecisionPlan.ColdStartPolicy;
import com.prosperitysmp.npc.market.HousingDecisionRepository;
import com.prosperitysmp.npc.market.HousingDecisionRepository.Options;
import com.prosperitysmp.npc.market.HousingMoveRequest.Policy;
import com.prosperitysmp.npc.market.HousingPolicy;
import com.prosperitysmp.npc.market.HousingRegistry;
import com.prosperitysmp.npc.property.OwnerDeskRepository;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentDialogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.town.ContractCatalogue;
import com.prosperitysmp.npc.town.PartnerRateReader;
import com.prosperitysmp.npc.town.TownContractRepository.Code;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * With the contracts and partnerships flags off (the shipped config), the read paths a running server takes write
 * nothing to the contract and partnership tables and change no price, rank or desk line. Even rows left from an
 * earlier run give no perk while partnerships are off. With contracts on, a contract row is written, so the zero
 * counts mean something.
 */
@Timeout(120)
class FlagsOffInertIT extends ContractItBase {
    private static final List<String> NEW_TABLES = List.of("npc_town_contract_players", "npc_town_contracts",
            "npc_contract_handover_evidence", "npc_contract_handover_resolutions", "npc_partnerships", "npc_partnership_renewals");

    private static ContractCatalogue shipped() throws Exception {
        try (var in = FlagsOffInertIT.class.getResourceAsStream("/config.yml")) {
            var config = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            return ContractCatalogue.load(config::get, CAST, name -> true);
        }
    }

    private static int newRows() throws SQLException {
        int total = 0;
        for (String table : NEW_TABLES) total += count(table);
        return total;
    }

    private static Options options(Map<UUID, Integer> caps) {
        return new Options(NOW.minus(Duration.ofDays(20)), 7, new Policy(10, 5, 4, 7), ColdStartPolicy.PROVISIONAL_50_V1, 8, 10000,
                Duration.ofSeconds(30), false, true, caps);
    }

    private static OwnerDeskRepository desk(ContractCatalogue catalogue, boolean on) {
        return new OwnerDeskRepository(database, Clock.fixed(NOW, ZONE), ZONE, 4, () -> false, () -> false, () -> 0L,
                () -> on ? new OwnerDeskRepository.PartnerConfig(true, false, catalogue::configPerks) : OwnerDeskRepository.PartnerConfig.OFF);
    }

    private UUID matchingFixture(UUID owner) throws SQLException {
        execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (1)");
        UUID home = home(owner);
        UUID seeker = UUID.randomUUID();
        assertTrue(residents.register(ResidentCatalogue.draft(seeker, "Resident Seeker")));
        assertTrue(new HousingRegistry(database).admit(seeker, new HousingPolicy(WORLD, "downtown", "room",
                HousingPolicy.Tier.STANDARD, 1200, 40), DAY.minusDays(30)));
        return home;
    }

    @Test void everythingOffWritesNothingAndChangesNothing() throws Exception {
        var catalogue = shipped();
        assertFalse(catalogue.anyOn());
        assertEquals(Map.of(), catalogue.rankCaps());
        UUID owner = UUID.randomUUID();
        UUID home = matchingFixture(owner);
        // Startup recovery, daily matching with advertising rank on, the owner desk and the commerce rate.
        assertTrue(contracts.recoverStaleIntents(100).isEmpty());
        var plan = new HousingDecisionRepository(database, Clock.fixed(NOW, ZONE), ZONE, 4).read(DAY, options(catalogue.rankCaps()));
        assertEquals(home, plan.requests().getFirst().propertyId());
        var data = desk(catalogue, false).read(WORLD, "contract-" + home, owner, DAY).orElseThrow();
        assertTrue(data.partner().isEmpty());
        var reader = new PartnerRateReader(partnerships, () -> catalogue, () -> false, () -> NOW);
        assertEquals(0, reader.feeDiscountPct(owner, ELLIS, DAY));
        for (var entry : CommerceCatalogue.entries(ResidentProfile.Occupation.SHOPKEEPER)) assertEquals(entry.cost(123), entry.cost(123, 0));
        var mara = CAST.byFirstName("mara").orElseThrow();
        var state = residents.readRelationship(mara.id(), owner);
        assertEquals("I prefer to know people before discussing business. There are no offers available.",
                ResidentDialogue.reply(mara, state, ResidentDialogue.Topic.BUSINESS, 1000, 0, null, catalogue.partnershipsEnabled()).line());
        assertEquals(0, newRows(), "no row in any of the six contract and partnership tables");

        // With contracts on, the same database writes one contract row, so the zero count above is real.
        assertEquals(Code.ACCEPTED, contracts.accept(contract("mara-wall-stone"), owner, NOW, DAY, 1).code());
        assertEquals(1, count("npc_town_contracts"));
    }

    @Test void rowsFromAnEarlierRunGiveNoPerkWhileOff() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID home = matchingFixture(owner);
        trusted(NICO, owner); kept(NICO, owner, 2);
        assertEquals(Code.PARTNER_STARTED, partnerStart(owner, NICO, NOW.minusSeconds(600), DAY).code());
        var off = shipped();
        // Desk: no line while off; the line appears with partnerships on.
        assertTrue(desk(off, false).read(WORLD, "contract-" + home, owner, DAY).orElseThrow().partner().isEmpty());
        var on = desk(ON, true).read(WORLD, "contract-" + home, owner, DAY).orElseThrow().partner().orElseThrow();
        assertEquals("Partner: Nico Reed until " + DAY.plusDays(6) + ".", on.line(), "rank perk not live: no rank sentence");
        // Matching: the empty caps (off) give no partner points, so the bonus is advertising only.
        var plan = new HousingDecisionRepository(database, Clock.fixed(NOW, ZONE), ZONE, 4).read(DAY, options(off.rankCaps()));
        assertEquals(1, plan.requests().size());
        assertEquals(0, PartnerRankProbe.partnerPoints(database, DAY, off.rankCaps(), owner));
        assertEquals(2, PartnerRankProbe.partnerPoints(database, DAY, Map.of(NICO, 2), owner), "with partnerships on, it counts");
        // Commerce: no partner rate while off.
        assertEquals(0, new PartnerRateReader(partnerships, () -> off, () -> false, () -> NOW).feeDiscountPct(owner, NICO, DAY));
    }

    /** The same read the housing snapshot uses. */
    static final class PartnerRankProbe {
        static int partnerPoints(com.prosperitysmp.npc.db.Database db, java.time.LocalDate day, Map<UUID, Integer> caps, UUID owner)
                throws SQLException {
            if (caps.isEmpty()) return 0;
            try (var c = db.getConnection()) {
                return com.prosperitysmp.npc.town.PartnershipRepository.rankBonusByOwner(c, day, caps).getOrDefault(owner, 0);
            }
        }
    }
}
