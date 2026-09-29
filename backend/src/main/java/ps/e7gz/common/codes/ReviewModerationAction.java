package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Review moderation action - {@code ck_review_moderation_action}. */
@Schema(enumAsRef = true)
public enum ReviewModerationAction {
    APPROVE,
    HIDE,
    RESTORE,
    REMOVE
}
