package ps.e7gz.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.operations.api.EmergencyBookings;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.support.boundary.TestCommands;

/**
 * E7-83: what the timer pause tests share - genuine bookings made by their own commands ({@link BookingFlows}), real
 * Emergencies declared and ended through {@code /api/v1/venue/emergencies}, the {@code TIMER_PAUSE_CHECK} job fired
 * through the real deadline worker ({@link DueJobs}), and the few stored instants a test must move because the database
 * clock cannot be (each said where it is done).
 */
@AutoConfigureMockMvc
abstract class TimerPauseTestBase extends IntegrationTest {

    static final String EMERGENCIES = "/api/v1/venue/emergencies";
    static final JdbcClient OWNER = JdbcClient.create(new SingleConnectionDataSource(
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR, true));

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ApplicationContext context;

    @Autowired
    CommandRunner runner;

    @Autowired
    TimerPauses timers;

    @Autowired
    EmergencyBookings bookings;

    BookingFlows flows;
    DueJobs checks;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void flows() {
        correlation = CorrelationId.open(CorrelationId.generate());
        flows = BookingFlows.of(context);
        checks = DueJobs.of(context, new TimerPauseCheckHandler(runner, timers, bookings));
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ---------------------------------------------------------------------------------- emergencies

    /** A venue Emergency Closure from now to {@code expectedEnd}, outside the booking's own interval. */
    UUID declareVenue(BookingFlows.Booking at, Instant expectedEnd) throws Exception {
        return id(mvc.perform(write(post(EMERGENCIES + "/venue-closure"), at, "{\"reason\":\"Power cut\","
                + "\"expectedEndAt\":\"" + expectedEnd + "\"}")).andExpect(status().isCreated()));
    }

    /** A resource Emergency Outage of the booking's own room. */
    UUID declareResource(BookingFlows.Booking at, Instant expectedEnd) throws Exception {
        return id(mvc.perform(write(post(EMERGENCIES + "/resource-outage"), at, "{\"resourceId\":\""
                + at.resourceId() + "\",\"reason\":\"Console overheating\",\"expectedReturnAt\":\"" + expectedEnd
                + "\"}")).andExpect(status().isCreated()));
    }

    void end(BookingFlows.Booking at, UUID emergencyId) throws Exception {
        mvc.perform(write(post(EMERGENCIES + "/" + emergencyId + "/end"), at, "{}")).andExpect(status().isOk());
    }

    /** Twenty minutes from now: inside the default session and ending well before the booking's own start. */
    static Instant soon() {
        return dbNow().plus(Duration.ofMinutes(20));
    }

    // ---------------------------------------------------------------------------------- the check job

    /** Every TIMER_PAUSE_CHECK of the booking still waiting, made due now, fired; true when one settled. */
    boolean fireChecks(BookingFlows.Booking booking) {
        List<String> keys = OWNER.sql("""
                        SELECT idempotency_key FROM technical.scheduled_job
                         WHERE type = 'TIMER_PAUSE_CHECK' AND subject_id = :id AND status = 'PENDING'
                         ORDER BY due_at""")
                .param("id", booking.bookingId()).query(String.class).list();
        assertThat(keys).as("a check is waiting").isNotEmpty();
        String key = keys.getFirst();
        runner.run(TestCommands.under(flows.scope(booking), command -> {
            checks.makeDue(command, key, command.commandNow());
            return null;
        }));
        return checks.fire(key);
    }

    static List<Instant> waitingChecks(UUID bookingId) {
        return OWNER.sql("""
                        SELECT due_at FROM technical.scheduled_job
                         WHERE type = 'TIMER_PAUSE_CHECK' AND subject_id = :id AND status = 'PENDING'
                         ORDER BY due_at""")
                .param("id", bookingId).query(OffsetDateTime.class).list().stream()
                .map(OffsetDateTime::toInstant).toList();
    }

    // ---------------------------------------------------------------------------------- stored state

    /** TEST-ONLY, triggers off: a write no command can make (moving an instant the database clock cannot). */
    static void withoutTriggers(Consumer<JdbcClient> writes) {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(
                PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            jdbc.sql("SET session_replication_role = replica").update();
            writes.accept(jdbc);
            jdbc.sql("SET session_replication_role = origin").update();
        } finally {
            owner.destroy();
        }
    }

    /** {@code schema.table.column} of the booking row ({@code id}) set to {@code at}, triggers off. */
    static void set(String column, UUID id, Instant at) {
        int dot = column.lastIndexOf('.');
        withoutTriggers(jdbc -> assertThat(jdbc.sql("UPDATE " + column.substring(0, dot) + " SET "
                        + column.substring(dot + 1) + " = :at WHERE id = :id")
                .param("at", utc(at)).param("id", id).update()).isOne());
    }

    /**
     * TEST-ONLY, triggers off: the venue's first session now ends at {@code firstEnd} and a second, labelled the next
     * operating day, starts at {@code secondStart} and ends where the first one ended - created on the first call, moved
     * on later ones. The materialized sessions are what every session rule reads (ADR-009).
     */
    static void splitSession(UUID venueId, Instant firstEnd, Instant secondStart) {
        withoutTriggers(jdbc -> {
            long sessions = jdbc.sql("SELECT count(*) FROM venue.operating_session WHERE venue_id = :venue")
                    .param("venue", venueId).query(Long.class).single();
            if (sessions == 1) {
                OffsetDateTime originalEnd = jdbc.sql("""
                                SELECT upper(session_period) FROM venue.operating_session WHERE venue_id = :venue""")
                        .param("venue", venueId).query(OffsetDateTime.class).single();
                jdbc.sql("""
                                UPDATE venue.operating_session
                                   SET session_period = tstzrange(lower(session_period), CAST(:end AS timestamptz), '[)'),
                                       local_end = CAST(:end AS timestamptz) AT TIME ZONE timezone
                                 WHERE venue_id = :venue""")
                        .param("end", utc(firstEnd)).param("venue", venueId).update();
                jdbc.sql("""
                                INSERT INTO venue.operating_session (id, venue_id, opening_template_id, operating_day,
                                                                     local_start, local_end, session_period, timezone,
                                                                     generated_from_version, generated_at)
                                SELECT gen_random_uuid(), venue_id, opening_template_id, operating_day + 1,
                                       CAST(:start AS timestamptz) AT TIME ZONE timezone,
                                       CAST(:end AS timestamptz) AT TIME ZONE timezone,
                                       tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'),
                                       timezone, generated_from_version, generated_at
                                  FROM venue.operating_session WHERE venue_id = :venue""")
                        .param("start", utc(secondStart)).param("end", originalEnd).param("venue", venueId)
                        .update();
                return;
            }
            jdbc.sql("""
                            UPDATE venue.operating_session
                               SET session_period = tstzrange(lower(session_period), CAST(:end AS timestamptz), '[)'),
                                   local_end = CAST(:end AS timestamptz) AT TIME ZONE timezone
                             WHERE venue_id = :venue
                               AND operating_day = (SELECT min(operating_day) FROM venue.operating_session
                                                     WHERE venue_id = :venue)""")
                    .param("end", utc(firstEnd)).param("venue", venueId).update();
            jdbc.sql("""
                            UPDATE venue.operating_session
                               SET session_period = tstzrange(CAST(:start AS timestamptz), upper(session_period), '[)'),
                                   local_start = CAST(:start AS timestamptz) AT TIME ZONE timezone
                             WHERE venue_id = :venue
                               AND operating_day = (SELECT max(operating_day) FROM venue.operating_session
                                                     WHERE venue_id = :venue)""")
                    .param("start", utc(secondStart)).param("venue", venueId).update();
        });
    }

    static void setLateConfirmDeadline(UUID bookingId, Instant at) {
        withoutTriggers(jdbc -> assertThat(jdbc.sql("""
                        UPDATE booking.late_confirmation SET expires_at = :at
                         WHERE booking_id = :id AND closed_at IS NULL""")
                .param("at", utc(at)).param("id", bookingId).update()).isOne());
    }

    static Instant instant(String sql, UUID id) {
        OffsetDateTime value = OWNER.sql(sql).param("id", id).query(OffsetDateTime.class).optional().orElse(null);
        return value == null ? null : value.toInstant();
    }

    static Instant responsePausedSince(UUID bookingId) {
        return instant("SELECT response_pause_started_at FROM booking.booking WHERE id = :id", bookingId);
    }

    static Instant lateConfirmPausedSince(UUID bookingId) {
        return instant("SELECT late_confirm_pause_started_at FROM booking.booking WHERE id = :id", bookingId);
    }

    static Instant responseDeadline(UUID bookingId) {
        return instant("SELECT venue_response_expires_at FROM booking.booking WHERE id = :id", bookingId);
    }

    static Instant lateConfirmDeadline(UUID bookingId) {
        return instant("SELECT expires_at FROM booking.late_confirmation WHERE booking_id = :id "
                + "ORDER BY attempt_no DESC LIMIT 1", bookingId);
    }

    static Duration responseCounter(UUID bookingId) {
        return seconds("SELECT EXTRACT(EPOCH FROM cumulative_emergency_pause_duration) FROM booking.booking "
                + "WHERE id = :id", bookingId);
    }

    static Duration lateConfirmCounter(UUID bookingId) {
        return seconds("SELECT EXTRACT(EPOCH FROM cumulative_late_confirm_emergency_pause_duration) "
                + "FROM booking.booking WHERE id = :id", bookingId);
    }

    static Duration seconds(String sql, UUID id) {
        java.math.BigDecimal value = OWNER.sql(sql).param("id", id).query(java.math.BigDecimal.class).optional()
                .orElse(null);
        return value == null ? null : Duration.ofNanos(value.movePointRight(9).longValue());
    }

    /** One pause history row, as {@code sequence/cause/open|closed/accumulated seconds}. */
    record PauseRow(int sequence, String kind, String cause, Instant startedAt, Instant endedAt,
                    Duration accumulated) {
    }

    static List<PauseRow> pauseRows(UUID bookingId) {
        return OWNER.sql("""
                        SELECT sequence, pause_kind, pause_cause, pause_started_at, pause_ended_at,
                               EXTRACT(EPOCH FROM accumulated_duration) AS accumulated
                          FROM operations.booking_timer_pause_history
                         WHERE booking_id = :id ORDER BY pause_kind, sequence""")
                .param("id", bookingId)
                .query((rs, n) -> {
                    OffsetDateTime ended = rs.getObject("pause_ended_at", OffsetDateTime.class);
                    java.math.BigDecimal accumulated = rs.getBigDecimal("accumulated");
                    return new PauseRow(rs.getInt("sequence"), rs.getString("pause_kind"), rs.getString("pause_cause"),
                            rs.getObject("pause_started_at", OffsetDateTime.class).toInstant(),
                            ended == null ? null : ended.toInstant(),
                            accumulated == null ? null : Duration.ofNanos(accumulated.movePointRight(9).longValue()));
                })
                .list();
    }

    static String statusOf(UUID bookingId) {
        return OWNER.sql("SELECT status || '/' || COALESCE(terminal_reason, '-') FROM booking.booking WHERE id = :id")
                .param("id", bookingId).query(String.class).single();
    }

    static long count(String sql) {
        return OWNER.sql(sql).query(Long.class).single();
    }

    static long events(String type, UUID bookingId) {
        return OWNER.sql("""
                        SELECT count(*) FROM technical.outbox_event
                         WHERE event_type = :type AND payload->>'bookingId' = :booking""")
                .param("type", type).param("booking", bookingId.toString()).query(Long.class).single();
    }

    static Instant dbNow() {
        return Deadlines.databaseNow();
    }

    static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------------------------- HTTP

    MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, BookingFlows.Booking at, String body) {
        return request.header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.VENUE,
                        at.venueAccountId()))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    UUID id(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return UUID.fromString(json.readTree(result.andReturn().getResponse().getContentAsString())
                .path("data").path("id").asText());
    }
}
