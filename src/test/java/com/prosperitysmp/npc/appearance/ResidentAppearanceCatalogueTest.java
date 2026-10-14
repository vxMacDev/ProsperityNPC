package com.prosperitysmp.npc.appearance;

import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ResidentAppearanceCatalogueTest {
    private static SignedResidentSkin first;
    private static SignedResidentSkin second;
    private final ResidentProfile mara = ResidentCatalogue.betaCast().getFirst();

    @BeforeAll static void syntheticCryptoFixtures() throws Exception {
        // Local test signatures are not Mojang assets and must never enter production manifests.
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(4096);
        KeyPair key = generator.generateKeyPair();
        first = signed(key, "a"); second = signed(key, "b");
    }

    private static SignedResidentSkin signed(KeyPair key, String hex) throws Exception {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/"
                + hex.repeat(64) + "\"}}}";
        String value = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA1withRSA");
        signature.initSign(key.getPrivate()); signature.update(value.getBytes(StandardCharsets.US_ASCII));
        return SignedResidentSkin.verifyWithKey(value, Base64.getEncoder().encodeToString(signature.sign()), key.getPublic());
    }

    @Test void finiteImmutableUniverseAndCacheNames() {
        var keys = new ResidentAppearanceCatalogue().allowedKeys();
        assertEquals(27, keys.size());
        assertEquals(27, keys.stream().map(ResidentAppearanceCatalogue::citizensKey).distinct().count());
        assertTrue(keys.stream().map(ResidentAppearanceCatalogue::citizensKey).allMatch(k -> k.startsWith("cit-")));
        assertThrows(UnsupportedOperationException.class, () -> keys.clear());
        assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceCatalogue.citizensKey("random/player"));
    }

    @Test void variantWithoutBaseDoesNotPublishIncompleteAppearance() {
        var catalogue = new ResidentAppearanceCatalogue();
        catalogue.replace(Map.of(mara.wardrobe().work(), first));
        var selected = catalogue.forSpawn(mara, 4000);
        assertEquals(ResidentAppearanceCatalogue.Status.MISSING_BASE, selected.status());
        assertTrue(selected.applied().isEmpty());
    }

    @Test void appliedBoundaryRejectsForeignCacheNamesAndMissingVerifiedAssets() {
        String key = mara.wardrobe().everyday();
        String cache = ResidentAppearanceCatalogue.citizensKey(key);
        assertThrows(IllegalArgumentException.class, () -> new ResidentAppearanceCatalogue.Applied(key, "random-player", first));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAppearanceCatalogue.Applied("unknown", cache, first));
        assertThrows(NullPointerException.class, () -> new ResidentAppearanceCatalogue.Applied(key, cache, null));
        assertEquals(first, new ResidentAppearanceCatalogue.Applied(key, cache, first).skin());
    }

    @Test void routineUsesAvailableVariantOtherwiseEveryday() {
        var catalogue = new ResidentAppearanceCatalogue();
        catalogue.replace(Map.of(mara.wardrobe().everyday(), first, mara.wardrobe().work(), second));
        assertSame(second, catalogue.forSpawn(mara, 4000).applied().orElseThrow().skin());
        assertEquals(ResidentAppearanceCatalogue.Status.REQUESTED, catalogue.forSpawn(mara, 4000).status());
        assertEquals(ResidentAppearanceCatalogue.Status.BASE_FALLBACK, catalogue.forSpawn(mara, 12000).status());
        assertSame(first, catalogue.forSpawn(mara, 12000).applied().orElseThrow().skin());
        assertEquals(ResidentAppearanceCatalogue.Status.REQUESTED, catalogue.forSpawn(mara, 18000).status());
        assertEquals(catalogue.forSpawn(mara, 4000), catalogue.forSpawn(mara, 28000));
    }

    @Test void replacementsCannotMutatePreviousSelectionsOrGrowCacheKeys() {
        var catalogue = new ResidentAppearanceCatalogue();
        var input = new HashMap<>(Map.of(mara.wardrobe().everyday(), first));
        assertEquals(1, catalogue.replace(input)); input.clear();
        var original = catalogue.forSpawn(mara, 0);
        assertEquals(2, catalogue.replace(Map.of(mara.wardrobe().everyday(), second)));
        var replacement = catalogue.forSpawn(mara, 0);
        assertSame(first, original.applied().orElseThrow().skin());
        assertSame(second, replacement.applied().orElseThrow().skin());
        assertEquals(original.applied().orElseThrow().citizensKey(), replacement.applied().orElseThrow().citizensKey());
        var reloaded = new ResidentAppearanceCatalogue();
        reloaded.replace(Map.of(mara.wardrobe().everyday(), second));
        assertEquals(replacement.applied().orElseThrow().citizensKey(), reloaded.forSpawn(mara, 0).applied().orElseThrow().citizensKey());
    }

    @Test void rejectedReplacementsLeavePublishedStateIntact() {
        var catalogue = new ResidentAppearanceCatalogue();
        catalogue.replace(Map.of(mara.wardrobe().everyday(), first));
        var expected = catalogue.forSpawn(mara, 0);
        assertThrows(IllegalArgumentException.class, () -> catalogue.replace(Map.of("resident/v999/unknown", second)));
        var invalid = new HashMap<String, SignedResidentSkin>(); invalid.put(mara.wardrobe().everyday(), null);
        assertThrows(NullPointerException.class, () -> catalogue.replace(invalid));
        assertThrows(NullPointerException.class, () -> catalogue.replace(null));
        assertEquals(expected, catalogue.forSpawn(mara, 0));
        assertEquals(1, catalogue.size());
        catalogue.replace(Map.of());
        assertTrue(catalogue.forSpawn(mara, 0).applied().isEmpty());
    }

    @Test void concurrentSelectionsBelongToExactlyOnePublishedRevision() throws Exception {
        var catalogue = new ResidentAppearanceCatalogue();
        var odd = Map.of(mara.wardrobe().everyday(), first);
        var even = Map.of(mara.wardrobe().everyday(), second, mara.wardrobe().work(), second);
        catalogue.replace(odd);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var writer = pool.submit(() -> {
                start.await();
                for (int revision = 2; revision <= 10_000; revision++) catalogue.replace(revision % 2 == 0 ? even : odd);
                return null;
            });
            var reader = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 20_000; i++) {
                    var selected = catalogue.forSpawn(mara, 4000);
                    boolean isEven = selected.revision() % 2 == 0;
                    assertEquals(isEven ? ResidentAppearanceCatalogue.Status.REQUESTED
                            : ResidentAppearanceCatalogue.Status.BASE_FALLBACK, selected.status());
                    var applied = selected.applied().orElseThrow();
                    assertSame(isEven ? second : first, applied.skin());
                    assertEquals(isEven ? mara.wardrobe().work() : mara.wardrobe().everyday(), applied.assetKey());
                    assertEquals(ResidentAppearanceCatalogue.citizensKey(applied.assetKey()), applied.citizensKey());
                }
                return null;
            });
            start.countDown();
            writer.get(10, java.util.concurrent.TimeUnit.SECONDS);
            reader.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private static com.prosperitysmp.npc.resident.ResidentCast extendedCast() throws Exception {
        try (var in = java.nio.file.Files.newInputStream(java.nio.file.Path.of("examples/residents.extended.yml"))) {
            return com.prosperitysmp.npc.resident.ResidentCastFile.read(in);
        }
    }

    @Test void nineResidentCastUniverseHas45KeysWithNoCollision() throws Exception {
        var cast = extendedCast();
        assertEquals(9, cast.profiles().size());
        var catalogue = ResidentAppearanceCatalogue.forCast(cast);
        assertEquals(18 + 27, catalogue.allowedKeys().size());
        assertEquals(45, catalogue.allowedKeys().stream().map(catalogue::cacheKey).distinct().count());
        assertTrue(catalogue.allows("resident/v1/otto-marsh/work"));
        assertTrue(catalogue.allows("resident/v1/cook/work"));
    }

    @Test void aResidentOutsideTheCastIsNotAllowedAndHasNoCacheKey() throws Exception {
        var builtIn = new ResidentAppearanceCatalogue();
        assertEquals(27, builtIn.allowedKeys().size());
        assertFalse(builtIn.allows("resident/v1/otto-marsh/everyday"));
        assertThrows(IllegalArgumentException.class, () -> builtIn.cacheKey("resident/v1/otto-marsh/everyday"));
        assertThrows(IllegalArgumentException.class, () -> builtIn.replace(Map.of("resident/v1/otto-marsh/everyday", first)));
        var extended = ResidentAppearanceCatalogue.forCast(extendedCast());
        assertFalse(extended.allows("resident/v1/someone-else/everyday"));
        assertThrows(IllegalArgumentException.class, () -> extended.cacheKey("resident/v1/someone-else/everyday"));
        assertEquals("cit-pnpc-resident-v1-otto-marsh-work", extended.cacheKey("resident/v1/otto-marsh/work"));
    }

    @Test void theBuiltInThreeStayAllowedWhenAFileLeavesThemOut() throws Exception {
        String text = """
                version: 1
                residents:
                  - key: otto-marsh
                    name: Otto Marsh
                    household: Marsh
                    generation: 1
                    occupation: COOK
                    means: STEADY
                    life-stage: ADULT
                    temperament: DIRECT
                    preference: CLEAR_TERMS
                """;
        var cast = com.prosperitysmp.npc.resident.ResidentCastFile.read(
                new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        var catalogue = ResidentAppearanceCatalogue.forCast(cast);
        assertTrue(catalogue.allows(mara.wardrobe().everyday()), "saved Mara rows still render");
        assertEquals(18 + 9 + 3, catalogue.allowedKeys().size());
    }

    @Test void reportLinesUseThePlainCopy() throws Exception {
        var cast = extendedCast();
        var otto = cast.byFirstName("otto").orElseThrow();
        var june = cast.byFirstName("june").orElseThrow();
        var catalogue = ResidentAppearanceCatalogue.forCast(cast);
        catalogue.replace(Map.of(otto.wardrobe().everyday(), first, otto.wardrobe().work(), second));
        assertEquals("Otto Marsh: everyday ready, work ready, leisure missing (wears everyday).", catalogue.reportLine(otto));
        assertEquals("June Hollis: no everyday skin. Avatar held.", catalogue.reportLine(june));
        assertTrue(catalogue.hasEveryday(otto));
        assertFalse(catalogue.hasEveryday(june));
    }
}
