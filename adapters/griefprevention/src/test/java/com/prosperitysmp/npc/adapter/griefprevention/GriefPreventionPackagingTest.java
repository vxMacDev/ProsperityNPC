package com.prosperitysmp.npc.adapter.griefprevention;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The adapter's plugin.yml loads it after ProsperityNPC and GriefPrevention, and after RealEstate when that is there. */
class GriefPreventionPackagingTest {

    private static Map<String, String> descriptor() throws IOException {
        var out = new LinkedHashMap<String, String>();
        try (var in = GriefPreventionPackagingTest.class.getResourceAsStream("/plugin.yml")) {
            assertNotNull(in, "plugin.yml is on the test classpath");
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith(" ")) continue;
                int colon = line.indexOf(':');
                out.put(line.substring(0, colon), line.substring(colon + 1).trim());
            }
        }
        return out;
    }

    @Test void theMainClassIsTheAdapterPluginInThisPackage() throws IOException {
        assertEquals(GriefPreventionBackend.class.getPackageName() + ".GriefPreventionAdapterPlugin", descriptor().get("main"));
    }

    @Test void itLoadsAfterProsperityNpcAndGriefPrevention() throws IOException {
        assertEquals("[ProsperityNPC, GriefPrevention]", descriptor().get("depend"));
    }

    @Test void realEstateIsOnlyASoftDependencyBecauseItsPresenceDecidesTheGuard() throws IOException {
        assertEquals("[RealEstate]", descriptor().get("softdepend"));
    }

    @Test void theVersionIsFilledInAtBuildTime() throws IOException {
        assertEquals("'0.1.0'", descriptor().get("version"));
    }

    @Test void itDeclaresTheServerApiVersionItWasBuiltFor() throws IOException {
        assertEquals("\"26.1\"", descriptor().get("api-version"));
    }

    @Test void theDescriptionNamesThePropertyBackendKeyAndAdmitsItIsUnverified() throws IOException {
        String description = descriptor().get("description");
        assertTrue(description.contains("property.backend griefprevention"), description);
        assertTrue(description.endsWith("Unverified."), description);
    }

    @Test void itDeclaresNoCommandsAndNoPermissions() throws IOException {
        assertFalse(descriptor().containsKey("commands"));
        assertFalse(descriptor().containsKey("permissions"));
    }
}
