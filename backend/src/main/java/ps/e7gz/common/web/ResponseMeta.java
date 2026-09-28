package ps.e7gz.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** {@code meta} on every response, success or failure (arch 09 §1.3). {@code serverTime} is RFC 3339 UTC (§1.2). */
public record ResponseMeta(
        @NotNull @Schema(example = "018f3c2e-7b1a-7c4d-9e2f-0a1b2c3d4e5f") String correlationId,
        @NotNull @Schema(example = "2026-10-12T17:04:11Z") Instant serverTime) {
}
