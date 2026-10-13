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
 * T3, {@code LATE_CONFIRM_WARNING:{bookingId}} (arch 08 sections 1 and 2.6, FR-LCF-12), written by Initiate only for a
 * window longer than 60 seconds, due 60 seconds before the deadline. Fired by the deadline worker (E7-40) in one
 * command with the booking's locks; the decision is {@link LateConfirmCommands#warn}. Its event is delivered by E7-55's
 * notification consumer; nothing here notifies anyone.
 */
@Component
@Profile("worker")
public class LateConfirmWarningHandler implements CommandJobHandler {

    private final LateConfirmations lateConfirmations;

    public LateConfirmWarningHandler(LateConfirmations lateConfirmations) {
        this.lateConfirmations = lateConfirmations;
    }

    @Override
    public String type() {
        return LateConfirmWindow.WARNING_JOB;
    }

    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
    }

    @Override
    public LockSet lockSet(ScheduledJob job) {
        return lateConfirmations.lockSetOf(job.subjectId());
    }

    @Override
    public JobOutcome fire(CommandContext context, ScheduledJob job) {
        return lateConfirmations.warn(context, job);
    }
}
