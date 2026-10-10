package ps.e7gz.notification.internal.email;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@code e7gz.email.provider=log}: accepts every email and logs its subject only (never the address or the body). */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    public LoggingEmailSender() {
        log.warn("Email provider is 'log': emails are written to this log and none is sent. Not for deployment.");
    }

    @Override
    public EmailResult send(String to, String subject, String body) {
        String id = "log-" + UUID.randomUUID();
        log.info("DEV EMAIL {}: {}", id, subject);
        return new EmailResult.Sent(id);
    }
}
