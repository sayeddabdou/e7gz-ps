package ps.e7gz.booking.internal;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.scheduling.api.CommandJobHandler;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;

/**
 * T4, {@code BOOKING_START:{bookingId}} (arch 08 section 1, FR-BOK-24), scheduled at {@code start_at} by the venue's
 * Accept (E7-47), a Late Confirm Accept (E7-58) and a future Manual Booking (E7-48). Fired by the deadline worker
 * (E7-40) in one command with the booking's locks; the decision is {@link BookingStarts#start}.
 */
@Component
@Profile("worker")
class BookingStartHandler implements CommandJobHandler {

    private final CommandRunner runner;
    private final VenueAnswerStore store;
    private final BookingStarts starts;

    BookingStartHandler(CommandRunner runner, VenueAnswerStore store, BookingStarts starts) {
        this.runner = runner;
        this.store = store;
        this.starts = starts;
    }

    @Override
    public String type() {
        return VenueAnswers.BOOKING_START;
    }

    /** Honouring an existing commitment, not new marketplace activity (ADR-022). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
    }

    /**
     * The booking's Customer (none for a Manual Booking), Venue and Resource - the locks of the customer's cancel, the
     * venue's attendance actions and the delayed-start no-show, so each of them and the start are serialised.
     */
    @Override
    public LockSet lockSet(ScheduledJob job) {
        return runner.run(new FindBooking(store, job.subjectId()))
                .map(keys -> keys.scope().lockSet())
                .orElse(LockSet.empty());
    }

    @Override
    public JobOutcome fire(CommandContext context, ScheduledJob job) {
        return starts.start(context, job);
    }
}
