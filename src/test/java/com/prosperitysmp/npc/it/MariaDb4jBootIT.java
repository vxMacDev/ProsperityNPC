package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Viability probe: confirms MariaDB4j can unpack + boot a real MariaDB on this
 * platform and answer a query through the mariadb-java-client. If this fails, the
 * repository integration suite cannot run here.
 */
class MariaDb4jBootIT {

    @Test
    void embeddedMariaDbBootsAndAnswers() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0); // pick a free port
        DB db = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        db.start();
        try {
            db.createDB("probe");
            String url = "jdbc:mariadb://localhost:" + cfg.getPort() + "/probe";
            try (Connection c = DriverManager.getConnection(url, "root", "");
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        } finally {
            db.stop();
        }
    }
}
