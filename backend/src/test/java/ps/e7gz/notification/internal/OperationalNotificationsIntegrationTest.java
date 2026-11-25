package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.messaging.internal.OutboxDrain;
import ps.e7gz.notification.api.NotifiedBookings;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-80 (Spec v5 section 52.5, FR-OPS-29; PO decisions 25 Nov 2026): the venue's operational notifications from the
 * real E7-82 / E7-84 commands through the real outbox, every event delivered twice as a dispatcher retry would.
 */
@AutoConfigureMockMvc
class OperationalNotificationsIntegrationTest extends IntegrationTest {

    static final String EMERGENCIES = "/api/v1/venue/emergencies";
    static final JdbcClient OWNER = JdbcClient.create(new SingleConnectionDataSource(
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
    BookingNotifier notifier;

    @Autowired
    NotifiedBookings notifiedBookings;

    @Autowired
    ScheduledJobs jobs;

    JdbcClient db;
    BookingFlows flows;
    OperationalNotificationConsumer operational;
    BookingNotificationConsumer bookings;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        flows = BookingFlows.of(context);
        operational = new OperationalNotificationConsumer(runner, notifications);
        bookings = new BookingNotificationConsumer(runner, notifier, notifiedBookings, jobs);
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    @Test
    void declarationNotifiesVenueOnce() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        declareVenue(booking, booking.startAt().plus(Duration.ofMinutes(60))).andExpect(status().isCreated());

        drain();

        assertThat(count(booking.venueId(), "VENUE_OWNER_EMERGENCY_NOTICE")).isOne();
    }

    @Test
    void ownerReviewEntryNotifiesVenueOnceAndItsCascadeHasItsOwnCode() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        UUID emergency = declareResource(booking, dbNow().plus(Duration.ofMinutes(20)));
        overThreshold(booking.resourceId());
        mvc.perform(post(EMERGENCIES + "/" + emergency + "/end").header("Authorization", venue(booking))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isOk());

        drain();

        assertThat(count(booking.venueId(), "VENUE_OWNER_REVIEW_ENTERED")).isOne();
        assertThat(count(booking.venueId(), "VENUE_OWNER_EMERGENCY_NOTICE")).as("the declaration's").isOne();
        assertThat(bookingCount(booking.customerId(), booking.bookingId(), "BOOKING_CANCELLED_OWNER_REVIEW")).isOne();
        assertThat(bookingCount(booking.customerId(), booking.bookingId(), "BOOKING_AUTO_CANCELLED")).isZero();
        assertThat(bookingCount(booking.venueId(), booking.bookingId(), "VENUE_BOOKING_AUTO_CANCELLED")).isOne();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    void drain() {
        OutboxDrain.drain(appDataSource, json, OperationalCancellationNotificationsIntegrationTest.twice(operational),
                OperationalCancellationNotificationsIntegrationTest.twice(bookings));
    }

    ResultActions declareVenue(BookingFlows.Booking at, Instant expectedEnd) throws Exception {
        return mvc.perform(post(EMERGENCIES + "/venue-closure").header("Authorization", venue(at))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Power cut\",\"expectedEndAt\":\"" + expectedEnd + "\"}"));
    }

    UUID declareResource(BookingFlows.Booking at, Instant expectedReturn) throws Exception {
        String body = mvc.perform(post(EMERGENCIES + "/resource-outage").header("Authorization", venue(at))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\":\"" + at.resourceId() + "\",\"reason\":\"Console overheating\","
                                + "\"expectedReturnAt\":\"" + expectedReturn + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("data").path("id").asText());
    }

    String venue(BookingFlows.Booking at) {
        return "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, at.venueAccountId());
    }

    long count(UUID recipient, String code) {
        return db.sql("SELECT count(*) FROM notification.notification WHERE recipient_id = :r AND code = :code")
                .param("r", recipient).param("code", code).query(Long.class).single();
    }

    long bookingCount(UUID recipient, UUID bookingId, String code) {
        return db.sql("""
                        SELECT count(*) FROM notification.notification
                         WHERE recipient_id = :r AND booking_id = :b AND code = :code""")
                .param("r", recipient).param("b", bookingId).param("code", code).query(Long.class).single();
    }

    static Instant dbNow() {
        return OWNER.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    /** As OwnerReviewHttpTest: 26 hours of Maintenance that ended four hours ago, planted behind the guards. */
    static void overThreshold(UUID room) {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(PostgresTestContainer.jdbcUrl(DATABASE),
                MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            UUID id = UUID.randomUUID();
            jdbc.sql("SET session_replication_role = replica").update();
            jdbc.sql("INSERT INTO operations.maintenance_window (id, resource_id, period, reason, owner_password_used)"
                            + " VALUES (:id, :room, tstzrange(clock_timestamp() - interval '30 hours',"
                            + " clock_timestamp() - interval '4 hours', '[)'), 'Repeated faults', true)")
                    .param("id", id).param("room", room).update();
            jdbc.sql("""
                            INSERT INTO inventory.resource_reservation (id, resource_id, reservation_type, maintenance_id,
                                                                        planned_start, planned_end, planned_range,
                                                                        actual_reservation_start, active_flag)
                            SELECT gen_random_uuid(), m.resource_id, 'MAINTENANCE', m.id, lower(m.period),
                                   upper(m.period), m.period, lower(m.period), true
                              FROM operations.maintenance_window m WHERE m.id = :id""")
                    .param("id", id).update();
            jdbc.sql("SET session_replication_role = origin").update();
        } finally {
            owner.destroy();
        }
    }
}
