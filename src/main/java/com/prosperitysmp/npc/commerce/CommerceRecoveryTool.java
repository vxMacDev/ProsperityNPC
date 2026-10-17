package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.commerce.CommerceOrderRepository.Handle;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository.PaymentDecision;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository.PaymentResolution;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.economy.HeldClass;
import com.prosperitysmp.npc.economy.LegacyProvenance;
import com.prosperitysmp.npc.economy.RecoveryServers;
import com.prosperitysmp.npc.economy.RefundClaims;
import com.prosperitysmp.npc.economy.RefundEvidence;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Root-only offline tool for a stopped, runtime-masked server. It never calls Vault, item delivery or migrations.
 * The profile (service, server_root, database, user, reserve_uuid) must match {@link RecoveryServers#FILE}, and the
 * private evidence manifest must repeat its service, server_root, database and reserve_uuid. The other manifest fields
 * are the required(...) reads below. --reconcile-payment records a staff decision on one order, --cancel-unstarted
 * cancels old untouched reservations, --inspect-delivery and --classify only read. --reconcile-payment is a dry run
 * unless the last argument is --apply: a dry run performs every check and write and then rolls them back. A refund
 * decision accepts an order whose reserve credit never started or was refused before its money call; its refund
 * evidence is checked by {@link RefundEvidence}, including the player's balance saved on the stopped server. The
 * decision VERIFIED_LEGACY_REFUSAL, and a refund over a credit an older build's gate refused (credit_proof=LEGACY),
 * also need the provenance fields of {@link LegacyProvenance}.
 * A refund that matches the balances of one already used for this payer, recorded after this order was issued, is
 * refused. Only when staff made a second, separate refund for this held order, --apply --distinct-refund skips that
 * one rule for this resolution; every other check still runs, and the claim records the assertion.
 * Usage: --reconcile-payment|--cancel-unstarted|--inspect-delivery &lt;profile&gt; &lt;evidence&gt; [--apply], or
 * --reconcile-payment &lt;profile&gt; &lt;evidence&gt; --apply --distinct-refund, or
 * --classify &lt;profile&gt;. Connects to MariaDB on 127.0.0.1:3306.
 */
public final class CommerceRecoveryTool {
    private CommerceRecoveryTool() { }

    public static void main(String[] args) throws Exception {
        boolean classify = args.length == 2 && args[0].equals("--classify");
        boolean distinctRefund = args.length == 5 && args[0].equals("--reconcile-payment") && args[3].equals("--apply")
                && args[4].equals("--distinct-refund");
        boolean apply = (args.length == 4 && args[0].equals("--reconcile-payment") && args[3].equals("--apply")) || distinctRefund;
        if (!classify && !apply && (args.length != 3 || (!args[0].equals("--reconcile-payment") && !args[0].equals("--cancel-unstarted")
                && !args[0].equals("--inspect-delivery"))))
            throw new IllegalArgumentException("Expected --reconcile-payment|--cancel-unstarted|--inspect-delivery"
                    + " <private profile.properties> <private evidence.properties> [--apply], or --reconcile-payment"
                    + " <private profile.properties> <private evidence.properties> --apply --distinct-refund,"
                    + " or --classify <private profile.properties>");
        if (!command("id", "-u").equals("0")) throw new IllegalStateException("Root operator required");
        Properties config = properties(privateBytes(Path.of(args[1]).toAbsolutePath(), 16384));
        String service = required(config, "service");
        String root = RecoveryServers.rootFor(service);
        if (!root.equals(required(config, "server_root"))) throw new IllegalArgumentException("Service/root mismatch");
        offline(service);
        if (classify) {
            for (String key : new String[]{"database", "user"})
                if (!required(config, key).matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid connection identifier");
            Path password = Path.of(root, "plugins/ProsperityNPC/db-password.txt");
            if (!password.equals(password.toRealPath()) || !Files.isRegularFile(password, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(password) > 4096 || !privateMode(password)) throw new IllegalArgumentException("Invalid password custody");
            try (Database db = new Database()) {
                db.connect(new DatabaseSettings("127.0.0.1", 3306, required(config, "database"), required(config, "user"),
                        Files.readString(password).trim(), 1, 5000));
                System.out.println(classifyReport(new CommerceOrderRepository(db, UUID.fromString(required(config, "reserve_uuid")))));
            }
            return;
        }
        Path manifest = Path.of(args[2]).toAbsolutePath();
        byte[] manifestBytes = privateBytes(manifest, 16384);
        Properties evidence = properties(manifestBytes);
        for (String key : new String[]{"service", "server_root", "database", "reserve_uuid"})
            if (!required(config, key).equals(required(evidence, key)))
                throw new IllegalArgumentException("Evidence profile binding conflicts: " + key);
        for (String key : new String[]{"database", "user"})
            if (!required(config, key).matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid connection identifier");
        UUID reserve = UUID.fromString(required(config, "reserve_uuid"));
        Path password = Path.of(root, "plugins/ProsperityNPC/db-password.txt");
        if (!password.equals(password.toRealPath()) || !Files.isRegularFile(password, LinkOption.NOFOLLOW_LINKS)
                || Files.size(password) > 4096 || !privateMode(password)) throw new IllegalArgumentException("Invalid password custody");
        if (args[0].equals("--inspect-delivery")) { inspectDelivery(config,evidence,root,password,reserve); return; }
        String operator = required(evidence, "operator"), caseRef = required(evidence, "case_ref"), notes = required(evidence, "notes");
        if (!operator.matches("[A-Za-z0-9_.:@-]{1,96}") || !caseRef.matches("[A-Za-z0-9_.:@-]{1,96}") || notes.length() > 2048)
            throw new IllegalArgumentException("Invalid operator case notes");
        try (Database db = new Database()) {
            db.connect(new DatabaseSettings("127.0.0.1", 3306, required(config, "database"), required(config, "user"),
                    Files.readString(password).trim(), 1, 5000));
            CommerceOrderRepository repository = new CommerceOrderRepository(db, reserve);
            // Cleanup also reads cutoff and limit from the manifest.
            if (args[0].equals("--cancel-unstarted")) {
                Instant cutoff = Instant.parse(required(evidence, "cutoff"));
                int limit = Integer.parseInt(required(evidence, "limit"));
                if (limit < 0 || limit > CommerceOrderRepository.MAX_SWEEP)
                    throw new IllegalArgumentException("Cleanup limit exceeds bounds");
                offline(service);
                int cancelled = repository.cancelUnstartedBefore(cutoff, limit);
                System.out.println("Untouched reservations cancelled: " + cancelled + "; actor=" + operator + "; case=" + caseRef
                        + "; manifest_sha256=" + hash(manifestBytes));
                return;
            }
            UUID payer = UUID.fromString(required(evidence, "payer_uuid"));
            UUID orderId = UUID.fromString(required(evidence, "order_id"));
            Handle handle = new Handle(orderId, UUID.fromString(required(evidence, "run_id")), payer, reserve,
                    required(evidence, "provider_sha256"));
            var found = repository.find(payer, orderId).orElseThrow(() -> new IllegalStateException("Unknown commerce order"));
            CommerceOrder order = found.order();
            if (!order.requestSha256().equals(required(evidence, "request_sha256"))
                    || order.costCents() != Long.parseLong(required(evidence, "cost_cents")))
                throw new IllegalArgumentException("Order hash/cost evidence conflicts");
            PaymentDecision decision = PaymentDecision.valueOf(required(evidence, "decision"));
            if (distinctRefund && decision != PaymentDecision.VERIFIED_REFUNDED_DEBIT)
                throw new IllegalArgumentException("--distinct-refund applies only to a VERIFIED_REFUNDED_DEBIT decision");
            boolean legacy = decision == PaymentDecision.VERIFIED_LEGACY_REFUSAL
                    || (decision == PaymentDecision.VERIFIED_REFUNDED_DEBIT && "LEGACY".equals(evidence.getProperty("credit_proof")));
            if (legacy) {
                String build = LegacyProvenance.verify(root, legacyRowTime(repository, payer, orderId), evidence,
                        name -> privateBytes(manifest.getParent().resolve(name), 128 * 1024 * 1024),
                        LegacyProvenance.namesIn(manifest.getParent()));
                System.out.println("Provenance: order written by reviewed build " + build + ".");
            }
            // payment-observed.json beside the manifest is independently reviewed evidence, not a substitute for persisted
            // observations. A refund decision also needs the refund intent and observation files and an unchanged reserve.
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream bundle = new DataOutputStream(bytes)) {
                part(bundle, manifestBytes);
                part(bundle, artifact(manifest, "payment-observed.json", required(evidence, "payment_observation_sha256")));
                if (decision == PaymentDecision.VERIFIED_REFUNDED_DEBIT) {
                    part(bundle, artifact(manifest, "refund-intent.json", required(evidence, "intent_sha256")));
                    byte[] observed = artifact(manifest, "refund-observed.json", required(evidence, "observation_sha256"));
                    // The player paid, so the reserve must be unchanged by the refund.
                    RefundEvidence.check(evidence, payer, orderId, false, observed);
                    // An applied refund replays exactly or not at all, so only an unresolved order needs the saved balance.
                    if (found.state() != CommerceOrderRepository.State.COMPENSATED)
                        RefundEvidence.requireSaved(evidence, RefundEvidence.savedBalanceBits(Path.of(root), payer));
                    part(bundle, observed);
                } else if (evidence.containsKey("refund_before_bits") || evidence.containsKey("refund_after_bits"))
                    throw new IllegalArgumentException("Refund bits without refund decision");
            }
            boolean refund = decision == PaymentDecision.VERIFIED_REFUNDED_DEBIT;
            PaymentResolution resolution = new PaymentResolution(UUID.fromString(required(evidence, "resolution_id")), operator, caseRef,
                    notes,
                    order.requestSha256(), decision, new CustodyBytes(bytes.toByteArray()),
                    refund ? Long.valueOf(required(evidence, "refund_before_bits")) : null,
                    refund ? Long.valueOf(required(evidence, "refund_after_bits")) : null,
                    refund ? required(evidence, "observation_sha256") : null,
                    Instant.parse(required(evidence, "resolved_at")));
            offline(service);
            var receipt = repository.reconcilePayment(order, handle, resolution, legacy, apply, distinctRefund);
            if (distinctRefund)
                RefundClaims.reportDistinctRefund(db, RefundClaims.Source.COMMERCE, orderId, resolution.resolutionId(),
                        operator, Logger.getLogger(CommerceRecoveryTool.class.getName()));
            System.out.println((apply ? "" : "Dry run, nothing written (add --apply): ") + "Commerce order " + orderId + ": "
                    + receipt.state() + "; resolution=" + resolution.resolutionId());
        }
    }

    /**
     * Read-only report of one paid order's item delivery, for a delivery held for staff: the order and custody state, the
     * recorded counts, how many of the item the stopped server's player file holds and what this order's save marker in
     * it shows. The order must belong to the payer and name request_nonce as its custody, and that custody must be a
     * commerce delivery. Nothing is written: the custody row is read at an instant before any expiry, so the lazy expiry
     * a normal read may do cannot fire. The profile also names paper_jar and libraries_dir (the installed server's own
     * jar and libraries, for its player-file reader). The manifest adds payer_uuid, order_id, request_nonce and
     * world_folder (relative to server_root). There is no in-tool decision for a held order.
     */
    private static void inspectDelivery(Properties config, Properties evidence, String root, Path password, UUID reserve) throws Exception {
        String world = required(evidence, "world_folder");
        if (!world.matches("[A-Za-z0-9_.-]{1,64}") || world.equals("..") || world.equals("."))
            throw new IllegalArgumentException("Invalid world folder");
        UUID payer = UUID.fromString(required(evidence, "payer_uuid"));
        UUID orderId = UUID.fromString(required(evidence, "order_id"));
        UUID nonce = UUID.fromString(required(evidence, "request_nonce"));
        try (URLClassLoader loader = serverLoader(Path.of(required(config, "paper_jar")), Path.of(required(config, "libraries_dir")));
             Database db = new Database()) {
            ItemSaveProof proof = new ItemSaveProof(loader);
            if (!proof.available()) throw new IllegalStateException("The installed server's player-file reader is unavailable");
            db.connect(new DatabaseSettings("127.0.0.1", 3306, required(config, "database"), required(config, "user"),
                    Files.readString(password).trim(), 1, 5000));
            var order = new CommerceOrderRepository(db, reserve).find(payer, orderId)
                    .orElseThrow(() -> new IllegalStateException("Unknown commerce order for this payer"));
            if (!nonce.equals(order.custodyNonce())) throw new IllegalStateException("This order's delivery is not request_nonce");
            var custody = new ItemCustodyRepository(db);
            var receipt=custody.find(payer,nonce,Instant.EPOCH)
                    .orElseThrow(() -> new IllegalStateException("Unknown item delivery for this payer"));
            ItemCustody request = receipt.request();
            if (request.source() != ItemCustody.Source.MERCHANT_GOODS && request.source() != ItemCustody.Source.WORKER_OUTPUT)
                throw new IllegalStateException("This delivery is not a paid order. Item repair returns use ItemRepairRecoveryTool.");
            var recorded = custody.recordedEvidence(payer, nonce).orElse(null);
            Path file = proof.playerFile(Path.of(root, world), payer).orElseThrow(() -> new IllegalStateException("No player file path"));
            byte[] saved = playerFile(file);
            var read = proof.readSavedDelivery(saved, request)
                    .orElseThrow(() -> new IllegalStateException("The player file could not be read for this item"));
            System.out.println(deliveryReport(orderId + " " + order.state(), order.order().material(), receipt, read, recorded, hash(saved))
                    + "\n" + orderMarkerLine(nonce, read.deliveryMarker()) + "\n" + versionLine(read));
        }
    }

    /**
     * The order's creation time, read from the order itself whatever its state, so an applied legacy decision can be
     * replayed with the same evidence after its terminal output was lost.
     */
    public static String legacyRowTime(CommerceOrderRepository repository, UUID payer, UUID orderId) throws Exception {
        return repository.createdAt(payer, orderId).orElseThrow(() -> new IllegalStateException("Unknown commerce order"));
    }

    /** Counts per class, and the startup hold's own count, which must be the same total. */
    static String classifyReport(CommerceOrderRepository repository) throws Exception {
        long holding = repository.heldCount();
        var rows = repository.held(100_000);
        Map<HeldClass, Integer> counts = new EnumMap<>(HeldClass.class);
        for (HeldClass c : HeldClass.values()) counts.put(c, 0);
        for (var row : rows) counts.merge(row.heldClass(), 1, Integer::sum);
        if (rows.size() != holding)
            throw new IllegalStateException("Classified " + rows.size() + " orders but the startup hold sees " + holding);
        StringBuilder out = new StringBuilder("Commerce orders holding payments: " + holding);
        for (var entry : counts.entrySet()) out.append("\n  ").append(entry.getKey()).append(": ").append(entry.getValue());
        return out.toString();
    }

    static String deliveryReport(String order, String material, ItemCustodyRepository.Receipt receipt, ItemSaveProof.SavedUnits read,
                                 ItemDeliveryEvidence recorded, String fileHash) {
        var request = receipt.request();
        return "Order delivery " + request.requestNonce() + " | order " + order
                + " | custody " + receipt.state() + (receipt.outcome() == null ? "" : " " + receipt.outcome())
                + " | delivery_run_id " + receipt.deliveryRunId() + " | payload_sha256 " + request.payload().sha256()
                + " | player file sha256 " + fileHash
                + "\n" + material + " x" + request.quantity() + ": storage and ender chest " + read.units().getFirst()
                + " | whole file " + read.wholeFile().getFirst()
                + (recorded == null ? " | no counts were recorded"
                        : " | recorded before " + recorded.beforeUnits() + " | recorded after " + recorded.afterUnits()
                          + " | not placed " + recorded.leftoverUnits());
    }

    /**
     * What this order's marker in the file shows. Only marker mode writes the delivery key, and a held order blocks every
     * other order delivery for the player, so a file carries this nonce's marker exactly when marker mode ran for it. The
     * after marker is set in the delivery tick, so it shows when the file was saved, not that the items were added.
     */
    static String orderMarkerLine(UUID nonce, String found) {
        if (ItemSaveProof.marker(nonce, true).equals(found))
            return "Marker: this order delivery's after marker. The file was saved at or after the delivery tick."
                    + " The marker alone does not prove the items were added; compare the counts.";
        if (ItemSaveProof.marker(nonce, false).equals(found))
            return "Marker: this order delivery's before marker. The file was saved before the items were given and does not show them.";
        return "Marker: none for this order delivery. It ran without the save marker or its marker was lost, so the marker"
                + " says nothing about it.";
    }

    static String versionLine(ItemSaveProof.SavedUnits read) {
        return "DataVersion: file " + (read.fileDataVersion() == null ? "none" : read.fileDataVersion()) + " | recorded with the items "
                + (read.recordedDataVersions().isEmpty() ? "none, so not compared" : read.recordedDataVersions());
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
    private static void part(DataOutputStream stream, byte[] bytes) throws Exception {
        stream.writeInt(bytes.length);
        stream.write(bytes);
    }
    private static byte[] artifact(Path manifest, String name, String expectedHash) throws Exception {
        ItemDeliveryEvidence.requireHash(expectedHash);
        byte[] bytes = privateBytes(manifest.getParent().resolve(name), 16384);
        if (!hash(bytes).equals(expectedHash)) throw new IllegalArgumentException("Case artifact changed: " + name);
        return bytes;
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static byte[] privateBytes(Path path, int limit) throws Exception {
        if (!path.equals(path.toRealPath()) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(path).getName().equals("root") || Files.size(path) < 1 || Files.size(path) > limit || !privateMode(path))
            throw new IllegalArgumentException("Evidence/profile must be a bounded, private root-owned regular file");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length < 1 || bytes.length > limit) throw new IllegalArgumentException("Evidence/profile size changed");
            return bytes;
        }
    }
    private static boolean privateMode(Path path) throws Exception {
        return Files.getPosixFilePermissions(path).stream()
                .noneMatch(p -> p != PosixFilePermission.OWNER_READ && p != PosixFilePermission.OWNER_WRITE);
    }
    private static Properties properties(byte[] bytes) throws Exception {
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(bytes));
        return properties;
    }
    private static void offline(String service) throws Exception {
        if (!command("systemctl", "show", service, "-p", "LoadState", "--value").equals("masked")
                || !command("systemctl", "show", service, "-p", "ActiveState", "--value").equals("inactive"))
            throw new IllegalStateException("Server must be stopped and runtime-masked throughout recovery");
    }
    private static String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Recovery environment check timed out");
        }
        if (process.exitValue() != 0) throw new IllegalStateException("Recovery environment check failed");
        return new String(process.getInputStream().readNBytes(4096)).trim();
    }
    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing evidence field: " + key);
        return value;
    }
}
