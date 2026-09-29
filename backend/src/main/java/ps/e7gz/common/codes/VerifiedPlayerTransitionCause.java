package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Cause of a Verified Player transition - {@code ck_verified_player_transition_cause}. */
@Schema(enumAsRef = true)
public enum VerifiedPlayerTransitionCause {
    TRUST_QUALIFICATION,
    TRUST_CORRECTION,
    ADMIN
}
