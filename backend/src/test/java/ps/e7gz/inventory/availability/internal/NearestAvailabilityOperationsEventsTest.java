package ps.e7gz.inventory.availability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.inventory.availability.api.NearestAvailabilityProjection;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.OutboxConsumer;
import ps.e7gz.messaging.api.OutboxEvent;
import ps.e7gz.messaging.internal.OutboxDrain;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.venue.api.VenueActivities;

/**
 * E7-81 (Sprint 2 review, PO decision 12 Oct 2026): MaintenanceCreated, MaintenanceEdited, MaintenanceDeleted and
 * ClosureCreated are written in the command of each change, and delivering that event alone - no ReservationHeld or
 * Released of the same command, no one-minute pass - refreshes the venue's Nearest Availability row.
 */
@AutoConfigureMockMvc
class NearestAvailabilityOperationsEventsTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));
    private static final Instant STALE = Instant.parse("2020-01-01T00:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ApplicationContext context;

    @Autowired
    NearestAvailabilityProjection projection;

    @Autowired
    VenueActivities venueActivities;

    @Autowired
    DataSource appDataSource;

    private BookingFlows.Booking booking;
    private String token;
    private Instant t;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void aVenue() {
        correlation = CorrelationId.open(CorrelationId.generate());
        booking = BookingFlows.of(context).pending();
        token = "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, booking.venueAccountId());
        t = booking.startAt().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.HOURS);
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @Test
    void eachMaintenanceEventRefreshesItsVenueAtOnce() throws Exception {
        String window = "{\"resourceId\":\"" + booking.resourceId() + "\",\"startAt\":\"" + t + "\",\"endAt\":\""
                + t.plus(Duration.ofHours(2)) + "\",\"reason\":\"Paint\"}";
        String created = refreshes(IntegrationEvents.MAINTENANCE_CREATED,
                json(post("/api/v1/venue/maintenance"), window));
        UUID id = UUID.fromString(json.readTree(created).path("data").path("id").asText());
        refreshes(IntegrationEvents.MAINTENANCE_EDITED, json(patch("/api/v1/venue/maintenance/" + id),
                "{\"endAt\":\"" + t.plus(Duration.ofHours(3)) + "\"}"));
        refreshes(IntegrationEvents.MAINTENANCE_DELETED, delete("/api/v1/venue/maintenance/" + id)
                .header("Authorization", token));
    }

    @Test
    void aNewClosureRefreshesItsVenueAtOnce() throws Exception {
        String proposal = "{\"kind\":\"FULL_DAY\",\"startAt\":\"" + t + "\",\"endAt\":\"" + t.plus(Duration.ofDays(1))
                + "\",\"reason\":\"Private event\"}";
        String preview = mvc.perform(json(post("/api/v1/venue/closures/preview"), "{\"proposal\":" + proposal + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String previewToken = json.readTree(preview).path("data").path("previewToken").asText();
        refreshes(IntegrationEvents.CLOSURE_CREATED, json(post("/api/v1/venue/closures"),
                "{\"proposal\":" + proposal + ",\"previewToken\":\"" + previewToken + "\"}")
                .header("Idempotency-Key", UUID.randomUUID().toString()));
    }

    /** The change writes one event of {@code type} for this venue; delivering only that type refreshes the row. */
    private String refreshes(String type, MockHttpServletRequestBuilder change) throws Exception {
        long before = events(type);
        stale();

        String body = mvc.perform(change).andExpect(status().is2xxSuccessful()).andReturn().getResponse()
                .getContentAsString();

        assertThat(events(type)).as(type).isEqualTo(before + 1);
        assertThat(refreshedAt()).as("nothing before the event is dispatched").contains(STALE);
        OutboxDrain.drain(appDataSource, json, new Only(type,
                new NearestAvailabilityEvents(projection, venueActivities)));
        assertThat(refreshedAt()).as(type + " refreshed the venue").satisfiesAnyOf(
                at -> assertThat(at).isEmpty(), at -> assertThat(at).get().isNotEqualTo(STALE));
        return body;
    }

    /** The consumer, for one event type only. */
    private record Only(String type, OutboxConsumer consumer) implements OutboxConsumer {

        @Override
        public Set<String> eventTypes() {
            return Set.of(type);
        }

        @Override
        public void handle(OutboxEvent event) {
            consumer.handle(event);
        }
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private long events(String type) {
        return OWNER.sql("""
                        SELECT count(*) FROM technical.outbox_event
                         WHERE event_type = :type AND payload->>'venueId' = :venue""")
                .param("type", type).param("venue", booking.venueId().toString()).query(Long.class).single();
    }

    private void stale() {
        OWNER.sql("""
                        INSERT INTO inventory.venue_activity_availability (venue_id, activity, nearest_available_at,
                                                                           refreshed_at)
                        VALUES (:venue, 'PLAYSTATION', '2020-01-01 10:00:00+00', :stale)
                        ON CONFLICT (venue_id, activity)
                        DO UPDATE SET nearest_available_at = EXCLUDED.nearest_available_at,
                                      refreshed_at = EXCLUDED.refreshed_at""")
                .param("venue", booking.venueId()).param("stale", OffsetDateTime.parse("2020-01-01T00:00:00Z"))
                .update();
    }

    private Optional<Instant> refreshedAt() {
        return OWNER.sql("""
                        SELECT refreshed_at FROM inventory.venue_activity_availability
                         WHERE venue_id = :venue AND activity = 'PLAYSTATION'""")
                .param("venue", booking.venueId()).query(OffsetDateTime.class).optional()
                .map(OffsetDateTime::toInstant);
    }
}
