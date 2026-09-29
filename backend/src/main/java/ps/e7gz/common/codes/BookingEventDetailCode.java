package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Detail code of a LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED booking event - {@code
 * ck_booking_history_detail_code_scope} (detail_code).
 */
@Schema(enumAsRef = true)
public enum BookingEventDetailCode {
    RESOURCE_DISABLED,
    RESOURCE_ARCHIVED,
    RESOURCE_CONSOLE_CHANGED,
    RESOURCE_OWNER_REVIEW_REQUIRED
}
