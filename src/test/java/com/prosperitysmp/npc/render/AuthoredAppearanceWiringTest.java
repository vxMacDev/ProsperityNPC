package com.prosperitysmp.npc.render;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Source-wiring regression only; not a substitute for Citizens runtime or client visual tests. */
class AuthoredAppearanceWiringTest {
    private static String source(String path) throws Exception {
        return Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/" + path));
    }

    @Test void bothResidentPathsUseAuthoredSpawnAndOnlyDebugRetainsUnconfiguredSpawn() throws Exception {
        String code = source("render/RenderManager.java");
        // Two spawn sites, plus the outfit swap's replacement spawn, which reuses the verified selection.
        assertEquals(3, code.split("citizens\\.spawnAuthored\\(", -1).length - 1);
        assertTrue(code.contains("NPC npc = citizens.spawnAuthored(r.profile.name(), at, applied);"));
        assertEquals(2, code.split("r\\.appliedKey = appearance\\.applied\\(\\)\\.orElseThrow\\(\\)\\.assetKey\\(\\);", -1).length - 1,
                "both spawn sites record the skin the avatar wears");
        assertEquals(1, code.split("citizens\\.spawn\\(", -1).length - 1);
        assertTrue(code.contains("citizens.spawn(\"NpcRenderTest\", spawn)"));
        assertTrue(code.contains("appearances.forSpawn(request.profile(), at.getWorld().getTime())"));
        assertTrue(code.contains("appearances.forSpawn(ctx.profile, ctx.spawn.getWorld().getTime())"));
        assertEquals(2, code.split("appearance\\.applied\\(\\)\\.isEmpty\\(\\)", -1).length - 1);
    }

    @Test void persistentVerifiedBytesAreConfiguredInsideCleanupBoundaryBeforeSpawn() throws Exception {
        String code = source("render/CitizensAdapter.java");
        int create = code.indexOf("npc = registry.createNPC");
        int configure = code.indexOf("skin.setSkinPersistent(appearance.citizensKey(), appearance.skin().signature(), appearance.skin().value())");
        int spawn = code.indexOf("npc.spawn(loc, SpawnReason.PLUGIN)");
        assertTrue(create >= 0 && configure > create && spawn > configure);
        assertTrue(code.indexOf("skin.setFetchDefaultSkin(false)") > create);
        assertTrue(code.indexOf("skin.setShouldUpdateSkins(false)") < configure);
        assertTrue(code.indexOf("finally", configure) > spawn);
        assertTrue(code.contains("despawn(npc)"));
    }

    @Test void startupUsesFixedLoaderAndRejectsWithoutPublishingOldOrPartialArt() throws Exception {
        String code = source("ProsperityNpcPlugin.java");
        assertTrue(code.contains("appearances.replace(ResidentAppearanceFiles.load(getDataFolder().toPath()))"));
        assertTrue(code.contains("appearances.replace(java.util.Map.of())"));
        assertTrue(code.contains("new ResidentRepository(database), appearances"));
        assertTrue(source("command/NpcAdminCommand.java").contains("plugin.appearanceStatus()"));
    }
}
