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
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * Venue Dashboard — resource Maintenance (arch 09 §4.2, FR-OPS-01…FR-OPS-05, FRS F-18, A1). Stubs of the Sprint 5
 * contract (E7-77) until E7-81. Maintenance never cancels a booking (FR-OPS-02): a window over a blocking reservation
 * or an existing Closure (FR-VEN-33) is refused, so these writes are outside ADR-015's scope (Idempotency-Key
 * optional). The Owner Password is needed only over the rolling threshold (FR-OPS-03, FR-OPS-04): more than 24 hours of
 * maintenance for the resource inside any rolling 48 hours, emergency-created maintenance included (FR-OPS-05). The list
 * is a PO decision of 19 Nov 2026 (E7-77, D1): the Dashboard needs each window's id to edit or delete it.
 */
@RestController
@RequestMapping("/api/v1/venue/maintenance")
@Tag(name = "venue-maintenance")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueMaintenanceController {

    @GetMapping
    @Operation(operationId = "venueListMaintenance",
            summary = "Maintenance windows not yet ended, earliest first (FR-OPS-01; PO decision 19 Nov 2026, E7-77)",
            description = "Every maintenance window of the signed-in venue whose end is after now, emergency-created "
                    + "ones included (createdFromEmergencyId). Implemented by E7-81.")
    public DataResponse<List<VenueMaintenanceWindow>> list(
            @Parameter(description = "Only this resource's windows.") @RequestParam(required = false) UUID resourceId) {
        throw NotImplementedYet.stub();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "venueCreateMaintenance",
            summary = "Create a maintenance window on one resource (FR-OPS-01…FR-OPS-03)",
            description = "Refused with MAINTENANCE_OVERLAPS_RESERVATION when it overlaps a blocking reservation or an "
                    + "existing Closure (FR-OPS-02, FR-VEN-33); nothing is ever cancelled. Windows of the same "
                    + "resource may overlap or touch (A1). Over the rolling threshold the Owner Password is needed: "
                    + "absent is OWNER_PASSWORD_REQUIRED, wrong is OWNER_PASSWORD_INVALID (audited, FR-VEN-05). An "
                    + "unknown or archived resource is NOT_FOUND. Implemented by E7-81.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 422, codes = ApiErrorCode.MAINTENANCE_OVERLAPS_RESERVATION)
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    @ApiError(status = 429, codes = ApiErrorCode.RATE_LIMITED)
    public DataResponse<VenueMaintenanceWindow> create(@Valid @RequestBody VenueMaintenanceCreateRequest request) {
        throw NotImplementedYet.stub();
    }

    @PatchMapping("/{maintenanceId}")
    @Operation(operationId = "venueEditMaintenance", summary = "Move, extend, shorten or re-word a window (FR-OPS-04)",
            description = "Omitted fields keep their value. An edit that extends a continuous period past the "
                    + "threshold needs the Owner Password (FR-OPS-04); the overlap rules of create apply to the new "
                    + "interval. A window that has already ended is OPERATIONAL_STATE_CHANGED. Implemented by E7-81.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.OPERATIONAL_STATE_CHANGED)
    @ApiError(status = 422, codes = ApiErrorCode.MAINTENANCE_OVERLAPS_RESERVATION)
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    @ApiError(status = 429, codes = ApiErrorCode.RATE_LIMITED)
    public DataResponse<VenueMaintenanceWindow> edit(@PathVariable UUID maintenanceId,
                                                     @Valid @RequestBody VenueMaintenanceEditRequest request) {
        throw NotImplementedYet.stub();
    }

    @DeleteMapping("/{maintenanceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "venueDeleteMaintenance", summary = "Delete a window that has not ended (FR-OPS-01)",
            description = "No Owner Password (deleting never raises the rolling total). Audited; the window stops "
                    + "blocking availability. A window that has already ended is OPERATIONAL_STATE_CHANGED. "
                    + "Implemented by E7-81.")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 409, codes = ApiErrorCode.OPERATIONAL_STATE_CHANGED)
    public void delete(@PathVariable UUID maintenanceId) {
        throw NotImplementedYet.stub();
    }

    /** {@code operations.maintenance_window}; the period is {@code [startAt, endAt)}. */
    @Schema(name = "VenueMaintenanceWindow")
    public record VenueMaintenanceWindow(
            @NotNull @Schema(example = "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f") UUID id,
            @NotNull @Schema(example = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f") UUID resourceId,
            @NotNull @Schema(example = "2026-12-08T08:00:00Z") Instant startAt,
            @NotNull @Schema(example = "2026-12-08T14:00:00Z") Instant endAt,
            @NotNull @Schema(example = "Controller replacement") String reason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The resource emergency this window was created from (FR-OPS-05), or null.")
            UUID createdFromEmergencyId,
            @NotNull @Schema(description = "The Owner Password authorised it (over the threshold, FR-OPS-03).")
            Boolean ownerPasswordUsed) {
    }

    @Schema(name = "VenueMaintenanceCreateRequest")
    public record VenueMaintenanceCreateRequest(
            @NotNull UUID resourceId,
            @NotNull @Schema(example = "2026-12-08T08:00:00Z") Instant startAt,
            @NotNull @Schema(description = "After startAt.", example = "2026-12-08T14:00:00Z") Instant endAt,
            @NotBlank @Size(max = 500) @Schema(example = "Controller replacement") String reason,
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    description = "4-digit Owner PIN, needed only over the rolling threshold (FR-OPS-03). When sent, it "
                            + "is always checked.")
            String ownerPassword) {

        @Override
        public String toString() {
            return "VenueMaintenanceCreateRequest[resourceId=" + resourceId + ", startAt=" + startAt + ", endAt="
                    + endAt + ", <owner password redacted>]";
        }
    }

    @Schema(name = "VenueMaintenanceEditRequest")
    public record VenueMaintenanceEditRequest(
            @Schema(example = "2026-12-08T08:00:00Z") Instant startAt,
            @Schema(example = "2026-12-08T18:00:00Z") Instant endAt,
            @Size(min = 1, max = 500) String reason,
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    description = "4-digit Owner PIN, needed only when the edit takes the period past the threshold "
                            + "(FR-OPS-04). When sent, it is always checked.")
            String ownerPassword) {

        @Override
        public String toString() {
            return "VenueMaintenanceEditRequest[startAt=" + startAt + ", endAt=" + endAt
                    + ", <owner password redacted>]";
        }
    }
}
