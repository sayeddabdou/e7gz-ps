package ps.e7gz.trust.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.BanInstanceStatus;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.ResultAggregate;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.trust.internal.AdminTrustCommands;

/**
 * Platform Admin trust remediation (arch 09 §5 "Trust", FR-TRS-27, FRS §2.10 reserved actions). In the Sprint 4
 * contract (E7-65); filled by E7-71 ({@link AdminTrustCommands}). Every action needs a reason and is audited with the
 * admin and the instant (FR-AUD-06), notifies the customer (FR-NTF-11) and is in ADR-015's scope (Idempotency-Key
 * required, D6). Ban routes sit under the user and keep {@code /correct} (PO decision D2, 17 Oct 2026); a ban no longer
 * ACTIVE, or a correction the history does not support, is ADMIN_ACTION_NOT_ALLOWED (D5).
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}")
@Tag(name = "admin-trust")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminTrustController {

    /** {@code technical.idempotency_record.endpoint} of each action (arch 08 section 6). */
    static final String ADJUST_ENDPOINT = "POST /api/v1/admin/users/{userId}/trust-adjustment";
    static final String LIFT_ENDPOINT = "POST /api/v1/admin/users/{userId}/bans/{banId}/lift";
    static final String SHORTEN_ENDPOINT = "POST /api/v1/admin/users/{userId}/bans/{banId}/shorten";
    static final String CORRECT_ENDPOINT = "POST /api/v1/admin/users/{userId}/bans/{banId}/correct";

    private static final StoredResponse<AdminTrustStanding> STANDING = StoredResponse.of(AdminTrustStanding.class,
            200, (AdminTrustStanding standing) -> new ResultAggregate("USER", standing.userId()));
    private static final StoredResponse<AdminBan> BAN = StoredResponse.of(AdminBan.class, 200,
            (AdminBan ban) -> new ResultAggregate("BAN_INSTANCE", ban.banId()));

    private final AdminTrustCommands trust;
    private final Idempotency idempotency;
    private final ResponseMetaFactory meta;

    AdminTrustController(AdminTrustCommands trust, Idempotency idempotency, ResponseMetaFactory meta) {
        this.trust = trust;
        this.idempotency = idempotency;
        this.meta = meta;
    }

    @PostMapping("/trust-adjustment")
    @Operation(operationId = "adminAdjustTrust",
            summary = "Set the customer's no-show counter and penalty stage (ADMIN_TRUST_ADJUSTMENT, FR-TRS-27)",
            description = "An absolute override, effective now (FR-TRS-05): counterAfter must stay below the threshold "
                    + "of penaltyStageAfter (FR-TRS-13), otherwise VALIDATION_FAILED on counterAfter. An anonymised "
                    + "account is ACCOUNT_DELETED. It changes no Ban: lift or shorten one separately. Returns the "
                    + "standing after replay.")
    @ApiError(status = 403, codes = ApiErrorCode.ACCOUNT_DELETED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminTrustStanding> adjustTrust(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminTrustAdjustmentRequest request,
            @AuthenticationPrincipal AuthenticatedSubject admin) {
        return meta.data(trust.adjust(admin.id(), userId, request.counterAfter(), request.penaltyStageAfter(),
                request.reason(), idempotency.request(admin, ADJUST_ENDPOINT, idempotencyKey,
                        Map.of("userId", userId), request),
                STANDING, AdminTrustStanding::of).body());
    }

    @PostMapping("/bans/{banId}/lift")
    @Operation(operationId = "adminLiftBan", summary = "Lift an ACTIVE ban now: LIFTED (FR-TRS-23, FR-TRS-27)",
            description = "Restrictions stop at once; previously cancelled bookings do not return (FR-TRS-25). A ban "
                    + "that is no longer ACTIVE is ADMIN_ACTION_NOT_ALLOWED; a ban of another user is NOT_FOUND.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminBan> liftBan(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID userId,
            @PathVariable UUID banId,
            @Valid @RequestBody AdminTrustReasonRequest request,
            @AuthenticationPrincipal AuthenticatedSubject admin) {
        return meta.data(trust.lift(admin.id(), userId, banId, request.reason(),
                idempotency.request(admin, LIFT_ENDPOINT, idempotencyKey, Map.of("userId", userId, "banId", banId),
                        request),
                BAN, AdminBan::of).body());
    }

    @PostMapping("/bans/{banId}/shorten")
    @Operation(operationId = "adminShortenBan", summary = "Bring an ACTIVE ban's end forward (FR-TRS-27)",
            description = "endsAt must be after now and before the current end, otherwise VALIDATION_FAILED on endsAt; "
                    + "the ban stays ACTIVE until then (endsAt = activatedAt + duration). A ban that is no longer "
                    + "ACTIVE is ADMIN_ACTION_NOT_ALLOWED.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminBan> shortenBan(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID userId,
            @PathVariable UUID banId,
            @Valid @RequestBody AdminBanShortenRequest request,
            @AuthenticationPrincipal AuthenticatedSubject admin) {
        return meta.data(trust.shorten(admin.id(), userId, banId, request.endsAt(), request.reason(),
                idempotency.request(admin, SHORTEN_ENDPOINT, idempotencyKey, Map.of("userId", userId, "banId", banId),
                        request),
                BAN, AdminBan::of).body());
    }

    @PostMapping("/bans/{banId}/correct")
    @Operation(operationId = "adminCorrectBan", summary = "End a ban proved invalid: CORRECTED (FR-TRS-25)",
            description = "Kept by PO decision D2 (17 Oct 2026). CORRECTED needs corrected trust history that no longer "
                    + "supports the ban (trust.assert_ban_lifecycle_audit). That history is corrected through the "
                    + "attendance routes, and the command that corrects it also corrects the ban - or shortens it to "
                    + "the part still supported. This route reconciles the customer and answers the ban as CORRECTED "
                    + "only when that reconciliation corrected it; a ban the history still supports, or one no longer "
                    + "ACTIVE, is ADMIN_ACTION_NOT_ALLOWED (lift or shorten it instead). Previously cancelled bookings "
                    + "do not return.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminBan> correctBan(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID userId,
            @PathVariable UUID banId,
            @Valid @RequestBody AdminTrustReasonRequest request,
            @AuthenticationPrincipal AuthenticatedSubject admin) {
        return meta.data(trust.correct(admin.id(), userId, banId, request.reason(),
                idempotency.request(admin, CORRECT_ENDPOINT, idempotencyKey, Map.of("userId", userId, "banId", banId),
                        request),
                BAN, AdminBan::of).body());
    }

    /** The mandatory reason of an admin remediation (FR-AUD-06), kept in its audit row. */
    @Schema(name = "AdminTrustReasonRequest")
    public record AdminTrustReasonRequest(
            @NotBlank @Size(min = 1, max = 500)
            @Schema(example = "Venue confirmed the no-show was a mistake.") String reason) {
    }

    /** The ADMIN_TRUST_ADJUSTMENT v1 absolute values: stages 0 to 3 (the recurring stage). */
    @Schema(name = "AdminTrustAdjustmentRequest")
    public record AdminTrustAdjustmentRequest(
            @NotNull @Min(0) @Schema(example = "0") Integer counterAfter,
            @NotNull @Min(0) @Max(3) @Schema(example = "1") Integer penaltyStageAfter,
            @NotBlank @Size(min = 1, max = 500) @Schema(example = "Two no-shows were recorded for a closed venue.")
            String reason) {
    }

    @Schema(name = "AdminBanShortenRequest")
    public record AdminBanShortenRequest(
            @NotNull @Schema(example = "2026-11-05T18:00:00Z") Instant endsAt,
            @NotBlank @Size(min = 1, max = 500)
            @Schema(example = "First offence; agreed with the venue.") String reason) {
    }

    /** The customer's standing as the admin sees it (FR-TRS-15). */
    @Schema(name = "AdminTrustStanding")
    public record AdminTrustStanding(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID userId,
            @NotNull @Schema(example = "0") Integer consecutiveNoShows,
            @NotNull @Schema(example = "1") Integer penaltyStage,
            @NotNull @Schema(example = "2") Integer currentThreshold,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) AdminBan activeBan) {

        static AdminTrustStanding of(AdminTrustCommands.Standing standing) {
            return new AdminTrustStanding(standing.userId(), standing.consecutiveNoShows(), standing.penaltyStage(),
                    standing.currentThreshold(), standing.activeBan() == null ? null : AdminBan.of(standing.activeBan()));
        }
    }

    /** A Ban Instance with its historical qualification point (FR-TRS-17, FR-TRS-19). */
    @Schema(name = "AdminBan")
    public record AdminBan(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID banId,
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e70") UUID userId,
            @NotNull BanInstanceStatus status,
            @NotNull @Schema(example = "0") Integer penaltyStage,
            @NotNull @Schema(description = "When history says the threshold of the ban's current stage was crossed.",
                    example = "2026-10-30T18:00:00Z") Instant qualificationAt,
            @NotNull @Schema(description = "Never back-dated (FR-TRS-18).", example = "2026-11-02T18:00:00Z")
            Instant activatedAt,
            @NotNull @Schema(example = "2026-11-09T18:00:00Z") Instant endsAt) {

        static AdminBan of(AdminTrustCommands.Ban ban) {
            return new AdminBan(ban.banId(), ban.userId(), ban.status(), ban.penaltyStage(), ban.qualificationAt(),
                    ban.activatedAt(), ban.endsAt());
        }
    }
}
