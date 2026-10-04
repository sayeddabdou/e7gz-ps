package ps.e7gz.resource.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.OperationalRestriction;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.resource.internal.Resources;
import ps.e7gz.resource.internal.Resources.StateChange;

/**
 * Venue Dashboard — resources (arch 09 §4.2, arch 03 §6, FR-VEN-13…FR-VEN-15, FR-VEN-41). The Owner Password travels
 * in the body of each protected command, never a header (arch 09 §4.3, ADR-011). Implemented by E7-26. A refused
 * Disable, Archive or Console Change names the blocking reservations in {@code error.details.reservations}.
 */
@RestController
@RequestMapping("/api/v1/venue/resources")
@Tag(name = "venue-resources")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueResourceController {

    private final Resources resources;
    private final ResponseMetaFactory meta;

    public VenueResourceController(Resources resources, ResponseMetaFactory meta) {
        this.resources = resources;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "venueListResources", summary = "The venue's resources with both status columns (FR-VEN-13)")
    public DataResponse<List<VenueResourceView>> list(@AuthenticationPrincipal AuthenticatedSubject venue) {
        return meta.data(resources.listForVenue(venue.id()).stream().map(VenueResourceView::of).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "venueCreateResource",
            summary = "Add a room or table; the name is assigned (Room n / Pool Table n / Table n, FR-VEN-41)",
            description = "The venue adds resources from the Dashboard; the admin can also add them during onboarding "
                    + "(FR-ADM-02; PO decision 2 Oct 2026, E7-22). The server names it with the next number for its "
                    + "activity (FR-VEN-41). consoleType is required for PLAYSTATION and absent otherwise "
                    + "(ck_console_iff_playstation). Starts ACTIVE with no operational restriction. Owner "
                    + "Password required (FRS §2.4 'protected resource configuration'; PO decision 2 Oct 2026).")
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenueResourceView> create(@AuthenticationPrincipal AuthenticatedSubject venue,
                                                  @Valid @RequestBody VenueResourceCreateRequest request) {
        return meta.data(VenueResourceView.of(resources.createForVenue(venue.id(), request.activity(),
                request.consoleType(), request.ownerPassword())));
    }

    @GetMapping("/{resourceId}")
    @Operation(operationId = "venueGetResource", summary = "One resource")
    public DataResponse<VenueResourceView> get(@AuthenticationPrincipal AuthenticatedSubject venue,
                                               @PathVariable UUID resourceId) {
        return meta.data(VenueResourceView.of(resources.getForVenue(venue.id(), resourceId)));
    }

    @PostMapping("/{resourceId}/disable")
    @Operation(operationId = "venueDisableResource",
            summary = "Disable — Owner Password; blocked by any current or future blocking reservation (FR-VEN-03, FR-VEN-14)")
    @ApiError(status = 422, codes = ApiErrorCode.RESOURCE_HAS_BLOCKING_RESERVATIONS)
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenueResourceView> disable(
            @AuthenticationPrincipal AuthenticatedSubject venue, @PathVariable UUID resourceId,
            @Valid @RequestBody VenueResourceOwnerPasswordRequest request) {
        return change(venue, resourceId, StateChange.DISABLE, request);
    }

    @PostMapping("/{resourceId}/re-enable")
    @Operation(operationId = "venueReEnableResource", summary = "Re-enable — Owner Password (FR-VEN-03)")
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenueResourceView> reEnable(
            @AuthenticationPrincipal AuthenticatedSubject venue, @PathVariable UUID resourceId,
            @Valid @RequestBody VenueResourceOwnerPasswordRequest request) {
        return change(venue, resourceId, StateChange.RE_ENABLE, request);
    }

    @PostMapping("/{resourceId}/archive")
    @Operation(operationId = "venueArchiveResource",
            summary = "Archive — Owner Password; blocked by any current or future blocking reservation (FR-VEN-03, FR-VEN-14)")
    @ApiError(status = 422, codes = ApiErrorCode.RESOURCE_HAS_BLOCKING_RESERVATIONS)
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenueResourceView> archive(
            @AuthenticationPrincipal AuthenticatedSubject venue, @PathVariable UUID resourceId,
            @Valid @RequestBody VenueResourceOwnerPasswordRequest request) {
        return change(venue, resourceId, StateChange.ARCHIVE, request);
    }

    @PostMapping("/{resourceId}/restore")
    @Operation(operationId = "venueRestoreResource", summary = "Restore — Owner Password (FR-VEN-03)")
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenueResourceView> restore(
            @AuthenticationPrincipal AuthenticatedSubject venue, @PathVariable UUID resourceId,
            @Valid @RequestBody VenueResourceOwnerPasswordRequest request) {
        return change(venue, resourceId, StateChange.RESTORE, request);
    }

    @PostMapping("/{resourceId}/console")
    @Operation(operationId = "venueChangeResourceConsole",
            summary = "Change console PS4 ↔ PS5 — Owner Password; clears the room's price overrides; blocked by blocking "
                    + "reservations (FR-VEN-14, FR-VEN-15)")
    @ApiError(status = 422, codes = ApiErrorCode.RESOURCE_HAS_BLOCKING_RESERVATIONS)
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenueResourceView> changeConsole(
            @AuthenticationPrincipal AuthenticatedSubject venue, @PathVariable UUID resourceId,
            @Valid @RequestBody VenueResourceConsoleRequest request) {
        return meta.data(VenueResourceView.of(resources.changeConsole(venue.id(), resourceId, request.consoleType(),
                request.ownerPassword())));
    }

    private DataResponse<VenueResourceView> change(AuthenticatedSubject venue, UUID resourceId, StateChange change,
                                                   VenueResourceOwnerPasswordRequest request) {
        return meta.data(VenueResourceView.of(resources.changeState(venue.id(), resourceId, change,
                request.ownerPassword())));
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

        static VenueResourceView of(Resources.Resource resource) {
            return new VenueResourceView(resource.id(), resource.activity(), resource.name(), resource.consoleType(),
                    resource.baseState(), resource.operationalRestriction());
        }
    }

    /** Console type exactly for PlayStation (ck_console_iff_playstation); the name is assigned (FR-VEN-41). */
    @Schema(name = "VenueResourceCreateRequest")
    public record VenueResourceCreateRequest(
            @NotNull Activity activity,
            @Schema(description = "PS5 or PS4 for PLAYSTATION; absent for BILLIARDS and PING_PONG.") ConsoleType consoleType,
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "4-digit Owner PIN; absent or blank is 423 OWNER_PASSWORD_REQUIRED.")
            String ownerPassword) {
    }

    @Schema(name = "VenueResourceOwnerPasswordRequest")
    public record VenueResourceOwnerPasswordRequest(
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "4-digit Owner PIN; absent or blank is 423 OWNER_PASSWORD_REQUIRED.")
            String ownerPassword) {
    }

    @Schema(name = "VenueResourceConsoleRequest")
    public record VenueResourceConsoleRequest(
            @NotNull ConsoleType consoleType,
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "4-digit Owner PIN; absent or blank is 423 OWNER_PASSWORD_REQUIRED.")
            String ownerPassword) {
    }
}
