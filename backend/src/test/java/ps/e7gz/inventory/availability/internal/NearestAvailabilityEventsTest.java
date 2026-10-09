package ps.e7gz.inventory.availability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aResource;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;
import static ps.e7gz.support.fixtures.Fixtures.anAdmin;
import static ps.e7gz.support.fixtures.Fixtures.anArea;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.geography.internal.Areas;
import ps.e7gz.inventory.availability.api.NearestAvailabilityProjection;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.internal.OutboxDrain;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.venue.api.VenueActivities;
import ps.e7gz.venue.internal.MarketplaceAction;
import ps.e7gz.venue.internal.Venues;

/**
 * E7-41 carry-forwards from E7-23 and the Sprint 1 review (PO decisions 8 and 9 Oct 2026): the admin's venue status
 * change and area deactivation write their integration events in the same command, and once dispatched they refresh
 * the Nearest Availability rows they touch - without waiting for the one-minute pass, which nothing here runs.
 */
class NearestAvailabilityEventsTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));
    private static final Instant STALE = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired
    Venues venues;

    @Autowired
    Areas areas;

    @Autowired
    NearestAvailabilityProjection projection;

    @Autowired
    VenueActivities venueActivities;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    @Test
    void venueMarketplaceStatusChangedRefreshesThePairsOfItsVenue() throws Exception {
        UUID admin = anAdmin().create().id();
        Fixtures.Venue venue = aVenue().offering(Activity.PLAYSTATION, Activity.BILLIARDS).create();
        aResource().at(venue).create();
        Fixtures.Venue other = aVenue().offering(Activity.PLAYSTATION).create();
        aResource().at(other).create();
        stale(venue.id());
        stale(other.id());

        try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
            venues.changeStatus(admin, venue.id(), MarketplaceAction.COMPLETE_ONBOARDING, "ready to go");
        }

        Map<String, Object> event = event(IntegrationEvents.VENUE_MARKETPLACE_STATUS_CHANGED, venue.id());
        assertThat(event.get("aggregate_type")).isEqualTo("VENUE");
        assertThat(json.readTree((String) event.get("payload"))).isEqualTo(json.readTree("""
                {"venueId": "%s", "previousStatus": "ONBOARDING", "newStatus": "INACTIVE"}""".formatted(venue.id())));
        assertThat(refreshedAt(venue.id())).as("nothing before the event is dispatched").isEqualTo(STALE);

        deliver();

        assertThat(refreshedAt(venue.id())).isAfter(STALE);
        assertThat(OWNER.sql("SELECT count(*) FROM inventory.venue_activity_availability WHERE venue_id = :venue")
                .param("venue", venue.id()).query(Integer.class).single())
                .as("BILLIARDS has no Resource, so no row (PO decision 7 Oct 2026)").isOne();
        assertThat(refreshedAt(other.id())).as("another venue is not touched").isEqualTo(STALE);
    }

    @Test
    void areaDeactivatedRefreshesThePairsOfEveryVenueOfTheArea() throws Exception {
        UUID admin = anAdmin().create().id();
        Fixtures.Area area = anArea().create();
        Fixtures.Venue first = aVenue().inArea(area).offering(Activity.PLAYSTATION).create();
        aResource().at(first).create();
        Fixtures.Venue second = aVenue().inArea(area).inactive().offering(Activity.PLAYSTATION).create();
        aResource().at(second).create();
        stale(first.id());
        stale(second.id());

        try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
            areas.setActive(admin, area.id(), false);
        }

        Map<String, Object> event = event(IntegrationEvents.AREA_DEACTIVATED, area.id());
        assertThat(event.get("aggregate_type")).isEqualTo("AREA");
        assertThat(json.readTree((String) event.get("payload")))
                .isEqualTo(json.readTree("{\"areaId\": \"%s\"}".formatted(area.id())));

        deliver();

        assertThat(refreshedAt(first.id())).isAfter(STALE);
        assertThat(refreshedAt(second.id())).isAfter(STALE);
    }

    @Test
    void activatingAnAreaWritesNoEvent() {
        UUID admin = anAdmin().create().id();
        Fixtures.Area area = anArea().inactive().create();

        try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
            areas.setActive(admin, area.id(), true);
        }

        assertThat(OWNER.sql("SELECT count(*) FROM technical.outbox_event WHERE aggregate_id = :area")
                .param("area", area.id()).query(Integer.class).single()).isZero();
    }

    @Test
    void theConsumerTakesEveryInventoryAffectingEventOfTheWorkerProfile() {
        assertThat(NearestAvailabilityEvents.class.getAnnotation(Profile.class).value()).containsExactly("worker");
        assertThat(new NearestAvailabilityEvents(projection, venueActivities).eventTypes()).containsExactlyInAnyOrder(
                "ReservationHeld", "ReservationReleased",
                "ResourceDisabled", "ResourceReEnabled", "ResourceArchived", "ResourceRestored",
                "ResourceConsoleChanged", "ResourceRestrictionChanged",
                "MaintenanceCreated", "MaintenanceEdited", "MaintenanceDeleted", "ClosureCreated",
                "EmergencyDeclared", "EmergencyExtended", "EmergencyEnded", "EmergencyConverted",
                "OpeningHoursChanged", "VenuePricingChanged", "RoomOverrideChanged",
                "VenueMarketplaceStatusChanged", "AreaDeactivated");
    }

    /** What the worker's dispatcher does right after the commit. */
    private void deliver() {
        OutboxDrain.drain(appDataSource, json, new NearestAvailabilityEvents(projection, venueActivities));
    }

    /** A row refreshed long ago, as if nothing had refreshed it since. */
    private static void stale(UUID venueId) {
        OWNER.sql("""
                        INSERT INTO inventory.venue_activity_availability (venue_id, activity, nearest_available_at,
                                                                           refreshed_at)
                        VALUES (:venue, 'PLAYSTATION', '2020-01-01 10:00:00+00', :stale)""")
                .param("venue", venueId).param("stale", OffsetDateTime.parse("2020-01-01T00:00:00Z")).update();
    }

    private static Instant refreshedAt(UUID venueId) {
        return OWNER.sql("""
                        SELECT refreshed_at FROM inventory.venue_activity_availability
                         WHERE venue_id = :venue AND activity = 'PLAYSTATION'""")
                .param("venue", venueId).query(OffsetDateTime.class).single().toInstant();
    }

    private static Map<String, Object> event(String type, UUID aggregateId) {
        List<Map<String, Object>> rows = OWNER.sql("""
                        SELECT aggregate_type, payload::text AS payload FROM technical.outbox_event
                         WHERE event_type = :type AND aggregate_id = :aggregate""")
                .param("type", type).param("aggregate", aggregateId).query().listOfRows();
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }
}
