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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import ps.e7gz.audit.AuditFixtures;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.concurrency.ConcurrentRace;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.trust.BanFixtures;

/**
 * E7-46 joined to E7-41 and E7-42 - {@code POST /api/v1/customer/bookings} over HTTP, through the real security chain,
 * Idempotency-Key handling, outbox and database (ADR-015, arch 08 section 6; PO decision 9 Oct 2026): one booking per
 * (customer, key), its events written once, a replay that runs nothing, a changed body refused, a refusal that leaves
 * no record, and the venue phone left out of a replay once the retention rule hides it (arch 10 section 5.1).
 */
@AutoConfigureMockMvc
class CustomerBookingCreateHttpTest extends IntegrationTest {

    static final String PATH = "/api/v1/customer/bookings";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    // ------------------------------------------------------------------------------------------------ first request

    @Test
    void aNewRequestCreatesOnePendingBookingWithItsEventsAndKeyRecord() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        String key = UUID.randomUUID().toString();

        JsonNode data = body(mvc.perform(create(customer(world), key, body(world, start, 120)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.meta.correlationId").isNotEmpty())
                .andReturn()).get("data");

        UUID bookingId = UUID.fromString(data.get("bookingId").asText());
        assertThat(data.get("status").asText()).isEqualTo("PENDING");
        assertThat(data.get("displayBucket").asText()).isEqualTo("upcoming");
        assertThat(data.get("terminalReason").isNull()).isTrue();
        assertThat(data.at("/venue/id").asText()).isEqualTo(world.venue.id().toString());
        assertThat(data.at("/venue/name").asText()).isEqualTo(world.venue.name());
        assertThat(data.at("/venue/phone").asText()).as("open while PENDING (FR-DIS-09)").isEqualTo(venuePhone(world));
        assertThat(data.at("/resource/name").asText()).isEqualTo(world.room.name());
        assertThat(data.at("/resource/consoleType").asText()).isEqualTo("PS5");
        assertThat(data.at("/resource/playMode").asText()).isEqualTo("SINGLE");
        assertThat(Instant.parse(data.get("startAt").asText())).isEqualTo(start);
        assertThat(Instant.parse(data.get("endAt").asText())).isEqualTo(start.plus(Duration.ofMinutes(120)));
        assertThat(data.get("nominalBookedMinutes").asInt()).isEqualTo(120);
        assertThat(data.at("/venuePriceTotal/amount").asText()).isEqualTo("155.54");
        assertThat(data.get("venueResponsePaused").asBoolean()).isFalse();
        assertThat(data.get("history")).hasSize(1);
        assertThat(data.at("/history/0/toStatus").asText()).isEqualTo("PENDING");

        // the booking, its reservation and both events exist exactly once (PO decision 9 Oct 2026)
        assertThat(count("booking.booking WHERE customer_id = :id", world.customer.id())).isEqualTo(1);
        assertThat(count("inventory.resource_reservation WHERE booking_id = :id", bookingId)).isEqualTo(1);
        Map<String, Object> created = OWNER.sql("""
                        SELECT payload->>'customerId' AS customer, payload->>'venueId' AS venue,
                               payload->>'resourceId' AS resource, payload->>'startAt' AS start_at, correlation_id
                          FROM technical.outbox_event
                         WHERE event_type = 'BookingCreated' AND aggregate_type = 'BOOKING' AND aggregate_id = :id""")
                .param("id", bookingId).query().singleRow();
        assertThat(created).containsEntry("customer", world.customer.id().toString())
                .containsEntry("venue", world.venue.id().toString())
                .containsEntry("resource", world.room.id().toString())
                .containsEntry("start_at", start.toString());
        Map<String, Object> held = OWNER.sql("""
                        SELECT payload->>'venueId' AS venue, payload#>>'{period,start}' AS period_start,
                               payload#>>'{period,end}' AS period_end,
                               jsonb_typeof(payload->'venueId') AS venue_type
                          FROM technical.outbox_event e
                          JOIN inventory.resource_reservation rr ON rr.id = e.aggregate_id
                         WHERE e.event_type = 'ReservationHeld' AND rr.booking_id = :id""")
                .param("id", bookingId).query().singleRow();
        // NearestAvailabilityEvents reads it with OutboxEvent.uuid, which requires a JSON string
        assertThat(held).containsEntry("venue", world.venue.id().toString())
                .containsEntry("venue_type", "string")
                .containsEntry("period_start", start.toString())
                .containsEntry("period_end", start.plus(Duration.ofMinutes(120)).toString());

        // the key's record: completed with the BOOKING aggregate, storing the data only (never the meta)
        Map<String, Object> record = OWNER.sql("""
                        SELECT state, response_status, result_aggregate_type, result_aggregate_id,
                               jsonb_exists(response_body, 'meta') AS has_meta,
                               response_body #>> '{data,bookingId}' AS stored_id
                          FROM technical.idempotency_record
                         WHERE principal_id = :customer AND idempotency_key = :key""")
                .param("customer", world.customer.id()).param("key", key).query().singleRow();
        assertThat(record).containsEntry("state", "COMPLETED").containsEntry("result_aggregate_type", "BOOKING")
                .containsEntry("result_aggregate_id", bookingId).containsEntry("has_meta", false)
                .containsEntry("stored_id", bookingId.toString());
        assertThat(((Number) record.get("response_status")).intValue()).isEqualTo(201);
    }

    // -------------------------------------------------------------------------------------------------------- replay

    @Test
    void anIdenticalReplayReturnsTheSameBookingAndWritesNothing() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        String key = UUID.randomUUID().toString();
        JsonNode first = body(mvc.perform(create(customer(world), key, body(world, start, 120)))
                .andExpect(status().isCreated()).andReturn());
        UUID bookingId = UUID.fromString(first.at("/data/bookingId").asText());
        long[] before = writes(world, bookingId);

        // the same canonical request: members reordered, key in upper case
        String reordered = """
                {"durationMinutes": 120, "startAt": "%s", "playMode": "SINGLE", "resourceId": "%s"}"""
                .formatted(start, world.room.id());
        JsonNode replay = body(mvc.perform(create(customer(world), key.toUpperCase(), reordered))
                .andExpect(status().isCreated()).andReturn());

        assertThat(replay.get("data")).isEqualTo(first.get("data"));
        assertThat(replay.at("/meta/correlationId")).isNotEqualTo(first.at("/meta/correlationId"));
        assertThat(writes(world, bookingId)).as("no booking, reservation, history, job or event is written again")
                .isEqualTo(before);
    }

    @Test
    void theSameKeyWithAnotherBodyIsIdempotencyKeyReusedAndCreatesNothing() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        String key = UUID.randomUUID().toString();
        JsonNode first = body(mvc.perform(create(customer(world), key, body(world, start, 120)))
                .andExpect(status().isCreated()).andReturn());
        UUID bookingId = UUID.fromString(first.at("/data/bookingId").asText());
        long[] before = writes(world, bookingId);

        mvc.perform(create(customer(world), key, body(world, start.plus(Duration.ofHours(3)), 60)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(writes(world, bookingId)).isEqualTo(before);
        assertThat(count("booking.booking WHERE customer_id = :id", world.customer.id())).isEqualTo(1);
    }

    // ----------------------------------------------------------------------------------------- the key is required

    @Test
    void aMissingKeyIsValidationFailedAndCreatesNothing() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();

        mvc.perform(create(customer(world), null, body(world, world.start(), 120)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value(OpenApiConfig.IDEMPOTENCY_KEY));

        assertThat(count("booking.booking WHERE customer_id = :id", world.customer.id())).isZero();
        assertThat(count("technical.idempotency_record WHERE principal_id = :id", world.customer.id())).isZero();
    }

    // ------------------------------------------------------------------------------------------ rollback and retry

    @Test
    void aRefusedRequestKeepsNoRecordNoEventAndTheSameKeyMayBeRetried() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.builder().unverifiedCustomer().create();
        String key = UUID.randomUUID().toString();
        String request = body(world, world.start(), 120);

        mvc.perform(create(customer(world), key, request))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PHONE_VERIFICATION_REQUIRED"));

        assertThat(count("booking.booking WHERE customer_id = :id", world.customer.id())).isZero();
        assertThat(count("inventory.resource_reservation WHERE resource_id = :id", world.room.id())).isZero();
        assertThat(count("technical.idempotency_record WHERE principal_id = :id", world.customer.id()))
                .as("the record rolled back with the command").isZero();
        assertThat(count("technical.outbox_event WHERE payload->>'customerId' = CAST(:id AS text)",
                world.customer.id())).isZero();
        assertThat(count("technical.outbox_event WHERE payload->>'resourceId' = CAST(:id AS text)", world.room.id()))
                .isZero();

        OWNER.sql("UPDATE iam.user_account SET phone_verified = true, phone_verified_at = now() WHERE id = :id")
                .param("id", world.customer.id()).update();
        mvc.perform(create(customer(world), key, request)).andExpect(status().isCreated());

        assertThat(count("booking.booking WHERE customer_id = :id", world.customer.id())).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ who may create

    @Test
    void theKeyBelongsToItsCustomerAndAnotherCustomerNeverGetsTheStoredBooking() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String key = UUID.randomUUID().toString();
        String request = body(world, world.start(), 120);
        mvc.perform(create(customer(world), key, request)).andExpect(status().isCreated());

        String other = accessTokens.issue(AuthSubjectType.CUSTOMER, world.anotherCustomer());
        mvc.perform(create(other, key, request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SLOT_NO_LONGER_AVAILABLE"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void onlyAnAuthenticatedCustomerMayCreate() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String venue = accessTokens.issue(AuthSubjectType.VENUE,
                Fixtures.aVenueAccount().forVenue(Fixtures.aVenue().active().create()).create().id());
        String request = body(world, world.start(), 120);

        mvc.perform(create(null, UUID.randomUUID().toString(), request)).andExpect(status().isUnauthorized());
        mvc.perform(create(venue, UUID.randomUUID().toString(), request)).andExpect(status().isForbidden());

        assertThat(count("inventory.resource_reservation WHERE resource_id = :id", world.room.id())).isZero();
    }

    // ------------------------------------------------------------------------------------------------- concurrency

    @Test
    void concurrentIdenticalRequestsCreateOneBookingAndEveryoneGetsIt() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String token = customer(world);
        String key = UUID.randomUUID().toString();
        String request = body(world, world.start(), 120);

        ConcurrentRace.Result<MvcResult> race = ConcurrentRace.run(5, Duration.ofSeconds(60), (racer, start) -> {
            start.await();
            return mvc.perform(create(token, key, request)).andReturn();
        });

        assertThat(race.failures()).isEmpty();
        assertThat(race.values()).extracting(result -> result.getResponse().getStatus()).containsOnly(201);
        assertThat(race.values()).extracting(result -> body(result).at("/data/bookingId").asText())
                .as("one booking, returned to every racer").hasSize(5).containsOnly(
                        body(race.values().get(0)).at("/data/bookingId").asText());
        assertThat(count("booking.booking WHERE customer_id = :id", world.customer.id())).isEqualTo(1);
        assertThat(count("inventory.resource_reservation WHERE resource_id = :id", world.room.id())).isEqualTo(1);
        assertThat(count("technical.outbox_event WHERE event_type = 'BookingCreated' "
                + "AND payload->>'customerId' = CAST(:id AS text)", world.customer.id())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- a replay after the world changed around it

    /**
     * A replay returns the stored result without re-checking the twenty creation conditions (ADR-015, arch 08 section
     * 6): what changed after the booking - the venue suspended, the room disabled, the customer banned - refuses a new
     * request but not the replay of the one that already succeeded, and the replay writes nothing. The phone projection
     * still runs on it.
     */
    @Test
    void anIdenticalReplaySucceedsAfterTheVenueRoomAndCustomerBecameIneligible() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String key = UUID.randomUUID().toString();
        String request = body(world, world.start(), 120);
        JsonNode first = body(mvc.perform(create(customer(world), key, request))
                .andExpect(status().isCreated()).andReturn());
        UUID bookingId = UUID.fromString(first.at("/data/bookingId").asText());

        // the venue: the real admin command (FR-VEN-07), with its own audit record and outbox event
        String admin = accessTokens.issue(AuthSubjectType.ADMIN, AuditFixtures.admin());
        mvc.perform(post("/api/v1/admin/venues/" + world.venue.id() + "/suspend")
                        .header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"E7-46 replay regression\"}"))
                .andExpect(status().isOk());
        // the room: TEST-ONLY direct write. No database guard refuses it, but the Disable command does while this
        // future booking holds the room (FR-VEN-14, ResourceCommands.refuseWhenBlocked), so no API reaches this state.
        OWNER.sql("UPDATE resource.resource SET base_state = 'DISABLED' WHERE id = :id")
                .param("id", world.room.id()).update();
        // the customer: the shared Ban fixture (no Trust command produces a Ban yet)
        BanFixtures.activeBan(world.customer.id());

        // the same body under a new key is now refused, and writes nothing
        mvc.perform(create(customer(world), UUID.randomUUID().toString(), request))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("ACCOUNT_BANNED_FOR_THIS_ACTION"));
        long[] before = writes(world, bookingId);

        JsonNode replay = body(mvc.perform(create(customer(world), key, request))
                .andExpect(status().isCreated()).andReturn());

        assertThat(replay.get("data")).as("the stored data, phone included while PENDING")
                .isEqualTo(first.get("data"));
        assertThat(writes(world, bookingId)).as("no booking, reservation, history, job, event or key record")
                .isEqualTo(before);
        UUID replayCorrelation = UUID.fromString(replay.at("/meta/correlationId").asText());
        assertThat(count("audit.audit_event WHERE correlation_id = :id", replayCorrelation)).isZero();
        assertThat(count("technical.outbox_event WHERE correlation_id = :id", replayCorrelation)).isZero();

        // the contact visibility projection still applies on top of the changed world (arch 10 section 5.1)
        cancelledBeforeStart(bookingId, "3 hours");
        JsonNode hidden = body(mvc.perform(create(customer(world), key, request))
                .andExpect(status().isCreated()).andReturn());
        assertThat(hidden.get("data")).isEqualTo(withoutVenuePhone(first.get("data")));
        assertThat(writes(world, bookingId)).isEqualTo(before);
    }

    // ------------------------------------------------------------------------------- the venue phone on a replay

    @Test
    void aReplayBeforeTheRetentionPointStillShowsTheVenuePhone() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String key = UUID.randomUUID().toString();
        String request = body(world, world.start(), 120);
        JsonNode first = body(mvc.perform(create(customer(world), key, request)).andReturn());

        // cancelled before its start, an hour ago: visible until terminalAt + 2h (FR-PTS-20)
        cancelledBeforeStart(UUID.fromString(first.at("/data/bookingId").asText()), "1 hour");

        JsonNode replay = body(mvc.perform(create(customer(world), key, request))
                .andExpect(status().isCreated()).andReturn());
        assertThat(replay.get("data")).isEqualTo(first.get("data"));
        assertThat(replay.at("/data/venue/phone").asText()).isEqualTo(venuePhone(world));
    }

    @Test
    void aReplayAfterTerminalAtPlusTwoHoursLeavesTheVenuePhoneOut() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String key = UUID.randomUUID().toString();
        String request = body(world, world.start(), 120);
        JsonNode first = body(mvc.perform(create(customer(world), key, request)).andReturn());
        long[] before = writes(world, UUID.fromString(first.at("/data/bookingId").asText()));

        cancelledBeforeStart(UUID.fromString(first.at("/data/bookingId").asText()), "3 hours");

        JsonNode replay = body(mvc.perform(create(customer(world), key, request))
                .andExpect(status().isCreated()).andReturn());
        assertThat(replay.at("/data/venue").has("phone")).as("absent - not null, not masked (NFR-18)").isFalse();
        assertThat(replay.get("data")).as("only the phone is left out").isEqualTo(withoutVenuePhone(first.get("data")));
        assertThat(writes(world, UUID.fromString(first.at("/data/bookingId").asText()))).isEqualTo(before);
    }

    @Test
    void aReplayAfterVenueResponseExpiredAtPlusTwoHoursLeavesTheVenuePhoneOut() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        String key = UUID.randomUUID().toString();
        String request = body(world, world.start(), 120);
        JsonNode first = body(mvc.perform(create(customer(world), key, request)).andReturn());

        expired(UUID.fromString(first.at("/data/bookingId").asText()), "3 hours");

        JsonNode replay = body(mvc.perform(create(customer(world), key, request))
                .andExpect(status().isCreated()).andReturn());
        assertThat(replay.get("data")).isEqualTo(withoutVenuePhone(first.get("data")));
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    private String customer(OnlineBookingWorld world) {
        return accessTokens.issue(AuthSubjectType.CUSTOMER, world.customer.id());
    }

    private static String body(OnlineBookingWorld world, Instant start, int minutes) {
        return """
                {"resourceId": "%s", "playMode": "SINGLE", "startAt": "%s", "durationMinutes": %d}"""
                .formatted(world.room.id(), start, minutes);
    }

    private static MockHttpServletRequestBuilder create(String token, String key, String body) {
        MockHttpServletRequestBuilder request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(body);
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

    private static JsonNode withoutVenuePhone(JsonNode data) {
        JsonNode copy = data.deepCopy();
        ((ObjectNode) copy.get("venue")).remove("phone");
        return copy;
    }

    private static String venuePhone(OnlineBookingWorld world) {
        return OWNER.sql("SELECT phone FROM venue.venue WHERE id = :id").param("id", world.venue.id())
                .query(String.class).single();
    }

    /** Bookings, reservations, history rows, jobs and both events of the world, and the customer's key records. */
    private static long[] writes(OnlineBookingWorld world, UUID bookingId) {
        return new long[] {
                count("booking.booking WHERE customer_id = :id", world.customer.id()),
                count("inventory.resource_reservation WHERE resource_id = :id", world.room.id()),
                count("booking.booking_history WHERE booking_id = :id", bookingId),
                count("technical.scheduled_job WHERE subject_id = :id", bookingId),
                count("technical.outbox_event WHERE event_type = 'BookingCreated' AND aggregate_id = :id", bookingId),
                count("technical.outbox_event WHERE event_type = 'ReservationHeld' "
                        + "AND payload->>'resourceId' = CAST(:id AS text)", world.room.id()),
                count("technical.idempotency_record WHERE principal_id = :id", world.customer.id())};
    }

    private static long count(String fromWhere, UUID id) {
        return OWNER.sql("SELECT count(*) FROM " + fromWhere).param("id", id).query(Long.class).single();
    }

    /**
     * TEST-ONLY: moves the booking to a later state the lifecycle tasks (E7-47/48/49) will write, with its times in the
     * past - which no command can do - by bypassing the triggers ({@code session_replication_role = replica}, as
     * {@code ErrorEnvelopeHttpTest} does). Every CHECK still applies.
     */
    private static void cancelledBeforeStart(UUID bookingId, String ago) {
        withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE booking.booking
                           SET status = 'CANCELLED', terminal_reason = 'CUSTOMER_CANCELLED',
                               fee_treatment = 'CUSTOMER_POLICY', terminal_at = now() - CAST(:ago AS interval),
                               commitment_period = NULL
                         WHERE id = :id""")
                .param("ago", ago).param("id", bookingId).update());
    }

    /** The same for VENUE_RESPONSE_EXPIRED, whose instant is its history transition (FR-PTS-20). */
    private static void expired(UUID bookingId, String ago) {
        withoutTriggers(jdbc -> {
            jdbc.sql("UPDATE booking.booking SET status = 'VENUE_RESPONSE_EXPIRED', commitment_period = NULL "
                    + "WHERE id = :id").param("id", bookingId).update();
            jdbc.sql("""
                            INSERT INTO booking.booking_history (id, booking_id, sequence, occurred_at, entry_kind,
                                                                 from_status, to_status, actor_type, correlation_id)
                            VALUES (gen_random_uuid(), :id, 2, now() - CAST(:ago AS interval), 'STATUS_TRANSITION',
                                    'PENDING', 'VENUE_RESPONSE_EXPIRED', 'SYSTEM', gen_random_uuid())""")
                    .param("id", bookingId).param("ago", ago).update();
        });
    }

    private static void withoutTriggers(Consumer<JdbcClient> writes) {
        SingleConnectionDataSource owner = new SingleConnectionDataSource(
                PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR, true);
        try {
            JdbcClient jdbc = JdbcClient.create(owner);
            jdbc.sql("SET session_replication_role = replica").update();
            writes.accept(jdbc);
            jdbc.sql("SET session_replication_role = origin").update();
        } finally {
            owner.destroy();
        }
    }
}
