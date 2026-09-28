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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.GeoPoint;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/** Platform Admin — Areas (arch 09 §5, arch 03 §4, FR-VEN-12, FR-VEN-47). Stub until E7-23. */
@RestController
@RequestMapping("/api/v1/admin/areas")
@Tag(name = "admin-areas")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminAreaController {

    @GetMapping
    @Operation(operationId = "adminListAreas", summary = "All areas, active and inactive")
    public DataResponse<List<AdminAreaView>> list() {
        throw NotImplementedYet.stub();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "adminCreateArea", summary = "Create an area (FR-VEN-12)")
    public DataResponse<AdminAreaView> create(@Valid @RequestBody AdminAreaCreateRequest request) {
        throw NotImplementedYet.stub();
    }

    @PatchMapping("/{areaId}")
    @Operation(operationId = "adminUpdateArea", summary = "Rename an area or move its centroid")
    public DataResponse<AdminAreaView> update(@PathVariable UUID areaId, @Valid @RequestBody AdminAreaUpdateRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{areaId}/activate")
    @Operation(operationId = "adminActivateArea", summary = "Activate an area")
    public DataResponse<AdminAreaView> activate(@PathVariable UUID areaId) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{areaId}/deactivate")
    @Operation(operationId = "adminDeactivateArea",
            summary = "Deactivate an area — refused while any assigned venue is ACTIVE or SUSPENDED (FR-VEN-47)")
    @ApiError(status = 422, codes = ApiErrorCode.AREA_HAS_ACTIVE_VENUES)
    public DataResponse<AdminAreaView> deactivate(@PathVariable UUID areaId) {
        throw NotImplementedYet.stub();
    }

    /** {@code geo.area}. */
    @Schema(name = "AdminArea")
    public record AdminAreaView(
            @NotNull @Schema(example = "3f6c1d2e-8a4b-4c5d-9e6f-7a8b9c0d1e2f") UUID id,
            @NotNull @Schema(example = "Nasr City") String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "مدينة نصر") String nameAr,
            @NotNull @Schema(example = "true") Boolean active,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) GeoPoint centroid) {
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
