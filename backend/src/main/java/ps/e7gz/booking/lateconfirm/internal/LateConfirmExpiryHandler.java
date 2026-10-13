package ps.e7gz.booking.lateconfirm.internal;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.scheduling.api.CommandJobHandler;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;

/**
 * T2, {@code LATE_CONFIRM_EXPIRY:{bookingId}} (arch 08 section 1, FR-LCF-09, FR-LCF-13), scheduled by Initiate at the
 * attempt's {@code expires_at}. Fired by the deadline worker (E7-40) in one command with the booking's locks; the
 * decision is {@link LateConfirmCommands#expire}. Public, with a public constructor, so a test can build it by hand
 * (the worker profile is off in tests, E7-45 {@code DueJobs}).
 */
@Component
@Profile("worker")
public class LateConfirmExpiryHandler implements CommandJobHandler {

    private final LateConfirmations lateConfirmations;

    public LateConfirmExpiryHandler(LateConfirmations lateConfirmations) {
        this.lateConfirmations = lateConfirmations;
    }

    @Override
    public String type() {
        return LateConfirmWindow.EXPIRY_JOB;
    }

    /** Honouring an existing commitment's lifecycle, not new marketplace activity (FR-LCF-01, ADR-022). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
    }

    /** The booking's Customer, Venue and Resource - the same locks as Initiate, Accept and Decline. */
    @Override
    public LockSet lockSet(ScheduledJob job) {
        return lateConfirmations.lockSetOf(job.subjectId());
    }

    @Override
    public JobOutcome fire(CommandContext context, ScheduledJob job) {
        return lateConfirmations.expire(context, job);
    }
}
