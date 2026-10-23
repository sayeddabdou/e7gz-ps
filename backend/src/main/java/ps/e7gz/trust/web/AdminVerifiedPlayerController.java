package ps.e7gz.trust.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.VerifiedPlayerQualification;
import ps.e7gz.common.codes.VerifiedPlayerStatus;
import ps.e7gz.common.codes.VerifiedPlayerTransitionCause;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;
import ps.e7gz.trust.web.AdminTrustController.AdminTrustReasonRequest;

/**
 * Platform Admin Verified Player management (arch 09 §5, FR-ADM-05, FR-ACC-15, arch 07 §6.2). Stubs of the Sprint 4
 * contract (E7-65) until E7-72. Each action needs a reason, creates a new transition ID (FR-ACC-12), is audited and
 * notifies the customer (FR-NTF-11); admin SUSPENDED and REVOKED are protected from automatic regain.
 */
@RestController
@RequestMapping("/api/v1/admin/verified-players")
@Tag(name = "admin-verified-players")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminVerifiedPlayerController {

    @GetMapping
    @Operation(operationId = "adminListVerifiedPlayers", summary = "Customers with a Verified Player status",
            description = "Every customer whose operational status is not NOT_VERIFIED, or who ever had a Verified "
                    + "Player transition; status filters exactly. Newest status change first, cursor paging (arch 09 "
                    + "§1.2). Implemented by E7-72.")
    public DataResponse<PageView<AdminVerifiedPlayer>> list(
            @RequestParam(required = false) VerifiedPlayerStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{userId}/suspend")
    @Operation(operationId = "adminSuspendVerifiedPlayer", summary = "VERIFIED → SUSPENDED (FR-ADM-05)",
            description = "Only from VERIFIED (arch 07 §6.2); otherwise ADMIN_ACTION_NOT_ALLOWED. Runs the pending / "
                    + "waiting tournament cascade once. Implemented by E7-72.")
    @ApiError(status = 403, codes = ApiErrorCode.ACCOUNT_DELETED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminVerifiedPlayer> suspend(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminTrustReasonRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{userId}/revoke")
    @Operation(operationId = "adminRevokeVerifiedPlayer", summary = "VERIFIED → REVOKED by the admin (FR-ADM-05)",
            description = "Only from VERIFIED (arch 07 §6.2); otherwise ADMIN_ACTION_NOT_ALLOWED. An admin REVOKED is "
                    + "never restored automatically (FR-ACC-15). Implemented by E7-72.")
    @ApiError(status = 403, codes = ApiErrorCode.ACCOUNT_DELETED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminVerifiedPlayer> revoke(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminTrustReasonRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{userId}/restore")
    @Operation(operationId = "adminRestoreVerifiedPlayer",
            summary = "Restore from SUSPENDED or REVOKED: VERIFIED if qualified now, else NOT_VERIFIED (FR-ACC-15)",
            description = "Any other current status is ADMIN_ACTION_NOT_ALLOWED. Implemented by E7-72.")
    @ApiError(status = 403, codes = ApiErrorCode.ACCOUNT_DELETED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminVerifiedPlayer> restore(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminTrustReasonRequest request) {
        throw NotImplementedYet.stub();
    }

    @Schema(name = "AdminVerifiedPlayer")
    public record AdminVerifiedPlayer(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID userId,
            @NotNull @Schema(example = "Omar Hassan") String fullName,
            @NotNull @Schema(example = "omar_h") String username,
            @NotNull VerifiedPlayerQualification qualification,
            @NotNull @Schema(example = "12") Integer completedBookings,
            @NotNull VerifiedPlayerStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "Cause of the latest transition; null before any.")
            VerifiedPlayerTransitionCause statusCause,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-20T12:00:00Z")
            Instant statusSince) {
    }
}
