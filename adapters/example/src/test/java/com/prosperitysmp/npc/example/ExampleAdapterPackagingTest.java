package com.prosperitysmp.npc.example;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The example's plugin.yml names a real plugin class and loads after ProsperityNPC, as the adapter guide says. */
class ExampleAdapterPackagingTest {

    private static Map<String, String> descriptor() throws IOException {
        var out = new LinkedHashMap<String, String>();
        var urls = ExampleAdapterPackagingTest.class.getClassLoader().getResources("plugin.yml");
        while (urls.hasMoreElements()) {
            String text;
            try (var in = urls.nextElement().openStream()) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!text.startsWith("name: ProsperityNPC-Example")) continue;
            for (String line : text.split("\n")) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith(" ")) continue;
                int colon = line.indexOf(':');
                out.put(line.substring(0, colon), line.substring(colon + 1).trim());
            }
            return out;
        }
        throw new AssertionError("the example adapter's plugin.yml is not on the test classpath");
    }

    @Test void theNameIsTheExampleAdapters() throws IOException {
        assertEquals("ProsperityNPC-Example", descriptor().get("name"));
    }

    @Test void theMainClassExistsIsFinalAndIsAJavaPlugin() throws Exception {
        Class<?> main = Class.forName(descriptor().get("main"), false, getClass().getClassLoader());
        assertEquals(ExampleAdapterPlugin.class, main);
        assertTrue(JavaPlugin.class.isAssignableFrom(main));
        assertTrue(Modifier.isFinal(main.getModifiers()));
    }

    @Test void itLoadsAfterProsperityNpcBecauseTheApiLivesInsideIt() throws IOException {
        assertEquals("[ProsperityNPC]", descriptor().get("depend"));
    }

    @Test void itDeclaresTheServerApiVersionItWasBuiltFor() throws IOException {
        assertEquals("\"26.1\"", descriptor().get("api-version"));
    }

    @Test void theVersionIsFilledInAtBuildTime() throws IOException {
        assertEquals("'0.1.0'", descriptor().get("version"));
        assertFalse(descriptor().get("version").contains("$"));
    }

    @Test void theDescriptionSaysItIsDevOnly() throws IOException {
        assertTrue(descriptor().get("description").startsWith("DEV-only reference adapter"), descriptor().get("description"));
    }

    @Test void theDescriptorNamesNoSoftDependencyAndNoCommands() throws IOException {
        var keys = descriptor().keySet();
        assertFalse(keys.contains("softdepend"));
        assertFalse(keys.contains("commands"));
        assertFalse(keys.contains("permissions"));
    }
}
