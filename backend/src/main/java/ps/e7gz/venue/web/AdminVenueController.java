package ps.e7gz.venue.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.VenueAccountLanguage;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.GeoPoint;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.PageView;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.venue.internal.MarketplaceAction;
import ps.e7gz.venue.internal.Venue;
import ps.e7gz.venue.internal.Venues;

/**
 * Platform Admin — venues and onboarding (arch 09 §5, arch 03 §5, FR-ADM-02, FR-VEN-06, FR-VEN-07). A new venue starts
 * in ONBOARDING. Implemented by E7-23 (venue account creation: E7-24).
 */
@RestController
@RequestMapping("/api/v1/admin/venues")
@Tag(name = "admin-venues")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class AdminVenueController {

    static final int MAX_TEXT = 200;

    private final Venues venues;
    private final ResponseMetaFactory meta;

    public AdminVenueController(Venues venues, ResponseMetaFactory meta) {
        this.venues = venues;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "adminListVenues", summary = "All venues, any marketplace status")
    public DataResponse<PageView<AdminVenueView>> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        Venues.Page page = venues.list(cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(AdminVenueView::of).toList(), page.nextCursor()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "adminCreateVenue", summary = "Create a venue in ONBOARDING, in an active area (FR-ADM-02, FR-VEN-12)")
    @ApiError(status = 422, codes = ApiErrorCode.AREA_INACTIVE)
    public DataResponse<AdminVenueView> create(@AuthenticationPrincipal AuthenticatedSubject admin,
                                               @Valid @RequestBody AdminVenueCreateRequest request) {
        return meta.data(AdminVenueView.of(venues.create(admin.id(), new Venues.NewVenue(request.areaId(),
                request.name(), request.address(), request.location().toCoordinates(), request.phone(),
                request.activities()))));
    }

    @PatchMapping("/{venueId}")
    @Operation(operationId = "adminUpdateVenue", summary = "Edit venue information; a new area must be active (FR-VEN-12)")
    @ApiError(status = 422, codes = {ApiErrorCode.AREA_INACTIVE, ApiErrorCode.VENUE_ACTIVITY_IN_USE})
    public DataResponse<AdminVenueView> update(@AuthenticationPrincipal AuthenticatedSubject admin,
                                               @PathVariable UUID venueId,
                                               @Valid @RequestBody AdminVenueUpdateRequest request) {
        GeoPoint location = request.location();
        return meta.data(AdminVenueView.of(venues.update(admin.id(), venueId, new Venues.VenueChanges(
                request.areaId(), request.name(), request.address(),
                location == null ? null : location.toCoordinates(), request.phone(), request.activities()))));
    }

    @PostMapping("/{venueId}/onboarding/complete")
    @Operation(operationId = "adminCompleteVenueOnboarding",
            summary = "Complete onboarding: ONBOARDING → INACTIVE, never ACTIVE (FR-ADM-02, FR-VEN-07)",
            description = "Completing onboarding does not activate the venue (PO decision, 28 Sep 2026). The venue "
                    + "leaves ONBOARDING for INACTIVE — hidden from discovery, Dashboard and manual booking available "
                    + "(FR-VEN-07); the database never lets it return to ONBOARDING. Activation is the separate "
                    + "activate action.")
    @ApiError(status = 422, codes = ApiErrorCode.MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED)
    public DataResponse<AdminVenueView> completeOnboarding(
            @AuthenticationPrincipal AuthenticatedSubject admin,
            @PathVariable UUID venueId, @Valid @RequestBody AdminVenueReasonRequest request) {
        return changeStatus(admin, venueId, MarketplaceAction.COMPLETE_ONBOARDING, request);
    }

    @GetMapping("/{venueId}")
    @Operation(operationId = "adminGetVenue", summary = "One venue with its venue account, if created (FR-ADM-02)")
    public DataResponse<AdminVenueDetailView> get(@PathVariable UUID venueId) {
        Venues.VenueDetail detail = venues.get(venueId);
        Venues.VenueAccountSummary account = detail.account();
        return meta.data(new AdminVenueDetailView(AdminVenueView.of(detail.venue()), account == null ? null
                : new AdminVenueAccountController.AdminVenueAccountView(account.id(), account.venueId(),
                        account.loginIdentifier(), account.status())));
    }

    // Marketplace status after onboarding: ACTIVE, INACTIVE and SUSPENDED, in any direction between the three, never
    // back to ONBOARDING (FR-VEN-07; the database refuses that, tr_127_venue_marketplace_transition). One action per
    // target status, each with the admin's reason (PO decision 2 Oct 2026, E7-22). A change the current status does not
    // allow (e.g. completing onboarding twice, or moving to the status the venue already has) is
    // MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED; ACTIVE or SUSPENDED in an inactive area is AREA_INACTIVE
    // (gd_venue_area_inactive_status). Stub until E7-23.

    @PostMapping("/{venueId}/activate")
    @Operation(operationId = "adminActivateVenue",
            summary = "Marketplace status → ACTIVE: discoverable and bookable online (FR-VEN-07)")
    @ApiError(status = 422, codes = {ApiErrorCode.MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED, ApiErrorCode.AREA_INACTIVE})
    public DataResponse<AdminVenueView> activate(
            @AuthenticationPrincipal AuthenticatedSubject admin,
            @PathVariable UUID venueId, @Valid @RequestBody AdminVenueReasonRequest request) {
        return changeStatus(admin, venueId, MarketplaceAction.ACTIVATE, request);
    }

    @PostMapping("/{venueId}/deactivate")
    @Operation(operationId = "adminDeactivateVenue",
            summary = "Marketplace status → INACTIVE: hidden, non-punitive; Dashboard and manual booking stay (FR-VEN-07)")
    @ApiError(status = 422, codes = ApiErrorCode.MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED)
    public DataResponse<AdminVenueView> deactivate(
            @AuthenticationPrincipal AuthenticatedSubject admin,
            @PathVariable UUID venueId, @Valid @RequestBody AdminVenueReasonRequest request) {
        return changeStatus(admin, venueId, MarketplaceAction.DEACTIVATE, request);
    }

    @PostMapping("/{venueId}/suspend")
    @Operation(operationId = "adminSuspendVenue",
            summary = "Marketplace status → SUSPENDED: as INACTIVE plus no new marketplace tournaments; administrative "
                    + "and punitive (FR-VEN-07)")
    @ApiError(status = 422, codes = {ApiErrorCode.MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED, ApiErrorCode.AREA_INACTIVE})
    public DataResponse<AdminVenueView> suspend(
            @AuthenticationPrincipal AuthenticatedSubject admin,
            @PathVariable UUID venueId, @Valid @RequestBody AdminVenueReasonRequest request) {
        return changeStatus(admin, venueId, MarketplaceAction.SUSPEND, request);
    }

    @PostMapping("/{venueId}/venue-account")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "adminCreateVenueAccount",
            summary = "Create the venue's one shared account with its initial Owner Password (FR-ADM-02, FR-VEN-01)",
            description = "Onboarding is admin-managed, including the account credentials and the initial Owner "
                    + "Password (FR-ADM-02; PO decision 2 Oct 2026, E7-22). One account per venue "
                    + "(uq_venue_account_venue). Both passwords are write-only: never returned, logged or echoed. "
                    + "The account starts ENABLED. Stub until E7-24.")
    @ApiError(status = 422, codes = {ApiErrorCode.VENUE_LOGIN_IDENTIFIER_TAKEN, ApiErrorCode.VENUE_ACCOUNT_ALREADY_EXISTS})
    public DataResponse<AdminVenueAccountController.AdminVenueAccountView> createVenueAccount(
            @PathVariable UUID venueId, @Valid @RequestBody AdminVenueAccountCreateRequest request) {
        throw NotImplementedYet.stub();
    }

    private DataResponse<AdminVenueView> changeStatus(AuthenticatedSubject admin, UUID venueId,
                                                      MarketplaceAction action, AdminVenueReasonRequest request) {
        return meta.data(AdminVenueView.of(venues.changeStatus(admin.id(), venueId, action, request.reason())));
    }

    /** {@code venue.venue} with its activities ({@code venue.venue_activity}). */
    @Schema(name = "AdminVenue")
    public record AdminVenueView(
            @NotNull @Schema(example = "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d") UUID id,
            @NotNull @Schema(example = "3f6c1d2e-8a4b-4c5d-9e6f-7a8b9c0d1e2f") UUID areaId,
            @NotNull @Schema(example = "Level Up Lounge") String name,
            @NotNull @Schema(example = "12 Abbas El Akkad St, Nasr City") String address,
            @NotNull GeoPoint location,
            @NotNull @Schema(example = "+201001234567") String phone,
            @NotNull @Schema(example = "Africa/Cairo") String timezone,
            @NotNull MarketplaceStatus marketplaceStatus,
            @NotNull List<Activity> activities) {

        static AdminVenueView of(Venue venue) {
            return new AdminVenueView(venue.id(), venue.areaId(), venue.name(), venue.address(),
                    GeoPoint.of(venue.location()), venue.phone(), venue.timezone(), venue.marketplaceStatus(),
                    venue.activities());
        }
    }

    @Schema(name = "AdminVenueCreateRequest")
    public record AdminVenueCreateRequest(
            @NotNull @Schema(example = "3f6c1d2e-8a4b-4c5d-9e6f-7a8b9c0d1e2f") UUID areaId,
            @NotBlank @Schema(example = "Level Up Lounge") String name,
            @NotBlank @Schema(example = "12 Abbas El Akkad St, Nasr City") String address,
            @NotNull @Valid GeoPoint location,
            @NotBlank @Schema(example = "+201001234567") String phone,
            @NotEmpty List<@NotNull Activity> activities) {
    }

    /** Only the fields sent change. */
    @Schema(name = "AdminVenueUpdateRequest")
    public record AdminVenueUpdateRequest(
            UUID areaId,
            @Schema(example = "Level Up Lounge") String name,
            @Schema(example = "12 Abbas El Akkad St, Nasr City") String address,
            @Valid GeoPoint location,
            @Schema(example = "+201001234567") String phone,
            List<@NotNull Activity> activities) {
    }

    /** Status changes carry the admin's reason (arch 03 §5 {@code setMarketplaceStatus(…, reason)}). */
    @Schema(name = "AdminVenueReasonRequest")
    public record AdminVenueReasonRequest(@NotBlank @Schema(example = "Setup verified on site") String reason) {
    }

    /** One venue for the admin venue page; {@code venueAccount} is null until the account is created. */
    @Schema(name = "AdminVenueDetail")
    public record AdminVenueDetailView(
            @NotNull AdminVenueView venue,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            AdminVenueAccountController.AdminVenueAccountView venueAccount) {
    }

    /** {@code iam.venue_account} and {@code iam.venue_owner_secret}; the server hashes both passwords (Argon2id). */
    @Schema(name = "AdminVenueAccountCreateRequest")
    public record AdminVenueAccountCreateRequest(
            @NotBlank @Size(max = MAX_TEXT) @Schema(example = "levelup.nasrcity") String loginIdentifier,
            @NotBlank @Size(max = MAX_TEXT) @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
            String password,
            @NotBlank @Size(max = MAX_TEXT) @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    description = "Sets the first Owner Password (FR-ADM-02); not a check of an existing one.")
            String initialOwnerPassword,
            @Schema(description = "Dashboard language; en when absent (FR-VEN-40).") VenueAccountLanguage languagePreference) {

        @Override
        public String toString() {
            return "AdminVenueAccountCreateRequest[loginIdentifier=" + loginIdentifier + ", <passwords redacted>]";
        }
    }
}
