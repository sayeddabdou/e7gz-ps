package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Emergency status - {@code ck_emergency_status}. */
@Schema(enumAsRef = true)
public enum EmergencyStatus {
    ACTIVE,
    AWAITING_CONFIRMATION,
    ENDED,
    CONVERTED
}
