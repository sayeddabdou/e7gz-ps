package ps.e7gz.inventory.availability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.money.Money;
import ps.e7gz.inventory.availability.api.NearestAvailabilityProjection;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.internal.OutboxDrain;
import ps.e7gz.pricing.internal.Pricing;
import ps.e7gz.resource.internal.Resources;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.venue.api.VenueActivities;

/**
 * E7-56 (Sprint 2 review, PO decision 12 Oct 2026): the room state and price commands of E7-26 and E7-27 write their
 * inventory-affecting events in the same command, and once dispatched each refreshes its venue's Nearest Availability
 * rows - without waiting for the one-minute pass, which nothing here runs (as E7-41's test does).
 */
class NearestAvailabilityRoomAndPriceEventsTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));
    private static final Instant STALE = Instant.parse("2020-01-01T00:00:00Z");
    private static final String PIN = "1234";

    @Autowired
    Resources resources;

    @Autowired
    Pricing pricing;

    @Autowired
    NearestAvailabilityProjection projection;

    @Autowired
    VenueActivities venueActivities;

    @Autowired
    DataSource appDataSource;

    @Autowired
    ObjectMapper json;

    @Autowired
    PasswordEncoder passwords;

    UUID venue;
    UUID account;
    UUID room;
    CorrelationId.Scope correlation;

    @BeforeEach
    void aVenueWithOnePlayStationRoom() {
        correlation = CorrelationId.open(CorrelationId.generate());
        venue = UUID.randomUUID();
        account = UUID.randomUUID();
        UUID area = UUID.randomUUID();
        OWNER.sql("INSERT INTO geo.area (id, name, is_active) VALUES (:id, :name, true)")
                .param("id", area).param("name", "E7-56 area " + area).update();
        OWNER.sql("""
                        INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone,
                                                 phone)
                        VALUES (:id, :area, :name, '12 Test St', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ACTIVE',
                                'Africa/Cairo', '+20223456789')""")
                .param("id", venue).param("area", area).param("name", "E7-56 venue " + venue).update();
        OWNER.sql("INSERT INTO venue.venue_activity (id, venue_id, activity) VALUES (:id, :venue, 'PLAYSTATION')")
                .param("id", UUID.randomUUID()).param("venue", venue).update();
        OWNER.sql("""
                        INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status,
                                                       language_preference)
                        VALUES (:id, :venue, :login, 'not-used-here', 'ENABLED', 'en')""")
                .param("id", account).param("venue", venue).param("login", "e7-56-" + account).update();
        OWNER.sql("""
                        INSERT INTO iam.venue_owner_secret (venue_account_id, owner_password_hash, changed_at, version)
                        VALUES (:account, :hash, now() - interval '1 day', 0)""")
                .param("account", account).param("hash", passwords.encode(PIN)).update();
        room = resources.createForVenue(account, Activity.PLAYSTATION, ConsoleType.PS5, PIN).id();
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @Test
    void eachRoomStateChangeRefreshesItsVenueAtOnce() throws Exception {
        refreshes(IntegrationEvents.RESOURCE_DISABLED, "RESOURCE",
                () -> resources.changeState(account, room, Resources.StateChange.DISABLE, PIN));
        refreshes(IntegrationEvents.RESOURCE_RE_ENABLED, "RESOURCE",
                () -> resources.changeState(account, room, Resources.StateChange.RE_ENABLE, PIN));
        refreshes(IntegrationEvents.RESOURCE_ARCHIVED, "RESOURCE",
                () -> resources.changeState(account, room, Resources.StateChange.ARCHIVE, PIN));
        refreshes(IntegrationEvents.RESOURCE_RESTORED, "RESOURCE",
                () -> resources.changeState(account, room, Resources.StateChange.RESTORE, PIN));
        refreshes(IntegrationEvents.RESOURCE_CONSOLE_CHANGED, "RESOURCE",
                () -> resources.changeConsole(account, room, ConsoleType.PS4, PIN));
    }

    @Test
    void eachPriceChangeRefreshesItsVenueAtOnce() throws Exception {
        refreshes(IntegrationEvents.VENUE_PRICING_CHANGED, "VENUE", () -> pricing.setDefaults(account, List.of(
                new Pricing.DefaultInput(Activity.PLAYSTATION, ConsoleType.PS5, PlayMode.SINGLE, egp("120.00")),
                new Pricing.DefaultInput(Activity.PLAYSTATION, ConsoleType.PS5, PlayMode.MULTIPLAYER, egp("160.00"))),
                PIN));
        refreshes(IntegrationEvents.ROOM_OVERRIDE_CHANGED, "RESOURCE", () -> pricing.setOverrides(account, room,
                List.of(new Pricing.OverrideInput(PlayMode.SINGLE, egp("140.00"))), PIN));
    }

    @Test
    void aResetOfTheRoomPricesRefreshesItsVenueToo() throws Exception {
        refreshes(IntegrationEvents.ROOM_OVERRIDE_CHANGED, "RESOURCE",
                () -> pricing.resetOverrides(account, room, PIN));
    }

    /** A refused change (wrong PIN) writes no event: it changed nothing the projection reads. */
    @Test
    void aRefusedChangeWritesNoEvent() {
        try {
            resources.changeState(account, room, Resources.StateChange.DISABLE, "0000");
        } catch (RuntimeException refused) {
            // 423 OWNER_PASSWORD_INVALID
        }

        assertThat(events(IntegrationEvents.RESOURCE_DISABLED)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /**
     * {@code change} writes exactly one more {@code type} event naming this venue (and the room for a room event), and
     * delivering it refreshes the venue's stale row.
     */
    private void refreshes(String type, String aggregateType, Runnable change) throws Exception {
        int before = events(type).size();
        stale();

        change.run();

        List<Event> written = events(type);
        assertThat(written).as(type).hasSize(before + 1);
        Event event = written.getLast();
        assertThat(event.aggregateType()).isEqualTo(aggregateType);
        JsonNode payload = json.readTree(event.payload());
        assertThat(payload.path("venueId").asText()).isEqualTo(venue.toString());
        if ("RESOURCE".equals(aggregateType)) {
            assertThat(payload.path("resourceId").asText()).isEqualTo(room.toString());
        }
        assertThat(refreshedAt()).as("nothing before the event is dispatched").contains(STALE);

        OutboxDrain.drain(appDataSource, json, new NearestAvailabilityEvents(projection, venueActivities));

        // A refresh recomputes the row from the authoritative state: with no sellable room left it removes the row
        assertThat(refreshedAt()).as(type + " refreshed the venue").satisfiesAnyOf(
                at -> assertThat(at).isEmpty(), at -> assertThat(at).get().isNotEqualTo(STALE));
    }

    record Event(String aggregateType, String payload) {
    }

    private List<Event> events(String type) {
        return OWNER.sql("""
                        SELECT aggregate_type, payload::text AS payload FROM technical.outbox_event
                         WHERE event_type = :type AND payload->>'venueId' = :venue ORDER BY id""")
                .param("type", type).param("venue", venue.toString())
                .query((rs, row) -> new Event(rs.getString("aggregate_type"), rs.getString("payload"))).list();
    }

    /** The venue's PLAYSTATION row, refreshed long ago, as if nothing had refreshed it since. */
    private void stale() {
        OWNER.sql("""
                        INSERT INTO inventory.venue_activity_availability (venue_id, activity, nearest_available_at,
                                                                           refreshed_at)
                        VALUES (:venue, 'PLAYSTATION', '2020-01-01 10:00:00+00', :stale)
                        ON CONFLICT (venue_id, activity)
                        DO UPDATE SET nearest_available_at = EXCLUDED.nearest_available_at,
                                      refreshed_at = EXCLUDED.refreshed_at""")
                .param("venue", venue).param("stale", OffsetDateTime.parse("2020-01-01T00:00:00Z")).update();
    }

    private Optional<Instant> refreshedAt() {
        return OWNER.sql("""
                        SELECT refreshed_at FROM inventory.venue_activity_availability
                         WHERE venue_id = :venue AND activity = 'PLAYSTATION'""")
                .param("venue", venue).query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant);
    }

    private static Money egp(String amount) {
        return new Money(new BigDecimal(amount));
    }
}
