package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The payment runtime's pure decisions: owner-away, opt-in, the reserve floor, balances, fingerprints and replies. */
class UtilityPaymentRuntimeHelpersTest {
    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @ParameterizedTest(name = "away={0} recurring={1} incident={2} purchase={3}")
    @CsvSource({
            "false,false,0,false,false", "false,true,0,false,false", "false,false,7,false,false", "false,false,0,true,false",
            "false,true,7,true,false",
            "true,false,0,false,true", "true,true,0,false,true", "true,true,7,false,true", "true,true,0,true,true",
            "true,true,7,true,true", "true,false,7,false,false", "true,false,0,true,false", "true,false,7,true,false",
            "true,false,-1,false,false"})
    void ownerAwayBlocksRecurringChargesAndManualUtilitiesButNotOwnerChosenWork(boolean away, boolean recurring,
                                                                                long incident, boolean purchase,
                                                                                boolean blocked) {
        assertEquals(blocked, UtilityPaymentRuntime.awayBlocks(away, recurring, incident, purchase));
    }

    @ParameterizedTest
    @CsvSource(value = {"true,null,true", "false,null,false", "null,true,true", "null,false,false", "null,null,false",
            "false,true,false", "true,false,true", "TRUE_TEXT,null,false", "null,TRUE_TEXT,false", "ONE,null,false"},
            nullValues = "null")
    void paymentsOpenOnlyForARealBooleanTrueAndTheNewKeyWinsWhenSet(String current, String legacy, boolean open) {
        assertEquals(open, UtilityPaymentRuntime.paymentOptIn(value(current), value(legacy)));
    }

    private static Object value(String text) {
        if (text == null) return null;
        return switch (text) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            case "TRUE_TEXT" -> "true";
            case "ONE" -> 1;
            default -> throw new IllegalArgumentException(text);
        };
    }

    @ParameterizedTest
    @CsvSource({"1300,300,1000,true", "1299,300,1000,false", "300,300,0,true", "299,300,0,false", "0,0,0,true",
            "100,300,-500,false", "100,50,-500,true", "9223372036854775807,1,9223372036854775806,true",
            "9223372036854775807,1,9223372036854775807,false"})
    void theReserveFloorMustSurviveThePayout(long available, long cents, long floor, boolean allowed) {
        assertEquals(allowed, UtilityPaymentRuntime.floorAllows(available, cents, floor));
    }

    @Test
    void readableCentsIsEmptyWhenTheBalanceCannotBeRead() {
        assertEquals(OptionalLong.empty(), UtilityPaymentRuntime.readableCents(() -> { throw new IllegalStateException("x"); }));
        assertEquals(OptionalLong.empty(), UtilityPaymentRuntime.readableCents(() -> { throw new NoClassDefFoundError("x"); }));
        assertEquals(OptionalLong.empty(), UtilityPaymentRuntime.readableCents(() -> Double.NaN));
        assertEquals(OptionalLong.empty(), UtilityPaymentRuntime.readableCents(() -> -0.01));
        assertEquals(OptionalLong.empty(), UtilityPaymentRuntime.readableCents(() -> Double.POSITIVE_INFINITY));
    }

    @Test
    void readableCentsKeepsAWholeDollarBalanceExactly() {
        assertEquals(OptionalLong.of(2_500), UtilityPaymentRuntime.readableCents(() -> 25.0));
        assertEquals(OptionalLong.of(0), UtilityPaymentRuntime.readableCents(() -> 0.0));
    }

    @Test
    void anUnreadableAwayDecisionIsUnknownNotFalse() {
        assertEquals(Boolean.TRUE, UtilityPaymentRuntime.awayDecision(() -> true));
        assertEquals(Boolean.FALSE, UtilityPaymentRuntime.awayDecision(() -> false));
        assertNull(UtilityPaymentRuntime.awayDecision(() -> { throw new IllegalStateException("db"); }));
        assertNull(UtilityPaymentRuntime.awayDecision(() -> { throw new NoClassDefFoundError("x"); }));
    }

    @Test
    void appreciationIsPaidForTheDayBeforeTheBatch() {
        assertEquals(LocalDate.of(2026, 10, 13), UtilityPaymentRuntime.appreciationDay(LocalDate.of(2026, 10, 14)));
        assertEquals(LocalDate.of(2025, 12, 31), UtilityPaymentRuntime.appreciationDay(LocalDate.of(2026, 1, 1)));
        assertEquals(LocalDate.of(2028, 2, 29), UtilityPaymentRuntime.appreciationDay(LocalDate.of(2028, 3, 1)));
    }

    @Test
    void theProviderFingerprintIsTheSha256OfTheJar(@TempDir Path dir) throws Exception {
        Path jar = Files.write(dir.resolve("provider.jar"), "abc".getBytes(StandardCharsets.US_ASCII));
        assertEquals(ABC_SHA256, UtilityPaymentRuntime.hash(jar));
        assertEquals(ABC_SHA256.substring(0, 8), MoneySources.sha8(jar, UtilityPaymentRuntime::hash));
    }

    @Test
    void anEmptyMissingOrDirectoryJarHasNoFingerprint(@TempDir Path dir) throws Exception {
        Path empty = Files.createFile(dir.resolve("empty.jar"));
        for (Path path : List.of(empty, dir.resolve("missing.jar"), dir)) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> UtilityPaymentRuntime.hash(path));
            assertEquals("Provider fingerprint unavailable", refused.getMessage(), path.toString());
        }
    }

    @Test
    void theRunLineNamesTheRunBuildAndJar(@TempDir Path dir) throws Exception {
        UUID run = UUID.fromString("00000000-0000-4000-8000-000000000001");
        Path jar = Files.write(dir.resolve("npc.jar"), "abc".getBytes(StandardCharsets.US_ASCII));
        assertEquals("NPC run " + run + " build 0.2.0-dev.67 jar " + ABC_SHA256,
                UtilityPaymentRuntime.runLine("NPC run", run, "0.2.0-dev.67", jar));
        assertEquals("NPC run " + run + " build b jar unavailable", UtilityPaymentRuntime.runLine("NPC run", run, "b", null));
        assertEquals("NPC run " + run + " build b jar unavailable",
                UtilityPaymentRuntime.runLine("NPC run", run, "b", dir.resolve("gone.jar")));
    }

    @Test
    void aReleaseThatThrowsIsLoggedAndReportedAsUnreleased() {
        List<String> warnings = new ArrayList<>();
        assertTrue(UtilityPaymentRuntime.releasedQuietly(() -> { }, warnings::add, "never"));
        assertEquals(List.of(), warnings);
        assertFalse(UtilityPaymentRuntime.releasedQuietly(() -> { throw new IllegalStateException("x"); }, warnings::add, "w1"));
        assertFalse(UtilityPaymentRuntime.releasedQuietly(() -> { throw new NoClassDefFoundError("x"); }, warnings::add, "w2"));
        assertEquals(List.of("w1", "w2"), warnings);
    }

    @Test
    void aBrokenWarningLogStillReportsTheReleaseAsUnreleased() {
        assertFalse(UtilityPaymentRuntime.releasedQuietly(() -> { throw new IllegalStateException("x"); },
                text -> { throw new IllegalStateException("log"); }, "w"));
    }

    @Test
    void theNotStartedRepliesSayNothingWasCharged() {
        assertEquals("No payment was started. Nothing was charged.", UtilityPaymentRuntime.NOT_STARTED_REPLY);
        assertEquals("Rent batch not started; nothing was paid.", UtilityPaymentRuntime.RENT_NOT_STARTED_REPLY);
        assertEquals("Rent batch skipped: every owner is away today. No money moved.", UtilityPaymentRuntime.ALL_AWAY_REPLY);
    }
}
