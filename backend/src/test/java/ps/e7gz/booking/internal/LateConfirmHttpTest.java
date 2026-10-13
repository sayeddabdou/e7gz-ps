package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.booking.internal.LateConfirmDb.attempt;
import static ps.e7gz.booking.internal.LateConfirmDb.attempts;
import static ps.e7gz.booking.internal.LateConfirmDb.history;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmations;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-58 over HTTP: the three Late Confirm routes of the Sprint 3 contract (E7-54) through the real security chain,
 * Idempotency-Key handling (E7-42), error envelope and database - a replay of a success and of a committed refusal
 * answers the same without running again (D1, D4), nothing about the customer reaches the venue (FR-LCF-05, NFR-19),
 * and each route is its own principal's and its own booking's.
 */
@AutoConfigureMockMvc
class LateConfirmHttpTest extends IntegrationTest {

    private static final String VENUE_BOOKINGS = "/api/v1/venue/bookings/";
    private static final String CUSTOMER_BOOKINGS = "/api/v1/customer/bookings/";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    @Autowired
    CommandRunner runner;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    VenueAnswerStore store;

    @Autowired
    VenueAnswers answers;

    @Autowired
    LateConfirmations lateConfirmations;

    private LateConfirmDb db;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        db = new LateConfirmDb(runner, creation, store, answers);
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @Test
    void theVenueSendsItOnceAndAReplayAnswersTheSame() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        String key = UUID.randomUUID().toString();

        JsonNode first = body(mvc.perform(send(venue(world), late, key)).andExpect(status().isOk()).andReturn());
        JsonNode replay = body(mvc.perform(send(venue(world), late, key)).andExpect(status().isOk()).andReturn());

        JsonNode data = first.get("data");
        assertThat(data.get("bookingId").asText()).isEqualTo(late.toString());
        assertThat(data.get("status").asText()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(Instant.parse(data.get("lateConfirmExpiresAt").asText()))
                .isEqualTo(attempt(late).orElseThrow().expiresAt());
        assertThat(replay.get("data")).isEqualTo(data);
        assertThat(attempts(late)).isEqualTo(1);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_INITIATED, late)).hasSize(1);
    }

    @Test
    void theNeutralRefusalAtFourCommitmentsCommitsOnceAndIsReplayedWithoutDetail() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        UUID late = db.expired(world, start, 60);
        for (int i = 1; i <= 4; i++) {
            creation.create(world.request(start.plus(Duration.ofHours(i)), 60));
        }
        String key = UUID.randomUUID().toString();

        for (int i = 0; i < 2; i++) {
            JsonNode refused = body(mvc.perform(send(venue(world), late, key))
                    .andExpect(status().isUnprocessableEntity()).andReturn());
            assertThat(refused.get("error").get("code").asText()).isEqualTo("CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM");
            assertThat(refused.get("error").has("details")).as("no details (FR-LCF-05)").isFalse();
            assertThat(refused.get("error").has("field")).as("no field").isFalse();
            assertThat(refused.toString()).doesNotContain("LIMIT").doesNotContain("COMMITMENT");
        }
        assertThat(history(late)).as("the event committed once, not again on the replay")
                .filteredOn(e -> "LATE_CONFIRM_FAILED_BOOKING_LIMIT".equals(e.eventType())).hasSize(1);
        assertThat(booking(late).status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(VenueAnswerDb.idempotencyRecords(world.account.id(), key)).isEqualTo(1);

        // NFR-19: the venue's booking detail shows no trace of it
        String detail = mvc.perform(get(VENUE_BOOKINGS + late).header("Authorization", "Bearer " + venue(world)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("LATE_CONFIRM_FAILED_BOOKING_LIMIT");
    }

    @Test
    void aConflictingAcceptIs409AndItsReplayIsTheSame409() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        UUID late = db.expired(world, start, 120);
        creation.create(world.request(world.customer.id(), second, start.plus(Duration.ofMinutes(30)), 60));
        mvc.perform(send(venue(world), late, UUID.randomUUID().toString())).andExpect(status().isOk());
        String key = UUID.randomUUID().toString();

        for (int i = 0; i < 2; i++) {
            mvc.perform(answer(customer(world), late, "accept", key))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("LATE_CONFIRM_CUSTOMER_CONFLICT"));
        }
        assertThat(history(late))
                .filteredOn(e -> "LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT".equals(e.eventType())).hasSize(1);
        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
    }

    @Test
    void theCustomerAcceptsOnceAndAReplayAnswersTheSame() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        mvc.perform(send(venue(world), late, UUID.randomUUID().toString())).andExpect(status().isOk());
        String key = UUID.randomUUID().toString();

        for (int i = 0; i < 2; i++) {
            mvc.perform(answer(customer(world), late, "accept", key))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.bookingId").value(late.toString()))
                    .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.data.terminalReason").isEmpty());
        }
        assertThat(events(IntegrationEvents.LATE_CONFIRM_ACCEPTED, late)).hasSize(1);
    }

    @Test
    void theCustomerDeclinesOnceAndAReplayAnswersTheSame() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        mvc.perform(send(venue(world), late, UUID.randomUUID().toString())).andExpect(status().isOk());
        String key = UUID.randomUUID().toString();

        for (int i = 0; i < 2; i++) {
            mvc.perform(answer(customer(world), late, "decline", key))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                    .andExpect(jsonPath("$.data.terminalReason").value("CUSTOMER_DECLINED_LATE_CONFIRM"));
        }
        assertThat(events(IntegrationEvents.LATE_CONFIRM_DECLINED, late)).hasSize(1);
    }

    /**
     * After T2 timed the booking out, an Accept or a Decline is a thrown 409 BOOKING_STATE_CHANGED: nothing is stored
     * under its key, so its replay is answered the same and changes nothing (E7-42).
     */
    @Test
    void anAnswerAfterT2TimedTheBookingOutIs409AndItsReplayIsTheSame() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        mvc.perform(send(venue(world), late, UUID.randomUUID().toString())).andExpect(status().isOk());
        LateConfirmDb.deadlinePassed(late);
        assertThat(fireT2(late)).isInstanceOf(JobOutcome.Done.class);

        for (String action : new String[] {"accept", "decline"}) {
            String key = UUID.randomUUID().toString();
            for (int i = 0; i < 2; i++) {
                mvc.perform(answer(customer(world), late, action, key))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.error.code").value("BOOKING_STATE_CHANGED"));
            }
            assertThat(VenueAnswerDb.idempotencyRecords(world.customer.id(), key)).as(action).isZero();
        }
        assertThat(booking(late).status()).isEqualTo("CANCELLED");
        assertThat(booking(late).terminalReason()).isEqualTo("CUSTOMER_RECONFIRM_TIMEOUT");
        assertThat(attempt(late).orElseThrow().response()).isEqualTo("TIMED_OUT");
        assertThat(events(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, late)).hasSize(1);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_ACCEPTED, late)).isEmpty();
        assertThat(events(IntegrationEvents.LATE_CONFIRM_DECLINED, late)).isEmpty();
    }

    /** A Decline committed before the deadline is replayed from its stored answer even after T2 has run (superseded). */
    @Test
    void aCommittedDeclineIsReplayedTheSameAfterT2Ran() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        mvc.perform(send(venue(world), late, UUID.randomUUID().toString())).andExpect(status().isOk());
        String key = UUID.randomUUID().toString();
        JsonNode first = body(mvc.perform(answer(customer(world), late, "decline", key))
                .andExpect(status().isOk()).andReturn());

        assertThat(fireT2(late)).isInstanceOf(JobOutcome.Superseded.class);

        JsonNode replay = body(mvc.perform(answer(customer(world), late, "decline", key))
                .andExpect(status().isOk()).andReturn());
        assertThat(replay.get("data")).isEqualTo(first.get("data"));
        assertThat(booking(late).terminalReason()).isEqualTo("CUSTOMER_DECLINED_LATE_CONFIRM");
        assertThat(events(IntegrationEvents.LATE_CONFIRM_DECLINED, late)).hasSize(1);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, late)).isEmpty();
    }

    @Test
    void acceptWhilePausedIsRefusedAndNothingIsStoredUnderTheKey() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        mvc.perform(send(venue(world), late, UUID.randomUUID().toString())).andExpect(status().isOk());
        LateConfirmDb.lateConfirmPaused(late);
        VenueAnswerDb.resourceEmergency(world.venue.id(), world.room.id());
        String key = UUID.randomUUID().toString();

        mvc.perform(answer(customer(world), late, "accept", key))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_SELLING_BLOCK_ACTIVE"));
        assertThat(VenueAnswerDb.idempotencyRecords(world.customer.id(), key)).as("nothing stored").isZero();

        mvc.perform(answer(customer(world), late, "decline", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    void everyRouteRequiresTheIdempotencyKeyItsOwnPrincipalAndItsOwnBooking() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        OnlineBookingWorld other = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);

        mvc.perform(send(venue(world), late, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value(OpenApiConfig.IDEMPOTENCY_KEY));
        mvc.perform(send(customer(world), late, UUID.randomUUID().toString())).andExpect(status().isForbidden());
        mvc.perform(send(null, late, UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        mvc.perform(send(venue(other), late, UUID.randomUUID().toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        assertThat(attempts(late)).isZero();

        mvc.perform(send(venue(world), late, UUID.randomUUID().toString())).andExpect(status().isOk());
        String stranger = accessTokens.issue(AuthSubjectType.CUSTOMER, world.anotherCustomer());
        for (String action : new String[] {"accept", "decline"}) {
            mvc.perform(answer(customer(world), late, action, null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.field").value(OpenApiConfig.IDEMPOTENCY_KEY));
            mvc.perform(answer(venue(world), late, action, UUID.randomUUID().toString()))
                    .andExpect(status().isForbidden());
            mvc.perform(answer(null, late, action, UUID.randomUUID().toString()))
                    .andExpect(status().isUnauthorized());
            mvc.perform(answer(stranger, late, action, UUID.randomUUID().toString()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        }
        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
    }

    // ---------------------------------------------------------------- Phase 2B.1 (decisions Q1-Q4, 13 Oct 2026)

    @Test
    void aTakenRoomAtInitiateIsACommitted409AndItsReplayIsTheSameWithOneEvent() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        UUID late = db.expired(world, start, 60);
        creation.create(world.request(world.anotherCustomer(), world.room, start, 60));
        String key = UUID.randomUUID().toString();

        for (int i = 0; i < 2; i++) {
            mvc.perform(send(venue(world), late, key))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("LATE_CONFIRM_RESOURCE_CONFLICT"))
                    .andExpect(jsonPath("$.error.details").doesNotExist());
        }
        assertThat(history(late))
                .filteredOn(e -> "LATE_CONFIRM_FAILED_RESOURCE_CONFLICT".equals(e.eventType())).hasSize(1);
        assertThat(booking(late).status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(attempts(late)).isZero();
        assertThat(VenueAnswerDb.idempotencyRecords(world.account.id(), key)).isEqualTo(1);
    }

    @Test
    void aTakenIntervalAtAcceptIsACommitted409AndItsReplayIsTheSameWithOneEvent() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        UUID late = db.expired(world, start, 60);
        mvc.perform(send(venue(world), late, UUID.randomUUID().toString())).andExpect(status().isOk());
        LateConfirmDb.emergencyIntervalOnly(world.venue.id(), world.room.id(), start.plus(Duration.ofMinutes(30)));
        String key = UUID.randomUUID().toString();

        for (int i = 0; i < 2; i++) {
            mvc.perform(answer(customer(world), late, "accept", key))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("LATE_CONFIRM_RESOURCE_CONFLICT"));
        }
        assertThat(history(late))
                .filteredOn(e -> "LATE_CONFIRM_FAILED_RESOURCE_CONFLICT".equals(e.eventType())).hasSize(1);
        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(attempt(late).orElseThrow().closedAt()).isNull();
    }

    /** D11 of E7-47: a lost hold is a 500 with no body code, everything rolled back, no idempotency record. */
    @Test
    void aLostHoldAnswers500AndStoresNothingUnderTheKey() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        mvc.perform(send(venue(world), late, UUID.randomUUID().toString())).andExpect(status().isOk());
        LateConfirmDb.holdLost(late);
        int historyBefore = history(late).size();
        String key = UUID.randomUUID().toString();

        mvc.perform(answer(customer(world), late, "accept", key))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").doesNotExist());

        assertThat(VenueAnswerDb.idempotencyRecords(world.customer.id(), key)).isZero();
        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(history(late)).hasSize(historyBefore);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_ACCEPTED, late)).isEmpty();
    }

    /**
     * FR-LCF-18, E7-49 D1: past expiry + 2 h both phones are hidden; the real Initiate reopens them through AWAITING,
     * and the real Accept keeps them.
     */
    @Test
    void aLateConfirmReopensBothPhonesAndAcceptKeepsThem() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        LateConfirmDb.expiredHoursAgo(late, 3);
        assertThat(customerPhoneSeenByVenue(world, late)).as("hidden after expiry + 2 h").isFalse();
        assertThat(venuePhoneSeenByCustomer(world, late)).isFalse();

        mvc.perform(send(venue(world), late, UUID.randomUUID().toString())).andExpect(status().isOk());
        assertThat(customerPhoneSeenByVenue(world, late)).as("reopened by AWAITING").isTrue();
        assertThat(venuePhoneSeenByCustomer(world, late)).isTrue();

        mvc.perform(answer(customer(world), late, "accept", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        assertThat(customerPhoneSeenByVenue(world, late)).as("restored on Accept").isTrue();
        assertThat(venuePhoneSeenByCustomer(world, late)).isTrue();
    }

    @Test
    void theLateConfirmEventsCarryTheVenueNameAndNoCustomerDetail() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID accepted = db.expired(world, world.start(), 60);
        UUID declined = db.expired(world, world.start().plus(Duration.ofHours(2)), 60);
        for (UUID late : new UUID[] {accepted, declined}) {
            mvc.perform(send(venue(world), late, UUID.randomUUID().toString())).andExpect(status().isOk());
        }
        mvc.perform(answer(customer(world), accepted, "accept", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        mvc.perform(answer(customer(world), declined, "decline", UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        assertPayload(events(IntegrationEvents.LATE_CONFIRM_INITIATED, accepted), world, accepted,
                "lateConfirmExpiresAt");
        assertPayload(events(IntegrationEvents.LATE_CONFIRM_ACCEPTED, accepted), world, accepted);
        assertPayload(events(IntegrationEvents.LATE_CONFIRM_DECLINED, declined), world, declined, "terminalReason");
    }

    // ---------------------------------------------------------------- Phase 2C: the E7-54 projections

    /**
     * Sprint 3 contract (E7-54): while AWAITING the customer's detail carries {@code lateConfirm {expiresAt, paused}}
     * and the venue's booking {@code lateConfirmExpiresAt} / {@code lateConfirmPaused} - the stored deadline and the
     * flag only, never the pause's cause; once answered, nothing.
     */
    @Test
    void theOpenLateConfirmationIsShownWithItsPauseFlagButNeverItsCause() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        mvc.perform(send(venue(world), late, UUID.randomUUID().toString())).andExpect(status().isOk());
        Instant expires = attempt(late).orElseThrow().expiresAt();

        JsonNode lateConfirm = customerDetail(world, late).at("/data/lateConfirm");
        List<String> fields = new ArrayList<>();
        lateConfirm.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("expiresAt", "paused");
        assertThat(Instant.parse(lateConfirm.get("expiresAt").asText())).isEqualTo(expires);
        assertThat(lateConfirm.get("paused").asBoolean()).isFalse();
        JsonNode venueBooking = venueDetail(world, late).at("/data/booking");
        assertThat(Instant.parse(venueBooking.get("lateConfirmExpiresAt").asText())).isEqualTo(expires);
        assertThat(venueBooking.get("lateConfirmPaused").asBoolean()).isFalse();
        JsonNode listed = venueListItem(world, late);
        assertThat(Instant.parse(listed.get("lateConfirmExpiresAt").asText())).isEqualTo(expires);
        assertThat(listed.get("lateConfirmPaused").asBoolean()).isFalse();

        LateConfirmDb.lateConfirmPaused(late);
        JsonNode customer = customerDetail(world, late);
        JsonNode venue = venueDetail(world, late);
        assertThat(customer.at("/data/lateConfirm/paused").asBoolean()).isTrue();
        assertThat(venue.at("/data/booking/lateConfirmPaused").asBoolean()).isTrue();
        assertThat(venueListItem(world, late).get("lateConfirmPaused").asBoolean()).isTrue();
        assertThat(customer.toString() + venue).as("the pause's cause is never shown").doesNotContain("E7-58 test")
                .doesNotContain("pauseCause");

        mvc.perform(answer(customer(world), late, "decline", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        assertThat(customerDetail(world, late).at("/data/lateConfirm").isNull()).isTrue();
        assertThat(venueDetail(world, late).at("/data/booking/lateConfirmExpiresAt").isNull()).isTrue();
        assertThat(venueDetail(world, late).at("/data/booking/lateConfirmPaused").asBoolean()).isFalse();
    }

    @Test
    void anAcceptedLateConfirmationLeavesNoLateConfirmFields() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        mvc.perform(send(venue(world), late, UUID.randomUUID().toString())).andExpect(status().isOk());
        mvc.perform(answer(customer(world), late, "accept", UUID.randomUUID().toString()))
                .andExpect(status().isOk());

        assertThat(customerDetail(world, late).at("/data/lateConfirm").isNull()).isTrue();
        assertThat(venueDetail(world, late).at("/data/booking/lateConfirmExpiresAt").isNull()).isTrue();
        assertThat(venueDetail(world, late).at("/data/booking/status").asText()).isEqualTo("CONFIRMED");
    }

    private JsonNode customerDetail(OnlineBookingWorld world, UUID bookingId) throws Exception {
        return body(mvc.perform(get(CUSTOMER_BOOKINGS + bookingId)
                .header("Authorization", "Bearer " + customer(world))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode venueDetail(OnlineBookingWorld world, UUID bookingId) throws Exception {
        return body(mvc.perform(get(VENUE_BOOKINGS + bookingId)
                .header("Authorization", "Bearer " + venue(world))).andExpect(status().isOk()).andReturn());
    }

    private JsonNode venueListItem(OnlineBookingWorld world, UUID bookingId) throws Exception {
        JsonNode list = body(mvc.perform(get("/api/v1/venue/bookings")
                        .param("status", "AWAITING_CUSTOMER_CONFIRMATION")
                        .header("Authorization", "Bearer " + venue(world)))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode item : list.at("/data/items")) {
            if (item.get("bookingId").asText().equals(bookingId.toString())) {
                return item;
            }
        }
        throw new AssertionError("booking " + bookingId + " not in the venue's AWAITING list: " + list);
    }

    private void assertPayload(List<String> payloads, OnlineBookingWorld world, UUID bookingId,
                               String... extra) throws Exception {
        assertThat(payloads).hasSize(1);
        JsonNode payload = json.readTree(payloads.getFirst());
        List<String> fields = new ArrayList<>(List.of("bookingId", "customerId",
                "venueId", "venueName", "resourceId", "startAt", "endAt"));
        fields.addAll(List.of(extra));
        List<String> actual = new ArrayList<>();
        payload.fieldNames().forEachRemaining(actual::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(fields);
        assertThat(payload.get("bookingId").asText()).isEqualTo(bookingId.toString());
        assertThat(payload.get("venueName").asText()).isEqualTo(world.venue.name());
    }

    private boolean customerPhoneSeenByVenue(OnlineBookingWorld world, UUID bookingId) throws Exception {
        JsonNode detail = body(mvc.perform(get(VENUE_BOOKINGS + bookingId)
                .header("Authorization", "Bearer " + venue(world))).andExpect(status().isOk()).andReturn());
        JsonNode customer = detail.at("/data/booking/customer");
        assertThat(customer.has("username")).as("the customer object is there").isTrue();
        return customer.has("phone");
    }

    private boolean venuePhoneSeenByCustomer(OnlineBookingWorld world, UUID bookingId) throws Exception {
        JsonNode detail = body(mvc.perform(get(CUSTOMER_BOOKINGS + bookingId)
                .header("Authorization", "Bearer " + customer(world))).andExpect(status().isOk()).andReturn());
        JsonNode venue = detail.at("/data/venue");
        assertThat(venue.has("name")).as("the venue object is there").isTrue();
        return venue.has("phone");
    }

    // ---------------------------------------------------------------- helpers

    /** T2's decision in one command under the booking's locks (the worker profile is off in tests). */
    private JobOutcome fireT2(UUID bookingId) {
        ScheduledJob job = new ScheduledJob(UUID.randomUUID(), "LATE_CONFIRM_EXPIRY", "BOOKING", bookingId,
                Instant.EPOCH, 1, LateConfirmDb.t2(bookingId), CorrelationId.generate());
        return runner.run(TestCommands.under(CommandScope.shared(lateConfirmations.lockSetOf(bookingId)),
                command -> lateConfirmations.expire(command, job)));
    }

    private String venue(OnlineBookingWorld world) {
        return accessTokens.issue(AuthSubjectType.VENUE, world.account.id());
    }

    private String customer(OnlineBookingWorld world) {
        return accessTokens.issue(AuthSubjectType.CUSTOMER, world.customer.id());
    }

    private static MockHttpServletRequestBuilder send(String token, UUID bookingId, String key) {
        return authorised(post(VENUE_BOOKINGS + bookingId + "/late-confirm"), token, key);
    }

    private static MockHttpServletRequestBuilder answer(String token, UUID bookingId, String action, String key) {
        return authorised(post(CUSTOMER_BOOKINGS + bookingId + "/late-confirm/" + action), token, key);
    }

    private static MockHttpServletRequestBuilder authorised(MockHttpServletRequestBuilder request, String token,
                                                            String key) {
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
