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

    /**
     * Spec v5 section 52.6 "Verified Player Revoked" (E7-72; PO decision 19 Oct 2026: admin alert inbox only). SEV_3,
     * informational: the revocation and its tournament cascade are already done in the same command. About the USER,
     * with the transition ID and its cause (ADMIN or TRUST_CORRECTION); never a name or a reason text.
     */
    /** Spec v5 section 52.6 "Emergency declarations" (E7-80, Q1). SEV_3: information, nothing for the admin to fix. */
    public static NewAdminAlert emergencyDeclared(UUID emergencyId, UUID venueId, String scope, UUID resourceId,
                                                  UUID correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("emergencyId", Objects.requireNonNull(emergencyId, "emergencyId").toString());
        payload.put("venueId", Objects.requireNonNull(venueId, "venueId").toString());
        payload.put("scope", Objects.requireNonNull(scope, "scope"));
        if (resourceId != null) {
            payload.put("resourceId", resourceId.toString());
        }
        return new NewAdminAlert(AdminAlertKind.EMERGENCY_DECLARED, AdminAlertSeverity.SEV_3, "EMERGENCY", emergencyId,
                payload, correlationId);
    }

    /** Spec v5 section 52.6 "Venue Account Disabled" (E7-80, Q1). SEV_3: the admin made the change. */
    public static NewAdminAlert venueAccountDisabled(UUID venueAccountId, UUID venueId, UUID adminId,
                                                     UUID correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("venueAccountId", Objects.requireNonNull(venueAccountId, "venueAccountId").toString());
        payload.put("venueId", Objects.requireNonNull(venueId, "venueId").toString());
        payload.put("adminId", Objects.requireNonNull(adminId, "adminId").toString());
        return new NewAdminAlert(AdminAlertKind.VENUE_ACCOUNT_DISABLED, AdminAlertSeverity.SEV_3, "VENUE_ACCOUNT",
                venueAccountId, payload, correlationId);
    }

    public static NewAdminAlert verifiedPlayerRevoked(UUID userId, UUID transitionId, String cause,
                                                      UUID correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", Objects.requireNonNull(userId, "userId").toString());
        payload.put("transitionId", Objects.requireNonNull(transitionId, "transitionId").toString());
        payload.put("cause", Objects.requireNonNull(cause, "cause"));
        payload.put("correlationId", Objects.requireNonNull(correlationId, "correlationId").toString());
        return new NewAdminAlert(AdminAlertKind.ADMIN_VERIFIED_PLAYER_REVOKED, AdminAlertSeverity.SEV_3, "USER", userId,
                payload, correlationId);
    }

    /**
     * FR-ATT-26 (E7-78, PO decision E2 19 Nov 2026): a Venue Account disable overlaps an ACTIVE Online Booking. SEV_3,
     * informational - the session continues and nothing is broken. About the BOOKING, with the venue, the session
     * interval, the disable instant, the attendance evidence standing at that instant and whether Platform Attendance
     * Protection may be required (no standing check-in, Late Present or no-show before the disable, arch 07 section
     * 7.5). Ids and instants only.
     *
     * @param evidence the standing evidence instants by name ({@code checkInAt}, {@code latePresentAt},
     *                 {@code noShowAt}), each absent when none stands
     */
    public static NewAdminAlert accountDisableOverlapsActiveBooking(UUID venueId, UUID bookingId, Instant startAt,
                                                                    Instant endAt, Instant disabledAt,
                                                                    Map<String, Instant> evidence,
                                                                    boolean protectionMayBeRequired,
                                                                    UUID correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("venueId", Objects.requireNonNull(venueId, "venueId").toString());
        payload.put("bookingId", Objects.requireNonNull(bookingId, "bookingId").toString());
        payload.put("startAt", Objects.requireNonNull(startAt, "startAt").toString());
        payload.put("endAt", Objects.requireNonNull(endAt, "endAt").toString());
        payload.put("disabledAt", Objects.requireNonNull(disabledAt, "disabledAt").toString());
        Map<String, Object> standing = new LinkedHashMap<>();
        Objects.requireNonNull(evidence, "evidence").forEach((name, at) -> standing.put(name, at.toString()));
        payload.put("attendanceEvidence", standing);
        payload.put("protectionMayBeRequired", protectionMayBeRequired);
        payload.put("correlationId", Objects.requireNonNull(correlationId, "correlationId").toString());
        return new NewAdminAlert(AdminAlertKind.ACCOUNT_DISABLE_OVERLAPS_ACTIVE_BOOKING, AdminAlertSeverity.SEV_3,
                "BOOKING", bookingId, payload, correlationId);
    }

    /**
     * Product v5.0 section 52.6, E7-71 (PO decision 20 Oct 2026: the alert inbox only): a Ban was activated. SEV_3 -
     * information for the Platform Admin, nothing is broken. Subject: the User; ids, stage and instants only.
     *
     * @param cancelledBookings how many bookings the Ban's cascade cancelled (FR-TRS-20)
     */
    public static NewAdminAlert userBanActivated(UUID userId, UUID banId, int penaltyStage, Instant activatedAt,
                                                 Instant endsAt, int cancelledBookings, UUID correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", Objects.requireNonNull(userId, "userId").toString());
        payload.put("banInstanceId", Objects.requireNonNull(banId, "banId").toString());
        payload.put("penaltyStage", penaltyStage);
        payload.put("activatedAt", Objects.requireNonNull(activatedAt, "activatedAt").toString());
        payload.put("endsAt", Objects.requireNonNull(endsAt, "endsAt").toString());
        payload.put("cancelledBookings", cancelledBookings);
        payload.put("correlationId", Objects.requireNonNull(correlationId, "correlationId").toString());
        return new NewAdminAlert(AdminAlertKind.ADMIN_USER_BAN_ACTIVATED, AdminAlertSeverity.SEV_3, "USER", userId,
                payload, correlationId);
    }
}
