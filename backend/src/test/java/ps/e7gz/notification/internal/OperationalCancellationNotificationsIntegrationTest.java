package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
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
import org.springframework.test.web.servlet.MockMvc;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.messaging.internal.OutboxDrain;
import ps.e7gz.notification.api.NotifiedBookings;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-80 (PO decisions 25 Nov 2026, Q2): the real emergency cascades (E7-82) through the real outbox and the booking
 * notification consumer, every event delivered twice as a dispatcher retry would. Each cancelled booking gives its
 * customer the cascade's own code once and its venue one VENUE_BOOKING_AUTO_CANCELLED - never the generic customer code
 * as well.
 */
@AutoConfigureMockMvc
class OperationalCancellationNotificationsIntegrationTest extends IntegrationTest {

    private static final String EMERGENCIES = "/api/v1/venue/emergencies";

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
    BookingNotifier notifier;

    @Autowired
    NotifiedBookings notifiedBookings;

    @Autowired
    ScheduledJobs jobs;

    JdbcClient db;
    BookingFlows flows;
    BookingNotificationConsumer bookings;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        flows = BookingFlows.of(context);
        bookings = new BookingNotificationConsumer(runner, notifier, notifiedBookings, jobs);
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    @Test
    void venueEmergencyCascadeTellsEachSideOnce() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        Instant expectedEnd = booking.startAt().plus(Duration.ofMinutes(60));
        mvc.perform(post(EMERGENCIES + "/venue-closure").header("Authorization", venue(booking))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Power cut\",\"expectedEndAt\":\"" + expectedEnd + "\"}"))
                .andExpect(status().isCreated());

        OutboxDrain.drain(appDataSource, json, twice(bookings));

        assertThat(count(booking.customerId(), booking.bookingId(), "BOOKING_CANCELLED_VENUE_EMERGENCY")).isOne();
        assertThat(count(booking.customerId(), booking.bookingId(), "BOOKING_AUTO_CANCELLED")).isZero();
        assertThat(count(booking.venueId(), booking.bookingId(), "VENUE_BOOKING_AUTO_CANCELLED")).isOne();
    }

    @Test
    void resourceEmergencyCascadeTellsEachSideOnce() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        Instant expectedReturn = booking.startAt().plus(Duration.ofMinutes(60));
        mvc.perform(post(EMERGENCIES + "/resource-outage").header("Authorization", venue(booking))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\":\"" + booking.resourceId() + "\",\"reason\":\"Console overheating\","
                                + "\"expectedReturnAt\":\"" + expectedReturn + "\"}"))
                .andExpect(status().isCreated());

        OutboxDrain.drain(appDataSource, json, twice(bookings));

        assertThat(count(booking.customerId(), booking.bookingId(), "BOOKING_CANCELLED_RESOURCE_EMERGENCY")).isOne();
        assertThat(count(booking.customerId(), booking.bookingId(), "BOOKING_AUTO_CANCELLED")).isZero();
        assertThat(count(booking.venueId(), booking.bookingId(), "VENUE_BOOKING_AUTO_CANCELLED")).isOne();
    }

    private String venue(BookingFlows.Booking at) {
        return "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, at.venueAccountId());
    }

    private long count(UUID recipient, UUID bookingId, String code) {
        return db.sql("""
                        SELECT count(*) FROM notification.notification
                         WHERE recipient_id = :recipient AND booking_id = :booking AND code = :code""")
                .param("recipient", recipient).param("booking", bookingId).param("code", code)
                .query(Long.class).single();
    }

    static OutboxConsumer twice(OutboxConsumer consumer) {
        return new OutboxConsumer() {
            @Override
            public Set<String> eventTypes() {
                return consumer.eventTypes();
            }

            @Override
            public void handle(OutboxEvent event) {
                consumer.handle(event);
                consumer.handle(event);
            }
        };
    }
}
