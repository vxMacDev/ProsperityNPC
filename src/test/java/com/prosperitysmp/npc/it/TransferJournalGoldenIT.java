package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.economy.EconomyEvent;
import com.prosperitysmp.npc.economy.EconomyEventRepository;
import com.prosperitysmp.npc.economy.EventSource;
import com.prosperitysmp.npc.economy.TransferDispatchFixture;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferJournal.State;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the journal writes for ordinary whole-dollar payments. Every registered migration is applied, the
 * same flows run against fixed ids, and the rows are dumped with timestamps blanked. The dump must match the
 * file recorded before fractional balances became payable. Set NPC_GOLDEN_RECORD_DIR to write a fresh file.
 */
class TransferJournalGoldenIT {
    private static final String GOLDEN = "golden/golden-journal-v1.txt";
    private static final String DAY = "2026-10-14";
    private static final UUID WORLD = new UUID(0, 6300);
    private static final UUID PROPERTY = new UUID(3, 1);
    private static final UUID OWNER = new UUID(3, 2);
    private static final UUID RESERVE = new UUID(3, 3);
    private static final UUID RUN = new UUID(2, 1);
    private static final String PROVIDER = "b".repeat(64);
    private static final long CENTS = 1_000;
    private static DB mariaDb;
    private static Database database;

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        mariaDb.start();
        String schema = "npc_golden_" + UUID.randomUUID().toString().replace("-", "");
        mariaDb.createDB(schema);
        database = new Database();
        database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 4, 10_000L));
        var field = MigrationRunner.class.getDeclaredField("MIGRATIONS");
        field.setAccessible(true);
        for (Object migration : (List<?>) field.get(null)) {
            var resource = migration.getClass().getDeclaredMethod("resource");
            resource.setAccessible(true);
            try (InputStream in = TransferJournalGoldenIT.class.getResourceAsStream("/" + resource.invoke(migration))) {
                assertNotNull(in);
                for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
            }
        }
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (database != null) database.close();
        } finally {
            if (mariaDb != null) mariaDb.stop();
        }
    }

    @Test
    void wholeDollarFlowsWriteTheSameRows() throws Exception {
        new PropertyRepository(database).insertEnrollment(new Property(PROPERTY, WORLD, "golden-1", "downtown", "room",
                2, 1_000, 1_000, OWNER, 0, 80, true), 5_000);
        EconomyEventRepository events = new EconomyEventRepository(database);
        TransferJournal journal = new TransferJournal(database, RESERVE);
        int attempt = 0;
        for (double start : new double[]{100.0, 1000.0}) {
            for (EventSource source : List.of(EventSource.RENT, EventSource.APPRECIATION, EventSource.UTILITY,
                    EventSource.UPGRADE, EventSource.REPAIR, EventSource.INSURANCE, EventSource.EVICTION)) {
                Handle handle = pending(events, source, "paid|" + source + "|" + start, ++attempt);
                List<String> calls = new ArrayList<>();
                var receipt = TransferDispatchFixture.dispatch(journal, handle, calls, start).toCompletableFuture().join();
                assertEquals(State.PAID, receipt.state());
                assertEquals(List.of("authorize-DEBIT", "money-DEBIT", "authorize-CREDIT", "money-CREDIT", "release"), calls);
            }
        }
        for (EventSource source : List.of(EventSource.UPGRADE, EventSource.RENT)) {
            Handle declined = pending(events, source, "declined|" + source, ++attempt);
            journal.beginDebit(declined);
            assertEquals(State.DECLINED, journal.recordDebit(declined, new Observation(true, false, 0.0, 100.0, 100.0, false)).state());

            Handle threw = pending(events, source, "threw|" + source, ++attempt);
            journal.beginDebit(threw);
            assertEquals(State.AMBIGUOUS, journal.recordDebit(threw, new Observation(false, false, null, 100.0, 100.0, true)).state());

            Handle shortCredit = pending(events, source, "short|" + source, ++attempt);
            journal.beginDebit(shortCredit);
            assertEquals(State.CREDIT_READY, journal.recordDebit(shortCredit, new Observation(true, true, 10.0, 1000.0, 990.0, false)).state());
            assertTrue(journal.beginCredit(shortCredit).newlyStarted());
            assertEquals(State.AMBIGUOUS, journal.recordCredit(shortCredit, new Observation(true, true, 10.0, 1000.0, 1009.99, false)).state());
        }

        List<String> dump = new ArrayList<>();
        dump.add("# events");
        dump.addAll(rows("SELECT * FROM npc_economy_events ORDER BY event_id"));
        dump.add("# attempts");
        dump.addAll(rows("SELECT * FROM npc_transfer_attempts ORDER BY event_id"));
        dump.add("# observations");
        dump.addAll(rows("SELECT attempt_id,leg,response_present,response_success,reported_bits,before_bits,after_bits,threw,observed_at"
                + " FROM npc_transfer_observations ORDER BY attempt_id,leg"));
        dump.add("# funding");
        dump.addAll(rows("SELECT property_id,one_time_seed_cents,successful_cash_sinks_cents,"
                + "successful_cash_payouts_cents,updated_at FROM npc_property_funding ORDER BY property_id"));
        assertEquals(List.of("n=0"), rows("SELECT COUNT(*) AS n FROM npc_property_funding"
                + " WHERE successful_cash_unfunded_cents<>0"));
        assertTrue(dump.size() > 40, "positive control: the flows wrote rows");

        if (hasColumn("npc_transfer_observations", "refused_before_call")) {
            assertEquals(List.of("n=0"), rows("SELECT COUNT(*) AS n FROM npc_transfer_observations"
                    + " WHERE refused_before_call IS NOT NULL OR refusal_cause IS NOT NULL"));
        }

        String text = String.join("\n", dump) + "\n";
        String recordDir = System.getenv("NPC_GOLDEN_RECORD_DIR");
        if (recordDir != null && !recordDir.isBlank()) {
            Path out = Path.of(recordDir).resolve(GOLDEN);
            Files.createDirectories(out.getParent());
            Files.write(out, text.getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = TransferJournalGoldenIT.class.getClassLoader().getResourceAsStream(GOLDEN)) {
            assertNotNull(in, "missing golden file " + GOLDEN);
            assertEquals(new String(in.readAllBytes(), StandardCharsets.UTF_8), text);
        }
    }

    private static Handle pending(EconomyEventRepository events, EventSource source, String key, int attempt) throws SQLException {
        long eventId = events.insertPending(EconomyEvent.pending("golden|" + key, CENTS, source, PROPERTY, OWNER, 0, OWNER, DAY));
        return new Handle(new UUID(1, attempt), eventId, RUN, PROVIDER);
    }

    private static boolean hasColumn(String table, String column) throws SQLException {
        return !rows("SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + table
                + "' AND COLUMN_NAME='" + column + "'").isEmpty();
    }

    /** One line per row. Binary columns as hex, timestamps reduced to set or unset. */
    private static List<String> rows(String sql) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<String> result = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        int type = meta.getColumnType(i);
                        String text;
                        if (value == null) text = "NULL";
                        else if (type == Types.TIMESTAMP || type == Types.DATE || type == Types.TIME) text = "T";
                        else if (value instanceof byte[] bytes) text = HexFormat.of().formatHex(bytes);
                        else text = rs.getString(i);
                        row.add(meta.getColumnLabel(i) + "=" + text);
                    }
                    result.add(String.join(" ", row));
                }
                return result;
            }
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            ps.execute();
        }
    }
}
