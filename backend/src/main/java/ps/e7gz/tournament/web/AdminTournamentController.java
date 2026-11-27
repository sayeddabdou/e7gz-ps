package ps.e7gz.tournament.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.ResultAggregate;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.tournament.internal.AnsweredTournament;
import ps.e7gz.tournament.internal.TournamentCancellation;

/**
 * Platform Admin - Tournaments (arch 09 section 5, FR-ADM-08). This controller holds the cancellation of E7-93; the list
 * is the Sprint 6 contract's (E7-88). The Admin DTOs are their own (arch 09 section 1.1). The cancellation releases the
 * room holds, so the Idempotency-Key is required (ADR-015), and it is audited with the Admin's reason.
 */
@RestController
@RequestMapping("/api/v1/admin/tournaments")
@Tag(name = "admin-tournaments")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminTournamentController {

    /** {@code technical.idempotency_record.endpoint} (arch 08 section 6). */
    static final String CANCEL_ENDPOINT = "POST /api/v1/admin/tournaments/{tournamentId}/cancel";

    private final TournamentCancellation cancellation;
    private final Idempotency idempotency;
    private final ResponseMetaFactory meta;

    AdminTournamentController(TournamentCancellation cancellation, Idempotency idempotency,
                              ResponseMetaFactory meta) {
        this.cancellation = cancellation;
        this.idempotency = idempotency;
        this.meta = meta;
    }

    @PostMapping("/{tournamentId}/cancel")
    @Operation(operationId = "adminCancelTournament",
            summary = "Cancel any tournament that has not ended: CANCELLED / ADMIN_CANCELLED (FR-TRN-21, FR-ADM-08)",
            description = "Any state before COMPLETED, ACTIVE included; a reason is required and audited. A "
                    + "tournament already COMPLETED, REJECTED or CANCELLED, or one an emergency or owner review is "
                    + "cancelling (its cascade owns the reason, FR-TRN-31), is ADMIN_ACTION_NOT_ALLOWED. Every room "
                    + "hold still held is released. Implemented by E7-93.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminTournamentCancellation> cancel(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID tournamentId,
            @Valid @RequestBody AdminTournamentCancelRequest request,
            @AuthenticationPrincipal AuthenticatedSubject admin) {
        return meta.data(cancellation.cancelByAdmin(admin.id(), tournamentId, request.reason(),
                idempotency.request(admin, CANCEL_ENDPOINT, idempotencyKey, Map.of("tournamentId", tournamentId),
                        request),
                StoredResponse.of(AdminTournamentCancellation.class, 200,
                        (AdminTournamentCancellation c) -> new ResultAggregate(
                                CustomerTournamentController.TOURNAMENT_AGGREGATE, c.tournamentId())),
                AdminTournamentCancellation::of).body());
    }

    @Schema(name = "AdminTournamentCancelRequest")
    public record AdminTournamentCancelRequest(
            @NotBlank @Size(min = 1, max = 500)
            @Schema(example = "Duplicate tournament reported by the venue.") String reason) {
    }

    @Schema(name = "AdminTournamentCancellation")
    public record AdminTournamentCancellation(
            @NotNull UUID tournamentId,
            @NotNull @Schema(description = "CANCELLED.") TournamentStatus status,
            @NotNull @Schema(description = "ADMIN_CANCELLED.") TournamentTerminalReason terminalReason) {

        static AdminTournamentCancellation of(AnsweredTournament cancelled) {
            return new AdminTournamentCancellation(cancelled.id(), cancelled.status(), cancelled.terminalReason());
        }
    }
}
