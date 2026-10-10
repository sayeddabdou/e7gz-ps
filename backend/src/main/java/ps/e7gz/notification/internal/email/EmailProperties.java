package ps.e7gz.notification.internal.email;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Email settings ({@code e7gz.email}). Locally the defaults reach Mailpit ({@code docker compose up -d}, inbox on
 * http://localhost:8025); staging and production take the SMTP provider's settings from the secret store (E7-101).
 *
 * @param provider {@code smtp} (default) or {@code log}
 * @param from     the sender address of every notification email
 */
@ConfigurationProperties("e7gz.email")
public record EmailProperties(
        @DefaultValue("smtp") Provider provider,
        @DefaultValue("E7GZ PS <no-reply@e7gz.local>") String from,
        @DefaultValue Smtp smtp) {

    public enum Provider {
        /** Writes emails to the application log. Tests and local runs without Mailpit. */
        LOG,
        /** An SMTP server: Mailpit locally, the email provider in staging and production. */
        SMTP
    }

    /**
     * @param startTls require STARTTLS (a real provider); Mailpit has none
     */
    public record Smtp(
            @DefaultValue("localhost") String host,
            @DefaultValue("1025") int port,
            String username,
            String password,
            @DefaultValue("false") boolean startTls,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {

        @Override
        public String toString() {
            return "Smtp[host=" + host + ", port=" + port + ", username=" + username + ", <password redacted>]";
        }
    }
}
