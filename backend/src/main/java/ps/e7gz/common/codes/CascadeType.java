package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Kind of a cascade execution - {@code ck_cascade_execution_type}. */
@Schema(enumAsRef = true)
public enum CascadeType {
    BAN,
    VERIFIED_PLAYER,
    OWNER_REVIEW,
    ACCOUNT_DISABLE,
    OTHER
}
