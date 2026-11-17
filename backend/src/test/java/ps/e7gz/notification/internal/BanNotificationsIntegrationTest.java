package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.fixtures.Fixtures.anAdmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.attendance.internal.AttendanceFlows;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.codes.AttendanceResult;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.messaging.internal.OutboxDrain;
import ps.e7gz.notification.api.NotifiedBookings;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-71 Ban notifications through the real outbox and the {@code worker} consumers, built by hand as in
 * {@code BookingNotificationsIntegrationTest}: each Ban event tells the customer once (Product v5.0 section 52.2;
 * BAN_EXTENDED: Amr Heiba, 18 Oct 2026), and each booking its cascade cancels tells the customer and the venue once.
 * Every event is drained twice and redelivered once more, as a dispatcher retry would.
 */
class BanNotificationsIntegrationTest extends IntegrationTest {

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
    AttendanceFlows attendance;
    BanNotificationConsumer bans;
    BookingNotificationConsumer bookings;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        flows = BookingFlows.of(context);
        attendance = AttendanceFlows.of(context);
        bans = new BanNotificationConsumer(runner, notifications);
        bookings = new BookingNotificationConsumer(runner, notifier, notifiedBookings, jobs);
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    /**
     * Activation: "Temporary Ban activated" once, with the stage and the end; the cascade-cancelled booking: "Booking
     * cancelled due to Ban" to the customer and "Booking Auto Cancelled" to its venue, once each.
     */
    @Test
    void anActivationTellsTheCustomerOnceAndEachCancelledBookingOnce() {
        BookingFlows.Customer customer = flows.customer();
        missed(customer, Duration.ofMinutes(20));
        missed(customer, Duration.ofMinutes(20));
        Booking cancelled = customer.nextConfirmed(Duration.ofHours(3));
        Booking third = missed(customer, Duration.ofMinutes(20));
        UUID user = third.customerId();

        deliverTwiceAndRedeliver(user, IntegrationEvents.BAN_ACTIVATED);

        assertThat(codes(user, "TEMPORARY_BAN_ACTIVATED")).hasSize(1);
        assertThat(param(user, "TEMPORARY_BAN_ACTIVATED", "penaltyStage")).isEqualTo("1");
        assertThat(param(user, "TEMPORARY_BAN_ACTIVATED", "endsAt")).isEqualTo(db.sql("""
                        SELECT payload ->> 'endsAt' FROM technical.outbox_event
                         WHERE event_type = 'BanActivated' AND payload ->> 'userId' = :user""")
                .param("user", user.toString()).query(String.class).single());
        assertThat(db.sql("""
                        SELECT recipient_type || ' ' || code FROM notification.notification
                         WHERE booking_id = :id AND code IN ('BOOKING_CANCELLED_BAN', 'VENUE_BOOKING_AUTO_CANCELLED')""")
                .param("id", cancelled.bookingId()).query(String.class).list())
                .containsExactlyInAnyOrder("CUSTOMER BOOKING_CANCELLED_BAN", "VENUE VENUE_BOOKING_AUTO_CANCELLED");
    }

    /**
     * Extension (Amr Heiba, 18 Oct 2026): BAN_EXTENDED once per committed extension, with the new stage and end and
     * whether the end moved - never a second time on redelivery, and never the activation code for it.
     */
    @Test
    void anExtensionTellsTheCustomerOnceWithTheNewStageAndEnd() {
        BookingFlows.Customer customer = flows.customer();
        Booking earliest = completed(customer, Duration.ofHours(3));
        Booking earlier = completed(customer, Duration.ofHours(2));
        missed(customer, Duration.ofMinutes(50));
        missed(customer, Duration.ofMinutes(40));
        Booking third = missed(customer, Duration.ofMinutes(30));
        UUID admin = anAdmin().create().id();
        attendance.correct(admin, earlier.bookingId(), AttendanceResult.ABSENT_NO_SHOW, "was absent");
        attendance.correct(admin, earliest.bookingId(), AttendanceResult.ABSENT_NO_SHOW, "was absent too");
        UUID user = third.customerId();

        deliverTwiceAndRedeliver(user, IntegrationEvents.BAN_EXTENDED);

        assertThat(codes(user, "BAN_EXTENDED")).hasSize(1);
        assertThat(param(user, "BAN_EXTENDED", "penaltyStage")).isEqualTo("2");
        assertThat(param(user, "BAN_EXTENDED", "endsAtChanged")).isEqualTo("true");
        assertThat(param(user, "BAN_EXTENDED", "endsAt")).isEqualTo(db.sql("""
                        SELECT payload ->> 'endsAt' FROM technical.outbox_event
                         WHERE event_type = 'BanExtended' AND payload ->> 'userId' = :user""")
                .param("user", user.toString()).query(String.class).single());
        assertThat(db.sql("""
                        SELECT ends_at = CAST(:end AS timestamptz) FROM trust.ban_instance
                         WHERE user_id = :user AND status = 'ACTIVE'""")
                .param("end", param(user, "BAN_EXTENDED", "endsAt")).param("user", user)
                .query(Boolean.class).single()).as("the end the Ban really has").isTrue();
    }

    /** Expiry: "Ban Expired" once, even when its event is handled twice. */
    @Test
    void anExpiryTellsTheCustomerOnce() {
        Booking third = missed(flows.customer(), Duration.ofMinutes(20));
        UUID user = third.customerId();
        OutboxEvent expired = new OutboxEvent(Long.MAX_VALUE - 7, IntegrationEvents.BAN_EXPIRED, "BAN_INSTANCE",
                UUID.randomUUID(), json.valueToTree(Map.of("userId", user.toString(), "penaltyStage", 1,
                        "endsAt", Instant.now().toString())), UUID.randomUUID(), Instant.now(), 0);

        bans.handle(expired);
        bans.handle(expired);

        assertThat(codes(user, "BAN_EXPIRED")).hasSize(1);
    }

    /** FR-TRS-22's MAX can keep the end: the extension then says the end did not move (no claim of a longer Ban). */
    @Test
    void anExtensionThatKeepsTheEndSaysSo() {
        String end = "2026-12-01T10:00:00Z";
        OutboxEvent kept = new OutboxEvent(1, IntegrationEvents.BAN_EXTENDED, "BAN_INSTANCE", UUID.randomUUID(),
                json.valueToTree(Map.of("userId", UUID.randomUUID().toString(), "penaltyStage", 3,
                        "previousEndsAt", end, "endsAt", end)), UUID.randomUUID(), Instant.now(), 0);

        assertThat(BanNotificationConsumer.request(kept).params())
                .containsEntry("penaltyStage", 3).containsEntry("endsAt", end).containsEntry("endsAtChanged", false);
        assertThat(BanNotificationConsumer.request(kept).code()).isEqualTo("BAN_EXTENDED");
    }

    // ----------------------------------------------------------------------------------------------------- support

    private Booking missed(BookingFlows.Customer customer, Duration startedAgo) {
        Booking booking = customer.nextActive(startedAgo);
        attendance.noShow(booking.venueAccountId(), booking.bookingId());
        return booking;
    }

    private Booking completed(BookingFlows.Customer customer, Duration endedAgo) {
        Booking booking = flows.pastItsEnd(customer.nextActive(Duration.ofMinutes(5)), endedAgo);
        assertThat(flows.fireCompletion(booking)).as("T5 settled").isTrue();
        return booking;
    }

    /** Every due event to both consumers, each event twice, then the User's {@code type} event once more. */
    private void deliverTwiceAndRedeliver(UUID user, String type) {
        OutboxDrain.drain(appDataSource, json, twice(bans), twice(bookings));
        OutboxEvent event = db.sql("""
                        SELECT id, event_type, aggregate_type, aggregate_id, CAST(payload AS text) AS payload,
                               correlation_id, created_at
                          FROM technical.outbox_event WHERE event_type = :type AND payload ->> 'userId' = :user""")
                .param("type", type).param("user", user.toString())
                .query((rs, n) -> {
                    try {
                        return new OutboxEvent(rs.getLong("id"), rs.getString("event_type"),
                                rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                                json.readTree(rs.getString("payload")), rs.getObject("correlation_id", UUID.class),
                                rs.getTimestamp("created_at").toInstant(), 0);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .single();
        bans.handle(event);
    }

    private List<UUID> codes(UUID user, String code) {
        return db.sql("SELECT id FROM notification.notification WHERE recipient_id = :user AND code = :code")
                .param("user", user).param("code", code).query(UUID.class).list();
    }

    private String param(UUID user, String code, String name) {
        return db.sql("""
                        SELECT params ->> :name FROM notification.notification
                         WHERE recipient_id = :user AND code = :code""")
                .param("name", name).param("user", user).param("code", code).query(String.class).single();
    }

    private static OutboxConsumer twice(OutboxConsumer consumer) {
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
