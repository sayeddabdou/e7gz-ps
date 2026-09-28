package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Resource operational restriction — {@code ck_resource_restriction} (FR-VEN-13). */
@Schema(enumAsRef = true)
public enum OperationalRestriction {
    NONE,
    OWNER_REVIEW_REQUIRED
}
