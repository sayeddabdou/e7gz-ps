package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.booking.internal.BookingStates.minutesFromNow;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;
import static ps.e7gz.booking.internal.VenueAnswerDb.idempotencyRecords;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.booking.internal.BookingStates.Plant;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.concurrency.ConcurrentRace;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-49 over HTTP: My Bookings by bucket with its cursor (FR-BOK-19), one booking with its history (FR-BOK-20), and the
 * customer's cancel (FR-BOK-31; PO decision 11 Oct 2026, E7-49, D2) through the real security chain, Idempotency-Key
 * handling (E7-42), error envelope and database - replay, a changed request under the same key, ownership, and the
 * races of a cancel against the venue's answer and against itself.
 */
@AutoConfigureMockMvc
class CustomerBookingHttpTest extends IntegrationTest {

    static final String BOOKINGS = "/api/v1/customer/bookings";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    @Autowired
    OnlineBookingCreation creation;

    private CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ------------------------------------------------------------------------------------------------- My Bookings

    @Test
    void eachBucketListsExactlyItsStatusesAndOnlyTheCustomersOwn() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID customer = world.anotherCustomer();
        // each planted as it is made, so the customer never holds more than 4 commitments (FR-BOK-11 item 3); the
        // REJECTED one last (see BookingStates: a planted terminal reason has no fee decision)
        CreatedBooking completed = book(world, customer, 0);
        BookingStates.plant(completed.bookingId(), Plant.of("COMPLETED").startAt(minutesFromNow(-300))
                .terminalAt(minutesFromNow(-240)));
        CreatedBooking expired = book(world, customer, 2);
        BookingStates.plant(expired.bookingId(), Plant.of("VENUE_RESPONSE_EXPIRED").expiredAt(minutesFromNow(-5)));
        CreatedBooking pending = book(world, customer, 0);
        CreatedBooking confirmed = book(world, customer, 2);
        BookingStates.plant(confirmed.bookingId(), Plant.of("CONFIRMED"));
        CreatedBooking rejected = book(world, customer, 4);
        BookingStates.plant(rejected.bookingId(), Plant.of("REJECTED").reason("VENUE_REJECTED")
                .terminalAt(minutesFromNow(-5)));
        book(world, world.anotherCustomer(), 4);                                           // someone else's

        JsonNode upcoming = list(customer, "upcoming", null, null);
        assertThat(ids(upcoming)).as("soonest first").containsExactly(pending.bookingId(), confirmed.bookingId());
        assertThat(upcoming.at("/data/items/0/displayBucket").asText()).isEqualTo("upcoming");
        assertThat(upcoming.at("/data/items/0/status").asText()).isEqualTo("PENDING");
        assertThat(upcoming.at("/data/items/0/venueResponseExpiresAt").asText())
                .isEqualTo(pending.venueResponseExpiresAt().toString());
        assertThat(upcoming.at("/data/items/1/venueResponseExpiresAt").isNull()).as("only while PENDING").isTrue();
        assertThat(upcoming.at("/data/items/0/totalAmount/amount").asText()).isEqualTo("77.77");
        assertThat(upcoming.at("/data/items/0/resource/consoleType").asText()).isEqualTo("PS5");
        assertThat(upcoming.at("/data/nextCursor").isNull()).isTrue();

        assertThat(ids(list(customer, "completed", null, null))).containsExactly(completed.bookingId());
        JsonNode cancelled = list(customer, "cancelled", null, null);
        assertThat(ids(cancelled)).as("most recent first").containsExactly(rejected.bookingId(), expired.bookingId());
        assertThat(cancelled.at("/data/items/1/status").asText()).as("FR-BOK-20: shown, never changed")
                .isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(cancelled.at("/data/items/1/displayBucket").asText()).isEqualTo("cancelled");
        assertThat(cancelled.at("/data/items/0/terminalReason").asText()).isEqualTo("VENUE_REJECTED");
        assertThat(booking(expired.bookingId()).status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
    }

    @Test
    void theCursorPagesThroughABucketWithoutRepeatsOrGaps() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID customer = world.anotherCustomer();
        List<UUID> expected = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            expected.add(book(world, customer, i * 2).bookingId());
        }

        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = list(customer, "upcoming", cursor, 3);
            seen.addAll(ids(page));
            cursor = page.at("/data/nextCursor").isNull() ? null : page.at("/data/nextCursor").asText();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(2);
        assertThat(seen).containsExactlyElementsOf(expected);
    }

    @Test
    void aBadBucketCursorOrLimitIsValidationFailed() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String token = customer(world.customer.id());

        for (MockHttpServletRequestBuilder bad : List.of(get(BOOKINGS), get(BOOKINGS).param("bucket", "UPCOMING"),
                get(BOOKINGS).param("bucket", "upcoming").param("limit", "101"),
                get(BOOKINGS).param("bucket", "upcoming").param("limit", "0"),
                get(BOOKINGS).param("bucket", "upcoming").param("cursor", "not-a-cursor"))) {
            mvc.perform(bad.header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(get(BOOKINGS).param("bucket", "upcoming").param("limit", "100")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------------------------------------- one booking

    @Test
    void oneBookingCarriesItsAmountsAndHistoryAndOnlyItsCustomerMayReadIt() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID customer = world.anotherCustomer();
        CreatedBooking created = book(world, customer, 0);

        JsonNode detail = json.readTree(mvc.perform(get(BOOKINGS + "/" + created.bookingId())
                        .header("Authorization", "Bearer " + customer(customer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.at("/data/status").asText()).isEqualTo("PENDING");
        assertThat(detail.at("/data/displayBucket").asText()).isEqualTo("upcoming");
        assertThat(detail.at("/data/venue/id").asText()).isEqualTo(world.venue.id().toString());
        assertThat(detail.at("/data/nominalBookedMinutes").asInt()).isEqualTo(60);
        assertThat(detail.at("/data/bookingFee/displayAsFree").asBoolean()).isTrue();
        assertThat(detail.at("/data/venueResponsePaused").asBoolean()).isFalse();
        assertThat(detail.at("/data/attendanceResult").isNull()).isTrue();
        assertThat(detail.at("/data/history").size()).isEqualTo(1);
        assertThat(detail.at("/data/history/0/toStatus").asText()).isEqualTo("PENDING");

        mvc.perform(get(BOOKINGS + "/" + created.bookingId())
                        .header("Authorization", "Bearer " + customer(world.anotherCustomer())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get(BOOKINGS + "/" + UUID.randomUUID()).header("Authorization", "Bearer " + customer(customer)))
                .andExpect(status().isNotFound());
        mvc.perform(get(BOOKINGS + "/" + created.bookingId())
                        .header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.VENUE,
                                world.account.id())))
                .andExpect(status().isForbidden());
        mvc.perform(get(BOOKINGS + "/" + created.bookingId())).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------------------------------------------ cancel

    @Test
    void cancelAnswersTheCancelledBookingAndAReplayAnswersTheSameWithoutRunningAgain() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID customer = world.anotherCustomer();
        CreatedBooking created = book(world, customer, 0);
        String key = UUID.randomUUID().toString();

        JsonNode first = cancel(customer, created.bookingId(), key, 200);
        JsonNode replay = cancel(customer, created.bookingId(), key, 200);

        assertThat(first.at("/data/status").asText()).isEqualTo("CANCELLED");
        assertThat(first.at("/data/terminalReason").asText()).isEqualTo("CUSTOMER_CANCELLED");
        assertThat(first.at("/data/displayBucket").asText()).isEqualTo("cancelled");
        assertThat(first.at("/data/history").size()).isEqualTo(2);
        assertThat(replay.get("data")).isEqualTo(first.get("data"));
        assertThat(history(created.bookingId())).hasSize(2);
        assertThat(VenueAnswerDb.events(ps.e7gz.messaging.api.IntegrationEvents.BOOKING_CANCELLED,
                created.bookingId())).hasSize(1);
    }

    @Test
    void cancelRequiresTheIdempotencyKeyAndRefusesItsReuseForAnotherBooking() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID customer = world.anotherCustomer();
        CreatedBooking one = book(world, customer, 0);
        CreatedBooking two = book(world, customer, 2);
        String key = UUID.randomUUID().toString();

        mvc.perform(post(BOOKINGS + "/" + one.bookingId() + "/cancel")
                        .header("Authorization", "Bearer " + customer(customer)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(booking(one.bookingId()).status()).isEqualTo("PENDING");

        cancel(customer, one.bookingId(), key, 200);
        JsonNode reused = cancel(customer, two.bookingId(), key, 422);
        assertThat(reused.at("/error/code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(booking(two.bookingId()).status()).isEqualTo("PENDING");
    }

    @Test
    void anotherCustomersBookingIsNotFoundBeforeAnyIdempotencyRecord() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = book(world, world.anotherCustomer(), 0);
        UUID stranger = world.anotherCustomer();
        String key = UUID.randomUUID().toString();

        JsonNode refused = cancel(stranger, created.bookingId(), key, 404);
        assertThat(refused.at("/error/code").asText()).isEqualTo("NOT_FOUND");
        assertThat(idempotencyRecords(stranger, key)).isZero();
        assertThat(booking(created.bookingId()).status()).isEqualTo("PENDING");
        mvc.perform(post(BOOKINGS + "/" + created.bookingId() + "/cancel")
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, key)
                        .header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.VENUE,
                                world.account.id())))
                .andExpect(status().isForbidden());
    }

    @Test
    void theRefusalsAnswerTheirCodesAndAreNotStored() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID customer = world.anotherCustomer();
        CreatedBooking expired = book(world, customer, 0);
        CreatedBooking active = book(world, world.anotherCustomer(), 2);
        UUID activeCustomer = active.customerId();
        BookingStates.plant(expired.bookingId(), Plant.of("VENUE_RESPONSE_EXPIRED").expiredAt(minutesFromNow(-5)));
        BookingStates.plant(active.bookingId(), Plant.of("ACTIVE").startAt(minutesFromNow(-5)));
        String key = UUID.randomUUID().toString();

        assertThat(cancel(customer, expired.bookingId(), key, 409).at("/error/code").asText())
                .isEqualTo("BOOKING_STATE_CHANGED");
        assertThat(idempotencyRecords(customer, key)).as("a refusal is never stored").isZero();
        assertThat(cancel(activeCustomer, active.bookingId(), UUID.randomUUID().toString(), 422)
                .at("/error/code").asText()).isEqualTo("DEADLINE_PASSED");
        assertThat(booking(expired.bookingId()).status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(booking(active.bookingId()).status()).isEqualTo("ACTIVE");
    }

    @Test
    void aCancelRacingTheVenuesAcceptAlwaysEndsCancelled() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID customer = world.anotherCustomer();
        CreatedBooking created = book(world, customer, 0);
        String customerToken = customer(customer);
        String venueToken = accessTokens.issue(AuthSubjectType.VENUE, world.account.id());

        ConcurrentRace.Result<String> race = ConcurrentRace.run(2, Duration.ofSeconds(30), (racer, start) -> {
            MockHttpServletRequestBuilder request = racer == 0
                    ? post(BOOKINGS + "/" + created.bookingId() + "/cancel")
                            .header("Authorization", "Bearer " + customerToken)
                    : post("/api/v1/venue/bookings/" + created.bookingId() + "/accept")
                            .header("Authorization", "Bearer " + venueToken);
            request.header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString());
            start.await();
            return (racer == 0 ? "cancel:" : "accept:") + mvc.perform(request).andReturn().getResponse().getStatus();
        });

        if (race.values().contains("accept:200")) {
            // the accept first: CONFIRMED before startAt is still cancellable (D2), so the cancel succeeds too
            assertThat(race.values()).containsExactlyInAnyOrder("cancel:200", "accept:200");
            assertThat(history(created.bookingId())).hasSize(3);
        } else {
            // the cancel first; the accept then finds no PENDING booking
            assertThat(race.values()).containsExactlyInAnyOrder("cancel:200", "accept:409");
            assertThat(history(created.bookingId())).hasSize(2);
        }
        assertThat(booking(created.bookingId()).status()).isEqualTo("CANCELLED");
        assertThat(booking(created.bookingId()).terminalReason()).isEqualTo("CUSTOMER_CANCELLED");
    }

    @Test
    void twoCancelsWithDifferentKeysCancelOnceAndTheOtherIsBookingStateChanged() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID customer = world.anotherCustomer();
        CreatedBooking created = book(world, customer, 0);
        String token = customer(customer);

        ConcurrentRace.Result<Integer> race = ConcurrentRace.run(2, Duration.ofSeconds(30), (racer, start) -> {
            MockHttpServletRequestBuilder cancel = post(BOOKINGS + "/" + created.bookingId() + "/cancel")
                    .header("Authorization", "Bearer " + token)
                    .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString());
            start.await();
            return mvc.perform(cancel).andReturn().getResponse().getStatus();
        });

        assertThat(race.values()).containsExactlyInAnyOrder(200, 409);
        assertThat(history(created.bookingId())).hasSize(2);
        assertThat(VenueAnswerDb.events(ps.e7gz.messaging.api.IntegrationEvents.BOOKING_CANCELLED,
                created.bookingId())).hasSize(1);
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /** A 60-minute booking {@code hoursAfter} hours after the world's first start, for {@code customer}. */
    private CreatedBooking book(OnlineBookingWorld world, UUID customer, int hoursAfter) {
        return creation.create(world.request(customer, world.room, world.start().plus(Duration.ofHours(hoursAfter)),
                60));
    }

    private JsonNode list(UUID customer, String bucket, String cursor, Integer limit) throws Exception {
        MockHttpServletRequestBuilder request = get(BOOKINGS).param("bucket", bucket)
                .header("Authorization", "Bearer " + customer(customer));
        if (cursor != null) {
            request.param("cursor", cursor);
        }
        if (limit != null) {
            request.param("limit", limit.toString());
        }
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private JsonNode cancel(UUID customer, UUID bookingId, String key, int expectedStatus) throws Exception {
        return json.readTree(mvc.perform(post(BOOKINGS + "/" + bookingId + "/cancel")
                        .header("Authorization", "Bearer " + customer(customer))
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, key))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString());
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.at("/data/items").forEach(item -> ids.add(UUID.fromString(item.get("bookingId").asText())));
        return ids;
    }

    private String customer(UUID customerId) {
        return accessTokens.issue(AuthSubjectType.CUSTOMER, customerId);
    }
}
