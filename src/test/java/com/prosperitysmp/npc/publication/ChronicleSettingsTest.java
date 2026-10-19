package com.prosperitysmp.npc.publication;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChronicleSettingsTest {
    private static final String GENERATION = "123e4567-e89b-12d3-a456-426614174000";

    private static MemoryConfiguration chronicle() {
        var config = new MemoryConfiguration();
        config.set("enabled", true);
        config.set("authorization-generation", GENERATION);
        config.set("refresh-seconds", 30);
        return config;
    }

    private static MemoryConfiguration publication() {
        var config = new MemoryConfiguration();
        config.set("enabled", true);
        config.set("refresh-seconds", 60);
        config.set("max-age-seconds", 300);
        config.set("recovery-age-seconds", 3600);
        config.set("retention-age-seconds", 86400);
        config.set("http-enabled", false);
        config.set("http-port", 0);
        config.set("approved-scopes", List.of(Map.of("property-id", new UUID(0, 1).toString(),
                "world-id", new UUID(0, 2).toString(), "region-id", "c001", "world-key", "main")));
        return config;
    }

    private static IllegalArgumentException refused(MemoryConfiguration chronicle, MemoryConfiguration publication) {
        return assertThrows(IllegalArgumentException.class, () -> ChronicleSettings.read(chronicle, publication));
    }

    @Test
    void aMissingSectionIsTheDisabledSettings() {
        assertEquals(ChronicleRuntime.Settings.disabled(), ChronicleSettings.read(null, publication()));
        assertEquals(ChronicleRuntime.Settings.disabled(), ChronicleSettings.read(null, null));
    }

    @Test
    void anExplicitFalseIsDisabledWhateverElseTheSectionHolds() {
        var section = chronicle();
        section.set("enabled", false);
        section.set("typo", "garbage");
        section.set("refresh-seconds", "not a number");
        assertEquals(ChronicleRuntime.Settings.disabled(), ChronicleSettings.read(section, null));
    }

    @Test
    void theDisabledSettingsHoldNoScopeAndNoGeneration() {
        var disabled = ChronicleSettings.read(null, publication());
        assertFalse(disabled.enabled());
        assertEquals(List.of(), disabled.scopes());
        assertEquals(null, disabled.generation());
        assertFalse(disabled.receiptRevisionEvidence());
    }

    @Test
    void aFullSectionWithPublicationScopesIsEnabled() {
        var settings = ChronicleSettings.read(chronicle(), publication());
        assertTrue(settings.enabled());
        assertEquals(UUID.fromString(GENERATION), settings.generation());
        assertEquals(Duration.ofSeconds(30), settings.interval());
        assertFalse(settings.receiptRevisionEvidence());
        assertEquals(1, settings.scopes().size());
        assertEquals(new UUID(0, 1), settings.scopes().get(0).propertyId());
        assertEquals(new UUID(0, 2), settings.scopes().get(0).worldId());
        assertEquals("c001", settings.scopes().get(0).regionId());
        assertEquals("main", settings.scopes().get(0).publicWorldKey());
    }

    @Test
    void aScopeListInsideTheChronicleBlockIsAnUnknownKeyNotASecondSourceOfScopes() {
        var section = chronicle();
        section.set("approved-scopes", List.of());
        assertEquals("Invalid Chronicle settings", refused(section, publication()).getMessage());
    }

    @Test
    void aLongRefreshIntervalIsAccepted() {
        var section = chronicle();
        section.set("refresh-seconds", 45L);
        assertEquals(Duration.ofSeconds(45), ChronicleSettings.read(section, publication()).interval());
    }

    @ParameterizedTest
    @ValueSource(strings = {"30", "30.0", "thirty"})
    void aRefreshIntervalThatIsNotAnIntegerIsRefused(String text) {
        var section = chronicle();
        section.set("refresh-seconds", text);
        assertEquals("Invalid Chronicle generation or interval", refused(section, publication()).getMessage());
    }

    @Test
    void aDecimalRefreshIntervalIsRefused() {
        var section = chronicle();
        section.set("refresh-seconds", 30.0);
        assertEquals("Invalid Chronicle generation or interval", refused(section, publication()).getMessage());
        section.set("refresh-seconds", 1.5f);
        assertEquals("Invalid Chronicle generation or interval", refused(section, publication()).getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -3600, 3601, 86400})
    void aRefreshIntervalOutsideOneSecondToOneHourIsRefused(int seconds) {
        var section = chronicle();
        section.set("refresh-seconds", seconds);
        assertEquals("Invalid Chronicle runtime settings", refused(section, publication()).getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3599, 3600})
    void aRefreshIntervalFromOneSecondToOneHourIsAccepted(int seconds) {
        var section = chronicle();
        section.set("refresh-seconds", seconds);
        assertEquals(Duration.ofSeconds(seconds), ChronicleSettings.read(section, publication()).interval());
    }

    @Test
    void anAbsentEnabledKeyIsRefusedNotTreatedAsDisabled() {
        var section = chronicle();
        section.set("enabled", null);
        assertEquals("Invalid Chronicle settings", refused(section, publication()).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false", "yes", "on", "1"})
    void aTextEnabledValueIsRefused(String text) {
        var section = chronicle();
        section.set("enabled", text);
        assertEquals("Invalid Chronicle settings", refused(section, publication()).getMessage());
    }

    @Test
    void aNumberEnabledValueIsRefused() {
        var section = chronicle();
        section.set("enabled", 1);
        assertEquals("Invalid Chronicle settings", refused(section, publication()).getMessage());
    }

    @Test
    void aMissingGenerationIsRefused() {
        var section = chronicle();
        section.set("authorization-generation", null);
        assertEquals("Invalid Chronicle settings", refused(section, publication()).getMessage());
    }

    @Test
    void aMissingIntervalIsRefused() {
        var section = chronicle();
        section.set("refresh-seconds", null);
        assertEquals("Invalid Chronicle settings", refused(section, publication()).getMessage());
    }

    @Test
    void anUnknownKeyIsRefused() {
        var section = chronicle();
        section.set("refresh-second", 30);
        assertEquals("Invalid Chronicle settings", refused(section, publication()).getMessage());
    }

    @Test
    void anUppercaseGenerationIsNotCanonical() {
        var section = chronicle();
        section.set("authorization-generation", GENERATION.toUpperCase(java.util.Locale.ROOT));
        assertEquals("Canonical generation required", refused(section, publication()).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"123e4567e89b12d3a456426614174000", "{123e4567-e89b-12d3-a456-426614174000}", "1-1-1-1-1",
            "", "not-a-uuid", " 123e4567-e89b-12d3-a456-426614174000"})
    void aGenerationThatIsNotAUuidInCanonicalFormIsRefused(String text) {
        var section = chronicle();
        section.set("authorization-generation", text);
        refused(section, publication());
    }

    @Test
    void aGenerationThatIsNotTextIsRefused() {
        var section = chronicle();
        section.set("authorization-generation", UUID.fromString(GENERATION));
        assertEquals("Invalid Chronicle generation or interval", refused(section, publication()).getMessage());
        section.set("authorization-generation", 12345);
        assertEquals("Invalid Chronicle generation or interval", refused(section, publication()).getMessage());
    }

    @Test
    void receiptRevisionEvidenceDefaultsOffAndFollowsAnExplicitBoolean() {
        var section = chronicle();
        assertFalse(ChronicleSettings.read(section, publication()).receiptRevisionEvidence());
        section.set("receipt-revision-evidence", true);
        assertTrue(ChronicleSettings.read(section, publication()).receiptRevisionEvidence());
        section.set("receipt-revision-evidence", false);
        assertFalse(ChronicleSettings.read(section, publication()).receiptRevisionEvidence());
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "true", "no", ""})
    void receiptRevisionEvidenceThatIsNotABooleanIsRefused(String text) {
        var section = chronicle();
        section.set("receipt-revision-evidence", text);
        assertEquals("Invalid Chronicle receipt-revision-evidence", refused(section, publication()).getMessage());
    }

    @Test
    void enablingWithoutAPublicationBlockIsRefused() {
        assertEquals("Explicit publication scopes required", refused(chronicle(), null).getMessage());
    }

    @Test
    void enablingWithADisabledPublicationBlockIsRefused() {
        var disabled = publication();
        disabled.set("enabled", false);
        assertEquals("Explicit publication scopes required", refused(chronicle(), disabled).getMessage());
    }

    @Test
    void aPublicationBlockThatIsItselfInvalidIsRefusedToo() {
        var broken = publication();
        broken.set("approved-scopes", List.of());
        refused(chronicle(), broken);
    }

    @Test
    void theReadSettingsOwnTheirScopesAfterTheConfigChanges() {
        var publication = publication();
        var settings = ChronicleSettings.read(chronicle(), publication);
        publication.set("approved-scopes", List.of());
        assertEquals(1, settings.scopes().size());
        assertThrows(UnsupportedOperationException.class, () -> settings.scopes().clear());
    }
}
