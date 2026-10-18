package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.Uuids;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Offline operator adjudication of held transfer-journal rows. Never sends money or runs migrations. Every mode
 * needs a root operator and a stopped, runtime-masked server, and the writing modes are dry runs unless the last
 * argument is --apply: a dry run performs every check and write and then rolls them back.
 * Profile (private root-owned properties): service, server_root, database, user, reserve_uuid, clock_timezone,
 * daily_reset_hour; service and server_root must match {@link RecoveryServers#FILE}. Evidence (private root-owned
 * properties): service, server_root, database, reserve_uuid, attempt_id, event_id, run_id, provider_sha256,
 * resolution_id, operator.
 * --record-completed-refund also needs refund_completed=true, refund_before_bits, refund_after_bits, reserve_before,
 * reserve_after, intent_sha256 and observation_sha256; the last two hash the adjacent root-owned refund-intent.json
 * and refund-observed.json. When a player paid (purchases or bills) the reserve was never credited, so reserve_before
 * and reserve_after must be equal. When the reserve paid (rent, appreciation) the refund restores the reserve, so
 * reserve_before and reserve_after are the refund's own before and after balances, and owner_before and owner_after
 * (equal) show the owner was never credited. {@link RefundEvidence} also checks refund-observed.json and the payer's
 * balance saved on the stopped server. It accepts a debit whose credit never started, or whose credit was refused
 * before its money call. With credit_proof=LEGACY it also accepts a credit an older build's gate refused, and then
 * needs the provenance fields of {@link LegacyProvenance}.
 * A refund that matches the balances of one already used for this payer, recorded after this payment's debit began,
 * is refused. Only when staff made a second, separate refund for this held payment, --apply --distinct-refund
 * skips that one rule for this resolution; every other check still runs, and the claim records the assertion.
 * --record-legacy-refusal records a debit an older build's gate refused before its money call (declined, no refund),
 * and always needs those provenance fields. --classify only reads and prints how the held rows divide.
 * Usage: --classify &lt;profile&gt; | --record-completed-refund|--record-legacy-refusal &lt;profile&gt; &lt;evidence&gt;
 * [--apply], or --record-completed-refund &lt;profile&gt; &lt;evidence&gt; --apply --distinct-refund. Connects to
 * MariaDB on 127.0.0.1:3306 with the owner-only {@code <server_root>/plugins/ProsperityNPC/db-password.txt}.
 */
public final class RefundRecoveryTool {
    private static final int MAX_EVIDENCE = 16384, MAX_JAR = 128 * 1024 * 1024;

    private RefundRecoveryTool() { }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "" : args[0];
        boolean classify = mode.equals("--classify") && args.length == 2;
        boolean distinctRefund = mode.equals("--record-completed-refund") && args.length == 5
                && args[3].equals("--apply") && args[4].equals("--distinct-refund");
        boolean writing = (mode.equals("--record-completed-refund") || mode.equals("--record-legacy-refusal"))
                && (args.length == 3 || (args.length == 4 && args[3].equals("--apply")) || distinctRefund);
        if (!classify && !writing)
            throw new IllegalArgumentException("Expected --classify <root-owned connection.properties>, or "
                    + "--record-completed-refund|--record-legacy-refusal <root-owned connection.properties> "
                    + "<root-owned evidence.properties> [--apply], or --record-completed-refund "
                    + "<root-owned connection.properties> <root-owned evidence.properties> --apply --distinct-refund");
        boolean apply = args.length >= 4;
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
        if (!systemd(service, "ActiveState").equals("inactive"))
            throw new IllegalStateException("Server must be stopped before journal adjudication");
        for (String key : new String[]{"database", "user"})
            if (!required(config, key).matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid connection identifier");
        Path password = Path.of(expectedRoot, "plugins/ProsperityNPC/db-password.txt");
        if (!password.equals(password.toRealPath()) || Files.size(password) > 4096
                || Files.getPosixFilePermissions(password).stream()
                        .anyMatch(x -> x != PosixFilePermission.OWNER_READ && x != PosixFilePermission.OWNER_WRITE))
            throw new IllegalArgumentException("Invalid password custody");
        UUID reserve = UUID.fromString(required(config, "reserve_uuid"));
        try (Database db = new Database()) {
            db.connect(new DatabaseSettings("127.0.0.1", 3306, required(config, "database"), required(config, "user"),
                    Files.readString(password).trim(), 1, 5000));
            var journal = new TransferJournal(db, reserve);
            if (classify) {
                System.out.println(classifyReport(journal));
                return;
            }
            Path evidence = Path.of(args[2]).toAbsolutePath();
            byte[] bytes = privateBytes(evidence, MAX_EVIDENCE);
            Properties p = new Properties();
            p.load(new ByteArrayInputStream(bytes));
            if (!required(config, "database").equals(p.getProperty("database")))
                throw new IllegalArgumentException("Evidence belongs to another database");
            if (!expectedRoot.equals(required(p, "server_root")) || !service.equals(required(p, "service")))
                throw new IllegalArgumentException("Evidence belongs to another server");
            if (!reserve.toString().equals(required(p, "reserve_uuid"))) throw new IllegalArgumentException("Reserve evidence mismatch");
            var zone = ZoneId.of(required(config, "clock_timezone"));
            int resetHour = Integer.parseInt(required(config, "daily_reset_hour"));
            if (resetHour < 0 || resetHour > 23) throw new IllegalArgumentException("Invalid reset hour");
            var handle = new TransferJournal.Handle(UUID.fromString(required(p, "attempt_id")), Long.parseLong(required(p, "event_id")),
                    UUID.fromString(required(p, "run_id")), required(p, "provider_sha256"));
            boolean refund = mode.equals("--record-completed-refund");
            boolean legacy = !refund || "LEGACY".equals(p.getProperty("credit_proof"));
            String domain = domain(db, handle.eventId());
            Attempt attempt = attempt(db, handle);
            if (refund) {
                byte[] observed = null;
                for (String artifact : new String[]{"intent", "observation"}) {
                    byte[] linked = privateBytes(evidence.getParent().resolve(
                            artifact.equals("intent") ? "refund-intent.json" : "refund-observed.json"), MAX_EVIDENCE);
                    if (!LegacyProvenance.sha256(linked).equals(required(p, artifact + "_sha256")))
                        throw new IllegalArgumentException("Refund artifact evidence changed");
                    if (artifact.equals("observation")) observed = linked;
                }
                // Rent and appreciation are paid by the reserve; purchases and utility bills by the player.
                boolean reservePays = domain.equals("rent") || domain.equals("appreciation");
                if (reservePays != attempt.payer().equals(reserve))
                    throw new IllegalStateException("The stored payer does not match the payment's domain");
                RefundEvidence.check(p, attempt.payer(), handle.attemptId(), reservePays, observed);
                // An applied refund replays exactly or not at all, so only an unresolved row needs the saved balance.
                if (!attempt.state().equals("COMPENSATED"))
                    RefundEvidence.requireSaved(p, RefundEvidence.savedBalanceBits(Path.of(expectedRoot), attempt.payer()));
            }
            if (legacy) {
                String build = LegacyProvenance.verify(expectedRoot, attempt.createdAt(), p,
                        name -> privateBytes(evidence.getParent().resolve(name), MAX_JAR),
                        LegacyProvenance.namesIn(evidence.getParent()));
                System.out.println("Provenance: row written by reviewed build " + build + ".");
            }
            String completedOutcome = refund && domain.equals("purchase") ? "PAYMENT_COMPENSATED" : "PAYMENT_DECLINED";
            if (domain.equals("bill")) checkBillReceipt(db, reserve, handle.eventId(), attempt.payer());
            if (domain.equals("purchase") || domain.equals("utility")) {
                String receiptTable = domain.equals("purchase") ? "npc_purchase_intents" : "npc_utility_payments";
                try (var c = db.getConnection(); var query = c.prepareStatement(
                        "SELECT state,outcome_code FROM " + receiptTable + " WHERE event_id=?")) {
                    query.setQueryTimeout(5);
                    query.setLong(1, handle.eventId());
                    try (var row = query.executeQuery()) {
                        if (!row.next() || !("WAITING".equals(row.getString(1))
                                || ("DECLINED".equals(row.getString(1)) && completedOutcome.equals(row.getString(2)))))
                            throw new IllegalStateException("Domain receipt requires manual review; nothing recorded");
                    }
                }
            }
            if (!systemd(service, "ActiveState").equals("inactive") || !systemd(service, "LoadState").equals("masked"))
                throw new IllegalStateException("Offline custody changed");
            UUID resolutionId = UUID.fromString(required(p, "resolution_id"));
            String evidenceSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var result = refund
                    ? journal.resolveRefundedDebit(handle, resolutionId, required(p, "operator"), evidenceSha,
                            required(p, "observation_sha256"), Long.parseLong(required(p, "refund_before_bits")),
                            Long.parseLong(required(p, "refund_after_bits")), legacy, apply, distinctRefund)
                    : journal.resolveLegacyRefusal(handle, resolutionId, required(p, "operator"), evidenceSha, apply);
            if (!apply) {
                System.out.println("Dry run: event " + handle.eventId() + " would become " + result.state() + " ("
                        + result.outcome() + "), then the " + domain + " side settles. Nothing was written; add --apply.");
                return;
            }
            String outcome;
            switch (domain) {
                case "purchase" -> {
                    var receipt = new PurchaseRepository(db, reserve, Clock.systemUTC(), zone, resetHour)
                            .complete(handle.eventId()).orElseThrow();
                    if (receipt.state() != PurchaseRepository.State.DECLINED || !receipt.outcome().equals(completedOutcome))
                        throw new IllegalStateException("Domain completion not verified; resolution retained");
                    outcome = receipt.outcome();
                }
                case "utility" -> {
                    var receipt = new UtilityRepository(db, reserve, Clock.systemUTC(), zone, resetHour)
                            .reconcile(handle.eventId()).orElseThrow();
                    if (receipt.state() != UtilityRepository.State.DECLINED)
                        throw new IllegalStateException("Utility completion not verified; resolution retained");
                    outcome = receipt.outcome();
                }
                case "bill" -> outcome = completeBillReceipt(db, reserve, handle.eventId());
                default -> {
                    // Rent and appreciation have no domain receipt: the declined proof itself releases the reservation.
                    if (!declinedProved(db, journal, handle.eventId()))
                        throw new IllegalStateException("Payout release not verified; resolution retained");
                    outcome = "RESERVATION_RELEASED";
                }
            }
            // Reported once the domain side has settled, so a report that fails cannot leave it unsettled.
            if (distinctRefund)
                RefundClaims.reportDistinctRefund(db, RefundClaims.Source.TRANSFER, handle.attemptId(), resolutionId,
                        required(p, "operator"), Logger.getLogger(RefundRecoveryTool.class.getName()));
            System.out.println("Event " + handle.eventId() + ": " + result.state() + "; " + outcome);
        }
    }

    /** Counts per class, and the startup hold's own count, which must be the same total. */
    static String classifyReport(TransferJournal journal) throws Exception {
        long holding = journal.heldCount();
        var rows = journal.held(100_000);
        Map<HeldClass, Integer> counts = new EnumMap<>(HeldClass.class);
        for (HeldClass c : HeldClass.values()) counts.put(c, 0);
        for (var row : rows) counts.merge(row.heldClass(), 1, Integer::sum);
        if (rows.size() != holding)
            throw new IllegalStateException("Classified " + rows.size() + " rows but the startup hold sees " + holding);
        StringBuilder out = new StringBuilder("Transfer journal rows holding payments: " + holding);
        for (var entry : counts.entrySet()) out.append("\n  ").append(entry.getKey()).append(": ").append(entry.getValue());
        return out.toString();
    }

    /** The stored attempt's payer, state and creation time (UTC). */
    private record Attempt(UUID payer, String state, String createdAt) { }

    private static Attempt attempt(Database db, TransferJournal.Handle handle) throws Exception {
        try (var c = db.getConnection(); var query = c.prepareStatement(
                "SELECT payer_uuid,state,created_at FROM npc_transfer_attempts WHERE attempt_id=? AND event_id=?")) {
            query.setQueryTimeout(5);
            query.setBytes(1, com.prosperitysmp.npc.db.Uuids.toBytes(handle.attemptId()));
            query.setLong(2, handle.eventId());
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("Unknown transfer attempt");
                return new Attempt(com.prosperitysmp.npc.db.Uuids.fromBytes(row.getBytes(1)), row.getString(2), row.getString(3));
            }
        }
    }

    /** Exactly one domain owns the event. Older schemas have no configured bills table. */
    static String domain(Database db, long eventId) throws Exception {
        try (var c = db.getConnection()) {
            boolean bills = billReceiptsPresent(c);
            try (var query = c.prepareStatement("""
                SELECT (SELECT COUNT(*) FROM npc_purchase_intents WHERE event_id=?),
                       (SELECT COUNT(*) FROM npc_utility_payments WHERE event_id=?),
                       (SELECT COUNT(*) FROM npc_rent_reservations WHERE event_id=?),
                       (SELECT COUNT(*) FROM npc_appreciation_reservations WHERE event_id=?),
                """ + (bills ? "(SELECT COUNT(*) FROM npc_charge_payments WHERE event_id=?)" : "0"))) {
                query.setQueryTimeout(5);
                for (int i = 1; i <= (bills ? 5 : 4); i++) query.setLong(i, eventId);
                try (var row = query.executeQuery()) {
                    if (!row.next() || row.getInt(1) + row.getInt(2) + row.getInt(3) + row.getInt(4) + row.getInt(5) != 1)
                        throw new IllegalStateException("Requires exactly one purchase, utility, bill, rent or appreciation receipt");
                    if (row.getInt(1) == 1) return "purchase";
                    if (row.getInt(2) == 1) return "utility";
                    if (row.getInt(3) == 1) return "rent";
                    return row.getInt(4) == 1 ? "appreciation" : "bill";
                }
            }
        }
    }

    private static boolean billReceiptsPresent(Connection c) throws SQLException {
        try (var query = c.prepareStatement("""
                SELECT 1 FROM information_schema.TABLES
                WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='npc_charge_payments'
                """)) {
            query.setQueryTimeout(5);
            try (var row = query.executeQuery()) { return row.next(); }
        }
    }

    static void checkBillReceipt(Database db, UUID reserve, long eventId, UUID payer) throws SQLException {
        UUID request;
        try (var c = db.getConnection(); var query = c.prepareStatement(
                "SELECT request_id FROM npc_charge_payments WHERE event_id=?")) {
            query.setQueryTimeout(5);
            query.setLong(1, eventId);
            try (var row = query.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("Bill receipt missing; nothing recorded");
                request = Uuids.fromBytes(row.getBytes(1));
            }
        }
        var receipt = BillPayments.findByRequest(db, request, payer)
                .orElseThrow(() -> new IllegalStateException("Bill receipt does not match the payer; nothing recorded"));
        boolean reviewed = receipt.state() == BillPayments.State.REVIEW_REQUIRED
                && "TRANSFER_UNCERTAIN".equals(receipt.outcome());
        if (receipt.eventId() != eventId || (receipt.state() != BillPayments.State.WAITING
                && receipt.state() != BillPayments.State.DECLINED && !reviewed))
            throw new IllegalStateException("Bill receipt requires manual review; nothing recorded");
        if (!new BillPayments(db, reserve, Clock.systemUTC()).history(receipt.invoiceId()).contains(receipt))
            throw new IllegalStateException("Bill receipt history changed; nothing recorded");
    }

    static String completeBillReceipt(Database db, UUID reserve, long eventId) throws SQLException {
        var receipt = new BillPayments(db, reserve, Clock.systemUTC()).reconcile(eventId)
                .orElseThrow(() -> new IllegalStateException("Bill receipt missing; resolution retained"));
        if (receipt.state() != BillPayments.State.DECLINED)
            throw new IllegalStateException("Bill completion not verified; resolution retained");
        return receipt.outcome();
    }

    /** A fresh transaction proves the event declined or refunded, which is what releases a payout reservation. */
    private static boolean declinedProved(Database db, TransferJournal journal, long eventId) throws Exception {
        try (Connection c = db.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try { return journal.confirmedDeclined(c, eventId); }
            finally { c.rollback(); }
        }
    }

    private static String systemd(String service, String field) throws Exception {
        var process = new ProcessBuilder("systemctl", "show", service, "-p", field, "--value").start();
        if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0)
            throw new IllegalStateException("Service state unavailable");
        return new String(process.getInputStream().readAllBytes()).trim();
    }

    private static Properties privateProperties(Path path) throws Exception {
        Properties result = new Properties();
        result.load(new ByteArrayInputStream(privateBytes(path, MAX_EVIDENCE)));
        return result;
    }

    /** A private, root-owned regular file of bounded size, read once. */
    private static byte[] privateBytes(Path path, int limit) throws Exception {
        if (!path.equals(path.toRealPath()) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(path).getName().equals("root") || Files.size(path) < 1 || Files.size(path) > limit
                || Files.getPosixFilePermissions(path).stream()
                        .anyMatch(x -> x != PosixFilePermission.OWNER_READ && x != PosixFilePermission.OWNER_WRITE))
            throw new IllegalArgumentException("Evidence must be a bounded, private root-owned regular file: " + path.getFileName());
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length < 1 || bytes.length > limit) throw new IllegalArgumentException("Evidence size changed");
            return bytes;
        }
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing evidence field: " + key);
        return value;
    }
}
