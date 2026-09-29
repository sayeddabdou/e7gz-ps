package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What an emergency was converted to (Venue scope: CLOSURE, OPENING_HOURS_CHANGE; Resource scope: MAINTENANCE,
 * DISABLED, ARCHIVED) - {@code ck_emergency_conversion_target} (converted_to, both branches).
 */
@Schema(enumAsRef = true)
public enum EmergencyConversionTarget {
    CLOSURE,
    OPENING_HOURS_CHANGE,
    MAINTENANCE,
    DISABLED,
    ARCHIVED
}
