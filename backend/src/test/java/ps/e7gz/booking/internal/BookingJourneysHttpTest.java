package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.booking.internal.OnlineBookingWorld.OWNER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.inventory.sweep.api.InvariantSweep;
import ps.e7gz.inventory.sweep.api.SweepReport;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-49 - the Phase 2 exit journeys end to end over HTTP (arch 14: "J1 and J8 complete end to end"), and the integrity
 * sweep clean after a seeded run of booking flows (arch 14: "every terminal booking carries one reason code", NFR-26,
 * NFR-28). Every write here goes through the real routes and commands - nothing is planted.
 */
@AutoConfigureMockMvc
class BookingJourneysHttpTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    @Autowired
    CommandRunner runner;

    @Autowired
    VenueAnswerStore store;

    @Autowired
    VenueAnswers answers;

    @Autowired
    InvariantSweep sweep;

    private CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    /** J1 (PRD): find a venue, see the price, book, wait as Pending, and the venue accepts: Confirmed. */
    @Test
    void j1FindAndBook() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String customer = accessTokens.issue(AuthSubjectType.CUSTOMER, world.customer.id());
        String venue = venueToken(world);

        // find: the venue is listed in its area for the activity, and its details load
        JsonNode venues = read(get("/api/v1/customer/venues").param("areaId", world.venue.areaId().toString())
                .param("activity", "PLAYSTATION"), customer);
        assertThat(venues.at("/data/items").findValuesAsText("id")).contains(world.venue.id().toString());
        JsonNode details = read(get("/api/v1/customer/venues/" + world.venue.id()).param("activity", "PLAYSTATION"),
                customer);
        assertThat(details.at("/data/id").asText()).isEqualTo(world.venue.id().toString());

        // the exact price, then the booking: Pending with the venue's answer deadline
        JsonNode quote = read(get("/api/v1/customer/bookings/quote").param("resourceId", world.room.id().toString())
                .param("playMode", "SINGLE").param("startAt", world.start().toString())
                .param("durationMinutes", "120"), customer);
        assertThat(quote.at("/data/totalAmount/amount").asText()).isEqualTo("155.54");
        JsonNode created = json.readTree(mvc.perform(post("/api/v1/customer/bookings")
                        .header("Authorization", "Bearer " + customer)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\": \"" + world.room.id() + "\", \"playMode\": \"SINGLE\", "
                                + "\"startAt\": \"" + world.start() + "\", \"durationMinutes\": 120}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String bookingId = created.at("/data/bookingId").asText();
        assertThat(created.at("/data/status").asText()).isEqualTo("PENDING");
        assertThat(created.at("/data/totalAmount/amount").asText()).isEqualTo(quote.at("/data/totalAmount/amount")
                .asText());

        JsonNode upcoming = read(get("/api/v1/customer/bookings").param("bucket", "upcoming"), customer);
        assertThat(upcoming.at("/data/items/0/bookingId").asText()).isEqualTo(bookingId);
        assertThat(upcoming.at("/data/items/0/status").asText()).isEqualTo("PENDING");
        assertThat(upcoming.at("/data/items/0/venue/phone").isTextual()).as("FR-DIS-09: open from PENDING").isTrue();

        // the venue sees the request and accepts within ten minutes
        JsonNode pending = read(get("/api/v1/venue/requests/pending"), venue);
        assertThat(pending.at("/data/0/bookingId").asText()).isEqualTo(bookingId);
        assertThat(pending.at("/data/0/customer/phone").isTextual()).isTrue();
        mvc.perform(post("/api/v1/venue/bookings/" + bookingId + "/accept").header("Authorization", "Bearer " + venue)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        JsonNode confirmed = read(get("/api/v1/customer/bookings/" + bookingId), customer);
        assertThat(confirmed.at("/data/status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.at("/data/displayBucket").asText()).isEqualTo("upcoming");
        assertThat(confirmed.at("/data/venue/phone").isTextual()).isTrue();
        assertThat(confirmed.at("/data/history").findValuesAsText("toStatus")).containsExactly("PENDING", "CONFIRMED");
        JsonNode listed = read(get("/api/v1/venue/bookings").param("q", bookingId), venue);
        assertThat(listed.at("/data/items/0/status").asText()).isEqualTo("CONFIRMED");
    }

    /** J8 (PRD): staff record a walk-in starting now - ACTIVE at once, on the same inventory, no Owner Password. */
    @Test
    void j8TheVenueRecordsAWalkIn() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String venue = venueToken(world);

        JsonNode walkIn = walkIn(world.room, venue, 201);
        String bookingId = walkIn.at("/data/bookingId").asText();
        assertThat(walkIn.at("/data/status").asText()).isEqualTo("ACTIVE");
        assertThat(walkIn.at("/data/source").asText()).isEqualTo("MANUAL");
        assertThat(walkIn.at("/data/walkIn/name").asText()).isEqualTo("Omar");
        assertThat(walkIn.at("/data/customer").isNull()).isTrue();

        // it takes the same inventory: the room is not sold twice
        assertThat(walkIn(world.room, venue, 409).at("/error/code").asText()).isEqualTo("SLOT_NO_LONGER_AVAILABLE");

        JsonNode listed = read(get("/api/v1/venue/bookings").param("source", "MANUAL"), venue);
        assertThat(listed.at("/data/items").findValuesAsText("bookingId")).containsExactly(bookingId);
        assertThat(listed.at("/data/items/0/businessDate").isTextual()).as("FR-MAN-09: always present").isTrue();
        JsonNode detail = read(get("/api/v1/venue/bookings/" + bookingId), venue);
        assertThat(detail.at("/data/booking/status").asText()).isEqualTo("ACTIVE");
        assertThat(detail.at("/data/booking/walkIn/phone").asText()).isEqualTo("+201001234567");
        assertThat(detail.at("/data/history").findValuesAsText("toStatus")).containsExactly("ACTIVE");
        assertThat(OWNER.sql("SELECT count(*) FROM inventory.resource_reservation WHERE booking_id = :id AND active_flag")
                .param("id", UUID.fromString(bookingId)).query(Integer.class).single()).isEqualTo(1);
    }

    /**
     * A seeded run of every booking flow of Sprint 2 - create, accept, reject, response expiry, customer cancel from
     * PENDING and from CONFIRMED, walk-in and future manual bookings and their cancel - then the nightly integrity
     * sweep (arch 11 section 4) finds nothing: no overlapping active reservation (NFR-28) and no terminal booking
     * without exactly one reason (NFR-26).
     */
    @Test
    void theIntegritySweepIsCleanAfterASeededRunOfEveryBookingFlow() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String venue = venueToken(world);
        List<UUID> terminal = new ArrayList<>();
        Map<String, Integer> expected = Map.of("CANCELLED", 4, "REJECTED", 1);

        UUID accepted = book(world, 0);
        answer(venue, accepted, "accept", null);
        UUID rejected = book(world, 1);
        answer(venue, rejected, "reject", "{\"reasonText\": \"Room under maintenance\"}");
        terminal.add(rejected);
        UUID expired = book(world, 2);
        expire(expired);
        UUID cancelledPending = book(world, 3);
        customerCancel(cancelledPending);
        terminal.add(cancelledPending);
        UUID cancelledConfirmed = book(world, 4);
        answer(venue, cancelledConfirmed, "accept", null);
        customerCancel(cancelledConfirmed);
        terminal.add(cancelledConfirmed);
        Fixtures.Resource second = world.anotherRoom(room -> room.playStation(ConsoleType.PS5));
        UUID walkIn = UUID.fromString(walkIn(second, venue, 201).at("/data/bookingId").asText());
        answer(venue, walkIn, "cancel-manual", null);
        terminal.add(walkIn);
        UUID futureManual = UUID.fromString(json.readTree(mvc.perform(post("/api/v1/venue/bookings")
                        .header("Authorization", "Bearer " + venue)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\": \"" + world.room.id() + "\", \"playMode\": \"SINGLE\", \"startAt\": \""
                                + world.start().plus(Duration.ofHours(6)) + "\", \"durationMinutes\": 60}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .at("/data/bookingId").asText());
        answer(venue, futureManual, "cancel-manual", null);
        terminal.add(futureManual);

        SweepReport report = sweep.run();

        assertThat(report.violations()).as("the integrity sweep is clean").isEmpty();
        assertThat(report.clean()).isTrue();
        // and the run is really there: each terminal booking with exactly one reason, nothing left holding a room
        for (UUID id : terminal) {
            assertThat(OWNER.sql("SELECT terminal_reason IS NOT NULL AND terminal_at IS NOT NULL FROM booking.booking "
                            + "WHERE id = :id").param("id", id).query(Boolean.class).single()).isTrue();
            assertThat(OWNER.sql("SELECT count(*) FROM inventory.resource_reservation WHERE booking_id = :id "
                    + "AND active_flag").param("id", id).query(Integer.class).single()).isZero();
        }
        assertThat(OWNER.sql("SELECT status, count(*) FROM booking.booking WHERE venue_id = :venue "
                        + "AND status IN ('CANCELLED', 'REJECTED') GROUP BY status")
                .param("venue", world.venue.id())
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getInt(2))).list())
                .containsExactlyInAnyOrderElementsOf(expected.entrySet());
        assertThat(VenueAnswerDb.booking(expired).status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(VenueAnswerDb.booking(accepted).status()).isEqualTo("CONFIRMED");
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private UUID book(OnlineBookingWorld world, int hoursAfter) throws Exception {
        UUID customer = world.anotherCustomer();
        String body = mvc.perform(post("/api/v1/customer/bookings")
                        .header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, customer))
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\": \"" + world.room.id() + "\", \"playMode\": \"SINGLE\", \"startAt\": \""
                                + world.start().plus(Duration.ofHours(hoursAfter)) + "\", \"durationMinutes\": 60}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).at("/data/bookingId").asText());
    }

    private void customerCancel(UUID bookingId) throws Exception {
        UUID customer = OWNER.sql("SELECT customer_id FROM booking.booking WHERE id = :id").param("id", bookingId)
                .query(UUID.class).single();
        mvc.perform(post("/api/v1/customer/bookings/" + bookingId + "/cancel")
                        .header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, customer))
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString()))
                .andExpect(status().isOk());
    }

    private void answer(String venue, UUID bookingId, String action, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/venue/bookings/" + bookingId + "/" + action)
                .header("Authorization", "Bearer " + venue)
                .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        mvc.perform(request).andExpect(status().isOk());
    }

    /** T1 as the worker runs it, with the stored deadline moved to the command's own instant (E7-47's way). */
    private void expire(UUID bookingId) {
        FindBooking.Keys keys = runner.run(new FindBooking(store, bookingId)).orElseThrow();
        JobOutcome outcome = runner.run(new Command<JobOutcome>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
            }

            @Override
            public CommandScope scope() {
                return keys.scope();
            }

            @Override
            public JobOutcome execute(CommandContext context) {
                Deadlines.move(context, "booking.booking.venue_response_expires_at", bookingId, context.commandNow());
                return answers.expire(context, new ScheduledJob(UUID.randomUUID(),
                        CreateOnlineBookingCommand.VENUE_RESPONSE_EXPIRY, CreateOnlineBookingCommand.BOOKING_SUBJECT,
                        bookingId, context.commandNow(), 1, VenueAnswers.venueResponseExpiryKey(bookingId),
                        CorrelationId.generate()));
            }
        });
        assertThat(outcome).isInstanceOf(JobOutcome.Done.class);
    }

    private JsonNode walkIn(Fixtures.Resource room, String venue, int expectedStatus) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/venue/bookings").header("Authorization", "Bearer " + venue)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\": \"" + room.id() + "\", \"playMode\": \"SINGLE\", "
                                + "\"durationMinutes\": 60, \"customerName\": \"Omar\", "
                                + "\"customerPhone\": \"01001234567\"}"))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString());
    }

    private JsonNode read(MockHttpServletRequestBuilder request, String token) throws Exception {
        return json.readTree(mvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String venueToken(OnlineBookingWorld world) {
        return accessTokens.issue(AuthSubjectType.VENUE, world.account.id());
    }
}
