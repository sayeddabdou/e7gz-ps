package ps.e7gz.venue.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;

/**
 * Platform Admin — venue accounts (arch 09 §5, arch 03 §5, FR-ADM-03, FR-VEN-06, FR-VEN-08). Nothing here deletes venue
 * data; an Owner Password reset notifies the venue. {@code venueAccountId} is {@code iam.venue_account.id}. Stub until
 * E7-24.
 */
@RestController
@RequestMapping("/api/v1/admin/venue-accounts")
@Tag(name = "admin-venue-accounts")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminVenueAccountController {

    @PostMapping("/{venueAccountId}/disable")
    @Operation(operationId = "adminDisableVenueAccount",
            summary = "Disable Dashboard access; marketplace status is untouched (FR-VEN-06, FR-VEN-08)")
    public DataResponse<AdminVenueAccountView> disable(
            @PathVariable UUID venueAccountId, @Valid @RequestBody AdminVenueAccountReasonRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{venueAccountId}/enable")
    @Operation(operationId = "adminEnableVenueAccount", summary = "Re-enable Dashboard access (FR-ADM-03)")
    public DataResponse<AdminVenueAccountView> enable(
            @PathVariable UUID venueAccountId, @Valid @RequestBody AdminVenueAccountReasonRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{venueAccountId}/reset-login-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "adminResetVenueLoginPassword", summary = "Reset the venue login password (FR-ADM-03)",
            description = "The admin chooses the new password and gives it to the venue through support (PO decision, "
                    + "28 Sep 2026). No temporary password, reset link or forced change. Audited; venue data is kept.")
    public void resetLoginPassword(
            @PathVariable UUID venueAccountId, @Valid @RequestBody AdminVenuePasswordResetRequest request) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{venueAccountId}/reset-owner-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "adminResetVenueOwnerPassword",
            summary = "Reset the Owner Password; the venue is notified (FR-ADM-03, FR-VEN-05)",
            description = "The admin chooses the new Owner Password and gives it to the venue through support (PO "
                    + "decision, 28 Sep 2026). No temporary password, reset link or forced change. Audited; the venue "
                    + "is notified (FR-ADM-03).")
    public void resetOwnerPassword(
            @PathVariable UUID venueAccountId, @Valid @RequestBody AdminVenuePasswordResetRequest request) {
        throw NotImplementedYet.stub();
    }

    /** {@code iam.venue_account} without its secrets. */
    @Schema(name = "AdminVenueAccount")
    public record AdminVenueAccountView(
            @NotNull @Schema(example = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d") UUID id,
            @NotNull @Schema(example = "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d") UUID venueId,
            @NotNull @Schema(example = "levelup.nasrcity") String loginIdentifier,
            @NotNull VenueAccountStatus accountStatus) {
    }

    /** Account status changes carry the admin's reason (arch 03 §5 {@code setAccountStatus(…, reason)}). */
    @Schema(name = "AdminVenueAccountReasonRequest")
    public record AdminVenueAccountReasonRequest(@NotBlank @Schema(example = "Owner request") String reason) {
    }

    /** The replacement the admin chose; never returned, logged or echoed. */
    @Schema(name = "AdminVenuePasswordResetRequest")
    public record AdminVenuePasswordResetRequest(
            @NotBlank @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String newPassword) {
    }
}
