package com.prosperitysmp.npc.it;

import com.prosperitysmp.npc.market.HousingDecisionPlan;
import com.prosperitysmp.npc.market.HousingDecisionPlan.ColdStartPolicy;
import com.prosperitysmp.npc.market.HousingDecisionRepository;
import com.prosperitysmp.npc.market.HousingDecisionRepository.Options;
import com.prosperitysmp.npc.market.HousingMoveRequest.Policy;
import com.prosperitysmp.npc.market.HousingPolicy;
import com.prosperitysmp.npc.market.HousingRegistry;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.town.TownContractRepository.Code;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Daily matching with the partnership rank perk (modeled on AdvertisingRankIT). The bonus orders a partner's home
 * ahead of an equal one only while advertising rank and the partner rank bonus are both on, never past 12 points,
 * and only for homes the partner owns now.
 */
@Timeout(120)
class PartnerRankIT extends ContractItBase {
    private static final UUID HOME = new UUID(0, 6010), OTHER = new UUID(0, 6011);
    private static final UUID SEEKER = new UUID(0, 6020);
    private static final Instant FROM = NOW.minus(Duration.ofDays(20));
    private static final Policy POLICY = new Policy(10, 5, 4, 7);
    private UUID plain, partner;

    @BeforeEach void seedMatching() throws SQLException {
        execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (1)");
        plain = UUID.randomUUID();
        partner = UUID.randomUUID();
        home(HOME, plain);
        home(OTHER, partner);
        assertTrue(residents.register(ResidentCatalogue.draft(SEEKER, "Resident Seeker")));
        assertTrue(new HousingRegistry(database).admit(SEEKER, new HousingPolicy(WORLD, "downtown", "room",
                HousingPolicy.Tier.STANDARD, 1200, 40), DAY.minusDays(30)));
        // The partner owns OTHER and partners with Nico (rank 2) through the real hand-over.
        trusted(NICO, partner);
        kept(NICO, partner, 2);
        assertEquals(Code.PARTNER_STARTED, partnerStart(partner, NICO, NOW.minusSeconds(3600), DAY).code());
    }

    private static void home(UUID id, UUID owner) throws SQLException {
        execute("""
                INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,
                  reference_rent_cents,asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled,row_version)
                VALUES (?,?,?,'downtown','room',4,1000,1200,?,3,40,TRUE,11)
                """, id, WORLD, "fixture-" + id, owner);
    }

    private static Options options(boolean advertising, Map<UUID, Integer> partnerCaps) {
        return new Options(FROM, 7, POLICY, ColdStartPolicy.PROVISIONAL_50_V1, 8, 10000, Duration.ofSeconds(30), false,
                advertising, partnerCaps);
    }

    private static HousingDecisionPlan read(Options options) throws SQLException {
        return new HousingDecisionRepository(database, Clock.fixed(NOW, ZONE), ZONE, 4).read(DAY, options);
    }

    private static UUID first(Options options) throws SQLException { return read(options).requests().getFirst().propertyId(); }

    @Test void thePartnerHomeGoesFirstOnlyWithBothFlagsOn() throws SQLException {
        var before = data();
        // Positive control: advertising alone orders as before (no advertising here, so the old order).
        assertEquals(HOME, first(options(true, Map.of())));
        assertEquals(HOME, first(options(false, Map.of())));
        // Mutation guard: ignoring advertising-rank-enabled makes this parent-off case pick OTHER.
        assertEquals(HOME, first(options(false, Map.of(NICO, 2))), "advertising rank off: no partner bonus");
        assertEquals(OTHER, first(options(true, Map.of(NICO, 2))));
        var off = read(options(true, Map.of()));
        var on = read(options(true, Map.of(NICO, 2)));
        assertEquals(off.ranks(), on.ranks(), "the score is untouched; only the order changes");
        assertEquals(before, data(), "matching reads never write");
    }

    @Test void theTotalBonusIsCappedAtTwelve() throws SQLException {
        // Both homes at advertising level 3 (12 points). The partner's 2 points add nothing past the cap.
        execute("INSERT INTO npc_upgrades(property_id,upgrade_type,level) VALUES (?,'ADVERTISING',3)", HOME);
        execute("INSERT INTO npc_upgrades(property_id,upgrade_type,level) VALUES (?,'ADVERTISING',3)", OTHER);
        assertEquals(HOME, first(options(true, Map.of(NICO, 2))), "12 + 2 is still 12: the old tie order");
        // One level below the top, the partner points count again.
        execute("UPDATE npc_upgrades SET level=2 WHERE property_id=?", OTHER);
        execute("UPDATE npc_upgrades SET level=2 WHERE property_id=?", HOME);
        assertEquals(OTHER, first(options(true, Map.of(NICO, 2))));
    }

    @Test void aSoldHomeLosesTheBonus() throws SQLException {
        assertEquals(OTHER, first(options(true, Map.of(NICO, 2))));
        execute("UPDATE npc_properties SET owner_uuid=?, ownership_epoch=4, row_version=12 WHERE property_id=?", UUID.randomUUID(), OTHER);
        assertEquals(HOME, first(options(true, Map.of(NICO, 2))), "the buyer inherits nothing");
    }

    @Test void configLoweredToZeroOrAnEndedPartnershipGivesNothing() throws SQLException {
        assertEquals(HOME, first(options(true, Map.of(ELLIS, 4))), "Nico has no rank perk in config");
        partnerships.end(partner, com.prosperitysmp.npc.town.Partnership.EndReason.OWNER_ENDED, NOW, DAY);
        assertEquals(HOME, first(options(true, Map.of(NICO, 2))));
    }
}
