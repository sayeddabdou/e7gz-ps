package ps.e7gz.notification.internal;

import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.notification.api.BookingReminders;
import ps.e7gz.notification.api.NotifiedBookings.NotifiedBooking;
import ps.e7gz.notification.api.NotifiedBookings;
import ps.e7gz.notification.internal.BookingNotificationRules.Recipient;
import ps.e7gz.notification.internal.BookingNotificationRules.Send;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;

/**
 * {@code BOOKING_STARTS_SOON} (Spec v5 section 52.2): the customer is told 20 minutes before {@code startAt} of a
 * CONFIRMED Online Booking (PO decision 12 Oct 2026, Sprint 3 review), once per booking, from the stored
 * {@code startAt}. Scheduled by {@link BookingNotificationConsumer} on the Accept's {@code BookingConfirmed}; a booking
 * confirmed less than 20 minutes before its start gets none, and a Manual Booking has no customer and gets none.
 * Superseded when the booking is no longer CONFIRMED (cancelled, started) or has started.
 */
@Component
@Profile("worker")
class BookingStartsSoonHandler extends BookingReminderHandler {

    static final Duration LEAD = Duration.ofMinutes(BookingReminders.BOOKING_STARTS_SOON_LEAD_MINUTES);

    static final Send SEND = new Send(Recipient.CUSTOMER, "BOOKING_STARTS_SOON");

    BookingStartsSoonHandler(CommandRunner runner, NotifiedBookings bookings, BookingNotifier notifier) {
        super(runner, bookings, notifier);
    }

    @Override
    public String type() {
        return BookingReminders.BOOKING_STARTS_SOON;
    }

    @Override
    JobOutcome fire(CommandContext context, ScheduledJob job, NotifiedBooking booking) {
        if (booking.source() != BookingSource.ONLINE || booking.customerId() == null
                || booking.status() != BookingStatus.CONFIRMED) {
            return JobOutcome.superseded("no longer CONFIRMED");
        }
        Instant due = booking.startAt().minus(LEAD);
        Instant now = context.commandNow();
        if (now.isBefore(due)) {
            return JobOutcome.rescheduleAt(due);
        }
        if (!now.isBefore(booking.startAt())) {
            return JobOutcome.superseded("the booking has started");
        }
        send(context, job, SEND, booking);
        return JobOutcome.done();
    }
}
