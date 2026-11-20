package ps.e7gz.operations.internal;

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
 */
@Component
@Profile("worker")
class EmergencyExpectedEndHandler implements CommandJobHandler {

    private final CommandRunner runner;
    private final VenueEmergencies emergencies;

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
        return runner.run(new Locks(job));
    }

    @Override
    public JobOutcome fire(CommandContext context, ScheduledJob job) {
        return switch (EmergencyCommands.expectedEnd(context, emergencies.deps(), job.subjectId(), job.dueAt())) {
            case DONE -> JobOutcome.done();
            case SUPERSEDED -> JobOutcome.superseded("no longer ACTIVE with this expected end");
            case NOT_YET -> JobOutcome.rescheduleAt(job.dueAt());
        };
    }

    private record Locks(ScheduledJob job) implements Command<LockSet> {

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
            return context.jdbc().sql("SELECT venue_id, resource_id FROM operations.emergency WHERE id = :id")
                    .param("id", job.subjectId())
                    .query((rs, n) -> {
                        LockSet.Builder locks = LockSet.builder().venues(rs.getObject("venue_id", java.util.UUID.class));
                        java.util.UUID resource = rs.getObject("resource_id", java.util.UUID.class);
                        if (resource != null) {
                            locks.resources(resource);
                        }
                        return locks.build();
                    })
                    .optional().orElse(LockSet.empty());
        }
    }
}
