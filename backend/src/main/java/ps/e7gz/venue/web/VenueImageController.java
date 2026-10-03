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
import ps.e7gz.venue.api.OwnerPasswordGate;
import ps.e7gz.venue.api.VenuePhotos;
import ps.e7gz.venue.internal.VenueAccounts;

/**
 * Venue Dashboard — the venue's own photos (arch 09 §4.2, PCR-005). Two-step upload (arch 12 §2): {@code POST} returns
 * a signed PUT, the browser uploads the bytes, {@code confirm} makes the photo visible. Add (confirm), reorder and
 * remove need the Owner Password (FRS §2.4 as amended by PCR-005). Contract from E7-23 PR 3; implemented by E7-24.
 * A venue only ever sees and changes its own photos: another venue's photo is NOT_FOUND.
 */
@RestController
@RequestMapping("/api/v1/venue/images")
@Tag(name = "venue-images")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueImageController {

    private final VenuePhotos photos;
    private final VenueAccounts accounts;
    private final OwnerPasswordGate ownerPassword;
    private final ResponseMetaFactory meta;

    public VenueImageController(VenuePhotos photos, VenueAccounts accounts, OwnerPasswordGate ownerPassword,
                                ResponseMetaFactory meta) {
        this.photos = photos;
        this.accounts = accounts;
        this.ownerPassword = ownerPassword;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "venueListImages",
            summary = "Visible photos in the venue's order; the first is the venue-card photo (FR-DIS-03, PCR-005)")
    public DataResponse<List<VenueImageView>> list(@AuthenticationPrincipal AuthenticatedSubject venue) {
        return meta.data(photos.visible(accounts.venueOf(venue.id())).stream().map(VenueImageView::of).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "venueStartImageUpload",
            summary = "Start an upload: returns a signed PUT link (10 minutes); nothing is visible yet (PCR-005)")
    @ApiError(status = 422, codes = {ApiErrorCode.VENUE_IMAGE_NOT_ACCEPTED, ApiErrorCode.VENUE_IMAGE_LIMIT_REACHED})
    public DataResponse<VenueImageUploadView> start(@AuthenticationPrincipal AuthenticatedSubject venue,
            @Valid @RequestBody VenueImageUploadRequest request) {
        VenuePhotos.Upload upload = photos.startUpload(accounts.venueOf(venue.id()), actor(venue, null),
                request.contentType(), request.sizeBytes());
        return meta.data(new VenueImageUploadView(VenueImageView.of(upload.photo()), upload.uploadUrl().toString(),
                upload.uploadExpiresAt()));
    }

    @PostMapping("/{imageId}/confirm")
    @Operation(operationId = "venueConfirmImage",
            summary = "The uploaded photo becomes visible, last in the order — Owner Password (PCR-005)")
    @ApiError(status = 422, codes = {ApiErrorCode.VENUE_IMAGE_NOT_UPLOADED, ApiErrorCode.VENUE_IMAGE_NOT_ACCEPTED,
            ApiErrorCode.VENUE_IMAGE_LIMIT_REACHED})
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenueImageView> confirm(@AuthenticationPrincipal AuthenticatedSubject venue,
            @PathVariable UUID imageId, @Valid @RequestBody VenueImageOwnerPasswordRequest request) {
        return meta.data(VenueImageView.of(photos.confirm(accounts.venueOf(venue.id()), imageId,
                actor(venue, request.ownerPassword()))));
    }

    @PutMapping("/order")
    @Operation(operationId = "venueReorderImages",
            summary = "Save the photo order: every visible photo exactly once — Owner Password (PCR-005)")
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<List<VenueImageView>> reorder(@AuthenticationPrincipal AuthenticatedSubject venue,
            @Valid @RequestBody VenueImageOrderRequest request) {
        return meta.data(photos.reorder(accounts.venueOf(venue.id()), request.imageIds(),
                actor(venue, request.ownerPassword())).stream().map(VenueImageView::of).toList());
    }

    @PostMapping("/{imageId}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "venueRemoveImage",
            summary = "Hide a photo; it is kept and the removal audited, never hard-deleted — Owner Password (PCR-005)")
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public void remove(@AuthenticationPrincipal AuthenticatedSubject venue, @PathVariable UUID imageId,
            @Valid @RequestBody VenueImageOwnerPasswordRequest request) {
        photos.remove(accounts.venueOf(venue.id()), imageId, actor(venue, request.ownerPassword()));
    }

    /**
     * The signed-in venue account. Its Owner Password step checks the password from this request's body inside the
     * change's transaction (ADR-011); starting an upload passes none and never calls the step.
     */
    private VenuePhotos.Actor actor(AuthenticatedSubject venue, String suppliedOwnerPassword) {
        UUID account = venue.id();
        return new VenuePhotos.Actor.Venue(account, CorrelationId.require(),
                context -> ownerPassword.verify(context, account, suppliedOwnerPassword));
    }

    /**
     * A photo as the venue sees it. The URLs are signed and valid until {@code urlsExpireAt} (1 hour, PO decision
     * 3 Oct 2026); they point to the resized copies without GPS/EXIF and are {@code null} until those are made.
     */
    @Schema(name = "VenueImage")
    public record VenueImageView(
            @NotNull UUID id,
            @NotNull @Schema(example = "image/jpeg") String contentType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    format = "uri", description = "Venue-card copy; null until processed") String smallUrl,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    format = "uri", description = "Venue Details copy; null until processed") String mediumUrl,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Instant urlsExpireAt) {

        static VenueImageView of(VenuePhotos.Photo photo) {
            return new VenueImageView(photo.id(), photo.contentType(),
                    photo.smallUrl() == null ? null : photo.smallUrl().toString(),
                    photo.mediumUrl() == null ? null : photo.mediumUrl().toString(), photo.urlsExpireAt());
        }
    }

    /** Arch 09 §4.2: {@code {contentType, sizeBytes}}. The server decides the accepted types and the size limit. */
    @Schema(name = "VenueImageUploadRequest")
    public record VenueImageUploadRequest(
            @NotBlank @Schema(example = "image/jpeg",
                    description = "image/jpeg, image/png or image/webp (PCR-005)") String contentType,
            @NotNull @Positive @Schema(example = "1048576") Long sizeBytes) {
    }

    /** Arch 09 §4.2: {@code {image, uploadUrl, uploadExpiresAt}}. PUT the bytes with the same Content-Type. */
    @Schema(name = "VenueImageUpload")
    public record VenueImageUploadView(
            @NotNull VenueImageView image,
            @NotNull @Schema(format = "uri") String uploadUrl,
            @NotNull Instant uploadExpiresAt) {
    }

    @Schema(name = "VenueImageOrderRequest")
    public record VenueImageOrderRequest(
            @NotEmpty List<@NotNull UUID> imageIds,
            @NotBlank @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String ownerPassword) {

        @Override
        public String toString() {
            return "VenueImageOrderRequest[imageIds=" + imageIds + ", <owner password redacted>]";
        }
    }

    @Schema(name = "VenueImageOwnerPasswordRequest")
    public record VenueImageOwnerPasswordRequest(
            @NotBlank @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String ownerPassword) {

        @Override
        public String toString() {
            return "VenueImageOwnerPasswordRequest[<owner password redacted>]";
        }
    }
}
