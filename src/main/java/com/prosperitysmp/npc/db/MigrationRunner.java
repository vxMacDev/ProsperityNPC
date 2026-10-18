package com.prosperitysmp.npc.db;

import com.prosperitysmp.npc.ProsperityNpcPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * A tiny forward-only migrator. Each migration is an additive SQL resource
 * applied at most once and recorded in {@code npc_schema_migrations}. DDL in
 * MariaDB auto-commits, so migrations use idempotent creates or repeatable
 * widening alters; a partially applied migration can be retried.
 */
public final class MigrationRunner {

    private record Migration(int version, String description, String resource) {
    }

    /**
     * How long a migration statement may wait for a table lock before it gives up. An ALTER queues behind any
     * open transaction that has touched the table, and the server default wait is a day. Kept under the pool's
     * 10 second socket timeout so the server reports the timeout before the client drops the connection.
     */
    static final int LOCK_WAIT_SECONDS = 5;
    private static final int ER_LOCK_WAIT_TIMEOUT = 1205;

    // Ordered list of migrations. Add new entries; never edit an applied one.
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "initial_schema", "db/migration/V1__initial_schema.sql"),
            new Migration(2, "resident_identity", "db/migration/V2__resident_identity.sql"),
            new Migration(3, "purchase_receipts", "db/migration/V3__purchase_receipts.sql"),
            new Migration(4, "transfer_journal", "db/migration/V4__transfer_journal.sql"),
            new Migration(5, "resident_route_tags", "db/migration/V5__resident_route_tags.sql"),
            new Migration(6, "incident_repairs", "db/migration/V6__incident_repairs.sql"),
            new Migration(7, "incident_rolls", "db/migration/V7__incident_rolls.sql"),
            new Migration(8, "eviction_tenancies", "db/migration/V8__eviction_tenancies.sql"),
            new Migration(9, "housing_registry", "db/migration/V9__housing_registry.sql"),
            new Migration(10, "housing_moves", "db/migration/V10__housing_moves.sql"),
            new Migration(11, "housing_history_order", "db/migration/V11__housing_history_order.sql"),
            new Migration(12, "incident_service_receipts", "db/migration/V12__incident_service_receipts.sql"),
            new Migration(13, "utility_obligations", "db/migration/V13__utility_obligations.sql"),
            new Migration(14, "utility_service_cohort", "db/migration/V14__utility_service_cohort.sql"),
            new Migration(15, "tenancy_departures", "db/migration/V15__tenancy_departures.sql"),
            new Migration(16, "rent_reservations", "db/migration/V16__rent_reservations.sql"),
            new Migration(17, "transfer_resolutions", "db/migration/V17__transfer_resolutions.sql"),
            new Migration(18, "civic_placements", "db/migration/V18__civic_placements.sql"),
            new Migration(19, "town_tasks", "db/migration/V19__town_tasks.sql"),
            new Migration(20, "training_grants", "db/migration/V20__training_grants.sql"),
            new Migration(21, "item_custody", "db/migration/V21__item_custody.sql"),
            new Migration(22, "commerce_orders", "db/migration/V22__commerce_orders.sql"),
            new Migration(23, "property_insurance", "db/migration/V23__property_insurance.sql"),
            new Migration(24, "auto_care", "db/migration/V24__auto_care.sql"),
            new Migration(25, "commerce_resolution", "db/migration/V25__commerce_resolution.sql"),
            new Migration(26, "chronicle_outbox", "db/migration/V26__chronicle_outbox.sql"),
            new Migration(27, "premium_admission_evidence", "db/migration/V27__premium_admission_evidence.sql"),
            new Migration(28, "tenant_moods", "db/migration/V28__tenant_moods.sql"),
            new Migration(29, "voluntary_departures", "db/migration/V29__voluntary_departures.sql"),
            new Migration(30, "appreciation_reservations", "db/migration/V30__appreciation_reservations.sql"),
            new Migration(31, "rent_change_delay", "db/migration/V31__rent_change_delay.sql"),
            new Migration(32, "owner_away_decisions", "db/migration/V32__owner_away_decisions.sql"),
            new Migration(33, "payout_retirements", "db/migration/V33__payout_retirements.sql"),
            new Migration(34, "linked_accounts", "db/migration/V34__linked_accounts.sql"),
            new Migration(35, "item_repairs", "db/migration/V35__item_repairs.sql"),
            new Migration(36, "town_contracts_partnerships", "db/migration/V36__town_contracts_partnerships.sql"),
            new Migration(37, "partnership_handin_terms", "db/migration/V37__partnership_handin_terms.sql"),
            new Migration(38, "item_return_staff_decision", "db/migration/V38__item_return_staff_decision.sql"),
            new Migration(39, "nav_surface", "db/migration/V39__nav_surface.sql"),
            new Migration(40, "money_evidence", "db/migration/V40__money_evidence.sql"),
            new Migration(41, "charge_catalogue", "db/migration/V41__charge_catalogue.sql"),
            new Migration(42, "charge_invoices", "db/migration/V42__charge_invoices.sql"),
            new Migration(43, "meter_baselines", "db/migration/V43__meter_baselines.sql"),
            new Migration(44, "nav_graph_sources", "db/migration/V44__nav_graph_sources.sql"),
            new Migration(45, "property_backend", "db/migration/V45__property_backend.sql"),
            new Migration(46, "charge_policy_history", "db/migration/V46__charge_policy_history.sql"),
            new Migration(47, "purchase_prices", "db/migration/V47__purchase_prices.sql"),
            new Migration(48, "auto_care_prices", "db/migration/V48__auto_care_prices.sql"),
            new Migration(49, "upgrade_cost_width", "db/migration/V49__upgrade_cost_width.sql"),
            new Migration(50, "eviction_price", "db/migration/V50__eviction_price.sql"),
            new Migration(51, "insurance_prices", "db/migration/V51__insurance_prices.sql"),
            new Migration(52, "utility_prices", "db/migration/V52__utility_prices.sql"),
            new Migration(53, "auto_care_utility_prices", "db/migration/V53__auto_care_utility_prices.sql"),
            new Migration(54, "repair_prices", "db/migration/V54__repair_prices.sql"),
            new Migration(55, "item_repair_prices", "db/migration/V55__item_repair_prices.sql"),
            new Migration(56, "auto_care_repair_prices", "db/migration/V56__auto_care_repair_prices.sql"));

    private final ProsperityNpcPlugin plugin;
    private final Database database;

    public MigrationRunner(ProsperityNpcPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    /** Applies any pending migrations; returns the highest applied version. */
    public int run() throws SQLException {
        try (Connection c = database.getConnection()) {
            ensureMigrationsTable(c);
            int highest = 0;
            for (Migration m : MIGRATIONS) {
                if (isApplied(c, m.version())) {
                    highest = Math.max(highest, m.version());
                    continue;
                }
                String sql = readResource(m.resource());
                int statements = applyMigration(c, m, sql);
                plugin.getLogger().info("Applied migration V" + m.version()
                        + " (" + m.description() + "): " + statements + " statements.");
                highest = Math.max(highest, m.version());
            }
            return highest;
        }
    }

    private void ensureMigrationsTable(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS npc_schema_migrations (
                      version     INT          NOT NULL,
                      description VARCHAR(128) NOT NULL,
                      applied_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                      PRIMARY KEY (version)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
        }
    }

    private boolean isApplied(Connection c, int version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM npc_schema_migrations WHERE version = ?")) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int applyMigration(Connection c, Migration m, String sql) throws SQLException {
        List<String> statements = splitStatements(sql);
        applyStatements(c, "V" + m.version() + " (" + m.description() + ")", statements, LOCK_WAIT_SECONDS);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO npc_schema_migrations (version, description) VALUES (?, ?)")) {
            ps.setInt(1, m.version());
            ps.setString(2, m.description());
            ps.executeUpdate();
        }
        return statements.size();
    }

    /**
     * Runs the statements with the session's lock wait cut to {@code lockWaitSeconds}, then puts it back (the
     * connection goes back to a pool). A statement that cannot get its lock fails with one clear message. DDL
     * commits as it runs, so statements before it stay applied; the script is repeatable, so it can simply be
     * retried once the other session is done.
     */
    public static int applyStatements(Connection c, String label, List<String> statements, int lockWaitSeconds)
            throws SQLException {
        int previous = sessionLockWait(c);
        setSessionLockWait(c, lockWaitSeconds);
        int running = 0;
        try {
            for (String stmt : statements) {
                running++;
                try (Statement st = c.createStatement()) {
                    st.execute(stmt);
                }
            }
            return statements.size();
        } catch (SQLException failure) {
            if (isLockTimeout(failure)) {
                throw new SQLException(lockTimeoutMessage(label, lockWaitSeconds, running, statements.size()),
                        failure.getSQLState(), failure.getErrorCode(), failure);
            }
            throw failure;
        } finally {
            try {
                setSessionLockWait(c, previous);
            } catch (SQLException ignored) {
                // The connection is already unusable; the pool will replace it.
            }
        }
    }

    /**
     * Error 1205 is raised for a metadata lock (another session has the table open in a transaction) and for
     * an InnoDB row lock alike, so the message names both. Earlier statements of the script are already
     * applied, because each DDL statement commits by itself.
     */
    static String lockTimeoutMessage(String label, int lockWaitSeconds, int statement, int statements) {
        return "Migration " + label + " gave up waiting for a lock (statement " + statement + " of " + statements
                + ", limit " + lockWaitSeconds + " s). Another session holds the table open in a transaction, or holds"
                + " a row lock on it. Earlier statements of the script may already be applied; it is safe to run again:"
                + " restart once that session has finished";
    }

    static boolean isLockTimeout(SQLException failure) {
        return failure.getErrorCode() == ER_LOCK_WAIT_TIMEOUT;
    }

    private static int sessionLockWait(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT @@SESSION.lock_wait_timeout")) {
            return rs.next() ? rs.getInt(1) : 86_400;
        }
    }

    private static void setSessionLockWait(Connection c, int seconds) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("SET SESSION lock_wait_timeout = " + seconds);
        }
    }

    /** Splits a plain-DDL script on statement-terminating semicolons, stripping line comments. */
    public static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String rawLine : sql.split("\n")) {
            String line = rawLine;
            int comment = line.indexOf("--");
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            cur.append(line).append('\n');
            if (line.stripTrailing().endsWith(";")) {
                String stmt = cur.toString().trim();
                stmt = stmt.substring(0, stmt.length() - 1).trim();
                if (!stmt.isEmpty()) {
                    out.add(stmt);
                }
                cur.setLength(0);
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }

    private String readResource(String path) throws SQLException {
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) {
                throw new SQLException("migration resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLException("failed reading migration " + path, e);
        }
    }
}
