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

    /** A Platform Admin lifted the Ban now (FR-TRS-23, FR-TRS-27): {@code endsAt} is the lift instant. */
    static NewOutboxEvent lifted(UUID userId, UUID banId, int stage, Instant previousEndsAt, Instant endsAt) {
        Map<String, Object> payload = base(userId, banId);
        payload.put("penaltyStage", stage);
        payload.put("previousEndsAt", previousEndsAt.toString());
        payload.put("endsAt", endsAt.toString());
        return new NewOutboxEvent(IntegrationEvents.BAN_LIFTED, BAN_AGGREGATE, banId, payload);
    }

    /**
     * The Ban's end was brought forward (FR-TRS-25, FR-TRS-27): by the Platform Admin, or by the Admin correction that
     * left it replay support only until {@code endsAt} ({@code correctionEventId} set).
     */
    static NewOutboxEvent shortened(UUID userId, UUID banId, int stage, Instant previousEndsAt, Instant endsAt,
                                    UUID correctionEventId) {
        Map<String, Object> payload = base(userId, banId);
        payload.put("penaltyStage", stage);
        payload.put("previousEndsAt", previousEndsAt.toString());
        payload.put("endsAt", endsAt.toString());
        if (correctionEventId != null) {
            payload.put("correctionEventId", correctionEventId);
        }
        return new NewOutboxEvent(IntegrationEvents.BAN_SHORTENED, BAN_AGGREGATE, banId, payload);
    }

    /** The Platform Admin's ADMIN_TRUST_ADJUSTMENT (FR-TRS-27), aggregate {@code USER}. */
    static NewOutboxEvent trustAdjusted(UUID userId, UUID trustEventId, int counterAfter, int penaltyStageAfter) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("trustEventId", trustEventId);
        payload.put("counterAfter", counterAfter);
        payload.put("penaltyStageAfter", penaltyStageAfter);
        return new NewOutboxEvent(IntegrationEvents.TRUST_ADJUSTED, "USER", userId, payload);
    }

    private static Map<String, Object> base(UUID userId, UUID banId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("banInstanceId", banId);
        return payload;
    }
}
