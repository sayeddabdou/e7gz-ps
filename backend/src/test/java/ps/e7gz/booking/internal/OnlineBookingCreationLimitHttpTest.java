package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.booking.internal.OnlineBookingWorld.OWNER;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-120 (arch 10 §7; PO decision 16 Nov 2026): one Customer creates at most 20 Online Bookings in the last 60 minutes,
 * counted exactly from {@code booking.booking}. The 21st is {@code 429 RATE_LIMITED} before every other creation
 * condition and writes nothing, so the venue gets no {@code BookingCreated}; a cancelled booking still counts, a replay
 * is not counted again, and Manual Bookings are never limited. Time moves by backdating {@code created_at} past the
 * triggers, because command instants come from the database clock.
 */
@AutoConfigureMockMvc
class OnlineBookingCreationLimitHttpTest extends IntegrationTest {

    static final String PATH = "/api/v1/customer/bookings";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    @Autowired
    ManualBookings manual;

    private CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    /** Done when: the 20th is accepted, the 21st is 429 with no venue request; 60 minutes after the first it is not. */
    @Test
    void theTwentyFirstBookingInSixtyMinutesIsRateLimitedUntilTheFirstIsSixtyMinutesOld() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String token = customer(world);
        Instant start = world.start();
        List<UUID> made = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID id = create(token, world, start, 60);
            cancel(token, id);                                       // a cancelled booking still counts
            made.add(id);
        }
        assertThat(bookingsOf(world)).isEqualTo(20);

        create(token, UUID.randomUUID().toString(), world, start, 60)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        assertThat(bookingsOf(world)).as("nothing written").isEqualTo(20);
        assertThat(bookingCreatedEventsOf(world)).as("the venue gets no request").isEqualTo(20);
        assertThat(keyRecordsOf(world)).as("no Idempotency-Key record").isEqualTo(40);

        createdAgo(made.get(0), "59 minutes");
        create(token, UUID.randomUUID().toString(), world, start, 60)
                .andExpect(status().isTooManyRequests());

        createdAgo(made.get(0), "60 minutes");
        UUID next = create(token, world, start, 60);
        assertThat(next).isNotNull();
        assertThat(bookingsOf(world)).isEqualTo(21);
    }

    /** Done when: the limit answers first, even when the 4-commitment limit (FR-BOK-03) would refuse too. */
    @Test
    void theLimitAnswersBeforeTheCommitmentLimit() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String token = customer(world);
        Instant start = world.start();
        UUID firstCancelled = null;
        for (int i = 0; i < 16; i++) {
            UUID id = create(token, world, start.plus(Duration.ofHours(4)), 60);
            cancel(token, id);
            firstCancelled = firstCancelled == null ? id : firstCancelled;
        }
        for (int i = 0; i < 4; i++) {                                // four kept: the commitment limit is reached
            create(token, world, start.plus(Duration.ofHours(i)), 60);
        }
        Instant fifth = start.plus(Duration.ofHours(5));

        create(token, UUID.randomUUID().toString(), world, fifth, 60)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));

        createdAgo(firstCancelled, "61 minutes");
        create(token, UUID.randomUUID().toString(), world, fifth, 60)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("COMMITMENT_LIMIT_REACHED"));
    }

    /** Done when: a replay with the same Idempotency-Key does not run the command, so it is not counted again. */
    @Test
    void aReplayIsNotCountedAgain() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String token = customer(world);
        Instant start = world.start();
        for (int i = 0; i < 19; i++) {
            cancel(token, create(token, world, start, 60));
        }
        String key = UUID.randomUUID().toString();
        UUID twentieth = id(create(token, key, world, start, 60).andExpect(status().isCreated()));

        UUID replayed = id(create(token, key, world, start, 60).andExpect(status().isCreated()));

        assertThat(replayed).isEqualTo(twentieth);
        assertThat(bookingsOf(world)).isEqualTo(20);
        cancel(token, twentieth);
        create(token, UUID.randomUUID().toString(), world, start, 60)
                .andExpect(status().isTooManyRequests());
    }

    /** Done when: a Manual Booking is never refused by this limit. */
    @Test
    void manualBookingsAreNeverLimited() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String token = customer(world);
        Instant start = world.start();
        for (int i = 0; i < 20; i++) {
            cancel(token, create(token, world, start, 60));
        }
        create(token, UUID.randomUUID().toString(), world, start, 60).andExpect(status().isTooManyRequests());

        for (int i = 0; i < 21; i++) {
            VenueBookingRow row = manual.create(world.account.id(),
                    ManualBookingRequest.of(world.room.id(), PlayMode.SINGLE, start, 60, null, null));
            manual.cancel(world.account.id(), row.bookingId());
        }

        assertThat(OWNER.sql("SELECT count(*) FROM booking.booking WHERE venue_id = :id AND source = 'MANUAL'")
                .param("id", world.venue.id()).query(Long.class).single()).isEqualTo(21);
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private String customer(OnlineBookingWorld world) {
        return accessTokens.issue(AuthSubjectType.CUSTOMER, world.customer.id());
    }

    private UUID create(String token, OnlineBookingWorld world, Instant start, int minutes) throws Exception {
        return id(create(token, UUID.randomUUID().toString(), world, start, minutes)
                .andExpect(status().isCreated()));
    }

    private ResultActions create(String token, String key, OnlineBookingWorld world, Instant start, int minutes)
            throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + token)
                .header(OpenApiConfig.IDEMPOTENCY_KEY, key)
                .content("""
                        {"resourceId": "%s", "playMode": "SINGLE", "startAt": "%s", "durationMinutes": %d}"""
                        .formatted(world.room.id(), start, minutes)));
    }

    private void cancel(String token, UUID bookingId) throws Exception {
        mvc.perform(post(PATH + "/" + bookingId + "/cancel")
                        .header("Authorization", "Bearer " + token)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString()))
                .andExpect(status().isOk());
    }

    private UUID id(ResultActions result) {
        return UUID.fromString(body(result.andReturn()).at("/data/bookingId").asText());
    }

    private JsonNode body(MvcResult result) {
        try {
            return json.readTree(result.getResponse().getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long bookingsOf(OnlineBookingWorld world) {
        return count("booking.booking WHERE customer_id = :id", world.customer.id());
    }

    private static long bookingCreatedEventsOf(OnlineBookingWorld world) {
        return count("technical.outbox_event WHERE event_type = 'BookingCreated' "
                + "AND payload->>'customerId' = CAST(:id AS text)", world.customer.id());
    }

    private static long keyRecordsOf(OnlineBookingWorld world) {
        return count("technical.idempotency_record WHERE principal_id = :id", world.customer.id());
    }

    private static long count(String fromWhere, UUID id) {
        return OWNER.sql("SELECT count(*) FROM " + fromWhere).param("id", id).query(Long.class).single();
    }

    /**
     * TEST-ONLY: puts the booking's {@code created_at} {@code ago} before the database clock, past the trigger that
     * keeps it fixed ({@code session_replication_role = replica}, as {@code CustomerBookingCreateHttpTest} does).
     */
    private static void createdAgo(UUID bookingId, String ago) {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(
                PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            jdbc.sql("SET session_replication_role = replica").update();
            int changed = jdbc.sql("UPDATE booking.booking SET created_at = clock_timestamp() - CAST(:ago AS interval) "
                            + "WHERE id = :id")
                    .param("ago", ago).param("id", bookingId).update();
            jdbc.sql("SET session_replication_role = origin").update();
            assertThat(changed).isEqualTo(1);
        } finally {
            owner.destroy();
        }
    }
}
