package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.Piece;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import com.prosperitysmp.npc.pieces.PieceSelector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The /npcadmin diagnose text: the format staff see, and the 30-line cap. */
class PieceDiagnosisTest {

    private static Piece piece(String id, String label) {
        return new Piece() {
            @Override public String id() { return id; }
            @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
            @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
            @Override public Capabilities capabilities() { return Capabilities.builder().build(); }
            @Override public String label() { return label; }
        };
    }

    @Test void theDefaultBootReadsAsTheSpecShowsIt() {
        var settings = List.of(
                new PieceDiagnosis.Setting("money.source", "vault", true, "provider Essentials via Vault; scale 2; artifact sha256 1a2b3c4d"),
                new PieceDiagnosis.Setting("property.backend", "arm", true, "guard write-hook installed; owner model: one UUID owner, no members"),
                new PieceDiagnosis.Setting("accounting.sink", "skript", true, "Skript heartbeat present"));
        var listing = new PieceSelector<>(Piece.class).select("arm", Map.of("arm", piece("arm", "")), List.of(
                new PieceSelector.Candidate<>(piece("example-null", "DEV only"), "ProsperityNPC-Example", "0.1.0", 2),
                new PieceSelector.Candidate<>(piece("vault", ""), "SomePlugin", "1.2", 2)));
        assertEquals(List.of(
                "ProsperityNPC pieces (API 1.2)",
                "money.source: vault [in use] provider Essentials via Vault; scale 2; artifact sha256 1a2b3c4d",
                "property.backend: arm [in use] guard write-hook installed; owner model: one UUID owner, no members",
                "accounting.sink: skript [in use] Skript heartbeat present",
                "found, not in use: example-null (ProsperityNPC-Example 0.1.0, API 1.2, DEV only)",
                "refused: vault (from SomePlugin 1.2): reserved id"), PieceDiagnosis.lines(settings, List.of(listing)));
    }

    @Test void theDefaultBootIsUnchangedWhenTheConfigStillNamesThePiecesInUse() {
        var plain = List.of(
                new PieceDiagnosis.Setting("money.source", "vault", true, "provider Essentials via Vault; scale 2; artifact sha256 1a2b3c4d"),
                new PieceDiagnosis.Setting("property.backend", "arm", true, "guard write-hook installed; owner model: one UUID owner, no members"),
                new PieceDiagnosis.Setting("accounting.sink", "skript", true, "Skript heartbeat present"));
        var withLive = List.of(
                new PieceDiagnosis.Setting("money.source", "vault", true, "provider Essentials via Vault; scale 2; artifact sha256 1a2b3c4d", "", "vault"),
                new PieceDiagnosis.Setting("property.backend", "arm", true, "guard write-hook installed; owner model: one UUID owner, no members", "", "arm"),
                new PieceDiagnosis.Setting("accounting.sink", "skript", true, "Skript heartbeat present"));
        assertEquals(PieceDiagnosis.lines(plain, List.of()), PieceDiagnosis.lines(withLive, List.of()));
    }

    @Test void theInUseLineShowsItsLabelAndAChangedConfigAsksForARestart() {
        var settings = List.of(
                new PieceDiagnosis.Setting("money.source", "vault", true, "provider Essentials via Vault; scale 2; artifact sha256 1a2b3c4d", "", "foo"),
                new PieceDiagnosis.Setting("property.backend", "griefprevention", true, "guard veto-events installed", "unverified", "griefprevention"));
        assertEquals(List.of(
                "ProsperityNPC pieces (API 1.2)",
                "money.source: vault [in use] provider Essentials via Vault; scale 2; artifact sha256 1a2b3c4d",
                "money.source: configured foo; restart required",
                "property.backend: griefprevention [in use] guard veto-events installed; label: unverified"),
                PieceDiagnosis.lines(settings, List.of()));
    }

    @Test void adapterSuppliedTextIsCleanedAndCapped() {
        String longId = "x".repeat(200);
        var listing = new PieceSelector<>(Piece.class).select("arm", Map.of("arm", piece("arm", "")), List.of(
                new PieceSelector.Candidate<>(piece("ok-piece", "tested\u00a7\u0000" + "y".repeat(100)), "P", "1", 2),
                new PieceSelector.Candidate<>(piece("bad\u00a7cid\n", ""), "Evil\u0007Plugin", "1\r\n", 2),
                new PieceSelector.Candidate<>(piece(longId, ""), "P", "1", 2)));
        var settings = List.of(new PieceDiagnosis.Setting("property.backend", "arm", true, "guard write-hook installed",
                "lab\u00a7el\u001b[0m", "arm\u00a7x"));
        var lines = PieceDiagnosis.lines(settings, List.of(listing));
        assertEquals(List.of(
                "ProsperityNPC pieces (API 1.2)",
                "property.backend: arm [in use] guard write-hook installed; label: label[0m",
                "property.backend: configured armx; restart required",
                "found, not in use: ok-piece (P 1, API 1.2, tested" + "y".repeat(74) + ")",
                "refused: badcid (from EvilPlugin 1): invalid id 'badcid'",
                "refused: " + "x".repeat(80) + " (from P 1): " + ("invalid id '" + longId + "'").substring(0, 160)), lines);
        for (String line : lines) assertFalse(line.matches("(?s).*[\\p{Cntrl}\u00a7].*"), line);
        assertEquals("", PieceDiagnosis.clean(null));
    }

    @Test void billsRepliesForABusyWorkerAndACumulativePreview() throws Exception {
        assertEquals("Bills worker busy; try again.", NpcAdminCommand.BILLS_BUSY);
        assertEquals("  water: raw 120 cents, max 500, meter water-meter {\"units\":12}",
                NpcAdminCommand.billPreviewLine("water", 120, 500, "water-meter", "{\"units\":12}", false), "unchanged");
        assertEquals("  power: raw 9000 cents, max 20000, meter kwh {\"units\":900} running total (preview shows no baseline)",
                NpcAdminCommand.billPreviewLine("power", 9000, 20000, "kwh", "{\"units\":900}", true));
        String command = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/prosperitysmp/npc/command/NpcAdminCommand.java"));
        String bills = command.substring(command.indexOf("private void doBills("), command.indexOf("static final String BILLS_BUSY"));
        assertEquals(2, bills.split("catch \\(java\\.util\\.concurrent\\.RejectedExecutionException busy\\)", -1).length - 1,
                "status and preview both catch a full worker queue");
        assertEquals(2, bills.split("send\\(sender, NamedTextColor\\.RED, BILLS_BUSY\\);", -1).length - 1);
        assertTrue(bills.contains("if (\"cumulative\".equals(choice.kind())) cumulative.add(p.meter().id());"));
    }

    @Test void neverMoreThanThirtyLines() {
        var candidates = new ArrayList<PieceSelector.Candidate<Piece>>();
        for (int i = 0; i < 40; i++) candidates.add(new PieceSelector.Candidate<>(piece("piece-" + i, ""), "P", "1", 2));
        var listing = new PieceSelector<>(Piece.class).select("arm", Map.of("arm", piece("arm", "")), candidates);
        var lines = PieceDiagnosis.lines(List.of(), List.of(listing));
        assertEquals(PieceDiagnosis.MAX_LINES, lines.size());
        assertTrue(lines.getLast().startsWith("... "), lines.getLast());
    }
}
