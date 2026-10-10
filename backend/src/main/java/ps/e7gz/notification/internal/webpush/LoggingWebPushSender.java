package ps.e7gz.notification.internal.webpush;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ps.e7gz.notification.internal.push.PushSender.PushMessage;

/**
 * The development Web Push adapter ({@code e7gz.web-push.provider=log}): accepts every push and writes its text and data
 * to the log, never the endpoint or keys. No VAPID keys are needed until deployment (PO decision 10 Oct 2026, E7-101).
 */
public class LoggingWebPushSender implements WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingWebPushSender.class);

    public LoggingWebPushSender() {
        log.warn("Web Push provider is 'log': venue pushes are written to this log and none is sent. Local "
                + "development only.");
    }

    @Override
    public WebPushResult send(Subscription subscription, PushMessage message) {
        String id = "log-" + UUID.randomUUID();
        log.info("DEV WEB PUSH {}: {} | {} {}", id, message.title(), message.body(), message.data());
        return new WebPushResult.Sent(id);
    }
}
