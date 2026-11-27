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
 * T9, {@code TOURNAMENT_START:{tournamentId}} at {@code startAt} (FR-TRN-17, FR-TRN-26); E7-93), scheduled by the Tournament's creation. Fired by the deadline worker (E7-40) in one command under the
 * Tournament's locks - {@code begin_command} adds its Venue, Resources and people - and decided as of its boundary by
 * {@link TournamentBoundaries#fire}, never as of the worker's time. No {@code @Scheduled}: every timer is a
 * {@code technical.scheduled_job}, and Tournament timers never pause.
 */
@Component
@Profile("worker")
class TournamentStartHandler implements CommandJobHandler {

    private final TournamentBoundaries boundaries;

    TournamentStartHandler(TournamentBoundaries boundaries) {
        this.boundaries = boundaries;
    }

    @Override
    public String type() {
        return Tournaments.START;
    }

    /** Honouring a Tournament already accepted, not new marketplace activity (ADR-022, Spec section 37.5). */
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
        return boundaries.fire(context, job, Tournaments.START);
    }
}
