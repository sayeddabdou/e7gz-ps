package ps.e7gz.admin.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One alert to raise (arch 11 section 5.4). The payload carries diagnostic facts only - ids, states, instants - never
 * rule text, a secret or full personal data (the database refuses credential keys: {@code admin.guard_admin_alert}).
 *
 * @param subjectType an upper-case identifier of what the alert is about, e.g. {@code BOOKING}
 */
public record NewAdminAlert(AdminAlertKind kind, AdminAlertSeverity severity, String subjectType, UUID subjectId,
                            Map<String, Object> payload, UUID correlationId) {

    public NewAdminAlert {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(subjectType, "subjectType");
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(correlationId, "correlationId");
        payload = Map.copyOf(payload);
    }

    /**
     * FR-BOK-28, NFR-29, arch 12 section 6.2 (PO decision 15 Oct 2026: built by E7-56, raised by E7-60 in the same
     * command as the cancellation): SEV_1 with the full diagnostic payload.
     *
     * @param conflictingSource what the inventory says holds or blocks the resource, when known
     */
    public static NewAdminAlert resourceStateIntegrityError(UUID bookingId, UUID resourceId, String unexpectedState,
                                                            Instant startAt, Instant detectedAt,
                                                            String conflictingSource, UUID correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", Objects.requireNonNull(bookingId, "bookingId").toString());
        payload.put("resourceId", Objects.requireNonNull(resourceId, "resourceId").toString());
        payload.put("unexpectedState", Objects.requireNonNull(unexpectedState, "unexpectedState"));
        payload.put("startAt", Objects.requireNonNull(startAt, "startAt").toString());
        payload.put("detectedAt", Objects.requireNonNull(detectedAt, "detectedAt").toString());
        if (conflictingSource != null) {
            payload.put("conflictingSource", conflictingSource);
        }
        payload.put("correlationId", Objects.requireNonNull(correlationId, "correlationId").toString());
        return new NewAdminAlert(AdminAlertKind.SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR, AdminAlertSeverity.SEV_1,
                "BOOKING", bookingId, payload, correlationId);
    }

    /**
     * E7-47 D11 (PO decisions 10 and 12 Oct 2026): a booking command found a data defect and rolled back. SEV_1 with the
     * booking ID, the detail and the correlation ID; raised after the rollback ({@link AdminAlerts#raiseAfterRollback}).
     */
    public static NewAdminAlert bookingIntegrityFailure(UUID bookingId, String detail, UUID correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", Objects.requireNonNull(bookingId, "bookingId").toString());
        payload.put("detail", Objects.requireNonNull(detail, "detail"));
        payload.put("correlationId", Objects.requireNonNull(correlationId, "correlationId").toString());
        return new NewAdminAlert(AdminAlertKind.BOOKING_INTEGRITY_FAILURE, AdminAlertSeverity.SEV_1, "BOOKING",
                bookingId, payload, correlationId);
    }
}
