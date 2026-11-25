package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.messaging.internal.OutboxDrain;
import ps.e7gz.notification.api.NotifiedMaintenance;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.boundary.TestCommands;

/**
 * E7-80 (PO decision 25 Nov 2026, Q4): "Maintenance Starting Soon" 30 minutes before the start, from the real E7-81
 * Maintenance commands through the real outbox (every event delivered twice), and the reminder fired by hand.
 */
@AutoConfigureMockMvc
class MaintenanceStartingSoonIntegrationTest extends IntegrationTest {

    private static final String MAINTENANCE = "/api/v1/venue/maintenance";
    private static final JdbcClient OWNER = JdbcClient.create(new SingleConnectionDataSource(
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR, true));

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ApplicationContext context;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    @Autowired
    CommandRunner runner;

    @Autowired
    NotificationService notifications;

    @Autowired
    NotifiedMaintenance maintenance;

    @Autowired
    ps.e7gz.scheduling.api.ScheduledJobs jobs;

    JdbcClient db;
    BookingFlows.Booking booking;
    String token;
    Instant t;
    OperationalNotificationConsumer consumer;
    MaintenanceStartingSoonHandler handler;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        correlation = CorrelationId.open(CorrelationId.generate());
        db = JdbcClient.create(appDataSource);
        BookingFlows flows = BookingFlows.of(context);
        booking = flows.pending();
        flows.ownerPin(booking.venueAccountId());
        token = "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, booking.venueAccountId());
        t = booking.startAt().plus(Duration.ofDays(3)).truncatedTo(ChronoUnit.HOURS);
        consumer = new OperationalNotificationConsumer(runner, notifications, jobs, maintenance);
        handler = new MaintenanceStartingSoonHandler(maintenance, notifications);
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    @Test
    void reminderScheduledThirtyMinutesBeforeOnce() throws Exception {
        UUID id = create(t, t.plus(Duration.ofHours(2)));

        drain();

        assertThat(jobs(id)).containsExactly(MaintenanceStartingSoonHandler.key(id, t) + " PENDING "
                + t.minus(Duration.ofMinutes(30)));
    }

    @Test
    void editMovesReminderAndTheOldJobSupersedesItself() throws Exception {
        UUID id = create(t, t.plus(Duration.ofHours(2)));
        drain();
        Instant later = t.plus(Duration.ofHours(1));
        mvc.perform(json(patch(MAINTENANCE + "/" + id), "{\"startAt\":\"" + later + "\"}"))
                .andExpect(status().isOk());

        drain();

        assertThat(jobs(id)).contains(MaintenanceStartingSoonHandler.key(id, later) + " PENDING "
                + later.minus(Duration.ofMinutes(30)));
        assertThat(fire(id, t)).isInstanceOf(JobOutcome.Superseded.class);
    }

    @Test
    void deleteWithdrawsReminder() throws Exception {
        UUID id = create(t, t.plus(Duration.ofHours(2)));
        drain();
        mvc.perform(delete(MAINTENANCE + "/" + id).header("Authorization", token)).andExpect(status().isNoContent());

        assertThat(fire(id, t)).isInstanceOf(JobOutcome.Superseded.class);
        assertThat(count()).isZero();
    }

    @Test
    void createdLessThanThirtyMinutesBeforeGetsNone() throws Exception {
        Instant soon = dbNow().plus(Duration.ofMinutes(12)).truncatedTo(ChronoUnit.MINUTES);
        UUID id = create(soon, soon.plus(Duration.ofMinutes(5)));

        drain();

        assertThat(jobs(id)).isEmpty();
    }

    @Test
    void firingAtTheDueTimeTellsTheVenueOnce() {
        Instant soon = dbNow().plus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.MILLIS);
        UUID id = plantWindow(soon);

        assertThat(fire(id, soon)).isInstanceOf(JobOutcome.Done.class);
        assertThat(fire(id, soon)).as("a retried job").isInstanceOf(JobOutcome.Done.class);

        assertThat(count()).isOne();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    void drain() {
        OutboxDrain.drain(appDataSource, json, OperationalCancellationNotificationsIntegrationTest.twice(consumer));
    }

    UUID create(Instant start, Instant end) throws Exception {
        String body = mvc.perform(json(post(MAINTENANCE), "{\"resourceId\":\"" + booking.resourceId()
                        + "\",\"startAt\":\"" + start + "\",\"endAt\":\"" + end + "\",\"reason\":\"Controller\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("data").path("id").asText());
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    JobOutcome fire(UUID id, Instant start) {
        String key = MaintenanceStartingSoonHandler.key(id, start);
        ScheduledJob job = new ScheduledJob(UUID.randomUUID(), MaintenanceStartingSoonHandler.TYPE,
                MaintenanceStartingSoonHandler.SUBJECT, id, start.minus(MaintenanceStartingSoonHandler.LEAD), 0, key,
                UUID.randomUUID());
        return runner.run(TestCommands.under(CommandScope.shared(LockSet.empty()), ctx -> handler.fire(ctx, job)));
    }

    List<String> jobs(UUID id) {
        return OWNER.sql("""
                        SELECT idempotency_key || ' ' || status || ' ' || to_char(due_at AT TIME ZONE 'UTC',
                               'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                          FROM technical.scheduled_job WHERE type = :type AND subject_id = :id ORDER BY due_at""")
                .param("type", MaintenanceStartingSoonHandler.TYPE).param("id", id).query(String.class).list();
    }

    long count() {
        return db.sql("SELECT count(*) FROM notification.notification WHERE recipient_id = :venue AND code = :code")
                .param("venue", booking.venueId()).param("code", MaintenanceStartingSoonHandler.CODE)
                .query(Long.class).single();
    }

    /** A window starting at {@code start}, planted behind the guards (a real one cannot start this close). */
    UUID plantWindow(Instant start) {
        UUID id = UUID.randomUUID();
        SingleConnectionDataSource owner = new SingleConnectionDataSource(PostgresTestContainer.jdbcUrl(DATABASE),
                MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            jdbc.sql("SET session_replication_role = replica").update();
            jdbc.sql("INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)"
                            + " VALUES (:id, :room, tstzrange(:start, :start + interval '1 hour', '[)'), 'Fan', false)")
                    .param("id", id).param("room", booking.resourceId())
                    .param("start", OffsetDateTime.ofInstant(start, java.time.ZoneOffset.UTC)).update();
            jdbc.sql("SET session_replication_role = origin").update();
        } finally {
            owner.destroy();
        }
        return id;
    }

    static Instant dbNow() {
        return OWNER.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }
}
