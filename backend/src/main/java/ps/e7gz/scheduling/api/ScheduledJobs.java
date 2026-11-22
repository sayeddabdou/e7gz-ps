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

    /**
     * Withdraws a job the command's own transition made unnecessary - e.g. T1 when the venue accepts or rejects (arch
     * 06 section 2.1; E7-47, PO decision D1 10 Oct 2026): a {@code PENDING}, {@code PAUSED} or {@code FAILED} job
     * becomes {@code SUPERSEDED} and never fires. Returns {@code false}, changing nothing, when no job has the key, when
     * it is already {@code DONE} or {@code SUPERSEDED}, or when a worker holds it {@code LEASED} right now: that worker's
     * fire re-reads the authoritative state under the same locks and supersedes it itself, so a job is never taken from
     * under its lease holder (whose {@code job_finish} would then fail).
     */
    boolean supersede(CommandContext context, String idempotencyKey);

    /**
     * E7-82: pauses a pausable deadline (T1, T2, T3) for an Emergency - {@code PENDING -> PAUSED}, its {@code due_at}
     * kept (arch 08 section 2.5, FR-OPS-34, FR-OPS-35) - so it never fires while the timer is paused. Returns
     * {@code false}, changing nothing, when no job has the key or it is not {@code PENDING}: a {@code LEASED} job is left
     * to its worker, whose fire re-reads the paused booking and backs off. {@link #rearmIfHeld} sends it back (E7-83).
     */
    boolean pause(CommandContext context, String idempotencyKey);

    /**
     * E7-83: {@link #rearm} when the job is {@code PAUSED} or {@code FAILED} - a resumed timer's T1, T2 or T3 (FR-OPS-36;
     * Sprint 2 review, PO decision 12 Oct 2026: a T1 that fired during the pause and ended {@code FAILED} is re-armed
     * too; v1.1.46: a FAILED job re-armed starts its attempts again). Returns {@code false}, changing nothing, when no
     * job has the key or it is in any other state: a
     * {@code PENDING} or {@code LEASED} job fires on its own and re-reads the stored deadline (rescheduling itself to it),
     * and a {@code DONE} or {@code SUPERSEDED} one is over.
     */
    boolean rearmIfHeld(CommandContext context, String idempotencyKey, Instant dueAt);
}
