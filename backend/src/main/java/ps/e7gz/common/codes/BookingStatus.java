package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Booking status - {@code ck_booking_status_values}, {@code ck_booking_history_status_values} (from_status,
 * to_status).
 */
@Schema(enumAsRef = true)
public enum BookingStatus {
    PENDING,
    VENUE_RESPONSE_EXPIRED,
    AWAITING_CUSTOMER_CONFIRMATION,
    CONFIRMED,
    ACTIVE,
    COMPLETED,
    REJECTED,
    CANCELLED,
    NO_SHOW
}
