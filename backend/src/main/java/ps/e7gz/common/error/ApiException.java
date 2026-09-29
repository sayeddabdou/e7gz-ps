package ps.e7gz.common.error;

import java.util.Map;
import java.util.Objects;

/**
 * A refusal the application decided: an {@link ApiErrorCode}, the input at fault when there is one, and
 * machine-readable details (arch 09 §1.3). Never carries rule text (ADR-021): the exception message is the code name,
 * for logs only, and is never sent to the client.
 */
public class ApiException extends RuntimeException {

    private final ApiErrorCode code;
    private final String field;
    private final Map<String, Object> details;

    public ApiException(ApiErrorCode code) {
        this(code, null, Map.of());
    }

    public ApiException(ApiErrorCode code, String field) {
        this(code, field, Map.of());
    }

    public ApiException(ApiErrorCode code, String field, Map<String, Object> details) {
        super(Objects.requireNonNull(code, "code").name(), null, false, false);
        this.code = code;
        this.field = field;
        this.details = Map.copyOf(Objects.requireNonNull(details, "details"));
    }

    public ApiErrorCode code() {
        return code;
    }

    /** The input at fault, or {@code null}. */
    public String field() {
        return field;
    }

    public Map<String, Object> details() {
        return details;
    }
}
