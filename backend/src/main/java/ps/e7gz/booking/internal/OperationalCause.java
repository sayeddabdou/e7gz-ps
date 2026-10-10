package ps.e7gz.booking.internal;

import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.error.ApiErrorCode;

/**
 * The operational causes that stop a venue's Accept (FR-BOK-16, arch 06 section 2.1): the booking is CANCELLED under
 * the cause's own reason - never a venue non-response reason - and the venue gets the matching 422 (PO decisions 5 Oct
 * 2026 and D3 10 Oct 2026). Each is identified explicitly from its own state, never inferred from a failing
 * availability check. The constants are in FR-BOK-23's frozen priority (owner review, resource emergency, venue
 * emergency), which decides when several hold at once.
 */
enum OperationalCause {

    /** Resource operational restriction OWNER_REVIEW_REQUIRED (FR-VEN-13, FR-OPS-30). */
    OWNER_REVIEW(BookingTerminalReason.RESOURCE_OWNER_REVIEW_REQUIRED, ApiErrorCode.RESOURCE_NOT_ELIGIBLE),
    /** An unresolved RESOURCE-scoped Emergency of the booked Resource. */
    RESOURCE_EMERGENCY(BookingTerminalReason.RESOURCE_EMERGENCY_OUTAGE, ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE),
    /** An unresolved VENUE-scoped Emergency of the Venue. */
    VENUE_EMERGENCY(BookingTerminalReason.VENUE_EMERGENCY_CLOSURE, ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE);

    private final BookingTerminalReason reason;
    private final ApiErrorCode code;

    OperationalCause(BookingTerminalReason reason, ApiErrorCode code) {
        this.reason = reason;
        this.code = code;
    }

    BookingTerminalReason reason() {
        return reason;
    }

    ApiErrorCode code() {
        return code;
    }
}
