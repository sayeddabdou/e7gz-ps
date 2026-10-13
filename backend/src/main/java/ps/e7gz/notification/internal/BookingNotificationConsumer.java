package ps.e7gz.notification.internal;

import java.time.Instant;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.messaging.api.PermanentDeliveryFailure;
import ps.e7gz.notification.api.BookingReminders;
import ps.e7gz.notification.api.NotifiedBookings.NotifiedBooking;
import ps.e7gz.notification.api.NotifiedBookings;
import ps.e7gz.notification.internal.BookingNotificationRules.Plan;
import ps.e7gz.notification.internal.BookingNotificationRules.Reminder;
import ps.e7gz.notification.internal.BookingNotificationRules.Send;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJobs;

/**
 * The one notification path for booking, Late Confirm and attendance events (E7-55; PO decision 12 Oct 2026, Sprint 3
 * review): the outbox dispatcher hands it each committed event of a type in {@link BookingNotificationRules#RULES}, and
 * one command creates that event's notifications and starts its reminder (arch 08 section 4.1). The notifications commit
 * together, after the event's own command - never for a command that rolled back (FR-NTF-12).
 *
 * <p>Idempotent (a retry of the event runs every consumer again): each notification's id is derived from the event, so
 * a second run creates nothing ({@link BookingNotifier}), and a reminder has a natural job key, which
 * {@link ScheduledJobs#schedule} keeps to one job.
 */
@Component
@Profile("worker")
class BookingNotificationConsumer implements OutboxConsumer {

    static final String BOOKING_SUBJECT = "BOOKING";

    private final CommandRunner runner;
    private final BookingNotifier notifier;
    private final NotifiedBookings bookings;
    private final ScheduledJobs jobs;

    BookingNotificationConsumer(CommandRunner runner, BookingNotifier notifier, NotifiedBookings bookings,
                                ScheduledJobs jobs) {
        this.runner = runner;
        this.notifier = notifier;
        this.bookings = bookings;
        this.jobs = jobs;
    }

    @Override
    public Set<String> eventTypes() {
        return BookingNotificationRules.eventTypes();
    }

    @Override
    public void handle(OutboxEvent event) {
        Plan plan = BookingNotificationRules.planFor(event);
        if (!plan.isEmpty()) {
            runner.run(new Deliver(event, plan));
        }
    }

    /** The event's notifications and reminder, in one transaction. Locks nothing: it writes only its own rows. */
    private final class Deliver implements Command<Void> {

        private final OutboxEvent event;
        private final Plan plan;

        private Deliver(OutboxEvent event, Plan plan) {
            this.event = event;
            this.plan = plan;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public Void execute(CommandContext context) {
            NotifiedBooking found = bookings.find(context, event.uuid("bookingId"))
                    .orElseThrow(() -> new PermanentDeliveryFailure(event.eventType() + " " + event.id()
                            + " names a booking that does not exist"));
            // The venue name at the event when the event carries it (E7-58's Late Confirm events), else the name now
            // (PO decision 13 Oct 2026)
            String eventVenueName = event.payload().path(BookingNotifier.VENUE_NAME).asText("");
            NotifiedBooking booking = eventVenueName.isBlank() ? found : found.withVenueName(eventVenueName);
            for (Send send : plan.sends()) {
                notifier.send(context, "outbox:" + event.id(), send, booking);
            }
            plan.reminder().ifPresent(reminder -> schedule(context, reminder, booking));
            return null;
        }

        private void schedule(CommandContext context, Reminder reminder, NotifiedBooking booking) {
            switch (reminder) {
                case VENUE_PENDING_REQUEST_EXPIRING -> {
                    // answered, cancelled or expired before this event was handled: nothing to remind of
                    if (booking.source() == BookingSource.ONLINE && booking.status() == BookingStatus.PENDING
                            && booking.venueResponseExpiresAt() != null) {
                        jobs.schedule(context, new NewJob(BookingReminders.VENUE_PENDING_REQUEST_EXPIRING,
                                BOOKING_SUBJECT, booking.bookingId(),
                                booking.venueResponseExpiresAt().minus(VenuePendingRequestExpiringHandler.LEAD),
                                BookingReminders.venuePendingRequestExpiringKey(booking.bookingId())));
                    }
                }
                case BOOKING_STARTS_SOON -> {
                    Instant due = booking.startAt().minus(BookingStartsSoonHandler.LEAD);
                    // Confirmed less than 20 minutes before its start: no reminder (PO decision 12 Oct 2026)
                    if (!event.occurredAt().isAfter(due)) {
                        jobs.schedule(context, new NewJob(BookingReminders.BOOKING_STARTS_SOON, BOOKING_SUBJECT,
                                booking.bookingId(), due, BookingReminders.bookingStartsSoonKey(booking.bookingId())));
                    }
                }
            }
        }
    }
}
