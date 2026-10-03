package ps.e7gz.venue.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.venue.internal.VenueAccounts;

/**
 * Venue Dashboard — change the Owner Password (arch 09 §4.2, FR-VEN-46). The Owner Password is a 4-digit PIN (PCR-006). The current Owner Password authorises the
 * change like any protected action (checked in the transaction, audited, rate-limited); the new one works at once and
 * the old one stops working. There is no venue self-service reset (Out-of-Scope §3).
 */
@RestController
@RequestMapping("/api/v1/venue/owner-password")
@Tag(name = "venue-owner-password")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueOwnerPasswordController {

    private final VenueAccounts accounts;

    public VenueOwnerPasswordController(VenueAccounts accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "venueChangeOwnerPassword",
            summary = "Change the Owner Password; the old one stops working at once (FR-VEN-46)")
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public void change(@AuthenticationPrincipal AuthenticatedSubject venue,
                       @Valid @RequestBody VenueOwnerPasswordChangeRequest request) {
        accounts.changeOwnerPassword(venue.id(), request.current(), request.next());
    }

    /** Arch 09 §4.2: {@code {current, next}}. Neither is ever returned, logged or echoed. */
    @Schema(name = "VenueOwnerPasswordChangeRequest")
    public record VenueOwnerPasswordChangeRequest(
            @NotBlank @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String current,
            @NotBlank @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY, pattern = "^[0-9]{4}$",
                    description = "The new Owner Password: a PIN of exactly 4 digits (PCR-006).") String next) {

        @Override
        public String toString() {
            return "VenueOwnerPasswordChangeRequest[<passwords redacted>]";
        }
    }
}
