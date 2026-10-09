package ps.e7gz.scheduling.internal;

import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The deadline worker ({@code e7gz.scheduler.*}, arch 08 section 2.4). Technical settings, not Product rules.
 *
 * @param tick             how often an idle worker thread polls; a {@code pg_notify('sched')} wakes it sooner
 * @param threads          worker threads per worker process (the worker pool is small on purpose, arch 12 section 1.4)
 * @param batchSize        due jobs one poll looks at
 * @param lease            how long a lease lasts: the longest a fire may take before another worker may take the job
 * @param maxAttempts      a job that has failed this many attempts is {@code FAILED} (Sev-2)
 * @param firstBackoff     wait after the first failed attempt; doubled after each further one
 * @param maxBackoff       longest wait between attempts
 * @param invariantSweepAt when the nightly invariant sweep runs (Africa/Cairo)
 */
@ConfigurationProperties("e7gz.scheduler")
record SchedulerProperties(
        @DefaultValue("1s") Duration tick,
        @DefaultValue("2") int threads,
        @DefaultValue("10") int batchSize,
        @DefaultValue("5m") Duration lease,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("10s") Duration firstBackoff,
        @DefaultValue("10m") Duration maxBackoff,
        @DefaultValue("04:00") LocalTime invariantSweepAt) {

    SchedulerProperties {
        if (tick.toMillis() < 1 || lease.toMillis() < 1 || firstBackoff.toMillis() < 1
                || maxBackoff.compareTo(firstBackoff) < 0) {
            throw new IllegalArgumentException("e7gz.scheduler: tick, lease and back-off must be positive, "
                    + "max-backoff at least first-backoff");
        }
        if (threads < 1 || batchSize < 1 || maxAttempts < 1) {
            throw new IllegalArgumentException("e7gz.scheduler: threads, batch-size and max-attempts must be at least 1");
        }
    }

    /** The wait after failed attempt number {@code attempt}: first-backoff, doubled each time, at most max-backoff. */
    Duration backoff(int attempt) {
        Duration wait = firstBackoff;
        for (int i = 1; i < attempt && wait.compareTo(maxBackoff) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
    }
}
