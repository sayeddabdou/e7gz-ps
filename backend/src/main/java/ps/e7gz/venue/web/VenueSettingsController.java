package ps.e7gz.venue.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.VenueAccountLanguage;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.GeoPoint;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.venue.internal.Venue;
import ps.e7gz.venue.internal.VenueAccounts;

/**
 * Venue Dashboard — the venue's own profile and Dashboard language (arch 09 §4.2, FR-VEN-40). The venue edits its
 * name, address and phone (PO decision 3 Oct 2026, E7-35) and its language; area, location, activities, timezone and
 * marketplace status stay with the Platform Admin (FR-ADM-02, FR-VEN-07, FR-VEN-12). No Owner Password: not a
 * protected action (FR-VEN-03). A venue only ever reads and changes its own venue. Implemented by E7-24.
 */
@RestController
@RequestMapping("/api/v1/venue/settings")
@Tag(name = "venue-settings")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenueSettingsController {

    private final VenueAccounts accounts;
    private final ResponseMetaFactory meta;

    public VenueSettingsController(VenueAccounts accounts, ResponseMetaFactory meta) {
        this.accounts = accounts;
        this.meta = meta;
    }

    @GetMapping
    @Operation(operationId = "venueGetSettings",
            summary = "The venue's profile and the account's Dashboard language (FR-VEN-40)")
    public DataResponse<VenueSettingsView> get(@AuthenticationPrincipal AuthenticatedSubject venue) {
        return meta.data(VenueSettingsView.of(accounts.settings(venue.id())));
    }

    @PatchMapping
    @Operation(operationId = "venueUpdateSettings",
            summary = "Edit name, address, phone and Dashboard language; only the fields sent change (FR-VEN-40)",
            description = "PO decision 3 Oct 2026 (E7-35): the venue may edit its name, address and phone; the same "
                    + "rules as the admin venue edit apply (not blank when sent, at most 200 characters, phone as "
                    + "below). Area, location, activities, timezone and marketplace status are not editable here.")
    public DataResponse<VenueSettingsView> update(@AuthenticationPrincipal AuthenticatedSubject venue,
                                                  @Valid @RequestBody VenueSettingsUpdateRequest request) {
        return meta.data(VenueSettingsView.of(accounts.updateSettings(venue.id(), new VenueAccounts.SettingsChanges(
                request.name(), request.address(), request.phone(), request.languagePreference()))));
    }

    /** {@code venue.venue} with its activities, its area's name and {@code iam.venue_account.language_preference}. */
    @Schema(name = "VenueSettings")
    public record VenueSettingsView(
            @NotNull @Schema(example = "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d") UUID venueId,
            @NotNull @Schema(example = "Level Up Lounge") String name,
            @NotNull @Schema(example = "3f6c1d2e-8a4b-4c5d-9e6f-7a8b9c0d1e2f") UUID areaId,
            @NotNull @Schema(example = "Nasr City") String areaName,
            @NotNull @Schema(example = "12 Abbas El Akkad St, Nasr City") String address,
            @NotNull GeoPoint location,
            @NotNull @Schema(example = "+201001234567") String phone,
            @NotNull @Schema(example = "Africa/Cairo") String timezone,
            @NotNull MarketplaceStatus marketplaceStatus,
            @NotNull List<Activity> activities,
            @NotNull VenueAccountLanguage languagePreference) {

        static VenueSettingsView of(VenueAccounts.Settings settings) {
            Venue venue = settings.venue();
            return new VenueSettingsView(venue.id(), venue.name(), venue.areaId(), settings.areaName(),
                    venue.address(), GeoPoint.of(venue.location()), venue.phone(), venue.timezone(),
                    venue.marketplaceStatus(), venue.activities(), settings.languagePreference());
        }
    }

    /** Only the fields sent change; a text field that is sent must not be blank. */
    @Schema(name = "VenueSettingsUpdateRequest")
    public record VenueSettingsUpdateRequest(
            @Size(max = AdminVenueController.MAX_TEXT) @Schema(example = "Level Up Lounge") String name,
            @Size(max = AdminVenueController.MAX_TEXT) @Schema(example = "12 Abbas El Akkad St, Nasr City") String address,
            @Schema(example = "+201001234567", description = AdminVenueController.PHONE_RULE) String phone,
            VenueAccountLanguage languagePreference) {
    }
}
