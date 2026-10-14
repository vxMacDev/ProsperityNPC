package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.appearance.ResidentAppearanceCatalogue;
import com.prosperitysmp.npc.appearance.TestSkins;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import net.citizensnpcs.api.npc.NPC;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The swap order and state carry-over, against a fake host. Not a Citizens or client test. */
class OutfitSwapTest {
    private static final String EVERYDAY = "resident/v1/mara-vale/everyday";
    private static final String LEISURE = "resident/v1/mara-vale/leisure";
    private static final ResidentAppearanceCatalogue.Selection WANTS_LEISURE = new ResidentAppearanceCatalogue.Selection(1, LEISURE,
            ResidentAppearanceCatalogue.Status.REQUESTED, Optional.of(new ResidentAppearanceCatalogue.Applied(LEISURE,
                    ResidentAppearanceCatalogue.citizensKey(LEISURE), TestSkins.synthetic('c'))));

    private static NPC npc(String label) {
        return (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "toString" -> label;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class Host implements RenderManager.OutfitHost {
        final List<String> events = new ArrayList<>();
        final Set<UUID> talking = new HashSet<>();
        double nearest = Double.POSITIVE_INFINITY;
        boolean failSpawn;
        int spawned;

        @Override public ResidentAppearanceCatalogue.Selection select(RenderManager.Render r) { return WANTS_LEISURE; }
        int scans;
        @Override public double nearestViewer(RenderManager.Render r) { scans++; return nearest; }
        @Override public boolean conversationOpen(UUID resident) { return talking.contains(resident); }
        @Override public NPC spawnReplacement(RenderManager.Render r, ResidentAppearanceCatalogue.Applied applied) {
            events.add("spawn " + applied.assetKey() + " while " + r.npc);
            return failSpawn ? null : npc("new-" + (++spawned));
        }
        @Override public void despawn(NPC npc) { events.add("despawn " + npc); }
        @Override public void resumeWalk(RenderManager.Render r, long now) { events.add("resume " + r.npc + " at " + r.pathIdx); }
    }

    private static RenderManager.Render render(String label) {
        var r = new RenderManager.Render(ConversationPause.Policy.LEGACY);
        r.npc = npc(label);
        r.appliedKey = EVERYDAY;
        r.profile = ResidentCatalogue.betaCast().getFirst();
        return r;
    }

    @Test void theNewAvatarSpawnsBeforeTheOldOneGoesAndTheWalkCarriesOver() {
        var host = new Host();
        var r = render("old");
        r.path = List.of(10L, 11L, 12L);
        r.pathIdx = 2;
        UUID property = UUID.randomUUID();
        r.propertyId = property;
        r.ownershipEpoch = 7;
        UUID resident = UUID.randomUUID();
        Map<UUID, RenderManager.Render> rendered = new HashMap<>(Map.of(resident, r));

        assertEquals(1, RenderManager.changeOutfits(rendered, host, 2, 16, 1_000));
        assertEquals(List.of("spawn " + LEISURE + " while old", "despawn old", "resume new-1 at 2"), host.events);
        assertSame(r, rendered.get(resident), "the same Render keeps going");
        assertEquals("new-1", r.npc.toString());
        assertEquals(LEISURE, r.appliedKey);
        assertEquals(List.of(10L, 11L, 12L), r.path);
        assertEquals(2, r.pathIdx);
        assertEquals(property, r.propertyId);
        assertEquals(7, r.ownershipEpoch);
        assertEquals(0, RenderManager.changeOutfits(rendered, host, 2, 16, 2_000), "one change per routine change");
    }

    @Test void anAvatarAlreadyInTheRightSkinNeverCostsAPlayerScan() {
        var host = new Host();
        var dressed = render("dressed");
        dressed.appliedKey = LEISURE;
        Map<UUID, RenderManager.Render> rendered = new HashMap<>(Map.of(UUID.randomUUID(), dressed));
        assertEquals(0, RenderManager.changeOutfits(rendered, host, 2, 16, 1_000));
        assertEquals(0, host.scans);
        // Positive control: an avatar that does need a change is scanned.
        host.nearest = 5;
        rendered.put(UUID.randomUUID(), render("plain"));
        assertEquals(0, RenderManager.changeOutfits(rendered, host, 2, 16, 1_000));
        assertEquals(1, host.scans);
    }

    @Test void aFailedSpawnKeepsTheOldAvatarAndBacksOff() {
        var host = new Host();
        host.failSpawn = true;
        var r = render("old");
        Map<UUID, RenderManager.Render> rendered = new HashMap<>(Map.of(UUID.randomUUID(), r));
        assertEquals(0, RenderManager.changeOutfits(rendered, host, 2, 16, 1_000));
        assertEquals(List.of("spawn " + LEISURE + " while old"), host.events, "the old avatar was never despawned");
        assertEquals("old", r.npc.toString());
        assertEquals(EVERYDAY, r.appliedKey);
        RenderManager.changeOutfits(rendered, host, 2, 16, 30_999);
        assertEquals(1, host.events.size(), "no new try inside RETRY_MS");
        host.failSpawn = false;
        assertEquals(1, RenderManager.changeOutfits(rendered, host, 2, 16, 31_000));
        assertEquals("new-1", r.npc.toString());
    }

    @Test void atMostTheConfiguredNumberChangePerPass() {
        var host = new Host();
        Map<UUID, RenderManager.Render> rendered = new HashMap<>();
        for (int i = 0; i < 5; i++) rendered.put(new UUID(0, i), render("old-" + i));
        assertEquals(2, RenderManager.changeOutfits(rendered, host, 2, 16, 1_000));
        assertEquals(2, RenderManager.changeOutfits(rendered, host, 2, 16, 1_050));
        assertEquals(1, RenderManager.changeOutfits(rendered, host, 2, 16, 1_100));
        assertEquals(5, host.spawned);
        assertTrue(rendered.values().stream().allMatch(r -> LEISURE.equals(r.appliedKey)));
    }

    @Test void failedTriesCountTowardThePassLimit() {
        var host = new Host();
        host.failSpawn = true;
        Map<UUID, RenderManager.Render> rendered = new HashMap<>();
        for (int i = 0; i < 4; i++) rendered.put(new UUID(0, i), render("old-" + i));
        RenderManager.changeOutfits(rendered, host, 2, 16, 1_000);
        assertEquals(2, host.events.size(), "respawn churn is bounded even when spawns fail");
    }

    @Test void nothingChangesNearAViewerInAChatOrOutsideOrdinaryResidents() {
        var host = new Host();
        UUID resident = new UUID(0, 1);
        var r = render("old");
        Map<UUID, RenderManager.Render> rendered = new HashMap<>(Map.of(resident, r));
        host.nearest = 5;
        assertEquals(0, RenderManager.changeOutfits(rendered, host, 2, 16, 1_000));
        host.nearest = Double.POSITIVE_INFINITY;
        host.talking.add(resident);
        assertEquals(0, RenderManager.changeOutfits(rendered, host, 2, 16, 1_000));
        host.talking.clear();
        r.pausedUntilMs = 2_000;
        assertEquals(0, RenderManager.changeOutfits(rendered, host, 2, 16, 1_000), "stopped for a conversation");
        r.pausedUntilMs = 0;
        for (var flag : List.of("debug", "preview", "walkingHome", "noProfile", "noKey")) {
            var other = render("other");
            switch (flag) {
                case "debug" -> other.debug = true;
                case "preview" -> { other.debug = true; other.encounterPreview = true; }
                case "walkingHome" -> other.walkingHome = true;
                case "noProfile" -> other.profile = null;
                default -> other.appliedKey = null;
            }
            assertEquals(0, RenderManager.changeOutfits(new HashMap<>(Map.of(resident, other)), host, 2, 16, 1_000), flag);
        }
        assertTrue(host.events.isEmpty());
        assertEquals(1, RenderManager.changeOutfits(rendered, host, 2, 16, 1_000), "positive control");
    }

    /** Integrator correction: a swap must not reset the pause window, or a resident could be stopped again at once. */
    @Test void aSwapAfterTheDeadlineKeepsThePauseCooldown() {
        var host = new Host();
        var r = render("old");
        UUID resident = new UUID(0, 9);
        Map<UUID, RenderManager.Render> rendered = new HashMap<>(Map.of(resident, r));
        long deadline = r.conversationPause.acquire(UUID.randomUUID(), 0);
        assertEquals(90_000, deadline);
        r.pausedUntilMs = deadline;
        assertEquals(1, RenderManager.changeOutfits(rendered, host, 2, 16, 95_000));
        assertSame(r, rendered.get(resident));
        assertEquals(0, r.conversationPause.acquire(UUID.randomUUID(), 96_000), "still inside the cooldown");
        assertTrue(r.conversationPause.acquire(UUID.randomUUID(), 120_000) > 120_000, "the next window opens on time");
    }
}
