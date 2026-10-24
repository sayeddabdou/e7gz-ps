package ps.e7gz.notification.internal;

import java.nio.charset.StandardCharsets;
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
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.notification.api.NotificationRequest;

/**
 * The customer's points notifications (E7-66, PO decision 23 Oct 2026), on the one notification path (E7-55, PO
 * decision 12 Oct 2026): the dispatcher hands it each committed {@code LOYALTY_POINTS_LEDGER_COMMITTED} event, and
 * {@link PointsNotificationRules} decides the notification. It commits after the points did - never for a command that
 * rolled back (FR-NTF-12).
 *
 * <p>Idempotent (a retry of the event runs every consumer again): the notification id is derived from the event, so a
 * second run finds it and creates nothing ({@link NotificationService#notifyOnce}).
 */
@Component
@Profile("worker")
class PointsNotificationConsumer implements OutboxConsumer {

    private final CommandRunner runner;
    private final NotificationService notifications;

    PointsNotificationConsumer(CommandRunner runner, NotificationService notifications) {
        this.runner = runner;
        this.notifications = notifications;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(IntegrationEvents.LOYALTY_POINTS_LEDGER_COMMITTED);
    }

    @Override
    public void handle(OutboxEvent event) {
        PointsNotificationRules.planFor(event).ifPresent(request -> runner.run(new Deliver(event, request)));
    }

    static UUID id(OutboxEvent event, NotificationRequest request) {
        return UUID.nameUUIDFromBytes(("E7-66 points notification|outbox:" + event.id() + "|" + request.code() + "|"
                + request.recipientId()).getBytes(StandardCharsets.UTF_8));
    }

    /** The notification, in its own transaction. Locks nothing: it writes only its own rows. */
    private final class Deliver implements Command<Void> {

        private final OutboxEvent event;
        private final NotificationRequest request;

        private Deliver(OutboxEvent event, NotificationRequest request) {
            this.event = event;
            this.request = request;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.A_NEW_ACTIVITY_MARKETPLACE;      // no Venue in scope (ADR-022)
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public Void execute(CommandContext context) {
            notifications.notifyOnce(context, id(event, request), request);
            return null;
        }
    }
}
