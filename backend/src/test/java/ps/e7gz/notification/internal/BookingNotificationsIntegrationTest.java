package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.messaging.internal.OutboxDrain;
import ps.e7gz.notification.api.BookingReminders;
import ps.e7gz.notification.api.NotifiedBookings;
import ps.e7gz.notification.api.NotifiedBookings.NotifiedBooking;
import ps.e7gz.notification.internal.email.LoggingEmailSender;
import ps.e7gz.notification.internal.push.LoggingPushSender;
import ps.e7gz.notification.internal.webpush.LoggingWebPushSender;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.support.boundary.TestCommands;

/**
 * E7-55 against the real database: each booking event creates its notifications exactly once (the task's "done when"),
 * through the one consumer the {@code worker} profile runs ({@link BookingNotificationConsumer}), with the Booking ID as
 * reference (FR-NTF-04); a retried event creates nothing more; the two reminder timers fire once, from the stored
 * deadline and start, and supersede themselves when the booking moved on.
 *
 * <p>The worker profile is off in tests, so the consumer, the handlers and the delivery worker are built here as the
 * worker builds them, and {@link OutboxDrain} runs the real dispatcher over the real outbox.
 */
@ExtendWith(OutputCaptureExtension.class)
class BookingNotificationsIntegrationTest extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    CommandRunner runner;

    @Autowired
    BookingNotifier notifier;

    @Autowired
    NotifiedBookings notifiedBookings;

    @Autowired
    ScheduledJobs jobs;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    JdbcClient db;
    BookingFlows flows;
    BookingNotificationConsumer consumer;
    VenuePendingRequestExpiringHandler expiring;
    BookingStartsSoonHandler startsSoon;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        flows = BookingFlows.of(context);
        consumer = new BookingNotificationConsumer(runner, notifier, notifiedBookings, jobs);
        expiring = new VenuePendingRequestExpiringHandler(runner, notifiedBookings, notifier);
        startsSoon = new BookingStartsSoonHandler(runner, notifiedBookings, notifier);
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void tearDown() {
        correlation.close();
    }

    // ------------------------------------------------------------------------------------------- events, once each

    @Test
    void aCreatedBookingTellsTheCustomerAndTheVenueOnceAndArmsTheExpiringReminder() {
        Booking booking = flows.pending();

        drain();

        assertThat(notifications(booking)).containsExactlyInAnyOrder(
                "CUSTOMER BOOKING_CREATED " + booking.customerId(),
                "VENUE VENUE_NEW_BOOKING_REQUEST " + booking.venueId());
        assertThat(venueName(booking, "BOOKING_CREATED")).isEqualTo(booking.venueName());
        assertThat(job(BookingReminders.venuePendingRequestExpiringKey(booking.bookingId()))).hasValueSatisfying(
                job -> {
                    assertThat(job.status()).isEqualTo("PENDING");
                    assertThat(job.dueAt()).isEqualTo(booking.venueResponseExpiresAt().minus(Duration.ofMinutes(3)));
                });
    }

    /** The task's test: one VENUE_NEW_BOOKING_REQUEST, its PUSH sent by the venue Web Push sender (the log one in dev). */
    @Test
    void theNewRequestIsWebPushedToTheVenueDashboardInItsLanguage(CapturedOutput output) {
        Booking booking = flows.pending();
        browser(booking.venueAccountId());

        drain();
        OutboxDrain.drain(appDataSource, json, new NotificationDeliveryWorker(appDataSource,
                new NotificationTemplates(), new LoggingPushSender(), new LoggingWebPushSender(),
                new LoggingEmailSender(), event -> false, json));

        UUID request = notificationId(booking, "VENUE_NEW_BOOKING_REQUEST");
        assertThat(delivery(request, "PUSH")).isEqualTo("SENT");
        assertThat(delivery(request, "IN_APP")).isEqualTo("SENT");
        assertThat(output).contains("DEV WEB PUSH").contains("New booking request")
                .contains("A customer sent a new booking request.");
    }

    @Test
    void anAcceptedBookingTellsTheCustomerOnceWithTheVenueNameAndArmsTheStartsSoonReminder() {
        Booking booking = flows.pending();
        flows.accept(booking);

        drain();

        assertThat(customerCodes(booking)).containsExactlyInAnyOrder("BOOKING_CREATED", "BOOKING_CONFIRMED");
        assertThat(venueName(booking, "BOOKING_CONFIRMED")).isEqualTo(booking.venueName());
        assertThat(job(BookingReminders.bookingStartsSoonKey(booking.bookingId()))).hasValueSatisfying(job -> {
            assertThat(job.status()).isEqualTo("PENDING");
            assertThat(job.dueAt()).isEqualTo(booking.startAt().minus(Duration.ofMinutes(20)));
        });
    }

    @Test
    void aBookingConfirmedLessThanTwentyMinutesBeforeItsStartGetsNoStartsSoonReminder() {
        Booking booking = flows.pendingStartingIn(Duration.ofMinutes(16));
        flows.accept(booking);

        drain();

        assertThat(customerCodes(booking)).contains("BOOKING_CONFIRMED");
        assertThat(job(BookingReminders.bookingStartsSoonKey(booking.bookingId()))).isEmpty();
    }

    @Test
    void aRejectedBookingTellsTheCustomerOnce() {
        Booking booking = flows.pending();
        flows.reject(booking);

        drain();

        assertThat(customerCodes(booking)).containsExactlyInAnyOrder("BOOKING_CREATED", "BOOKING_REJECTED");
        assertThat(venueName(booking, "BOOKING_REJECTED")).isEqualTo(booking.venueName());
    }

    @Test
    void anExpiredRequestAndALaterRejectAfterExpiryEachTellTheCustomerOnceWithTheirOwnCode() {
        Booking booking = flows.pending();
        flows.expire(booking);
        drain();
        assertThat(customerCodes(booking)).containsExactlyInAnyOrder("BOOKING_CREATED", "VENUE_RESPONSE_EXPIRED");

        flows.rejectAfterExpiry(booking);
        drain();

        assertThat(customerCodes(booking)).containsExactlyInAnyOrder("BOOKING_CREATED", "VENUE_RESPONSE_EXPIRED",
                "REJECTED_AFTER_EXPIRY");
        assertThat(venueCodes(booking)).containsExactly("VENUE_NEW_BOOKING_REQUEST");
    }

    @Test
    void aCustomerCancelledBookingTellsTheCustomerOnce() {
        Booking booking = flows.pending();
        flows.customerCancels(booking);

        drain();

        assertThat(customerCodes(booking)).containsExactlyInAnyOrder("BOOKING_CREATED", "BOOKING_CANCELLED");
        assertThat(venueCodes(booking)).containsExactly("VENUE_NEW_BOOKING_REQUEST");
    }

    /** FR-BOK-16: the generic customer code (PO decision 13 Oct 2026) and the venue's auto-cancel (12 Oct 2026). */
    @Test
    void anAcceptStoppedByAnOperationalCauseTellsTheCustomerAndTheVenueOnce() {
        Booking booking = flows.pending();
        flows.acceptStoppedByVenueEmergency(booking);

        drain();

        assertThat(customerCodes(booking)).containsExactlyInAnyOrder("BOOKING_CREATED", "BOOKING_AUTO_CANCELLED");
        assertThat(venueCodes(booking)).containsExactlyInAnyOrder("VENUE_NEW_BOOKING_REQUEST",
                "VENUE_BOOKING_AUTO_CANCELLED");
    }

    /** PO decision 13 Oct 2026: the venue name the event recorded wins over the name when the notification is made. */
    @Test
    void theVenueNameAnEventCarriesIsTheOneTheCustomerSees() {
        Booking booking = flows.pending();
        flows.accept(booking);

        consumer.handle(new OutboxEvent(ThreadLocalRandom.current().nextLong(1L << 40, Long.MAX_VALUE),
                IntegrationEvents.BOOKING_CONFIRMED, "BOOKING", booking.bookingId(),
                json.valueToTree(Map.of("bookingId", booking.bookingId().toString(),
                        "venueName", "The name at the event")),
                CorrelationId.generate(), Instant.EPOCH, 0));

        assertThat(venueName(booking, "BOOKING_CONFIRMED")).isEqualTo("The name at the event");
    }

    /** A retry runs every consumer of the event again (OutboxConsumer): the second run creates nothing. */
    @Test
    void anEventHandledTwiceCreatesItsNotificationsOnce() {
        Booking booking = flows.pending();
        flows.accept(booking);

        OutboxDrain.drain(appDataSource, json, twice(consumer));

        assertThat(notifications(booking)).containsExactlyInAnyOrder(
                "CUSTOMER BOOKING_CREATED " + booking.customerId(),
                "VENUE VENUE_NEW_BOOKING_REQUEST " + booking.venueId(),
                "CUSTOMER BOOKING_CONFIRMED " + booking.customerId());
        assertThat(db.sql("SELECT type FROM technical.scheduled_job WHERE subject_id = :id AND type IN (:types)")
                .param("id", booking.bookingId())
                .param("types", List.of(BookingReminders.VENUE_PENDING_REQUEST_EXPIRING,
                        BookingReminders.BOOKING_STARTS_SOON))
                .query(String.class).list())
                .as("one starts-soon job; no expiring one: the request was answered before BookingCreated was handled")
                .containsExactly(BookingReminders.BOOKING_STARTS_SOON);
    }

    // ---------------------------------------------------------------------------- VENUE_PENDING_REQUEST_EXPIRING

    @Test
    void theExpiringReminderFiresOnceInsideTheLastThreeMinutes() {
        Booking booking = flows.pending();
        drain();
        String key = BookingReminders.venuePendingRequestExpiringKey(booking.bookingId());
        DueJobs due = DueJobs.of(context, expiring);

        runner.run(TestCommands.under(flows.scope(booking), command -> {
            BookingFlows.moveResponseDeadline(command, booking, command.commandNow().plus(Duration.ofMinutes(2)));
            due.makeDue(command, key, command.commandNow());
            return null;
        }));
        assertThat(due.fire(key)).isTrue();

        assertThat(job(key).orElseThrow().status()).isEqualTo("DONE");
        assertThat(venueCodes(booking)).containsExactlyInAnyOrder("VENUE_NEW_BOOKING_REQUEST",
                "VENUE_PENDING_REQUEST_EXPIRING");
    }

    @Test
    void theExpiringReminderIsSupersededOnceTheVenueAnswered() {
        Booking booking = flows.pending();
        drain();
        flows.accept(booking);
        String key = BookingReminders.venuePendingRequestExpiringKey(booking.bookingId());
        DueJobs due = DueJobs.of(context, expiring);

        runner.run(TestCommands.under(flows.scope(booking), command -> {
            due.makeDue(command, key, command.commandNow());
            return null;
        }));
        due.fire(key);

        assertThat(job(key).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(venueCodes(booking)).containsExactly("VENUE_NEW_BOOKING_REQUEST");
    }

    @Test
    void theExpiringReminderFollowsADeadlineThatMovedLaterAndNeverWarnsAtOrAfterTheDeadline() {
        Booking booking = flows.pending();
        drain();
        String key = BookingReminders.venuePendingRequestExpiringKey(booking.bookingId());
        DueJobs due = DueJobs.of(context, expiring);

        Instant deadline = runner.run(TestCommands.under(flows.scope(booking), command -> {
            Instant later = command.commandNow().plus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.SECONDS);
            BookingFlows.moveResponseDeadline(command, booking, later);
            due.makeDue(command, key, command.commandNow());
            return later;
        }));
        due.fire(key);
        assertThat(job(key).orElseThrow()).satisfies(job -> {
            assertThat(job.status()).isEqualTo("PENDING");
            assertThat(job.dueAt()).isEqualTo(deadline.minus(Duration.ofMinutes(3)));
        });

        runner.run(TestCommands.under(flows.scope(booking), command -> {
            BookingFlows.moveResponseDeadline(command, booking, command.commandNow());
            due.makeDue(command, key, command.commandNow());
            return null;
        }));
        due.fire(key);

        assertThat(job(key).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(venueCodes(booking)).containsExactly("VENUE_NEW_BOOKING_REQUEST");
    }

    // --------------------------------------------------------------------------------------- BOOKING_STARTS_SOON

    /**
     * {@code start_at} is immutable, so the boundaries are hit by handing the handler the booking with its start moved
     * relative to the fire command's own instant; everything else is the real booking.
     */
    @Test
    void theStartsSoonReminderFiresOnceFromTheStartAndOnlyInsideItsTwentyMinutes() {
        Booking booking = flows.pending();
        flows.accept(booking);
        drain();
        String key = BookingReminders.bookingStartsSoonKey(booking.bookingId());

        JobOutcome early = fireStartsSoon(booking, key, Duration.ofMinutes(20).plusSeconds(1));
        JobOutcome at = fireStartsSoon(booking, key, Duration.ofMinutes(20));
        JobOutcome started = fireStartsSoon(booking, key, Duration.ZERO);
        JobOutcome again = fireStartsSoon(booking, key, Duration.ofMinutes(5));

        assertThat(early).isInstanceOf(JobOutcome.Reschedule.class);
        assertThat(at).isInstanceOf(JobOutcome.Done.class);
        assertThat(started).isInstanceOf(JobOutcome.Superseded.class);
        assertThat(again).isInstanceOf(JobOutcome.Done.class);
        assertThat(customerCodes(booking)).containsExactlyInAnyOrder("BOOKING_CREATED", "BOOKING_CONFIRMED",
                "BOOKING_STARTS_SOON");
        assertThat(venueName(booking, "BOOKING_STARTS_SOON")).isEqualTo(booking.venueName());
    }

    @Test
    void theStartsSoonReminderIsSupersededOnceTheBookingIsCancelled() {
        Booking booking = flows.pending();
        flows.accept(booking);
        drain();
        String key = BookingReminders.bookingStartsSoonKey(booking.bookingId());
        DueJobs due = DueJobs.of(context, startsSoon);
        cancelConfirmed(booking);

        runner.run(TestCommands.under(flows.scope(booking), command -> {
            due.makeDue(command, key, command.commandNow());
            return null;
        }));
        due.fire(key);

        assertThat(job(key).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(customerCodes(booking)).doesNotContain("BOOKING_STARTS_SOON");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void drain() {
        OutboxDrain.drain(appDataSource, json, consumer);
    }

    /** The handler's own decision, with "now" {@code before} the start; one command, under the booking's locks. */
    private JobOutcome fireStartsSoon(Booking booking, String key, Duration before) {
        return runner.run(TestCommands.under(flows.scope(booking), command -> {
            Instant start = command.commandNow().plus(before);
            NotifiedBooking moved = notifiedBookings.find(command, booking.bookingId()).orElseThrow().withStartAt(start);
            return startsSoon.fire(command, new ScheduledJob(UUID.randomUUID(), BookingReminders.BOOKING_STARTS_SOON,
                    "BOOKING", booking.bookingId(), start.minus(Duration.ofMinutes(20)), 1, key,
                    CorrelationId.generate()), moved);
        }));
    }

    /** A CONFIRMED booking is no longer CONFIRMED: the customer's cancel of it is E7-49's, through the real command. */
    private void cancelConfirmed(Booking booking) {
        flows.customerCancels(booking);
        assertThat(db.sql("SELECT status FROM booking.booking WHERE id = :id").param("id", booking.bookingId())
                .query(String.class).single()).isEqualTo("CANCELLED");
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

    /** "RECIPIENT_TYPE CODE recipientId" of every notification about the booking; each must reference it. */
    private List<String> notifications(Booking booking) {
        return db.sql("""
                        SELECT recipient_type || ' ' || code || ' ' || recipient_id FROM notification.notification
                         WHERE booking_id = :id""")
                .param("id", booking.bookingId()).query(String.class).list();
    }

    private List<String> customerCodes(Booking booking) {
        return codes(booking, "CUSTOMER", booking.customerId());
    }

    private List<String> venueCodes(Booking booking) {
        return codes(booking, "VENUE", booking.venueId());
    }

    private List<String> codes(Booking booking, String type, UUID recipient) {
        return db.sql("""
                        SELECT code FROM notification.notification
                         WHERE booking_id = :id AND recipient_type = :type AND recipient_id = :recipient""")
                .param("id", booking.bookingId()).param("type", type).param("recipient", recipient)
                .query(String.class).list();
    }

    private UUID notificationId(Booking booking, String code) {
        return db.sql("SELECT id FROM notification.notification WHERE booking_id = :id AND code = :code")
                .param("id", booking.bookingId()).param("code", code).query(UUID.class).single();
    }

    private String venueName(Booking booking, String code) {
        return db.sql("""
                        SELECT params ->> 'venueName' FROM notification.notification
                         WHERE booking_id = :id AND code = :code""")
                .param("id", booking.bookingId()).param("code", code).query(String.class).single();
    }

    private String delivery(UUID notificationId, String channel) {
        return db.sql("""
                        SELECT status FROM notification.notification_delivery
                         WHERE notification_id = :id AND channel = :channel""")
                .param("id", notificationId).param("channel", channel).query(String.class).single();
    }

    private record Job(String status, Instant dueAt) {
    }

    private Optional<Job> job(String key) {
        return db.sql("SELECT status, due_at FROM technical.scheduled_job WHERE idempotency_key = :key")
                .param("key", key)
                .query((rs, n) -> new Job(rs.getString("status"), rs.getObject("due_at", OffsetDateTime.class)
                        .toInstant()))
                .optional();
    }

    /** An active Dashboard browser of the account (as in {@code NotificationDeliveryWorkerTest}). */
    private void browser(UUID venueAccountId) {
        db.sql("""
                        INSERT INTO notification.venue_push_subscription
                               (id, venue_account_id, endpoint, p256dh_key, auth_key, last_seen_at, created_at)
                        VALUES (:id, :account, :endpoint, 'p256dh', 'auth', now(), now())""")
                .param("id", UUID.randomUUID()).param("account", venueAccountId)
                .param("endpoint", "https://fcm.googleapis.com/fcm/send/e7-55-" + UUID.randomUUID()).update();
    }
}
