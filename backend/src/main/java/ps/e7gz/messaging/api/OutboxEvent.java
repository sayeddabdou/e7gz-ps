package ps.e7gz.messaging.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One {@code technical.outbox_event} row as a consumer sees it (arch 08 section 4). A consumer is called only for a
 * committed event: the row does not exist before its command commits.
 *
 * @param attempt       the delivery attempts already finished before this one (0 on the first)
 * @param occurredAt    the command instant of the command that published it ({@code created_at})
 * @param correlationId the ID of that command's request or job; the dispatcher has it open on the thread while the
 *                      consumer runs, and every retry reuses it
 */
public record OutboxEvent(long id, String eventType, String aggregateType, UUID aggregateId, JsonNode payload,
                          UUID correlationId, Instant occurredAt, int attempt) {

    public OutboxEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    /** A UUID field of the payload; fails when it is missing or malformed (a producer defect, never retried). */
    public UUID uuid(String field) {
        return UUID.fromString(text(field));
    }

    /** A text field of the payload; fails when it is missing (a producer defect, never retried). */
    public String text(String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isTextual()) {
            throw new PermanentDeliveryFailure(eventType + " " + id + " has no text field '" + field + "'");
        }
        return value.asText();
    }
}
