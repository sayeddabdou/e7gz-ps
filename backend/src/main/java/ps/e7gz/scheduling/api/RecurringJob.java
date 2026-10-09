package ps.e7gz.scheduling.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A system job the worker runs on a timetable, on {@code technical.scheduled_job} like every other job (PO decision
 * 4 Oct 2026): {@code subject_type = SYSTEM}, {@code subject_id} a fixed UUID derived from the type,
 * {@code idempotency_key = TYPE:{occurrence}} and its own id as its correlation ID.
 * <ul>
 *   <li>Nightly: one row per night. Each run inserts the next night's row in the same transaction as its own outcome
 *       (a run that ends {@code FAILED} too, so one bad night does not stop the next); a conflicting key does
 *       nothing.</li>
 *   <li>Every interval: one row for good (PO decision 9 Oct 2026). Each run, whatever its outcome, moves that row to
 *       the next run ({@code job_finish} with {@code p_next_run}); a failed run is logged and the next one is on time,
 *       as the old timers did.</li>
 * </ul>
 * At start-up the worker inserts the first row when the type has no {@code PENDING} or {@code LEASED} one. The work
 * runs outside any transaction ({@link BackgroundJobHandler}) and must be safe to repeat.
 */
public record RecurringJob(String type, Recurrence recurrence, Runnable work) implements BackgroundJobHandler {

    public static final String SUBJECT_TYPE = "SYSTEM";

    public RecurringJob {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(recurrence, "recurrence");
        Objects.requireNonNull(work, "work");
    }

    /** {@code UUID.nameUUIDFromBytes(type)}: the same subject on every worker and every run. */
    public UUID subjectId() {
        return UUID.nameUUIDFromBytes(type.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code TYPE:{occurrence}} of the run due at {@code dueAt}. */
    public String idempotencyKey(Instant dueAt) {
        return type + ":" + recurrence.occurrence(dueAt);
    }

    @Override
    public JobOutcome run(ScheduledJob job) {
        work.run();
        return JobOutcome.done();
    }
}
