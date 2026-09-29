package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Verified Player qualification - {@code ck_trust_projection_vp_qualification}. */
@Schema(enumAsRef = true)
public enum VerifiedPlayerQualification {
    NOT_QUALIFIED,
    QUALIFIED
}
