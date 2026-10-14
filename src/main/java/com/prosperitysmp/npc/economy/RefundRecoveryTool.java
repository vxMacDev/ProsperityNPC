package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Offline operator adjudication of an already completed refund. Never sends money or runs migrations.
 * Profile (private root-owned properties): service, server_root, database, user, reserve_uuid, clock_timezone,
 * daily_reset_hour; service and server_root must match {@link RecoveryServers#FILE}. Evidence (private root-owned
 * properties): service, server_root, database, reserve_uuid, attempt_id, event_id, run_id, provider_sha256,
 * resolution_id, operator, refund_completed=true, reserve_before, reserve_after (equal), refund_before_bits,
 * refund_after_bits, intent_sha256, observation_sha256; the last two hash the adjacent root-owned
 * refund-intent.json and refund-observed.json. Usage: --record-completed-refund &lt;profile&gt; &lt;evidence&gt;. Connects to
 * MariaDB on 127.0.0.1:3306 with the owner-only
 * {@code <server_root>/plugins/ProsperityNPC/db-password.txt}.
 */
public final class RefundRecoveryTool {
    private RefundRecoveryTool() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[0].equals("--record-completed-refund"))
            throw new IllegalArgumentException("Expected --record-completed-refund <root-owned connection.properties> <root-owned "
                    + "evidence.properties>");
        var id = new ProcessBuilder("id", "-u").start();
        if (!id.waitFor(5, TimeUnit.SECONDS) || id.exitValue() != 0
                || !new String(id.getInputStream().readAllBytes()).trim().equals("0"))
            throw new IllegalStateException("Root operator required");
        Path connection = Path.of(args[1]).toAbsolutePath();
        Properties config = privateProperties(connection);
        String service = required(config, "service");
        String expectedRoot = RecoveryServers.rootFor(service);
        if (!required(config, "server_root").equals(expectedRoot))
            throw new IllegalArgumentException("Service/root mismatch");
        if (!systemd(service, "LoadState").equals("masked"))
            throw new IllegalStateException("Service must be runtime-masked during offline recovery");
        var state = new ProcessBuilder("systemctl", "show", service, "-p", "ActiveState", "--value").start();
        if (!state.waitFor(5, TimeUnit.SECONDS) || state.exitValue() != 0
                || !new String(state.getInputStream().readAllBytes()).trim().equals("inactive"))
            throw new IllegalStateException("Server must be stopped before journal adjudication");
        Path evidence = Path.of(args[2]).toAbsolutePath();
        if (!evidence.equals(evidence.toRealPath()) || !Files.isRegularFile(evidence, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(evidence).getName().equals("root") || Files.size(evidence) > 16384)
            throw new IllegalArgumentException("Invalid evidence custody");
        var permissions = Files.getPosixFilePermissions(evidence);
        if (permissions.stream().anyMatch(p -> p != PosixFilePermission.OWNER_READ && p != PosixFilePermission.OWNER_WRITE))
            throw new IllegalArgumentException("Evidence must be private to root");
        byte[] bytes = Files.readAllBytes(evidence);
        Properties p = new Properties();
        p.load(new ByteArrayInputStream(bytes));
        for (String artifact : new String[]{"intent", "observation"}) {
            Path linked = evidence.getParent().resolve(artifact.equals("intent") ? "refund-intent.json" : "refund-observed.json");
            if (!linked.equals(linked.toRealPath()) || !Files.getOwner(linked).getName().equals("root")
                    || Files.size(linked) > 16384 || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(linked))).equals(required(p, artifact + "_sha256")))
                throw new IllegalArgumentException("Refund artifact evidence changed");
        }
        if (!required(config, "database").equals(p.getProperty("database")) || !"true".equals(p.getProperty("refund_completed"))
                || !required(p, "reserve_before").equals(required(p, "reserve_after")))
            throw new IllegalArgumentException("Completed correction with unchanged reserve required");
        var handle = new TransferJournal.Handle(UUID.fromString(required(p, "attempt_id")), Long.parseLong(required(p, "event_id")),
                UUID.fromString(required(p, "run_id")), required(p, "provider_sha256"));
        for (String key : new String[]{"database", "user"})
            if (!required(config, key).matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid connection identifier");
        if (!expectedRoot.equals(required(p, "server_root")) || !service.equals(required(p, "service")))
            throw new IllegalArgumentException("Evidence belongs to another server");
        Path password = Path.of(expectedRoot, "plugins/ProsperityNPC/db-password.txt");
        if (!password.equals(password.toRealPath()) || Files.size(password) > 4096
                || Files.getPosixFilePermissions(password).stream()
                        .anyMatch(x -> x != PosixFilePermission.OWNER_READ && x != PosixFilePermission.OWNER_WRITE))
            throw new IllegalArgumentException("Invalid password custody");
        UUID reserve = UUID.fromString(required(config, "reserve_uuid"));
        if (!reserve.toString().equals(required(p, "reserve_uuid"))) throw new IllegalArgumentException("Reserve evidence mismatch");
        var zone = ZoneId.of(required(config, "clock_timezone"));
        int resetHour = Integer.parseInt(required(config, "daily_reset_hour"));
        if (resetHour < 0 || resetHour > 23) throw new IllegalArgumentException("Invalid reset hour");
        try (Database db = new Database()) {
            db.connect(new DatabaseSettings("127.0.0.1", 3306, required(config, "database"), required(config, "user"),
                    Files.readString(password).trim(), 1, 5000));
            var journal = new TransferJournal(db, reserve);
            // Reject unsupported domain receipts before recording any resolution.
            String domain;
            try (var c = db.getConnection(); var query = c.prepareStatement("""
                    SELECT (SELECT COUNT(*) FROM npc_purchase_intents WHERE event_id=?),
                           (SELECT COUNT(*) FROM npc_utility_payments WHERE event_id=?)
                    """)) {
                query.setQueryTimeout(5);
                query.setLong(1, handle.eventId());
                query.setLong(2, handle.eventId());
                try (var row = query.executeQuery()) {
                    if (!row.next() || row.getInt(1) + row.getInt(2) != 1)
                        throw new IllegalStateException("Requires one purchase or utility receipt; other recovery remains held");
                    domain = row.getInt(1) == 1 ? "purchase" : "utility";
                }
            }
            String receiptTable = domain.equals("purchase") ? "npc_purchase_intents" : "npc_utility_payments";
            String completedOutcome = domain.equals("purchase") ? "PAYMENT_COMPENSATED" : "PAYMENT_DECLINED";
            try (var c = db.getConnection(); var query = c.prepareStatement(
                    "SELECT state,outcome_code FROM " + receiptTable + " WHERE event_id=?")) {
                query.setQueryTimeout(5);
                query.setLong(1, handle.eventId());
                try (var row = query.executeQuery()) {
                    if (!row.next() || !("WAITING".equals(row.getString(1))
                            || ("DECLINED".equals(row.getString(1)) && completedOutcome.equals(row.getString(2)))))
                        throw new IllegalStateException("Domain receipt requires manual review; no compensation recorded");
                }
            }
            if (!systemd(service, "ActiveState").equals("inactive") || !systemd(service, "LoadState").equals("masked"))
                throw new IllegalStateException("Offline custody changed");
            var result = journal.resolveRefundedDebit(handle, UUID.fromString(required(p, "resolution_id")), required(p, "operator"),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                    Long.parseLong(required(p, "refund_before_bits")), Long.parseLong(required(p, "refund_after_bits")));
            String outcome;
            if (domain.equals("purchase")) {
                var receipt = new PurchaseRepository(db, reserve, Clock.systemUTC(), zone, resetHour)
                        .complete(handle.eventId()).orElseThrow();
                if (receipt.state() != PurchaseRepository.State.DECLINED || !receipt.outcome().equals("PAYMENT_COMPENSATED"))
                    throw new IllegalStateException("Domain completion not verified; resolution retained");
                outcome = receipt.outcome();
            } else {
                var receipt = new UtilityRepository(db, reserve, Clock.systemUTC(), zone, resetHour)
                        .reconcile(handle.eventId()).orElseThrow();
                if (receipt.state() != UtilityRepository.State.DECLINED)
                    throw new IllegalStateException("Utility completion not verified; resolution retained");
                outcome = receipt.outcome();
            }
            System.out.println("Event " + handle.eventId() + ": " + result.state() + "; " + outcome);

        }
    }

    private static String systemd(String service, String field) throws Exception {
        var process = new ProcessBuilder("systemctl", "show", service, "-p", field, "--value").start();
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0)
            throw new IllegalStateException("Service state unavailable");
        return new String(process.getInputStream().readAllBytes()).trim();
    }

    private static Properties privateProperties(Path path) throws Exception {
        if (!path.equals(path.toRealPath()) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(path).getName().equals("root") || Files.size(path) > 16384
                || Files.getPosixFilePermissions(path).stream()
                        .anyMatch(x -> x != PosixFilePermission.OWNER_READ && x != PosixFilePermission.OWNER_WRITE))
            throw new IllegalArgumentException("Connection profile must be a private root-owned regular file");
        Properties result = new Properties();
        result.load(new ByteArrayInputStream(Files.readAllBytes(path)));
        return result;
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing evidence field: " + key);
        return value;
    }
}
