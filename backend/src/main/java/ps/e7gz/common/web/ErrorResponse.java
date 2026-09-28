package ps.e7gz.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import ps.e7gz.common.error.ApiErrorCode;

/**
 * The failure envelope (arch 09 §1.3): a closed {@link ApiErrorCode}, the input at fault when there is one, and
 * machine-readable details — never prose.
 */
public record ErrorResponse(@NotNull Error error, @NotNull ResponseMeta meta) {

    @Schema(name = "ErrorBody")
    public record Error(
            @NotNull ApiErrorCode code,
            @Schema(description = "The input at fault, when a specific one is.", example = "startAt") String field,
            @Schema(description = "Machine-readable details; never prose.",
                    additionalProperties = Schema.AdditionalPropertiesValue.TRUE) Map<String, Object> details) {
    }
}
