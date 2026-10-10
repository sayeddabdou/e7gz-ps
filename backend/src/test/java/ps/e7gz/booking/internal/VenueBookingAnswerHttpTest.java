package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.booking.internal.OnlineBookingWorld.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.events;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.concurrency.ConcurrentRace;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-47 over HTTP: the five venue routes through the real security chain, Idempotency-Key handling (E7-42), request
 * validation, error envelope and database - replay, a changed request under the same key, the FR-BOK-16 refusal
 * replayed as the same 422 (PO decision D2), cross-venue access, and the customer phone left out of a replay once
 * the retention rule hides it (arch 10 section 5.1).
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class VenueBookingAnswerHttpTest extends IntegrationTest {

    static final String BOOKINGS = "/api/v1/venue/bookings/";

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

    @Test
    void acceptAnswersTheVenueBookingAndAReplayAnswersTheSameWithoutRunningAgain() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        String key = UUID.randomUUID().toString();

        JsonNode first = body(mvc.perform(write(venue(world), created, "accept", key, null))
                .andExpect(status().isOk()).andReturn());
        JsonNode replay = body(mvc.perform(write(venue(world), created, "accept", key, null))
                .andExpect(status().isOk()).andReturn());

        JsonNode data = first.get("data");
        assertThat(data.get("bookingId").asText()).isEqualTo(created.bookingId().toString());
        assertThat(data.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(data.get("source").asText()).isEqualTo("ONLINE");
        assertThat(data.get("terminalReason").isNull()).isTrue();
        assertThat(data.get("venueResponsePaused").asBoolean()).isFalse();
        assertThat(data.at("/customer/username").asText()).isEqualTo(customer(created, "username"));
        assertThat(data.at("/customer/phone").asText()).isEqualTo(customer(created, "phone_e164"));
        assertThat(data.get("walkIn").isNull()).isTrue();
        assertThat(data.at("/venuePriceTotal/amount").asText()).isEqualTo("77.77");
        assertThat(replay.get("data")).isEqualTo(data);
        assertThat(history(created.bookingId())).hasSize(2);
        assertThat(events(IntegrationEvents.BOOKING_CONFIRMED, created.bookingId())).hasSize(1);
    }

    @Test
    void theSameKeyWithAnotherReasonIsIdempotencyKeyReused() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        String key = UUID.randomUUID().toString();

        mvc.perform(write(venue(world), created, "reject", key, reason("Room under maintenance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.terminalReason").value("VENUE_REJECTED"))
                .andExpect(jsonPath("$.data.rejectReasonText").value("Room under maintenance"));
        mvc.perform(write(venue(world), created, "reject", key, reason("Venue issue")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(booking(created.bookingId()).rejectText()).isEqualTo("Room under maintenance");
    }

    @Test
    void theRejectReasonIsMandatoryAndAtMost200Characters() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);

        for (String invalid : new String[] {reason(""), reason("   "), reason("x".repeat(201)), "{}"}) {
            mvc.perform(write(venue(world), created, "reject", UUID.randomUUID().toString(), invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        assertThat(booking(created.bookingId()).status()).isEqualTo("PENDING");

        mvc.perform(write(venue(world), created, "reject", UUID.randomUUID().toString(), reason("x".repeat(200))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rejectReasonText").value("x".repeat(200)));
    }

    @Test
    void everyAnswerRequiresTheIdempotencyKeyAndNoOwnerPassword() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);

        for (String action : new String[] {"accept", "reject", "reject-after-expiry"}) {
            mvc.perform(write(venue(world), created, action, null, action.equals("reject") ? reason("x") : null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.field").value(OpenApiConfig.IDEMPOTENCY_KEY));
        }
        assertThat(booking(created.bookingId()).status()).isEqualTo("PENDING");
    }

    @Test
    void anOperationalCancellationAnswersItsCodeAndTheReplayAnswersTheSameRefusal() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.venueEmergency(world.venue.id());
        String key = UUID.randomUUID().toString();

        JsonNode first = body(mvc.perform(write(venue(world), created, "accept", key, null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VENUE_SELLING_BLOCK_ACTIVE")).andReturn());
        assertThat(booking(created.bookingId()).status()).as("the cancellation committed").isEqualTo("CANCELLED");
        assertThat(booking(created.bookingId()).terminalReason()).isEqualTo("VENUE_EMERGENCY_CLOSURE");

        JsonNode replay = body(mvc.perform(write(venue(world), created, "accept", key, null))
                .andExpect(status().isUnprocessableEntity()).andReturn());
        assertThat(replay.get("error")).isEqualTo(first.get("error"));
        assertThat(OWNER.sql("""
                        SELECT response_status FROM technical.idempotency_record
                         WHERE principal_id = :id AND idempotency_key = :key""")
                .param("id", world.account.id()).param("key", key).query(Integer.class).single())
                .as("stored as the 422 it is, never as a success").isEqualTo(422);
        assertThat(history(created.bookingId())).hasSize(2);
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, created.bookingId())).hasSize(1);

        // a new key now meets a booking that is no longer PENDING
        mvc.perform(write(venue(world), created, "accept", UUID.randomUUID().toString(), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BOOKING_STATE_CHANGED"));
    }

    /** D8: a paused Accept is an ordinary refusal - not stored - so the same key is evaluated afresh after the pause. */
    @Test
    void aPausedAcceptRetriedWithTheSameKeyAfterThePauseEndsIsEvaluatedAfresh() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.venueEmergency(world.venue.id());
        VenueAnswerDb.paused(created.bookingId());
        String key = UUID.randomUUID().toString();

        mvc.perform(write(venue(world), created, "accept", key, null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VENUE_SELLING_BLOCK_ACTIVE"));
        assertThat(booking(created.bookingId()).status()).isEqualTo("PENDING");
        assertThat(VenueAnswerDb.responsePaused(created.bookingId())).isTrue();
        assertThat(VenueAnswerDb.idempotencyRecords(world.account.id(), key)).as("nothing stored").isZero();

        VenueAnswerDb.endEmergencies(world.venue.id());
        VenueAnswerDb.resumed(created.bookingId());

        mvc.perform(write(venue(world), created, "accept", key, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        assertThat(VenueAnswerDb.idempotencyRecords(world.account.id(), key)).isEqualTo(1);
    }

    /** D11: a lost reservation is a data defect - generic 500, everything rolled back, no idempotency record. */
    @Test
    void aMissingReservationIsAGeneric500AndNothingIsSavedOrStored(CapturedOutput output) throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        VenueAnswerDb.reservationLost(created.reservationId());
        String acceptKey = UUID.randomUUID().toString();
        String rejectKey = UUID.randomUUID().toString();

        UUID correlationId = UUID.randomUUID();
        mvc.perform(write(venue(world), created, "accept", acceptKey, null)
                        .header(CorrelationId.HEADER, correlationId.toString()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").doesNotExist());
        mvc.perform(write(venue(world), created, "reject", rejectKey, reason("Room under maintenance")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").doesNotExist());

        assertThat(booking(created.bookingId()).status()).isEqualTo("PENDING");
        assertThat(history(created.bookingId())).hasSize(1);
        assertThat(VenueAnswerDb.outboxTypes(created.bookingId())).containsExactly(IntegrationEvents.BOOKING_CREATED);
        assertThat(VenueAnswerDb.job(VenueAnswerDb.t1(created.bookingId())).orElseThrow().status())
                .isEqualTo("PENDING");
        assertThat(VenueAnswerDb.idempotencyRecords(world.account.id(), acceptKey)).isZero();
        assertThat(VenueAnswerDb.idempotencyRecords(world.account.id(), rejectKey)).isZero();
        assertThat(output.getAll()).contains("ERROR").contains("Booking integrity failure")
                .contains("bookingId=" + created.bookingId()).contains("correlationId=" + correlationId);
    }

    @Test
    void onlyTheBookingsOwnVenueMayAnswerIt() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        OnlineBookingWorld other = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);

        mvc.perform(write(venue(other), created, "accept", UUID.randomUUID().toString(), null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(write(accessTokens.issue(AuthSubjectType.CUSTOMER, created.customerId()), created, "accept",
                        UUID.randomUUID().toString(), null))
                .andExpect(status().isForbidden());
        mvc.perform(write(null, created, "accept", UUID.randomUUID().toString(), null))
                .andExpect(status().isUnauthorized());
        assertThat(booking(created.bookingId()).status()).isEqualTo("PENDING");
    }

    @Test
    void twoAcceptsWithDifferentKeysConfirmOnceAndTheOtherIsBookingStateChanged() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        String token = venue(world);

        ConcurrentRace.Result<Integer> race = ConcurrentRace.run(2, Duration.ofSeconds(30), (racer, start) -> {
            MockHttpServletRequestBuilder accept = write(token, created, "accept", UUID.randomUUID().toString(), null);
            start.await();
            return mvc.perform(accept).andReturn().getResponse().getStatus();
        });

        assertThat(race.values()).containsExactlyInAnyOrder(200, 409);
        assertThat(booking(created.bookingId()).status()).isEqualTo("CONFIRMED");
        assertThat(history(created.bookingId())).hasSize(2);
    }

    @Test
    void thePendingAndMissedListsAnswerTheVenueBookings() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);

        mvc.perform(get("/api/v1/venue/requests/pending").header("Authorization", "Bearer " + venue(world)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].bookingId").value(created.bookingId().toString()))
                .andExpect(jsonPath("$.data[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data[0].venueResponseExpiresAt")
                        .value(created.venueResponseExpiresAt().toString()))
                .andExpect(jsonPath("$.data[0].venueResponsePaused").value(false));
        mvc.perform(get("/api/v1/venue/requests/missed").header("Authorization", "Bearer " + venue(world)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void aReplayAfterTerminalAtPlusTwoHoursLeavesTheCustomerPhoneOut() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking created = pending(world);
        String key = UUID.randomUUID().toString();
        String request = reason("Room under maintenance");
        JsonNode first = body(mvc.perform(write(venue(world), created, "reject", key, request))
                .andExpect(status().isOk()).andReturn());
        assertThat(first.at("/data/customer/phone").isMissingNode()).as("visible until terminalAt + 2h").isFalse();

        JsonNode before = body(mvc.perform(write(venue(world), created, "reject", key, request)).andReturn());
        assertThat(before.get("data")).isEqualTo(first.get("data"));

        // TEST-ONLY: the rejection happened three hours ago, past its retention point (arch 10 section 5.1)
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE booking.booking SET terminal_at = terminal_at - interval '3 hours' WHERE id = :id""")
                .param("id", created.bookingId()).update());

        JsonNode after = body(mvc.perform(write(venue(world), created, "reject", key, request))
                .andExpect(status().isOk()).andReturn());
        JsonNode expected = first.get("data").deepCopy();
        ((ObjectNode) expected.get("customer")).remove("phone");
        assertThat(after.get("data")).isEqualTo(expected);
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private CreatedBooking pending(OnlineBookingWorld world) {
        return creation.create(world.request(world.anotherCustomer(), world.room, world.start(), 60));
    }

    private String venue(OnlineBookingWorld world) {
        return accessTokens.issue(AuthSubjectType.VENUE, world.account.id());
    }

    private static String customer(CreatedBooking created, String column) {
        return OWNER.sql("SELECT " + column + " FROM iam.user_account WHERE id = :id")
                .param("id", created.customerId()).query(String.class).single();
    }

    private static String reason(String text) {
        return "{\"reasonText\": \"" + text + "\"}";
    }

    private static MockHttpServletRequestBuilder write(String token, CreatedBooking created, String action,
                                                       String key, String body) {
        MockHttpServletRequestBuilder request = post(BOOKINGS + created.bookingId() + "/" + action);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header(OpenApiConfig.IDEMPOTENCY_KEY, key);
        }
        return request;
    }

    private JsonNode body(MvcResult result) {
        try {
            return json.readTree(result.getResponse().getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
