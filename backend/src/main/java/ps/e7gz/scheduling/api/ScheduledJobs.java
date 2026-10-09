package ps.e7gz.scheduling.api;

import java.time.Instant;
import ps.e7gz.common.command.CommandContext;

/**
 * How a command creates and re-arms jobs (ADR-006). Every call runs on the command's own transaction, so a job exists
 * exactly when the business state that needs it commits (arch 08 section 2.2, property 1).
 */
public interface ScheduledJobs {

    /**
     * Writes the job {@code PENDING}, with the correlation ID of the current request or job. When a job with the same
     * idempotency key exists already, nothing changes and the result is {@code false}. A job due within a second wakes
     * the workers when the command commits ({@code pg_notify('sched')}).
     */
    boolean schedule(CommandContext context, NewJob job);

    /**
     * Sends a {@code PAUSED} or {@code FAILED} job back to {@code PENDING} at {@code dueAt}, recomputed by the caller
     * ({@code technical.job_rearm}; FR-OPS-36, FR-OPS-39). The database refuses any other state.
     */
    void rearm(CommandContext context, String idempotencyKey, Instant dueAt);
}
