package ps.e7gz.notification.internal.webpush;

import java.util.Objects;
import ps.e7gz.notification.internal.push.PushSender.PushMessage;

/**
 * Sends one push to one Venue Dashboard browser (ADR-014, arch 01 section 14 A6, arch 08 section 4.3). Chosen by
 * {@code e7gz.web-push.provider}: {@code log} (local development and tests, the default) or {@code vapid}.
 */
public interface WebPushSender {

    /** A {@code notification.venue_push_subscription} row: the browser's endpoint and its two keys. */
    record Subscription(String endpoint, String p256dh, String auth) {

        public Subscription {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(p256dh, "p256dh");
            Objects.requireNonNull(auth, "auth");
        }

        @Override
        public String toString() {
            return "Subscription[<redacted>]";
        }
    }

    /** The push service's answer, classified (arch 08 section 4.3). Error codes are short tokens, never provider text. */
    sealed interface WebPushResult {

        /** Accepted by the push service. */
        record Sent(String messageId) implements WebPushResult {
        }

        /** The subscription expired or was unsubscribed (404, 410): revoke it (never delete it), do not retry. */
        record Gone(String errorCode) implements WebPushResult {
        }

        /** The push service refuses this message for good (400, 413, a malformed subscription): do not retry. */
        record Rejected(String errorCode) implements WebPushResult {
        }

        /** 5xx, timeout, throttling, our VAPID credentials: retried on the outbox back-off. */
        record Transient(String errorCode) implements WebPushResult {
        }
    }

    WebPushResult send(Subscription subscription, PushMessage message);
}
