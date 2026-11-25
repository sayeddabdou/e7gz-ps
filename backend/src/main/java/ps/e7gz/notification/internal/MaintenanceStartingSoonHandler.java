package ps.e7gz.notification.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.NotificationRecipientType;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.notification.api.NotificationRequest;
import ps.e7gz.notification.api.NotifiedMaintenance;
import ps.e7gz.notification.api.NotifiedMaintenance.Window;
import ps.e7gz.scheduling.api.CommandJobHandler;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;

/**
 * {@code VENUE_MAINTENANCE_STARTING_SOON} (Spec v5 section 52.5 "Maintenance Starting Soon", FR-NTF-09): the venue is
 * told 30 minutes before a Maintenance window starts (E7-80, PO decision 25 Nov 2026 Q4). Scheduled by
 * {@link OperationalNotificationConsumer} on {@code MaintenanceCreated} / {@code MaintenanceEdited}, one job per start:
 * the key carries the start, so an edit that moves it makes a new job and this one, when it fires, finds the window
 * moved and supersedes itself. A deleted window, or one already started, supersedes it too (no warning after the fact).
 * Decided from the stored window and the command instant, never from when the worker ran (arch 08 section 2.2).
 */
@Component
@Profile("worker")
class MaintenanceStartingSoonHandler implements CommandJobHandler {

    static final String TYPE = "VENUE_MAINTENANCE_STARTING_SOON";
    static final String CODE = "VENUE_MAINTENANCE_STARTING_SOON";
    static final String SUBJECT = "MAINTENANCE";
    static final Duration LEAD = Duration.ofMinutes(30);

    enum Decision {
        SEND, NOT_YET, SUPERSEDED
    }

    private final NotifiedMaintenance maintenance;
    private final NotificationService notifications;

    MaintenanceStartingSoonHandler(NotifiedMaintenance maintenance, NotificationService notifications) {
        this.maintenance = maintenance;
        this.notifications = notifications;
    }

    static String key(UUID maintenanceId, Instant startAt) {
        return TYPE + ":" + maintenanceId + ":" + startAt.toEpochMilli();
    }

    /** The start a job's key was scheduled for. */
    static Instant keyStart(String idempotencyKey) {
        return Instant.ofEpochMilli(Long.parseLong(idempotencyKey.substring(idempotencyKey.lastIndexOf(':') + 1)));
    }

    static Decision decide(Optional<Window> window, Instant keyStart, Instant now) {
        if (window.isEmpty() || window.get().deleted() || !window.get().startAt().equals(keyStart)
                || !now.isBefore(keyStart)) {
            return Decision.SUPERSEDED;
        }
        return now.isBefore(keyStart.minus(LEAD)) ? Decision.NOT_YET : Decision.SEND;
    }

    @Override
    public String type() {
        return TYPE;
    }

    /** Honouring a venue's own planned work, not new marketplace activity (ADR-022). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
    }

    /** It only reads the window and writes its own notification row. */
    @Override
    public LockSet lockSet(ScheduledJob job) {
        return LockSet.empty();
    }

    @Override
    public JobOutcome fire(CommandContext context, ScheduledJob job) {
        Optional<Window> window = maintenance.find(context, job.subjectId());
        Instant start = keyStart(job.idempotencyKey());
        return switch (decide(window, start, context.commandNow())) {
            case SUPERSEDED -> JobOutcome.superseded("the window moved, was deleted or has started");
            case NOT_YET -> JobOutcome.rescheduleAt(start.minus(LEAD));
            case SEND -> {
                Window w = window.orElseThrow();
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("maintenanceId", w.maintenanceId().toString());
                params.put("resourceId", w.resourceId().toString());
                params.put("startAt", w.startAt().toString());
                notifications.notifyOnce(context, OperationalNotificationConsumer.id(job.idempotencyKey(), CODE,
                        w.venueId()), NotificationRequest.of(CODE, NotificationRecipientType.VENUE, w.venueId(), params));
                yield JobOutcome.done();
            }
        };
    }
}
