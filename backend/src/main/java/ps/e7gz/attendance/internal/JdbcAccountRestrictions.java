package ps.e7gz.attendance.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.admin.api.AdminAlerts;
import ps.e7gz.admin.api.NewAdminAlert;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.venue.api.AccountRestrictions;

/**
 * {@link AccountRestrictions} on the two tables this module owns for Platform Attendance Protection (arch 03 section
 * 1, module 11; arch 07 section 7.5; E7-78):
 * <ul>
 *   <li>{@code iam.venue_account_restriction_interval} - one row per disable, {@code disabled_at} the command instant,
 *       closed once by the re-enable ({@code tr_venue_restriction_interval_immutable}). E7-118's T6 reads it
 *       ({@link JdbcVenueAccountRestrictionsAt}), and so do the attendance guards: no evidence can be recorded inside
 *       an interval ({@code attendance.guard_attendance_record_write});</li>
 *   <li>{@code attendance.attendance_protection_candidate} - FR-ATT-20: at disable time only a candidate and its audit
 *       context, {@code recorded_at} = {@code disabled_at} ({@code attendance.validate_protection_candidate}); the
 *       effective Trust event comes when the attendance resolves ({@link AttendanceProtection}).
 *       {@code uq_attendance_candidate_interval} keeps one per booking per interval, so a booking that spans two
 *       disable/re-enable cycles has two candidates and still at most one protection event (FR-ATT-21).</li>
 * </ul>
 * With each candidate, in the same command (PO decision E2, 19 Nov 2026): the customer's
 * {@code ActiveSessionAttendanceRecordingAffected} event - its notification id is derived from the event, so a second
 * delivery creates nothing - and the Platform Admin's operational alert (FR-ATT-26).
 */
@Component
class JdbcAccountRestrictions implements AccountRestrictions {

    static final String OPEN = """
            INSERT INTO iam.venue_account_restriction_interval (id, venue_id, disabled_at, source, admin_id, reason,
                                                                correlation_id)
            VALUES (:id, :venueId, :at, 'ADMIN', :adminId, :reason, :correlationId)
            """;

    /** The one permitted mutation: {@code reenabled_at} NULL -> the re-enable instant. */
    static final String CLOSE = """
            UPDATE iam.venue_account_restriction_interval
               SET reenabled_at = :at
             WHERE venue_id = :venueId AND reenabled_at IS NULL
            """;

    static final String CANDIDATE = """
            INSERT INTO attendance.attendance_protection_candidate (id, booking_id, restriction_interval_id,
                                                                    recorded_at, audit_context)
            VALUES (:id, :bookingId, :intervalId, :at, CAST(:context AS jsonb))
            """;

    private final AttendanceStore store;
    private final Outbox outbox;
    private final AdminAlerts alerts;
    private final ObjectMapper json;

    JdbcAccountRestrictions(AttendanceStore store, Outbox outbox, AdminAlerts alerts, ObjectMapper json) {
        this.store = store;
        this.outbox = outbox;
        this.alerts = alerts;
        this.json = json;
    }

    @Override
    public UUID open(CommandContext command, UUID venueId, UUID adminId, String reason, UUID correlationId) {
        UUID id = UUID.randomUUID();
        command.jdbc().sql(OPEN)
                .param("id", id)
                .param("venueId", Objects.requireNonNull(venueId, "venueId"))
                .param("at", utc(command.commandNow()))
                .param("adminId", Objects.requireNonNull(adminId, "adminId"))
                .param("reason", Objects.requireNonNull(reason, "reason"))
                .param("correlationId", Objects.requireNonNull(correlationId, "correlationId"))
                .update();
        return id;
    }

    @Override
    public boolean close(CommandContext command, UUID venueId) {
        return command.jdbc().sql(CLOSE)
                .param("venueId", Objects.requireNonNull(venueId, "venueId"))
                .param("at", utc(command.commandNow()))
                .update() > 0;
    }

    @Override
    public void activeSessionsAffected(CommandContext command, UUID venueId, UUID intervalId,
                                       List<ActiveSession> sessions) {
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(intervalId, "intervalId");
        if (sessions.isEmpty()) {
            return;
        }
        Instant disabledAt = command.commandNow();
        Map<UUID, AttendanceStore.Evidence> evidence = new LinkedHashMap<>();
        store.find(command, sessions.stream().map(ActiveSession::bookingId).toList())
                .forEach(row -> evidence.put(row.bookingId(), row));
        for (ActiveSession session : sessions) {
            AttendanceStore.Evidence held = evidence.getOrDefault(session.bookingId(),
                    AttendanceStore.Evidence.none(session.bookingId()));
            Map<String, Instant> standing = standing(held);
            // arch 07 section 7.5 conditions 4 and 5 (FR-ATT-15, FR-ATT-16): evidence recorded before the disable stays
            // authoritative, so protection can apply only when none stands now
            boolean protectionMayBeRequired = standing.isEmpty();
            command.jdbc().sql(CANDIDATE)
                    .param("id", UUID.randomUUID())
                    .param("bookingId", session.bookingId())
                    .param("intervalId", intervalId)
                    .param("at", utc(disabledAt))
                    .param("context", context(standing, protectionMayBeRequired))
                    .update();
            outbox.publish(command, recordingAffected(venueId, intervalId, disabledAt, session));
            alerts.raise(command, NewAdminAlert.accountDisableOverlapsActiveBooking(venueId, session.bookingId(),
                    session.startAt(), session.endAt(), disabledAt, standing, protectionMayBeRequired,
                    CorrelationId.require()));
        }
    }

    /** The evidence that stands (recorded and not reversed) at the disable, by name; empty when none does. */
    static Map<String, Instant> standing(AttendanceStore.Evidence evidence) {
        Map<String, Instant> standing = new LinkedHashMap<>();
        if (evidence.activeCheckIn() != null) {
            standing.put("checkInAt", evidence.activeCheckIn());
        }
        if (evidence.activeLatePresent() != null) {
            standing.put("latePresentAt", evidence.activeLatePresent());
        }
        if (evidence.activeNoShow() != null) {
            standing.put("noShowAt", evidence.activeNoShow());
        }
        return standing;
    }

    private String context(Map<String, Instant> standing, boolean protectionMayBeRequired) {
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, String> evidence = new LinkedHashMap<>();
        standing.forEach((name, at) -> evidence.put(name, at.toString()));
        context.put("attendanceEvidence", evidence);
        context.put("protectionMayBeRequired", protectionMayBeRequired);
        try {
            return json.writeValueAsString(context);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("protection candidate context", impossible);
        }
    }

    private static NewOutboxEvent recordingAffected(UUID venueId, UUID intervalId, Instant disabledAt,
                                                    ActiveSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", session.bookingId());
        payload.put("customerId", session.customerId());
        payload.put("venueId", venueId);
        payload.put("resourceId", session.resourceId());
        payload.put("startAt", session.startAt().toString());
        payload.put("endAt", session.endAt().toString());
        payload.put("restrictionIntervalId", intervalId);
        payload.put("disabledAt", disabledAt.toString());
        return new NewOutboxEvent(IntegrationEvents.ACTIVE_SESSION_ATTENDANCE_RECORDING_AFFECTED,
                AttendanceEvents.BOOKING_AGGREGATE, session.bookingId(), payload);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC);
    }
}
