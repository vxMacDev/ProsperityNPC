package com.prosperitysmp.npc;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden for the daily cycle's replies, recorded on the dev.64 tree before any bills work. Every text the cycle
 * step can send or log is listed in source order. With bills off the cycle must say exactly these things and no
 * others, so a new phase may route calls but may not add or change a reply.
 */
class DailyCycleGoldenTest {
    static final List<String> DEV64_LITERALS = List.of(
            "Stopped before next phase; committed receipts are retained.",
            "Cycle finished. Individual phase receipts determine actual outcomes.",
            "Daily cycle reply unavailable",
            "Daily cycle reply unavailable",
            "Phase ",
            " unavailable or busy; later phases not started.",
            "phase-",
            "Daily cycle phase ",
            " did not start; later phases were not run.");

    static List<String> literals(String body) {
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
        while (m.find()) found.add(m.group(1));
        return found;
    }

    static String dailyPhaseBody() throws Exception {
        String plugin = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/ProsperityNpcPlugin.java"));
        int start = plugin.indexOf("private void runDailyPhase(int phase,java.util.function.BooleanSupplier authority,");
        int end = plugin.indexOf("/** Reloads and re-validates the economy limits");
        return plugin.substring(start, end);
    }

    @Test void theCycleSaysExactlyWhatDev64Said() throws Exception {
        assertEquals(DEV64_LITERALS, literals(dailyPhaseBody()));
    }

    @Test void theExtractorSeesALiteral() {
        // Positive control: the extractor finds a literal, so an empty list above is a real result.
        assertEquals(List.of("a", "b\\\"c"), literals("x(\"a\"); y(\"b\\\"c\");"));
    }
}
