package ps.e7gz.scheduling.api;

/**
 * A job whose work is not one business write: system work such as the nightly invariant sweep, which opens its own
 * transactions or commands. The worker commits the job's lease first, calls {@link #run} outside any transaction, then
 * records the outcome in a transaction of its own. If the worker dies while it runs, the lease expires and another worker
 * takes the job again, so the work must be safe to repeat.
 */
public non-sealed interface BackgroundJobHandler extends ScheduledJobHandler {

    /** Runs outside any transaction, with the job's correlation ID open on the thread. Throw to have it retried. */
    JobOutcome run(ScheduledJob job);
}
