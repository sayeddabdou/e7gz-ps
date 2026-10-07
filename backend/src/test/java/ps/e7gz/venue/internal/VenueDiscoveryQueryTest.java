package ps.e7gz.venue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-34: the customer venue list is ordered and paged by the database (ADR-019, arch 09 section 1.2). The statement
 * orders by the PostGIS KNN operator {@code <->} with a location, keyset-bounds the page with a row comparison, never
 * uses OFFSET or {@code ST_Distance} ordering, and the KNN step can use {@code ix_venue_location_gist}.
 */
class VenueDiscoveryQueryTest extends IntegrationTest {

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void theListStatementKeysetPagesInSqlWithoutOffset(boolean inArea, boolean paged) {
        String near = JdbcVenueDiscovery.listSql(true, inArea, paged);
        String byName = JdbcVenueDiscovery.listSql(false, inArea, paged);

        for (String sql : List.of(near, byName)) {
            assertThat(sql).doesNotContainIgnoringCase("OFFSET").doesNotContainIgnoringCase("ST_Distance")
                    .contains("v.marketplace_status = 'ACTIVE'").contains("a.activity = :activity")
                    .contains("LIMIT :limit");
            assertThat(sql.contains("v.area_id = :areaId")).isEqualTo(inArea);
        }
        // The KNN step orders by the distance operator alone, so the GiST index can serve it; those rows and their ties
        // are then ordered by (distance, id) in SQL.
        assertThat(near).contains("ORDER BY (v.location <-> ST_SetSRID(ST_MakePoint(CAST(:lng AS double precision)")
                .contains("ST_DWithin(v.location, ").contains("ORDER BY p.distance, p.id");
        assertThat(byName).contains("ORDER BY v.name, v.id").doesNotContain("<->");
        if (paged) {
            assertThat(near).contains("AND ((v.location <-> ").contains(", v.id) > ((SELECT c.location <-> ");
            assertThat(byName).contains("AND (v.name, v.id) > ((SELECT c.name FROM venue.venue c WHERE c.id = :after)");
        }
    }

    @Test
    void theNearestFirstListCanUseTheGistIndexForKnnOrdering() throws Exception {
        String plan = plan(JdbcVenueDiscovery.listSql(true, false, true));

        // Planner-tolerant: a tiny test table favours a btree and a sort, so sequential and bitmap scans and sorts are
        // ruled out; the KNN step must then be the GiST index scan with its "Order By" on the distance operator.
        assertThat(plan).contains("ix_venue_location_gist").contains("Order By: (location <->");
    }

    private static String plan(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR,
                MIGRATOR)) {
            connection.setAutoCommit(false);
            try {
                JdbcClient jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
                jdbc.sql("SET LOCAL enable_seqscan = off").update();
                jdbc.sql("SET LOCAL enable_bitmapscan = off").update();
                jdbc.sql("SET LOCAL enable_sort = off").update();
                List<String> lines = jdbc.sql("EXPLAIN " + sql)
                        .param("activity", "PLAYSTATION").param("limit", 21)
                        .param("lat", new BigDecimal("30.0444")).param("lng", new BigDecimal("31.2357"))
                        .param("after", UUID.randomUUID())
                        .query(String.class).list();
                return String.join("\n", lines);
            } finally {
                connection.rollback();
            }
        }
    }
}
