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
 * {@code VENUE_PENDING_REQUEST_EXPIRING} (Spec v5 section 52.5 "Pending Request Expiring", FR-NTF-09): the venue is
 * told at {@code venue_response_expires_at} - 3 minutes, minute 7 of the 10-minute window (PO decision 12 Oct 2026).
 * Scheduled by {@link BookingNotificationConsumer} on {@code BookingCreated}.
 *
 * <p>Superseded, as T1 is, once the booking is no longer a PENDING Online Booking - Accept, Reject, customer cancel,
 * expiry - or when the deadline itself has come (T1 expires it; no warning after the fact). A deadline that moved later
 * reschedules it. A paused response timer (FR-OPS-34): like T1, the pause moves this job to PAUSED and the resume
 * re-arms it (E7-80, {@link BookingReminders}); fired while paused anyway, it throws and changes nothing, so it backs
 * off and stays re-armable.
 */
@Component
@Profile("worker")
class VenuePendingRequestExpiringHandler extends BookingReminderHandler {

    static final Duration LEAD = Duration.ofMinutes(BookingReminders.VENUE_PENDING_REQUEST_EXPIRING_LEAD_MINUTES);

    static final Send SEND = new Send(Recipient.VENUE, "VENUE_PENDING_REQUEST_EXPIRING");

    VenuePendingRequestExpiringHandler(CommandRunner runner, NotifiedBookings bookings, BookingNotifier notifier) {
        super(runner, bookings, notifier);
    }

    @Override
    public String type() {
        return BookingReminders.VENUE_PENDING_REQUEST_EXPIRING;
    }

    @Override
    JobOutcome fire(CommandContext context, ScheduledJob job, NotifiedBooking booking) {
        if (booking.source() != BookingSource.ONLINE || booking.status() != BookingStatus.PENDING) {
            return JobOutcome.superseded("no longer PENDING");
        }
        if (booking.responsePaused()) {
            throw new IllegalStateException(type() + " fired for booking " + booking.bookingId()
                    + " while its response timer is paused");
        }
        Instant deadline = booking.venueResponseExpiresAt();
        Instant due = deadline.minus(LEAD);
        Instant now = context.commandNow();
        if (now.isBefore(due)) {
            return JobOutcome.rescheduleAt(due);
        }
        if (!now.isBefore(deadline)) {
            return JobOutcome.superseded("the response deadline has come");
        }
        send(context, job, SEND, booking);
        return JobOutcome.done();
    }
}
