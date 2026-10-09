package ps.e7gz.scheduling.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJobs;

/** {@link ScheduledJobs} on the command's own transaction (every profile: commands schedule, the worker fires). */
@Component
class ScheduledJobsService implements ScheduledJobs {

    /** A job due this soon wakes the workers at commit instead of waiting for their next tick (arch 08 section 2.4). */
    static final Duration WAKE_WITHIN = Duration.ofSeconds(1);

    private final JdbcScheduledJobStore store;

    ScheduledJobsService(JdbcScheduledJobStore store) {
        this.store = store;
    }

    @Override
    public boolean schedule(CommandContext context, NewJob job) {
        boolean created = store.insert(context.jdbc(), UUID.randomUUID(), job, CorrelationId.require());
        if (created && !job.dueAt().isAfter(context.commandNow().plus(WAKE_WITHIN))) {
            store.wakeWorkers(context.jdbc());
        }
        return created;
    }

    @Override
    public void rearm(CommandContext context, String idempotencyKey, Instant dueAt) {
        store.rearm(context.jdbc(), idempotencyKey, dueAt);
    }
}
