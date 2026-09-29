package ps.e7gz.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * The body of an unexpected failure (HTTP 500, arch 09 §1.4): only {@code meta}, whose correlation ID lets support find
 * the logged cause. No error code, message, SQL or stack trace is ever sent.
 */
@Schema(description = "Unexpected failure: carries only the correlation ID (arch 09 §1.4).")
public record UnexpectedErrorResponse(@NotNull ResponseMeta meta) {
}
