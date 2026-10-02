package ps.e7gz.common.validation;

import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;

/** Optional text fields of an edit request: absent means unchanged; a field that is sent must not be blank. */
public final class TextFields {

    private TextFields() {
    }

    /** {@code null} stays {@code null} (unchanged); blank is {@code VALIDATION_FAILED} on {@code field}; else stripped. */
    public static String optional(String value, String field) {
        if (value == null) {
            return null;
        }
        if (value.isBlank()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, field);
        }
        return value.strip();
    }
}
