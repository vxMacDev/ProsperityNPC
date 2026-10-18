package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository;
import com.prosperitysmp.npc.commerce.ItemCustody;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.accounting.AccountingSink;
import com.prosperitysmp.npc.economy.accounting.SkriptSink;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/**
 * Absolute transfer-counter projection, not money movement or an exactly-once audit callback.
 * Itemized authority remains in SQL (events, attempts and observations); DTOs are private,
 * rebuildable views, not a public endpoint. No CURRENT.json or duplicate audit journal is written.
 * Skript must preserve other sources and acknowledge only after applying both categories.
 */
public final class AccountingProjection implements AutoCloseable {
    private static final long TTL = Duration.ofSeconds(30).toNanos();
    private static final long REFRESH = Duration.ofSeconds(5).toNanos();
    private static final long READ_BUDGET = Duration.ofSeconds(20).toNanos();
    private static final List<String> COMMERCE_SOURCES = List.of("npc-worker-wages", "npc-merchant-goods");
    public record SourceTotal(String source, long cents) {
        public String getSource() { return source; }
        public long getCents() { return cents; }
    }
    public record Group(String day, String category, List<SourceTotal> sources) {
        public Group { sources = List.copyOf(sources); }
        public String getDay() { return day; }
        public String getCategory() { return category; }
        public List<SourceTotal> getSources() { return sources; }
    }
    public record Item(long eventId, UUID payer, UUID payee, String source, long cents, Instant finalizedAt) {
        public long getEventId() { return eventId; }
        public String getPayer() { return payer.toString(); }
        public String getPayee() { return payee.toString(); }
        public String getSource() { return source; }
        public long getCents() { return cents; }
        public String getFinalizedAt() { return finalizedAt.toString(); }
    }
    public record CommerceReceipt(UUID orderId, UUID payer, UUID reserve, String source, long cents, Instant fundedAt) {
        public CommerceReceipt {
            Objects.requireNonNull(orderId);
            Objects.requireNonNull(payer);
            Objects.requireNonNull(reserve);
            Objects.requireNonNull(fundedAt);
            if (payer.equals(reserve) || cents <= 0 || !COMMERCE_SOURCES.contains(source) || fundedAt.toEpochMilli() < 0)
                throw new IllegalArgumentException("Invalid commerce accounting receipt");
        }
        public String getOrderId() { return orderId.toString(); }
        public String getPayer() { return payer.toString(); }
        public String getReserve() { return reserve.toString(); }
        public String getSource() { return source; }
        public long getCents() { return cents; }
        public String getFundedAt() { return fundedAt.toString(); }
    }
    public record Snapshot(long generation, Instant capturedAt, String zone, List<Group> groups, List<Item> receipts,
                           List<CommerceReceipt> commerceReceipts) {
        public Snapshot {
            groups = List.copyOf(groups);
            receipts = List.copyOf(receipts);
            commerceReceipts = List.copyOf(commerceReceipts);
        }
        public Snapshot(long generation, Instant capturedAt, String zone, List<Group> groups, List<Item> receipts) {
            this(generation, capturedAt, zone, groups, receipts, List.of());
        }
        public long getGeneration() { return generation; }
        public String getCapturedAt() { return capturedAt.toString(); }
        public String getZone() { return zone; }
        public List<Group> getGroups() { return groups; }
        public List<Item> getReceipts() { return receipts; }
        public List<CommerceReceipt> getCommerceReceipts() { return commerceReceipts; }
    }
    private record Completion(long generation, long started, Snapshot snapshot) { }
    private final Database database;
    private final TransferJournal transfers;
    private final CommerceOrderRepository commerce;
    private final UUID reserve;
    private final AccountingSink sink;
    private final Clock clock = Clock.systemUTC();
    private final ZoneId zone = ZoneId.systemDefault();
    private final ThreadPoolExecutor worker;
    private final BukkitTask ticker;
    private final Object completionLock = new Object();
    private Completion completion;
    private boolean closed, inFlight;
    private long generation, acknowledged = -1, submitted, snapshotStarted;
    private Instant lastWall;
    private Snapshot current;

    public AccountingProjection(ProsperityNpcPlugin plugin) {
        this(plugin, new SkriptSink(() -> plugin.bridgeApi() != null && plugin.bridgeApi().isBridgeConnected()));
    }

    /** The sink decides who acknowledges a snapshot: Skript (passive) or the plugin itself (internal). */
    public AccountingProjection(ProsperityNpcPlugin plugin, AccountingSink sink) {
        main();
        Objects.requireNonNull(plugin);
        this.sink = Objects.requireNonNull(sink);
        database = plugin.database();
        reserve = ReserveAccount.fromConfig(plugin.getConfig());
        transfers = new TransferJournal(database, reserve);
        commerce = new CommerceOrderRepository(database, reserve);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), r -> {
            Thread t = new Thread(r, "npc-accounting-projection");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        try {
            plugin.trackDatabaseWorker(worker);
            ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
        } catch (RuntimeException | Error failure) {
            worker.shutdown();
            throw failure;
        }
    }

    /** Main-thread read; not proof that Skript variables have been flushed durably to disk. */
    public boolean ready() {
        main();
        expire();
        return current != null && acknowledged == current.generation();
    }
    public Snapshot snapshot() {
        main();
        expire();
        return current;
    }
    public boolean acknowledge(long value) {
        main();
        expire();
        if (current == null || value != current.generation()) return false;
        acknowledged = value;
        return true;
    }

    /** Host calls after a new payment/uncertain outcome, before permitting another debit. */
    public void invalidate() {
        main();
        generation = Math.incrementExact(generation);
        current = null;
        acknowledged = -1;
        submitted = 0;
    }

    private void expire() {
        Instant now = clock.instant();
        boolean backwards = lastWall != null && now.isBefore(lastWall);
        lastWall = now;
        if (closed || backwards || !zone.equals(ZoneId.systemDefault())) {
            if (backwards) generation = Math.incrementExact(generation);
            current = null;
            acknowledged = -1;
        }
        if (current != null && (now.isBefore(current.capturedAt())
                || Duration.between(current.capturedAt(), now).compareTo(Duration.ofSeconds(30)) >= 0
                || System.nanoTime() - snapshotStarted < 0 || System.nanoTime() - snapshotStarted >= TTL)) {
            current = null;
            acknowledged = -1;
        }
    }

    private void tick() {
        main();
        expire();
        if (closed) return;
        Completion done;
        synchronized (completionLock) {
            done = completion;
            completion = null;
        }
        if (done != null) {
            inFlight = false;
            if (done.generation() == generation) {
                long next = acknowledgementAfterRefresh(current, acknowledged, done.snapshot(), sink.selfAcknowledges());
                current = done.snapshot();
                snapshotStarted = done.started();
                acknowledged = next;
                expire();
            }
        }
        long now = System.nanoTime();
        if (inFlight || submitted != 0 && now - submitted < REFRESH) return;
        if (!zone.equals(ZoneId.systemDefault())) return;
        long token = Math.incrementExact(generation);
        generation = token;
        submitted = now;
        inFlight = true;
        try {
            worker.execute(() -> {
                Snapshot value = null;
                try { value = read(token, now); }
                catch (Exception | LinkageError held) { /* No partial zeros or unverifiable receipts may publish. */ }
                finally {
                    synchronized (completionLock) {
                        if (!closed) completion = new Completion(token, now, value);
                    }
                }
            });
        } catch (RuntimeException rejected) {
            inFlight = false;
            current = null;
            acknowledged = -1;
        }
    }

    /**
     * What acknowledged becomes when a refresh of the current generation finishes. A failed read acknowledges
     * nothing. A self-acknowledging sink (accounting.sink=internal) acknowledges the fresh snapshot's own generation;
     * otherwise only an unchanged, already acknowledged snapshot keeps its acknowledgement. Called only for the
     * current generation, never from invalidate().
     */
    public static long acknowledgementAfterRefresh(Snapshot previous, long acknowledged, Snapshot next, boolean selfAcknowledges) {
        if (next == null) return -1;
        if (selfAcknowledges) return next.generation();
        return canRetainAcknowledgement(previous, acknowledged, next) ? next.generation() : -1;
    }

    /** The sink this projection reports to. */
    public AccountingSink sink() {
        return sink;
    }

    // A refresh of identical projected data is not a new accounting obligation.
    // invalidate()/expire() clear current, so neither can inherit an old acknowledgement.
    static boolean canRetainAcknowledgement(Snapshot previous, long acknowledged, Snapshot next) {
        return previous != null && next != null && acknowledged == previous.generation()
                && !next.capturedAt().isBefore(previous.capturedAt())
                && previous.zone().equals(next.zone()) && previous.groups().equals(next.groups())
                && previous.receipts().equals(next.receipts()) && previous.commerceReceipts().equals(next.commerceReceipts());
    }

    private Snapshot read(long token, long started) throws SQLException {
        Instant captured = clock.instant();
        // confirmedPaid uses locking proof reads, so this is a rollback-only transaction,
        // not ReadSnapshot's SQL READ ONLY mode. No INSERT/UPDATE/DELETE is issued here.
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                return readSnapshot(c, transfers, commerce, reserve, token, captured, zone, started);
            } finally { c.rollback(); }
        }
    }

    static Snapshot readSnapshot(Connection c, TransferJournal transfers, CommerceOrderRepository commerce, UUID reserve,
                                 long token, Instant captured, ZoneId zone, long started) throws SQLException {
        Objects.requireNonNull(c);
        Objects.requireNonNull(transfers);
        Objects.requireNonNull(commerce);
        Objects.requireNonNull(reserve);
        Objects.requireNonNull(captured);
        Objects.requireNonNull(zone);
        if (c.getAutoCommit() || c.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ)
            throw new SQLException("Accounting requires caller-owned repeatable-read transaction");
        LocalDate today = captured.atZone(zone).toLocalDate();
        Instant from = today.minusDays(6).atStartOfDay(zone).toInstant();
        var items = new ArrayList<Item>();
        var commerceItems = new ArrayList<CommerceReceipt>();
        String slots = String.join(",", Collections.nCopies(EventSource.values().length, "?"));
        try (PreparedStatement ps = c.prepareStatement("SELECT e.event_id,e.source,e.direction,e.paid_amount_cents,e.finalized_at,"
                + "a.payer_uuid,a.payee_uuid FROM npc_economy_events e LEFT JOIN npc_transfer_attempts a ON a.event_id=e.event_id "
                + "WHERE e.state='PAID' AND (e.finalized_at IS NULL OR e.finalized_at>=?) AND e.source IN (" + slots + ") "
                + "ORDER BY e.event_id LIMIT 10001")) {
            ps.setQueryTimeout(5);
            ps.setObject(1, LocalDateTime.ofInstant(from, ZoneOffset.UTC));
            int index = 2;
            for (EventSource source : EventSource.values()) ps.setString(index++, source.key());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    warnings(c, ps, rs);
                    budget(started);
                    if (items.size() >= 10000) throw new SQLException("Accounting row bound");
                    EventSource source = source(rs.getString("source"));
                    long id = rs.getLong("event_id");
                    if (rs.wasNull() || id <= 0) throw new SQLException("Accounting event identity");
                    long cents = rs.getLong("paid_amount_cents");
                    if (rs.wasNull() || cents <= 0 || !source.direction().moneyFlowKey().equals(rs.getString("direction")))
                        throw new SQLException("Accounting amount or direction");
                    LocalDateTime time = rs.getObject("finalized_at", LocalDateTime.class);
                    if (time == null || time.toInstant(ZoneOffset.UTC).isAfter(captured)) throw new SQLException("Accounting timestamp");
                    items.add(new Item(id, uuid(rs, "payer_uuid"), uuid(rs, "payee_uuid"), source.key(), cents,
                            time.toInstant(ZoneOffset.UTC)));
                }
                warnings(c, ps, rs);
            }
        }
        long previous = 0;
        for (Item item : items) {
            budget(started);
            if (item.eventId() <= previous || !transfers.confirmedPaid(c, item.eventId()))
                throw new SQLException("Accounting paid proof unavailable");
            previous = item.eventId();
        }
        warnings(c, null, null);
        budget(started);
        var seenOrders = new HashSet<UUID>();
        for (CommerceOrderRepository.PaidProof proof : commerce.paidProofs(c, from, captured, CommerceOrderRepository.MAX_PAID_PROOFS)) {
            budget(started);
            if (proof == null || proof.order() == null || proof.handle() == null || proof.fundedAt() == null
                    || proof.outputReadyAt() == null || !seenOrders.add(proof.order().orderId())
                    || !proof.order().orderId().equals(proof.handle().orderId())
                    || !proof.order().playerId().equals(proof.handle().payerId()) || !reserve.equals(proof.handle().reserveId())
                    || proof.handle().payerId().equals(reserve) || proof.order().costCents() <= 0
                    || proof.fundedAt().isBefore(from) || proof.fundedAt().isAfter(captured)
                    || proof.fundedAt().isBefore(proof.order().issuedAt())
                    || !proof.outputReadyAt().equals(proof.fundedAt().plusSeconds(proof.order().outputDelaySeconds()))
                    || TransferProof.evaluateTransfer(proof.order().costCents(), proof.debit(),
                            proof.credit()) != TransferProof.TransferOutcome.PAID)
                throw new SQLException("Accounting commerce proof binding");
            commerceItems.add(new CommerceReceipt(proof.order().orderId(), proof.handle().payerId(), reserve,
                    commerceSource(proof.order().source()), proof.order().costCents(), proof.fundedAt()));
        }
        warnings(c, null, null);
        budget(started);
        // The bills key appears only once a bills event exists, read in this same transaction. Rows are never
        // deleted, so once it has appeared it stays, and Skript never sees a key vanish.
        boolean billsActive = billsActive(c);
        var groups = new ArrayList<Group>();
        for (int offset = 6; offset >= 0; offset--) {
            String day = today.minusDays(offset).toString();
            for (String category : List.of("xfer-to-public", "xfer-from-public")) {
                var totals = new LinkedHashMap<String, Long>();
                for (EventSource source : EventSource.values()) {
                    if (source == EventSource.BILLS && !billsActive) continue;
                    totals.put(source.key(), 0L);
                }
                for (String source : COMMERCE_SOURCES) {
                    if (totals.putIfAbsent(source, 0L) != null) throw new SQLException("Accounting source collision");
                }
                for (Item item : items) if (item.finalizedAt().atZone(zone).toLocalDate().toString().equals(day)
                        && category(source(item.source())).equals(category))
                    totals.merge(item.source(), item.cents(), Math::addExact);
                if (category.equals("xfer-from-public")) {
                    for (CommerceReceipt item : commerceItems) if (item.fundedAt().atZone(zone).toLocalDate().toString().equals(day))
                        totals.merge(item.source(), item.cents(), Math::addExact);
                }
                var sources = new ArrayList<SourceTotal>();
                for (var entry : totals.entrySet()) {
                    // Skript stores dollar numbers. Refuse totals that cannot round-trip exact cents.
                    if (TransferProof.toCents(entry.getValue() / 100.0).orElse(-1) != entry.getValue())
                        throw new SQLException("Accounting numeric bound");
                    sources.add(new SourceTotal(entry.getKey(), entry.getValue()));
                }
                groups.add(checkedGroup(day, category, sources));
            }
        }
        budget(started);
        return new Snapshot(token, captured, zone.getId(), groups, items, commerceItems);
    }

    /** The Skript bridge's limit on sources in one group (npc-economy-bridge.sk). */
    static final int MAX_SOURCES_PER_GROUP = 32;

    static Group checkedGroup(String day, String category, List<SourceTotal> sources) throws SQLException {
        if (sources.size() > MAX_SOURCES_PER_GROUP) throw new SQLException("Accounting source bound");
        return new Group(day, category, sources);
    }

    /** Whether any bills event was ever written. Caller's repeatable-read transaction. */
    static boolean billsActive(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT EXISTS(SELECT 1 FROM npc_economy_events WHERE source=?)")) {
            ps.setQueryTimeout(5);
            ps.setString(1, EventSource.BILLS.key());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Accounting bills check unavailable");
                warnings(c, ps, rs);
                return rs.getBoolean(1);
            }
        }
    }

    private static String commerceSource(ItemCustody.Source source) {
        return switch (source) {
            case WORKER_OUTPUT -> "npc-worker-wages";
            case MERCHANT_GOODS -> "npc-merchant-goods";
            // Item-repair returns move no money and are never a commerce order (the order table refuses them).
            case REPAIR_RETURN -> throw new IllegalStateException("Repair returns are not commerce");
        };
    }

    private static EventSource source(String key) throws SQLException {
        for (EventSource source : EventSource.values()) if (source.key().equals(key)) return source;
        throw new SQLException("Accounting source unavailable");
    }
    private static String category(EventSource source) { return source.isPayout() ? "xfer-to-public" : "xfer-from-public"; }
    private static UUID uuid(ResultSet rs, String field) throws SQLException {
        byte[] bytes = rs.getBytes(field);
        if (bytes == null || bytes.length != 16) throw new SQLException("Accounting account unavailable");
        return Uuids.fromBytes(bytes);
    }
    private static void budget(long started) throws SQLException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - started >= READ_BUDGET)
            throw new SQLException("Accounting read budget");
    }
    private static void warnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        if (c.getWarnings() != null || ps != null && ps.getWarnings() != null || rs != null && rs.getWarnings() != null)
            throw new SQLException("Accounting read warning");
    }
    @Override public void close() {
        main();
        synchronized (completionLock) {
            closed = true;
            completion = null;
        }
        current = null;
        acknowledged = -1;
        ticker.cancel();
        worker.shutdown();
    }
    private static void main() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required"); }
}
