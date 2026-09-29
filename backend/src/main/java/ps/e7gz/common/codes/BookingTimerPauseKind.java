package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Which booking timer an emergency paused - {@code ck_booking_timer_pause_kind}. */
@Schema(enumAsRef = true)
public enum BookingTimerPauseKind {
    VENUE_RESPONSE,
    LATE_CONFIRM
}
