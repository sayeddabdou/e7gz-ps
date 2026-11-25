package ps.e7gz.notification.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
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
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.notification.api.NotificationRequest;
import ps.e7gz.notification.api.NotifiedMaintenance;
import ps.e7gz.scheduling.api.NewJob;
import ps.e7gz.scheduling.api.ScheduledJobs;

/**
 * Delivers {@link OperationalNotificationRules} after the event's own command committed (FR-NTF-12; E7-80). Exactly
 * once: each notification's id is derived from the event, the code and the recipient, so a retried event - every
 * consumer runs again - creates nothing ({@link NotificationService#notifyOnce}).
 *
 * <p>It also starts "Maintenance Starting Soon" (Q4): on {@code MaintenanceCreated} / {@code MaintenanceEdited} one job
 * per start, due 30 minutes before it ({@link MaintenanceStartingSoonHandler}); a natural key keeps a retried event to
 * one job ({@link ScheduledJobs#schedule}). A window created or moved to less than 30 minutes before its start gets
 * none. A deleted window needs nothing here: its job supersedes itself when it fires.
 */
@Component
@Profile("worker")
class OperationalNotificationConsumer implements OutboxConsumer {

    static final Set<String> MAINTENANCE_EVENTS = Set.of(IntegrationEvents.MAINTENANCE_CREATED,
            IntegrationEvents.MAINTENANCE_EDITED);

    private final CommandRunner runner;
    private final NotificationService notifications;
    private final ScheduledJobs jobs;
    private final NotifiedMaintenance maintenance;

    OperationalNotificationConsumer(CommandRunner runner, NotificationService notifications, ScheduledJobs jobs,
                                    NotifiedMaintenance maintenance) {
        this.runner = runner;
        this.notifications = notifications;
        this.jobs = jobs;
        this.maintenance = maintenance;
    }

    @Override
    public Set<String> eventTypes() {
        Set<String> types = new HashSet<>(OperationalNotificationRules.eventTypes());
        types.addAll(MAINTENANCE_EVENTS);
        return Set.copyOf(types);
    }

    @Override
    public void handle(OutboxEvent event) {
        if (MAINTENANCE_EVENTS.contains(event.eventType())) {
            runner.run(new ScheduleMaintenanceReminder(event));
            return;
        }
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

    /** The window's reminder, when its due time is still ahead of the event. Writes only the job row. */
    private final class ScheduleMaintenanceReminder implements Command<Void> {

        private final OutboxEvent event;

        private ScheduleMaintenanceReminder(OutboxEvent event) {
            this.event = event;
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
            UUID id = event.uuid("maintenanceId");
            maintenance.find(context, id).filter(window -> !window.deleted()).ifPresent(window -> {
                Instant due = window.startAt().minus(MaintenanceStartingSoonHandler.LEAD);
                if (due.isAfter(event.occurredAt())) {
                    jobs.schedule(context, new NewJob(MaintenanceStartingSoonHandler.TYPE,
                            MaintenanceStartingSoonHandler.SUBJECT, id, due,
                            MaintenanceStartingSoonHandler.key(id, window.startAt())));
                }
            });
            return null;
        }
    }
}
