package ps.e7gz.notification.internal.push;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The development push adapter ({@code e7gz.push.provider=log}): accepts every push and writes its code and data to the
 * log, never the token. No Firebase project is needed until deployment (PO decision 10 Oct 2026: the provider accounts
 * are set up with the PO before deployment, E7-101).
 */
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    public LoggingPushSender() {
        log.warn("Push provider is 'log': pushes are written to this log and none is sent. Local development only.");
    }

    @Override
    public PushResult send(String deviceToken, PushMessage message) {
        String id = "log-" + UUID.randomUUID();
        log.info("DEV PUSH {}: {} | {} {}", id, message.title(), message.body(), message.data());
        return new PushResult.Sent(id);
    }
}
