package ps.e7gz.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import ps.e7gz.common.error.ApiErrorCode;

/**
 * The failure envelope (arch 09 §1.3): a closed {@link ApiErrorCode}, the input at fault when there is one, and
 * machine-readable details — never prose.
 *
 * <p>{@code field} and {@code details} are optional in the contract ({@code field?: string}, not nullable): when absent
 * they are omitted from the JSON, never sent as {@code null} (E7-12).
 */
public record ErrorResponse(@NotNull Error error, @NotNull ResponseMeta meta) {

    @Schema(name = "ErrorBody")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Error(
            @NotNull ApiErrorCode code,
            @Schema(description = "The input at fault, when a specific one is.", example = "startAt") String field,
            @Schema(description = "Machine-readable details; never prose.",
                    additionalProperties = Schema.AdditionalPropertiesValue.TRUE) Map<String, Object> details) {
    }
}
