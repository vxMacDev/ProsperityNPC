package com.prosperitysmp.npc.it;

import com.prosperitysmp.npc.market.HousingDecisionPlan.ColdStartPolicy;
import com.prosperitysmp.npc.market.HousingDecisionRepository;
import com.prosperitysmp.npc.market.HousingDecisionRepository.Options;
import com.prosperitysmp.npc.market.HousingMoveRequest;
import com.prosperitysmp.npc.market.HousingMoveRequest.Policy;
import com.prosperitysmp.npc.market.HousingPolicy;
import com.prosperitysmp.npc.market.HousingRegistry;
import com.prosperitysmp.npc.property.OwnerDeskRepository;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Homes enrolled under another property backend (V45) are never matched with residents and never shown on the owner
 * desk, while the configured backend's homes are. Real MariaDB with every migration.
 */
@Timeout(120)
class HousingBackendMatchIT extends ContractItBase {
    private static Options options() {
        return new Options(NOW.minus(Duration.ofDays(20)), 7, new Policy(10, 5, 4, 7), ColdStartPolicy.PROVISIONAL_50_V1, 8, 10000,
                Duration.ofSeconds(30), false, true, Map.of());
    }

    private static List<UUID> matched(String backend) throws Exception {
        return new HousingDecisionRepository(database, Clock.fixed(NOW, ZONE), ZONE, 4, 0, backend).read(DAY, options())
                .requests().stream().map(HousingMoveRequest::propertyId).toList();
    }

    private static OwnerDeskRepository desk(String backend) {
        return new OwnerDeskRepository(database, Clock.fixed(NOW, ZONE), ZONE, 4, () -> false, () -> false, () -> 0L,
                () -> OwnerDeskRepository.PartnerConfig.OFF).backendId(backend);
    }

    private UUID seekerAndHome(UUID owner) throws Exception {
        execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (1)");
        UUID home = home(owner);
        UUID seeker = UUID.randomUUID();
        assertTrue(residents.register(ResidentCatalogue.draft(seeker, "Resident Seeker")));
        assertTrue(new HousingRegistry(database).admit(seeker, new HousingPolicy(WORLD, "downtown", "room",
                HousingPolicy.Tier.STANDARD, 1200, 40), DAY.minusDays(30)));
        return home;
    }

    @Test void aHomeEnrolledUnderAnotherBackendIsNeverMatched() throws Exception {
        UUID home = seekerAndHome(UUID.randomUUID());
        assertEquals("arm", scalar("SELECT backend_id FROM npc_properties WHERE property_id=?", home));
        assertEquals(List.of(home), matched("arm"), "the configured backend's home is matched");
        assertEquals(List.of(), matched("worldguard-listed"), "after a switch the old ARM home is left out");
        assertEquals(List.of(home), matched(null), "no backend given: no filter");

        execute("UPDATE npc_properties SET backend_id='worldguard-listed' WHERE property_id=?", home);
        assertEquals(List.of(home), matched("worldguard-listed"));
        assertEquals(List.of(), matched("griefprevention"));
    }

    @Test void theOwnerDeskShowsOnlyTheConfiguredBackendsHome() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID home = home(owner);
        String region = "contract-" + home;
        assertTrue(desk("arm").read(WORLD, region, owner, DAY).isPresent());
        assertTrue(desk(null).read(WORLD, region, owner, DAY).isPresent(), "no backend given: no filter");
        assertTrue(desk("worldguard-listed").read(WORLD, region, owner, DAY).isEmpty(),
                "an owner standing in their old ARM home after a switch sees nothing");
    }
}
