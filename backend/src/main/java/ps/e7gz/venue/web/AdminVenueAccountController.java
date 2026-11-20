package ps.e7gz.venue.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.VenueAccountLanguage;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.venue.internal.VenueAccounts;
import ps.e7gz.venue.internal.Venues;

/**
 * Platform Admin — venue accounts (arch 09 §5, arch 03 §5, FR-ADM-03, FR-VEN-06, FR-VEN-08). Nothing here deletes venue
 * data; an Owner Password reset notifies the venue (that notification comes with E7-43). {@code venueAccountId} is
 * {@code iam.venue_account.id}. Implemented by E7-24 (PO decision 3 Oct 2026); disable changes Dashboard access only -
 * its booking and tournament consequences (O10) come with E7-78. Disable and enable require the Idempotency-Key since
 * the Sprint 5 contract (PO decision 19 Nov 2026, E7-77): with E7-78 a disable cancels AWAITING bookings and moves
 * their Booking Fee refunds (ADR-015). The list is a stub of that contract until E7-78 (carry-forward from E7-37).
 */
@RestController
@RequestMapping("/api/v1/admin/venue-accounts")
@Tag(name = "admin-venue-accounts")
@SecurityRequirement(name = OpenApiConfig.BEARER)
@Validated
public class AdminVenueAccountController {

    static final String DISABLE_ENDPOINT = "POST /api/v1/admin/venue-accounts/{venueAccountId}/disable";
    static final String ENABLE_ENDPOINT = "POST /api/v1/admin/venue-accounts/{venueAccountId}/enable";

    private final VenueAccounts accounts;
    private final ResponseMetaFactory meta;
    private final Idempotency idempotency;

    public AdminVenueAccountController(VenueAccounts accounts, ResponseMetaFactory meta, Idempotency idempotency) {
        this.accounts = accounts;
        this.meta = meta;
        this.idempotency = idempotency;
    }

    @GetMapping
    @Operation(operationId = "adminListVenueAccounts",
            summary = "Every venue account in one list (FR-ADM-03), ordered by venue name then id",
            description = "Cursor paging like GET /admin/venues. Never a password, hash or Owner PIN. Implemented by "
                    + "E7-78 (PO decision 4 Oct 2026, carry-forward from E7-37).")
    public DataResponse<PageView<AdminVenueAccountListItem>> list(
            @Parameter(description = "Only accounts with this status.")
            @RequestParam(required = false) VenueAccountStatus accountStatus,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        throw NotImplementedYet.stub();
    }

    @PostMapping("/{venueAccountId}/disable")
    @Operation(operationId = "adminDisableVenueAccount",
            summary = "Disable Dashboard access; marketplace status is untouched (FR-VEN-06, FR-VEN-08)")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminVenueAccountView> disable(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @AuthenticationPrincipal AuthenticatedSubject admin,
            @PathVariable UUID venueAccountId, @Valid @RequestBody AdminVenueAccountReasonRequest request) {
        return meta.data(AdminVenueAccountView.of(accounts.setStatus(admin.id(), venueAccountId,
                VenueAccountStatus.DISABLED, request.reason(), idempotency.request(admin, DISABLE_ENDPOINT,
                        idempotencyKey, Map.of("venueAccountId", venueAccountId), request))));
    }

    @PostMapping("/{venueAccountId}/enable")
    @Operation(operationId = "adminEnableVenueAccount", summary = "Re-enable Dashboard access (FR-ADM-03)")
    @ApiError(status = 404, codes = ApiErrorCode.NOT_FOUND)
    @ApiError(status = 422, codes = ApiErrorCode.IDEMPOTENCY_KEY_REUSED)
    public DataResponse<AdminVenueAccountView> enable(
            @RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY) UUID idempotencyKey,
            @AuthenticationPrincipal AuthenticatedSubject admin,
            @PathVariable UUID venueAccountId, @Valid @RequestBody AdminVenueAccountReasonRequest request) {
        return meta.data(AdminVenueAccountView.of(accounts.setStatus(admin.id(), venueAccountId,
                VenueAccountStatus.ENABLED, request.reason(), idempotency.request(admin, ENABLE_ENDPOINT,
                        idempotencyKey, Map.of("venueAccountId", venueAccountId), request))));
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
                    + "decision, 28 Sep 2026). The Owner Password is a PIN of exactly 4 digits (PCR-006). No temporary "
                    + "password, reset link or forced change. Audited; the venue "
                    + "is notified (FR-ADM-03). It is the only way to end the long lock (OWNER_PASSWORD_LOCKED, "
                    + "PCR-012); it also ends a running 15-minute lockout, and both counts start again from zero "
                    + "(E7-121).")
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

    /** One row of the venue-account list: the account, its venue's name and its Dashboard language (E7-37). */
    @Schema(name = "AdminVenueAccountListItem")
    public record AdminVenueAccountListItem(
            @NotNull @Schema(example = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d") UUID id,
            @NotNull @Schema(example = "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d") UUID venueId,
            @NotNull @Schema(example = "levelup.nasrcity") String loginIdentifier,
            @NotNull VenueAccountStatus accountStatus,
            @NotNull @Schema(example = "Level Up Nasr City") String venueName,
            @NotNull VenueAccountLanguage languagePreference) {
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
