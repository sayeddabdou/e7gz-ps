package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Review status - {@code ck_review_status}, {@code ck_review_moderation_status_values} (previous_status,
 * new_status).
 */
@Schema(enumAsRef = true)
public enum ReviewStatus {
    PENDING_MODERATION,
    ACTIVE,
    HIDDEN,
    REMOVED
}
