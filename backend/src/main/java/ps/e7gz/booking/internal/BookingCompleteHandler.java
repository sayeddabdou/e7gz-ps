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
 * T5, {@code BOOKING_COMPLETE:{bookingId}} (arch 08 section 1, arch 06 section 2.3), scheduled at {@code end_at} by
 * every start (E7-60, D14) and by a walk-in Manual Booking (E7-48, D8). Fired by the deadline worker (E7-40) in one
 * command with the booking's locks; the decision is {@link BookingCompletions#complete} (E7-61).
 */
@Component
@Profile("worker")
class BookingCompleteHandler implements CommandJobHandler {

    private final CommandRunner runner;
    private final VenueAnswerStore store;
    private final BookingCompletions completions;

    BookingCompleteHandler(CommandRunner runner, VenueAnswerStore store, BookingCompletions completions) {
        this.runner = runner;
        this.store = store;
        this.completions = completions;
    }

    @Override
    public String type() {
        return ManualBookingCommand.BOOKING_COMPLETE;
    }

    /** Honouring an existing commitment, not new marketplace activity (ADR-022). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
    }

    /**
     * The booking's Customer (none for a Manual Booking; with every Booking of theirs, which the Trust and Loyalty
     * writes need, {@code trust.command_now}), Venue and Resource - the locks of the venue's attendance actions and of
     * the PCR-007 cancel, so each of them and the completion are serialised.
     */
    @Override
    public LockSet lockSet(ScheduledJob job) {
        return runner.run(new FindBooking(store, job.subjectId()))
                .map(keys -> keys.scope().lockSet())
                .orElse(LockSet.empty());
    }

    @Override
    public JobOutcome fire(CommandContext context, ScheduledJob job) {
        return completions.complete(context, job);
    }
}
