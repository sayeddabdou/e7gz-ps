package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Resolution of an owner review - {@code ck_owner_review_resolution}. */
@Schema(enumAsRef = true)
public enum OwnerReviewResolution {
    RETURN_TO_SERVICE,
    EXTEND_MAINTENANCE,
    DISABLE,
    ARCHIVE
}
