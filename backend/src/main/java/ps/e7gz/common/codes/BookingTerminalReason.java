package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Reason of a terminal booking outcome (ADR-016; never a booking event or an API error code) - {@code
 * ck_booking_terminal_reason_values}, {@code ck_booking_history_terminal_reason_values}, {@code
 * ck_booking_fee_refund_terminal_reason}.
 */
@Schema(enumAsRef = true)
public enum BookingTerminalReason {
    CUSTOMER_CANCELLED,
    CUSTOMER_DECLINED_LATE_CONFIRM,
    CUSTOMER_RECONFIRM_TIMEOUT,
    VENUE_REJECTED,
    REJECTED_AFTER_EXPIRY,
    VENUE_CANCELLED_CONFIRMED_BOOKING,
    VENUE_CANCELLED_MANUAL_BOOKING,
    VENUE_DID_NOT_RESPOND_IN_TIME,
    VENUE_OPENING_HOURS_CHANGED,
    VENUE_CLOSURE,
    VENUE_EMERGENCY_CLOSURE,
    RESOURCE_EMERGENCY_OUTAGE,
    VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY,
    RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY,
    LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY,
    RESOURCE_OWNER_REVIEW_REQUIRED,
    VENUE_ACCOUNT_DISABLED,
    SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR,
    USER_BANNED,
    ADMIN_CANCELLED,
    ADMIN_CORRECTION,
    ADMIN_NO_SHOW_REVERSAL
}
