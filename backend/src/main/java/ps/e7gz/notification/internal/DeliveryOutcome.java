package ps.e7gz.notification.internal;

import java.util.Objects;

/**
 * What one attempt of one channel achieved (arch 08 section 4.3). A channel adapter classifies its provider's answer:
 * transient (5xx, timeout, throttle) is retried on the outbox back-off; permanent (invalid recipient, malformed) is
 * final at once. The error code is a short machine token kept in {@code notification_delivery.error_code}: never a
 * provider message, a token, an address or a phone number (arch 10 section 6).
 */
sealed interface DeliveryOutcome {

    static DeliveryOutcome sent(String providerMessageId) {
        return new Sent(providerMessageId);
    }

    static DeliveryOutcome transientFailure(String errorCode) {
        return new Transient(errorCode);
    }

    static DeliveryOutcome permanentFailure(String errorCode) {
        return new Permanent(errorCode);
    }

    record Sent(String providerMessageId) implements DeliveryOutcome {
        public Sent {
            Objects.requireNonNull(providerMessageId, "providerMessageId");
        }
    }

    record Transient(String errorCode) implements DeliveryOutcome {
        public Transient {
            Objects.requireNonNull(errorCode, "errorCode");
        }
    }

    record Permanent(String errorCode) implements DeliveryOutcome {
        public Permanent {
            Objects.requireNonNull(errorCode, "errorCode");
        }
    }
}
