package ps.e7gz.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import ps.e7gz.attendance.internal.AttendanceFlows;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
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
import ps.e7gz.venue.api.HoursChangeBookings;

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

    @Autowired
    HoursChangeBookings hoursChangeBookings;

    JdbcClient db;
    BookingFlows flows;
    AttendanceFlows attendance;
    BookingNotificationConsumer consumer;
    VenuePendingRequestExpiringHandler expiring;
    BookingStartsSoonHandler startsSoon;
    CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        db = JdbcClient.create(appDataSource);
        flows = BookingFlows.of(context);
        attendance = AttendanceFlows.of(context);
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

    // ---------------------------------------------------------------- E7-113 opening-hours change (O5), Phase 1

    /**
     * FR-VEN-23 step 9: the hours change's BookingCancelled tells the customer once - VENUE_OPENING_HOURS_CHANGED,
     * made by that one stored event even when it is handled twice and redelivered - and tells the venue nothing.
     */
    @Test
    void anOnlineBookingCancelledByAnHoursChangeTellsOnlyTheCustomerOnce() {
        Booking booking = flows.pending();
        flows.publishCancelledByHoursChange(booking);

        drainTwiceAndRedeliver(booking, IntegrationEvents.BOOKING_CANCELLED);

        assertMadeOnceBy(booking, IntegrationEvents.BOOKING_CANCELLED,
                "CUSTOMER VENUE_OPENING_HOURS_CHANGED " + booking.customerId());
        assertThat(notifications(booking)).containsExactlyInAnyOrder(
                "CUSTOMER BOOKING_CREATED " + booking.customerId(),
                "VENUE VENUE_NEW_BOOKING_REQUEST " + booking.venueId(),
                "CUSTOMER VENUE_OPENING_HOURS_CHANGED " + booking.customerId());
        assertThat(venueCodes(booking)).as("no venue notification for its own change")
                .containsExactly("VENUE_NEW_BOOKING_REQUEST");
        assertThat(venueName(booking, "VENUE_OPENING_HOURS_CHANGED")).isEqualTo(booking.venueName());
    }

    /**
     * A Manual Booking cancelled by an hours change notifies nobody: no customer, and the venue made the change. The
     * stored event's own plan is checked too: the notifier would skip the missing customer anyway, so only the plan
     * shows the rule itself refuses it.
     */
    @Test
    void aManualBookingCancelledByAnHoursChangeNotifiesNobody() {
        Booking booking = flows.manualConfirmed();
        flows.publishCancelledByHoursChange(booking);
        OutboxEvent cancelled = outboxEvent(booking, IntegrationEvents.BOOKING_CANCELLED);
        assertThat(cancelled.text("terminalReason")).isEqualTo("VENUE_OPENING_HOURS_CHANGED");
        assertThat(cancelled.payload().path("source").asText()).isEqualTo("MANUAL");

        assertThat(BookingNotificationRules.planFor(cancelled)).isEqualTo(BookingNotificationRules.Plan.NONE);

        drainTwiceAndRedeliver(booking, IntegrationEvents.BOOKING_CANCELLED);

        assertThat(notifications(booking)).isEmpty();
    }

    /** One notification per cancelled booking, not per customer: two bookings of one customer make two. */
    @Test
    void twoBookingsOfOneCustomerCancelledByAnHoursChangeMakeTwoBookingNotifications() {
        List<Booking> bookings = flows.twoPendingOfOneCustomer();
        Booking first = bookings.get(0);
        Booking second = bookings.get(1);
        assertThat(second.customerId()).isEqualTo(first.customerId());
        flows.publishCancelledByHoursChange(first);
        flows.publishCancelledByHoursChange(second);

        drain();

        for (Booking booking : bookings) {
            assertThat(customerCodes(booking)).as("booking %s", booking.bookingId())
                    .containsOnlyOnce("VENUE_OPENING_HOURS_CHANGED");
            assertMadeOnceBy(booking, IntegrationEvents.BOOKING_CANCELLED,
                    "CUSTOMER VENUE_OPENING_HOURS_CHANGED " + booking.customerId());
        }
        assertThat(notificationId(first, "VENUE_OPENING_HOURS_CHANGED"))
                .isNotEqualTo(notificationId(second, "VENUE_OPENING_HOURS_CHANGED"));
        assertThat(db.sql("""
                        SELECT count(*) FROM notification.notification
                         WHERE code = 'VENUE_OPENING_HOURS_CHANGED' AND recipient_id = :customer""")
                .param("customer", first.customerId()).query(Long.class).single()).isEqualTo(2L);
    }

    /**
     * E7-113 Phase 3B, end to end: the REAL hours-change cancellation ({@link HoursChangeBookings#cancel}, committed
     * under the caller's locks) writes the BookingCancelled events this one consumer turns into notifications - no
     * synthetic event. Two Online Bookings of one customer, cancelled in one call, make two notifications, one per
     * booking; a Manual Booking cancelled the same way makes none; redelivery makes nothing new.
     */
    @Test
    void theRealHoursChangeCancellationTellsTheCustomerOncePerOnlineBookingAndNobodyForAManualOne() {
        List<Booking> online = flows.twoPendingOfOneCustomer();
        Booking first = online.get(0);
        Booking second = online.get(1);
        assertThat(second.customerId()).isEqualTo(first.customerId());
        assertThat(second.venueId()).isEqualTo(first.venueId());
        Booking manual = flows.manualConfirmed();
        assertThat(db.sql("SELECT operating_session_id IS NOT NULL FROM booking.booking WHERE id = :id")
                .param("id", manual.bookingId()).query(Boolean.class).single())
                .as("a Manual Booking made inside published hours").isTrue();

        cancelByHoursChange(first.venueId(), first.venueAccountId(), online);
        cancelByHoursChange(manual.venueId(), manual.venueAccountId(), List.of(manual));

        // committed, read on another connection: the bookings and the one event each cancellation wrote
        for (Booking booking : List.of(first, second, manual)) {
            assertThat(db.sql("SELECT status || ' ' || terminal_reason FROM booking.booking WHERE id = :id")
                    .param("id", booking.bookingId()).query(String.class).single())
                    .as("booking %s", booking.bookingId()).isEqualTo("CANCELLED VENUE_OPENING_HOURS_CHANGED");
            OutboxEvent cancelled = outboxEvent(booking, IntegrationEvents.BOOKING_CANCELLED);
            assertThat(cancelled.text("terminalReason")).isEqualTo("VENUE_OPENING_HOURS_CHANGED");
        }
        assertThat(outboxEvent(first, IntegrationEvents.BOOKING_CANCELLED).payload().has("source")).isFalse();
        OutboxEvent manualCancelled = outboxEvent(manual, IntegrationEvents.BOOKING_CANCELLED);
        assertThat(manualCancelled.payload().path("source").asText()).isEqualTo("MANUAL");
        assertThat(manualCancelled.payload().path("customerId").isNull()).isTrue();
        assertThat(bookingEventTypes(first)).containsExactly(IntegrationEvents.BOOKING_CREATED,
                IntegrationEvents.BOOKING_CANCELLED);
        assertThat(bookingEventTypes(second)).containsExactly(IntegrationEvents.BOOKING_CREATED,
                IntegrationEvents.BOOKING_CANCELLED);
        assertThat(bookingEventTypes(manual)).containsExactly(IntegrationEvents.BOOKING_CONFIRMED,
                IntegrationEvents.BOOKING_CANCELLED);
        assertThat(db.sql("SELECT count(*) FROM technical.outbox_event WHERE event_type ILIKE '%OpeningHours%'")
                .query(Long.class).single()).as("no event for the hours change itself").isZero();

        // every stored event handled twice by the real consumer, then each cancellation redelivered once more
        drainTwiceAndRedeliver(first, IntegrationEvents.BOOKING_CANCELLED);
        consumer.handle(outboxEvent(second, IntegrationEvents.BOOKING_CANCELLED));
        consumer.handle(outboxEvent(manual, IntegrationEvents.BOOKING_CANCELLED));

        for (Booking booking : online) {
            assertMadeOnceBy(booking, IntegrationEvents.BOOKING_CANCELLED,
                    "CUSTOMER VENUE_OPENING_HOURS_CHANGED " + booking.customerId());
            assertThat(customerCodes(booking)).as("booking %s", booking.bookingId())
                    .containsExactlyInAnyOrder("BOOKING_CREATED", "VENUE_OPENING_HOURS_CHANGED");
            assertThat(venueCodes(booking)).as("no venue notification for its own change")
                    .containsExactly("VENUE_NEW_BOOKING_REQUEST");
        }
        assertThat(notificationId(first, "VENUE_OPENING_HOURS_CHANGED"))
                .isNotEqualTo(notificationId(second, "VENUE_OPENING_HOURS_CHANGED"));
        assertThat(db.sql("""
                        SELECT booking_id FROM notification.notification
                         WHERE code = 'VENUE_OPENING_HOURS_CHANGED' AND recipient_type = 'CUSTOMER'
                           AND recipient_id = :customer""")
                .param("customer", first.customerId()).query(UUID.class).list())
                .as("one per booking, for the one customer")
                .containsExactlyInAnyOrder(first.bookingId(), second.bookingId());
        assertThat(notifications(manual)).as("no customer, and the venue made the change").isEmpty();
    }

    /**
     * E7-113 Phase 3C, end to end (FR-VEN-28): the REAL hours-change cancellation of an AWAITING booking closes its Late
     * Confirmation in the same command and writes one BookingCancelled - no Late Confirm event - so this one consumer
     * tells the customer once, VENUE_OPENING_HOURS_CHANGED, and nothing about the Late Confirmation; redelivery makes
     * nothing new.
     */
    @Test
    void theRealHoursChangeCancellationOfAnAwaitingBookingTellsTheCustomerOnceAndNothingAboutTheLateConfirm() {
        Booking booking = flows.lateConfirmed();
        List<String> eventsBefore = bookingEventTypes(booking);
        assertThat(eventsBefore).contains(IntegrationEvents.LATE_CONFIRM_INITIATED);

        cancelByHoursChange(booking.venueId(), booking.venueAccountId(), List.of(booking));

        // committed, read on another connection: the booking, its closed attempt and the one event it gained
        assertThat(db.sql("SELECT status || ' ' || terminal_reason FROM booking.booking WHERE id = :id")
                .param("id", booking.bookingId()).query(String.class).single())
                .isEqualTo("CANCELLED VENUE_OPENING_HOURS_CHANGED");
        assertThat(db.sql("""
                        SELECT closed_at IS NOT NULL AND customer_response IS NULL
                          FROM booking.late_confirmation WHERE booking_id = :id""")
                .param("id", booking.bookingId()).query(Boolean.class).single())
                .as("the attempt closed without a Customer response").isTrue();
        List<String> eventsAfter = bookingEventTypes(booking);
        assertThat(eventsAfter.subList(0, eventsBefore.size())).isEqualTo(eventsBefore);
        assertThat(eventsAfter.subList(eventsBefore.size(), eventsAfter.size()))
                .containsExactly(IntegrationEvents.BOOKING_CANCELLED);
        assertThat(outboxEvent(booking, IntegrationEvents.BOOKING_CANCELLED).text("terminalReason"))
                .isEqualTo("VENUE_OPENING_HOURS_CHANGED");
        assertThat(db.sql("SELECT count(*) FROM technical.outbox_event WHERE event_type ILIKE '%OpeningHours%'")
                .query(Long.class).single()).as("no event for the hours change itself").isZero();

        drainTwiceAndRedeliver(booking, IntegrationEvents.BOOKING_CANCELLED);

        assertMadeOnceBy(booking, IntegrationEvents.BOOKING_CANCELLED,
                "CUSTOMER VENUE_OPENING_HOURS_CHANGED " + booking.customerId());
        assertThat(notifications(booking)).containsExactlyInAnyOrderElementsOf(beforeLateConfirm(booking,
                "CUSTOMER LATE_CONFIRMATION_RECEIVED " + booking.customerId(),
                "CUSTOMER VENUE_OPENING_HOURS_CHANGED " + booking.customerId()));
        assertThat(customerCodes(booking)).as("no Late Confirm outcome is told")
                .doesNotContain("CUSTOMER_CONFIRMATION_EXPIRED", "LATE_CONFIRMATION_EXPIRING");
        assertThat(venueCodes(booking)).as("no venue notification for its own change")
                .doesNotContain("VENUE_LATE_CONFIRM_DECLINED", "VENUE_CUSTOMER_CONFIRMATION_EXPIRED",
                        "VENUE_LATE_CONFIRM_ACCEPTED");
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

    // ---------------------------------------------------------------------- E7-58 Late Confirm, once each

    /** §52.2 "Late Confirmation Received": the customer, from LateConfirmInitiated, with the venue's name. */
    @Test
    void aLateConfirmTellsTheCustomerOnce() {
        Booking booking = flows.lateConfirmed();

        drainTwiceAndRedeliver(booking, IntegrationEvents.LATE_CONFIRM_INITIATED);

        assertMadeOnceBy(booking, IntegrationEvents.LATE_CONFIRM_INITIATED,
                "CUSTOMER LATE_CONFIRMATION_RECEIVED " + booking.customerId());
        assertThat(notifications(booking)).containsExactlyInAnyOrderElementsOf(beforeLateConfirm(booking,
                "CUSTOMER LATE_CONFIRMATION_RECEIVED " + booking.customerId()));
        assertThat(venueName(booking, "LATE_CONFIRMATION_RECEIVED")).isEqualTo(booking.venueName());
    }

    /** §52.2 "Late Confirmation Expiring": the customer, from T3's LateConfirmWarningDue. */
    @Test
    void theLateConfirmWarningTellsTheCustomerOnce() {
        Booking booking = flows.lateConfirmed();
        flows.warnLateConfirm(booking);

        drainTwiceAndRedeliver(booking, IntegrationEvents.LATE_CONFIRM_WARNING_DUE);

        assertMadeOnceBy(booking, IntegrationEvents.LATE_CONFIRM_WARNING_DUE,
                "CUSTOMER LATE_CONFIRMATION_EXPIRING " + booking.customerId());
        assertThat(notifications(booking)).containsExactlyInAnyOrderElementsOf(beforeLateConfirm(booking,
                "CUSTOMER LATE_CONFIRMATION_RECEIVED " + booking.customerId(),
                "CUSTOMER LATE_CONFIRMATION_EXPIRING " + booking.customerId()));
    }

    /** §52.5 "Customer Accepted Late Confirmation": the venue only; no starts-soon reminder (PO decision 13 Oct). */
    @Test
    void anAcceptedLateConfirmTellsTheVenueOnceAndArmsNoStartsSoonReminder() {
        Booking booking = flows.lateConfirmed();
        flows.acceptLateConfirm(booking);

        drainTwiceAndRedeliver(booking, IntegrationEvents.LATE_CONFIRM_ACCEPTED);

        assertMadeOnceBy(booking, IntegrationEvents.LATE_CONFIRM_ACCEPTED,
                "VENUE VENUE_LATE_CONFIRM_ACCEPTED " + booking.venueId());
        assertThat(notifications(booking)).containsExactlyInAnyOrderElementsOf(beforeLateConfirm(booking,
                "CUSTOMER LATE_CONFIRMATION_RECEIVED " + booking.customerId(),
                "VENUE VENUE_LATE_CONFIRM_ACCEPTED " + booking.venueId()));
        assertThat(job(BookingReminders.bookingStartsSoonKey(booking.bookingId()))).isEmpty();
        assertThat(jobTypes(booking)).as("T4 BOOKING_START is the booking's own start job, not a reminder")
                .contains("BOOKING_START").doesNotContain(BookingReminders.BOOKING_STARTS_SOON);
    }

    /** §52.5 "Customer Declined Late Confirmation": the venue only. */
    @Test
    void aDeclinedLateConfirmTellsTheVenueOnce() {
        Booking booking = flows.lateConfirmed();
        flows.declineLateConfirm(booking);

        drainTwiceAndRedeliver(booking, IntegrationEvents.LATE_CONFIRM_DECLINED);

        assertMadeOnceBy(booking, IntegrationEvents.LATE_CONFIRM_DECLINED,
                "VENUE VENUE_LATE_CONFIRM_DECLINED " + booking.venueId());
        assertThat(notifications(booking)).containsExactlyInAnyOrderElementsOf(beforeLateConfirm(booking,
                "CUSTOMER LATE_CONFIRMATION_RECEIVED " + booking.customerId(),
                "VENUE VENUE_LATE_CONFIRM_DECLINED " + booking.venueId()));
    }

    /** §52.2 and §52.5 "Customer Confirmation Expired": ONE LateConfirmTimedOut, one notification for each side. */
    @Test
    void aTimedOutLateConfirmTellsTheCustomerAndTheVenueOnceEach() {
        Booking booking = flows.lateConfirmed();
        flows.timeOutLateConfirm(booking);

        drainTwiceAndRedeliver(booking, IntegrationEvents.LATE_CONFIRM_TIMED_OUT);

        assertMadeOnceBy(booking, IntegrationEvents.LATE_CONFIRM_TIMED_OUT,
                "CUSTOMER CUSTOMER_CONFIRMATION_EXPIRED " + booking.customerId(),
                "VENUE VENUE_CUSTOMER_CONFIRMATION_EXPIRED " + booking.venueId());
        assertThat(notifications(booking)).containsExactlyInAnyOrderElementsOf(beforeLateConfirm(booking,
                "CUSTOMER LATE_CONFIRMATION_RECEIVED " + booking.customerId(),
                "CUSTOMER CUSTOMER_CONFIRMATION_EXPIRED " + booking.customerId(),
                "VENUE VENUE_CUSTOMER_CONFIRMATION_EXPIRED " + booking.venueId()));
    }

    // ---------------------------------------------------------------------- E7-59 attendance, once each

    /** §52.2 "Check-in Recorded": the customer only, from the one CheckInRecorded. */
    @Test
    void aCheckInTellsTheCustomerOnce() {
        Booking booking = flows.active(Duration.ofMinutes(5));
        attendance.checkIn(booking.venueAccountId(), booking.bookingId());

        drainTwiceAndRedeliver(booking, IntegrationEvents.CHECK_IN_RECORDED);

        assertMadeOnceBy(booking, IntegrationEvents.CHECK_IN_RECORDED,
                "CUSTOMER CHECK_IN_RECORDED " + booking.customerId());
        assertThat(venueCodes(booking)).doesNotContain("CHECK_IN_RECORDED");
    }

    /** §52.2 "No-show Recorded": the customer only, from the one NoShowRecorded; never a "cancelled" code. */
    @Test
    void aNoShowTellsTheCustomerOnce() {
        Booking booking = flows.active(Duration.ofMinutes(20));
        attendance.noShow(booking.venueAccountId(), booking.bookingId());

        drainTwiceAndRedeliver(booking, IntegrationEvents.NO_SHOW_RECORDED);

        assertMadeOnceBy(booking, IntegrationEvents.NO_SHOW_RECORDED,
                "CUSTOMER NO_SHOW_RECORDED " + booking.customerId());
        assertThat(customerCodes(booking)).noneMatch(code -> code.contains("CANCELLED"));
        assertThat(outboxEvents(booking, "BookingNoShowRecorded")).isEmpty();
    }

    /** Mark Present - Late has no §52.2 notification of its own: its event creates nothing. */
    @Test
    void aLatePresentTellsNobody() {
        Booking booking = flows.active(Duration.ofMinutes(20));
        drain();
        List<String> before = notifications(booking);
        attendance.latePresent(booking.venueAccountId(), booking.bookingId());

        drainTwiceAndRedeliver(booking, IntegrationEvents.LATE_PRESENT_RECORDED);

        assertThat(notifications(booking)).containsExactlyInAnyOrderElementsOf(before);
    }

    /**
     * A no-show on a delayed start that would have cancelled (Amr Heiba, 17 Oct 2026): the start-time cancellation
     * tells the customer and the venue once each, by the established BookingCancelled path - never NO_SHOW_RECORDED.
     */
    @Test
    void aStartTimeCancellationBehindADelayedStartTellsEachSideOnce() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(20));
        flows.resourceDisabledBehindTheGuards(booking);
        assertThatThrownBy(() -> attendance.noShow(booking.venueAccountId(), booking.bookingId()))
                .isInstanceOf(ps.e7gz.common.error.ApiException.class);

        drainTwiceAndRedeliver(booking, IntegrationEvents.BOOKING_CANCELLED);

        assertMadeOnceBy(booking, IntegrationEvents.BOOKING_CANCELLED,
                "CUSTOMER BOOKING_AUTO_CANCELLED " + booking.customerId(),
                "VENUE VENUE_BOOKING_AUTO_CANCELLED " + booking.venueId());
        assertThat(customerCodes(booking)).doesNotContain("NO_SHOW_RECORDED");
    }

    // ------------------------------------------------------------- E7-60 start (T4)

    /**
     * E7-60: a start-time cancellation by T4 tells the customer and the venue once each (Spec v5 section 31.3: the
     * customer is notified; PO decision 12 Oct 2026: VENUE_BOOKING_AUTO_CANCELLED), even when redelivered.
     */
    @Test
    void aStartTimeCancellationByTheStartTellsEachSideOnce() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(5));
        flows.resourceDisabledBehindTheGuards(booking);
        assertThat(flows.fireStart(booking)).isTrue();

        drainTwiceAndRedeliver(booking, IntegrationEvents.BOOKING_CANCELLED);

        assertMadeOnceBy(booking, IntegrationEvents.BOOKING_CANCELLED,
                "CUSTOMER BOOKING_AUTO_CANCELLED " + booking.customerId(),
                "VENUE VENUE_BOOKING_AUTO_CANCELLED " + booking.venueId());
    }

    /** A Manual Booking cancelled at its start: there is no customer, so the venue alone is told, once. */
    @Test
    void aManualStartTimeCancellationTellsOnlyTheVenueOnce() {
        Booking booking = flows.manualConfirmedWithOverdueStart(Duration.ofSeconds(5));
        flows.resourceDisabledBehindTheGuards(booking);
        assertThat(flows.fireStart(booking)).isTrue();

        drainTwiceAndRedeliver(booking, IntegrationEvents.BOOKING_CANCELLED);

        assertMadeOnceBy(booking, IntegrationEvents.BOOKING_CANCELLED,
                "VENUE VENUE_BOOKING_AUTO_CANCELLED " + booking.venueId());
        assertThat(notifications(booking)).containsExactly("VENUE VENUE_BOOKING_AUTO_CANCELLED " + booking.venueId());
    }

    /** PO decision 13 Oct 2026: BookingStarted notifies nobody. */
    @Test
    void aStartTellsNobody() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofSeconds(5));
        drain();
        List<String> before = notifications(booking);
        assertThat(flows.fireStart(booking)).isTrue();

        drainTwiceAndRedeliver(booking, IntegrationEvents.BOOKING_STARTED);

        assertThat(notifications(booking)).isEqualTo(before);
    }

    // ------------------------------------------------------------- E7-58 Late Confirm warning, FR-LCF-12

    @Test
    void aWindowOverSixtySecondsHasItsWarningJobAndOneExpiringNotification() {
        Booking booking = flows.lateConfirmedStartingIn(Duration.ofSeconds(75));
        assertThat(job(BookingFlows.lateConfirmWarningKey(booking))).hasValueSatisfying(
                job -> assertThat(job.status()).isEqualTo("PENDING"));
        flows.warnLateConfirm(booking);

        drainTwiceAndRedeliver(booking, IntegrationEvents.LATE_CONFIRM_WARNING_DUE);

        assertThat(customerCodes(booking)).containsOnlyOnce("LATE_CONFIRMATION_EXPIRING");
    }

    /**
     * A window of 60 seconds or less gets no T3 job, so no LateConfirmWarningDue and no LATE_CONFIRMATION_EXPIRING,
     * even after it times out. The window here starts 60 s (and 30 s) before the start, so it is at most 60 s: the
     * exact 60.000 s instant needs a fixed command instant and is the E7-45 boundary test
     * {@code LateConfirmBoundaryTest.initiatedAt759ForAnEightPmStartTheWindowIsExactlySixtySecondsWithNoWarning}.
     */
    @Test
    void aWindowOfSixtySecondsOrLessHasNoWarningJobAndNoExpiringNotification() {
        for (Duration startIn : List.of(Duration.ofSeconds(60), Duration.ofSeconds(30))) {
            Booking booking = flows.lateConfirmedStartingIn(startIn);
            assertThat(job(BookingFlows.lateConfirmWarningKey(booking))).as("%s: no T3 job", startIn).isEmpty();
            flows.timeOutLateConfirm(booking);

            drain();

            assertThat(outboxEvents(booking, IntegrationEvents.LATE_CONFIRM_WARNING_DUE)).as("%s", startIn)
                    .isEmpty();
            assertThat(customerCodes(booking)).as("%s", startIn).doesNotContain("LATE_CONFIRMATION_EXPIRING")
                    .contains("CUSTOMER_CONFIRMATION_EXPIRED");
        }
    }

    // ------------------------------------------------------------- E7-58 venue name and privacy

    /** PO decision 13 Oct 2026: a Late Confirm event's venue name is the one the customer sees, even after a rename. */
    @Test
    void aLateConfirmNotificationKeepsTheVenueNameOfItsEventAfterARename() {
        Booking booking = flows.lateConfirmed();
        BookingFlows.renameVenue(booking.venueId(), "Renamed after the Late Confirm");

        drain();

        assertThat(venueName(booking, "LATE_CONFIRMATION_RECEIVED")).isEqualTo(booking.venueName());
    }

    /** An event without a venue name (BookingConfirmed) falls back to the venue name when the notification is made. */
    @Test
    void anEventWithoutAVenueNameUsesTheVenuesNameNow() {
        Booking booking = flows.pending();
        flows.accept(booking);
        assertThat(outboxEvent(booking, IntegrationEvents.BOOKING_CONFIRMED).payload().has("venueName")).isFalse();
        BookingFlows.renameVenue(booking.venueId(), "Renamed before delivery");

        drain();

        assertThat(venueName(booking, "BOOKING_CONFIRMED")).isEqualTo("Renamed before delivery");
    }

    /**
     * F1 / PCR-008 and NFR-19: a conflicting Accept is the 409 only - no event, no notification, no
     * LATE_CONFIRM_SCHEDULE_CONFLICT - and no venue Late Confirm notification carries any parameter about the customer.
     */
    @Test
    void aConflictingAcceptNotifiesNobodyAndVenueNotificationsCarryNothingAboutTheCustomer() {
        Booking conflicting = flows.lateConfirmedOverlappingAnotherBooking();
        flows.acceptLateConfirmConflicting(conflicting);
        Booking declined = flows.lateConfirmed();
        flows.declineLateConfirm(declined);
        Booking timedOut = flows.lateConfirmed();
        flows.timeOutLateConfirm(timedOut);

        drain();

        assertThat(notifications(conflicting)).containsExactlyInAnyOrderElementsOf(beforeLateConfirm(conflicting,
                "CUSTOMER LATE_CONFIRMATION_RECEIVED " + conflicting.customerId()));
        assertThat(db.sql("SELECT count(*) FROM notification.notification WHERE code = :code")
                .param("code", "LATE_CONFIRM_SCHEDULE_CONFLICT")
                .query(Long.class).single()).isZero();
        for (Booking booking : List.of(declined, timedOut)) {
            assertThat(db.sql("""
                            SELECT params::text FROM notification.notification
                             WHERE booking_id = :id AND recipient_type = 'VENUE'""")
                    .param("id", booking.bookingId()).query(String.class).list())
                    .isNotEmpty().allSatisfy(params -> assertThat(params).isEqualTo("{}"));
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The notifications a Late Confirm booking already had from its request and its expiry (E7-46, E7-47). */
    private static List<String> beforeLateConfirm(Booking booking, String... lateConfirm) {
        List<String> all = new ArrayList<>(List.of(
                "CUSTOMER BOOKING_CREATED " + booking.customerId(),
                "VENUE VENUE_NEW_BOOKING_REQUEST " + booking.venueId(),
                "CUSTOMER VENUE_RESPONSE_EXPIRED " + booking.customerId()));
        all.addAll(List.of(lateConfirm));
        return all;
    }

    /**
     * Every event handled twice by the dispatcher, then the booking's stored event of {@code type} handed to the
     * consumer once more: a redelivery of the same outbox event.
     */
    private void drainTwiceAndRedeliver(Booking booking, String type) {
        OutboxDrain.drain(appDataSource, json, twice(consumer));
        consumer.handle(outboxEvent(booking, type));
    }

    /** {@code expected} ("RECIPIENT_TYPE CODE recipientId") each exist once, created by the booking's one event. */
    private void assertMadeOnceBy(Booking booking, String type, String... expected) {
        OutboxEvent event = outboxEvent(booking, type);
        for (String notification : expected) {
            String[] parts = notification.split(" ");
            assertThat(notifications(booking)).as(notification).containsOnlyOnce(notification);
            assertThat(notificationId(booking, parts[1])).as("%s made by %s %d", parts[1], type, event.id())
                    .isEqualTo(BookingNotifier.id("outbox:" + event.id(), parts[1], UUID.fromString(parts[2])));
        }
    }

    /** The booking's one stored event of {@code type}, as the dispatcher hands it to a consumer. */
    private OutboxEvent outboxEvent(Booking booking, String type) {
        List<OutboxEvent> events = outboxEvents(booking, type);
        assertThat(events).as(type).hasSize(1);
        return events.getFirst();
    }

    private List<OutboxEvent> outboxEvents(Booking booking, String type) {
        return db.sql("""
                        SELECT id, event_type, aggregate_type, aggregate_id, payload::text AS payload, correlation_id,
                               created_at, attempt
                          FROM technical.outbox_event
                         WHERE aggregate_id = :id AND event_type = :type""")
                .param("id", booking.bookingId()).param("type", type)
                .query((rs, n) -> new OutboxEvent(rs.getLong("id"), rs.getString("event_type"),
                        rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
                        payload(rs.getString("payload")), rs.getObject("correlation_id", UUID.class),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getInt("attempt")))
                .list();
    }

    private JsonNode payload(String text) {
        try {
            return json.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> jobTypes(Booking booking) {
        return db.sql("SELECT type FROM technical.scheduled_job WHERE subject_id = :id")
                .param("id", booking.bookingId()).query(String.class).list();
    }

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

    /**
     * E7-113: the bookings of one venue cancelled by an hours change through the real Booking port, in one committed
     * command under the locks its caller must hold - the venue, the bookings' Resources and their Customers.
     */
    private void cancelByHoursChange(UUID venueId, UUID venueAccountId, List<Booking> bookings) {
        LockSet.Builder locks = LockSet.builder().venues(venueId);
        for (Booking booking : bookings) {
            locks.resources(booking.resourceId());
            if (booking.customerId() != null) {
                locks.users(booking.customerId());
            }
        }
        runner.run(TestCommands.under(CommandScope.shared(locks.build()), command -> {
            hoursChangeBookings.cancel(command, venueId, venueAccountId,
                    bookings.stream().map(Booking::bookingId).toList());
            return null;
        }));
    }

    /** The types of the booking's own events (aggregate = the booking), oldest first. */
    private List<String> bookingEventTypes(Booking booking) {
        return db.sql("SELECT event_type FROM technical.outbox_event WHERE aggregate_id = :id ORDER BY id")
                .param("id", booking.bookingId()).query(String.class).list();
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
