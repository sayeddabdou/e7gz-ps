package ps.e7gz.inventory.availability.internal;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.geo.Coordinates;
import ps.e7gz.common.money.Money;
import ps.e7gz.geography.api.AreaLookup;
import ps.e7gz.geography.api.AreaLookup.AreaName;
import ps.e7gz.inventory.availability.api.AvailabilitySnapshots;
import ps.e7gz.inventory.availability.api.AvailabilitySnapshots.Snapshot;
import ps.e7gz.inventory.availability.api.DiscoveryRatings;
import ps.e7gz.inventory.availability.api.DiscoveryRatings.Rating;
import ps.e7gz.pricing.api.VenuePriceList;
import ps.e7gz.pricing.api.VenuePriceList.HourlyRate;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.venue.api.VenueDiscovery;
import ps.e7gz.venue.api.VenueDiscovery.OpenState;
import ps.e7gz.venue.api.VenueDiscovery.OpeningDay;
import ps.e7gz.venue.api.VenueDiscovery.Profile;
import ps.e7gz.venue.api.VenuePhotos;

/**
 * Customer discovery (arch 09 section 3.2, E7-34): the venue list, the map markers and Venue Details, each composed from
 * a fixed number of set reads through the owning modules' published APIs (arch 03 section 2.2) - never one read per
 * venue:
 * <ol>
 *   <li>the page of venues, filtered, ordered and keyset-bounded by PostGIS in the venue module (ADR-019);</li>
 *   <li>their area names (geography);</li>
 *   <li>their Nearest Availability projection rows, by primary key ({@link AvailabilitySnapshots}, NFR-11);</li>
 *   <li>their rating statistics, through {@link DiscoveryRatings}, which the Reviews module implements (arch 03
 *       section 2.1: Availability never depends upward on Reviews);</li>
 *   <li>their card photos, signed small copies (E7-23).</li>
 * </ol>
 * An advisory read path ({@code FR-AVL-11}): nothing here asks the canonical predicate or refreshes the projection.
 */
@Service
public class CustomerDiscovery {

    /** Page size when the request names none (the admin venue list's default). */
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final VenueDiscovery venues;
    private final AreaLookup areas;
    private final AvailabilitySnapshots snapshots;
    private final DiscoveryRatings ratings;
    private final VenuePhotos photos;
    private final ResourceCatalog resources;
    private final VenuePriceList prices;
    private final Clock clock;

    CustomerDiscovery(VenueDiscovery venues, AreaLookup areas, AvailabilitySnapshots snapshots,
                      DiscoveryRatings ratings, VenuePhotos photos, ResourceCatalog resources, VenuePriceList prices,
                      Clock clock) {
        this.venues = venues;
        this.areas = areas;
        this.snapshots = snapshots;
        this.ratings = ratings;
        this.photos = photos;
        this.resources = resources;
        this.prices = prices;
        this.clock = clock;
    }

    /** A venue card (FR-DIS-03). */
    public record Card(UUID id, String name, URI imageUrl, AreaName area, Integer distanceMeters, Money startingPrice,
                       Instant nearestAvailableAt, BigDecimal averageRating, int reviewCount) {
    }

    public record CardPage(List<Card> items, String nextCursor) {
    }

    /** A map marker and its preview (FR-DIS-10): the card's values plus the venue's position. */
    public record Marker(UUID id, String name, URI imageUrl, Coordinates location, Integer distanceMeters,
                         Money startingPrice, Instant nearestAvailableAt, BigDecimal averageRating, int reviewCount) {
    }

    /** Venue Details (FR-DIS-06, FR-DIS-11); resources, prices and Nearest Availability are of the selected activity. */
    public record Details(UUID id, String name, List<URI> imageUrls, AreaName area, String address,
                          Coordinates location, Integer distanceMeters, List<OpeningDay> openingHours, boolean openNow,
                          Instant opensAt, List<Activity> activities, Activity selectedActivity,
                          List<ResourceCatalog.Shown> resources, List<HourlyRate> prices, Instant nearestAvailableAt,
                          BigDecimal averageRating, int reviewCount, boolean bookingEnabled) {
    }

    /**
     * FR-DIS-01, FR-DIS-02: {@code areaId} is a hard filter whenever it is sent; without it a location is required (PO
     * decision 7 Oct 2026; the caller has checked that at least one is present).
     */
    public CardPage list(UUID areaId, Activity activity, Coordinates near, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        VenueDiscovery.Page page = venues.search(
                new VenueDiscovery.Search(areaId, activity, near, DiscoveryCursor.decode(cursor), size));
        List<UUID> ids = page.items().stream().map(VenueDiscovery.Listed::venueId).toList();
        Map<UUID, AreaName> areaNames = areas.names(page.items().stream().map(VenueDiscovery.Listed::areaId).toList());
        Map<UUID, Snapshot> projection = snapshots.of(ids, activity);
        Map<UUID, Rating> stats = ratings.of(ids);
        Map<UUID, VenuePhotos.SignedLink> images = photos.cardImages(ids);
        List<Card> cards = page.items().stream().map(venue -> {
            Snapshot snapshot = projection.get(venue.venueId());
            Rating rating = stats.getOrDefault(venue.venueId(), Rating.NONE);
            VenuePhotos.SignedLink image = images.get(venue.venueId());
            return new Card(venue.venueId(), venue.name(), image == null ? null : image.url(),
                    area(areaNames, venue.areaId()), venue.distanceMeters(),
                    snapshot == null ? null : snapshot.startingPrice(),
                    snapshot == null ? null : snapshot.nearestAvailableAt(), rating.averageRating(),
                    rating.reviewCount());
        }).toList();
        String next = page.more() ? DiscoveryCursor.encode(page.items().getLast().venueId()) : null;
        return new CardPage(cards, next);
    }

    /**
     * FR-DIS-10, ADR-019: the venues inside the map viewport under the list's eligibility rules, each with the card's
     * values, read the same way as {@link #list} - one set read per source, never one per venue.
     */
    public List<Marker> markers(VenueDiscovery.Viewport viewport, UUID areaId, Activity activity, Coordinates near) {
        List<VenueDiscovery.Marker> found =
                venues.markers(new VenueDiscovery.MapSearch(viewport, areaId, activity, near));
        if (found.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = found.stream().map(VenueDiscovery.Marker::venueId).toList();
        Map<UUID, Snapshot> projection = snapshots.of(ids, activity);
        Map<UUID, Rating> stats = ratings.of(ids);
        Map<UUID, VenuePhotos.SignedLink> images = photos.cardImages(ids);
        return found.stream().map(venue -> {
            Snapshot snapshot = projection.get(venue.venueId());
            Rating rating = stats.getOrDefault(venue.venueId(), Rating.NONE);
            VenuePhotos.SignedLink image = images.get(venue.venueId());
            return new Marker(venue.venueId(), venue.name(), image == null ? null : image.url(), venue.location(),
                    venue.distanceMeters(), snapshot == null ? null : snapshot.startingPrice(),
                    snapshot == null ? null : snapshot.nearestAvailableAt(), rating.averageRating(),
                    rating.reviewCount());
        }).toList();
    }

    /**
     * Any existing venue answers, whatever its marketplace status; booking is enabled only when it is ACTIVE (FR-DIS-08).
     * An activity the venue does not offer, or none, selects nothing: no resources, prices or Nearest Availability.
     */
    public Details details(UUID venueId, Activity activity, Coordinates from) {
        Profile venue = venues.profile(venueId, from).orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
        Activity selected = activity != null && venue.activities().contains(activity) ? activity : null;
        OpenState open = venues.openState(venueId, clock.instant());
        Snapshot snapshot = selected == null ? null : snapshots.of(List.of(venueId), selected).get(venueId);
        Rating rating = ratings.of(List.of(venueId)).getOrDefault(venueId, Rating.NONE);
        return new Details(venue.venueId(), venue.name(),
                photos.gallery(venueId).stream().map(VenuePhotos.SignedLink::url).toList(),
                area(areas.names(List.of(venue.areaId())), venue.areaId()), venue.address(), venue.location(),
                venue.distanceMeters(), venues.weeklyHours(venueId), open.openNow(), open.opensAt(),
                venue.activities(), selected,
                selected == null ? List.of() : resources.customerVisible(venueId, selected),
                selected == null ? List.of() : prices.defaults(venueId, selected),
                snapshot == null ? null : snapshot.nearestAvailableAt(), rating.averageRating(), rating.reviewCount(),
                venue.marketplaceStatus() == MarketplaceStatus.ACTIVE);
    }

    /** {@code venue.area_id} is a foreign key: the area always exists. */
    private static AreaName area(Map<UUID, AreaName> names, UUID areaId) {
        AreaName name = names.get(areaId);
        if (name == null) {
            throw new IllegalStateException("Area " + areaId + " of a venue not found");
        }
        return name;
    }
}
