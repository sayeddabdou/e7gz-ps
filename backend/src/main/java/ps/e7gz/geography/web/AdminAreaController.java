package ps.e7gz.geography.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.GeoPoint;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.geography.internal.Area;
import ps.e7gz.geography.internal.Areas;

/** Platform Admin — Areas (arch 09 §5, arch 03 §4, FR-VEN-12, FR-VEN-47). Implemented by E7-23. */
@RestController
@RequestMapping("/api/v1/admin/areas")
@Tag(name = "admin-areas")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminAreaController {

    private final Areas areas;
    private final ResponseMetaFactory meta;

    public AdminAreaController(Areas areas, ResponseMetaFactory meta) {
        this.areas = areas;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "adminListAreas", summary = "All areas, active and inactive")
    public DataResponse<List<AdminAreaView>> list() {
        return meta.data(areas.all().stream().map(AdminAreaView::of).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "adminCreateArea", summary = "Create an area (FR-VEN-12)")
    public DataResponse<AdminAreaView> create(@AuthenticationPrincipal AuthenticatedSubject admin,
                                              @Valid @RequestBody AdminAreaCreateRequest request) {
        GeoPoint c = request.centroid();
        return meta.data(AdminAreaView.of(areas.create(admin.id(), request.name(), request.nameAr(),
                c == null ? null : c.toCoordinates())));
    }

    @PatchMapping("/{areaId}")
    @Operation(operationId = "adminUpdateArea", summary = "Rename an area or move its centroid")
    public DataResponse<AdminAreaView> update(@AuthenticationPrincipal AuthenticatedSubject admin,
                                              @PathVariable UUID areaId, @Valid @RequestBody AdminAreaUpdateRequest request) {
        GeoPoint c = request.centroid();
        return meta.data(AdminAreaView.of(areas.update(admin.id(), areaId, request.name(), request.nameAr(),
                c == null ? null : c.toCoordinates())));
    }

    @PostMapping("/{areaId}/activate")
    @Operation(operationId = "adminActivateArea", summary = "Activate an area")
    public DataResponse<AdminAreaView> activate(@AuthenticationPrincipal AuthenticatedSubject admin,
                                                @PathVariable UUID areaId) {
        return meta.data(AdminAreaView.of(areas.setActive(admin.id(), areaId, true)));
    }

    @PostMapping("/{areaId}/deactivate")
    @Operation(operationId = "adminDeactivateArea",
            summary = "Deactivate an area — refused while any assigned venue is ACTIVE or SUSPENDED (FR-VEN-47)")
    @ApiError(status = 422, codes = ApiErrorCode.AREA_HAS_ACTIVE_VENUES)
    public DataResponse<AdminAreaView> deactivate(@AuthenticationPrincipal AuthenticatedSubject admin,
                                                  @PathVariable UUID areaId) {
        return meta.data(AdminAreaView.of(areas.setActive(admin.id(), areaId, false)));
    }

    /** {@code geo.area}. */
    @Schema(name = "AdminArea")
    public record AdminAreaView(
            @NotNull @Schema(example = "3f6c1d2e-8a4b-4c5d-9e6f-7a8b9c0d1e2f") UUID id,
            @NotNull @Schema(example = "Nasr City") String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "مدينة نصر") String nameAr,
            @NotNull @Schema(example = "true") Boolean active,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) GeoPoint centroid) {

        static AdminAreaView of(Area area) {
            return new AdminAreaView(area.id(), area.name(), area.nameAr(), area.active(),
                    GeoPoint.of(area.centroid()));
        }
    }

    @Schema(name = "AdminAreaCreateRequest")
    public record AdminAreaCreateRequest(
            @NotBlank @Schema(example = "Nasr City") String name,
            @Schema(example = "مدينة نصر") String nameAr,
            @Valid GeoPoint centroid) {
    }

    /** Only the fields sent change. */
    @Schema(name = "AdminAreaUpdateRequest")
    public record AdminAreaUpdateRequest(
            @Schema(example = "Nasr City") String name,
            @Schema(example = "مدينة نصر") String nameAr,
            @Valid GeoPoint centroid) {
    }
}
