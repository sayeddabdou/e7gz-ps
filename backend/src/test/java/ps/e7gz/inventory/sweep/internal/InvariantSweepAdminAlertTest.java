package ps.e7gz.inventory.sweep.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.inventory.sweep.internal.PlantedViolationDatabase.ordered;
import static ps.e7gz.inventory.sweep.internal.PlantedViolationDatabase.reservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.ReservationType;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.inventory.sweep.api.InvariantSeverity;
import ps.e7gz.inventory.sweep.api.InvariantViolation;
import ps.e7gz.inventory.sweep.api.InvariantViolationReporter;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-56 (PO decision 4 Oct 2026, E7-32 review): the Platform Admin reporter the application wires turns each sweep
 * violation into exactly one INVARIANT_VIOLATION SEV_1 alert with the invariant code, every fact and the sweep's
 * correlation ID - and changes nothing else. The sweep reads the planted scratch database; alerts go to the real one.
 */
class InvariantSweepAdminAlertTest extends IntegrationTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2031-06-01T00:00:00Z"), ZoneOffset.UTC);

    /** Every reporter the application wires: the Platform Admin one (E7-56). */
    @Autowired
    List<InvariantViolationReporter> reporters;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    JdbcClient db;
    UUID sweepCorrelation;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        PlantedViolationDatabase.clear();
        db = JdbcClient.create(appDataSource);
        sweepCorrelation = CorrelationId.generate();
        correlation = CorrelationId.open(sweepCorrelation);                  // as the E7-40 worker opens it
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    private void sweep() {
        new ReadOnlyInvariantSweep(PlantedViolationDatabase.dataSource(), List.of(new ActiveReservationOverlapCheck()),
                reporters, CLOCK).run();
    }

    @Test
    void aCleanSweepRaisesNoAlert() {
        reservation(ordered(1), UUID.randomUUID(), ReservationType.BOOKING, "2031-03-01T08:00:00Z",
                "2031-03-01T09:00:00Z", true);

        sweep();

        assertThat(alertsOf(sweepCorrelation)).isEmpty();
    }

    @Test
    void aPlantedViolationRaisesExactlyOneAlertWithItsFactsAndTheSweepsCorrelation() throws Exception {
        UUID resource = UUID.randomUUID();
        reservation(ordered(1), resource, ReservationType.BOOKING, "2031-03-01T08:00:00Z", "2031-03-01T09:00:00Z",
                true);
        reservation(ordered(2), resource, ReservationType.BOOKING, "2031-03-01T08:30:00Z", "2031-03-01T09:30:00Z",
                true);
        List<String> planted = PlantedViolationDatabase.allRows();
        List<Long> before = businessRowCounts();

        sweep();

        List<Alert> alerts = alertsOf(sweepCorrelation);
        assertThat(alerts).hasSize(1);
        Alert alert = alerts.getFirst();
        assertThat(alert.kind()).isEqualTo("INVARIANT_VIOLATION");
        assertThat(alert.severity()).isEqualTo("SEV_1");
        assertThat(alert.subjectType()).isEqualTo("RESOURCE");
        assertThat(alert.subjectId()).isEqualTo(resource);
        JsonNode payload = json.readTree(alert.payload());
        assertThat(payload.path("invariant").asText()).isEqualTo(ActiveReservationOverlapCheck.INVARIANT);
        assertThat(payload.path("firstReservationId").asText()).isEqualTo(ordered(1).toString());
        assertThat(payload.path("secondReservationId").asText()).isEqualTo(ordered(2).toString());
        assertThat(payload.path("firstReservationType").asText()).isEqualTo("BOOKING");
        assertThat(payload.path("secondReservationType").asText()).isEqualTo("BOOKING");

        assertThat(PlantedViolationDatabase.allRows()).as("the sweep repairs nothing").isEqualTo(planted);
        assertThat(businessRowCounts()).as("no Inventory, Booking or Trust row").isEqualTo(before);
    }

    /** A violation still there the next night is not raised again while its alert is open. */
    @Test
    void theSameViolationIsNotRaisedAgainWhileItsAlertIsOpen() {
        UUID resource = UUID.randomUUID();
        reservation(ordered(1), resource, ReservationType.BOOKING, "2031-03-01T08:00:00Z", "2031-03-01T09:00:00Z",
                true);
        reservation(ordered(2), resource, ReservationType.TOURNAMENT_HOLD, "2031-03-01T08:30:00Z",
                "2031-03-01T09:30:00Z", true);

        sweep();
        sweep();

        assertThat(db.sql("SELECT count(*) FROM admin.admin_alert WHERE kind = 'INVARIANT_VIOLATION' "
                + "AND subject_id = :resource").param("resource", resource).query(Long.class).single()).isEqualTo(1);
    }

    /** One failing alert is logged and does not stop the others (the sweep does not catch reporter failures). */
    @Test
    void oneFailingAlertDoesNotStopTheNext() {
        UUID resource = UUID.randomUUID();
        InvariantViolationReporter admin = reporters.getFirst();

        admin.report(new InvariantViolation("TEST_SECRET_FACT", InvariantSeverity.SEV_1,
                "RESOURCE", resource, Map.of("apiKey", "x")));             // refused by the database
        admin.report(new InvariantViolation("TEST_PLAIN_FACT", InvariantSeverity.SEV_1,
                "RESOURCE", resource, Map.of("rows", "2")));

        assertThat(db.sql("SELECT payload->>'invariant' FROM admin.admin_alert WHERE subject_id = :resource")
                .param("resource", resource).query(String.class).list()).containsExactly("TEST_PLAIN_FACT");
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    record Alert(String kind, String severity, String subjectType, UUID subjectId, String payload) {
    }

    private List<Alert> alertsOf(UUID correlationId) {
        return db.sql("""
                        SELECT kind, severity, subject_type, subject_id, payload::text AS payload
                          FROM admin.admin_alert WHERE correlation_id = :correlation""")
                .param("correlation", correlationId)
                .query((rs, row) -> new Alert(rs.getString("kind"), rs.getString("severity"),
                        rs.getString("subject_type"), rs.getObject("subject_id", UUID.class),
                        rs.getString("payload")))
                .list();
    }

    private List<Long> businessRowCounts() {
        return db.sql("""
                SELECT (SELECT count(*) FROM inventory.resource_reservation)
                     + (SELECT count(*) FROM booking.booking)
                     + (SELECT count(*) FROM trust.trust_event)""").query(Long.class).list();
    }
}
