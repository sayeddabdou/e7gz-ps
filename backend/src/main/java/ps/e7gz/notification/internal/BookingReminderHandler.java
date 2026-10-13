package ps.e7gz.notification.internal;

import java.util.Optional;
import java.util.UUID;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.notification.api.NotifiedBookings.NotifiedBooking;
import ps.e7gz.notification.api.NotifiedBookings;
import ps.e7gz.notification.internal.BookingNotificationRules.Send;
import ps.e7gz.scheduling.api.CommandJobHandler;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;

/**
 * A booking reminder timer ({@code ps.e7gz.notification.api.BookingReminders}, E7-55). Fired in one command under the
 * booking's Customer, Venue and Resource locks - those of the venue's answer, the customer's cancel and T1 - it re-reads
 * the booking and decides from it and the command instant, never from when the worker ran (arch 08 section 2.2): no
 * longer applicable - superseded; its due instant moved later - rescheduled; otherwise the notification, created once
 * per job, and {@code DONE} in the same transaction.
 */
abstract class BookingReminderHandler implements CommandJobHandler {

    private final CommandRunner runner;
    private final NotifiedBookings bookings;
    private final BookingNotifier notifier;

    BookingReminderHandler(CommandRunner runner, NotifiedBookings bookings, BookingNotifier notifier) {
        this.runner = runner;
        this.bookings = bookings;
        this.notifier = notifier;
    }

    /** Honouring an existing booking, not new marketplace activity (ADR-022). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
    }

    @Override
    public LockSet lockSet(ScheduledJob job) {
        return runner.run(new Find(bookings, job.subjectId()))
                .map(snapshot -> snapshot.scope().lockSet())
                .orElse(LockSet.empty());
    }

    @Override
    public JobOutcome fire(CommandContext context, ScheduledJob job) {
        return bookings.find(context, job.subjectId())
                .map(snapshot -> fire(context, job, snapshot))
                .orElseGet(() -> JobOutcome.superseded("no such booking"));
    }

    abstract JobOutcome fire(CommandContext context, ScheduledJob job, NotifiedBooking booking);

    /** The reminder's notification, at most once per job. */
    void send(CommandContext context, ScheduledJob job, Send send, NotifiedBooking booking) {
        notifier.send(context, job.idempotencyKey(), send, booking);
    }

    /** Read-only, before the fire command: a booking never changes Customer, Venue or Resource. */
    private record Find(NotifiedBookings bookings, UUID bookingId) implements Command<Optional<NotifiedBooking>> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public Optional<NotifiedBooking> execute(CommandContext context) {
            return bookings.find(context, bookingId);
        }
    }
}
