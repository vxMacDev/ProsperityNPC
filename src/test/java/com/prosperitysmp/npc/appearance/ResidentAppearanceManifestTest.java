package com.prosperitysmp.npc.appearance;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Ephemeral test-key signatures are synthetic crypto fixtures, never approved Mojang artwork. */
class ResidentAppearanceManifestTest {
    private static final Gson GSON = new Gson();
    private static final String EMPTY = "{\"version\":1,\"assets\":[]}";
    private static final List<String> KEYS = new ResidentAppearanceCatalogue().allowedKeys().stream().sorted().toList();
    private static KeyPair testKey;
    private static String value;
    private static String signature;

    @BeforeAll static void syntheticCryptoFixture() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(4096);
        testKey = generator.generateKeyPair();
        value = Base64.getEncoder().encodeToString(("{\"textures\":{\"SKIN\":{\"url\":"
                + "\"https://textures.minecraft.net/texture/" + "a".repeat(64) + "\"}}}")
                .getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(testKey.getPrivate());
        signer.update(value.getBytes(StandardCharsets.US_ASCII));
        signature = Base64.getEncoder().encodeToString(signer.sign());
    }

    private static ByteArrayInputStream input(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String row(String key) {
        return GSON.toJson(Map.of("key", key, "value", value, "signature", signature));
    }

    private static String document(String... rows) {
        return "{\"version\":1,\"assets\":[" + String.join(",", rows) + "]}";
    }

    private static SignedResidentSkin verifySynthetic(String encoded, String signed) {
        return SignedResidentSkin.verifyWithKey(encoded, signed, testKey.getPublic());
    }

    private static Map<String, SignedResidentSkin> readSynthetic(String text) {
        return ResidentAppearanceManifest.read(input(text), ResidentAppearanceManifestTest::verifySynthetic);
    }

    private static void rejectBeforeVerification(String text) {
        var calls = new AtomicInteger();
        var error = assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceManifest.read(input(text), (v, s) -> {
            calls.incrementAndGet();
            return verifySynthetic(v, s);
        }));
        assertEquals(0, calls.get());
        assertEquals("Invalid resident appearance manifest", error.getMessage());
        assertNull(error.getCause());
    }

    @Test void publicEmptyDocumentIsValidAndImmutable() {
        Map<String, SignedResidentSkin> result = ResidentAppearanceManifest.read(input(EMPTY));
        assertTrue(result.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.put(KEYS.getFirst(), null));
    }

    @Test void positiveRowsUseSyntheticVerifiedSkinsAndImmutableMap() {
        Map<String, SignedResidentSkin> result = readSynthetic(document(row(KEYS.get(0)), row(KEYS.get(1))));
        assertEquals(2, result.size());
        assertEquals(value, result.get(KEYS.getFirst()).value());
        assertEquals(signature, result.get(KEYS.getFirst()).signature());
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test void acceptsAll27KeysAndRejects28thRowBeforeAnyVerification() {
        String[] rows = KEYS.stream().map(ResidentAppearanceManifestTest::row).toArray(String[]::new);
        assertEquals(27, readSynthetic(document(rows)).size());
        rejectBeforeVerification(document(String.join(",", rows), row(KEYS.getFirst())));
    }

    @Test void leavesCallerStreamOpenOnSuccessAndFailure() {
        class OwnedStream extends ByteArrayInputStream {
            boolean closed;
            OwnedStream(String text) { super(text.getBytes(StandardCharsets.UTF_8)); }
            @Override public void close() { closed = true; }
        }
        var success = new OwnedStream(EMPTY);
        ResidentAppearanceManifest.read(success);
        assertFalse(success.closed);
        var failure = new OwnedStream("not JSON");
        assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceManifest.read(failure));
        assertFalse(failure.closed);
        var untrusted = new OwnedStream(document(row(KEYS.getFirst())));
        assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceManifest.read(untrusted));
        assertFalse(untrusted.closed);
    }

    @Test void enforcesByteLimitBeforeParsingAndDoesNotConsumeRemainder() {
        String exact = EMPTY + " ".repeat(512 * 1024 - EMPTY.length());
        assertTrue(ResidentAppearanceManifest.read(input(exact)).isEmpty());
        var oversized = input(exact + "  ");
        assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceManifest.read(oversized));
        assertEquals(1, oversized.available());
        // Non-ASCII characters count as UTF-8 bytes, not just Java characters.
        var multibyte = input("\"" + "\u00e9".repeat(300000) + "\"");
        assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceManifest.read(multibyte));
        assertEquals(600002 - (512 * 1024 + 1), multibyte.available());
    }

    @Test void malformedUtf8AndBomFail() {
        assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceManifest.read(
                new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28})));
        rejectBeforeVerification("\ufeff" + EMPTY);
    }

    @Test void versionRequiresExactIntegerOneLexeme() {
        for (String version : List.of("1.0", "1e0", "1E+0", "01", "+1", "0", "2", "-1", "\"1\"", "true", "null", "[]", "{}")) {
            rejectBeforeVerification(EMPTY.replace(":1,", ":" + version + ","));
        }
        assertTrue(ResidentAppearanceManifest.read(input("{\"assets\":[],\"version\":1}")).isEmpty());
    }

    @Test void rejectsDuplicateRootFieldsEvenWhenBothValuesAreValid() {
        rejectBeforeVerification("{\"version\":1,\"version\":1,\"assets\":[]}");
        rejectBeforeVerification("{\"version\":1,\"\\u0076ersion\":1,\"assets\":[]}");
        rejectBeforeVerification("{\"version\":1,\"assets\":[],\"assets\":[]}");
        rejectBeforeVerification("{\"version\":1,\"assets\":[],\"\\u0061ssets\":[]}");
    }

    @Test void rejectsDuplicateRowFieldsWithOtherwiseValidSignedEntry() {
        for (String field : List.of("key", "value", "signature")) {
            String fieldValue = switch (field) {
                case "key" -> KEYS.getFirst();
                case "value" -> value;
                default -> signature;
            };
            String prefix = "{" + GSON.toJson(field) + ":" + GSON.toJson(fieldValue) + ",";
            rejectBeforeVerification(document(prefix + row(KEYS.getFirst()).substring(1)));
            String escaped = "\\u%04x%s".formatted((int) field.charAt(0), field.substring(1));
            rejectBeforeVerification(document("{\"" + escaped + "\":" + GSON.toJson(fieldValue) + ","
                    + row(KEYS.getFirst()).substring(1)));
        }
    }

    @Test void rejectsDuplicateAssetKeysAndUnknownKeys() {
        rejectBeforeVerification(document(row(KEYS.getFirst()), row(KEYS.getFirst())));
        rejectBeforeVerification(document(row("resident/v1/unapproved/everyday")));
        rejectBeforeVerification(document(row(KEYS.getFirst().toUpperCase(java.util.Locale.ROOT))));
    }

    @Test void rejectsMissingExtraAndMistypedFields() {
        for (String text : List.of("{}", "{\"version\":1}", "{\"assets\":[]}",
                "{\"version\":1,\"assets\":[],\"license\":\"not approval\"}",
                "null", "[]", "{\"version\":1,\"assets\":null}", "{\"version\":1,\"assets\":{}}",
                document("null"), document("[]"), document("{}"))) rejectBeforeVerification(text);
        for (String field : List.of("key", "value", "signature")) {
            var fields = new java.util.HashMap<String, Object>(Map.of("key", KEYS.getFirst(), "value", value, "signature", signature));
            fields.remove(field);
            rejectBeforeVerification(document(GSON.toJson(fields)));
            for (Object bad : List.of(1, true, List.of(), Map.of(), "")) {
                fields.put(field, bad);
                rejectBeforeVerification(document(GSON.toJson(fields)));
            }
            // Gson normally omits null map values; insert explicit JSON null instead.
            rejectBeforeVerification(document(row(KEYS.getFirst()).replace("\"" + field + "\":",
                    "\"" + field + "\":null,\"unused\":")));
        }
        rejectBeforeVerification(document("{\"publicKey\":\"not trusted\"," + row(KEYS.getFirst()).substring(1)));
    }

    @Test void fieldLengthBoundsPrecedeVerification() {
        for (String field : List.of("key", "value", "signature")) {
            int cap = switch (field) { case "key" -> 128; case "value" -> 8192; default -> 2048; };
            var fields = new java.util.HashMap<>(Map.of("key", KEYS.getFirst(), "value", value, "signature", signature));
            fields.put(field, "x".repeat(cap + 1));
            rejectBeforeVerification(document(GSON.toJson(fields)));
        }
    }

    @Test void rejectsLenientJsonTrailingDataAndNestedUnexpectedObjectsBeforeVerification() {
        for (String text : List.of(EMPTY + "{}", EMPTY + "false", "/*x*/" + EMPTY,
                EMPTY.replace("\"version\"", "version"), EMPTY.replace('"', '\''),
                "{\"version\":1,\"assets\":[],}", "{\"version\":1,\"assets\":[,]}",
                document("{\"key\":{\"nested\":{\"deep\":{}}}}"))) rejectBeforeVerification(text);
        rejectBeforeVerification(document(row(KEYS.getFirst())) + " trailing-secret");
    }

    @Test void publicVerifierNeverAcceptsEphemeralTestTrust() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> ResidentAppearanceManifest.read(input(document(row(KEYS.getFirst())))));
        assertEquals("Invalid resident appearance manifest", error.getMessage());
        assertNull(error.getCause());
    }

    @Test void secondVerificationFailureReturnsNoPartialMapAndDoesNotPublish() {
        var catalogue = new ResidentAppearanceCatalogue();
        var sentinel = Map.of(KEYS.getFirst(), verifySynthetic(value, signature));
        catalogue.replace(sentinel);
        var result = new AtomicReference<>(sentinel);
        var calls = new AtomicInteger();
        var error = assertThrows(IllegalArgumentException.class, () -> result.set(ResidentAppearanceManifest.read(
                input(document(row(KEYS.get(0)), row(KEYS.get(1)))), (v, s) -> {
                    if (calls.incrementAndGet() == 2) throw new IllegalArgumentException("sensitive-value-DO-NOT-RELAY");
                    return verifySynthetic(v, s);
                })));
        assertEquals(2, calls.get());
        assertSame(sentinel, result.get());
        assertEquals(1, catalogue.revision());
        assertEquals(1, catalogue.size());
        assertEquals("Invalid resident appearance manifest", error.getMessage());
        assertNull(error.getCause());
    }

    @Test void nullInputNullVerifierNullVerificationAndIoFailureAreGeneric() {
        assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceManifest.read(null));
        assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceManifest.read(input(EMPTY), null));
        assertThrows(IllegalArgumentException.class,
                () -> ResidentAppearanceManifest.read(input(document(row(KEYS.getFirst()))), (v, s) -> null));
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("sensitive-source"); }
        };
        var error = assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceManifest.read(broken));
        assertEquals("Invalid resident appearance manifest", error.getMessage());
        assertNull(error.getCause());
    }

    /** Enough distinct well-formed keys for a full 32-resident cast, plus spares. */
    private static List<String> castKeys(int count) {
        var keys = new java.util.ArrayList<String>();
        for (int i = 0; keys.size() < count; i++)
            for (String slot : List.of("everyday", "work", "leisure")) if (keys.size() < count) keys.add("resident/v1/r" + i + "x/" + slot);
        return keys;
    }

    private static ResidentAppearanceManifest.Result readCast(String text, java.util.Set<String> allowed) {
        return ResidentAppearanceManifest.read(input(text), allowed, ResidentAppearanceCatalogue::wellFormedResidentKey,
                ResidentAppearanceManifestTest::verifySynthetic);
    }

    @Test void a114EntryFileIsAcceptedAndA115thEntryRejectsTheFile() {
        var keys = castKeys(115);
        var allowed = java.util.Set.copyOf(keys);
        String[] rows = keys.stream().map(ResidentAppearanceManifestTest::row).toArray(String[]::new);
        var full = readCast(document(java.util.Arrays.copyOf(rows, 114)), allowed);
        assertEquals(114, full.assets().size());
        assertEquals(0, full.skipped());
        var calls = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceManifest.read(input(document(rows)), allowed,
                ResidentAppearanceCatalogue::wellFormedResidentKey, (v, s) -> { calls.incrementAndGet(); return verifySynthetic(v, s); }));
        assertEquals(0, calls.get(), "the size cap fails before any verification");
    }

    @Test void aWellFormedKeyOutsideTheCastIsSkippedAndCountedNotFatal() {
        var allowed = java.util.Set.of(KEYS.getFirst());
        var result = readCast(document(row(KEYS.getFirst()), row("resident/v1/otto-marsh/everyday"),
                row("resident/v1/otto-marsh/work")), allowed);
        assertEquals(java.util.Set.of(KEYS.getFirst()), result.assets().keySet());
        assertEquals(2, result.skipped());
    }

    @Test void aMalformedKeyOrBadSignatureStillRejectsTheWholeFile() {
        var allowed = java.util.Set.of(KEYS.getFirst());
        for (String bad : List.of("resident/v1/otto-marsh/party", "resident/v2/otto-marsh/work", "player/otto", "resident/v1/O/work"))
            assertThrows(IllegalArgumentException.class, () -> readCast(document(row(KEYS.getFirst()), row(bad)), allowed), bad);
        String forged = GSON.toJson(Map.of("key", "resident/v1/otto-marsh/everyday", "value", value,
                "signature", Base64.getEncoder().encodeToString(new byte[512])));
        var error = assertThrows(IllegalArgumentException.class, () -> readCast(document(row(KEYS.getFirst()), forged), allowed));
        assertEquals("Invalid resident appearance manifest", error.getMessage());
    }

    @Test void theBuiltInReaderNeverSkips() {
        // With residents.yml off, an unknown resident key rejects the file.
        assertThrows(IllegalArgumentException.class,
                () -> readSynthetic(document(row(KEYS.getFirst()), row("resident/v1/otto-marsh/everyday"))));
    }
}
