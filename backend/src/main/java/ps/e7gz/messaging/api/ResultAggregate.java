package ps.e7gz.messaging.api;

import java.util.Objects;
import java.util.UUID;

/**
 * The aggregate an idempotent write created or changed ({@code result_aggregate_type} / {@code result_aggregate_id}),
 * e.g. {@code BOOKING} and the booking id.
 */
public record ResultAggregate(String type, UUID id) {

    public ResultAggregate {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (type.isBlank()) {
            throw new IllegalArgumentException("aggregate type must not be blank");
        }
    }
}
