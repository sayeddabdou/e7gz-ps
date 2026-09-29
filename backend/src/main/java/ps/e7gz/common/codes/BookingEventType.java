package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Booking Event: the closed list of append-only booking history events (ADR-016; never a terminal reason or an
 * API error code) - {@code ck_booking_history_event_type_values}.
 */
@Schema(enumAsRef = true)
public enum BookingEventType {
    CHECK_IN_RECORDED,
    CHECK_IN_REVERSED,
    LATE_PRESENT_RECORDED,
    LATE_PRESENT_REVERSED,
    NO_SHOW_RECORDED,
    NO_SHOW_REVERSED,
    ATTENDANCE_CORRECTED,
    LATE_CONFIRM_FAILED_RESOURCE_CONFLICT,
    LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT,
    LATE_CONFIRM_FAILED_BOOKING_LIMIT,
    LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED,
    VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE
}
