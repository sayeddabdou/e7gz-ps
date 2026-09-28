package ps.e7gz.resource.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.OperationalRestriction;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * Venue Dashboard — resources (arch 09 §4.2, arch 03 §6, FR-VEN-13…FR-VEN-15, FR-VEN-41). The Owner Password travels
 * in the body of each protected command, never a header (arch 09 §4.3, ADR-011). Stub until E7-26.
 */
@RestController
@RequestMapping("/api/v1/venue/resources")
@Tag(name = "venue-resources")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueResourceController {

    @GetMapping
    @Operation(operationId = "venueListResources", summary = "The venue's resources with both status columns (FR-VEN-13)")
    public DataResponse<List<VenueResourceView>> list() {
        throw NotImplementedYet.stub();
    }

    @GetMapping("/{resourceId}")
    @Operation(operationId = "venueGetResource", summary = "One resource")
    public DataResponse<VenueResourceView> get(@PathVariable UUID resourceId) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{resourceId}/disable")
    @Operation(operationId = "venueDisableResource",
            summary = "Disable — Owner Password; blocked by any current or future blocking reservation (FR-VEN-03, FR-VEN-14)")
    @ApiError(status = 422, codes = ApiErrorCode.RESOURCE_HAS_BLOCKING_RESERVATIONS)
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenueResourceView> disable(
            @PathVariable UUID resourceId, @Valid @RequestBody VenueResourceOwnerPasswordRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{resourceId}/re-enable")
    @Operation(operationId = "venueReEnableResource", summary = "Re-enable — Owner Password (FR-VEN-03)")
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenueResourceView> reEnable(
            @PathVariable UUID resourceId, @Valid @RequestBody VenueResourceOwnerPasswordRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{resourceId}/archive")
    @Operation(operationId = "venueArchiveResource",
            summary = "Archive — Owner Password; blocked by any current or future blocking reservation (FR-VEN-03, FR-VEN-14)")
    @ApiError(status = 422, codes = ApiErrorCode.RESOURCE_HAS_BLOCKING_RESERVATIONS)
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenueResourceView> archive(
            @PathVariable UUID resourceId, @Valid @RequestBody VenueResourceOwnerPasswordRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{resourceId}/restore")
    @Operation(operationId = "venueRestoreResource", summary = "Restore — Owner Password (FR-VEN-03)")
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenueResourceView> restore(
            @PathVariable UUID resourceId, @Valid @RequestBody VenueResourceOwnerPasswordRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{resourceId}/console")
    @Operation(operationId = "venueChangeResourceConsole",
            summary = "Change console PS4 ↔ PS5 — Owner Password; clears the room's price overrides; blocked by blocking "
                    + "reservations (FR-VEN-14, FR-VEN-15)")
    @ApiError(status = 422, codes = ApiErrorCode.RESOURCE_HAS_BLOCKING_RESERVATIONS)
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenueResourceView> changeConsole(
            @PathVariable UUID resourceId, @Valid @RequestBody VenueResourceConsoleRequest request) {
        throw NotImplementedYet.stub();
    }

    /** {@code resource.resource}; console type is set exactly for PlayStation ({@code ck_console_iff_playstation}). */
    @Schema(name = "VenueResource")
    public record VenueResourceView(
            @NotNull @Schema(example = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f") UUID id,
            @NotNull Activity activity,
            @NotNull @Schema(example = "Room 1") String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) ConsoleType consoleType,
            @NotNull ResourceBaseState baseState,
            @NotNull OperationalRestriction operationalRestriction) {
    }

    @Schema(name = "VenueResourceOwnerPasswordRequest")
    public record VenueResourceOwnerPasswordRequest(
            @NotBlank @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String ownerPassword) {
    }

    @Schema(name = "VenueResourceConsoleRequest")
    public record VenueResourceConsoleRequest(
            @NotNull ConsoleType consoleType,
            @NotBlank @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String ownerPassword) {
    }
}
