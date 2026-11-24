package ps.e7gz.inventory.sweep.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.inventory.sweep.api.InvariantCheck;
import ps.e7gz.inventory.sweep.api.InvariantSeverity;
import ps.e7gz.inventory.sweep.api.InvariantViolation;
import ps.e7gz.inventory.sweep.api.SweepReport;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-79, FR-OPS-21 (PO decision 22 Nov 2026, G1: rooms and venues): the sellable-gap check against planted states. The
 * database refuses a gap when a command writes it, so the planted rows live in a scratch database in the same
 * container whose {@code operations.emergency}, {@code resource.resource_selling_block} and
 * {@code venue.venue_selling_block} have exactly the migrated tables' columns and no constraints or triggers - as E7-32's
 * {@link PlantedViolationDatabase}. The production SQL runs unchanged; the application database is never touched.
 */
class SellableGapPlantedTest extends IntegrationTest {

    static final String NAME = "e7gz_e779_gap_plant";
    static final List<String> TABLES = List.of("operations.emergency", "resource.resource_selling_block",
            "venue.venue_selling_block");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2031-06-01T00:00:00Z"), ZoneOffset.UTC);

    private static DataSource plant;

    @Autowired
    List<InvariantCheck> applicationChecks;

    private final SellableGapCheck check = new SellableGapCheck();

    @BeforeEach
    void cleanPlant() {
        jdbc().sql("TRUNCATE " + String.join(", ", TABLES)).update();
    }

    @Test
    void theApplicationWiresTheCheckAsSev1() {
        assertThat(applicationChecks).filteredOn(c -> c.invariant().equals(SellableGapCheck.INVARIANT))
                .singleElement().satisfies(c -> assertThat(c.severity()).isEqualTo(InvariantSeverity.SEV_1));
    }

    @Test
    void aRoomEmergencyWithoutAnActiveRoomBlockIsAGap() {
        UUID venue = UUID.randomUUID();
        UUID room = UUID.randomUUID();
        UUID emergency = emergency("RESOURCE", venue, room, "ACTIVE");

        assertThat(gaps()).singleElement().satisfies(v -> {
            assertThat(v.subjectType()).isEqualTo("EMERGENCY");
            assertThat(v.subjectId()).isEqualTo(emergency);
            assertThat(v.severity()).isEqualTo(InvariantSeverity.SEV_1);
            assertThat(v.facts()).containsEntry("scope", "RESOURCE").containsEntry("resourceId", room.toString())
                    .containsEntry("venueId", venue.toString()).containsEntry("status", "ACTIVE")
                    .containsEntry("declaredAt", "2031-05-31T22:00:00Z");
        });
    }

    @Test
    void aVenueEmergencyWithoutAnActiveVenueBlockIsAGap() {
        UUID venue = UUID.randomUUID();
        UUID emergency = emergency("VENUE", venue, null, "ACTIVE");

        assertThat(gaps()).singleElement().satisfies(v -> {
            assertThat(v.subjectId()).isEqualTo(emergency);
            assertThat(v.facts()).containsEntry("scope", "VENUE").containsEntry("venueId", venue.toString())
                    .doesNotContainKey("resourceId");
        });
    }

    /** FR-OPS-18: past its expected end the Emergency still holds its block until staff resolve it. */
    @Test
    void anEmergencyAwaitingConfirmationStillNeedsItsBlock() {
        UUID emergency = emergency("RESOURCE", UUID.randomUUID(), UUID.randomUUID(), "AWAITING_CONFIRMATION");

        assertThat(gaps()).extracting(InvariantViolation::subjectId).containsExactly(emergency);
    }

    @Test
    void anEndedBlockLeavesTheGapOpen() {
        UUID room = UUID.randomUUID();
        UUID emergency = emergency("RESOURCE", UUID.randomUUID(), room, "ACTIVE");
        roomBlock(room, emergency, false);
        UUID venue = UUID.randomUUID();
        UUID venueEmergency = emergency("VENUE", venue, null, "ACTIVE");
        venueBlock(venue, venueEmergency, false);

        assertThat(gaps()).extracting(InvariantViolation::subjectId)
                .containsExactlyInAnyOrder(emergency, venueEmergency);
    }

    @Test
    void anActiveBlockOnTheSubjectClosesTheGap() {
        UUID room = UUID.randomUUID();
        roomBlock(room, emergency("RESOURCE", UUID.randomUUID(), room, "ACTIVE"), true);
        UUID venue = UUID.randomUUID();
        venueBlock(venue, emergency("VENUE", venue, null, "AWAITING_CONFIRMATION"), true);
        // arch 11: any active block on the subject keeps it unsellable, whichever Emergency it came from
        UUID shared = UUID.randomUUID();
        UUID first = emergency("RESOURCE", UUID.randomUUID(), shared, "ACTIVE");
        emergency("RESOURCE", UUID.randomUUID(), shared, "ACTIVE");
        roomBlock(shared, first, true);

        assertThat(gaps()).isEmpty();
    }

    /** A block on another room, or a room block for a venue Emergency, does not cover the subject. */
    @Test
    void aBlockOnAnotherSubjectDoesNotCount() {
        UUID venue = UUID.randomUUID();
        UUID room = UUID.randomUUID();
        UUID roomEmergency = emergency("RESOURCE", venue, room, "ACTIVE");
        roomBlock(UUID.randomUUID(), roomEmergency, true);
        UUID venueEmergency = emergency("VENUE", venue, null, "ACTIVE");
        roomBlock(UUID.randomUUID(), venueEmergency, true);           // a room block never covers the whole venue

        assertThat(gaps()).extracting(InvariantViolation::subjectId)
                .containsExactlyInAnyOrder(roomEmergency, venueEmergency);
    }

    /** A resolved Emergency needs no block: END EMERGENCY is the staff assertion that the room is usable. */
    @Test
    void endedAndConvertedEmergenciesAreNotGaps() {
        emergency("RESOURCE", UUID.randomUUID(), UUID.randomUUID(), "ENDED");
        emergency("VENUE", UUID.randomUUID(), null, "CONVERTED");

        assertThat(gaps()).isEmpty();
    }

    /** Through the real sweep: reported once, and the snapshot is read-only - nothing planted changes. */
    @Test
    void theSweepReportsTheGapAndRepairsNothing() {
        UUID emergency = emergency("RESOURCE", UUID.randomUUID(), UUID.randomUUID(), "ACTIVE");
        List<String> before = rows();
        List<InvariantViolation> received = new ArrayList<>();

        SweepReport report = new ReadOnlyInvariantSweep(dataSource(), List.of(check), List.of(received::add), CLOCK)
                .run();

        assertThat(report.invariants()).containsExactly(SellableGapCheck.INVARIANT);
        assertThat(received).extracting(InvariantViolation::subjectId).containsExactly(emergency);
        assertThat(rows()).isEqualTo(before);
    }

    // ------------------------------------------------------------------------------------------------ plant

    private List<InvariantViolation> gaps() {
        return check.detect(jdbc());
    }

    private static UUID emergency(String scope, UUID venue, UUID room, String status) {
        UUID id = UUID.randomUUID();
        OffsetDateTime declared = OffsetDateTime.parse("2031-05-31T22:00:00Z");
        boolean resolved = status.equals("ENDED") || status.equals("CONVERTED");
        jdbc().sql("""
                        INSERT INTO operations.emergency (id, scope, venue_id, resource_id, status, declared_at,
                                                          expected_end_at, declared_period, reason, converted_to,
                                                          ended_at)
                        VALUES (:id, :scope, :venue, :room, :status, :declared, :expected,
                                tstzrange(:declared, :expected, '[)'), 'planted', :converted, :ended)""")
                .param("id", id).param("scope", scope).param("venue", venue).param("room", room)
                .param("status", status).param("declared", declared).param("expected", declared.plusHours(2))
                .param("converted", status.equals("CONVERTED") ? "CLOSURE" : null)
                .param("ended", resolved ? declared.plusHours(1) : null)
                .update();
        return id;
    }

    private static void roomBlock(UUID room, UUID emergency, boolean active) {
        block("resource.resource_selling_block", "resource_id", room, emergency, active);
    }

    private static void venueBlock(UUID venue, UUID emergency, boolean active) {
        block("venue.venue_selling_block", "venue_id", venue, emergency, active);
    }

    private static void block(String table, String subjectColumn, UUID subject, UUID emergency, boolean active) {
        OffsetDateTime started = OffsetDateTime.parse("2031-05-31T22:00:00Z");
        jdbc().sql("INSERT INTO " + table + " (id, " + subjectColumn + ", cause, emergency_id, active, started_at, "
                        + "ended_at) VALUES (:id, :subject, 'EMERGENCY', :emergency, :active, :started, :ended)")
                .param("id", UUID.randomUUID()).param("subject", subject).param("emergency", emergency)
                .param("active", active).param("started", started)
                .param("ended", active ? null : started.plusMinutes(30)).update();
    }

    private static List<String> rows() {
        return jdbc().sql("""
                SELECT row_to_json(e)::text FROM operations.emergency e
                UNION ALL SELECT row_to_json(r)::text FROM resource.resource_selling_block r
                UNION ALL SELECT row_to_json(v)::text FROM venue.venue_selling_block v
                ORDER BY 1""").query(String.class).list();
    }

    private static JdbcClient jdbc() {
        return JdbcClient.create(dataSource());
    }

    /** Created once per test JVM from the migrated schema of that moment, as {@link PlantedViolationDatabase}. */
    static synchronized DataSource dataSource() {
        if (plant == null) {
            create();
            plant = new SimpleDriverDataSource(new org.postgresql.Driver(), PostgresTestContainer.jdbcUrl(NAME),
                    "postgres", "postgres");
        }
        return plant;
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
            try (Connection target = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(NAME), "postgres",
                    "postgres");
                 Statement s = target.createStatement()) {
                for (String table : TABLES) {
                    s.execute("CREATE SCHEMA IF NOT EXISTS " + table.substring(0, table.indexOf('.')));
                    s.execute("CREATE TABLE " + table + " (" + columns(migrated, table) + ")");
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("E7-79 planted sellable-gap database failed", e);
        }
    }

    private static String columns(Connection migrated, String table) throws SQLException {
        try (PreparedStatement statement = migrated.prepareStatement("""
                SELECT string_agg(format('%I %s', a.attname,
                           format_type(CASE WHEN t.typtype = 'd' THEN t.typbasetype ELSE a.atttypid END,
                                       CASE WHEN t.typtype = 'd' THEN t.typtypmod ELSE a.atttypmod END)),
                           ', ' ORDER BY a.attnum)
                  FROM pg_attribute a
                  JOIN pg_type t ON t.oid = a.atttypid
                 WHERE a.attrelid = CAST(? AS regclass) AND a.attnum > 0 AND NOT a.attisdropped""")) {
            statement.setString(1, table);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
