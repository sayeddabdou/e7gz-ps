package ps.e7gz.trust.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.BanInstanceStatus;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * Platform Admin trust remediation (arch 09 §5 "Trust", FR-TRS-27, FRS §2.10 reserved actions). Stubs of the Sprint 4
 * contract (E7-65) until E7-71. Every action needs a reason and is audited with the admin and the instant (FR-AUD-06),
 * notifies the customer (FR-NTF-11) and is in ADR-015's scope (Idempotency-Key required). Ban routes sit under the user
 * and keep {@code /correct} (PO decision D2, 17 Oct 2026).
 */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}")
@Tag(name = "admin-trust")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminTrustController {

    @PostMapping("/trust-adjustment")
    @Operation(operationId = "adminAdjustTrust",
            summary = "Set the customer's no-show counter and penalty stage (ADMIN_TRUST_ADJUSTMENT, FR-TRS-27)",
            description = "An absolute override, effective now (FR-TRS-05): counterAfter must stay below the threshold "
                    + "of penaltyStageAfter (FR-TRS-13), otherwise VALIDATION_FAILED on counterAfter. An anonymised "
                    + "account is ACCOUNT_DELETED. Returns the standing after replay. Implemented by E7-71.")
    @ApiError(status = 403, codes = ApiErrorCode.ACCOUNT_DELETED)
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminTrustStanding> adjustTrust(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID userId,
            @Valid @RequestBody AdminTrustAdjustmentRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/bans/{banId}/lift")
    @Operation(operationId = "adminLiftBan", summary = "Lift an ACTIVE ban now: LIFTED (FR-TRS-23, FR-TRS-27)",
            description = "Restrictions stop at once; previously cancelled bookings do not return (FR-TRS-25). A ban "
                    + "that is no longer ACTIVE is ADMIN_ACTION_NOT_ALLOWED; a ban of another user is NOT_FOUND. "
                    + "Implemented by E7-71.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminBan> liftBan(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID userId,
            @PathVariable UUID banId,
            @Valid @RequestBody AdminTrustReasonRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/bans/{banId}/shorten")
    @Operation(operationId = "adminShortenBan", summary = "Bring an ACTIVE ban's end forward (FR-TRS-27)",
            description = "endsAt must be after now and before the current end, otherwise VALIDATION_FAILED on endsAt; "
                    + "the ban stays ACTIVE until then (endsAt = activatedAt + duration). A ban that is no longer "
                    + "ACTIVE is ADMIN_ACTION_NOT_ALLOWED. Implemented by E7-71.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminBan> shortenBan(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID userId,
            @PathVariable UUID banId,
            @Valid @RequestBody AdminBanShortenRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/bans/{banId}/correct")
    @Operation(operationId = "adminCorrectBan", summary = "End a ban proved invalid: CORRECTED (FR-TRS-25)",
            description = "Kept by PO decision D2 (17 Oct 2026). The database accepts CORRECTED only when corrected "
                    + "trust history no longer supports the ban (trust.assert_ban_lifecycle_audit); otherwise, or when "
                    + "the ban is no longer ACTIVE, it is ADMIN_ACTION_NOT_ALLOWED. Previously cancelled bookings do "
                    + "not return. E7-71 decides how this route meets that rule.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminBan> correctBan(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @PathVariable UUID userId,
            @PathVariable UUID banId,
            @Valid @RequestBody AdminTrustReasonRequest request) {
        throw NotImplementedYet.stub();
    }

    /** The mandatory reason of an admin remediation (FR-AUD-06), kept in its audit row. */
    @Schema(name = "AdminTrustReasonRequest")
    public record AdminTrustReasonRequest(
            @NotBlank @Size(min = 1, max = 500)
            @Schema(example = "Venue confirmed the no-show was a mistake.") String reason) {
    }

    @Schema(name = "AdminTrustAdjustmentRequest")
    public record AdminTrustAdjustmentRequest(
            @NotNull @Min(0) @Schema(example = "0") Integer counterAfter,
            @NotNull @Min(0) @Schema(example = "1") Integer penaltyStageAfter,
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
    }

    /** A Ban Instance with its historical qualification point (FR-TRS-17, FR-TRS-19). */
    @Schema(name = "AdminBan")
    public record AdminBan(
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f") UUID banId,
            @NotNull @Schema(example = "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e70") UUID userId,
            @NotNull BanInstanceStatus status,
            @NotNull @Schema(example = "0") Integer penaltyStage,
            @NotNull @Schema(description = "When history says the threshold was crossed.",
                    example = "2026-10-30T18:00:00Z") Instant qualificationAt,
            @NotNull @Schema(description = "Never back-dated (FR-TRS-18).", example = "2026-11-02T18:00:00Z")
            Instant activatedAt,
            @NotNull @Schema(example = "2026-11-09T18:00:00Z") Instant endsAt) {
    }
}
