package ps.e7gz.operations.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.EmergencyConversionTarget;
import ps.e7gz.common.codes.EmergencyScope;
import ps.e7gz.common.codes.EmergencyStatus;
import ps.e7gz.common.codes.OwnerReviewResolution;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.operations.internal.VenueEmergencies;
import ps.e7gz.operations.internal.VenueOwnerReviews;

/**
 * Platform Admin — read-only monitors of Owner Reviews and Emergencies (FRS F-22, FR-OPS-06, FR-OPS-11 "Platform Admin
 * visibility", FR-OPS-29). Not in arch 09 §5: PO decision 19 Nov 2026 (E7-77, D3) adds them so the Admin can watch both
 * (E7-86). There is no admin action here: a review is resolved only by the venue with its Owner Password (FR-OPS-33).
 * Built by E7-84 (owner reviews) and E7-82 (emergencies) on the Sprint 5 contract.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "admin-operations")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Validated
public class AdminOperationsController {

    private final VenueEmergencies emergencies;
    private final VenueOwnerReviews reviews;
    private final ResponseMetaFactory meta;

    public AdminOperationsController(VenueEmergencies emergencies, VenueOwnerReviews reviews,
                                     ResponseMetaFactory meta) {
        this.emergencies = emergencies;
        this.reviews = reviews;
        this.meta = meta;
    }

    @GetMapping("/owner-reviews")
    @Operation(operationId = "adminListOwnerReviews",
            summary = "Owner Reviews of every venue, newest first (PO decision 19 Nov 2026, E7-77)",
            description = "Open reviews by default; resolved=true lists the resolved ones instead. Cursor paging "
                    + "(arch 09 §1.2). Implemented by E7-84.")
    public DataResponse<PageView<AdminOwnerReview>> listOwnerReviews(
            @Parameter(description = "Only this venue's reviews.") @RequestParam(required = false) UUID venueId,
            @Parameter(description = "false (default): open reviews; true: resolved reviews.")
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        VenueOwnerReviews.AdminPage page = reviews.adminList(venueId, resolved, cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(AdminOwnerReview::of).toList(),
                page.nextCursor()));
    }

    @GetMapping("/emergencies")
    @Operation(operationId = "adminListEmergencies",
            summary = "Emergencies of every venue, newest first (PO decision 19 Nov 2026, E7-77)",
            description = "Without status: the unresolved ones (ACTIVE and AWAITING_CONFIRMATION). Cursor paging "
                    + "(arch 09 §1.2). Implemented by E7-82.")
    public DataResponse<PageView<AdminEmergency>> listEmergencies(
            @Parameter(description = "Only this venue's emergencies.") @RequestParam(required = false) UUID venueId,
            @Parameter(description = "Only this status.") @RequestParam(required = false) EmergencyStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        VenueEmergencies.AdminPage page = emergencies.adminList(venueId, status, cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(AdminEmergency::of).toList(), page.nextCursor()));
    }

    @Schema(name = "AdminOwnerReview")
    public record AdminOwnerReview(
            @NotNull @Schema(example = "7a8b9c0d-1e2f-4a3b-c4d5-e6f7a8b9c0d1") UUID id,
            @NotNull @Schema(example = "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d") UUID venueId,
            @NotNull @Schema(example = "Level Up Nasr City") String venueName,
            @NotNull @Schema(example = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f") UUID resourceId,
            @NotNull @Schema(example = "Room 2") String resourceName,
            @NotNull @Schema(example = "2026-12-09T21:00:00Z") Instant enteredAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Instant resolvedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) OwnerReviewResolution resolution) {

        static AdminOwnerReview of(VenueOwnerReviews.AdminView row) {
            VenueOwnerReviews.View r = row.review();
            return new AdminOwnerReview(r.id(), row.venueId(), row.venueName(), r.resourceId(), row.resourceName(),
                    r.enteredAt(), r.resolvedAt(), r.resolution());
        }
    }

    @Schema(name = "AdminEmergency")
    public record AdminEmergency(
            @NotNull @Schema(example = "6f7a8b9c-0d1e-4f2a-b3c4-d5e6f7a8b9c0") UUID id,
            @NotNull @Schema(example = "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d") UUID venueId,
            @NotNull @Schema(example = "Level Up Nasr City") String venueName,
            @NotNull EmergencyScope scope,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The resource of a RESOURCE Emergency; null for VENUE.") UUID resourceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "Room 2")
            String resourceName,
            @NotNull EmergencyStatus status,
            @NotNull @Schema(example = "2026-12-09T19:12:00Z") Instant declaredAt,
            @NotNull @Schema(example = "2026-12-09T21:00:00Z") Instant expectedEndAt,
            @NotNull @Schema(example = "Power cut") String reason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) EmergencyConversionTarget convertedTo,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Instant endedAt) {

        static AdminEmergency of(VenueEmergencies.AdminView row) {
            VenueEmergencies.View e = row.emergency();
            return new AdminEmergency(e.id(), row.venueId(), row.venueName(), e.scope(), e.resourceId(),
                    row.resourceName(), e.status(), e.declaredAt(), e.expectedEndAt(), e.reason(), e.convertedTo(),
                    e.endedAt());
        }
    }
}
