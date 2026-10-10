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
 * T1, {@code VENUE_RESPONSE_EXPIRY:{bookingId}} (arch 08 section 1, FR-BOK-18), scheduled by online booking creation
 * at {@code venue_response_expires_at}. Fired by the deadline worker (E7-40) in one command with the booking's locks;
 * the decision is {@link VenueAnswers#expire}. No {@code @Scheduled}: every timer is a {@code technical.scheduled_job}.
 */
@Component
@Profile("worker")
class VenueResponseExpiryHandler implements CommandJobHandler {

    private final CommandRunner runner;
    private final VenueAnswerStore store;
    private final VenueAnswers answers;

    VenueResponseExpiryHandler(CommandRunner runner, VenueAnswerStore store, VenueAnswers answers) {
        this.runner = runner;
        this.store = store;
        this.answers = answers;
    }

    @Override
    public String type() {
        return CreateOnlineBookingCommand.VENUE_RESPONSE_EXPIRY;
    }

    /** Honouring the venue's existing request lifecycle, not new marketplace activity (ADR-022). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
    }

    /** The booking's Customer, Venue and Resource - the same locks as the venue's answer and the customer's cancel. */
    @Override
    public LockSet lockSet(ScheduledJob job) {
        return runner.run(new FindBooking(store, job.subjectId()))
                .map(keys -> keys.scope().lockSet())
                .orElse(LockSet.empty());
    }

    @Override
    public JobOutcome fire(CommandContext context, ScheduledJob job) {
        return answers.expire(context, job);
    }
}
