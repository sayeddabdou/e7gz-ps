package ps.e7gz.tournament.internal;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.scheduling.api.CommandJobHandler;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;

/**
 * T7, {@code TOURNAMENT_APPROVAL_EXPIRY:{tournamentId}} (arch 08 section 1, FR-TRN-10; E7-92), scheduled by the request's
 * creation at {@code approvalExpiresAt}. Fired by the deadline worker (E7-40) in one command under the Tournament's
 * locks - {@code begin_command} adds its Venue, Resources and people - and decided by {@link TournamentApproval#expire}.
 * No {@code @Scheduled}: every timer is a {@code technical.scheduled_job}, and Tournament timers never pause.
 */
@Component
@Profile("worker")
class TournamentApprovalExpiryHandler implements CommandJobHandler {

    private final TournamentApproval approval;

    TournamentApprovalExpiryHandler(TournamentApproval approval) {
        this.approval = approval;
    }

    @Override
    public String type() {
        return Tournaments.APPROVAL_EXPIRY;
    }

    /** Ending a request the Venue already received, not new marketplace activity (ADR-022). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
    }

    @Override
    public LockSet lockSet(ScheduledJob job) {
        return LockSet.builder().tournaments(job.subjectId()).build();
    }

    @Override
    public JobOutcome fire(CommandContext context, ScheduledJob job) {
        return approval.expire(context, job);
    }
}
