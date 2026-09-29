package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Why a referral is INVALID - {@code ck_referral_shape} (invalidation_kind). */
@Schema(enumAsRef = true)
public enum ReferralInvalidationKind {
    INVALID_CODE,
    SELF_REFERRAL,
    ADMIN_FRAUD
}
