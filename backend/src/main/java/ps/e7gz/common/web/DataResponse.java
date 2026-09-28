package ps.e7gz.common.web;

import jakarta.validation.constraints.NotNull;

/** The success envelope: {@code { "data": …, "meta": { … } }} (arch 09 §1.3). */
public record DataResponse<T>(@NotNull T data, @NotNull ResponseMeta meta) {
}
