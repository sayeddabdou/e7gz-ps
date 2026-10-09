package ps.e7gz.scheduling.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One {@code technical.scheduled_job} row as its handler sees it (arch 08 section 2). A handler is called only for a
 * job its worker holds the lease of, so {@code attempt} already counts the current one.
 *
 * @param dueAt         the persisted due instant; it is when the job became due, never when the worker happened to run
 * @param correlationId the ID of the command that created the job (a nightly system job: its own id); the worker has
 *                      it open on the thread while the handler runs, and every retry reuses it
 */
public record ScheduledJob(UUID id, String type, String subjectType, UUID subjectId, Instant dueAt, int attempt,
                           String idempotencyKey, UUID correlationId) {

    public ScheduledJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(dueAt, "dueAt");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
