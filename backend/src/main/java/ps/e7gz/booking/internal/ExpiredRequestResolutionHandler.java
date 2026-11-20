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
 * T6, {@code VENUE_RESPONSE_EXPIRED_RESOLUTION:{bookingId}} (arch 08 section 1, FR-BOK-23; E7-118), scheduled at
 * {@code start_at} by every PENDING -> VENUE_RESPONSE_EXPIRED. Fired by the deadline worker (E7-40) in one command with
 * the booking's locks; the decision is {@link ExpiredRequestResolutions#resolve}.
 */
@Component
@Profile("worker")
class ExpiredRequestResolutionHandler implements CommandJobHandler {

    private final CommandRunner runner;
    private final VenueAnswerStore store;
    private final ExpiredRequestResolutions resolutions;

    ExpiredRequestResolutionHandler(CommandRunner runner, VenueAnswerStore store,
                                    ExpiredRequestResolutions resolutions) {
        this.runner = runner;
        this.store = store;
        this.resolutions = resolutions;
    }

    @Override
    public String type() {
        return ExpiredRequestResolutions.VENUE_RESPONSE_EXPIRED_RESOLUTION;
    }

    /** Ending the customer's existing request at its start, not new marketplace activity (ADR-022). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
    }

    /**
     * The booking's Customer, Venue and Resource - the locks of a Late Confirm initiation and Reject After Expiry, so
     * each of them and T6 are serialised.
     */
    @Override
    public LockSet lockSet(ScheduledJob job) {
        return runner.run(new FindBooking(store, job.subjectId()))
                .map(keys -> keys.scope().lockSet())
                .orElse(LockSet.empty());
    }

    @Override
    public JobOutcome fire(CommandContext context, ScheduledJob job) {
        return resolutions.resolve(context, job);
    }
}
