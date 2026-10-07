package ps.e7gz.inventory.availability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.money.Money;
import ps.e7gz.geography.api.AreaLookup;
import ps.e7gz.inventory.availability.api.AvailabilitySnapshots;
import ps.e7gz.inventory.availability.api.DiscoveryRatings;
import ps.e7gz.pricing.api.VenuePriceList;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.venue.api.VenueDiscovery;
import ps.e7gz.venue.api.VenuePhotos;

/**
 * E7-34: a list page is a fixed number of set reads, whatever its size - one search, then one batch read each of the
 * area names, the projection rows, the ratings and the card photos, all with the whole page's ids. No per-venue read.
 */
class CustomerDiscoveryTest {

    private final VenueDiscovery venues = mock(VenueDiscovery.class);
    private final AreaLookup areas = mock(AreaLookup.class);
    private final AvailabilitySnapshots snapshots = mock(AvailabilitySnapshots.class);
    private final DiscoveryRatings ratings = mock(DiscoveryRatings.class);
    private final VenuePhotos photos = mock(VenuePhotos.class);
    private final ResourceCatalog resources = mock(ResourceCatalog.class);
    private final VenuePriceList prices = mock(VenuePriceList.class);
    private final CustomerDiscovery discovery = new CustomerDiscovery(venues, areas, snapshots, ratings, photos,
            resources, prices, Clock.fixed(Instant.parse("2031-01-01T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void aPageOfTwentyVenuesIsFiveSetReads() {
        UUID area = UUID.randomUUID();
        List<VenueDiscovery.Listed> listed = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            listed.add(new VenueDiscovery.Listed(UUID.randomUUID(), "Venue " + i, area, 100 * i));
        }
        List<UUID> ids = listed.stream().map(VenueDiscovery.Listed::venueId).toList();
        UUID first = ids.getFirst();
        when(venues.search(any())).thenReturn(new VenueDiscovery.Page(listed, true));
        when(areas.names(anyCollection())).thenReturn(Map.of(area, new AreaLookup.AreaName(area, "Maadi", null)));
        when(snapshots.of(anyCollection(), eq(Activity.PLAYSTATION))).thenReturn(Map.of(first,
                new AvailabilitySnapshots.Snapshot(first, Instant.parse("2031-01-01T12:00:00Z"), Money.of("50.00"))));
        when(ratings.of(anyCollection())).thenReturn(Map.of());
        when(photos.cardImages(anyCollection())).thenReturn(Map.of(first,
                new VenuePhotos.SignedLink(URI.create("https://files.test/small.jpg"), Instant.EPOCH)));

        CustomerDiscovery.CardPage page = discovery.list(area, Activity.PLAYSTATION, null, null, 20);

        assertThat(page.items()).hasSize(20);
        assertThat(page.items().getFirst().startingPrice()).isEqualTo(Money.of("50.00"));
        assertThat(page.items().get(1).startingPrice()).isNull();
        assertThat(page.items().get(1).averageRating()).isNull();
        assertThat(page.items().get(1).reviewCount()).isZero();
        assertThat(page.items().get(1).imageUrl()).isNull();
        assertThat(page.nextCursor()).isEqualTo(DiscoveryCursor.encode(ids.getLast()));

        verify(venues, times(1)).search(any());
        verify(areas, times(1)).names(anyCollection());
        ArgumentCaptor<java.util.Collection<UUID>> snapshotIds = collection();
        verify(snapshots, times(1)).of(snapshotIds.capture(), eq(Activity.PLAYSTATION));
        ArgumentCaptor<java.util.Collection<UUID>> ratingIds = collection();
        verify(ratings, times(1)).of(ratingIds.capture());
        ArgumentCaptor<java.util.Collection<UUID>> photoIds = collection();
        verify(photos, times(1)).cardImages(photoIds.capture());
        assertThat(snapshotIds.getValue()).containsExactlyElementsOf(ids);
        assertThat(ratingIds.getValue()).containsExactlyElementsOf(ids);
        assertThat(photoIds.getValue()).containsExactlyElementsOf(ids);
        verifyNoMoreInteractions(venues, areas, snapshots, ratings, photos);
        verifyNoMoreInteractions(resources, prices);
    }

    @Test
    void theCursorIsTheLastVenueAndTheLimitDefaultsToTwenty() {
        UUID after = UUID.randomUUID();
        when(venues.search(any())).thenReturn(new VenueDiscovery.Page(List.of(), false));

        CustomerDiscovery.CardPage page = discovery.list(UUID.randomUUID(), Activity.BILLIARDS, null,
                DiscoveryCursor.encode(after), null);

        ArgumentCaptor<VenueDiscovery.Search> search = ArgumentCaptor.forClass(VenueDiscovery.Search.class);
        verify(venues).search(search.capture());
        assertThat(search.getValue().after()).isEqualTo(after);
        assertThat(search.getValue().limit()).isEqualTo(CustomerDiscovery.DEFAULT_PAGE_SIZE);
        assertThat(page.items()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<java.util.Collection<UUID>> collection() {
        return ArgumentCaptor.forClass(java.util.Collection.class);
    }
}
