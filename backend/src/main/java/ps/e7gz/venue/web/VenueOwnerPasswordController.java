package ps.e7gz.venue.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/** Venue Dashboard — change the Owner Password (arch 09 §4.2, FR-VEN-46). Stub until E7-24. */
@RestController
@RequestMapping("/api/v1/venue/owner-password")
@Tag(name = "venue-owner-password")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueOwnerPasswordController {

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "venueChangeOwnerPassword",
            summary = "Change the Owner Password; the old one stops working at once (FR-VEN-46)")
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public void change(@Valid @RequestBody VenueOwnerPasswordChangeRequest request) {
        throw NotImplementedYet.stub();
    }

    /** Arch 09 §4.2: {@code {current, next}}. */
    @Schema(name = "VenueOwnerPasswordChangeRequest")
    public record VenueOwnerPasswordChangeRequest(
            @NotBlank @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String current,
            @NotBlank @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String next) {
    }
}
