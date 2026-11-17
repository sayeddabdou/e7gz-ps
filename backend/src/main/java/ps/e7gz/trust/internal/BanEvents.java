package ps.e7gz.trust.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;

/**
 * The Ban integration events (arch 08 section 3.2, Trust row; E7-71), aggregate {@code BAN_INSTANCE} with the Ban
 * Instance id, each written in the command that changed the Ban. The outbox adds {@code correlationId} and
 * {@code occurredAt}; instants are ISO-8601 UTC strings. Payloads: see {@link IntegrationEvents}.
 */
final class BanEvents {

    static final String BAN_AGGREGATE = "BAN_INSTANCE";

    private BanEvents() {
    }

    static NewOutboxEvent activated(UUID userId, UUID banId, int stage, Instant activatedAt, Instant endsAt,
                                    int cancelledBookings) {
        Map<String, Object> payload = base(userId, banId);
        payload.put("penaltyStage", stage);
        payload.put("activatedAt", activatedAt.toString());
        payload.put("endsAt", endsAt.toString());
        payload.put("cancelledBookings", cancelledBookings);
        return new NewOutboxEvent(IntegrationEvents.BAN_ACTIVATED, BAN_AGGREGATE, banId, payload);
    }

    static NewOutboxEvent extended(UUID userId, UUID banId, int previousStage, int stage, Instant previousEndsAt,
                                   Instant endsAt) {
        Map<String, Object> payload = base(userId, banId);
        payload.put("previousPenaltyStage", previousStage);
        payload.put("penaltyStage", stage);
        payload.put("previousEndsAt", previousEndsAt.toString());
        payload.put("endsAt", endsAt.toString());
        return new NewOutboxEvent(IntegrationEvents.BAN_EXTENDED, BAN_AGGREGATE, banId, payload);
    }

    static NewOutboxEvent expired(UUID userId, UUID banId, int stage, Instant endsAt) {
        Map<String, Object> payload = base(userId, banId);
        payload.put("penaltyStage", stage);
        payload.put("endsAt", endsAt.toString());
        return new NewOutboxEvent(IntegrationEvents.BAN_EXPIRED, BAN_AGGREGATE, banId, payload);
    }

    static NewOutboxEvent corrected(UUID userId, UUID banId, int stage, Instant endsAt, UUID correctionEventId) {
        Map<String, Object> payload = base(userId, banId);
        payload.put("penaltyStage", stage);
        payload.put("endsAt", endsAt.toString());
        payload.put("correctionEventId", correctionEventId);
        return new NewOutboxEvent(IntegrationEvents.BAN_CORRECTED, BAN_AGGREGATE, banId, payload);
    }

    private static Map<String, Object> base(UUID userId, UUID banId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("banInstanceId", banId);
        return payload;
    }
}
