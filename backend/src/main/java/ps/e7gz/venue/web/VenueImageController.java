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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * Venue Dashboard — the venue's own photos (arch 09 §4.2, PCR-005). Two-step upload (arch 12 §2): {@code POST} returns
 * a signed PUT, the browser uploads the bytes, {@code confirm} makes the photo visible. Add (confirm), reorder and
 * remove need the Owner Password (FRS §2.4 as amended by PCR-005). Contract stub (E7-23 PR 3); implemented by E7-24.
 */
@RestController
@RequestMapping("/api/v1/venue/images")
@Tag(name = "venue-images")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueImageController {

    @GetMapping
    @Operation(operationId = "venueListImages",
            summary = "Visible photos in the venue's order; the first is the venue-card photo (FR-DIS-03, PCR-005)")
    public DataResponse<List<VenueImageView>> list() {
        throw NotImplementedYet.stub();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "venueStartImageUpload",
            summary = "Start an upload: returns a signed PUT link (10 minutes); nothing is visible yet (PCR-005)")
    @ApiError(status = 422, codes = {ApiErrorCode.VENUE_IMAGE_NOT_ACCEPTED, ApiErrorCode.VENUE_IMAGE_LIMIT_REACHED})
    public DataResponse<VenueImageUploadView> start(@Valid @RequestBody VenueImageUploadRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{imageId}/confirm")
    @Operation(operationId = "venueConfirmImage",
            summary = "The uploaded photo becomes visible, last in the order — Owner Password (PCR-005)")
    @ApiError(status = 422, codes = {ApiErrorCode.VENUE_IMAGE_NOT_UPLOADED, ApiErrorCode.VENUE_IMAGE_LIMIT_REACHED})
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenueImageView> confirm(
            @PathVariable UUID imageId, @Valid @RequestBody VenueImageOwnerPasswordRequest request) {
        throw NotImplementedYet.stub();
    }

    @PutMapping("/order")
    @Operation(operationId = "venueReorderImages",
            summary = "Save the photo order: every visible photo exactly once — Owner Password (PCR-005)")
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<List<VenueImageView>> reorder(@Valid @RequestBody VenueImageOrderRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{imageId}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "venueRemoveImage",
            summary = "Hide a photo; it is kept and the removal audited, never hard-deleted — Owner Password (PCR-005)")
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public void remove(@PathVariable UUID imageId, @Valid @RequestBody VenueImageOwnerPasswordRequest request) {
        throw NotImplementedYet.stub();
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
    }

    @Schema(name = "VenueImageOwnerPasswordRequest")
    public record VenueImageOwnerPasswordRequest(
            @NotBlank @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String ownerPassword) {
    }
}
