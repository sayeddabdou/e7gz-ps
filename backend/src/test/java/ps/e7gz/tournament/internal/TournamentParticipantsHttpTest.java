package ps.e7gz.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.fixtures.Fixtures.aUser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.audit.AuditFixtures;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.Db;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.World;

/**
 * E7-93 over HTTP: the Customer's {@code join}, {@code join-private}, {@code leave}, {@code remove} and {@code cancel},
 * the Venue's {@code cancel} and {@code operational-termination} and the Platform Admin's {@code cancel}, with the real
 * security filter chain, idempotency and error mapping.
 */
@AutoConfigureMockMvc
class TournamentParticipantsHttpTest extends IntegrationTest {

    private static final String CUSTOMER = "/api/v1/customer/tournaments/";
    private static final String VENUE = "/api/v1/venue/tournaments/";
    private static final String ADMIN = "/api/v1/admin/tournaments/";

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    @Autowired
    TournamentCreation creation;

    @Autowired
    TournamentApproval approval;

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
    void joinReplaysWithTheSameKeyAndLeaveFreesTheSlot() throws Exception {
        World world = World.create();
        UUID id = venueHosted(world, TournamentVisibility.PUBLIC);
        UUID player = aUser().verified().create().id();
        String key = UUID.randomUUID().toString();

        JsonNode joined = read(customer(player, CUSTOMER + id + "/join", key, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tournamentId").value(id.toString()))
                .andExpect(jsonPath("$.data.userId").value(player.toString()))
                .andExpect(jsonPath("$.data.participantStatus").value("JOINED"))
                .andExpect(jsonPath("$.data.tournamentStatus").value("WAITING_FOR_PLAYERS")));
        JsonNode replayed = read(customer(player, CUSTOMER + id + "/join", key, null).andExpect(status().isOk()));
        assertThat(replayed.at("/data")).isEqualTo(joined.at("/data"));

        customer(player, CUSTOMER + id + "/leave", UUID.randomUUID().toString(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participantStatus").value("LEFT"));
        customer(player, CUSTOMER + id + "/leave", UUID.randomUUID().toString(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OPERATIONAL_STATE_CHANGED"));
    }

    @Test
    void joinAnswersItsCodes() throws Exception {
        World world = World.create();
        UUID publicOne = venueHosted(world, TournamentVisibility.PUBLIC);

        customer(aUser().unverified().create().id(), CUSTOMER + publicOne + "/join", UUID.randomUUID().toString(),
                null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PHONE_VERIFICATION_REQUIRED"));
        customer(aUser().verified().create().id(), CUSTOMER + UUID.randomUUID() + "/join",
                UUID.randomUUID().toString(), null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(post(CUSTOMER + publicOne + "/join")
                        .header("Authorization", token(AuthSubjectType.CUSTOMER, aUser().verified().create().id())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPrivateTournamentIsJoinedWithItsCode() throws Exception {
        World world = World.create();
        UUID id = venueHosted(world, TournamentVisibility.PRIVATE);
        String code = TournamentFlows.inviteCode(id);
        UUID player = aUser().verified().create().id();

        customer(player, CUSTOMER + id + "/join", UUID.randomUUID().toString(), null)
                .andExpect(status().isNotFound());
        customer(player, CUSTOMER + "join-private", UUID.randomUUID().toString(), "{\"inviteCode\":\"" + code + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participantStatus").value("JOINED"));
        customer(player, CUSTOMER + "join-private", UUID.randomUUID().toString(), "{\"inviteCode\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void theCreatorRemovesAndCancels() throws Exception {
        World world = World.create();
        UUID id = playerHostedApproved(world);
        UUID player = aUser().verified().create().id();
        customer(player, CUSTOMER + id + "/join", UUID.randomUUID().toString(), null).andExpect(status().isOk());

        customer(player, CUSTOMER + id + "/cancel", UUID.randomUUID().toString(), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        customer(world.creator().id(), CUSTOMER + id + "/participants/" + player + "/remove",
                UUID.randomUUID().toString(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.participantStatus").value("REMOVED"));
        customer(world.creator().id(), CUSTOMER + id + "/cancel", UUID.randomUUID().toString(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.terminalReason").value("CREATOR_CANCELLED"));
    }

    @Test
    void theVenueCancelsAWaitingTournamentWithAnEmptyBody() throws Exception {
        World world = World.create();
        UUID id = venueHosted(world, TournamentVisibility.PUBLIC);

        mvc.perform(post(VENUE + id + "/cancel").header("Authorization", token(AuthSubjectType.VENUE,
                        world.account().id())).header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.terminalReason").value("VENUE_CANCELLED"));
    }

    @Test
    void operationalTerminationOfATournamentNotActiveIsAChangedStateAndNeedsAReason() throws Exception {
        World world = World.create();
        UUID id = venueHosted(world, TournamentVisibility.PUBLIC);
        String venue = token(AuthSubjectType.VENUE, world.account().id());

        mvc.perform(post(VENUE + id + "/operational-termination").header("Authorization", venue)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ownerPassword\":\"4321\",\"reason\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(post(VENUE + id + "/operational-termination").header("Authorization", venue)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Power cut\"}"))
                .andExpect(status().is(423))
                .andExpect(jsonPath("$.error.code").value("OWNER_PASSWORD_REQUIRED"));
        assertThat(Db.tournament(id).status()).isEqualTo("WAITING_FOR_PLAYERS");
    }

    @Test
    void anAdminCancelsWithAReason() throws Exception {
        World world = World.create();
        UUID id = venueHosted(world, TournamentVisibility.PUBLIC);
        String admin = token(AuthSubjectType.ADMIN, AuditFixtures.admin());

        mvc.perform(post(ADMIN + id + "/cancel").header("Authorization", admin)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Duplicate listing\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.terminalReason").value("ADMIN_CANCELLED"));
        mvc.perform(post(ADMIN + id + "/cancel").header("Authorization", admin)
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_ACTION_NOT_ALLOWED"));
        mvc.perform(post(ADMIN + id + "/cancel").header("Authorization", token(AuthSubjectType.VENUE,
                                world.account().id()))
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private UUID venueHosted(World world, TournamentVisibility visibility) {
        return creation.createByVenue(world.account().id(), TournamentRequest.of(Activity.PLAYSTATION,
                PlayMode.SINGLE, world.rooms(), 16, world.startAt(), 120, visibility, null, null)).id();
    }

    private UUID playerHostedApproved(World world) {
        UUID id = creation.requestByPlayer(world.creator().id(), world.venue().id(), false,
                TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, world.rooms(), 16, world.startAt(), 120,
                        TournamentVisibility.PUBLIC, null, null)).id();
        approval.approve(world.account().id(), id);
        return id;
    }

    private ResultActions customer(UUID userId, String path, String key, String body) throws Exception {
        var request = post(path).header("Authorization", token(AuthSubjectType.CUSTOMER, userId))
                .header(OpenApiConfig.IDEMPOTENCY_KEY, key);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
    }

    private String token(AuthSubjectType type, UUID id) {
        return "Bearer " + accessTokens.issue(type, id);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
