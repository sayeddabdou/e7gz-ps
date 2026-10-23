package ps.e7gz.trust.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * Platform Admin Global Rules: the no-show ladder, read-only (arch 09 §5, FR-TRS-13, NFR-37; arch 01 §14 A8: a fixed
 * Product rule, never configuration). In the Sprint 4 contract by PO decision 20 Oct 2026 (E7-65), so the admin trust
 * screen (E7-75) has its data; a stub until E7-71, which owns the ladder constants. There is no write route.
 */
@RestController
@RequestMapping("/api/v1/admin/global-rules")
@Tag(name = "admin-global-rules")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminGlobalRulesController {

    @GetMapping
    @Operation(operationId = "adminGetGlobalRules", summary = "The no-show ladder, read-only (FR-TRS-13)",
            description = "One row per penalty stage, in order: at that stage, consecutiveNoShows eligible no-shows in "
                    + "a row start a ban of banDuration and advance the stage (FR-TRS-14). The last row is recurring: "
                    + "from that stage on, every consecutiveNoShows give the same ban. Implemented by E7-71.")
    public DataResponse<AdminGlobalRules> get() {
        throw NotImplementedYet.stub();
    }

    @Schema(name = "AdminGlobalRules")
    public record AdminGlobalRules(@NotNull List<AdminNoShowLadderStep> noShowLadder) {
    }

    /** One rung of the ladder (FR-TRS-13): stage 0 — 3 → 7 days, 1 — 2 → 1 month, 2 — 2 → 2 months, then every 3. */
    @Schema(name = "AdminNoShowLadderStep")
    public record AdminNoShowLadderStep(
            @NotNull @Schema(description = "The penalty stage this rung applies at.", example = "0")
            Integer penaltyStage,
            @NotNull @Schema(description = "Consecutive eligible no-shows that start the ban (the stage's threshold).",
                    example = "3") Integer consecutiveNoShows,
            @NotNull @Schema(description = "The ban length, an ISO-8601 period (P7D, P1M, P2M).", example = "P7D")
            String banDuration,
            @NotNull @Schema(description = "True on the last rung: it repeats for every later stage.", example = "false")
            Boolean recurring) {
    }
}
