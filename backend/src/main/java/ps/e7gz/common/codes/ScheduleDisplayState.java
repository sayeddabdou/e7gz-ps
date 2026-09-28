package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** A cell of the Dashboard availability schedule — exactly the display states of arch 09 §4.1 (FR-VEN-37). */
@Schema(enumAsRef = true)
public enum ScheduleDisplayState {
    FREE,
    PENDING,
    AWAITING_CUSTOMER_CONFIRMATION,
    CONFIRMED,
    ACTIVE,
    MANUAL,
    TOURNAMENT_HOLD,
    MAINTENANCE,
    CLOSURE
}
