package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Source of a loyalty reward application - {@code ck_reward_application_source} (source_kind; literal branches). */
@Schema(enumAsRef = true)
public enum RewardSourceKind {
    BOOKING,
    REFERRAL
}
