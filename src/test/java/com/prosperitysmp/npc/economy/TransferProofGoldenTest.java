package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.TransferProof.Leg;
import com.prosperitysmp.npc.economy.TransferProof.LegOutcome;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the leg rule as it stood before fractional balances became payable. The two CSV files were written by
 * this generator against that code. Rows whose balances are both whole cents must never change. Any other row
 * may only move away from AMBIGUOUS, and then only to the answer of the exact-decimal check below, which is
 * written separately from TransferProof. Set NPC_GOLDEN_RECORD_DIR to write fresh files instead of comparing.
 */
class TransferProofGoldenTest {
    private static final long[] CENTS = {1, 29, 99, 500, 10_000, 100_000_001, 838_860_799};
    private static final double[] BALANCES = {0, 0.29, 100.0, 1_000_000.01, 8_388_607.99, 57492.488404927248686, 8_388_608.0};
    private static final String EVALUATE = "golden/golden-evaluate-v1.csv";
    private static final String TO_CENTS = "golden/golden-tocents-v1.csv";
    private static final BigDecimal CEILING = new BigDecimal("1000000000");

    private record Row(Leg leg, long cents, double before, double after, double reported,
                       boolean present, boolean success, boolean threw) {
        Observation observation() {
            return new Observation(present, success, reported, before, after, threw);
        }
        String key() {
            return leg.name().charAt(0) + "," + cents + "," + hex(before) + "," + hex(after) + "," + hex(reported)
                    + "," + (present ? 1 : 0) + (success ? 1 : 0) + (threw ? 1 : 0);
        }
    }

    static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (Leg leg : Leg.values()) {
            for (long cents : CENTS) {
                for (double base : BALANCES) {
                    for (double before : new double[]{Math.nextDown(base), base, Math.nextUp(base)}) {
                        for (long delta : new long[]{cents, cents - 1, cents + 1, 0}) {
                            double after = leg == Leg.DEBIT ? before - delta / 100.0 : before + delta / 100.0;
                            for (long reported : new long[]{cents, cents - 1, 0}) {
                                for (int flags = 0; flags < 8; flags++) {
                                    rows.add(new Row(leg, cents, before, after, reported / 100.0,
                                            (flags & 4) != 0, (flags & 2) != 0, (flags & 1) != 0));
                                }
                            }
                        }
                    }
                }
            }
        }
        return rows;
    }

    /** Every value the evaluate rows use, plus a seeded spread of raw and in-range bit patterns. */
    static List<Double> conversionInputs(List<Row> rows) {
        Set<Long> bits = new LinkedHashSet<>();
        for (Row row : rows) {
            bits.add(Double.doubleToRawLongBits(row.before()));
            bits.add(Double.doubleToRawLongBits(row.after()));
            bits.add(Double.doubleToRawLongBits(row.reported()));
        }
        SplittableRandom random = new SplittableRandom(20261014L);
        for (int i = 0; i < 6_000; i++) bits.add(random.nextLong());
        for (int i = 0; i < 6_000; i++) bits.add(Double.doubleToRawLongBits(random.nextLong(0, 1_000_000_000_000L) / 100.0));
        for (int i = 0; i < 6_000; i++) {
            double value = random.nextDouble() * Math.pow(10, random.nextInt(-3, 11));
            bits.add(Double.doubleToRawLongBits(random.nextBoolean() ? value : Math.nextUp(value)));
        }
        List<Double> values = new ArrayList<>();
        for (long b : bits) values.add(Double.longBitsToDouble(b));
        return values;
    }

    @Test
    void evaluateKeepsEveryWholeCentAnswer() throws IOException {
        List<Row> rows = rows();
        List<String> golden = recordOrRead(EVALUATE, rows.stream().map(r -> r.key() + "," + code(
                TransferProof.evaluate(r.cents(), r.leg(), r.observation()))).toList());
        assertEquals(rows.size(), golden.size(), "golden row count");
        assertTrue(rows.size() > 20_000, "the generator covers the whole matrix");
        int wholeCent = 0, moved = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            String[] stored = golden.get(i).split(",");
            assertEquals(row.key(), golden.get(i).substring(0, golden.get(i).lastIndexOf(',')), "generator drift at row " + i);
            LegOutcome before = decode(stored[stored.length - 1]);
            LegOutcome now = TransferProof.evaluate(row.cents(), row.leg(), row.observation());
            boolean bothWhole = TransferProof.toCents(row.before()).isPresent() && TransferProof.toCents(row.after()).isPresent();
            if (bothWhole) {
                wholeCent++;
                assertEquals(before, now, "whole-cent row " + i + " " + row);
            } else if (before != now) {
                moved++;
                assertEquals(LegOutcome.AMBIGUOUS, before, "only an ambiguous row may change: " + row);
                assertEquals(independentAnswer(row), now, "changed row must match the exact check: " + row);
            }
        }
        assertTrue(wholeCent > 10_000, "positive control: whole-cent rows were compared");
        assertTrue(moved > 100, "positive control: fractional rows now resolve");
        // Every row outside the whole-cent rule, changed or not, agrees with the exact check.
        for (Row row : rows) {
            if (TransferProof.toCents(row.before()).isPresent() && TransferProof.toCents(row.after()).isPresent()) continue;
            assertEquals(independentAnswer(row), TransferProof.evaluate(row.cents(), row.leg(), row.observation()), row.toString());
        }
    }

    /**
     * Whole-cent balances carrying noise under the documented 1e-9 per value. The whole-cent rule accepts these
     * and the exact rule alone would not, so this file proves the whole-cent rule still decides them. It was
     * written by the same generator run against the earlier TransferProof on its own.
     */
    static List<Row> noisyRows() {
        long[] cents = {1, 29, 500, 10_000, 100_000_001};
        double[] bases = {0.29, 100.0, 1_000_000.01, 57492.48};
        double[] noise = {-9e-10, -4e-10, -1e-10, 0, 1e-10, 4e-10, 9e-10};
        List<Row> rows = new ArrayList<>();
        for (Leg leg : Leg.values())
            for (long c : cents)
                for (double base : bases)
                    for (double nb : noise)
                        for (long delta : new long[]{c, c - 1, 0})
                            for (double na : noise)
                                for (int flags : new int[]{6, 4}) {
                                    double before = base + nb + (leg == Leg.DEBIT ? c / 100.0 : 0);
                                    double after = (leg == Leg.DEBIT ? base + c / 100.0 - delta / 100.0 : base + delta / 100.0) + na;
                                    rows.add(new Row(leg, c, before, after, c / 100.0, (flags & 4) != 0, (flags & 2) != 0, false));
                                }
        return rows;
    }

    @Test
    void wholeCentNoiseIsStillDecidedByTheWholeCentRule() throws IOException {
        List<Row> rows = noisyRows();
        List<String> golden = recordOrRead("golden/golden-evaluate-v2.csv", rows.stream().map(r -> r.key() + "," + code(
                TransferProof.evaluate(r.cents(), r.leg(), r.observation()))).toList());
        assertEquals(rows.size(), golden.size(), "golden row count");
        int wholeCent = 0, exactWouldDiffer = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            String line = golden.get(i);
            assertEquals(row.key(), line.substring(0, line.lastIndexOf(',')), "generator drift at row " + i);
            LegOutcome before = decode(line.substring(line.lastIndexOf(',') + 1));
            LegOutcome now = TransferProof.evaluate(row.cents(), row.leg(), row.observation());
            if (TransferProof.toCents(row.before()).isPresent() && TransferProof.toCents(row.after()).isPresent()) {
                wholeCent++;
                assertEquals(before, now, "whole-cent row " + i + " " + row);
                if (independentAnswer(row) != before) exactWouldDiffer++;
            } else if (before != now) {
                assertEquals(LegOutcome.AMBIGUOUS, before, "only an ambiguous row may change: " + row);
                assertEquals(independentAnswer(row), now, "changed row must match the exact check: " + row);
            }
        }
        assertTrue(wholeCent > 5_000, "positive control: whole-cent rows were compared");
        assertTrue(exactWouldDiffer > 1_000, "positive control: these rows would change if the exact rule decided them");
    }

    @Test
    void toCentsIsUnchangedOnEveryRecordedBitPattern() throws IOException {
        List<Double> inputs = conversionInputs(rows());
        List<String> golden = recordOrRead(TO_CENTS, inputs.stream().map(v -> hex(v) + "," + cents(TransferProof.toCents(v))).toList());
        assertEquals(inputs.size(), golden.size(), "golden conversion count");
        int present = 0;
        for (int i = 0; i < inputs.size(); i++) {
            String expected = golden.get(i);
            assertEquals(expected, hex(inputs.get(i)) + "," + cents(TransferProof.toCents(inputs.get(i))), "conversion row " + i);
            if (!expected.endsWith(",-")) present++;
        }
        assertTrue(present > 1_000 && present < inputs.size(), "positive control: both accepted and rejected values");
    }

    /**
     * The exact rule restated from first principles: the balances are exact binary values below the ceiling,
     * each may be off from the true balance by the gap to the next double, and the move must equal the request
     * within those two gaps. A failure response must show no move beyond the same gaps.
     */
    static LegOutcome independentAnswer(Row row) {
        if (row.threw() || !row.present()) return LegOutcome.AMBIGUOUS;
        OptionalLong reported = TransferProof.toCents(row.reported());
        if (reported.isEmpty()) return LegOutcome.AMBIGUOUS;
        BigDecimal b = exact(row.before()), a = exact(row.after());
        if (b == null || a == null) return LegOutcome.AMBIGUOUS;
        BigDecimal gap = exact(Math.nextUp(row.before())).subtract(b).add(exact(Math.nextUp(row.after())).subtract(a));
        BigDecimal movedBy = row.leg() == Leg.DEBIT ? b.subtract(a) : a.subtract(b);
        if (!row.success()) return movedBy.abs().compareTo(gap) <= 0 ? LegOutcome.NO_MOVEMENT : LegOutcome.AMBIGUOUS;
        return reported.getAsLong() == row.cents()
                && movedBy.subtract(BigDecimal.valueOf(row.cents()).movePointLeft(2)).abs().compareTo(gap) <= 0
                ? LegOutcome.CONFIRMED : LegOutcome.AMBIGUOUS;
    }

    private static BigDecimal exact(double value) {
        if (!Double.isFinite(value) || value < 0) return null;
        BigDecimal exact = new BigDecimal(value);
        return exact.compareTo(CEILING) < 0 ? exact : null;
    }

    private static List<String> recordOrRead(String resource, List<String> current) throws IOException {
        String recordDir = System.getenv("NPC_GOLDEN_RECORD_DIR");
        if (recordDir != null && !recordDir.isBlank()) {
            Path out = Path.of(recordDir).resolve(resource);
            Files.createDirectories(out.getParent());
            Files.write(out, (String.join("\n", current) + "\n").getBytes(StandardCharsets.UTF_8));
            return current;
        }
        try (InputStream in = TransferProofGoldenTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "missing golden file " + resource);
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) if (!line.isEmpty()) lines.add(line);
            }
            return lines;
        }
    }

    private static String hex(double value) { return Long.toHexString(Double.doubleToRawLongBits(value)); }
    private static String cents(OptionalLong value) { return value.isPresent() ? Long.toString(value.getAsLong()) : "-"; }
    private static String code(LegOutcome outcome) { return outcome.name().substring(0, 1); }
    private static LegOutcome decode(String code) {
        for (LegOutcome outcome : LegOutcome.values()) if (code(outcome).equals(code)) return outcome;
        fail("bad outcome code " + code);
        return null;
    }
}
