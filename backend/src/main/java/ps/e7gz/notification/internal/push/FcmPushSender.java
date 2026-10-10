package ps.e7gz.notification.internal.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Firebase Cloud Messaging HTTP v1 ({@code POST /v1/projects/{project}/messages:send}): one message per device token,
 * to Android directly and to iPhones through APNs (arch 01 decision A7). The message carries the rendered title and
 * body, the data the app deep-links with (FR-NTF-04), high priority on Android, and the APNs alert headers and default
 * sound so an iPhone shows it even when the app is closed. Needs the Firebase project's APNs key for iOS (E7-101).
 *
 * <p>Errors (arch 08 section 4.3, FCM error codes): {@code UNREGISTERED}, {@code INVALID_ARGUMENT} on the token and
 * {@code SENDER_ID_MISMATCH} mean the token can never work - revoke it; 429, 5xx, timeouts and credential problems are
 * retried. Neither the token, the provider's response body nor the access token is ever logged.
 */
class FcmPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);

    /** The Android notification channel the customer app creates (E7-51: {@code booking-updates}). */
    static final String ANDROID_CHANNEL = "booking-updates";

    private final RestClient http;
    private final FcmAccessTokens tokens;
    private final ObjectMapper json;

    FcmPushSender(RestClient http, FcmAccessTokens tokens, ObjectMapper json) {
        this.http = http;
        this.tokens = tokens;
        this.json = json;
    }

    @Override
    public PushResult send(String deviceToken, PushMessage message) {
        try {
            JsonNode answer = http.post()
                    .uri("/v1/projects/{project}/messages:send", tokens.projectId())
                    .headers(headers -> headers.setBearerAuth(tokens.current()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("message", message(deviceToken, message)))
                    .retrieve()
                    .body(JsonNode.class);
            String name = answer == null ? "" : answer.path("name").asText("");
            return name.isBlank() ? new PushResult.Transient("FCM_NO_MESSAGE_NAME") : new PushResult.Sent(name);
        } catch (RestClientResponseException e) {
            return classify(e.getStatusCode().value(), errorCode(e.getResponseBodyAsString()));
        } catch (RestClientException | IllegalStateException e) {
            log.warn("FCM send failed: {}", e.getClass().getSimpleName());
            return new PushResult.Transient("FCM_UNREACHABLE");
        }
    }

    /** The FCM v1 message: notification, data, Android and APNs blocks. */
    static Map<String, Object> message(String deviceToken, PushMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", deviceToken);
        body.put("notification", Map.of("title", message.title(), "body", message.body()));
        body.put("data", message.data());
        // The customer app's Android channel (apps/customer, E7-51), created with high importance.
        body.put("android", Map.of("priority", "HIGH",
                "notification", Map.of("sound", "default", "channel_id", ANDROID_CHANNEL)));
        body.put("apns", Map.of(
                "headers", Map.of("apns-priority", "10", "apns-push-type", "alert"),
                "payload", Map.of("aps", Map.of("sound", "default"))));
        return body;
    }

    /** FCM's own error code ({@code error.details[].errorCode}), or the HTTP status as a fallback. */
    private String errorCode(String responseBody) {
        try {
            for (JsonNode detail : json.readTree(responseBody).path("error").path("details")) {
                String code = detail.path("errorCode").asText("");
                if (!code.isBlank()) {
                    return code;
                }
            }
        } catch (Exception unreadable) {
            // fall through: the HTTP status decides
        }
        return "";
    }

    static PushResult classify(int status, String fcmErrorCode) {
        switch (fcmErrorCode) {
            case "UNREGISTERED", "INVALID_ARGUMENT", "SENDER_ID_MISMATCH" -> {
                return new PushResult.InvalidToken("FCM_" + fcmErrorCode);
            }
            case "QUOTA_EXCEEDED", "UNAVAILABLE", "INTERNAL", "THIRD_PARTY_AUTH_ERROR" -> {
                return new PushResult.Transient("FCM_" + fcmErrorCode);
            }
            default -> {
                if (status == 404) {
                    return new PushResult.InvalidToken("FCM_UNREGISTERED");
                }
                return new PushResult.Transient("FCM_HTTP_" + status);
            }
        }
    }
}
