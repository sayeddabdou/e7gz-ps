package ps.e7gz.operations.internal;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.scheduling.api.CommandJobHandler;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;

/**
 * {@code EMERGENCY_EXPECTED_END:{emergencyId}:{expectedEnd}}, scheduled at the expected end by a declaration or an
 * extension (E7-82; FR-OPS-18): ACTIVE -> AWAITING_CONFIRMATION, the selling block kept ({@link
 * EmergencyCommands#expectedEnd}). Its key carries the expected end, so an extension schedules a new job and withdraws
 * the old one. The reminders of FR-OPS-25 are E7-80's.
 *
 * <p>E7-84: a resource Emergency's expected end may enter an Owner Review (FR-OPS-26), whose cascade needs the
 * Resource's Customers and Tournaments locked ({@link EmergencyCommands#ownerReviewLocks}). The locks planned for a job
 * are handed to its {@link #fire} through {@link #planned}; a job fired without them (or whose reach grew meanwhile)
 * fails with {@link EmergencyCommands.StaleLocks}, and the worker runs it again with fresh locks.
 */
@Component
@Profile("worker")
class EmergencyExpectedEndHandler implements CommandJobHandler {

    private final CommandRunner runner;
    private final VenueEmergencies emergencies;
    private final Map<UUID, LockSet> planned = new ConcurrentHashMap<>();

    EmergencyExpectedEndHandler(CommandRunner runner, VenueEmergencies emergencies) {
        this.runner = runner;
        this.emergencies = emergencies;
    }

    @Override
    public String type() {
        return EmergencyCommands.EXPECTED_END_JOB;
    }

    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
    }

    /** The venue and the resource: the locks of the Emergency's own commands, so they and this are serialised. */
    @Override
    public LockSet lockSet(ScheduledJob job) {
        LockSet locks = runner.run(new Locks(job, emergencies.deps()));
        planned.put(job.id(), locks);
        return locks;
    }

    @Override
    public JobOutcome fire(CommandContext context, ScheduledJob job) {
        LockSet locks = planned.getOrDefault(job.id(), LockSet.empty());
        JobOutcome outcome = switch (EmergencyCommands.expectedEnd(context, emergencies.deps(), job.subjectId(),
                job.dueAt(), locks)) {
            case DONE -> JobOutcome.done();
            case SUPERSEDED -> JobOutcome.superseded("no longer ACTIVE with this expected end");
            case NOT_YET -> JobOutcome.rescheduleAt(job.dueAt());
        };
        planned.remove(job.id(), locks);
        return outcome;
    }

    private record Locks(ScheduledJob job, EmergencyCommands.Deps deps) implements Command<LockSet> {

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
            return context.jdbc().sql(EmergencyCommands.SELECT + " WHERE e.id = :id")
                    .param("id", job.subjectId())
                    .query(EmergencyCommands.Emergency::map)
                    .optional()
                    .map(emergency -> {
                        LockSet.Builder locks = LockSet.builder().venues(emergency.venueId());
                        if (emergency.resourceId() != null) {
                            locks.resources(emergency.resourceId());
                            EmergencyCommands.ownerReviewLocks(context, deps, emergency, locks);
                        }
                        return locks.build();
                    })
                    .orElse(LockSet.empty());
        }
    }
}
