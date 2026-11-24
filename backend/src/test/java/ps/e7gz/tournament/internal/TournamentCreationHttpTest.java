package ps.e7gz.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aUser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.Db;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.World;

/**
 * E7-91 over HTTP: {@code POST /api/v1/customer/tournaments} and {@code POST /api/v1/venue/tournaments}, with the real
 * security filter chain, idempotency and error mapping.
 *
 * <p><b>Done when</b> ({@link #aPlayerRequestOnARoomWithAConfirmedBookingFailsAndTheBookingIsUntouched}): a player
 * request against a room with a CONFIRMED booking fails with 409 TOURNAMENT_RESOURCE_CONFLICT and the booking row is
 * unchanged (FR-TRN-08: existing reservations always win).
 */
@AutoConfigureMockMvc
class TournamentCreationHttpTest extends IntegrationTest {

    static final String CUSTOMER = "/api/v1/customer/tournaments";
    static final String VENUE = "/api/v1/venue/tournaments";

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    // ------------------------------------------------------------------------------------------------ done-when

    @Test
    void aPlayerRequestOnARoomWithAConfirmedBookingFailsAndTheBookingIsUntouched() throws Exception {
        World world = World.create();
        UUID room = world.rooms().getFirst();
        // the venue's own CONFIRMED booking, 19:00-20:00, inside the tournament's 18:00-21:00
        UUID bookingId = UUID.fromString(read(mvc.perform(post("/api/v1/venue/bookings")
                        .header("Authorization", venueToken(world))
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\": \"" + room + "\", \"playMode\": \"SINGLE\", \"startAt\": \""
                                + world.startAt().plus(Duration.ofHours(1)) + "\", \"durationMinutes\": 60}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))).at("/data/bookingId").asText());
        BookingRow before = booking(bookingId);
        String key = UUID.randomUUID().toString();

        player(world, key, body(world, List.of(world.rooms().get(1), room), "PUBLIC", true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TOURNAMENT_RESOURCE_CONFLICT"))
                .andExpect(jsonPath("$.error.field").value("resourceIds"));

        assertThat(booking(bookingId)).isEqualTo(before);
        assertThat(before.status()).isEqualTo("CONFIRMED");
        assertThat(before.reservationActive()).isTrue();
        assertThat(Db.activeHoldsOn(world.rooms())).as("only the booking holds a room").isOne();
        assertThat(tournamentsAt(world)).isZero();
        assertThat(idempotencyRecords(key)).as("nothing committed, so a retry is evaluated afresh").isZero();
    }

    // ------------------------------------------------------------------------------------------------ player

    @Test
    void aVerifiedPlayerAsksAndTheSameKeyReplaysTheSameAnswer() throws Exception {
        World world = World.create();
        String key = UUID.randomUUID().toString();
        String body = body(world, world.rooms(), "PRIVATE", true);

        JsonNode created = read(player(world, key, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING_VENUE_APPROVAL"))
                .andExpect(jsonPath("$.data.hostType").value("PLAYER"))
                .andExpect(jsonPath("$.data.creatorParticipates").value(true))
                .andExpect(jsonPath("$.data.capacity").value(16))
                .andExpect(jsonPath("$.data.durationMinutes").value(180))
                .andExpect(jsonPath("$.data.venueCost.amount").value("600.00"))
                .andExpect(jsonPath("$.data.venueCost.currency").value("EGP"))
                .andExpect(jsonPath("$.data.creationFee.amount").value("0.00"))
                .andExpect(jsonPath("$.data.entryFee.amount").value("50.00"))
                .andExpect(jsonPath("$.data.prize.amount").value("0.00"))
                .andExpect(jsonPath("$.data.inviteCode").doesNotExist())
                .andExpect(jsonPath("$.data.resources.length()").value(2))
                .andExpect(jsonPath("$.data.resources[0].consoleType").value("PS5"))
                .andExpect(jsonPath("$.data.resources[0].hourlyPrice").doesNotExist()));
        Instant createdAt = Instant.parse(created.at("/data/createdAt").asText());
        assertThat(Instant.parse(created.at("/data/approvalExpiresAt").asText()))
                .isEqualTo(createdAt.plus(Duration.ofMinutes(10)));
        assertThat(Instant.parse(created.at("/data/startAt").asText())).isEqualTo(world.startAt());

        JsonNode replayed = read(player(world, key, body).andExpect(status().isCreated()));

        assertThat(replayed.at("/data")).isEqualTo(created.at("/data"));
        assertThat(tournamentsAt(world)).isOne();
    }

    /** FR-TRN-11 and the reviewer checklist: ux_tournament_one_pending_creator is PENDING_TOURNAMENT_REQUEST_EXISTS. */
    @Test
    void aSecondPendingRequestIsRefused() throws Exception {
        World world = World.create();
        player(world, UUID.randomUUID().toString(), body(world, List.of(world.rooms().getFirst()), "PUBLIC", true))
                .andExpect(status().isCreated());

        player(world, UUID.randomUUID().toString(), body(world, List.of(world.rooms().get(1)), "PUBLIC", false))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PENDING_TOURNAMENT_REQUEST_EXISTS"));

        assertThat(Db.activeHoldsOn(List.of(world.rooms().get(1)))).isZero();
    }

    @Test
    void aCustomerWhoIsNotAVerifiedPlayerIsRefused() throws Exception {
        World world = World.create();
        String customer = "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, aUser().verified().create().id());

        mvc.perform(post(CUSTOMER).header("Authorization", customer)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body(world, world.rooms(), "PUBLIC", true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("VERIFIED_PLAYER_REQUIRED"));
    }

    @Test
    void aMalformedRequestIsRefusedOnItsField() throws Exception {
        World world = World.create();
        String rooms = "[\"" + world.rooms().get(0) + "\"]";

        player(world, UUID.randomUUID().toString(), raw(world, rooms, "\"playMode\": null,", 16, 180))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.field").value("playMode"));
        player(world, UUID.randomUUID().toString(), raw(world, rooms, "", 7, 180))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        player(world, UUID.randomUUID().toString(), raw(world, rooms, "", 16, 75))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("durationMinutes"));
        player(world, UUID.randomUUID().toString(), raw(world, "[]", "", 16, 180))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("resourceIds"));
        assertThat(tournamentsAt(world)).isZero();
    }

    // ------------------------------------------------------------------------------------------------ venue

    @Test
    void aVenueCreatesItsOwnPrivateTournament() throws Exception {
        World world = World.create();

        venue(world, body(world, world.rooms(), "PRIVATE", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("WAITING_FOR_PLAYERS"))
                .andExpect(jsonPath("$.data.hostType").value("VENUE"))
                .andExpect(jsonPath("$.data.businessDate").value(world.businessDate().toString()))
                .andExpect(jsonPath("$.data.inviteCode").isString())
                .andExpect(jsonPath("$.data.approvalExpiresAt").doesNotExist())
                .andExpect(jsonPath("$.data.venueCost.amount").value("600.00"))
                .andExpect(jsonPath("$.data.resources[0].hourlyPrice.amount").value("100.00"));
    }

    @Test
    void aVenueCannotUseAnotherVenuesRoom() throws Exception {
        World world = World.create();
        World other = World.create();

        venue(world, body(world, other.rooms(), "PUBLIC", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        assertThat(Db.activeHoldsOn(other.rooms())).isZero();
    }

    @Test
    void anIdempotencyKeyIsRequired() throws Exception {
        World world = World.create();

        mvc.perform(post(VENUE).header("Authorization", venueToken(world))
                        .contentType(MediaType.APPLICATION_JSON).content(body(world, world.rooms(), "PUBLIC", null)))
                .andExpect(status().isBadRequest());
        assertThat(tournamentsAt(world)).isZero();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private ResultActions player(World world, String key, String body) throws Exception {
        String token = "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, world.creator().id());
        return mvc.perform(post(CUSTOMER).header("Authorization", token).header(OpenApiConfig.IDEMPOTENCY_KEY, key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions venue(World world, String body) throws Exception {
        return mvc.perform(post(VENUE).header("Authorization", venueToken(world))
                .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String venueToken(World world) {
        return "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, world.account().id());
    }

    /** PS5 single play, 16 players, 180 minutes from the World's start, entry fee 50.00, no prize given. */
    private static String body(World world, List<UUID> rooms, String visibility, Boolean creatorParticipates) {
        return "{\"venueId\": \"" + world.venue().id() + "\", \"activity\": \"PLAYSTATION\", \"playMode\": \"SINGLE\", "
                + "\"resourceIds\": [" + String.join(", ", rooms.stream().map(r -> "\"" + r + "\"").toList()) + "], "
                + "\"capacity\": 16, \"startAt\": \"" + world.startAt() + "\", \"durationMinutes\": 180, "
                + "\"visibility\": \"" + visibility + "\", "
                + (creatorParticipates == null ? "" : "\"creatorParticipates\": " + creatorParticipates + ", ")
                + "\"entryFee\": {\"amount\": \"50.00\", \"currency\": \"EGP\"}}";
    }

    private static String raw(World world, String rooms, String playMode, int capacity, int minutes) {
        return "{\"venueId\": \"" + world.venue().id() + "\", \"activity\": \"PLAYSTATION\", "
                + (playMode.isEmpty() ? "\"playMode\": \"SINGLE\", " : playMode + " ")
                + "\"resourceIds\": " + rooms + ", \"capacity\": " + capacity + ", \"startAt\": \"" + world.startAt()
                + "\", \"durationMinutes\": " + minutes + ", \"visibility\": \"PUBLIC\", \"creatorParticipates\": true}";
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    record BookingRow(String status, long version, OffsetDateTime startAt, OffsetDateTime endAt,
                      boolean reservationActive) {
    }

    private static BookingRow booking(UUID bookingId) {
        return OWNER.sql("""
                        SELECT b.status, b.version, b.start_at, b.end_at, r.active_flag
                          FROM booking.booking b
                          JOIN inventory.resource_reservation r ON r.booking_id = b.id
                         WHERE b.id = :id""")
                .param("id", bookingId)
                .query((rs, n) -> new BookingRow(rs.getString(1), rs.getLong(2),
                        rs.getObject(3, OffsetDateTime.class), rs.getObject(4, OffsetDateTime.class),
                        rs.getBoolean(5)))
                .single();
    }

    private static int tournamentsAt(World world) {
        return OWNER.sql("SELECT count(*) FROM tournament.tournament WHERE venue_id = :venue")
                .param("venue", world.venue().id()).query(Integer.class).single();
    }

    private static int idempotencyRecords(String key) {
        return OWNER.sql("SELECT count(*) FROM technical.idempotency_record WHERE idempotency_key = :key")
                .param("key", key).query(Integer.class).single();
    }
}
