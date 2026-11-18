package ps.e7gz.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-71: every database session the application opens is UTC (arch 04 P2), whatever the JVM's zone - pgjdbc would
 * otherwise send the JVM's (Africa/Cairo on a developer machine), and the database's interval arithmetic would follow
 * that zone's DST ({@code trust.guard_ban_instance}, {@code ck_ban_instance_time_arithmetic}). The runtime pools
 * ({@code app}: api, worker loops, outbox dispatcher, LISTEN connections; {@code admin}) apply it on every physical
 * connection ({@code connection-init-sql}); the migrator applies it in Flyway's init SQL.
 */
class SessionTimeZoneIntegrationTest extends IntegrationTest {

    @Autowired
    DataSource appDataSource;

    @Autowired
    @Qualifier(DataSourceConfig.ADMIN_DATA_SOURCE)
    DataSource adminDataSource;

    @Autowired
    Flyway flyway;

    /** Several connections held at once, so more than one physical connection of each pool is checked. */
    @Test
    void everyAppAndAdminConnectionIsUtc() throws SQLException {
        assertThat(zones(appDataSource, 4)).containsOnly("UTC");
        assertThat(zones(adminDataSource, 3)).containsOnly("UTC");
    }

    /** The migrator - the one writer outside the pools - runs Flyway's init SQL, which sets UTC after its role. */
    @Test
    void theMigratorSessionIsUtc() throws SQLException {
        String init = flyway.getConfiguration().getInitSql();
        assertThat(init).contains("SET ROLE postgres;").contains("SET TIME ZONE 'UTC';");
        try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(init);
            assertThat(zone(connection)).isEqualTo("UTC");
        }
    }

    private static List<String> zones(DataSource dataSource, int connections) throws SQLException {
        List<Connection> held = new ArrayList<>();
        try {
            List<String> zones = new ArrayList<>();
            for (int i = 0; i < connections; i++) {
                Connection connection = dataSource.getConnection();
                held.add(connection);
                zones.add(zone(connection));
            }
            return zones;
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }

    private static String zone(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SHOW TIME ZONE")) {
            result.next();
            return result.getString(1);
        }
    }
}
