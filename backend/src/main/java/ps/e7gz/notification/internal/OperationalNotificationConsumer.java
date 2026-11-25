package ps.e7gz.notification.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.notification.api.NotificationRequest;

/**
 * Delivers {@link OperationalNotificationRules} after the event's own command committed (FR-NTF-12; E7-80). Exactly
 * once: each notification's id is derived from the event, the code and the recipient, so a retried event - every
 * consumer runs again - creates nothing ({@link NotificationService#notifyOnce}).
 */
@Component
@Profile("worker")
class OperationalNotificationConsumer implements OutboxConsumer {

    private final CommandRunner runner;
    private final NotificationService notifications;

    OperationalNotificationConsumer(CommandRunner runner, NotificationService notifications) {
        this.runner = runner;
        this.notifications = notifications;
    }

    @Override
    public Set<String> eventTypes() {
        return OperationalNotificationRules.eventTypes();
    }

    @Override
    public void handle(OutboxEvent event) {
        List<NotificationRequest> requests = OperationalNotificationRules.planFor(event);
        if (!requests.isEmpty()) {
            runner.run(new Deliver(event, requests));
        }
    }

    static UUID id(String origin, String code, UUID recipient) {
        return UUID.nameUUIDFromBytes(("E7-80 operational notification|" + origin + "|" + code + "|" + recipient)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** The event's notifications in one transaction; it locks nothing and writes only its own rows. */
    private final class Deliver implements Command<Void> {

        private final OutboxEvent event;
        private final List<NotificationRequest> requests;

        private Deliver(OutboxEvent event, List<NotificationRequest> requests) {
            this.event = event;
            this.requests = requests;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public Void execute(CommandContext context) {
            for (NotificationRequest request : requests) {
                notifications.notifyOnce(context, id("outbox:" + event.id(), request.code(), request.recipientId()),
                        request);
            }
            return null;
        }
    }
}
