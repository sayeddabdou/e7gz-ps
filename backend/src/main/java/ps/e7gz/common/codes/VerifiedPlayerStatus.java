package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Verified Player operational status - {@code ck_trust_projection_vp_operational_status}, {@code
 * ck_verified_player_transition_from_status}, {@code ck_verified_player_transition_to_status}.
 */
@Schema(enumAsRef = true)
public enum VerifiedPlayerStatus {
    NOT_VERIFIED,
    VERIFIED,
    SUSPENDED,
    REVOKED
}
