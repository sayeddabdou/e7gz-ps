package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.booking.internal.OnlineBookingWorld.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-48 over HTTP: {@code POST /venue/bookings} and {@code POST /venue/bookings/{bookingId}/cancel-manual} through the
 * real security chain, Idempotency-Key handling (E7-42), request validation, error envelope and database - including
 * PCR-007's commit-time provenance check on an ACTIVE walk-in cancelled through the API (PO decision 10 Oct 2026,
 * E7-48).
 */
@AutoConfigureMockMvc
class ManualBookingHttpTest extends IntegrationTest {

    static final String BOOKINGS = "/api/v1/venue/bookings";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    @Test
    void aWalkInAnswers201WithTheWalkInAndAReplayAnswersTheSameWithoutRunningAgain() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String key = UUID.randomUUID().toString();
        String request = walkIn(world, " Omar ", "0100 123 4567");

        JsonNode first = body(mvc.perform(create(venue(world), key, request)).andExpect(status().isCreated())
                .andReturn());
        JsonNode replay = body(mvc.perform(create(venue(world), key, request)).andExpect(status().isCreated())
                .andReturn());

        JsonNode data = first.get("data");
        assertThat(data.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(data.get("source").asText()).isEqualTo("MANUAL");
        assertThat(data.get("customer").isNull()).isTrue();
        assertThat(data.at("/walkIn/name").asText()).isEqualTo("Omar");
        assertThat(data.at("/walkIn/phone").asText()).isEqualTo("+201001234567");
        assertThat(data.get("startAt").asText()).isEqualTo(data.get("createdAt").asText());
        assertThat(data.get("businessDate").isTextual()).isTrue();
        assertThat(data.at("/venuePriceTotal/amount").asText()).isEqualTo("77.77");
        assertThat(data.get("venueResponseExpiresAt").isNull()).isTrue();
        assertThat(replay.get("data")).isEqualTo(data);
        assertThat(bookingsOf(world)).isOne();
    }

    @Test
    void theSameKeyWithAnotherBodyIsIdempotencyKeyReused() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String key = UUID.randomUUID().toString();

        mvc.perform(create(venue(world), key, walkIn(world, null, null))).andExpect(status().isCreated());
        mvc.perform(create(venue(world), key, walkIn(world, "Someone else", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(bookingsOf(world)).isOne();
    }

    @Test
    void bothRoutesRequireTheIdempotencyKeyAndNoOwnerPassword() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();

        mvc.perform(create(venue(world), null, walkIn(world, null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value(OpenApiConfig.IDEMPOTENCY_KEY));
        mvc.perform(cancel(venue(world), UUID.randomUUID(), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value(OpenApiConfig.IDEMPOTENCY_KEY));
        assertThat(bookingsOf(world)).isZero();
    }

    /** D4: a disabled venue account's token is refused by the security filter - 401 on both routes. */
    @Test
    void aDisabledVenueAccountIs401() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.builder().accountDisabled().create();

        mvc.perform(create(venue(world), UUID.randomUUID().toString(), walkIn(world, null, null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(cancel(venue(world), UUID.randomUUID(), UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
        assertThat(bookingsOf(world)).isZero();
    }

    @Test
    void anOnboardingVenueIsForbiddenAndACustomerCannotUseTheVenueRoutes() throws Exception {
        OnlineBookingWorld onboarding = OnlineBookingWorld.builder().status(MarketplaceStatus.ONBOARDING).create();
        mvc.perform(create(venue(onboarding), UUID.randomUUID().toString(), walkIn(onboarding, null, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        OnlineBookingWorld world = OnlineBookingWorld.create();
        mvc.perform(create(accessTokens.issue(AuthSubjectType.CUSTOMER, world.customer.id()),
                        UUID.randomUUID().toString(), walkIn(world, null, null)))
                .andExpect(status().isForbidden());
        assertThat(bookingsOf(onboarding) + bookingsOf(world)).isZero();
    }

    /** D10: a phone that is not an Egyptian number is VALIDATION_FAILED on customerPhone; nothing is written. */
    @Test
    void anInvalidPhoneIsValidationFailed() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();

        mvc.perform(create(venue(world), UUID.randomUUID().toString(), walkIn(world, null, "12345")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("customerPhone"));
        assertThat(bookingsOf(world)).isZero();
        assertThat(OWNER.sql("SELECT count(*) FROM technical.idempotency_record WHERE principal_id = :id")
                .param("id", world.account.id()).query(Long.class).single()).isZero();
    }

    @Test
    void aResourceOfAnotherVenueIsNotFound() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        OnlineBookingWorld other = OnlineBookingWorld.create();

        mvc.perform(create(venue(world), UUID.randomUUID().toString(), walkIn(other, null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    /** PCR-007 through the API: the commit-time check sees the venue's own history row; a replay changes nothing. */
    @Test
    void cancelManualOfAnActiveWalkInAnswersCancelledAndAReplayAnswersTheSame() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        JsonNode created = body(mvc.perform(create(venue(world), UUID.randomUUID().toString(),
                walkIn(world, null, null))).andExpect(status().isCreated()).andReturn());
        UUID id = UUID.fromString(created.at("/data/bookingId").asText());
        String key = UUID.randomUUID().toString();

        JsonNode first = body(mvc.perform(cancel(venue(world), id, key)).andExpect(status().isOk()).andReturn());
        JsonNode replay = body(mvc.perform(cancel(venue(world), id, key)).andExpect(status().isOk()).andReturn());

        assertThat(first.at("/data/status").asText()).isEqualTo("CANCELLED");
        assertThat(first.at("/data/terminalReason").asText()).isEqualTo("VENUE_CANCELLED_MANUAL_BOOKING");
        assertThat(replay.get("data")).isEqualTo(first.get("data"));
        assertThat(history(id)).hasSize(2);
        mvc.perform(cancel(venue(world), id, UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BOOKING_STATE_CHANGED"));
    }

    @Test
    void cancelManualOfAnotherVenuesBookingIsNotFound() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        OnlineBookingWorld other = OnlineBookingWorld.create();
        JsonNode created = body(mvc.perform(create(venue(world), UUID.randomUUID().toString(),
                walkIn(world, null, null))).andExpect(status().isCreated()).andReturn());

        mvc.perform(cancel(venue(other), UUID.fromString(created.at("/data/bookingId").asText()),
                        UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    private String venue(OnlineBookingWorld world) {
        return accessTokens.issue(AuthSubjectType.VENUE, world.account.id());
    }

    private String walkIn(OnlineBookingWorld world, String name, String phone) throws Exception {
        var body = json.createObjectNode();
        body.put("resourceId", world.room.id().toString());
        body.put("playMode", "SINGLE");
        body.put("durationMinutes", 60);
        if (name != null) {
            body.put("customerName", name);
        }
        if (phone != null) {
            body.put("customerPhone", phone);
        }
        return json.writeValueAsString(body);
    }

    private static long bookingsOf(OnlineBookingWorld world) {
        return OWNER.sql("SELECT count(*) FROM booking.booking WHERE resource_id = :id").param("id", world.room.id())
                .query(Long.class).single();
    }

    private static MockHttpServletRequestBuilder create(String token, String key, String body) {
        return withHeaders(post(BOOKINGS).contentType(MediaType.APPLICATION_JSON).content(body), token, key);
    }

    private static MockHttpServletRequestBuilder cancel(String token, UUID bookingId, String key) {
        return withHeaders(post(BOOKINGS + "/" + bookingId + "/cancel-manual"), token, key);
    }

    private static MockHttpServletRequestBuilder withHeaders(MockHttpServletRequestBuilder request, String token,
                                                             String key) {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (key != null) {
            request.header(OpenApiConfig.IDEMPOTENCY_KEY, key);
        }
        return request;
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
