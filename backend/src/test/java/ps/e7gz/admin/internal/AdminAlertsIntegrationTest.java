package ps.e7gz.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.fixtures.Fixtures.anAdmin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.admin.api.AdminAlertKind;
import ps.e7gz.admin.api.AdminAlertSeverity;
import ps.e7gz.admin.api.AdminAlerts;
import ps.e7gz.admin.api.NewAdminAlert;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.TestCommands;

/**
 * E7-56: alerts go through {@code admin.raise_alert} (v1.1.36) from either runtime role, the table rules still hold,
 * and an alert of a rolled-back command survives the rollback exactly once.
 */
class AdminAlertsIntegrationTest extends IntegrationTest {

    @Autowired
    AdminAlerts alerts;

    @Autowired
    CommandRunner runner;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    JdbcClient db;
    CorrelationId.Scope correlation;
    UUID correlationId;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        correlationId = CorrelationId.generate();
        correlation = CorrelationId.open(correlationId);
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    /** Done when (E7-56): SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR produces an alert with the full payload. */
    @Test
    void resourceStateIntegrityErrorCarriesTheFullDiagnosticPayload() throws Exception {
        UUID booking = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-10-15T17:00:00Z");
        Instant detectedAt = Instant.parse("2026-10-15T17:00:02Z");

        UUID id = runner.run(TestCommands.under(CommandScope.shared(LockSet.empty()), context ->
                alerts.raise(context, NewAdminAlert.resourceStateIntegrityError(booking, resource, "DISABLED", startAt,
                        detectedAt, "MAINTENANCE", correlationId))));

        Row row = row(id);
        assertThat(row.kind()).isEqualTo("SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR");
        assertThat(row.severity()).isEqualTo("SEV_1");
        assertThat(row.subjectType()).isEqualTo("BOOKING");
        assertThat(row.subjectId()).isEqualTo(booking);
        assertThat(row.correlationId()).isEqualTo(correlationId);
        assertThat(row.acknowledged()).isFalse();
        JsonNode payload = json.readTree(row.payload());
        assertThat(payload.path("bookingId").asText()).isEqualTo(booking.toString());
        assertThat(payload.path("resourceId").asText()).isEqualTo(resource.toString());
        assertThat(payload.path("unexpectedState").asText()).isEqualTo("DISABLED");
        assertThat(payload.path("startAt").asText()).isEqualTo("2026-10-15T17:00:00Z");
        assertThat(payload.path("detectedAt").asText()).isEqualTo("2026-10-15T17:00:02Z");
        assertThat(payload.path("conflictingSource").asText()).isEqualTo("MAINTENANCE");
        assertThat(payload.path("correlationId").asText()).isEqualTo(correlationId.toString());
    }

    @Test
    void anUnknownConflictingSourceIsLeftOut() throws Exception {
        UUID id = runner.run(TestCommands.under(CommandScope.shared(LockSet.empty()), context ->
                alerts.raise(context, NewAdminAlert.resourceStateIntegrityError(UUID.randomUUID(), UUID.randomUUID(),
                        "ARCHIVED", Instant.parse("2026-10-15T17:00:00Z"), Instant.parse("2026-10-15T17:00:00Z"),
                        null, correlationId))));

        assertThat(json.readTree(row(id).payload()).has("conflictingSource")).isFalse();
    }

    @Test
    void theAdminRuntimeRaisesThroughTheSameProducer() {
        UUID admin = anAdmin().create().id();

        UUID id = runner.run(TestCommands.under(CommandScope.admin(admin, correlationId, LockSet.empty()), context ->
                alerts.raise(context, new NewAdminAlert(AdminAlertKind.INVARIANT_VIOLATION, AdminAlertSeverity.SEV_3,
                        "RESOURCE", UUID.randomUUID(), Map.of("invariant", "TEST"), correlationId))));

        assertThat(row(id).severity()).isEqualTo("SEV_3");
    }

    /** The producer is the only way in: e7gz_app_runtime still has no INSERT on the table (v1.1.29). */
    @Test
    void theAppRuntimeCannotInsertDirectly() {
        assertThatThrownBy(() -> db.sql("""
                        INSERT INTO admin.admin_alert(id, kind, severity, subject_type, subject_id, correlation_id)
                        VALUES (gen_random_uuid(), 'X', 'SEV_1', 'BOOKING', gen_random_uuid(), gen_random_uuid())""")
                .update())
                .rootCause().hasMessageContaining("permission denied for table admin_alert");
    }

    /** admin.guard_admin_alert still refuses a credential key in the payload. */
    @Test
    void aCredentialKeyInThePayloadIsRefused() {
        NewAdminAlert secret = new NewAdminAlert(AdminAlertKind.REFRESH_TOKEN_REUSE, AdminAlertSeverity.SEV_2, "USER",
                UUID.randomUUID(), Map.of("refreshToken", "x"), correlationId);

        assertThatThrownBy(() -> runner.run(TestCommands.under(CommandScope.shared(LockSet.empty()),
                context -> alerts.raise(context, secret))))
                .hasMessageContaining("forbidden credential/secret field");
    }

    /** Severity is open text: the existing RISK abuse signal stays valid next to SEV_1..SEV_3. */
    @Test
    void theRiskSeverityStaysValid() {
        UUID id = db.sql("SELECT admin.raise_alert('TOURNAMENT_CANCELLATION_ABUSE', 'RISK', 'TOURNAMENT', "
                        + "gen_random_uuid(), NULL, gen_random_uuid())")
                .query(UUID.class).single();

        assertThat(row(id).severity()).isEqualTo("RISK");
    }

    /**
     * E7-47 D11, PO decision 10 Oct 2026: the failed command is rolled back, yet its alert commits in a transaction of
     * its own after the rollback.
     */
    @Test
    void anAlertRaisedAfterRollbackSurvivesTheRollback() {
        UUID booking = UUID.randomUUID();

        assertThatThrownBy(() -> runner.run(TestCommands.under(CommandScope.shared(LockSet.empty()), context -> {
            alerts.raiseAfterRollback(NewAdminAlert.bookingIntegrityFailure(booking, "no reservation", correlationId));
            throw new IllegalStateException("integrity failure");
        }))).hasMessage("integrity failure");

        List<Row> rows = rowsAbout(booking);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().kind()).isEqualTo("BOOKING_INTEGRITY_FAILURE");
        assertThat(rows.getFirst().severity()).isEqualTo("SEV_1");
        assertThat(rows.getFirst().correlationId()).isEqualTo(correlationId);
    }

    @Test
    void aCommandThatCommitsRaisesNothingAfterRollback() {
        UUID booking = UUID.randomUUID();

        runner.run(TestCommands.under(CommandScope.shared(LockSet.empty()), context -> {
            alerts.raiseAfterRollback(NewAdminAlert.bookingIntegrityFailure(booking, "no reservation", correlationId));
            return null;
        }));

        assertThat(rowsAbout(booking)).isEmpty();
    }

    /** The bounded retries of a failing job find the open alert and raise no second one. */
    @Test
    void anIdenticalOpenAlertIsNotRaisedTwice() {
        UUID booking = UUID.randomUUID();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> runner.run(TestCommands.under(CommandScope.shared(LockSet.empty()), context -> {
                alerts.raiseAfterRollback(NewAdminAlert.bookingIntegrityFailure(booking, "no reservation",
                        correlationId));
                throw new IllegalStateException("integrity failure");
            }))).isInstanceOf(IllegalStateException.class);
        }
        alerts.raiseAfterRollback(NewAdminAlert.bookingIntegrityFailure(booking, "another detail", correlationId));

        assertThat(rowsAbout(booking)).extracting(Row::payload).hasSize(2);
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    record Row(String kind, String severity, String subjectType, UUID subjectId, String payload, UUID correlationId,
               boolean acknowledged) {
    }

    private Row row(UUID id) {
        return db.sql(SELECT + " WHERE id = :id").param("id", id).query(AdminAlertsIntegrationTest::map).single();
    }

    private List<Row> rowsAbout(UUID subjectId) {
        return db.sql(SELECT + " WHERE subject_id = :id ORDER BY created_at").param("id", subjectId)
                .query(AdminAlertsIntegrationTest::map).list();
    }

    private static final String SELECT = """
            SELECT kind, severity, subject_type, subject_id, payload::text AS payload, correlation_id,
                   acknowledged_at IS NOT NULL AS acknowledged
              FROM admin.admin_alert""";

    private static Row map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Row(rs.getString("kind"), rs.getString("severity"), rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class), rs.getString("payload"),
                rs.getObject("correlation_id", UUID.class), rs.getBoolean("acknowledged"));
    }
}
