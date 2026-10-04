package ps.e7gz.pricing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.web.ApiError;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.MoneyView;
import ps.e7gz.common.security.AuthenticatedSubject;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.pricing.internal.Pricing;

/**
 * Venue Dashboard — pricing (arch 09 §4.2–§4.3, arch 03 §7, FR-VEN-43…FR-VEN-45). Every write needs the Owner
 * Password in the body (FR-VEN-03, ADR-011). Implemented by E7-27.
 */
@RestController
@RequestMapping("/api/v1/venue")
@Tag(name = "venue-pricing")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class VenuePricingController {

    private final Pricing pricing;
    private final ResponseMetaFactory meta;

    public VenuePricingController(Pricing pricing, ResponseMetaFactory meta) {
        this.pricing = pricing;
        this.meta = meta;
    }

    @GetMapping("/pricing")
    @Operation(operationId = "venueGetPricing", summary = "Venue default prices and room overrides")
    public DataResponse<VenuePricingView> get(@AuthenticationPrincipal AuthenticatedSubject venue) {
        return meta.data(VenuePricingView.of(pricing.get(venue.id())));
    }

    @PutMapping("/pricing")
    @Operation(operationId = "venuePutPricing", summary = "Set venue default prices — Owner Password (FR-VEN-03)",
            description = "Sets each price sent; a price not sent keeps its value (default prices are never deleted). "
                    + "PlayStation rows carry consoleType and playMode, Billiards and Ping Pong rows neither "
                    + "(ck_price_dimensions); one row per activity, console and play mode; only activities the venue "
                    + "offers.")
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenuePricingView> put(@AuthenticationPrincipal AuthenticatedSubject venue,
                                              @Valid @RequestBody VenuePricingUpdateRequest request) {
        return meta.data(VenuePricingView.of(pricing.setDefaults(venue.id(), request.defaults().stream()
                .map(d -> new Pricing.DefaultInput(d.activity(), d.consoleType(), d.playMode(),
                        d.hourlyPrice().toMoney()))
                .toList(), request.ownerPassword())));
    }

    @PutMapping("/resources/{resourceId}/price-override")
    @Operation(operationId = "venuePutRoomPriceOverride",
            summary = "Set a PlayStation room's price override for its console — Owner Password (FR-VEN-03, FR-VEN-43)",
            description = "The prices sent replace the room's overrides; a play mode not sent uses the venue default. "
                    + "PlayStation rooms only (FR-VEN-44); another venue's room is NOT_FOUND.")
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenuePricingView> putOverride(
            @AuthenticationPrincipal AuthenticatedSubject venue, @PathVariable UUID resourceId,
            @Valid @RequestBody VenueRoomOverrideRequest request) {
        return meta.data(VenuePricingView.of(pricing.setOverrides(venue.id(), resourceId, request.prices().stream()
                .map(p -> new Pricing.OverrideInput(p.playMode(), p.hourlyPrice().toMoney())).toList(),
                request.ownerPassword())));
    }

    @DeleteMapping("/resources/{resourceId}/price-override")
    @Operation(operationId = "venueDeleteRoomPriceOverride",
            summary = "Reset a room to the venue default — Owner Password in the body (FR-VEN-03, ADR-011)")
    @ApiError(status = 423, codes = {ApiErrorCode.OWNER_PASSWORD_REQUIRED, ApiErrorCode.OWNER_PASSWORD_INVALID})
    public DataResponse<VenuePricingView> deleteOverride(
            @AuthenticationPrincipal AuthenticatedSubject venue, @PathVariable UUID resourceId,
            @Valid @RequestBody VenuePricingOwnerPasswordRequest request) {
        return meta.data(VenuePricingView.of(pricing.resetOverrides(venue.id(), resourceId,
                request.ownerPassword())));
    }

    @Schema(name = "VenuePricing")
    public record VenuePricingView(
            @NotNull List<VenueDefaultPrice> defaults,
            @NotNull List<VenueRoomOverride> overrides) {

        static VenuePricingView of(Pricing.View view) {
            return new VenuePricingView(
                    view.defaults().stream().map(d -> new VenueDefaultPrice(d.activity(), d.consoleType(),
                            d.playMode(), MoneyView.of(d.hourlyPrice()))).toList(),
                    view.overrides().stream().map(o -> new VenueRoomOverride(o.resourceId(), o.consoleType(),
                            o.playMode(), MoneyView.of(o.hourlyPrice()))).toList());
        }
    }

    /**
     * {@code pricing.venue_default_price}: PlayStation per console × play mode; Billiards and Ping Pong without either
     * ({@code ck_price_dimensions}, FR-VEN-43, FR-VEN-44).
     */
    @Schema(name = "VenueDefaultPrice")
    public record VenueDefaultPrice(
            @NotNull Activity activity,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) ConsoleType consoleType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) PlayMode playMode,
            @NotNull @Valid MoneyView hourlyPrice) {
    }

    /** {@code pricing.resource_price_override}. */
    @Schema(name = "VenueRoomOverride")
    public record VenueRoomOverride(
            @NotNull @Schema(example = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f") UUID resourceId,
            @NotNull ConsoleType consoleType,
            @NotNull PlayMode playMode,
            @NotNull MoneyView hourlyPrice) {
    }

    /** Arch 09 §4.3's example body: {@code { "defaults": [ … ], "ownerPassword": "…" }}. */
    @Schema(name = "VenuePricingUpdateRequest")
    public record VenuePricingUpdateRequest(
            @NotEmpty List<@Valid VenueDefaultPrice> defaults,
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "4-digit Owner PIN; absent or blank is 423 OWNER_PASSWORD_REQUIRED.")
            String ownerPassword) {
    }

    /** Prices per play mode for the room's current console. */
    @Schema(name = "VenueRoomOverrideRequest")
    public record VenueRoomOverrideRequest(
            @NotEmpty List<@Valid VenueRoomOverridePrice> prices,
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "4-digit Owner PIN; absent or blank is 423 OWNER_PASSWORD_REQUIRED.")
            String ownerPassword) {
    }

    @Schema(name = "VenueRoomOverridePrice")
    public record VenueRoomOverridePrice(@NotNull PlayMode playMode, @NotNull @Valid MoneyView hourlyPrice) {
    }

    @Schema(name = "VenuePricingOwnerPasswordRequest")
    public record VenuePricingOwnerPasswordRequest(
            @Schema(format = "password", accessMode = Schema.AccessMode.WRITE_ONLY,
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "4-digit Owner PIN; absent or blank is 423 OWNER_PASSWORD_REQUIRED.")
            String ownerPassword) {
    }
}
