package ps.e7gz.operations.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.EmergencyConversionTarget;
import ps.e7gz.common.codes.EmergencyScope;
import ps.e7gz.common.codes.EmergencyStatus;
import ps.e7gz.common.codes.VenueClosureKind;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * Venue Dashboard — Emergencies (arch 09 §4.2, arch 06 §2.5, FRS F-19, F-20, FR-OPS-06…FR-OPS-24). Stubs of the
 * Sprint 5 contract (E7-77) until E7-82 (and E7-84 for the owner-review entry on end). No Owner Password to declare,
 * extend or end (FR-OPS-06, FR-OPS-11, FR-VEN-04); a conversion obeys its target's own authorization (FR-OPS-23).
 * Every write that can cancel commitments or move refunds requires the Idempotency-Key (ADR-015): declare, extend, end
 * (an over-threshold resource enters Owner Review and its cascade, FR-OPS-27, FR-OPS-28) and convert. Extend and
 * convert are preview + commit, like opening hours (PO decision 19 Nov 2026, E7-77, D2; FR-OPS-22). A venue Emergency
 * becomes an opening-hours change through {@code POST /venue/opening-hours/commit} with its {@code emergencyId}, not
 * here (PO decision 19 Nov 2026, E7-77, D5). The list is PO decision D1 of 19 Nov 2026 (E7-77).
 */
@RestController
@RequestMapping("/api/v1/venue/emergencies")
@Tag(name = "venue-emergencies")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueEmergencyController {

    @GetMapping
    @Operation(operationId = "venueListEmergencies",
            summary = "Unresolved Emergencies: ACTIVE and AWAITING_CONFIRMATION (FR-OPS-18; PO decision 19 Nov 2026)",
            description = "The venue's open Emergencies, newest first; the expected end passing never resolves one "
                    + "(FR-OPS-18, FR-OPS-25). Implemented by E7-82.")
    public DataResponse<List<VenueEmergency>> list() {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/venue-closure")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "venueDeclareEmergencyClosure",
            summary = "Declare a venue Emergency Closure starting now (FR-OPS-06…FR-OPS-10, FR-OPS-44)",
            description = "Starts at the command instant, never scheduled. expectedEndAt is after now and no later "
                    + "than the end of the current Operating Session, otherwise EMERGENCY_EXPECTED_END_OUT_OF_SESSION "
                    + "(FR-OPS-07). One transaction: the Emergency ACTIVE, the venue selling block, and the cascade "
                    + "over [now, expectedEndAt) under VENUE_EMERGENCY_CLOSURE (arch 06 §2.5). A venue Emergency "
                    + "already unresolved is VENUE_SELLING_BLOCK_ACTIVE. Implemented by E7-82.")
    @ApiError(status = 422, codes = {ApiErrorCode.EMERGENCY_EXPECTED_END_OUT_OF_SESSION,
            ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<VenueEmergency> declareVenueClosure(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody VenueEmergencyClosureRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/resource-outage")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "venueDeclareResourceOutage",
            summary = "Declare a resource Emergency Outage starting now (FR-OPS-11…FR-OPS-14)",
            description = "Blocks only that resource (FR-OPS-15). expectedReturnAt is after now and no later than "
                    + "the end of the current Operating Session, otherwise EMERGENCY_EXPECTED_END_OUT_OF_SESSION; a "
                    + "longer outage is Maintenance (FR-OPS-11). Cascade under RESOURCE_EMERGENCY_OUTAGE "
                    + "(FR-OPS-12). That resource already under an unresolved Emergency is "
                    + "RESOURCE_SELLING_BLOCK_ACTIVE. An unknown or archived resource is NOT_FOUND. Implemented by "
                    + "E7-82.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 422, codes = {ApiErrorCode.EMERGENCY_EXPECTED_END_OUT_OF_SESSION,
            ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE, ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<VenueEmergency> declareResourceOutage(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody VenueResourceOutageRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{emergencyId}/extend/preview")
    @Operation(operationId = "venuePreviewEmergencyExtension",
            summary = "List the reservations a later expected end would cancel (FR-OPS-22)",
            description = "Changes nothing. Previously cancelled reservations are not listed (they stay cancelled). "
                    + "Not beyond the current Operating Session: EMERGENCY_EXPECTED_END_OUT_OF_SESSION. A resolved "
                    + "Emergency is OPERATIONAL_STATE_CHANGED. Implemented by E7-82.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.OPERATIONAL_STATE_CHANGED)
    @ApiError(status = 422, codes = ApiErrorCode.EMERGENCY_EXPECTED_END_OUT_OF_SESSION)
    public DataResponse<VenueEmergencyImpactPreview> previewExtension(
            @PathVariable UUID emergencyId, @Valid @RequestBody VenueEmergencyExtendPreviewRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{emergencyId}/extend")
    @Operation(operationId = "venueExtendEmergency",
            summary = "Move the expected end later and cancel the newly affected reservations (FR-OPS-22)",
            description = "One transaction; the newly affected set is recomputed, and a set different from the "
                    + "preview's is AFFECTED_SET_CHANGED_REFRESH_PREVIEW with nothing changed. Implemented by E7-82.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = {ApiErrorCode.AFFECTED_SET_CHANGED_REFRESH_PREVIEW,
            ApiErrorCode.OPERATIONAL_STATE_CHANGED})
    @ApiError(status = 422, codes = {ApiErrorCode.EMERGENCY_EXPECTED_END_OUT_OF_SESSION,
            ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<VenueEmergency> extend(@RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
                                               @PathVariable UUID emergencyId,
                                               @Valid @RequestBody VenueEmergencyExtendRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{emergencyId}/end")
    @Operation(operationId = "venueEndEmergency",
            summary = "END EMERGENCY: staff assert the venue or resource is operational again (FR-OPS-19…FR-OPS-21)",
            description = "The audited staff assertion of FR-OPS-21; the selling block ends in the same commit. "
                    + "Nothing cancelled is restored (FR-OPS-10). When the resource cannot return to service "
                    + "EMERGENCY_CANNOT_END_RESOURCE_NOT_OPERATIONAL (FR-OPS-20). The rolling maintenance threshold is "
                    + "evaluated first: an over-threshold resource enters OWNER_REVIEW_REQUIRED and its cascade instead "
                    + "of returning to sale (FR-OPS-26…FR-OPS-28, E7-84). A resolved Emergency is "
                    + "OPERATIONAL_STATE_CHANGED. Implemented by E7-82 and E7-84.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.OPERATIONAL_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.EMERGENCY_CANNOT_END_RESOURCE_NOT_OPERATIONAL,
            ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    public DataResponse<VenueEmergency> end(@RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
                                            @PathVariable UUID emergencyId) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{emergencyId}/convert/preview")
    @Operation(operationId = "venuePreviewEmergencyConversion",
            summary = "Validate a conversion and list what it would cancel (FR-OPS-23)",
            description = "Changes nothing. Targets: venue Emergency → CLOSURE; resource Emergency → MAINTENANCE, "
                    + "DISABLED or ARCHIVED. Any other target, OPENING_HOURS_CHANGE included (that conversion goes "
                    + "through the opening-hours commit), is CONVERSION_TARGET_NOT_ALLOWED. ownerPasswordRequired "
                    + "follows the target's own rule. Implemented by E7-82.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.OPERATIONAL_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.CONVERSION_TARGET_NOT_ALLOWED,
            ApiErrorCode.CLOSURE_OVERLAPS_ACTIVE_SESSION, ApiErrorCode.TOURNAMENT_CONFLICT_BLOCKS_SAVE,
            ApiErrorCode.MAINTENANCE_OVERLAPS_RESERVATION, ApiErrorCode.RESOURCE_HAS_BLOCKING_RESERVATIONS})
    public DataResponse<VenueEmergencyImpactPreview> previewConversion(
            @PathVariable UUID emergencyId, @Valid @RequestBody VenueEmergencyConversion request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{emergencyId}/convert")
    @Operation(operationId = "venueConvertEmergency",
            summary = "Replace the Emergency with its target in one commit: CONVERTED (FR-OPS-23, FR-OPS-24)",
            description = "The block ends at the same commit that activates the replacement, so there is no "
                    + "sellable gap (FR-OPS-21, FR-OPS-24). The target's own rules apply: a Closure cancels its "
                    + "affected set (recomputed; different from the preview's is "
                    + "AFFECTED_SET_CHANGED_REFRESH_PREVIEW), Maintenance never overlaps a reservation, Disable and "
                    + "Archive need the future reservations resolved and the Owner Password. Implemented by E7-82.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = {ApiErrorCode.AFFECTED_SET_CHANGED_REFRESH_PREVIEW,
            ApiErrorCode.OPERATIONAL_STATE_CHANGED})
    @ApiError(status = 422, codes = {ApiErrorCode.CONVERSION_TARGET_NOT_ALLOWED,
            ApiErrorCode.CLOSURE_OVERLAPS_ACTIVE_SESSION, ApiErrorCode.TOURNAMENT_CONFLICT_BLOCKS_SAVE,
            ApiErrorCode.MAINTENANCE_OVERLAPS_RESERVATION, ApiErrorCode.RESOURCE_HAS_BLOCKING_RESERVATIONS,
            ApiErrorCode.IDEMPOTENCY_KEY_REUSED})
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    @ApiError(status = 429, codes = ApiErrorCode.RATE_LIMITED)
    public DataResponse<VenueEmergency> convert(@RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
                                                @PathVariable UUID emergencyId,
                                                @Valid @RequestBody VenueEmergencyConvertRequest request) {
        throw NotImplementedYet.stub();
    }

    /** {@code operations.emergency}. */
    @Schema(name = "VenueEmergency")
    public record VenueEmergency(
            @NotNull @Schema(example = "6f7a8b9c-0d1e-4f2a-b3c4-d5e6f7a8b9c0") UUID id,
            @NotNull EmergencyScope scope,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The resource of a RESOURCE Emergency; null for VENUE.") UUID resourceId,
            @NotNull EmergencyStatus status,
            @NotNull @Schema(example = "2026-12-09T19:12:00Z") Instant declaredAt,
            @NotNull @Schema(example = "2026-12-09T21:00:00Z") Instant expectedEndAt,
            @NotNull @Schema(example = "Power cut") String reason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "Set only when CONVERTED.") EmergencyConversionTarget convertedTo,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "When it became ENDED or CONVERTED.") Instant endedAt) {
    }

    @Schema(name = "VenueEmergencyClosureRequest")
    public record VenueEmergencyClosureRequest(
            @NotBlank @Size(max = 500) @Schema(example = "Power cut") String reason,
            @NotNull @Schema(example = "2026-12-09T21:00:00Z") Instant expectedEndAt) {
    }

    @Schema(name = "VenueResourceOutageRequest")
    public record VenueResourceOutageRequest(
            @NotNull UUID resourceId,
            @NotBlank @Size(max = 500) @Schema(example = "Console overheating") String reason,
            @NotNull @Schema(example = "2026-12-09T21:00:00Z") Instant expectedReturnAt) {
    }

    @Schema(name = "VenueEmergencyExtendPreviewRequest")
    public record VenueEmergencyExtendPreviewRequest(
            @NotNull @Schema(description = "Later than the current expected end.", example = "2026-12-09T23:00:00Z")
            Instant expectedEndAt) {
    }

    @Schema(name = "VenueEmergencyExtendRequest")
    public record VenueEmergencyExtendRequest(
            @NotNull @Schema(example = "2026-12-09T23:00:00Z") Instant expectedEndAt,
            @NotBlank String previewToken) {
    }

    /** What the extension or conversion would cancel, and whether saving it needs the Owner Password. */
    @Schema(name = "VenueEmergencyImpactPreview")
    public record VenueEmergencyImpactPreview(
            @NotNull @Schema(description = "Bound to the exact affected set.", example = "b64:7c2e…")
            String previewToken,
            @NotNull List<VenueEmergencyAffectedBooking> affected,
            @NotNull Boolean ownerPasswordRequired) {
    }

    /** A reservation the change would cancel. */
    @Schema(name = "VenueEmergencyAffectedBooking")
    public record VenueEmergencyAffectedBooking(
            @NotNull @Schema(example = "5e6f7a8b-9c0d-4e1f-a2b3-c4d5e6f7a8b9") UUID bookingId,
            @NotNull @Schema(example = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f") UUID resourceId,
            @NotNull @Schema(example = "2026-12-09T21:00:00Z") Instant startAt,
            @NotNull @Schema(example = "2026-12-09T23:00:00Z") Instant endAt) {
    }

    /**
     * The replacement (FR-OPS-23): {@code closure} for CLOSURE, {@code maintenance} for MAINTENANCE, neither for
     * DISABLED or ARCHIVED; a missing or extra part is VALIDATION_FAILED on it.
     */
    @Schema(name = "VenueEmergencyConversion")
    public record VenueEmergencyConversion(
            @NotNull EmergencyConversionTarget target,
            @Valid VenueEmergencyClosureTarget closure,
            @Valid VenueEmergencyMaintenanceTarget maintenance) {
    }

    /** A normal Closure from the conversion instant to {@code endAt}. */
    @Schema(name = "VenueEmergencyClosureTarget")
    public record VenueEmergencyClosureTarget(
            @NotNull VenueClosureKind kind,
            @NotNull @Schema(example = "2026-12-10T12:00:00Z") Instant endAt,
            @NotBlank @Size(max = 500) @Schema(example = "Water damage repair") String reason) {
    }

    /** Maintenance of the resource from the conversion instant to {@code endAt} (FR-OPS-05: it counts). */
    @Schema(name = "VenueEmergencyMaintenanceTarget")
    public record VenueEmergencyMaintenanceTarget(
            @NotNull @Schema(example = "2026-12-11T12:00:00Z") Instant endAt,
            @NotBlank @Size(max = 500) @Schema(example = "Waiting for a replacement part") String reason) {
    }

    @Schema(name = "VenueEmergencyConvertRequest")
    public record VenueEmergencyConvertRequest(
            @NotNull @Valid VenueEmergencyConversion conversion,
            @NotBlank String previewToken,
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    description = "4-digit Owner PIN when the preview said ownerPasswordRequired. When sent, it is "
                            + "always checked.")
            String ownerPassword) {

        @Override
        public String toString() {
            return "VenueEmergencyConvertRequest[conversion=" + conversion + ", <owner password redacted>]";
        }
    }
}
