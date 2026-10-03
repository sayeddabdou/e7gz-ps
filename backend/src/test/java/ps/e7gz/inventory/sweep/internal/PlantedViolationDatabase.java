package ps.e7gz.inventory.sweep.internal;

import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.common.codes.ReservationType;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-32 controlled fixture for planted violations. The database correctly refuses every state the sweep exists to
 * detect ({@code ex_no_double_booking}, the cross-class guard, {@code ck_booking_terminal_reason}), and those guards
 * are never disabled or weakened. So the planted rows live in a separate scratch database in the same container:
 * {@code inventory.resource_reservation} and {@code booking.booking} there have exactly the migrated tables' column
 * names and types, read from the migrated database's catalog, and no constraints or triggers. The sweep's production
 * SQL runs unchanged against it; the application database is never touched.
 */
final class PlantedViolationDatabase {

    static final String NAME = "e7gz_e732_sweep_plant";

    private static final List<String> TABLES = List.of("inventory.resource_reservation", "booking.booking");

    /** Real column list of a migrated table; a domain column is declared with its base type. */
    private static final String COLUMNS = """
            SELECT string_agg(format('%I %s', a.attname,
                       format_type(CASE WHEN t.typtype = 'd' THEN t.typbasetype ELSE a.atttypid END,
                                   CASE WHEN t.typtype = 'd' THEN t.typtypmod ELSE a.atttypmod END)),
                       ', ' ORDER BY a.attnum)
              FROM pg_attribute a
              JOIN pg_type t ON t.oid = a.atttypid
             WHERE a.attrelid = CAST(? AS regclass) AND a.attnum > 0 AND NOT a.attisdropped
            """;

    private static DataSource dataSource;

    private PlantedViolationDatabase() {
    }

    /** Created once per test JVM, from the migrated schema of that moment. */
    static synchronized DataSource dataSource() {
        if (dataSource == null) {
            create();
            dataSource = new SimpleDriverDataSource(new org.postgresql.Driver(),
                    PostgresTestContainer.jdbcUrl(NAME), "postgres", "postgres");
        }
        return dataSource;
    }

    static JdbcClient jdbc() {
        return JdbcClient.create(dataSource());
    }

    static void clear() {
        jdbc().sql("TRUNCATE " + String.join(", ", TABLES)).update();
    }

    /**
     * Fixed, ordered reservation ids for tests where the pair's orientation matters: the overlap check pairs rows
     * {@code a.id < b.id}, and PostgreSQL orders uuids by their bytes, so {@code ordered(1) < ordered(2) < ...}.
     */
    static UUID ordered(int n) {
        return UUID.fromString("e7320000-5eed-4000-8000-%012x".formatted(n));
    }

    /** An active (or released) reservation row with a random id, for tests where the pair's orientation is irrelevant. */
    static UUID reservation(UUID resource, ReservationType type, String start, String end, boolean active) {
        return reservation(UUID.randomUUID(), resource, type, start, end, active);
    }

    /** An active (or released) reservation row; the owner column the type requires is set as in production. */
    static UUID reservation(UUID id, UUID resource, ReservationType type, String start, String end, boolean active) {
        UUID owner = UUID.randomUUID();
        OffsetDateTime from = utc(start);
        OffsetDateTime to = utc(end);
        OffsetDateTime created = from.minusDays(1);
        jdbc().sql("""
                        INSERT INTO inventory.resource_reservation (id, resource_id, reservation_type, booking_id,
                                                                    tournament_id, maintenance_id, closure_id,
                                                                    planned_start, planned_end, planned_range,
                                                                    actual_reservation_start,
                                                                    actual_reservation_release_at, release_reason,
                                                                    active_flag, version)
                        VALUES (:id, :resource, :type, :booking, :tournament, :maintenance, :closure, :from, :to,
                                tstzrange(:from, :to, '[)'), :created, :releasedAt, :releaseReason, :active, 0)
                        """)
                .param("id", id).param("resource", resource).param("type", type.name())
                .param("booking", type == ReservationType.BOOKING || type == ReservationType.LATE_CONFIRM_HOLD
                        ? owner : null)
                .param("tournament", type == ReservationType.TOURNAMENT_APPROVAL_HOLD
                        || type == ReservationType.TOURNAMENT_HOLD ? owner : null)
                .param("maintenance", type == ReservationType.MAINTENANCE ? owner : null)
                .param("closure", type == ReservationType.CLOSURE ? owner : null)
                .param("from", from).param("to", to).param("created", created)
                .param("releasedAt", active ? null : created.plusHours(1))
                .param("releaseReason", active ? null : "E7-32 planted release")
                .param("active", active)
                .update();
        return id;
    }

    static UUID booking(String status, String terminalReason) {
        UUID id = UUID.randomUUID();
        jdbc().sql("INSERT INTO booking.booking (id, status, terminal_reason) VALUES (:id, :status, :reason)")
                .param("id", id).param("status", status).param("reason", terminalReason).update();
        return id;
    }

    /** Every planted row of both tables, as text, in a stable order: equal before and after means nothing changed. */
    static List<String> allRows() {
        return jdbc().sql("""
                SELECT row_to_json(r)::text FROM inventory.resource_reservation r
                UNION ALL
                SELECT row_to_json(b)::text FROM booking.booking b
                ORDER BY 1
                """).query(String.class).list();
    }

    private static void create() {
        try (Connection migrated = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR,
                MIGRATOR);
             Connection server = DriverManager.getConnection(PostgresTestContainer.jdbcUrl("postgres"), "postgres",
                     "postgres")) {
            try (Statement s = server.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS " + NAME + " WITH (FORCE)");
                s.execute("CREATE DATABASE " + NAME + " TEMPLATE template0");
            }
            try (Connection plant = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(NAME), "postgres",
                    "postgres");
                 Statement s = plant.createStatement()) {
                for (String table : TABLES) {
                    s.execute("CREATE SCHEMA IF NOT EXISTS " + table.substring(0, table.indexOf('.')));
                    s.execute("CREATE TABLE " + table + " (" + columns(migrated, table) + ")");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("E7-32 planted-violation database failed", e);
        }
    }

    private static String columns(Connection migrated, String table) throws SQLException {
        try (PreparedStatement statement = migrated.prepareStatement(COLUMNS)) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private static OffsetDateTime utc(String instant) {
        return OffsetDateTime.ofInstant(Instant.parse(instant), ZoneOffset.UTC);
    }
}
