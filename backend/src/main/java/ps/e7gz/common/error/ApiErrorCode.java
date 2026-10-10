package ps.e7gz.common.error;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The closed error-code enum returned in {@code error.code} (arch 09 §1.5, ADR-016). Clients map codes to text; the
 * backend never sends rule text (ADR-021).
 *
 * <p>Each member carries the HTTP status arch 09 §1.4–§1.5 gives it. The domain members are exactly the ones §1.5
 * lists. The general members at the end ({@code VALIDATION_FAILED} … {@code RATE_LIMITED}) are the Product Owner's
 * decision of 29 Sep 2026 (E7-12), recorded in arch 09 §1.5; a more specific domain code always wins over them.
 * An unexpected failure has no code: it is a 500 carrying only the correlation ID (arch 09 §1.4).
 *
 * <p>The OTP group is listed in §1.5 as "422 / 429": the three limits of arch 09 §6 (attempts per code, resend delay,
 * sends per hour) are 429 as §1.4 says for OTP rate limiting; a wrong or expired code is 422.
 */
@Schema(enumAsRef = true)
public enum ApiErrorCode {
    PHONE_VERIFICATION_REQUIRED(403),
    OTP_INVALID(422),
    OTP_EXPIRED(422),
    OTP_ATTEMPTS_EXCEEDED(429),
    OTP_RESEND_TOO_SOON(429),
    OTP_HOURLY_LIMIT_REACHED(429),
    USERNAME_TAKEN(422),
    USERNAME_COOLDOWN_ACTIVE(422),
    /** FR-ACC-01: Phone is unique. Product Owner decision of 30 Sep 2026 (E7-13), recorded in arch 09 §1.5. */
    PHONE_TAKEN(422),
    ACCOUNT_BANNED_FOR_THIS_ACTION(403),
    BOOKING_LEAD_TIME_TOO_SHORT(422),
    BOOKING_DURATION_INVALID(422),
    COMMITMENT_LIMIT_REACHED(422),
    OVERLAPPING_COMMITMENT(422),
    INTERVAL_NOT_WITHIN_ONE_SESSION(422),
    ONLINE_INTAKE_WINDOW_CLOSED(422),
    VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY(422),
    RESOURCE_NOT_ELIGIBLE(422),
    VENUE_SELLING_BLOCK_ACTIVE(422),
    RESOURCE_SELLING_BLOCK_ACTIVE(422),
    SLOT_NO_LONGER_AVAILABLE(409),
    DEADLINE_PASSED(422),
    CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM(422),
    LATE_CONFIRM_RESOURCE_CONFLICT(409),
    LATE_CONFIRM_CUSTOMER_CONFLICT(409),
    MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING(422),
    MANUAL_BOOKING_BACKDATING_NOT_ALLOWED(422),
    CHECK_IN_WINDOW_CLOSED(422),
    LATE_PRESENT_NOT_AVAILABLE(422),
    NO_SHOW_NOT_AVAILABLE(422),
    ATTENDANCE_REVERSAL_REQUIRES_ADMIN(403),
    OWNER_PASSWORD_REQUIRED(423),
    OWNER_PASSWORD_INVALID(423),
    RESOURCE_HAS_BLOCKING_RESERVATIONS(422),
    TOURNAMENT_CONFLICT_BLOCKS_SAVE(422),
    AFFECTED_SET_CHANGED_REFRESH_PREVIEW(409),
    CLOSURE_OVERLAPS_ACTIVE_SESSION(422),
    MAINTENANCE_OVERLAPS_RESERVATION(422),
    EMERGENCY_EXPECTED_END_OUT_OF_SESSION(422),
    EMERGENCY_CANNOT_END_RESOURCE_NOT_OPERATIONAL(422),
    CONVERSION_TARGET_NOT_ALLOWED(422),
    OWNER_REVIEW_RESOLUTION_BLOCKED(422),
    TOURNAMENT_CREATION_CUTOFF_PASSED(422),
    VERIFIED_PLAYER_REQUIRED(403),
    PENDING_TOURNAMENT_REQUEST_EXISTS(422),
    TOURNAMENT_RETRY_COOLDOWN_ACTIVE(422),
    TOURNAMENT_CAPACITY_REACHED(422),
    TOURNAMENT_JOIN_WINDOW_CLOSED(422),
    TOURNAMENT_RESOURCE_CONFLICT(409),
    REVIEW_COOLDOWN_ACTIVE(422),
    REVIEW_REPORT_ALREADY_OPEN(422),
    AREA_HAS_ACTIVE_VENUES(422),
    AREA_INACTIVE(422),
    /** FR-VEN-12: another area already has this name (trimmed, case-insensitive). PO decision 2 Oct 2026. */
    AREA_NAME_TAKEN(422),
    /** FR-ADM-02, FR-VEN-01: another venue account has this login identifier. PO decision 2 Oct 2026 (E7-22). */
    VENUE_LOGIN_IDENTIFIER_TAKEN(422),
    /** FR-VEN-01: the venue already has its one shared account. PO decision 2 Oct 2026 (E7-22). */
    VENUE_ACCOUNT_ALREADY_EXISTS(422),
    /** FR-VEN-07: the venue's current marketplace status does not allow this change. PO decision 2 Oct 2026 (E7-22). */
    MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED(422),
    /** FR-ADM-02: an activity removed from a venue still has rooms, prices or availability. PO decision 2 Oct 2026. */
    VENUE_ACTIVITY_IN_USE(422),
    /** PCR-005: the photo is not JPEG, PNG or WebP, or is too large. PO decision 2 Oct 2026. */
    VENUE_IMAGE_NOT_ACCEPTED(422),
    /** PCR-005: the venue already has the maximum number of visible photos. PO decision 2 Oct 2026. */
    VENUE_IMAGE_LIMIT_REACHED(422),
    /** PCR-005: confirm was called before the file reached storage, or it does not match. PO decision 2 Oct 2026. */
    VENUE_IMAGE_NOT_UPLOADED(422),
    ACCOUNT_DELETED(403),
    REVIEW_ALREADY_EXISTS(422),
    REVIEW_REMOVED_NOT_RESTORABLE(422),
    IDEMPOTENCY_KEY_REUSED(422),
    /**
     * The booking is no longer in the state the action needs (e.g. the customer cancelled a second earlier): refresh
     * and look again. PO decision 5 Oct 2026 (E7-39).
     */
    BOOKING_STATE_CHANGED(409),
    /**
     * Admin "try again" on a notification delivery that is not DEAD_LETTER (already sent, or still being retried):
     * nothing to retry. PO decision 10 Oct 2026 (E7-43).
     */
    NOTIFICATION_DELIVERY_NOT_DEAD_LETTER(409),

    // General codes (arch 09 §1.4; Product Owner decision 29 Sep 2026, E7-12).
    /** A malformed or invalid request: unreadable body, bad parameter, failed bean validation. */
    VALIDATION_FAILED(400),
    /** Missing, invalid or expired access token. */
    UNAUTHENTICATED(401),
    /** Authenticated, but not permitted for this actor or account state. */
    FORBIDDEN(403),
    /** Not found, or not visible to this principal. */
    NOT_FOUND(404),
    /** A request rate limit (arch 09 §6) other than the OTP limits, which have their own codes. */
    RATE_LIMITED(429);

    private final int httpStatus;

    ApiErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    /** The HTTP status this code is always returned with (arch 09 §1.4–§1.5). */
    public int httpStatus() {
        return httpStatus;
    }
}
