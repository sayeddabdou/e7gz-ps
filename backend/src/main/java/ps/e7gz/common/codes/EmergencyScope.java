package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Scope of an emergency - {@code ck_emergency_scope}. */
@Schema(enumAsRef = true)
public enum EmergencyScope {
    VENUE,
    RESOURCE
}
