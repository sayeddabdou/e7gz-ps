package ps.e7gz.inventory.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aDefaultPrice;
import static ps.e7gz.support.fixtures.Fixtures.aPriceOverride;
import static ps.e7gz.support.fixtures.Fixtures.aResource;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;
import static ps.e7gz.support.fixtures.Fixtures.anArea;
import static ps.e7gz.support.fixtures.Fixtures.anOpeningTemplate;
import static ps.e7gz.support.fixtures.Fixtures.anOperatingSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.inventory.availability.api.NearestAvailabilityProjection;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-34 Discovery API over HTTP (arch 09 section 3.2, ADR-019, FR-DIS-01…FR-DIS-03, FR-DIS-08, FR-DIS-09, FR-DIS-11,
 * PCR-005; PO decisions 7 Oct 2026 on ordering without a location, the area / location rules and visible Resources).
 *
 * <p>The database is shared with other tests, so every case works in its own new area, or around its own random point
 * far from every other test's venues (the fixtures put venues in Cairo).
 */
@AutoConfigureMockMvc
class CustomerDiscoveryHttpTest extends IntegrationTest {

    private static final String VENUES = "/api/v1/customer/venues";
    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    Clock clock;

    /** Test setup only (E7-33 refresh); the discovery request path never calls it. */
    @Autowired
    NearestAvailabilityProjection projection;

    private Fixtures.Area area;
    /** A point in the Southern Ocean, a different one per case. */
    private BigDecimal baseLat;
    private BigDecimal baseLng;

    @BeforeEach
    void ownAreaAndPoint() {
        area = anArea().namedAr("منطقة").create();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        baseLat = BigDecimal.valueOf(random.nextInt(-5_500, -4_500), 2);
        baseLng = BigDecimal.valueOf(random.nextInt(-15_000, 15_000), 2);
    }

    // ------------------------------------------------------------------------------------------ lifecycle

    @Test
    void theListShowsActiveVenuesOnlyAndNeverOnboardingInactiveOrSuspendedOnes() throws Exception {
        Fixtures.Venue active = aVenue().inArea(area).named("A active").active().create();
        aVenue().inArea(area).named("B onboarding").status(MarketplaceStatus.ONBOARDING).create();
        aVenue().inArea(area).named("C inactive").inactive().create();
        aVenue().inArea(area).named("D suspended").suspended().create();

        JsonNode page = list("areaId", area.id().toString(), "activity", "PLAYSTATION");

        assertThat(ids(page)).containsExactly(active.id().toString());
        assertThat(page.path("nextCursor").isNull()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = MarketplaceStatus.class, names = {"ONBOARDING", "INACTIVE", "SUSPENDED"})
    void aNonActiveVenuesDetailsStillLoadWithBookingDisabled(MarketplaceStatus status) throws Exception {
        Fixtures.Venue venue = aVenue().inArea(area).status(status).create();

        details(venue.id(), "activity", "PLAYSTATION").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(venue.id().toString()))
                .andExpect(jsonPath("$.data.bookingEnabled").value(false));
    }

    @Test
    void anActiveVenuesDetailsEnableBooking() throws Exception {
        Fixtures.Venue venue = aVenue().inArea(area).active().create();

        details(venue.id()).andExpect(status().isOk()).andExpect(jsonPath("$.data.bookingEnabled").value(true));
    }

    @Test
    void anUnknownVenueIsTheCanonicalNotFound() throws Exception {
        details(UUID.randomUUID()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------------------------------ filters

    @Test
    void theAreaIsAHardFilter() throws Exception {
        Fixtures.Area other = anArea().create();
        Fixtures.Venue here = aVenue().inArea(area).active().create();
        aVenue().inArea(other).active().create();

        assertThat(ids(list("areaId", area.id().toString(), "activity", "PLAYSTATION")))
                .containsExactly(here.id().toString());
    }

    @Test
    void theAreaStaysAHardFilterWithALocation() throws Exception {
        Fixtures.Area other = anArea().create();
        Fixtures.Venue far = aVenue().inArea(area).active().at(lat(0.5), lng(0)).create();
        aVenue().inArea(other).active().at(lat(0), lng(0)).create();

        JsonNode page = list("areaId", area.id().toString(), "activity", "PLAYSTATION", "lat", lat(0), "lng", lng(0));

        assertThat(ids(page)).containsExactly(far.id().toString());
        assertThat(page.path("items").get(0).path("distanceMeters").asInt()).isGreaterThan(50_000);
    }

    @Test
    void theActivityIsAFilter() throws Exception {
        Fixtures.Venue billiards = aVenue().inArea(area).active().offering(Activity.BILLIARDS).create();
        Fixtures.Venue both = aVenue().inArea(area).active().offering(Activity.PLAYSTATION, Activity.BILLIARDS)
                .create();

        assertThat(ids(list("areaId", area.id().toString(), "activity", "PLAYSTATION")))
                .containsExactly(both.id().toString());
        assertThat(ids(list("areaId", area.id().toString(), "activity", "BILLIARDS")))
                .containsExactlyInAnyOrder(billiards.id().toString(), both.id().toString());
    }

    // ------------------------------------------------------------------------------------------ ordering and paging

    @Test
    void withALocationVenuesComeNearestFirstWithPostgisDistances() throws Exception {
        Fixtures.Venue third = aVenue().active().named("A third").at(lat(0.03), lng(0)).create();
        Fixtures.Venue first = aVenue().active().named("C first").at(lat(0.001), lng(0)).create();
        Fixtures.Venue second = aVenue().active().named("B second").at(lat(0.01), lng(0)).create();

        JsonNode page = list("activity", "PLAYSTATION", "lat", lat(0), "lng", lng(0), "limit", "3");

        assertThat(ids(page)).containsExactly(first.id().toString(), second.id().toString(), third.id().toString());
        List<Integer> distances = items(page).stream().map(item -> item.path("distanceMeters").asInt()).toList();
        assertThat(distances).isSorted();
        // 0.001 degree of latitude is about 111 m on the sphere.
        assertThat(distances.getFirst()).isBetween(105, 117);
        assertThat(distances).containsExactlyElementsOf(sphereDistances(List.of(first, second, third)));
    }

    @Test
    void withoutALocationTheAreaIsOrderedByNameThenIdAndHasNoDistance() throws Exception {
        Fixtures.Venue zed = aVenue().inArea(area).active().named("Zed").create();
        Fixtures.Venue alpha1 = aVenue().inArea(area).active().named("Alpha").create();
        Fixtures.Venue alpha2 = aVenue().inArea(area).active().named("Alpha").create();
        List<String> alphas = sorted(alpha1.id(), alpha2.id());

        JsonNode page = list("areaId", area.id().toString(), "activity", "PLAYSTATION");

        assertThat(ids(page)).containsExactly(alphas.get(0), alphas.get(1), zed.id().toString());
        assertThat(items(page)).allSatisfy(item -> assertThat(item.path("distanceMeters").isNull()).isTrue());
    }

    @Test
    void venuesAtTheSameDistanceAreOrderedById() throws Exception {
        Fixtures.Venue one = aVenue().active().at(lat(0.002), lng(0)).create();
        Fixtures.Venue two = aVenue().active().at(lat(0.002), lng(0)).create();

        JsonNode page = list("activity", "PLAYSTATION", "lat", lat(0), "lng", lng(0), "limit", "2");

        assertThat(ids(page)).containsExactlyElementsOf(sorted(one.id(), two.id()));
    }

    @Test
    void tiesAcrossAPageBoundaryStayInIdOrder() throws Exception {
        String nearest = aVenue().active().at(lat(0.001), lng(0)).create().id().toString();
        List<String> tied = sorted(aVenue().active().at(lat(0.003), lng(0)).create().id(),
                aVenue().active().at(lat(0.003), lng(0)).create().id(),
                aVenue().active().at(lat(0.003), lng(0)).create().id(),
                aVenue().active().at(lat(0.003), lng(0)).create().id());

        JsonNode first = list("activity", "PLAYSTATION", "lat", lat(0), "lng", lng(0), "limit", "2");
        JsonNode second = list("activity", "PLAYSTATION", "lat", lat(0), "lng", lng(0), "limit", "2",
                "cursor", first.path("nextCursor").asText());
        JsonNode third = list("activity", "PLAYSTATION", "lat", lat(0), "lng", lng(0), "limit", "1",
                "cursor", second.path("nextCursor").asText());

        assertThat(ids(first)).containsExactly(nearest, tied.get(0));
        assertThat(ids(second)).containsExactly(tied.get(1), tied.get(2));
        assertThat(ids(third)).containsExactly(tied.get(3));
    }

    @Test
    void locationCursorPagesFollowDistanceThenIdWithoutDuplicates() throws Exception {
        List<String> expected = new ArrayList<>();
        Fixtures.Venue tieA = aVenue().active().at(lat(0.004), lng(0)).create();
        Fixtures.Venue tieB = aVenue().active().at(lat(0.004), lng(0)).create();
        expected.add(aVenue().active().at(lat(0.001), lng(0)).create().id().toString());
        expected.add(aVenue().active().at(lat(0.002), lng(0)).create().id().toString());
        expected.addAll(sorted(tieA.id(), tieB.id()));
        expected.add(aVenue().active().at(lat(0.008), lng(0)).create().id().toString());

        List<String> seen = new ArrayList<>();
        JsonNode first = list("activity", "PLAYSTATION", "lat", lat(0), "lng", lng(0), "limit", "2");
        seen.addAll(ids(first));
        JsonNode second = list("activity", "PLAYSTATION", "lat", lat(0), "lng", lng(0), "limit", "2",
                "cursor", first.path("nextCursor").asText());
        seen.addAll(ids(second));
        JsonNode third = list("activity", "PLAYSTATION", "lat", lat(0), "lng", lng(0), "limit", "1",
                "cursor", second.path("nextCursor").asText());
        seen.addAll(ids(third));

        assertThat(seen).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }

    @Test
    void areaCursorPagesFollowNameThenIdToTheLastPage() throws Exception {
        List<String> expected = new ArrayList<>();
        Fixtures.Venue b1 = aVenue().inArea(area).active().named("Bravo").create();
        Fixtures.Venue b2 = aVenue().inArea(area).active().named("Bravo").create();
        expected.add(aVenue().inArea(area).active().named("Alpha").create().id().toString());
        expected.addAll(sorted(b1.id(), b2.id()));
        expected.add(aVenue().inArea(area).active().named("Charlie").create().id().toString());
        expected.add(aVenue().inArea(area).active().named("Delta").create().id().toString());

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = cursor == null
                    ? list("areaId", area.id().toString(), "activity", "PLAYSTATION", "limit", "2")
                    : list("areaId", area.id().toString(), "activity", "PLAYSTATION", "limit", "2", "cursor", cursor);
            seen.addAll(ids(page));
            cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyElementsOf(expected).doesNotHaveDuplicates();
    }

    @Test
    void aMalformedOrUnknownCursorIsValidationFailedOnCursor() throws Exception {
        for (String cursor : new String[] {"not a cursor!", "AAAA", "eyJvZmZzZXQiOjIwfQ"}) {
            call(VENUES, "areaId", area.id().toString(), "activity", "PLAYSTATION", "cursor", cursor)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.field").value("cursor"));
        }
        // Well-formed, but no venue has that id.
        call(VENUES, "areaId", area.id().toString(), "activity", "PLAYSTATION", "cursor", "AAAAAAAAAAAAAAAAAAAAAA")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("cursor"));
    }

    // ------------------------------------------------------------------------------------------ position rules

    @Test
    void neitherAreaNorLocationIsValidationFailedOnAreaId() throws Exception {
        call(VENUES, "activity", "PLAYSTATION").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("areaId"));
    }

    @Test
    void halfALocationIsValidationFailedOnTheMissingCoordinate() throws Exception {
        call(VENUES, "areaId", area.id().toString(), "activity", "PLAYSTATION", "lat", "30.05")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("lng"));
        call(VENUES, "areaId", area.id().toString(), "activity", "PLAYSTATION", "lng", "31.2")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("lat"));
        Fixtures.Venue venue = aVenue().inArea(area).active().create();
        call(VENUES + "/" + venue.id(), "lat", "30.05").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("lng"));
        call(VENUES + "/" + venue.id(), "lng", "31.2").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("lat"));
    }

    @Test
    void aCoordinateOutOfRangeIsValidationFailedOnIt() throws Exception {
        call(VENUES, "activity", "PLAYSTATION", "lat", "90.5", "lng", "31.2")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("lat"));
        call(VENUES, "activity", "PLAYSTATION", "lat", "30", "lng", "-180.5")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.field").value("lng"));
    }

    // ------------------------------------------------------------------------------------------ projection

    @Test
    void startingPriceAndNearestAvailabilityComeFromTheProjection() throws Exception {
        Fixtures.Venue projected = aVenue().inArea(area).active().named("A projected").create();
        Fixtures.Venue bare = aVenue().inArea(area).active().named("B bare").create();
        Instant nearest = Instant.parse("2031-03-04T18:15:00Z");
        project(projected.id(), Activity.PLAYSTATION, nearest, "85.50");

        List<JsonNode> cards = items(list("areaId", area.id().toString(), "activity", "PLAYSTATION"));

        assertThat(cards.get(0).path("nearestAvailableAt").asText()).isEqualTo("2031-03-04T18:15:00Z");
        assertThat(cards.get(0).path("startingPrice").path("amount").asText()).isEqualTo("85.50");
        assertThat(cards.get(0).path("startingPrice").path("currency").asText()).isEqualTo("EGP");
        assertThat(cards.get(1).path("id").asText()).isEqualTo(bare.id().toString());
        assertThat(cards.get(1).path("nearestAvailableAt").isNull()).isTrue();
        assertThat(cards.get(1).path("startingPrice").isNull()).isTrue();
        details(projected.id(), "activity", "PLAYSTATION").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nearestAvailableAt").value("2031-03-04T18:15:00Z"));
    }

    @Test
    void theProjectionRowOfAnotherActivityIsNotUsed() throws Exception {
        Fixtures.Venue venue = aVenue().inArea(area).active().offering(Activity.PLAYSTATION, Activity.BILLIARDS)
                .create();
        project(venue.id(), Activity.BILLIARDS, Instant.parse("2031-03-04T18:15:00Z"), "40.00");

        JsonNode card = items(list("areaId", area.id().toString(), "activity", "PLAYSTATION")).getFirst();

        assertThat(card.path("startingPrice").isNull()).isTrue();
        assertThat(card.path("nearestAvailableAt").isNull()).isTrue();
    }

    @Test
    void aDiscoveryReadNeverRefreshesTheProjection() throws Exception {
        Fixtures.Venue venue = aVenue().inArea(area).active().create();
        aResource().at(venue).playStation(ConsoleType.PS5).create();
        project(venue.id(), Activity.PLAYSTATION, Instant.parse("2031-03-04T18:15:00Z"), "85.50");
        OffsetDateTime refreshedAt = refreshedAt(venue.id());

        list("areaId", area.id().toString(), "activity", "PLAYSTATION");
        details(venue.id(), "activity", "PLAYSTATION").andExpect(status().isOk());

        assertThat(refreshedAt(venue.id())).isEqualTo(refreshedAt);
    }

    // ------------------------------------------------------------------------------------------ photos

    @Test
    void theCardShowsTheFirstVisibleSmallCopyAndDetailsTheMediumCopiesInOrder() throws Exception {
        Fixtures.Venue venue = aVenue().inArea(area).active().create();
        UUID removed = photo(venue.id(), 0, true, true);
        UUID unprocessed = photo(venue.id(), 1, false, false);
        UUID second = photo(venue.id(), 3, true, false);
        UUID first = photo(venue.id(), 2, true, false);

        JsonNode card = items(list("areaId", area.id().toString(), "activity", "PLAYSTATION")).getFirst();
        String image = card.path("imageUrl").asText();
        assertThat(image).contains("/venues/" + venue.id() + "/" + first + "-small.jpg").contains("X-Amz-Expires=3600");

        JsonNode detail = body(details(venue.id()).andExpect(status().isOk()));
        List<String> urls = StreamSupport.stream(detail.path("data").path("imageUrls").spliterator(), false)
                .map(JsonNode::asText).toList();
        assertThat(urls).hasSize(2);
        assertThat(urls.get(0)).contains(first + "-medium.jpg").contains("X-Amz-Expires=3600");
        assertThat(urls.get(1)).contains(second + "-medium.jpg");

        String everything = card.toString() + detail;
        assertThat(everything).doesNotContain(removed.toString()).doesNotContain(unprocessed.toString())
                .doesNotContain(first + ".jpg").doesNotContain(second + ".jpg");
    }

    @Test
    void aVenueWithoutPhotosHasANullCardImageAndNoDetailImages() throws Exception {
        Fixtures.Venue venue = aVenue().inArea(area).active().create();

        JsonNode card = items(list("areaId", area.id().toString(), "activity", "PLAYSTATION")).getFirst();

        assertThat(card.has("imageUrl")).isTrue();
        assertThat(card.path("imageUrl").isNull()).isTrue();
        details(venue.id()).andExpect(jsonPath("$.data.imageUrls").isArray())
                .andExpect(jsonPath("$.data.imageUrls").isEmpty());
    }

    // ------------------------------------------------------------------------------------------ ratings

    @Test
    void ratingsComeFromTheVenueRatingStatistics() throws Exception {
        Fixtures.Venue rated = aVenue().inArea(area).active().named("A rated").create();
        Fixtures.Venue unrated = aVenue().inArea(area).active().named("B unrated").create();
        review(rated.id(), 5);
        review(rated.id(), 4);
        review(rated.id(), 4);

        List<JsonNode> cards = items(list("areaId", area.id().toString(), "activity", "PLAYSTATION"));

        assertThat(cards.get(0).path("averageRating").decimalValue()).isEqualByComparingTo("4.33");
        assertThat(cards.get(0).path("reviewCount").asInt()).isEqualTo(3);
        assertThat(cards.get(1).path("averageRating").isNull()).isTrue();
        assertThat(cards.get(1).path("reviewCount").asInt()).isZero();
        details(rated.id()).andExpect(jsonPath("$.data.averageRating").value(4.33))
                .andExpect(jsonPath("$.data.reviewCount").value(3));
        details(unrated.id()).andExpect(jsonPath("$.data.averageRating").isEmpty())
                .andExpect(jsonPath("$.data.reviewCount").value(0));
    }

    // ------------------------------------------------------------------------------------------ details

    @Test
    void detailsShowTheVenueAreaAddressLocationAndCustomerDistance() throws Exception {
        Fixtures.Area named = anArea().named("Zamalek " + UUID.randomUUID()).namedAr("الزمالك").create();
        Fixtures.Venue venue = aVenue().inArea(named).active().named("Level Up").address("1 Nile St")
                .at("30.0609", "31.2197").offering(Activity.BILLIARDS, Activity.PLAYSTATION).create();

        details(venue.id(), "lat", "30.0609", "lng", "31.2297").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Level Up"))
                .andExpect(jsonPath("$.data.address").value("1 Nile St"))
                .andExpect(jsonPath("$.data.area.id").value(named.id().toString()))
                .andExpect(jsonPath("$.data.area.name").value(named.name()))
                .andExpect(jsonPath("$.data.area.nameAr").value("الزمالك"))
                .andExpect(jsonPath("$.data.location.lat").value(30.0609))
                .andExpect(jsonPath("$.data.location.lng").value(31.2197))
                .andExpect(jsonPath("$.data.distanceMeters").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.greaterThan(900), org.hamcrest.Matchers.lessThan(1_000))))
                .andExpect(jsonPath("$.data.activities[0]").value("PLAYSTATION"))
                .andExpect(jsonPath("$.data.activities[1]").value("BILLIARDS"))
                .andExpect(jsonPath("$.data.selectedActivity").isEmpty())
                .andExpect(jsonPath("$.data.resources").isEmpty())
                .andExpect(jsonPath("$.data.prices").isEmpty());
        details(venue.id()).andExpect(jsonPath("$.data.distanceMeters").isEmpty());
    }

    @Test
    void detailsListTheSelectedActivitysVisibleResourcesAndDefaultPrices() throws Exception {
        Fixtures.Venue venue = aVenue().inArea(area).suspended().create();
        Fixtures.Resource room1 = aResource().at(venue).playStation(ConsoleType.PS5).named("Room 1").create();
        Fixtures.Resource room2 = aResource().at(venue).playStation(ConsoleType.PS4).named("Room 2")
                .ownerReviewRequired().create();
        Fixtures.Resource disabled = aResource().at(venue).playStation(ConsoleType.PS4).named("Room 3").disabled()
                .create();
        Fixtures.Resource archived = aResource().at(venue).playStation(ConsoleType.PS4).named("Room 4").archived()
                .create();
        // A temporary selling block stops booking the room; it does not hide it (PO decision 7 Oct 2026).
        Fixtures.Resource blocked = aResource().at(venue).playStation(ConsoleType.PS5).named("Room 5").create();
        resourceSellingBlock(blocked);
        aDefaultPrice().forVenue(venue).playStation(ConsoleType.PS5, PlayMode.SINGLE).hourly("90.00").create();
        aDefaultPrice().forVenue(venue).playStation(ConsoleType.PS4, PlayMode.MULTIPLAYER).hourly("70.00").create();

        JsonNode data = body(details(venue.id(), "activity", "PLAYSTATION").andExpect(status().isOk())).path("data");

        assertThat(data.path("bookingEnabled").asBoolean()).isFalse();
        assertThat(data.path("selectedActivity").asText()).isEqualTo("PLAYSTATION");
        assertThat(data.path("resources").toString()).doesNotContain(disabled.id().toString())
                .doesNotContain(archived.id().toString());
        assertThat(data.path("resources")).hasSize(3);
        assertThat(data.path("resources").get(0).path("id").asText()).isEqualTo(room1.id().toString());
        assertThat(data.path("resources").get(0).path("name").asText()).isEqualTo("Room 1");
        assertThat(data.path("resources").get(0).path("consoleType").asText()).isEqualTo("PS5");
        assertThat(data.path("resources").get(1).path("id").asText()).isEqualTo(room2.id().toString());
        assertThat(data.path("resources").get(2).path("id").asText()).as("selling-blocked, still shown")
                .isEqualTo(blocked.id().toString());
        assertThat(data.path("prices")).hasSize(2);
        assertThat(data.path("prices").get(0).path("consoleType").asText()).isEqualTo("PS4");
        assertThat(data.path("prices").get(0).path("playMode").asText()).isEqualTo("MULTIPLAYER");
        assertThat(data.path("prices").get(0).path("hourlyPrice").path("amount").asText()).isEqualTo("70.00");
        assertThat(data.path("prices").get(1).path("hourlyPrice").path("amount").asText()).isEqualTo("90.00");
    }

    @Test
    void detailsWithoutAnActivityStillLoadWithNothingSelected() throws Exception {
        Fixtures.Venue venue = aVenue().inArea(area).active().named("No activity").create();
        aResource().at(venue).playStation(ConsoleType.PS5).create();
        aDefaultPrice().forVenue(venue).playStation(ConsoleType.PS5, PlayMode.SINGLE).hourly("90.00").create();
        project(venue.id(), Activity.PLAYSTATION, Instant.parse("2031-03-04T18:15:00Z"), "90.00");
        photo(venue.id(), 0, true, false);
        review(venue.id(), 5);

        expectNothingSelected(details(venue.id()), venue);
    }

    @Test
    void detailsForAnActivityTheVenueDoesNotOfferStillLoadWithNothingSelected() throws Exception {
        Fixtures.Venue venue = aVenue().inArea(area).suspended().named("Billiards only").offering(Activity.BILLIARDS)
                .create();
        aResource().at(venue).billiards().create();
        aDefaultPrice().forVenue(venue).billiards().hourly("40.00").create();
        project(venue.id(), Activity.BILLIARDS, Instant.parse("2031-03-04T18:15:00Z"), "40.00");
        photo(venue.id(), 0, true, false);
        review(venue.id(), 5);

        expectNothingSelected(details(venue.id(), "activity", "PLAYSTATION"), venue);
    }

    private void expectNothingSelected(ResultActions result, Fixtures.Venue venue) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(venue.id().toString()))
                .andExpect(jsonPath("$.data.name").value(venue.name()))
                .andExpect(jsonPath("$.data.selectedActivity").isEmpty())
                .andExpect(jsonPath("$.data.resources").isArray()).andExpect(jsonPath("$.data.resources").isEmpty())
                .andExpect(jsonPath("$.data.prices").isArray()).andExpect(jsonPath("$.data.prices").isEmpty())
                .andExpect(jsonPath("$.data.nearestAvailableAt").isEmpty())
                .andExpect(jsonPath("$.data.imageUrls.length()").value(1))
                .andExpect(jsonPath("$.data.openingHours.length()").value(7))
                .andExpect(jsonPath("$.data.averageRating").value(5.0))
                .andExpect(jsonPath("$.data.reviewCount").value(1))
                .andExpect(jsonPath("$.data.area.id").value(area.id().toString()));
        String bookable = OWNER.sql("SELECT marketplace_status FROM venue.venue WHERE id = :id")
                .param("id", venue.id()).query(String.class).single();
        result.andExpect(jsonPath("$.data.bookingEnabled").value("ACTIVE".equals(bookable)));
    }

    @Test
    void detailPricesAreTheDefaultsWhileARoomOverrideLowersTheProjectedStartingPrice() throws Exception {
        Fixtures.Venue venue = aVenue().inArea(area).active().create();
        Fixtures.Resource room = aResource().at(venue).playStation(ConsoleType.PS5).named("Room 1").create();
        aDefaultPrice().forVenue(venue).playStation(ConsoleType.PS5, PlayMode.SINGLE).hourly("90.00").create();
        aDefaultPrice().forVenue(venue).playStation(ConsoleType.PS5, PlayMode.MULTIPLAYER).hourly("120.00").create();
        aPriceOverride().forRoom(room).playMode(PlayMode.SINGLE).hourly("60.00").create();
        // Test setup: E7-33 computes the row as its refresh would; the discovery request itself never refreshes.
        projection.refresh(venue.id(), Activity.PLAYSTATION);

        JsonNode card = items(list("areaId", area.id().toString(), "activity", "PLAYSTATION")).getFirst();
        JsonNode data = body(details(venue.id(), "activity", "PLAYSTATION").andExpect(status().isOk())).path("data");

        assertThat(card.path("startingPrice").path("amount").asText()).as("the override is the effective from-price")
                .isEqualTo("60.00");
        assertThat(data.path("prices")).hasSize(2);
        assertThat(data.path("prices").get(0).path("playMode").asText()).isEqualTo("MULTIPLAYER");
        assertThat(data.path("prices").get(0).path("hourlyPrice").path("amount").asText()).isEqualTo("120.00");
        assertThat(data.path("prices").get(1).path("playMode").asText()).isEqualTo("SINGLE");
        assertThat(data.path("prices").get(1).path("hourlyPrice").path("amount").asText())
                .as("the default stays the default").isEqualTo("90.00");
        assertThat(data.path("prices").toString()).doesNotContain("60.00");
    }

    @Test
    void openingHoursAndOpenNowComeFromTheVenuesHoursAndSessions() throws Exception {
        Fixtures.Venue open = aVenue().inArea(area).active().create();
        Fixtures.Venue closed = aVenue().inArea(area).active().create();
        Fixtures.Venue neverSaved = aVenue().inArea(area).active().create();
        ZoneId cairo = ZoneId.of("Africa/Cairo");
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), cairo).truncatedTo(ChronoUnit.MINUTES);
        anOpeningTemplate().forVenue(open).hours("12:00", "02:00").create();
        anOperatingSession().forVenue(open).on(now.minusHours(1).toLocalDate().toString(),
                now.minusHours(1).toString(), now.plusHours(1).toString()).create();
        LocalDateTime later = now.plusHours(3);
        anOperatingSession().forVenue(closed).on(later.toLocalDate().toString(), later.toString(),
                later.plusHours(2).toString()).create();

        details(open.id()).andExpect(jsonPath("$.data.openNow").value(true))
                .andExpect(jsonPath("$.data.opensAt").isEmpty())
                .andExpect(jsonPath("$.data.openingHours.length()").value(7))
                .andExpect(jsonPath("$.data.openingHours[0].weekday").value(0))
                .andExpect(jsonPath("$.data.openingHours[0].closed").value(false))
                .andExpect(jsonPath("$.data.openingHours[0].opensAtLocal").value("12:00:00"))
                .andExpect(jsonPath("$.data.openingHours[0].closesAtLocal").value("02:00:00"));
        details(closed.id()).andExpect(jsonPath("$.data.openNow").value(false))
                .andExpect(jsonPath("$.data.opensAt").value(later.atZone(cairo).toInstant().toString()));
        details(neverSaved.id()).andExpect(jsonPath("$.data.openNow").value(false))
                .andExpect(jsonPath("$.data.opensAt").isEmpty())
                .andExpect(jsonPath("$.data.openingHours[6].closed").value(true))
                .andExpect(jsonPath("$.data.openingHours[6].opensAtLocal").isEmpty());
    }

    // ------------------------------------------------------------------------------------------ privacy

    @Test
    void noCustomerResponseCarriesThePhoneOrOtherPrivateFields() throws Exception {
        Fixtures.Venue venue = aVenue().inArea(area).active().phone("+20229998877").create();
        photo(venue.id(), 0, true, false);
        aResource().at(venue).playStation(ConsoleType.PS5).create();

        String card = items(list("areaId", area.id().toString(), "activity", "PLAYSTATION")).getFirst().toString();
        String detail = body(details(venue.id(), "activity", "PLAYSTATION")).path("data").toString();

        for (String json : List.of(card, detail)) {
            assertThat(json).doesNotContain("+20229998877").doesNotContain("29998877")
                    .doesNotContainIgnoringCase("\"phone").doesNotContain("marketplaceStatus")
                    .doesNotContain("timezone").doesNotContain("objectKey").doesNotContain("baseState")
                    .doesNotContain("operationalRestriction").doesNotContain("refreshedAt")
                    .doesNotContain("venueAccount").doesNotContain("loginIdentifier");
        }
    }

    // ------------------------------------------------------------------------------------------ helpers

    private String lat(double offset) {
        return baseLat.add(BigDecimal.valueOf((long) Math.round(offset * 10_000), 4)).toPlainString();
    }

    private String lng(double offset) {
        return baseLng.add(BigDecimal.valueOf((long) Math.round(offset * 10_000), 4)).toPlainString();
    }

    /** {@code location <-> point} computed by PostGIS for each venue, rounded as the API rounds it. */
    private List<Integer> sphereDistances(List<Fixtures.Venue> venues) {
        return venues.stream().map(venue -> OWNER.sql("""
                        SELECT round(v.location <-> ST_SetSRID(ST_MakePoint(CAST(:lng AS double precision),
                                     CAST(:lat AS double precision)), 4326)::geography)::integer
                          FROM venue.venue v WHERE v.id = :id""")
                .param("id", venue.id()).param("lat", new BigDecimal(lat(0))).param("lng", new BigDecimal(lng(0)))
                .query(Integer.class).single()).toList();
    }

    /**
     * PostgreSQL's uuid order: unsigned, byte by byte - the order of the lower-case text form. ({@link UUID#compareTo}
     * compares signed longs, which differs when the first hex digit is 8 or more.)
     */
    private static List<String> sorted(UUID... ids) {
        return java.util.Arrays.stream(ids).map(UUID::toString).sorted(Comparator.naturalOrder()).toList();
    }

    private void project(UUID venueId, Activity activity, Instant nearest, String price) {
        OWNER.sql("""
                        INSERT INTO inventory.venue_activity_availability (venue_id, activity, nearest_available_at,
                                                                           starting_price, refreshed_at)
                        VALUES (:venue, :activity, :nearest, :price, now())""")
                .param("venue", venueId).param("activity", activity.name())
                .param("nearest", OffsetDateTime.ofInstant(nearest, ZoneOffset.UTC))
                .param("price", new BigDecimal(price).setScale(2, RoundingMode.UNNECESSARY)).update();
    }

    /** An active selling block on one Resource ({@code resource.resource_selling_block}, FR-OPS-15). */
    private static void resourceSellingBlock(Fixtures.Resource room) {
        OWNER.sql("""
                        INSERT INTO resource.resource_selling_block (id, resource_id, cause, active, started_at)
                        VALUES (:id, :room, 'E7-34 test block', true, now())""")
                .param("id", UUID.randomUUID()).param("room", room.id()).update();
    }

    private OffsetDateTime refreshedAt(UUID venueId) {
        return OWNER.sql("SELECT refreshed_at FROM inventory.venue_activity_availability WHERE venue_id = :venue")
                .param("venue", venueId).query(OffsetDateTime.class).single();
    }

    /** A photo row; its copies exist when {@code processed}. No file is needed: links are signed, not fetched. */
    private UUID photo(UUID venueId, int position, boolean processed, boolean removed) {
        UUID id = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO venue.venue_image (id, venue_id, object_key, content_type, sort_order, uploaded_at,
                                                       processed_at, removed_at)
                        VALUES (:id, :venue, :key, 'image/jpeg', :position, now(),
                                CASE WHEN :processed THEN now() END, CASE WHEN :removed THEN now() END)""")
                .param("id", id).param("venue", venueId).param("key", "venues/" + venueId + "/" + id + ".jpg")
                .param("position", position).param("processed", processed).param("removed", removed).update();
        return id;
    }

    /** Through the review producer, which also refreshes {@code review.venue_rating_stats}. */
    private void review(UUID venueId, int rating) {
        Fixtures.User user = aUser().verified().create();
        OWNER.sql("SELECT review.create_review(:user, :venue, CAST(:rating AS smallint), 'Great place', gen_random_uuid())")
                .param("user", user.id()).param("venue", venueId).param("rating", rating).query(UUID.class).single();
    }

    private JsonNode list(String... params) throws Exception {
        return body(call(VENUES, params).andExpect(status().isOk())).path("data");
    }

    private ResultActions details(UUID venueId, String... params) throws Exception {
        return call(VENUES + "/" + venueId, params);
    }

    private ResultActions call(String path, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(path);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static List<JsonNode> items(JsonNode page) {
        return StreamSupport.stream(page.path("items").spliterator(), false).toList();
    }

    private static List<String> ids(JsonNode page) {
        return items(page).stream().map(item -> item.path("id").asText()).toList();
    }
}
