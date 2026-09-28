package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Trust Input Event type - {@code ck_trust_event_type}. */
@Schema(enumAsRef = true)
public enum TrustEventType {
    BOOKING_ATTENDED,
    BOOKING_ATTENDANCE_UNCONFIRMED,
    BOOKING_ATTENDED_INTERRUPTED,
    BOOKING_NO_SHOW,
    NO_SHOW_REVERSED,
    ATTENDANCE_CORRECTED,
    ADMIN_TRUST_ADJUSTMENT,
    VERIFIED_PLAYER_SUSPENDED,
    VERIFIED_PLAYER_REVOKED,
    VERIFIED_PLAYER_RESTORED,
    BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION
}
