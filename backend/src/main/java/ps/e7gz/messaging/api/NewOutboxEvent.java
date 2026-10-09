package ps.e7gz.messaging.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One integration event a command publishes (arch 08 section 3.2): its type, the aggregate it is about, and a payload of
 * plain JSON values (strings, numbers, booleans, lists, maps; a UUID or an enum is written as its string). The outbox
 * adds the envelope: {@code correlation_id} from the running request or job, and {@code created_at} = the command
 * instant (the event's {@code occurredAt}).
 *
 * @param eventType     e.g. {@code VenueMarketplaceStatusChanged} ({@code ck_outbox_event_names})
 * @param aggregateType e.g. {@code VENUE} ({@code ck_outbox_event_names}: upper case)
 */
public record NewOutboxEvent(String eventType, String aggregateType, UUID aggregateId, Map<String, Object> payload) {

    public NewOutboxEvent {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        payload = new LinkedHashMap<>(Objects.requireNonNull(payload, "payload"));
    }
}
