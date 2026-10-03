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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.venue.internal.VenueAccounts;
import ps.e7gz.venue.internal.Venues;

/**
 * Platform Admin — venue accounts (arch 09 §5, arch 03 §5, FR-ADM-03, FR-VEN-06, FR-VEN-08). Nothing here deletes venue
 * data; an Owner Password reset notifies the venue (that notification comes with E7-43). {@code venueAccountId} is
 * {@code iam.venue_account.id}. Implemented by E7-24 (PO decision 3 Oct 2026); disable changes Dashboard access only -
 * its booking and tournament consequences (O10) come with E7-78.
 */
@RestController
@RequestMapping("/api/v1/admin/venue-accounts")
@Tag(name = "admin-venue-accounts")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminVenueAccountController {

    private final VenueAccounts accounts;
    private final ResponseMetaFactory meta;

    public AdminVenueAccountController(VenueAccounts accounts, ResponseMetaFactory meta) {
        this.accounts = accounts;
        this.meta = meta;
    }

    @PostMapping("/{venueAccountId}/disable")
    @Operation(operationId = "adminDisableVenueAccount",
            summary = "Disable Dashboard access; marketplace status is untouched (FR-VEN-06, FR-VEN-08)")
    public DataResponse<AdminVenueAccountView> disable(@AuthenticationPrincipal AuthenticatedSubject admin,
            @PathVariable UUID venueAccountId, @Valid @RequestBody AdminVenueAccountReasonRequest request) {
        return meta.data(AdminVenueAccountView.of(
                accounts.setStatus(admin.id(), venueAccountId, VenueAccountStatus.DISABLED, request.reason())));
    }

    @PostMapping("/{venueAccountId}/enable")
    @Operation(operationId = "adminEnableVenueAccount", summary = "Re-enable Dashboard access (FR-ADM-03)")
    public DataResponse<AdminVenueAccountView> enable(@AuthenticationPrincipal AuthenticatedSubject admin,
            @PathVariable UUID venueAccountId, @Valid @RequestBody AdminVenueAccountReasonRequest request) {
        return meta.data(AdminVenueAccountView.of(
                accounts.setStatus(admin.id(), venueAccountId, VenueAccountStatus.ENABLED, request.reason())));
    }

    @PostMapping("/{venueAccountId}/reset-login-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "adminResetVenueLoginPassword", summary = "Reset the venue login password (FR-ADM-03)",
            description = "The admin chooses the new password and gives it to the venue through support (PO decision, "
                    + "28 Sep 2026). No temporary password, reset link or forced change. Audited; venue data is kept.")
    public void resetLoginPassword(@AuthenticationPrincipal AuthenticatedSubject admin,
            @PathVariable UUID venueAccountId, @Valid @RequestBody AdminVenuePasswordResetRequest request) {
        accounts.resetLoginPassword(admin.id(), venueAccountId, request.newPassword());
    }

    @PostMapping("/{venueAccountId}/reset-owner-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "adminResetVenueOwnerPassword",
            summary = "Reset the Owner Password; the venue is notified (FR-ADM-03, FR-VEN-05)",
            description = "The admin chooses the new Owner Password and gives it to the venue through support (PO "
                    + "decision, 28 Sep 2026). No temporary password, reset link or forced change. Audited; the venue "
                    + "is notified (FR-ADM-03).")
    public void resetOwnerPassword(@AuthenticationPrincipal AuthenticatedSubject admin,
            @PathVariable UUID venueAccountId, @Valid @RequestBody AdminVenuePasswordResetRequest request) {
        accounts.resetOwnerPassword(admin.id(), venueAccountId, request.newPassword());
    }

    /** {@code iam.venue_account} without its secrets. */
    @Schema(name = "AdminVenueAccount")
    public record AdminVenueAccountView(
            @NotNull @Schema(example = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d") UUID id,
            @NotNull @Schema(example = "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d") UUID venueId,
            @NotNull @Schema(example = "levelup.nasrcity") String loginIdentifier,
            @NotNull VenueAccountStatus accountStatus) {

        static AdminVenueAccountView of(Venues.VenueAccountSummary account) {
            return new AdminVenueAccountView(account.id(), account.venueId(), account.loginIdentifier(),
                    account.status());
        }
    }

    /** Account status changes carry the admin's reason (arch 03 §5 {@code setAccountStatus(…, reason)}). */
    @Schema(name = "AdminVenueAccountReasonRequest")
    public record AdminVenueAccountReasonRequest(@NotBlank @Schema(example = "Owner request") String reason) {
    }

    /** The replacement the admin chose; never returned, logged or echoed. */
    @Schema(name = "AdminVenuePasswordResetRequest")
    public record AdminVenuePasswordResetRequest(
            @NotBlank @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY) String newPassword) {

        @Override
        public String toString() {
            return "AdminVenuePasswordResetRequest[<password redacted>]";
        }
    }
}
