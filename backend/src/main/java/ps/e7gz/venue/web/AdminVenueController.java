package ps.e7gz.venue.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.GeoPoint;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;

/**
 * Platform Admin — venues and onboarding (arch 09 §5, arch 03 §5, FR-ADM-02, FR-VEN-06, FR-VEN-07). A new venue starts
 * in ONBOARDING. Stub until E7-23.
 */
@RestController
@RequestMapping("/api/v1/admin/venues")
@Tag(name = "admin-venues")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminVenueController {

    @GetMapping
    @Operation(operationId = "adminListVenues", summary = "All venues, any marketplace status")
    public DataResponse<PageView<AdminVenueView>> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        throw NotImplementedYet.stub();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "adminCreateVenue", summary = "Create a venue in ONBOARDING, in an active area (FR-ADM-02, FR-VEN-12)")
    @ApiError(status = 422, codes = ApiErrorCode.AREA_INACTIVE)
    public DataResponse<AdminVenueView> create(@Valid @RequestBody AdminVenueCreateRequest request) {
        throw NotImplementedYet.stub();
    }

    @PatchMapping("/{venueId}")
    @Operation(operationId = "adminUpdateVenue", summary = "Edit venue information; a new area must be active (FR-VEN-12)")
    @ApiError(status = 422, codes = ApiErrorCode.AREA_INACTIVE)
    public DataResponse<AdminVenueView> update(@PathVariable UUID venueId, @Valid @RequestBody AdminVenueUpdateRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{venueId}/onboarding/complete")
    @Operation(operationId = "adminCompleteVenueOnboarding",
            summary = "Complete onboarding: ONBOARDING → INACTIVE, never ACTIVE (FR-ADM-02, FR-VEN-07)",
            description = "Completing onboarding does not activate the venue (PO decision, 28 Sep 2026). The venue "
                    + "leaves ONBOARDING for INACTIVE — hidden from discovery, Dashboard and manual booking available "
                    + "(FR-VEN-07); the database never lets it return to ONBOARDING. Activation is a separate admin "
                    + "action, not part of this contract yet.")
    public DataResponse<AdminVenueView> completeOnboarding(
            @PathVariable UUID venueId, @Valid @RequestBody AdminVenueReasonRequest request) {
        throw NotImplementedYet.stub();
    }

    /** {@code venue.venue} with its activities ({@code venue.venue_activity}). */
    @Schema(name = "AdminVenue")
    public record AdminVenueView(
            @NotNull @Schema(example = "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d") UUID id,
            @NotNull @Schema(example = "3f6c1d2e-8a4b-4c5d-9e6f-7a8b9c0d1e2f") UUID areaId,
            @NotNull @Schema(example = "Level Up Lounge") String name,
            @NotNull @Schema(example = "12 Abbas El Akkad St, Nasr City") String address,
            @NotNull GeoPoint location,
            @NotNull @Schema(example = "+201001234567") String phone,
            @NotNull @Schema(example = "Africa/Cairo") String timezone,
            @NotNull MarketplaceStatus marketplaceStatus,
            @NotNull List<Activity> activities) {
    }

    @Schema(name = "AdminVenueCreateRequest")
    public record AdminVenueCreateRequest(
            @NotNull @Schema(example = "3f6c1d2e-8a4b-4c5d-9e6f-7a8b9c0d1e2f") UUID areaId,
            @NotBlank @Schema(example = "Level Up Lounge") String name,
            @NotBlank @Schema(example = "12 Abbas El Akkad St, Nasr City") String address,
            @NotNull @Valid GeoPoint location,
            @NotBlank @Schema(example = "+201001234567") String phone,
            @NotEmpty List<@NotNull Activity> activities) {
    }

    /** Only the fields sent change. */
    @Schema(name = "AdminVenueUpdateRequest")
    public record AdminVenueUpdateRequest(
            UUID areaId,
            @Schema(example = "Level Up Lounge") String name,
            @Schema(example = "12 Abbas El Akkad St, Nasr City") String address,
            @Valid GeoPoint location,
            @Schema(example = "+201001234567") String phone,
            List<@NotNull Activity> activities) {
    }

    /** Status changes carry the admin's reason (arch 03 §5 {@code setMarketplaceStatus(…, reason)}). */
    @Schema(name = "AdminVenueReasonRequest")
    public record AdminVenueReasonRequest(@NotBlank @Schema(example = "Setup verified on site") String reason) {
    }
}
