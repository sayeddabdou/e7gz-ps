package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Resource reservation type - {@code ck_reservation_type}. */
@Schema(enumAsRef = true)
public enum ReservationType {
    BOOKING,
    LATE_CONFIRM_HOLD,
    TOURNAMENT_APPROVAL_HOLD,
    TOURNAMENT_HOLD,
    MAINTENANCE,
    CLOSURE
}
