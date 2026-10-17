package com.prosperitysmp.npc.economy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Proof, from outside the database, of which NPC build wrote a held payment row. The database keeps no build
 * record for old rows, so an operator brings the DEV install record and the exact jar bytes it names, and the
 * install record of the next DEV install. Only a jar on the reviewed list below counts, and only for a row written
 * well inside that install's window. A row whose build cannot be identified stays held. Memory of which build ran
 * does not count, and neither do typed times: each install record names the stopped backup taken just before its
 * install, and the window runs from this install's backup to the next install's backup.
 *
 * The next install record must be the install that came straight after this one. The tool can only see the install
 * records beside the evidence, so every DEV install record must be there: a complete install log is required, and a
 * row is refused when any other record's backup falls inside its window.
 *
 * Evidence fields: row_created_at (UTC, as the DATETIME(3) column prints it: the plugin's sessions write UTC),
 * install_record and install_record_sha256 (an INSTALLED-devNN.md file beside the evidence), next_install_record and
 * next_install_record_sha256 (the record of the install that came straight after it), jar_file (the jar beside the
 * evidence) and jar_sha256. The records describe one server, so only that server's rows can use them.
 *
 * Which server that is, and the folder that holds its stopped backups, come from {@link #FILE} on the machine running
 * the tool, not from this source. It sits beside {@link RecoveryServers#FILE} under the same custody: a private (0600
 * or 0400) root-owned regular file in a root-owned directory that only root can write. Format: one
 * {@code server_root=/absolute/path} line and one {@code backup_dir=/absolute/path} line, e.g.
 * {@code server_root=/opt/example-dev} and {@code backup_dir=/var/backups/npc}; blank lines and {@code #} comments are
 * allowed, nothing else is. Without that file, or with an unsafe or malformed one, no row can use this proof and held
 * rows stay held.
 */
public final class LegacyProvenance {
    /** A row this close to either edge of its install window is refused: the install or restart time is not exact. */
    static final Duration EDGE = Duration.ofMinutes(10);

    /** The root-owned settings naming the server the install records describe and the folder of its stopped backups. */
    public static final Path FILE = Path.of("/etc/prosperitynpc/legacy-provenance.properties");
    private static final int MAX_BYTES = 4096;
    private static final String LABEL = "Legacy provenance settings";
    private static final String LINES = LABEL + " lines must be server_root=/absolute/path or backup_dir=/absolute/path";
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss");
    private static final Pattern RECORD_NAME = Pattern.compile("INSTALLED-dev[0-9]{1,3}\\.md");

    /**
     * Jar SHA-256 to build. Each jar was read with javap -c: in VaultLegObservation.execute, getBalance, then
     * TransferProof.toCents, then a throw when it is empty, then the funds check and its throw, all come before
     * withdrawPlayer or depositPlayer. The bytecode of execute and of TransferProof.toCents is the same in every
     * listed jar, so the old gate that legacyGateRefused replays is the one each of them ran. Builds not listed
     * here, or whose jar bytes are not to hand, are not trusted.
     */
    static final Map<String, String> REVIEWED = Map.ofEntries(
            Map.entry("8ebe50fc257f16dbb88696e362b08215c644aa4e22e9d3effe7af3b6074af16a", "0.2.0-dev.36"),
            Map.entry("7f9b3d09f27d8514c08ccea7678cabf10d1bd900b80a43c95c253b65ee85f973", "0.2.0-dev.37"),
            Map.entry("8c246afe2e29123069330832ea91008654a6c6edaf20e90f678e92e781bc078a", "0.2.0-dev.38"),
            Map.entry("beeee6869ecf15415cd192026eae2aea45cb268e1555d31dea8d47829cb2a83f", "0.2.0-dev.39"),
            Map.entry("111ba1ec2c004810cebf381b0e3d628e5c9de2becc1f614a6f3ca9f577670264", "0.2.0-dev.42"),
            Map.entry("cf4c1e846eb3307b5cab402dc6326bd00fd050339cc6b03f0f8a5d456c8d4fdd", "0.2.0-dev.45"),
            Map.entry("367c4e68cc23966a80ddd258c7e6672bfd73a2eaffb89405c94722a362020dc4", "0.2.0-dev.46"),
            Map.entry("9760c9044abf18dd1e7628463ade07747001032347badbe01e92e87b244d9e03", "0.2.0-dev.49"),
            Map.entry("ea7be5e5d8fe3ab125acf5ae905739c3fff0c2ece766f64e39dcf9e1054bc3e8", "0.2.0-dev.50"),
            Map.entry("c7e6b35a66ef6473f4d82bc90d21a76d18ab18b1fb7d69a8440182a0cf2de383", "0.2.0-dev.51"),
            Map.entry("f38ab4eb1a14d5e41c583f2053eb998f16232807c1635514efd7cf14fc5df70c", "0.2.0-dev.52"),
            Map.entry("4e230ab0ca67d74ab9e9a196a29b20e39adc5686bf12562e3a515ef594906a38", "0.2.0-dev.53"),
            Map.entry("8ec36bc9a8abc6cbb0910a23bcff446f8fc7756a0ff3b63ab130753a7cee2580", "0.2.0-dev.54"),
            Map.entry("f58094627184f224b59526effb4cd53b0c70f42f84bd6a5c7f4a3cb7ee980c15", "0.2.0-dev.55"),
            Map.entry("a0c0ae7c2fe990e2244b99650efc98ebdb989aa0d87924de7c3644ff85bf9e11", "0.2.0-dev.56"),
            Map.entry("4a31fab39ff860753a6fdee335b5a9598c5b19cc748e9520a876fb76a5b03408", "0.2.0-dev.62"));

    /** Reads a file that sits beside the evidence, under the tool's own custody checks. */
    @FunctionalInterface public interface Adjacent { byte[] read(String name) throws Exception; }

    /** The server root the install records describe, and the folder that holds that server's stopped backups. */
    record Config(String serverRoot, String backupDir) {
        Config {
            if (serverRoot == null || backupDir == null || !RecoveryServers.validRoot(serverRoot)
                    || !RecoveryServers.validRoot(backupDir))
                throw new IllegalArgumentException(LINES);
        }

        /** The stopped backup an install record names, for example /var/backups/npc/20260908T202030948661Z-access-dev39. */
        Pattern backup() { return Pattern.compile(Pattern.quote(backupDir) + "/(\\d{8}T\\d{6})(\\d{0,9})Z-"); }
    }

    private LegacyProvenance() { }

    /** Reads {@link #FILE}; null when this machine has none. A file that is there but unsafe or malformed is refused. */
    static Config load() throws Exception {
        if (Files.notExists(FILE, LinkOption.NOFOLLOW_LINKS)) return null;
        return parse(RecoveryServers.readRootOwned(FILE, MAX_BYTES, LABEL));
    }

    static Config parse(byte[] bytes) {
        String text = RecoveryServers.utf8(bytes, LABEL);
        Map<String, String> values = new HashMap<>();
        for (String line : text.split("\n", -1)) {
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isEmpty() || line.startsWith("#")) continue;
            int split = line.indexOf('=');
            String key = split < 0 ? "" : line.substring(0, split), value = split < 0 ? "" : line.substring(split + 1);
            if (!(key.equals("server_root") || key.equals("backup_dir")) || !RecoveryServers.validRoot(value))
                throw new IllegalArgumentException(LINES);
            if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException(LABEL + " repeat " + key);
        }
        if (values.size() != 2) throw new IllegalArgumentException(LABEL + " need both server_root and backup_dir");
        return new Config(values.get("server_root"), values.get("backup_dir"));
    }

    /**
     * Checks the evidence for one row. serverRoot is the tool's verified server root, and rowCreatedAt is the row's
     * created_at as the database returns it. Returns the reviewed build; throws IllegalArgumentException naming the
     * first requirement that fails. The server and its backup folder come from {@link #FILE}.
     */
    public static String verify(String serverRoot, String rowCreatedAt, Properties evidence, Adjacent adjacent,
                                Collection<String> besideEvidence) throws Exception {
        return verify(load(), serverRoot, rowCreatedAt, evidence, adjacent, besideEvidence, REVIEWED);
    }

    static String verify(Config config, String serverRoot, String rowCreatedAt, Properties evidence, Adjacent adjacent,
                         Collection<String> besideEvidence, Map<String, String> reviewed) throws Exception {
        if (config == null)
            throw new IllegalArgumentException("No legacy install records are configured on this machine (" + FILE + ")");
        if (!config.serverRoot().equals(serverRoot))
            throw new IllegalArgumentException("Install records describe another server");
        Pattern backups = config.backup();
        Instant created = time(rowCreatedAt, "row created_at");
        if (!created.equals(time(required(evidence, "row_created_at"), "row_created_at")))
            throw new IllegalArgumentException("Evidence names a different row time");
        if (evidence.containsKey("window_start") || evidence.containsKey("window_end"))
            throw new IllegalArgumentException("The install window comes from the install records, not typed times");
        String jarSha = required(evidence, "jar_sha256");
        if (!jarSha.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid jar_sha256");
        String record = record(evidence, "install_record");
        String next = record(evidence, "next_install_record");
        if (record.equals(next)) throw new IllegalArgumentException("The next install record must be a different install");
        String recordText = text(adjacent, evidence, "install_record", record);
        String nextText = text(adjacent, evidence, "next_install_record", next);
        if (!recordText.contains(jarSha)) throw new IllegalArgumentException("Install record does not name this jar");
        String jarFile = required(evidence, "jar_file");
        if (!jarFile.matches("[A-Za-z0-9_.-]{1,96}\\.jar")) throw new IllegalArgumentException("Invalid jar_file name");
        if (!sha256(adjacent.read(jarFile)).equals(jarSha)) throw new IllegalArgumentException("Jar bytes do not match jar_sha256");
        String build = reviewed.get(jarSha);
        if (build == null) throw new IllegalArgumentException("Jar is not on the reviewed list");
        Instant start = backup(backups, recordText, "install_record"), end = backup(backups, nextText, "next_install_record");
        if (!start.isBefore(end)) throw new IllegalArgumentException("Install window is empty");
        // The next record must be the install that came straight after this one. Installs are ordered by the stopped
        // backup each record names, so any other record from the window's start to its end is an install between
        // them, and the window would take in rows written by a build that is not on the list.
        if (!besideEvidence.contains(record) || !besideEvidence.contains(next))
            throw new IllegalArgumentException(
                    "Both install records must sit beside the evidence: a complete install log is required");
        for (String name : besideEvidence) {
            if (!RECORD_NAME.matcher(name).matches() || name.equals(record) || name.equals(next)) continue;
            Instant other = backup(backups, new String(adjacent.read(name), StandardCharsets.UTF_8), name);
            if (!other.isBefore(start) && !other.isAfter(end))
                throw new IllegalArgumentException("Install record " + name + " lies between install_record and "
                        + "next_install_record. The next record must be the install straight after, so a complete install log "
                        + "is required beside the evidence");
        }
        if (created.isBefore(start.plus(EDGE)) || created.isAfter(end.minus(EDGE)))
            throw new IllegalArgumentException("Row is outside, or within ten minutes of the edge of, the install window");
        return build;
    }

    /** The names of the files in the evidence folder; verify reads the install records among them. */
    public static List<String> namesIn(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String record(Properties evidence, String key) {
        String name = required(evidence, key);
        if (!RECORD_NAME.matcher(name).matches()) throw new IllegalArgumentException("Invalid " + key + " name");
        return name;
    }

    private static String text(Adjacent adjacent, Properties evidence, String key, String name) throws Exception {
        byte[] bytes = adjacent.read(name);
        if (!sha256(bytes).equals(required(evidence, key + "_sha256")))
            throw new IllegalArgumentException(key.equals("install_record") ? "Install record changed" : "Next install record changed");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** The one stopped backup a record names, as a UTC instant. None, or two different ones, cannot bound a window. */
    private static Instant backup(Pattern backups, String text, String key) {
        Set<Instant> found = new HashSet<>();
        Matcher m = backups.matcher(text);
        while (m.find()) {
            try {
                LocalDateTime second = LocalDateTime.parse(m.group(1), BACKUP_TIME);
                String digits = m.group(2);
                long nanos = digits.isEmpty() ? 0 : Long.parseLong((digits + "000000000").substring(0, 9));
                found.add(second.toInstant(ZoneOffset.UTC).plusNanos(nanos));
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("Invalid backup time in " + key);
            }
        }
        if (found.size() != 1) throw new IllegalArgumentException(key + " must name exactly one stopped backup");
        return found.iterator().next();
    }

    private static Instant time(String text, String name) {
        try { return LocalDateTime.parse(text.trim().replace(' ', 'T')).toInstant(ZoneOffset.UTC); }
        catch (DateTimeParseException | NullPointerException invalid) { throw new IllegalArgumentException("Invalid " + name); }
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing evidence field: " + key);
        return value.trim();
    }
}
