package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Principal type of a refresh token - {@code ck_refresh_token_subject_type}. */
@Schema(enumAsRef = true)
public enum AuthSubjectType {
    CUSTOMER,
    VENUE,
    ADMIN
}
