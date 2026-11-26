package ps.e7gz.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.tournament.internal.TournamentApprovalIntegrationTest.Backdate;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.Db;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.World;

/**
 * E7-92 over HTTP: {@code POST /api/v1/venue/tournaments/{id}/approve} and {@code /reject}, with the real security
 * filter chain, idempotency and error mapping. Neither asks for the Owner Password (arch 10 section 3.2, FR-TRN-24).
 */
@AutoConfigureMockMvc
class TournamentApprovalHttpTest extends IntegrationTest {

    private static final String VENUE = "/api/v1/venue/tournaments/";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    @Autowired
    TournamentCreation creation;

    private CorrelationId.Scope correlation;

    @BeforeEach
    void open() {
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void close() {
        correlation.close();
    }

    @Test
    void theVenueApprovesWithoutTheOwnerPasswordAndTheSameKeyReplays() throws Exception {
        World world = World.create();
        UUID id = request(world).id();
        String key = UUID.randomUUID().toString();

        JsonNode approved = read(answer(world, id, "approve", key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tournamentId").value(id.toString()))
                .andExpect(jsonPath("$.data.status").value("WAITING_FOR_PLAYERS"))
                .andExpect(jsonPath("$.data.terminalReason").doesNotExist()));
        JsonNode replayed = read(answer(world, id, "approve", key).andExpect(status().isOk()));

        assertThat(replayed.at("/data")).isEqualTo(approved.at("/data"));
        assertThat(Db.history(id)).hasSize(2);
        assertThat(TournamentApprovalIntegrationTest.events(IntegrationEvents.TOURNAMENT_APPROVED, id)).hasSize(1);
    }

    @Test
    void theVenueRejectsAndASecondAnswerIsAChangedState() throws Exception {
        World world = World.create();
        UUID id = request(world).id();

        answer(world, id, "reject", UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.terminalReason").value("VENUE_REJECTED_TOURNAMENT"));

        answer(world, id, "approve", UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OPERATIONAL_STATE_CHANGED"));
        assertThat(Db.tournament(id).status()).isEqualTo("REJECTED");
    }

    @Test
    void anAnswerAfterTheDeadlineIsDeadlinePassed() throws Exception {
        World world = World.create();
        UUID id = request(world).id();
        Backdate.by(id, Duration.ofMinutes(10).plusSeconds(1));

        answer(world, id, "approve", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("DEADLINE_PASSED"));
        answer(world, id, "reject", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("DEADLINE_PASSED"));
    }

    @Test
    void anotherVenuesRequestIsNotFound() throws Exception {
        World world = World.create();
        World other = World.create();
        UUID id = request(world).id();

        answer(other, id, "approve", UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void aCustomerCannotAnswer() throws Exception {
        World world = World.create();
        UUID id = request(world).id();
        String customer = "Bearer " + accessTokens.issue(AuthSubjectType.CUSTOMER, world.creator().id());

        mvc.perform(post(VENUE + id + "/approve").header("Authorization", customer)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        assertThat(Db.tournament(id).status()).isEqualTo("PENDING_VENUE_APPROVAL");
    }

    @Test
    void anIdempotencyKeyIsRequired() throws Exception {
        World world = World.create();
        UUID id = request(world).id();

        mvc.perform(post(VENUE + id + "/approve").header("Authorization", venueToken(world)))
                .andExpect(status().isBadRequest());
        assertThat(Db.tournament(id).status()).isEqualTo("PENDING_VENUE_APPROVAL");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private CreatedTournament request(World world) {
        return creation.requestByPlayer(world.creator().id(), world.venue().id(), false,
                TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, List.of(world.rooms().getFirst()), 16,
                        world.startAt(), 120, TournamentVisibility.PUBLIC, null, null));
    }

    private ResultActions answer(World world, UUID tournamentId, String action, String key) throws Exception {
        return mvc.perform(post(VENUE + tournamentId + "/" + action).header("Authorization", venueToken(world))
                .header(OpenApiConfig.IDEMPOTENCY_KEY, key));
    }

    private String venueToken(World world) {
        return "Bearer " + accessTokens.issue(AuthSubjectType.VENUE, world.account().id());
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
