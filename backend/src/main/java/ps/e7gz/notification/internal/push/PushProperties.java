package ps.e7gz.notification.internal.push;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Push settings ({@code e7gz.push}).
 *
 * @param provider {@code log} (default: local development and tests) or {@code fcm}
 */
@ConfigurationProperties("e7gz.push")
public record PushProperties(
        @DefaultValue("log") Provider provider,
        @DefaultValue Fcm fcm) {

    public enum Provider {
        /** Writes pushes to the application log. Local development only. */
        LOG,
        /** Firebase Cloud Messaging HTTP v1: Android, and iOS through APNs (arch 01 decision A7). */
        FCM
    }

    /**
     * @param serviceAccount Base64 of the Firebase service-account JSON file (project_id, client_email, private_key),
     *                       from the secret store only (arch 12 section 3; E7-101)
     * @param baseUrl        the FCM API root; {@code /v1/projects/{project}/messages:send} is under it
     * @param connectTimeout TCP connect timeout
     * @param readTimeout    response timeout
     */
    public record Fcm(
            String serviceAccount,
            @DefaultValue("https://fcm.googleapis.com") URI baseUrl,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {

        @Override
        public String toString() {
            return "Fcm[serviceAccount=<redacted>, baseUrl=" + baseUrl + "]";
        }
    }
}
