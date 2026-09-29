package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Kind of a booking history row - {@code ck_booking_history_entry_kind}. */
@Schema(enumAsRef = true)
public enum BookingHistoryEntryKind {
    STATUS_TRANSITION,
    BOOKING_EVENT
}
