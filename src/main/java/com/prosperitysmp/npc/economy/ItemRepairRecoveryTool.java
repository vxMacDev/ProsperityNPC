package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.commerce.CustodyBytes;
import com.prosperitysmp.npc.commerce.ItemCollectionEvidence;
import com.prosperitysmp.npc.commerce.ItemCustody;
import com.prosperitysmp.npc.commerce.ItemCustodyRepository;
import com.prosperitysmp.npc.commerce.ItemDeliveryEvidence;
import com.prosperitysmp.npc.commerce.ItemSaveProof;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Root-only, server-stopped, manifest-bound decision for one held item hand-in; never Vault, never item delivery,
 * never migrations. Items owed back are delivered by the plugin on the player's next /property repair returns.
 * Profile: service, server_root, database, user, world_folder (relative to server_root), paper_jar and
 * libraries_dir (the installed server's own jar and library folder, for its player-file reader), clock_timezone,
 * daily_reset_hour. service and server_root must match {@link RecoveryServers#FILE}.
 * Manifest: the same service/server_root/database, collection_id, player_uuid, run_id, quote_sha256,
 * resolution_id, operator, case_ref, notes, decision (VERIFIED_COLLECTED or VERIFIED_NOT_COLLECTED),
 * player_file_sha256 (from --inspect), and optional override_reason.
 * --inspect prints, per line, the units in inventory storage plus ender chest (what the recorded counts compare to) and
 * in the whole file (offhand, armor, ender chest and nested containers included), against the hand-in's before count
 * and the take counts the plugin recorded, if it got that far. It also says whether the file carries this hand-in's
 * before or after marker: the after marker is set just before the take's own save, so only a later file carries it. --resolve re-reads the same file (its hash must still match) and records the decision.
 * Every decision on both paths needs override_reason, which is kept with the evidence. The plugin records no hash of the
 * save a take or delivery made, so a stopped server's file can never be proven to be that save: after a failed save and a
 * crash restore, the player can move items until the file agrees with any recorded counts. The checks below still run
 * and their findings are printed with the refusal, so staff see what the file and the recorded counts say.
 * VERIFIED_COLLECTED fits when the recorded take counts show the whole recipe removed and the file holds exactly the
 * recorded after count. VERIFIED_NOT_COLLECTED fits when the recorded counts show nothing removed and the file holds
 * the before count. A file alone cannot tell a take from items the player moved into a chest.
 * <p>
 * Both paths also compare the saved file's DataVersion with the one recorded on the items. A different one (after a
 * server upgrade) is refused unless override_reason says why. Items recorded with no DataVersion are not compared.
 * <p>
 * Held returns: --inspect-return and --resolve-return settle one held REPAIR_RETURN delivery (the stack is AMBIGUOUS, or
 * DELIVERY_INTENT from a run that died). While it is held no item delivery of any kind starts for that player. Manifest:
 * the same service/server_root/database, request_nonce, player_uuid, then for --resolve-return also run_id,
 * payload_sha256 (both printed by --inspect-return), resolution_id, operator, case_ref, notes, decision
 * (VERIFIED_DELIVERED, VERIFIED_NOT_DELIVERED or RETURNED_BY_STAFF), player_file_sha256 and optional override_reason.
 * VERIFIED_DELIVERED fits when the counts the runtime recorded show the whole stack added and the file holds exactly
 * that count. VERIFIED_NOT_DELIVERED fits when the recorded counts show nothing added and the file holds the count
 * from before. Either way it needs override_reason, as above.
 * VERIFIED_NOT_DELIVERED issues the same stack again for /property repair returns, once. --inspect-return prints the
 * same two counts and whether the file carries this return's before or after marker.
 * A return held as PAYLOAD_UNREADABLE has stored items the server could not read, so the plugin never started it and
 * issuing the same bytes again cannot help. If staff gave the items back by hand, RETURNED_BY_STAFF closes it with
 * override_reason, records the reason and issues nothing. VERIFIED_DELIVERED is refused for it.
 * Usage: --inspect|--resolve|--inspect-return|--resolve-return &lt;profile&gt; &lt;manifest&gt;.
 * Connects to MariaDB on 127.0.0.1:3306.
 */
public final class ItemRepairRecoveryTool {
    private ItemRepairRecoveryTool() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !List.of("--inspect", "--resolve", "--inspect-return", "--resolve-return").contains(args[0]))
            throw new IllegalArgumentException("Expected --inspect|--resolve|--inspect-return|--resolve-return"
                    + " <private profile.properties> <private manifest.properties>");
        if (!command("id", "-u").equals("0")) throw new IllegalStateException("Root operator required");
        Properties config = properties(privateBytes(Path.of(args[1]).toAbsolutePath(), 16384));
        String service = required(config, "service");
        String root = RecoveryServers.rootFor(service);
        if (!root.equals(required(config, "server_root"))) throw new IllegalArgumentException("Service/root mismatch");
        offline(service);
        Path manifest = Path.of(args[2]).toAbsolutePath();
        byte[] manifestBytes = privateBytes(manifest, 16384);
        Properties evidence = properties(manifestBytes);
        for (String key : new String[]{"service", "server_root", "database"})
            if (!required(config, key).equals(required(evidence, key))) throw new IllegalArgumentException("Manifest profile binding conflicts: " + key);
        for (String key : new String[]{"database", "user"})
            if (!required(config, key).matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid connection identifier");
        Path password = Path.of(root, "plugins/ProsperityNPC/db-password.txt");
        if (!password.equals(password.toRealPath()) || !Files.isRegularFile(password, LinkOption.NOFOLLOW_LINKS)
                || Files.size(password) > 4096 || !privateMode(password)) throw new IllegalArgumentException("Invalid password custody");
        String world = required(config, "world_folder");
        if (!world.matches("[A-Za-z0-9_.-]{1,64}") || world.equals("..") || world.equals("."))
            throw new IllegalArgumentException("Invalid world folder");
        boolean returns = args[0].endsWith("-return");
        UUID player = UUID.fromString(required(evidence, "player_uuid"));
        try (URLClassLoader loader = serverLoader(Path.of(required(config, "paper_jar")), Path.of(required(config, "libraries_dir")));
             Database db = new Database()) {
            var proof = new ItemSaveProof(loader);
            if (!proof.available()) throw new IllegalStateException("The installed server's player-file reader is unavailable");
            db.connect(new DatabaseSettings("127.0.0.1", 3306, required(config, "database"), required(config, "user"),
                    Files.readString(password).trim(), 1, 5000));
            var repository = new ItemRepairRepository(db);
            if (returns) {
                settleReturn(args[0].equals("--resolve-return"), service, root, world, manifestBytes, evidence, player,
                        proof, repository, new ItemCustodyRepository(db));
                return;
            }
            UUID collection = UUID.fromString(required(evidence, "collection_id"));
            var stored = repository.find(collection, player).orElseThrow(() -> new IllegalStateException("Unknown item hand-in for this player"));
            var quote = stored.quote();
            if (!stored.runId().toString().equals(required(evidence, "run_id")) || !quote.quoteSha256().equals(required(evidence, "quote_sha256")))
                throw new IllegalArgumentException("Manifest does not bind this hand-in's run and quote");
            Path file = proof.playerFile(Path.of(root, world), player).orElseThrow(() -> new IllegalStateException("No player file path"));
            byte[] saved = playerFile(file);
            String fileHash = hash(saved);
            var read = proof.readSaved(saved, player, quote.lines().stream().map(ItemRepairQuote.Line::prototype).toList())
                    .orElseThrow(() -> new IllegalStateException("The player file could not be read for these items"));
            var recorded = repository.takeEvidence(collection).orElse(null);
            if (recorded != null) requireBinding(recorded.playerId(), recorded.collectionId(), player, collection);
            String report = report(stored, read, recorded, fileHash) + "\n" + markerLine("hand-in", "take", collection, read.handInMarker())
                    + "\n" + versionLine(read);
            System.out.println(report);
            if (args[0].equals("--inspect")) return;
            if (!fileHash.equals(required(evidence, "player_file_sha256")))
                throw new IllegalArgumentException("The player file changed since --inspect");
            var decision = ItemRepairRepository.Decision.valueOf(required(evidence, "decision"));
            String operator = required(evidence, "operator"), caseRef = required(evidence, "case_ref"), notes = required(evidence, "notes");
            if (!operator.matches("[A-Za-z0-9_.:@-]{1,96}") || !caseRef.matches("[A-Za-z0-9_.:@-]{1,96}") || notes.length() > 2048)
                throw new IllegalArgumentException("Invalid operator case notes");
            String problem = handInProblem(read, decision, quote.beforeUnits(), quote.quantities(), recorded);
            String override = checkedOverride(problem, evidence.getProperty("override_reason", ""));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream bundle = new DataOutputStream(bytes)) {
                part(bundle, manifestBytes);
                part(bundle, report.getBytes(StandardCharsets.UTF_8));
                part(bundle, override.getBytes(StandardCharsets.UTF_8));
            }
            var resolution = new ItemRepairRepository.OperatorResolution(UUID.fromString(required(evidence, "resolution_id")),
                    operator, decision, new CustodyBytes(bytes.toByteArray()));
            var days = new com.prosperitysmp.npc.clock.EconomyClock(Clock.systemUTC(), ZoneId.of(required(config, "clock_timezone")),
                    Integer.parseInt(required(config, "daily_reset_hour")));
            offline(service);
            Instant at = Instant.now();
            var settled = repository.reconcile(collection, player, resolution, at, days.dayAt(at));
            System.out.println("Item hand-in " + collection + ": " + settled.state() + "; returns " + settled.returns().size()
                    + "; resolution=" + resolution.resolutionId() + "; actor=" + operator + "; case=" + caseRef);
        }
    }

    /**
     * The gate both resolve paths go through before anything is written: a decision the recorded counts and the saved
     * file do not clearly support goes ahead only with a staff reason, which is stored with the evidence. Returns that
     * reason (empty when nothing was unclear).
     */
    static String checkedOverride(String problem, String overrideReason) {
        String override = overrideReason == null ? "" : overrideReason.strip();
        if (problem != null && override.isEmpty())
            throw new IllegalArgumentException(problem + " Add override_reason only after checking the player by other means.");
        if (override.length() > 512) throw new IllegalArgumentException("override_reason is too long");
        return override;
    }

    /**
     * Why no stopped-server file is the take's or delivery's own save: nothing recorded its hash. A save that failed, a
     * crash restore and items moved since can make any file agree with the recorded counts.
     */
    static final String UNPROVEN_FILE = "Nothing recorded a hash of the save the plugin made, so this file cannot be proven to be"
            + " that save. After a failed save and a crash, the player can move items until the file matches the recorded counts.";

    /** Everything unclear about a hand-in decision. Never null: see {@link #UNPROVEN_FILE}. */
    static String handInProblem(ItemSaveProof.SavedUnits read, ItemRepairRepository.Decision decision, List<Integer> before,
                                List<Integer> quantities, ItemCollectionEvidence recorded) {
        return problems(UNPROVEN_FILE, ItemSaveProof.dataVersionProblem(read.fileDataVersion(), read.recordedDataVersions()),
                decisionProblem(decision, read.units(), before, quantities, recorded));
    }

    /** Everything unclear about a return decision. Never null: see {@link #UNPROVEN_FILE}. */
    static String returnProblem(ItemSaveProof.SavedUnits read, ItemCustodyRepository.Decision decision, int quantity,
                                ItemDeliveryEvidence recorded) {
        return problems(UNPROVEN_FILE, ItemSaveProof.dataVersionProblem(read.fileDataVersion(), read.recordedDataVersions()),
                returnDecisionProblem(decision, read.units().getFirst(), quantity, recorded));
    }

    /** Recorded take counts are evidence only for the player and hand-in they were written for. */
    static void requireBinding(UUID recordedPlayer, UUID recordedCollection, UUID player, UUID collection) {
        if (!recordedPlayer.equals(player) || !recordedCollection.equals(collection))
            throw new IllegalStateException("The recorded take counts do not bind this hand-in");
    }

    /** One held return delivery: report, then (for --resolve-return) the checked decision. */
    private static void settleReturn(boolean resolve, String service, String root, String world, byte[] manifestBytes,
                                     Properties evidence, UUID player, ItemSaveProof proof, ItemRepairRepository repository,
                                     ItemCustodyRepository custody) throws Exception {
        UUID nonce = UUID.fromString(required(evidence, "request_nonce"));
        // Read-only check first: find() may expire a plain commerce reservation, and this tool never touches those.
        var binding = repository.returnBinding(nonce)
                .orElseThrow(() -> new IllegalStateException("This is not the current stack of any item hand-in. This tool settles only those."));
        var receipt = custody.find(player, nonce, Instant.now())
                .orElseThrow(() -> new IllegalStateException("Unknown item delivery for this player"));
        ItemCustody request = receipt.request();
        if (request.source() != ItemCustody.Source.REPAIR_RETURN)
            throw new IllegalStateException("This delivery is not an item repair return. This tool settles only those.");
        var stored = repository.find(binding.collectionId(), player)
                .orElseThrow(() -> new IllegalStateException("The hand-in for this return is missing"));
        var plan = stored.quote().returnPlan().get(binding.stackNo());
        if (plan.quantity() != request.quantity() || !plan.payload().sha256().equals(request.payload().sha256()))
            throw new IllegalStateException("The return does not match its hand-in's stack");
        var line = stored.quote().lines().get(plan.lineNo());
        var recorded = custody.recordedEvidence(player, nonce).orElse(null);
        Path file = proof.playerFile(Path.of(root, world), player).orElseThrow(() -> new IllegalStateException("No player file path"));
        byte[] saved = playerFile(file);
        String fileHash = hash(saved);
        var read = proof.readSaved(saved, player, List.of(line.prototype()))
                .orElseThrow(() -> new IllegalStateException("The player file could not be read for this item"));
        String report = returnReport(receipt, binding, line.material(), read, recorded, fileHash)
                + "\n" + markerLine("return", "delivery", nonce, read.returnMarker()) + "\n" + versionLine(read);
        System.out.println(report);
        if (!resolve) return;
        UUID run = UUID.fromString(required(evidence, "run_id"));
        if (!run.equals(receipt.deliveryRunId()) || !request.payload().sha256().equals(required(evidence, "payload_sha256")))
            throw new IllegalArgumentException("Manifest does not bind this delivery's run and items");
        if (!fileHash.equals(required(evidence, "player_file_sha256")))
            throw new IllegalArgumentException("The player file changed since --inspect-return");
        var decision = ItemCustodyRepository.Decision.valueOf(required(evidence, "decision"));
        String operator = required(evidence, "operator"), caseRef = required(evidence, "case_ref"), notes = required(evidence, "notes");
        if (!operator.matches("[A-Za-z0-9_.:@-]{1,96}") || !caseRef.matches("[A-Za-z0-9_.:@-]{1,96}") || notes.length() > 2048)
            throw new IllegalArgumentException("Invalid operator case notes");
        String problem = returnProblem(read, decision, request.quantity(), recorded);
        String override = checkedOverride(problem, evidence.getProperty("override_reason", ""));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream bundle = new DataOutputStream(bytes)) {
            part(bundle, manifestBytes);
            part(bundle, report.getBytes(StandardCharsets.UTF_8));
            part(bundle, override.getBytes(StandardCharsets.UTF_8));
        }
        var resolution = new ItemCustodyRepository.OperatorResolution(UUID.fromString(required(evidence, "resolution_id")),
                operator, decision, new CustodyBytes(bytes.toByteArray()));
        offline(service);
        var settled = repository.resolveReturn(request, run, resolution, Instant.now());
        System.out.println("Return delivery " + nonce + ": " + settled.state()
                + (settled.reissued() == null ? "" : "; issued again as " + settled.reissued().requestNonce())
                + (override.isEmpty() ? "" : "; override_reason recorded")
                + "; resolution=" + resolution.resolutionId() + "; actor=" + operator + "; case=" + caseRef);
    }

    /**
     * Null when the recorded counts and the saved file clearly support the decision. Saying delivered when it was not
     * loses the player's items; saying not delivered when it was gives them twice. So: DELIVERED needs the recorded
     * counts to show the whole stack added and the file to hold that count. NOT_DELIVERED needs the recorded counts to
     * show nothing added and the file to hold the count from before. A stack that was added but is missing from the file
     * could be a lost save or items the player moved, and the counts cannot tell those apart, so it is refused.
     */
    static String returnDecisionProblem(ItemCustodyRepository.Decision decision, int saved, int quantity,
                                        ItemDeliveryEvidence recorded) {
        if (decision == ItemCustodyRepository.Decision.RETURNED_BY_STAFF) return STAFF_RETURN;
        if (recorded == null) return "No counts were recorded for this delivery, so the file cannot show whether it arrived.";
        if (decision == ItemCustodyRepository.Decision.VERIFIED_DELIVERED) {
            if (recorded.leftoverUnits() != 0 || recorded.afterUnits() - recorded.beforeUnits() != quantity)
                return "The recorded counts do not show the whole stack of " + quantity + " added.";
            return saved == recorded.afterUnits() ? null
                    : "The saved file shows " + saved + ", not " + recorded.afterUnits() + " for VERIFIED_DELIVERED.";
        }
        if (recorded.afterUnits() != recorded.beforeUnits())
            return "The recorded counts show items were added, so the file cannot prove they never arrived.";
        return saved == recorded.beforeUnits() ? null
                : "The saved file shows " + saved + ", not " + recorded.beforeUnits() + " for VERIFIED_NOT_DELIVERED.";
    }

    /** Why RETURNED_BY_STAFF always needs override_reason: nothing the plugin recorded can show a hand-off by staff. */
    static final String STAFF_RETURN = "Staff gave these items back by hand, so nothing the plugin recorded can show it."
            + " RETURNED_BY_STAFF issues nothing again.";

    /** The report line for a return held because its stored items could not be read. */
    static final String UNREADABLE_LINE = "This return's stored items could not be read, so the plugin never started it."
            + " If staff gave the items back by hand, use decision RETURNED_BY_STAFF with override_reason.";

    static String returnReport(ItemCustodyRepository.Receipt receipt, ItemRepairRepository.ReturnBinding binding, String material,
                               ItemSaveProof.SavedUnits read, ItemDeliveryEvidence recorded, String fileHash) {
        var request = receipt.request();
        return "Return delivery " + request.requestNonce() + " | hand-in " + binding.collectionId() + " stack " + binding.stackNo()
                + " | state " + receipt.state() + (receipt.outcome() == null ? "" : " " + receipt.outcome())
                + " | run_id " + receipt.deliveryRunId() + " | payload_sha256 " + request.payload().sha256()
                + " | player file sha256 " + fileHash
                + "\n" + material + " x" + request.quantity() + ": " + counts(read, 0)
                + (recorded == null ? " | no counts were recorded"
                        : " | recorded before " + recorded.beforeUnits() + " | recorded after " + recorded.afterUnits()
                          + " | not placed " + recorded.leftoverUnits())
                + (ItemCustodyRepository.PAYLOAD_UNREADABLE.equals(receipt.outcome()) ? "\n" + UNREADABLE_LINE : "");
    }

    /**
     * Null when the recorded take counts and the saved file clearly support the decision; the same rule as
     * {@link #returnDecisionProblem}. Saying collected when nothing was taken gives a free repair; saying not collected
     * when the items were taken loses them. A file alone cannot tell a take from items the player moved into a chest, so:
     * COLLECTED needs the recorded counts to start at the hand-in's before counts and show every line's whole quantity
     * removed, and the file to hold exactly the recorded after counts. NOT_COLLECTED needs the recorded counts to start at
     * the before counts and show nothing removed, and the file to hold the before counts. No recorded counts is refused.
     */
    static String decisionProblem(ItemRepairRepository.Decision decision, List<Integer> saved, List<Integer> before,
                                  List<Integer> quantities, ItemCollectionEvidence recorded) {
        if (saved.size() != before.size() || quantities.size() != before.size()) return "The file does not list every line.";
        if (recorded == null)
            return "No take counts were recorded for this hand-in, so the file cannot show whether the plugin took the items."
                    + " Items the player moved into a chest look the same as a take.";
        if (!recorded.beforeUnits().equals(before))
            return "The recorded counts start at " + recorded.beforeUnits() + ", not the hand-in's " + before + ".";
        if (decision == ItemRepairRepository.Decision.VERIFIED_COLLECTED) {
            for (int i = 0; i < before.size(); i++)
                if (recorded.beforeUnits().get(i) - recorded.afterUnits().get(i) != quantities.get(i))
                    return "The recorded counts do not show the whole recipe " + quantities + " taken.";
            return saved.equals(recorded.afterUnits()) ? null
                    : "The saved file shows " + saved + ", not " + recorded.afterUnits() + " for VERIFIED_COLLECTED.";
        }
        if (!recorded.afterUnits().equals(recorded.beforeUnits()))
            return "The recorded counts show items were taken, so the file cannot prove they never were.";
        return saved.equals(before) ? null
                : "The saved file shows " + saved + ", not " + before + " for VERIFIED_NOT_COLLECTED.";
    }

    /** Every problem found, in order, or null when there is none. */
    static String problems(String... found) {
        var out = new StringBuilder();
        for (String problem : found) if (problem != null) out.append(out.isEmpty() ? "" : " ").append(problem);
        return out.isEmpty() ? null : out.toString();
    }

    static String versionLine(ItemSaveProof.SavedUnits read) {
        return "DataVersion: file " + (read.fileDataVersion() == null ? "none" : read.fileDataVersion()) + " | recorded with the items "
                + (read.recordedDataVersions().isEmpty() ? "none, so not compared" : read.recordedDataVersions());
    }

    /**
     * One line's saved units: inventory storage plus ender chest (what the recorded counts compare to), and every unit
     * anywhere in the file (offhand, armor, ender chest, nested shulker boxes and bundles, a ridden animal's chest).
     */
    static String counts(ItemSaveProof.SavedUnits read, int line) {
        return "storage and ender chest " + read.units().get(line) + " | whole file " + read.wholeFile().get(line);
    }

    /**
     * Whether the file carries this hand-in's or return's marker (found, null when the file has none). The before marker
     * is set before the save just ahead of the take or delivery, and the after marker just before the take's or
     * delivery's own save, so only a file saved after that carries it.
     */
    static String markerLine(String what, String act, UUID id, String found) {
        if (found == null) return "Marker: none for this " + what + ". The file was saved before this " + what + " started, or the marker was removed.";
        if (found.equals(ItemSaveProof.marker(id, true))) return "Marker: this " + what + "'s after marker. The file was saved after the " + act + ".";
        if (found.equals(ItemSaveProof.marker(id, false)))
            return "Marker: this " + what + "'s before marker. The file was saved before the " + act + ", so it does not show it.";
        return "Marker: " + found + ", which belongs to another " + what + ". This file is not this " + what + "'s save.";
    }

    static String report(ItemRepairRepository.Stored stored, ItemSaveProof.SavedUnits read, ItemCollectionEvidence recorded, String fileHash) {
        var out = new StringBuilder("Item hand-in ").append(stored.quote().collectionId()).append(" | state ").append(stored.state())
                .append(stored.outcome() == null ? "" : " " + stored.outcome()).append(" | player file sha256 ").append(fileHash)
                .append(recorded == null ? " | no take counts were recorded" : "");
        var lines = stored.quote().lines();
        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            out.append('\n').append(line.material()).append(": ").append(counts(read, i)).append(" | before ").append(line.beforeUnits())
                    .append(" | after if taken ").append(line.beforeUnits() - line.quantity());
            if (recorded != null && i < recorded.beforeUnits().size())
                out.append(" | recorded before ").append(recorded.beforeUnits().get(i)).append(" | recorded after ").append(recorded.afterUnits().get(i));
        }
        return out.toString();
    }

    private static URLClassLoader serverLoader(Path jar, Path libraries) throws Exception {
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(libraries, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("paper_jar and libraries_dir must name the installed server files");
        List<URL> urls = new ArrayList<>();
        urls.add(jar.toUri().toURL());
        try (var files = Files.walk(libraries)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".jar")).sorted(Comparator.reverseOrder()).toList())
                urls.add(path.toUri().toURL());
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static byte[] playerFile(Path file) throws Exception {
        if (!file.equals(file.toRealPath()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) < 1 || Files.size(file) > 2 * 1024 * 1024) throw new IllegalArgumentException("Invalid player file");
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(2 * 1024 * 1024 + 1);
            if (bytes.length < 1 || bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Player file size changed");
            return bytes;
        }
    }
    private static void part(DataOutputStream stream, byte[] bytes) throws Exception { stream.writeInt(bytes.length); stream.write(bytes); }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static byte[] privateBytes(Path path, int limit) throws Exception {
        if (!path.equals(path.toRealPath()) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(path).getName().equals("root") || Files.size(path) < 1 || Files.size(path) > limit || !privateMode(path))
            throw new IllegalArgumentException("Manifest/profile must be a bounded, private root-owned regular file");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length < 1 || bytes.length > limit) throw new IllegalArgumentException("Manifest/profile size changed");
            return bytes;
        }
    }
    private static boolean privateMode(Path path) throws Exception {
        return Files.getPosixFilePermissions(path).stream()
                .noneMatch(p -> p != PosixFilePermission.OWNER_READ && p != PosixFilePermission.OWNER_WRITE);
    }
    private static Properties properties(byte[] bytes) throws Exception {
        Properties properties = new Properties(); properties.load(new ByteArrayInputStream(bytes)); return properties;
    }
    private static void offline(String service) throws Exception {
        if (!command("systemctl", "show", service, "-p", "LoadState", "--value").equals("masked")
                || !command("systemctl", "show", service, "-p", "ActiveState", "--value").equals("inactive"))
            throw new IllegalStateException("Server must be stopped and runtime-masked throughout recovery");
    }
    private static String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Recovery environment check timed out"); }
        if (process.exitValue() != 0) throw new IllegalStateException("Recovery environment check failed");
        return new String(process.getInputStream().readNBytes(4096)).trim();
    }
    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing field: " + key);
        return value;
    }
}
