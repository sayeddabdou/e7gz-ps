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
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.resource.internal.Resources;

/**
 * Platform Admin — a venue's resources during onboarding (FR-ADM-02: onboarding covers resources; PO decision 2 Oct
 * 2026, E7-22: the admin and the venue can both add them). Names are assigned (FR-VEN-41); state changes stay with
 * the venue and its Owner Password (arch 09 §4.2). The admin list/create carry-forward is implemented for E7-37;
 * venue-side state changes remain in E7-26.
 */
@RestController
@RequestMapping("/api/v1/admin/venues/{venueId}/resources")
@Tag(name = "admin-venue-resources")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminVenueResourceController {

    private final Resources resources;
    private final ResponseMetaFactory meta;

    public AdminVenueResourceController(Resources resources, ResponseMetaFactory meta) {
        this.resources = resources;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "adminListVenueResources", summary = "The venue's resources with both status columns "
            + "(FR-VEN-13)")
    public DataResponse<List<AdminResourceView>> list(@PathVariable UUID venueId) {
        return meta.data(resources.list(venueId).stream().map(AdminResourceView::of).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "adminCreateVenueResource",
            summary = "Add a room or table to the venue; the name is assigned (Room n / Pool Table n / Table n, "
                    + "FR-VEN-41)",
            description = "consoleType is required for PLAYSTATION and absent otherwise (ck_console_iff_playstation). "
                    + "Starts ACTIVE with no operational restriction.")
    public DataResponse<AdminResourceView> create(
            @AuthenticationPrincipal AuthenticatedSubject admin, @PathVariable UUID venueId,
            @Valid @RequestBody AdminResourceCreateRequest request) {
        return meta.data(AdminResourceView.of(resources.create(admin.id(), venueId, request.activity(),
                request.consoleType())));
    }

    /** {@code resource.resource}; console type is set exactly for PlayStation ({@code ck_console_iff_playstation}). */
    @Schema(name = "AdminResource")
    public record AdminResourceView(
            @NotNull @Schema(example = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f") UUID id,
            @NotNull @Schema(example = "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d") UUID venueId,
            @NotNull Activity activity,
            @NotNull @Schema(example = "Room 1") String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) ConsoleType consoleType,
            @NotNull ResourceBaseState baseState,
            @NotNull OperationalRestriction operationalRestriction) {

        static AdminResourceView of(Resources.Resource resource) {
            return new AdminResourceView(resource.id(), resource.venueId(), resource.activity(), resource.name(),
                    resource.consoleType(), resource.baseState(), resource.operationalRestriction());
        }
    }

    /** Console type exactly for PlayStation (ck_console_iff_playstation); the name is assigned (FR-VEN-41). */
    @Schema(name = "AdminResourceCreateRequest")
    public record AdminResourceCreateRequest(
            @NotNull Activity activity,
            @Schema(description = "PS5 or PS4 for PLAYSTATION; absent for BILLIARDS and PING_PONG.") ConsoleType consoleType) {
    }
}
