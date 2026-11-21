package ps.e7gz.operations.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.OwnerReviewResolution;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.operations.internal.VenueOwnerReviews;

/**
 * Venue Dashboard — Owner Review (arch 09 §4.2, arch 06 §2.7, FRS F-22, FR-OPS-26…FR-OPS-33, FR-OPS-43). Stubs of the
 * Sprint 5 contract (E7-77), built by E7-84. Entry is a system cascade with no Owner Password (FR-OPS-29); only the
 * resolution needs it (FR-OPS-33). A resolution cancels nothing (blocking commitments refuse it, FR-OPS-43), so it is
 * outside ADR-015's scope, like the resource state changes it ends in (Idempotency-Key optional).
 */
@RestController
@RequestMapping("/api/v1/venue/owner-reviews")
@Tag(name = "venue-owner-reviews")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueOwnerReviewController {

    static final String RESOLVE_ENDPOINT = "POST /api/v1/venue/owner-reviews/{resourceId}/resolve";

    private final VenueOwnerReviews reviews;
    private final ResponseMetaFactory meta;
    private final Idempotency idempotency;

    public VenueOwnerReviewController(VenueOwnerReviews reviews, ResponseMetaFactory meta, Idempotency idempotency) {
        this.reviews = reviews;
        this.meta = meta;
        this.idempotency = idempotency;
    }

    @GetMapping
    @Operation(operationId = "venueListOwnerReviews",
            summary = "Resources in OWNER_REVIEW_REQUIRED, oldest first (FR-OPS-26, FR-OPS-43)",
            description = "One open review per resource. Implemented by E7-84.")
    public DataResponse<List<VenueOwnerReview>> list(@AuthenticationPrincipal AuthenticatedSubject venue) {
        return meta.data(reviews.list(venue.id()).stream().map(VenueOwnerReview::of).toList());
    }

    @PostMapping("/{resourceId}/resolve")
    @Operation(operationId = "venueResolveOwnerReview",
            summary = "Resolve with the Owner Password: Return to Service, Extend Maintenance, Disable or Archive "
                    + "(FR-OPS-33)",
            description = "Return to Service needs no blocking maintenance, no emergency selling block and an "
                    + "otherwise operational resource; it sets operationalRestriction = NONE. EXTEND_MAINTENANCE takes "
                    + "maintenance. While future protected reservations remain, the answer is "
                    + "OWNER_REVIEW_RESOLUTION_BLOCKED with details.blockingReservations [{bookingId, period, kind}] "
                    + "(arch 09 §4.2, FR-OPS-43). A resource with no open review is OPERATIONAL_STATE_CHANGED. "
                    + "Implemented by E7-84.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.OPERATIONAL_STATE_CHANGED)
    @ApiError(status = 422, codes = {ApiErrorCode.OWNER_REVIEW_RESOLUTION_BLOCKED,
            ApiErrorCode.MAINTENANCE_OVERLAPS_RESERVATION})
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    @ApiError(status = 429, codes = ApiErrorCode.RATE_LIMITED)
    public DataResponse<VenueOwnerReview> resolve(
            @Parameter(hidden = true) @RequestHeader(value = OpenApiConfig.IDEMPOTENCY_KEY, required = false)
            UUID idempotencyKey,
            @AuthenticationPrincipal AuthenticatedSubject venue, @PathVariable UUID resourceId,
            @Valid @RequestBody VenueOwnerReviewResolveRequest request) {
        VenueOwnerReviewMaintenance maintenance = request.maintenance();
        VenueOwnerReviews.ResolutionView view = new VenueOwnerReviews.ResolutionView(request.action(),
                maintenance == null ? null : maintenance.endAt(), maintenance == null ? null : maintenance.reason());
        return meta.data(reviews.resolve(venue.id(), resourceId, view, request.ownerPassword(),
                idempotencyKey == null ? null : idempotency.request(venue, RESOLVE_ENDPOINT, idempotencyKey,
                        Map.of("resourceId", resourceId), request),
                StoredResponse.of(VenueOwnerReview.class, 200), VenueOwnerReview::of));
    }

    /** {@code operations.owner_review}. */
    @Schema(name = "VenueOwnerReview")
    public record VenueOwnerReview(
            @NotNull @Schema(example = "7a8b9c0d-1e2f-4a3b-c4d5-e6f7a8b9c0d1") UUID id,
            @NotNull @Schema(example = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f") UUID resourceId,
            @NotNull @Schema(example = "2026-12-09T21:00:00Z") Instant enteredAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Instant resolvedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) OwnerReviewResolution resolution) {

        static VenueOwnerReview of(VenueOwnerReviews.View view) {
            return new VenueOwnerReview(view.id(), view.resourceId(), view.enteredAt(), view.resolvedAt(),
                    view.resolution());
        }
    }

    @Schema(name = "VenueOwnerReviewMaintenance")
    public record VenueOwnerReviewMaintenance(
            @NotNull @Schema(example = "2026-12-11T12:00:00Z") Instant endAt,
            @NotBlank @Size(max = 500) @Schema(example = "Waiting for a replacement part") String reason) {
    }

    @Schema(name = "VenueOwnerReviewResolveRequest")
    public record VenueOwnerReviewResolveRequest(
            @NotNull OwnerReviewResolution action,
            @Valid @Schema(description = "Required for EXTEND_MAINTENANCE, absent otherwise (VALIDATION_FAILED).")
            VenueOwnerReviewMaintenance maintenance,
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "4-digit Owner PIN; absent or blank is 423 OWNER_PASSWORD_REQUIRED.")
            String ownerPassword) {

        @Override
        public String toString() {
            return "VenueOwnerReviewResolveRequest[action=" + action + ", <owner password redacted>]";
        }
    }
}
