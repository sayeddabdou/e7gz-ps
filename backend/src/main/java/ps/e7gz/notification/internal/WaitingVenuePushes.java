package ps.e7gz.notification.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;

/**
 * Sends the venue PUSH deliveries E7-43 left waiting (PO decision 10 Oct 2026, E7-44). Before Web Push existed the
 * worker skipped them and their {@code NotificationDeliveryRequested} event was DISPATCHED, so nothing would try them
 * again. When the worker starts, every venue notification with a PUSH delivery still {@code PENDING} and no event of
 * its own waiting gets a new one; the worker then sends it like any other (same retry and dead-letter rules). Safe at
 * every start: a delivery already sent or dead-lettered is never picked, and a notification whose event still waits is
 * left to it.
 */
@Component
@Profile("worker")
class WaitingVenuePushes implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WaitingVenuePushes.class);
    private static final int BATCH = 500;

    private final CommandRunner runner;
    private final Outbox outbox;

    WaitingVenuePushes(CommandRunner runner, Outbox outbox) {
        this.runner = runner;
        this.outbox = outbox;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
            int requeued = requeue();
            if (requeued > 0) {
                log.info("{} venue notification(s) with a waiting Web Push delivery queued again", requeued);
            }
        }
    }

    /** Queues every waiting venue PUSH again, in batches; returns how many notifications were queued. */
    int requeue() {
        int total = 0;
        int queued;
        do {
            queued = runner.run(new Requeue(outbox));
            total += queued;
        } while (queued == BATCH);
        return total;
    }

    private record Requeue(Outbox outbox) implements Command<Integer> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public Integer execute(CommandContext context) {
            // Two workers starting together queue each notification once: the second waits here, then (READ
            // COMMITTED) its query sees the events the first committed and skips those notifications.
            context.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended('notification:waiting_venue_pushes', 0))")
                    .query().singleRow();
            List<UUID> waiting = context.jdbc().sql("""
                            SELECT DISTINCT n.id
                              FROM notification.notification n
                              JOIN notification.notification_delivery d ON d.notification_id = n.id
                             WHERE n.recipient_type = 'VENUE' AND d.channel = 'PUSH' AND d.status = 'PENDING'
                               AND NOT EXISTS (SELECT 1 FROM technical.outbox_event e
                                                WHERE e.event_type = :type AND e.aggregate_id = n.id
                                                  AND e.state = 'PENDING')
                             LIMIT :batch""")
                    .param("type", IntegrationEvents.NOTIFICATION_DELIVERY_REQUESTED).param("batch", BATCH)
                    .query(UUID.class).list();
            for (UUID id : waiting) {
                outbox.publish(context, new NewOutboxEvent(IntegrationEvents.NOTIFICATION_DELIVERY_REQUESTED,
                        "NOTIFICATION", id, Map.of("notificationId", id.toString())));
            }
            return waiting.size();
        }
    }
}
