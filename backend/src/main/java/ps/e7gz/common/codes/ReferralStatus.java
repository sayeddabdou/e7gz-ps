package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Referral status - {@code ck_referral_status}. */
@Schema(enumAsRef = true)
public enum ReferralStatus {
    PENDING,
    REWARDED,
    INVALID
}
