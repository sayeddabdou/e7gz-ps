package ps.e7gz.scheduling.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A job a command schedules (arch 08 section 2.2).
 *
 * @param subjectType    the aggregate type of {@code subjectId}, e.g. {@code BOOKING}
 * @param idempotencyKey unique across all jobs: one job per transition and subject
 */
public record NewJob(String type, String subjectType, UUID subjectId, Instant dueAt, String idempotencyKey) {

    public NewJob {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(dueAt, "dueAt");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }

    /** With the natural key {@code TYPE:{subjectId}}, e.g. {@code VENUE_RESPONSE_EXPIRY:{bookingId}}. */
    public static NewJob of(String type, String subjectType, UUID subjectId, Instant dueAt) {
        return new NewJob(type, subjectType, subjectId, dueAt, type + ":" + subjectId);
    }
}
