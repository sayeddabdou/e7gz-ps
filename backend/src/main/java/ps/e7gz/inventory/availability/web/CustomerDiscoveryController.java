package ps.e7gz.inventory.availability.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.geo.Coordinates;
import ps.e7gz.common.money.Money;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.GeoPoint;
import ps.e7gz.common.web.MoneyView;
import ps.e7gz.common.web.PageView;
import ps.e7gz.common.web.ResponseMetaFactory;
import ps.e7gz.geography.api.AreaLookup;
import ps.e7gz.inventory.availability.internal.CustomerDiscovery;
import ps.e7gz.inventory.availability.internal.FindAvailable;
import ps.e7gz.venue.api.VenueDiscovery;

/**
 * Customer discovery (arch 09 §3.2, FR-DIS-*). Public — no access token (arch 09 §1.2). List, map and details read
 * the Nearest Availability projection (advisory, FR-AVL-11); Find Available asks the canonical predicate live
 * ({@link FindAvailable}, arch 05 §5.4). Venues that are not marketplace ACTIVE are absent from list, map and search; a
 * deep-linked venue page still answers 200 with {@code bookingEnabled: false} (FR-DIS-08). The venue phone never
 * appears here (FR-DIS-09): no DTO below has a field for it.
 *
 * <p>{@code lat} and {@code lng} are one position: one without the other is VALIDATION_FAILED on the missing one, and
 * each must be in its WGS 84 range ({@link GeoPoint}). The list needs {@code areaId} when no position is sent
 * (VALIDATION_FAILED on {@code areaId}; PO decisions 7 Oct 2026).
 */
@RestController
@RequestMapping("/api/v1/customer")
@Tag(name = "customer-discovery")
public class CustomerDiscoveryController {

    private final CustomerDiscovery discovery;
    private final FindAvailable findAvailable;
    private final ResponseMetaFactory meta;

    public CustomerDiscoveryController(CustomerDiscovery discovery, FindAvailable findAvailable,
                                       ResponseMetaFactory meta) {
        this.discovery = discovery;
        this.findAvailable = findAvailable;
        this.meta = meta;
    }

    @GetMapping("/venues")
    @Operation(operationId = "customerListVenues",
            summary = "Eligible venues; nearest first with a location; a chosen area is a hard filter (FR-DIS-01, FR-DIS-02)")
    public DataResponse<PageView<CustomerVenueCard>> listVenues(
            @RequestParam(required = false) UUID areaId,
            @RequestParam Activity activity,
            @Parameter(description = "Customer latitude; send with lng.") @RequestParam(required = false) Double lat,
            @Parameter(description = "Customer longitude; send with lat.") @RequestParam(required = false) Double lng,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        Coordinates near = position(lat, lng);
        if (areaId == null && near == null) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "areaId");
        }
        CustomerDiscovery.CardPage page = discovery.list(areaId, activity, near, cursor, limit);
        return meta.data(new PageView<>(page.items().stream().map(CustomerDiscoveryController::card).toList(),
                page.nextCursor()));
    }

    @GetMapping("/venues/map")
    @Operation(operationId = "customerListVenueMarkers",
            summary = "Map markers under the same eligibility rules as the list (FR-DIS-10)")
    public DataResponse<List<CustomerVenueMarker>> listVenueMarkers(
            @Parameter(description = "Visible map area as minLng,minLat,maxLng,maxLat.", example = "31.20,29.95,31.45,30.15")
            @RequestParam String bounds,
            @Parameter(description = "Manual area selection; remains a hard filter in Map view (FR-DIS-02).")
            @RequestParam(required = false) UUID areaId,
            @RequestParam Activity activity,
            @Parameter(description = "Customer latitude, for distance; send with lng.") @RequestParam(required = false) Double lat,
            @Parameter(description = "Customer longitude, for distance; send with lat.") @RequestParam(required = false) Double lng) {
        return meta.data(discovery.markers(viewport(bounds), areaId, activity, position(lat, lng)).stream()
                .map(CustomerDiscoveryController::marker).toList());
    }

    /**
     * {@code minLng,minLat,maxLng,maxLat} (SPRINT-1-CONTRACT section 2): four decimals, each in its WGS 84 range, a
     * minimum never above its maximum; anything else is VALIDATION_FAILED on {@code bounds}.
     */
    static VenueDiscovery.Viewport viewport(String bounds) {
        String[] parts = bounds.split(",", -1);
        if (parts.length != 4) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "bounds");
        }
        BigDecimal[] values = new BigDecimal[4];
        try {
            for (int i = 0; i < 4; i++) {
                values[i] = new BigDecimal(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "bounds");
        }
        boolean inRange = within(values[0], 180) && within(values[1], 90) && within(values[2], 180)
                && within(values[3], 90);
        if (!inRange || values[0].compareTo(values[2]) > 0 || values[1].compareTo(values[3]) > 0) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "bounds");
        }
        return new VenueDiscovery.Viewport(values[0], values[1], values[2], values[3]);
    }

    private static boolean within(BigDecimal value, int limit) {
        return value.abs().compareTo(BigDecimal.valueOf(limit)) <= 0;
    }

    @GetMapping("/venues/{venueId}")
    @Operation(operationId = "customerGetVenue",
            summary = "Venue details; answers for non-ACTIVE venues too, with bookingEnabled false (FR-DIS-06, FR-DIS-08, FR-DIS-11)")
    public DataResponse<CustomerVenueDetails> getVenue(
            @PathVariable UUID venueId,
            @RequestParam(required = false) Activity activity,
            @Parameter(description = "Customer latitude, for distance; send with lng.") @RequestParam(required = false) Double lat,
            @Parameter(description = "Customer longitude, for distance; send with lat.") @RequestParam(required = false) Double lng) {
        return meta.data(details(discovery.details(venueId, activity, position(lat, lng))));
    }

    /** The customer position, or null without one; half a position or one out of range is VALIDATION_FAILED. */
    static Coordinates position(Double lat, Double lng) {
        if (lat == null && lng == null) {
            return null;
        }
        if (lng == null) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "lng");
        }
        if (lat == null) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "lat");
        }
        if (!(lat >= -90 && lat <= 90)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "lat");
        }
        if (!(lng >= -180 && lng <= 180)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "lng");
        }
        return new GeoPoint(lat, lng).toCoordinates();
    }

    private static CustomerVenueCard card(CustomerDiscovery.Card card) {
        return new CustomerVenueCard(card.id(), card.name(), url(card.imageUrl()), area(card.area()),
                card.distanceMeters(), money(card.startingPrice()), card.nearestAvailableAt(), card.averageRating(),
                card.reviewCount());
    }

    private static CustomerVenueMarker marker(CustomerDiscovery.Marker marker) {
        return new CustomerVenueMarker(marker.id(), marker.name(), url(marker.imageUrl()), GeoPoint.of(marker.location()),
                marker.distanceMeters(), money(marker.startingPrice()), marker.nearestAvailableAt(),
                marker.averageRating(), marker.reviewCount());
    }

    private static CustomerAvailableVenue available(FindAvailable.Result result) {
        return new CustomerAvailableVenue(result.venueId(), result.venueName(), result.resources().stream()
                .map(r -> new CustomerVenueResource(r.resourceId(), r.name(), r.consoleType())).toList());
    }

    private static CustomerVenueDetails details(CustomerDiscovery.Details d) {
        return new CustomerVenueDetails(d.id(), d.name(), d.imageUrls().stream().map(URI::toString).toList(),
                area(d.area()), d.address(), GeoPoint.of(d.location()), d.distanceMeters(),
                d.openingHours().stream().map(day -> new CustomerOpeningDay(day.weekday(), day.closed(),
                        day.opensAtLocal(), day.closesAtLocal())).toList(),
                d.openNow(), d.opensAt(), d.activities(), d.selectedActivity(),
                d.resources().stream().map(r -> new CustomerVenueResource(r.resourceId(), r.name(), r.consoleType()))
                        .toList(),
                d.prices().stream().map(p -> new CustomerVenuePrice(p.consoleType(), p.playMode(),
                        MoneyView.of(p.hourlyPrice()))).toList(),
                d.nearestAvailableAt(), d.averageRating(), d.reviewCount(), d.bookingEnabled());
    }

    private static CustomerVenueArea area(AreaLookup.AreaName area) {
        return new CustomerVenueArea(area.id(), area.name(), area.nameAr());
    }

    private static MoneyView money(Money money) {
        return money == null ? null : MoneyView.of(money);
    }

    private static String url(URI uri) {
        return uri == null ? null : uri.toString();
    }

    @GetMapping("/find-available")
    @Operation(operationId = "customerFindAvailable",
            summary = "Resources free for the whole requested interval; no price filter (FR-DIS-12)",
            description = "Only intervals inside one Operating Session of the venue, starting at least 15 minutes "
                    + "ahead (FR-BOK-02), are searched; an earlier start finds nothing (PO decisions 8 Oct 2026).")
    public DataResponse<List<CustomerAvailableVenue>> findAvailable(
            @RequestParam UUID areaId,
            @RequestParam Activity activity,
            @Parameter(description = "Local date (Africa/Cairo).") @RequestParam LocalDate date,
            @Parameter(description = "Local start time (Africa/Cairo).", example = "18:00:00") @RequestParam LocalTime startTime,
            @Parameter(description = "60 to 360 minutes in steps of 30 (FR-BOK-01).", example = "90")
            @RequestParam @Min(60) @Max(360) Integer durationMinutes) {
        return meta.data(findAvailable.search(areaId, activity, date, startTime, durationMinutes).stream()
                .map(CustomerDiscoveryController::available).toList());
    }

    @Schema(name = "CustomerVenueArea")
    public record CustomerVenueArea(
            @NotNull @Schema(example = "3f6c1d2e-8a4b-4c5d-9e6f-7a8b9c0d1e2f") UUID id,
            @NotNull @Schema(example = "Nasr City") String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "مدينة نصر") String nameAr) {
    }

    /** The venue card (FR-DIS-03). {@code nearestAvailableAt} is a projection snapshot, not a promise (FR-AVL-11). */
    @Schema(name = "CustomerVenueCard")
    public record CustomerVenueCard(
            @NotNull @Schema(example = "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d") UUID id,
            @NotNull @Schema(example = "Level Up Lounge") String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, format = "uri",
                    example = "https://files.example.test/venues/level-up/1.jpg") String imageUrl,
            @NotNull CustomerVenueArea area,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "Metres from the customer; null without a location.", example = "1250") Integer distanceMeters,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) MoneyView startingPrice,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-12T18:00:00Z")
            Instant nearestAvailableAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "4.6") BigDecimal averageRating,
            @NotNull @Schema(example = "38") Integer reviewCount) {
    }

    /** A map marker and its preview (FR-DIS-10). */
    @Schema(name = "CustomerVenueMarker")
    public record CustomerVenueMarker(
            @NotNull @Schema(example = "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d") UUID id,
            @NotNull @Schema(example = "Level Up Lounge") String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, format = "uri",
                    example = "https://files.example.test/venues/level-up/1.jpg") String imageUrl,
            @NotNull GeoPoint location,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "1250") Integer distanceMeters,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) MoneyView startingPrice,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-12T18:00:00Z")
            Instant nearestAvailableAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "4.6") BigDecimal averageRating,
            @NotNull @Schema(example = "38") Integer reviewCount) {
    }

    /** Venue details (FR-DIS-06, FR-DIS-11). Resources and prices are for the selected activity. */
    @Schema(name = "CustomerVenueDetails")
    public record CustomerVenueDetails(
            @NotNull @Schema(example = "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d") UUID id,
            @NotNull @Schema(example = "Level Up Lounge") String name,
            @NotNull @ArraySchema(schema = @Schema(format = "uri", example = "https://files.example.test/venues/level-up/1.jpg"))
            List<String> imageUrls,
            @NotNull CustomerVenueArea area,
            @NotNull @Schema(example = "12 Abbas El Akkad St, Nasr City") String address,
            @NotNull GeoPoint location,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "1250") Integer distanceMeters,
            @NotNull List<CustomerOpeningDay> openingHours,
            @NotNull @Schema(description = "False shows as Closed Now (FR-DIS-06).") Boolean openNow,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "Start of the next operating session while closed (Opens at …).", example = "2026-10-12T09:00:00Z")
            Instant opensAt,
            @NotNull List<Activity> activities,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Activity selectedActivity,
            @NotNull List<CustomerVenueResource> resources,
            @NotNull List<CustomerVenuePrice> prices,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-12T18:00:00Z")
            Instant nearestAvailableAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "4.6") BigDecimal averageRating,
            @NotNull @Schema(example = "38") Integer reviewCount,
            @NotNull @Schema(description = "False when the venue is not marketplace ACTIVE (FR-DIS-08).") Boolean bookingEnabled) {
    }

    /** One weekday of the venue's opening hours, in venue local time. */
    @Schema(name = "CustomerOpeningDay")
    public record CustomerOpeningDay(
            @NotNull @Min(0) @Max(6) @Schema(description = "venue.venue_opening_day.weekday: 0 = Monday … 6 = Sunday.", example = "0")
            Integer weekday,
            @NotNull Boolean closed,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "12:00:00") LocalTime opensAtLocal,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "02:00:00") LocalTime closesAtLocal) {
    }

    /** A resource of the selected activity; console type shown wherever a resource appears (FR-VEN-41). */
    @Schema(name = "CustomerVenueResource")
    public record CustomerVenueResource(
            @NotNull @Schema(example = "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f") UUID id,
            @NotNull @Schema(example = "Room 1") String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) ConsoleType consoleType) {
    }

    /** An hourly price of the selected activity (FR-VEN-43, FR-VEN-44); console and mode only for PlayStation. */
    @Schema(name = "CustomerVenuePrice")
    public record CustomerVenuePrice(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) ConsoleType consoleType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) PlayMode playMode,
            @NotNull MoneyView hourlyPrice) {
    }

    /** A venue with at least one resource free for the whole interval (FR-DIS-12). */
    @Schema(name = "CustomerAvailableVenue")
    public record CustomerAvailableVenue(
            @NotNull @Schema(example = "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d") UUID venueId,
            @NotNull @Schema(example = "Level Up Lounge") String venueName,
            @NotNull List<CustomerVenueResource> resources) {
    }
}
