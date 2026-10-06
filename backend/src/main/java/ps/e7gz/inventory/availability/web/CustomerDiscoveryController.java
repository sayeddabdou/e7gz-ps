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
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.GeoPoint;
import ps.e7gz.common.web.MoneyView;
import ps.e7gz.common.web.NotImplementedYet;
import ps.e7gz.common.web.PageView;

/**
 * Customer discovery (arch 09 §3.2, FR-DIS-*). Public — no access token (arch 09 §1.2). Stub until E7-34.
 * Venues that are not marketplace ACTIVE are absent from list, map and search; a deep-linked venue page still
 * answers 200 with {@code bookingEnabled: false} (FR-DIS-08). The venue phone never appears here (FR-DIS-09).
 */
@RestController
@RequestMapping("/api/v1/customer")
@Tag(name = "customer-discovery")
public class CustomerDiscoveryController {

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
        throw NotImplementedYet.stub();
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
        throw NotImplementedYet.stub();
    }

    @GetMapping("/venues/{venueId}")
    @Operation(operationId = "customerGetVenue",
            summary = "Venue details; answers for non-ACTIVE venues too, with bookingEnabled false (FR-DIS-06, FR-DIS-08, FR-DIS-11)")
    public DataResponse<CustomerVenueDetails> getVenue(
            @PathVariable UUID venueId,
            @RequestParam(required = false) Activity activity,
            @Parameter(description = "Customer latitude, for distance; send with lng.") @RequestParam(required = false) Double lat,
            @Parameter(description = "Customer longitude, for distance; send with lat.") @RequestParam(required = false) Double lng) {
        throw NotImplementedYet.stub();
    }

    @GetMapping("/find-available")
    @Operation(operationId = "customerFindAvailable",
            summary = "Resources free for the whole requested interval; no price filter (FR-DIS-12)")
    public DataResponse<List<CustomerAvailableVenue>> findAvailable(
            @RequestParam UUID areaId,
            @RequestParam Activity activity,
            @Parameter(description = "Local date (Africa/Cairo).") @RequestParam LocalDate date,
            @Parameter(description = "Local start time (Africa/Cairo).", example = "18:00:00") @RequestParam LocalTime startTime,
            @RequestParam @Min(1) Integer durationMinutes) {
        throw NotImplementedYet.stub();
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
