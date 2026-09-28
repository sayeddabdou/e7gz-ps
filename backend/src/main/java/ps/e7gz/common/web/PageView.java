package ps.e7gz.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * A cursor page (arch 09 §1.2: {@code ?cursor=&limit=} with {@code nextCursor} in the response). §1.2 does not say
 * where {@code nextCursor} sits; here it is next to the items inside {@code data}. {@code nextCursor} is null on the
 * last page.
 */
public record PageView<T>(
        @NotNull List<T> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "eyJvZmZzZXQiOjIwfQ")
        String nextCursor) {
}
