package ps.e7gz.venue.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.venue.api.VenuePhotos;

/**
 * Platform Admin — venue photos at onboarding and any time later (arch 09 §5, FR-ADM-02, PCR-005). Same two-step upload
 * as the Venue Dashboard (arch 12 §2), without the Owner Password. Implemented by E7-23 PR 3.
 */
@RestController
@RequestMapping("/api/v1/admin/venues/{venueId}/images")
@Tag(name = "admin-venue-images")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminVenueImageController {

    private final VenuePhotos photos;
    private final ResponseMetaFactory meta;

    public AdminVenueImageController(VenuePhotos photos, ResponseMetaFactory meta) {
        this.photos = photos;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "adminListVenueImages",
            summary = "The venue's visible photos in order; the first is the venue-card photo (PCR-005)")
    public DataResponse<List<AdminVenueImageView>> list(@PathVariable UUID venueId) {
        return meta.data(photos.visible(venueId).stream().map(AdminVenueImageView::of).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "adminStartVenueImageUpload",
            summary = "Start an upload: returns a signed PUT link (10 minutes); nothing is visible yet (PCR-005)")
    @ApiError(status = 422, codes = {ApiErrorCode.VENUE_IMAGE_NOT_ACCEPTED, ApiErrorCode.VENUE_IMAGE_LIMIT_REACHED})
    public DataResponse<AdminVenueImageUploadView> start(@AuthenticationPrincipal AuthenticatedSubject admin,
            @PathVariable UUID venueId, @Valid @RequestBody AdminVenueImageUploadRequest request) {
        VenuePhotos.Upload upload = photos.startUpload(venueId, actor(admin), request.contentType(), request.sizeBytes());
        return meta.data(new AdminVenueImageUploadView(AdminVenueImageView.of(upload.photo()),
                upload.uploadUrl().toString(), upload.uploadExpiresAt()));
    }

    @PostMapping("/{imageId}/confirm")
    @Operation(operationId = "adminConfirmVenueImage",
            summary = "The uploaded photo becomes visible, last in the order (PCR-005)")
    @ApiError(status = 422, codes = {ApiErrorCode.VENUE_IMAGE_NOT_UPLOADED, ApiErrorCode.VENUE_IMAGE_NOT_ACCEPTED,
            ApiErrorCode.VENUE_IMAGE_LIMIT_REACHED})
    public DataResponse<AdminVenueImageView> confirm(@AuthenticationPrincipal AuthenticatedSubject admin,
            @PathVariable UUID venueId, @PathVariable UUID imageId) {
        return meta.data(AdminVenueImageView.of(photos.confirm(venueId, imageId, actor(admin))));
    }

    @PutMapping("/order")
    @Operation(operationId = "adminReorderVenueImages",
            summary = "Save the photo order: every visible photo exactly once (PCR-005)")
    public DataResponse<List<AdminVenueImageView>> reorder(@AuthenticationPrincipal AuthenticatedSubject admin,
            @PathVariable UUID venueId, @Valid @RequestBody AdminVenueImageOrderRequest request) {
        return meta.data(photos.reorder(venueId, request.imageIds(), actor(admin)).stream()
                .map(AdminVenueImageView::of).toList());
    }

    @PostMapping("/{imageId}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "adminRemoveVenueImage",
            summary = "Hide a photo; it is kept and the removal audited, never hard-deleted (PCR-005)")
    public void remove(@AuthenticationPrincipal AuthenticatedSubject admin,
            @PathVariable UUID venueId, @PathVariable UUID imageId) {
        photos.remove(venueId, imageId, actor(admin));
    }

    private static VenuePhotos.Actor actor(AuthenticatedSubject admin) {
        return new VenuePhotos.Actor.Admin(admin.id(), CorrelationId.require());
    }

    /**
     * A photo as the Platform Admin sees it. The URLs are signed and valid until {@code urlsExpireAt} (1 hour, PO
     * decision 3 Oct 2026); they point to the resized copies without GPS/EXIF and are {@code null} until those are made.
     */
    @Schema(name = "AdminVenueImage")
    public record AdminVenueImageView(
            @NotNull UUID id,
            @NotNull @Schema(example = "image/jpeg") String contentType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    format = "uri", description = "Venue-card copy; null until processed") String smallUrl,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    format = "uri", description = "Venue Details copy; null until processed") String mediumUrl,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Instant urlsExpireAt) {

        static AdminVenueImageView of(VenuePhotos.Photo photo) {
            return new AdminVenueImageView(photo.id(), photo.contentType(),
                    photo.smallUrl() == null ? null : photo.smallUrl().toString(),
                    photo.mediumUrl() == null ? null : photo.mediumUrl().toString(), photo.urlsExpireAt());
        }
    }

    @Schema(name = "AdminVenueImageUploadRequest")
    public record AdminVenueImageUploadRequest(
            @NotBlank @Schema(example = "image/jpeg",
                    description = "image/jpeg, image/png or image/webp (PCR-005)") String contentType,
            @NotNull @Positive @Schema(example = "1048576") Long sizeBytes) {
    }

    /** PUT the bytes to {@code uploadUrl} with the same Content-Type. */
    @Schema(name = "AdminVenueImageUpload")
    public record AdminVenueImageUploadView(
            @NotNull AdminVenueImageView image,
            @NotNull @Schema(format = "uri") String uploadUrl,
            @NotNull Instant uploadExpiresAt) {
    }

    @Schema(name = "AdminVenueImageOrderRequest")
    public record AdminVenueImageOrderRequest(@NotEmpty List<@NotNull UUID> imageIds) {
    }
}
