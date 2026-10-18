package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.economy.TransferProof.Refusal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

class LegacyProvenanceTest {
    private static final String DEV39 = "beeee6869ecf15415cd192026eae2aea45cb268e1555d31dea8d47829cb2a83f";

    /** Example settings: the server the install records describe and the folder of its stopped backups. */
    private static final LegacyProvenance.Config CONFIG = new LegacyProvenance.Config("/opt/example-dev", "/var/backups/npc");

    /** An install record in the DEV format: the jar it installed and the stopped backup taken just before. */
    private static byte[] record(String build, String sha, String backup) {
        return ("# DEV" + build + " installation\n\nNPC SHA256 `" + sha + "`.\nStopped backup and verified receipt:\n`/var/backups/npc/"
                + backup + "-access-dev" + build + "/receipt.json`.\n").getBytes(StandardCharsets.UTF_8);
    }

    /** A jar whose bytes hash to the given SHA cannot be made up, so the fake jar's own hash is used as the reviewed one. */
    private static final class Case {
        final Map<String, byte[]> files = new HashMap<>();
        final Properties evidence = new Properties();
        String created = "2026-09-10 12:00:00.000";
        String root = CONFIG.serverRoot();
        LegacyProvenance.Config config = CONFIG;
        Case(byte[] jar) throws Exception {
            String sha = LegacyProvenance.sha256(jar);
            put("install_record", "INSTALLED-dev39.md", record("39", sha, "20260908T202030948661Z"));
            put("next_install_record", "INSTALLED-dev42.md", record("42", "1".repeat(64), "20260912T080000000000Z"));
            files.put("npc.jar", jar);
            evidence.setProperty("row_created_at", created);
            evidence.setProperty("jar_file", "npc.jar");
            evidence.setProperty("jar_sha256", sha);
        }
        void put(String key, String name, byte[] bytes) {
            files.put(name, bytes);
            evidence.setProperty(key, name);
            try { evidence.setProperty(key + "_sha256", LegacyProvenance.sha256(bytes)); }
            catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
        String verify() throws Exception {
            return LegacyProvenance.verify(config, root, created, evidence, name -> {
                byte[] bytes = files.get(name);
                if (bytes == null) throw new java.nio.file.NoSuchFileException(name);
                return bytes;
            }, List.copyOf(files.keySet()), LegacyProvenance.REVIEWED);
        }
        String verify(Map<String, String> reviewed) throws Exception {
            return LegacyProvenance.verify(config, root, created, evidence, files::get, List.copyOf(files.keySet()), reviewed);
        }
    }

    @Test void theReviewedListHoldsOnlyCheckedJars() {
        assertEquals("0.2.0-dev.39", LegacyProvenance.REVIEWED.get(DEV39));
        assertTrue(LegacyProvenance.REVIEWED.keySet().stream().allMatch(sha -> sha.matches("[a-f0-9]{64}")));
        assertEquals(LegacyProvenance.REVIEWED.size(), LegacyProvenance.REVIEWED.values().stream().distinct().count(),
                "one jar per build");
    }

    @Test void anUnreviewedJarIsRefusedEvenWithPerfectPaperwork() throws Exception {
        var c = new Case("not a reviewed jar".getBytes(StandardCharsets.UTF_8));
        var refused = assertThrows(IllegalArgumentException.class, c::verify);
        assertEquals("Jar is not on the reviewed list", refused.getMessage());
    }

    @Test void everyOtherRequirementIsCheckedBeforeTrustingTheJar() throws Exception {
        // Each break below fails before the reviewed-list check, so these run without a real reviewed jar.
        Map<String, java.util.function.Consumer<Case>> breaks = new HashMap<>();
        breaks.put("Install records describe another server", c -> c.root = "/opt/example-prod");
        // A machine without the settings file proves nothing, so held rows stay held.
        breaks.put("No legacy install records are configured on this machine (" + LegacyProvenance.FILE + ")",
                c -> c.config = null);
        breaks.put("Evidence names a different row time", c -> c.evidence.setProperty("row_created_at", "2026-09-10 12:00:00.001"));
        breaks.put("Install record changed", c -> c.evidence.setProperty("install_record_sha256", "0".repeat(64)));
        breaks.put("Next install record changed", c -> c.evidence.setProperty("next_install_record_sha256", "0".repeat(64)));
        breaks.put("Install record does not name this jar",
                c -> c.put("install_record", "INSTALLED-dev39.md", record("39", "0".repeat(64), "20260908T202030948661Z")));
        breaks.put("Jar bytes do not match jar_sha256", c -> c.files.put("npc.jar", "other bytes".getBytes(StandardCharsets.UTF_8)));
        breaks.put("Invalid install_record name", c -> c.evidence.setProperty("install_record", "../etc/passwd"));
        breaks.put("Invalid next_install_record name", c -> c.evidence.setProperty("next_install_record", "../INSTALLED-dev42.md"));
        breaks.put("Invalid jar_file name", c -> c.evidence.setProperty("jar_file", "../npc.jar"));
        breaks.put("Missing evidence field: next_install_record", c -> c.evidence.remove("next_install_record"));
        breaks.put("The next install record must be a different install",
                c -> c.evidence.setProperty("next_install_record", "INSTALLED-dev39.md"));
        // Typed window times are never trusted, even when they would only narrow the window.
        breaks.put("The install window comes from the install records, not typed times",
                c -> c.evidence.setProperty("window_end", "2026-09-30 00:00:00.000"));
        for (var entry : breaks.entrySet()) {
            var c = new Case("jar".getBytes(StandardCharsets.UTF_8));
            entry.getValue().accept(c);
            assertEquals(entry.getKey(), assertThrows(IllegalArgumentException.class, c::verify).getMessage(), entry.getKey());
        }
    }

    @Test void theWindowRunsFromThisInstallsBackupToTheNextAndRefusesRowsNearItsEdges() throws Exception {
        // A reviewed jar is needed to reach the window check, so this call's reviewed list adds the test jar.
        byte[] jar = "window jar".getBytes(StandardCharsets.UTF_8);
        String sha = LegacyProvenance.sha256(jar);
        var reviewed = new HashMap<>(LegacyProvenance.REVIEWED);
        reviewed.put(sha, "test");
        assertFalse(LegacyProvenance.REVIEWED.containsKey(sha));
        // This install's backup is 2026-09-08 20:20:30.948661 UTC; the next one's is 2026-09-12 08:00:00 UTC.
        for (String[] row : new String[][]{
                {"2026-09-08 20:30:30.949", "ok"}, {"2026-09-08 20:30:30.948", "edge"},
                {"2026-09-12 07:50:00.000", "ok"}, {"2026-09-12 07:50:00.001", "edge"},
                {"2026-09-07 12:00:00.000", "edge"}, {"2026-09-13 12:00:00.000", "edge"},
                {"2026-09-10 12:00:00.000", "ok"}}) {
            var c = new Case(jar);
            c.created = row[0];
            c.evidence.setProperty("row_created_at", row[0]);
            String verdict;
            try { verdict = c.verify(reviewed); }
            catch (IllegalArgumentException refused) {
                assertTrue(refused.getMessage().contains("install window"), refused.getMessage());
                verdict = "edge";
            }
            assertEquals(row[1], verdict.equals("test") ? "ok" : verdict, row[0]);
        }
        // A next record from before this install leaves no window at all.
        var empty = new Case(jar);
        empty.put("next_install_record", "INSTALLED-dev38.md", record("38", "2".repeat(64), "20260908T192050462859Z"));
        assertEquals("Install window is empty", assertThrows(IllegalArgumentException.class, () -> empty.verify(reviewed)).getMessage());
    }

    @Test void aRecordMustNameExactlyOneStoppedBackup() throws Exception {
        byte[] jar = "backup jar".getBytes(StandardCharsets.UTF_8);
        String sha = LegacyProvenance.sha256(jar);
        var reviewed = new HashMap<>(LegacyProvenance.REVIEWED);
        reviewed.put(sha, "test");
        var none = new Case(jar);
        none.put("install_record", "INSTALLED-dev39.md",
                ("# DEV39\nNPC SHA256 `" + sha + "`. Backup taken on 2026-09-08.\n").getBytes(StandardCharsets.UTF_8));
        assertEquals("install_record must name exactly one stopped backup",
                assertThrows(IllegalArgumentException.class, () -> none.verify(reviewed)).getMessage());
        var two = new Case(jar);
        byte[] twice = (new String(record("42", "1".repeat(64), "20260912T080000000000Z"), StandardCharsets.UTF_8)
                + "Also `/var/backups/npc/20260915T080000Z-access-dev43`.\n").getBytes(StandardCharsets.UTF_8);
        two.put("next_install_record", "INSTALLED-dev42.md", twice);
        assertEquals("next_install_record must name exactly one stopped backup",
                assertThrows(IllegalArgumentException.class, () -> two.verify(reviewed)).getMessage());
        // The same backup named twice is still one backup.
        var repeated = new Case(jar);
        byte[] again = (new String(record("39", sha, "20260908T202030948661Z"), StandardCharsets.UTF_8)
                + "Restore from `/var/backups/npc/20260908T202030948661Z-access-dev39`.\n").getBytes(StandardCharsets.UTF_8);
        repeated.put("install_record", "INSTALLED-dev39.md", again);
        assertEquals("test", repeated.verify(reviewed));
    }

    @Test void theNextRecordMustBeTheInstallStraightAfterAmongEveryRecordBesideTheEvidence() throws Exception {
        byte[] jar = "adjacent jar".getBytes(StandardCharsets.UTF_8);
        String sha = LegacyProvenance.sha256(jar);
        var reviewed = new HashMap<>(LegacyProvenance.REVIEWED);
        reviewed.put(sha, "test");
        // A is dev39 (backup 09-08), B is dev42 (09-12), C is dev45 (09-15). The row is from 09-10.
        byte[] b = record("42", "1".repeat(64), "20260912T080000000000Z");
        byte[] c = record("45", "3".repeat(64), "20260915T080000000000Z");
        var skipping = new Case(jar);
        skipping.files.put("INSTALLED-dev42.md", b);
        skipping.put("next_install_record", "INSTALLED-dev45.md", c);
        var refused = assertThrows(IllegalArgumentException.class, () -> skipping.verify(reviewed));
        assertTrue(refused.getMessage().startsWith("Install record INSTALLED-dev42.md lies between"), refused.getMessage());
        assertTrue(refused.getMessage().contains("a complete install log is required"), refused.getMessage());
        // A to B, with C beside them, is accepted: C came after B.
        var adjacent = new Case(jar);
        adjacent.files.put("INSTALLED-dev45.md", c);
        assertEquals("test", adjacent.verify(reviewed));
        // Order comes from each record's stopped backup, not from its name.
        var renamed = new Case(jar);
        renamed.files.put("INSTALLED-dev40.md", record("40", "4".repeat(64), "20260911T080000000000Z"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> renamed.verify(reviewed)).getMessage()
                .startsWith("Install record INSTALLED-dev40.md lies between"));
        // Every install record beside the evidence must be readable as one: an install that cannot be placed could
        // be the one in between.
        var unreadable = new Case(jar);
        unreadable.files.put("INSTALLED-dev41.md", "# DEV41, backup not noted\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("INSTALLED-dev41.md must name exactly one stopped backup",
                assertThrows(IllegalArgumentException.class, () -> unreadable.verify(reviewed)).getMessage());
        // Files that are not install records do not count.
        var other = new Case(jar);
        other.files.put("notes.md", "/var/backups/npc/20260910T000000Z-access-dev40".getBytes(StandardCharsets.UTF_8));
        assertEquals("test", other.verify(reviewed));
        // The two named records must be among the records the tool can see.
        var hidden = new Case(jar);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> LegacyProvenance.verify(hidden.config, hidden.root,
                hidden.created, hidden.evidence, hidden.files::get, List.of("INSTALLED-dev39.md", "npc.jar"), reviewed)).getMessage()
                .contains("a complete install log is required"));
    }

    @Test void onlyBackupsInTheConfiguredFolderBoundAWindow() throws Exception {
        byte[] jar = "folder jar".getBytes(StandardCharsets.UTF_8);
        String sha = LegacyProvenance.sha256(jar);
        var reviewed = new HashMap<>(LegacyProvenance.REVIEWED);
        reviewed.put(sha, "test");
        // The same records read with another backup folder name no stopped backup at all.
        var elsewhere = new Case(jar);
        elsewhere.config = new LegacyProvenance.Config(CONFIG.serverRoot(), "/var/backups/other");
        assertEquals("install_record must name exactly one stopped backup",
                assertThrows(IllegalArgumentException.class, () -> elsewhere.verify(reviewed)).getMessage());
        // The folder is matched as written: a "." in it is not a wildcard.
        var dotted = new Case(jar);
        dotted.config = new LegacyProvenance.Config(CONFIG.serverRoot(), "/var/backups/np.");
        assertEquals("install_record must name exactly one stopped backup",
                assertThrows(IllegalArgumentException.class, () -> dotted.verify(reviewed)).getMessage());
        // A backup in another folder does not count as a second backup either.
        var mixed = new Case(jar);
        byte[] extra = (new String(record("39", sha, "20260908T202030948661Z"), StandardCharsets.UTF_8)
                + "Copied from `/var/backups/other/20260909T000000Z-access-dev39`.\n").getBytes(StandardCharsets.UTF_8);
        mixed.put("install_record", "INSTALLED-dev39.md", extra);
        assertEquals("test", mixed.verify(reviewed));
    }

    @Test void theSettingsNameOneServerAndOneBackupFolder() {
        assertEquals(CONFIG, LegacyProvenance.parse(
                "# legacy install records\n\nserver_root=/opt/example-dev\r\n# stopped backups\nbackup_dir=/var/backups/npc\n"
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals(CONFIG, LegacyProvenance.parse(
                "backup_dir=/var/backups/npc\nserver_root=/opt/example-dev".getBytes(StandardCharsets.UTF_8)));
        String lines = "Legacy provenance settings lines must be server_root=/absolute/path or backup_dir=/absolute/path";
        Map<String, String> refused = new java.util.LinkedHashMap<>();
        refused.put("", "Legacy provenance settings need both server_root and backup_dir");
        refused.put("# only a comment\n", "Legacy provenance settings need both server_root and backup_dir");
        refused.put("server_root=/opt/example-dev\n", "Legacy provenance settings need both server_root and backup_dir");
        refused.put("backup_dir=/var/backups/npc\n", "Legacy provenance settings need both server_root and backup_dir");
        refused.put("server_root=/opt/example-dev\nbackup_dir=/var/backups/npc\nserver_root=/opt/example-dev\n",
                "Legacy provenance settings repeat server_root");
        refused.put("server_root=/opt/example-dev\nbackup_dir=/var/backups/npc\nbackup_dir=/var/backups/other\n",
                "Legacy provenance settings repeat backup_dir");
        refused.put("server_root=opt/example-dev\nbackup_dir=/var/backups/npc\n", lines);
        refused.put("server_root=/opt/example-dev\nbackup_dir=var/backups/npc\n", lines);
        refused.put("server_root=/opt/../example-dev\nbackup_dir=/var/backups/npc\n", lines);
        refused.put("server_root=/opt/example-dev\nbackup_dir=/var/backups/npc/..\n", lines);
        refused.put("server_root=/opt/example-dev\nbackup_dir=/var/./backups\n", lines);
        refused.put("server_root=/opt/example dev\nbackup_dir=/var/backups/npc\n", lines);
        refused.put("server_root=/opt/example-dev\nbackup_dir=/var/backups/npc/\n", lines);
        refused.put("server_root=/opt/example-dev\nbackup_dir=/var/backups/npc\nservice=minecraft\n", lines);
        refused.put("server_root = /opt/example-dev\nbackup_dir=/var/backups/npc\n", lines);
        refused.put("server_root=/opt/example-dev\n  \nbackup_dir=/var/backups/npc\n", lines);
        refused.put("server_root=/opt/example-dev\nthis is not a setting\nbackup_dir=/var/backups/npc\n", lines);
        for (var entry : refused.entrySet())
            assertEquals(entry.getValue(), assertThrows(IllegalArgumentException.class,
                    () -> LegacyProvenance.parse(entry.getKey().getBytes(StandardCharsets.UTF_8))).getMessage(), entry.getKey());
        assertEquals("Legacy provenance settings must be UTF-8", assertThrows(IllegalArgumentException.class,
                () -> LegacyProvenance.parse(new byte[]{'s', '=', '/', (byte) 0xff})).getMessage());
        assertThrows(IllegalArgumentException.class, () -> new LegacyProvenance.Config("/opt/example-dev", "backups"));
    }

    @Test void aRowFromALaterInstallCannotBorrowAnEarlierWindow() throws Exception {
        // The window ends at the next install's backup, which the operator cannot move. A row written after that
        // install, by a build that may have called Vault, is refused.
        byte[] jar = "earlier jar".getBytes(StandardCharsets.UTF_8);
        String sha = LegacyProvenance.sha256(jar);
        var reviewed = new HashMap<>(LegacyProvenance.REVIEWED);
        reviewed.put(sha, "test");
        var later = new Case(jar);
        later.created = "2026-09-12 09:00:00.000";
        later.evidence.setProperty("row_created_at", later.created);
        assertThrows(IllegalArgumentException.class, () -> later.verify(reviewed));
    }

    @Test void heldRowsSortIntoTheirRecoveryPaths() {
        Observation movedDebit = new Observation(true, true, 10.0, 100.0, 90.0, false);
        Observation legacyDebit = new Observation(false, false, null, 100.001, 100.001, true);
        Observation legacyCredit = new Observation(false, false, null, 20.005, 20.005, true);
        Observation refusedCredit = Observation.refused(Refusal.LEASE_REFUSED, null, null, null);
        Observation movedCredit = new Observation(true, true, 10.0, 20.0, 30.0, false);
        Observation shortCredit = new Observation(true, true, 10.0, 20.0, 29.99, false);
        assertEquals(HeldClass.D_LEGACY_DEBIT_REFUSAL, HeldClass.of(true, 1000, false, legacyDebit, null));
        assertEquals(HeldClass.C_LEGACY_CREDIT_REFUSAL, HeldClass.of(true, 1000, true, movedDebit, legacyCredit));
        assertEquals(HeldClass.R_REFUSED_CREDIT, HeldClass.of(true, 1000, true, movedDebit, refusedCredit));
        assertEquals(HeldClass.E_NOW_PROVABLE, HeldClass.of(true, 1000, true, movedDebit, movedCredit));
        assertEquals(HeldClass.E_NOW_PROVABLE, HeldClass.of(true, 1000, false,
                new Observation(true, false, 0.0, 100.001, 100.001, false), null));
        assertEquals(HeldClass.U_UNPROVABLE, HeldClass.of(true, 1000, true, movedDebit, shortCredit));
        assertEquals(HeldClass.U_UNPROVABLE, HeldClass.of(true, 1000, false, null, null), "no observation");
        assertEquals(HeldClass.U_UNPROVABLE, HeldClass.of(false, 1000, false, legacyDebit, null), "an interrupted intent");
        assertEquals(HeldClass.U_UNPROVABLE, HeldClass.of(true, 1000, true, legacyDebit, legacyCredit), "credit after an unmoved debit");
        assertEquals(HeldClass.U_UNPROVABLE, HeldClass.of(true, 1000, false,
                new Observation(false, false, null, 100.0, 100.0, true), null), "the old gate would have passed");
    }

    @Test
    void namesInListsAFoldersFileNamesSorted(@TempDir Path dir) throws Exception {
        Files.createFile(dir.resolve("b.md"));
        Files.createFile(dir.resolve("A.md"));
        Files.createDirectory(dir.resolve("c"));
        assertEquals(List.of("A.md", "b.md", "c"), LegacyProvenance.namesIn(dir));
        assertEquals(List.of(), LegacyProvenance.namesIn(Files.createDirectory(dir.resolve("empty"))));
    }
}
