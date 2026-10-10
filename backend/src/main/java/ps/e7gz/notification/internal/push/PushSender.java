package ps.e7gz.notification.internal.push;

import java.util.Map;
import java.util.Objects;

/**
 * Sends one push to one device token (arch 08 section 4.1, arch 01 decision A7: FCM for Android and, via APNs, iOS).
 * Chosen by {@code e7gz.push.provider}: {@code log} (local development and tests, the default) or {@code fcm}.
 */
public interface PushSender {

    /**
     * @param title the rendered title (ADR-021: from the template catalogue)
     * @param body  the rendered body
     * @param data  machine values the app opens the right screen with: {@code code}, {@code notificationId}, and the
     *              {@code bookingId} or {@code tournamentId} (FR-NTF-04: the push deep-links to that exact Booking)
     */
    record PushMessage(String title, String body, Map<String, String> data) {
        public PushMessage {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(body, "body");
            data = Map.copyOf(data);
        }
    }

    /** The provider's answer, classified (arch 08 section 4.3). Error codes are short tokens, never provider text. */
    sealed interface PushResult {

        /** Accepted by the provider. */
        record Sent(String providerMessageId) implements PushResult {
        }

        /** The token is unknown, expired or of another app: revoke it (never delete it) and do not retry. */
        record InvalidToken(String errorCode) implements PushResult {
        }

        /** 5xx, timeout, throttling, credentials: retried on the outbox back-off. */
        record Transient(String errorCode) implements PushResult {
        }
    }

    PushResult send(String deviceToken, PushMessage message);
}
