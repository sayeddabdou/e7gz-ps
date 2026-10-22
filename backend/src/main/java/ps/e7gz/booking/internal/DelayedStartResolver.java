package ps.e7gz.booking.internal;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ps.e7gz.booking.api.AttendanceBookings;
import ps.e7gz.booking.internal.StartTimeResolution.Outcome;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.scheduling.api.ScheduledJobs;

/**
 * The delayed start behind a venue's attendance action (decisions of Amr Heiba, 17 Oct 2026; PO decision 19 Oct 2026,
 * R1): a CONFIRMED Online Booking past its {@code startAt} whose BOOKING_START (T4) is still waiting is resolved here
 * exactly as T4 would have resolved it at {@code startAt} - by {@link StartTimeResolution}, and when it starts by
 * {@link BookingStarts#start}, T4's own transition, never a second version of either. Runs inside the check-in's, Late
 * Present's or no-show's command, under the booking's Customer, Venue and Resource locks; see
 * {@link AttendanceBookings#resolveDelayedStart} for the four answers.
 *
 * <p>The start-time cancellation is {@link StartTimeCancellation}, the same one E7-60's BOOKING_START handler writes:
 * dated the stored {@code startAt}, with its full refund, {@code BookingCancelled} and, for
 * {@code SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR}, the SEV_1 alert.
 */
@Component
class DelayedStartResolver {

    private static final Logger log = LoggerFactory.getLogger(DelayedStartResolver.class);

    private final VenueAnswerStore store;
    private final StartTimeResolution resolution;
    private final StartTimeCancellation cancellation;
    private final BookingStarts starts;
    private final ScheduledJobs jobs;

    DelayedStartResolver(VenueAnswerStore store, StartTimeResolution resolution, StartTimeCancellation cancellation,
                         BookingStarts starts, ScheduledJobs jobs) {
        this.store = store;
        this.resolution = resolution;
        this.cancellation = cancellation;
        this.starts = starts;
        this.jobs = jobs;
    }

    AttendanceBookings.DelayedStart resolve(CommandContext context, UUID bookingId) {
        Optional<Facts> found = store.facts(context, bookingId);
        if (found.isEmpty() || found.get().source() != BookingSource.ONLINE
                || found.get().status() != BookingStatus.CONFIRMED
                || context.commandNow().isBefore(found.get().startAt())) {
            return AttendanceBookings.DelayedStart.NOT_DELAYED;
        }
        Facts booking = found.get();
        // Read-only first: an undetermined start writes nothing, not even the job's withdrawal
        Outcome outcome = resolution.resolve(context, booking);
        if (outcome instanceof Outcome.Undetermined undetermined) {
            log.warn("Delayed start of booking {} not resolved: {} (correlationId={})", bookingId, undetermined.why(),
                    CorrelationId.current().map(UUID::toString).orElse("none"));
            return AttendanceBookings.DelayedStart.UNDETERMINED;
        }
        // The proof of the race: the booking's own start job still waiting, withdrawn now so it can never fire
        if (!jobs.supersede(context, ManualBookingCommand.bookingStartKey(bookingId))) {
            return AttendanceBookings.DelayedStart.NOT_DELAYED;
        }
        return switch (outcome) {
            case Outcome.Start start -> {
                starts.start(context, booking);
                yield AttendanceBookings.DelayedStart.STARTED;
            }
            case Outcome.Cancel cancel -> {
                cancellation.cancel(context, booking, cancel);
                yield AttendanceBookings.DelayedStart.CANCELLED;
            }
            case Outcome.Undetermined undetermined -> throw new IllegalStateException("handled above");
        };
    }
}
