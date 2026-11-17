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
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.ResultAggregate;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.trust.internal.AdminVerifiedPlayers;
import ps.e7gz.trust.web.AdminTrustController.AdminTrustReasonRequest;

/**
 * Platform Admin Verified Player management (arch 09 §5, FR-ADM-05, FR-ACC-15, arch 07 §6.2, Spec v5 §51.5). Filled by
 * E7-72 ({@link AdminVerifiedPlayers}). Each action needs a reason, creates a new transition ID (FR-ACC-12), is audited
 * and notifies the customer (FR-NTF-11); admin SUSPENDED and REVOKED are protected from automatic regain. A current
 * status the action does not start from is ADMIN_ACTION_NOT_ALLOWED (PO decision D5, 17 Oct 2026).
 */
@RestController
@RequestMapping("/api/v1/admin/verified-players")
@Tag(name = "admin-verified-players")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminVerifiedPlayerController {

    /** {@code technical.idempotency_record.endpoint} of each action (arch 08 section 6). */
    static final String SUSPEND_ENDPOINT = "POST /api/v1/admin/verified-players/{userId}/suspend";
    static final String REVOKE_ENDPOINT = "POST /api/v1/admin/verified-players/{userId}/revoke";
    static final String RESTORE_ENDPOINT = "POST /api/v1/admin/verified-players/{userId}/restore";

    private static final StoredResponse<AdminVerifiedPlayer> ANSWERED = StoredResponse.of(AdminVerifiedPlayer.class,
            200, (AdminVerifiedPlayer player) -> new ResultAggregate("USER", player.userId()));

    private final AdminVerifiedPlayers players;
    private final Idempotency idempotency;
    private final ResponseMetaFactory meta;

    AdminVerifiedPlayerController(AdminVerifiedPlayers players, Idempotency idempotency, ResponseMetaFactory meta) {
        this.players = players;
        this.idempotency = idempotency;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "adminListVerifiedPlayers", summary = "Customers with a Verified Player status",
            description = "Every customer whose operational status is not NOT_VERIFIED, or who ever had a Verified "
                    + "Player transition; status filters exactly. Newest status change first, cursor paging (arch 09 "
                    + "§1.2).")
    public DataResponse<PageView<AdminVerifiedPlayer>> list(
            @RequestParam(required = false) VerifiedPlayerStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        AdminVerifiedPlayers.Page page = players.list(status, cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(AdminVerifiedPlayer::of).toList(),
                page.nextCursor()));
    }

    @PostMapping("/{userId}/suspend")
    @Operation(operationId = "adminSuspendVerifiedPlayer", summary = "VERIFIED → SUSPENDED (FR-ADM-05)",
            description = "Only from VERIFIED (arch 07 §6.2); otherwise ADMIN_ACTION_NOT_ALLOWED. Blocks creating new "
                    + "player-hosted tournaments and cancels nothing existing (FR-TRN-20).")
    @ApiError(status = 403, codes = ApiErrorCode.ACCOUNT_DELETED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminVerifiedPlayer> suspend(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminTrustReasonRequest request,
            @AuthenticationPrincipal AuthenticatedSubject admin) {
        return meta.data(players.suspend(admin.id(), userId, request.reason(),
                idempotency.request(admin, SUSPEND_ENDPOINT, idempotencyKey, Map.of("userId", userId), request),
                ANSWERED, AdminVerifiedPlayer::of).body());
    }

    @PostMapping("/{userId}/revoke")
    @Operation(operationId = "adminRevokeVerifiedPlayer", summary = "VERIFIED → REVOKED by the admin (FR-ADM-05)",
            description = "Only from VERIFIED (arch 07 §6.2); otherwise ADMIN_ACTION_NOT_ALLOWED. Runs the pending / "
                    + "waiting tournament cascade once for its transition (FR-TRN-20). An admin REVOKED is never "
                    + "restored automatically (FR-ACC-15).")
    @ApiError(status = 403, codes = ApiErrorCode.ACCOUNT_DELETED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminVerifiedPlayer> revoke(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminTrustReasonRequest request,
            @AuthenticationPrincipal AuthenticatedSubject admin) {
        return meta.data(players.revoke(admin.id(), userId, request.reason(),
                idempotency.request(admin, REVOKE_ENDPOINT, idempotencyKey, Map.of("userId", userId), request),
                ANSWERED, AdminVerifiedPlayer::of).body());
    }

    @PostMapping("/{userId}/restore")
    @Operation(operationId = "adminRestoreVerifiedPlayer",
            summary = "Restore from SUSPENDED or REVOKED: VERIFIED if qualified now, else NOT_VERIFIED (FR-ACC-15)",
            description = "Any other current status is ADMIN_ACTION_NOT_ALLOWED. Never resurrects a cancelled "
                    + "tournament (FR-TRN-20).")
    @ApiError(status = 403, codes = ApiErrorCode.ACCOUNT_DELETED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminVerifiedPlayer> restore(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminTrustReasonRequest request,
            @AuthenticationPrincipal AuthenticatedSubject admin) {
        return meta.data(players.restore(admin.id(), userId, request.reason(),
                idempotency.request(admin, RESTORE_ENDPOINT, idempotencyKey, Map.of("userId", userId), request),
                ANSWERED, AdminVerifiedPlayer::of).body());
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

        static AdminVerifiedPlayer of(AdminVerifiedPlayers.Player player) {
            return new AdminVerifiedPlayer(player.userId(), player.fullName(), player.username(),
                    player.qualification(), player.completedBookings(), player.status(), player.statusCause(),
                    player.statusSince());
        }
    }
}
