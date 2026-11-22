package ps.e7gz.operations.internal;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.operations.api.EmergencyBookings;
import ps.e7gz.scheduling.api.CommandJobHandler;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;

/**
 * {@code TIMER_PAUSE_CHECK:{bookingId}:{due}} (E7-83; arch 08 section 2.5, FR-OPS-37…FR-OPS-40): a paused timer decided
 * again at the next instant its decision can change - the 30-minute cap, the last feasible resume, the next online
 * intake window - or at once when an Emergency resolution could not decide it under its own locks. The decision is
 * {@link TimerPauses#reconsider}, from the stored state and the command instant, never from when the job ran: the cap
 * cancels at the instant it was reached. A timer no longer paused (resumed, rejected, declined, cancelled) supersedes
 * the job; one still waiting writes the next check (one per booking and instant) and this job is done.
 */
@Component
@Profile("worker")
class TimerPauseCheckHandler implements CommandJobHandler {

    private final CommandRunner runner;
    private final TimerPauses timers;
    private final EmergencyBookings bookings;

    TimerPauseCheckHandler(CommandRunner runner, TimerPauses timers, EmergencyBookings bookings) {
        this.runner = runner;
        this.timers = timers;
        this.bookings = bookings;
    }

    @Override
    public String type() {
        return TimerPauses.CHECK_JOB;
    }

    /** FR-OPS-39 is an operational decision on an existing commitment (category B, ADR-022). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
    }

    /** The booking's Venue, Resource and Customer - the locks of every answer to it (arch 04 section 2.1). */
    @Override
    public LockSet lockSet(ScheduledJob job) {
        return runner.run(new Locks(bookings, job));
    }

    @Override
    public JobOutcome fire(CommandContext context, ScheduledJob job) {
        TimerPauses.Decision decision = timers.settle(context, job.subjectId());
        return decision.outcome() == TimerPauses.Outcome.NOT_PAUSED
                ? JobOutcome.superseded("no paused timer") : JobOutcome.done();
    }

    private record Locks(EmergencyBookings bookings, ScheduledJob job) implements Command<LockSet> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public LockSet execute(CommandContext context) {
            return bookings.pausedTimer(context, job.subjectId())
                    .map(timer -> LockSet.builder().venues(timer.venueId()).resources(timer.resourceId())
                            .users(timer.customerId()).build())
                    .orElse(LockSet.empty());
        }
    }
}
