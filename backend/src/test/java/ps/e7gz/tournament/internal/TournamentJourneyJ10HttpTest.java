package ps.e7gz.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.fixtures.Fixtures.aUser;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.common.web.OpenApiConfig;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.tournament.internal.TournamentParticipantsIntegrationTest.Shift;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.Db;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.World;

/**
 * <b>J10 - A player hosts a tournament</b> (PRD section 6 J10, FR-TRN-01 to FR-TRN-16; roadmap Phase 6 exit: "J10
 * completes", end-to-end), over HTTP with the real security chain, idempotency and the real checkpoint worker (E7-94):
 * <ol>
 *   <li>A Verified Player requests a Tournament at least two hours before its start. The Resources take Approval
 *       Holds; nobody may join while it waits for the Venue.</li>
 *   <li>The Venue accepts within its ten minutes: WAITING_FOR_PLAYERS, the holds now Tournament Holds.</li>
 *   <li>Players join. At exactly one hour before the start the checkpoint runs: eight or more Active Participants
 *       confirm it; fewer cancel it and release the Resources in the same transaction.</li>
 * </ol>
 * Time is moved for the checkpoint only, with {@link Shift}. Discover listing is not asserted: the customer list route
 * is a later task's (E7-88).
 */
@AutoConfigureMockMvc
class TournamentJourneyJ10HttpTest extends IntegrationTest {

    private static final String CUSTOMER = "/api/v1/customer/tournaments";
    private static final String VENUE = "/api/v1/venue/tournaments/";

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    ObjectMapper json;

    @Autowired
    TournamentBoundaries boundaries;

    private DueJobs due;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void open() {
        correlation = CorrelationId.open(CorrelationId.generate());
        due = DueJobs.of(context, new TournamentCheckpointHandler(boundaries), new TournamentStartHandler(boundaries),
                new TournamentCompletionHandler(boundaries));
    }

    @AfterEach
    void close() {
        correlation.close();
    }

    /** The Creator plays; seven more join: 8 Active Participants at the checkpoint - CONFIRMED. */
    @Test
    void aVerifiedPlayerHostsAndEightPlayersConfirmItAtTheCheckpoint() throws Exception {
        World world = World.create(Duration.ofMinutes(125));
        UUID id = requestAndAccept(world);

        for (int i = 0; i < 7; i++) {
            join(aUser().verified().create().id(), id).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.participantStatus").value("JOINED"));
        }
        assertThat(Db.participants(id)).hasSize(8);

        Shift.pastCheckpoint(id, Duration.ZERO);                       // exactly startAt - 1h is now
        assertThat(due.fire(Tournaments.CHECKPOINT + ":" + id)).isTrue();

        assertThat(Db.tournament(id).status()).isEqualTo("CONFIRMED");
        assertThat(Db.holds(id)).filteredOn(hold -> hold.type().equals("TOURNAMENT_HOLD")).hasSize(2)
                .allSatisfy(hold -> assertThat(hold.active()).isTrue());
        assertThat(Db.history(id)).extracting(Db.HistoryRow::to)
                .containsExactly("PENDING_VENUE_APPROVAL", "WAITING_FOR_PLAYERS", "CONFIRMED");
        assertThat(DueJobs.job(Tournaments.CHECKPOINT + ":" + id).status()).isEqualTo("DONE");
    }

    /** The Creator plays; six more join: 7 at the checkpoint - CANCELLED, every Resource released at checkpointAt. */
    @Test
    void sevenPlayersAtTheCheckpointCancelAndReleaseTheResources() throws Exception {
        World world = World.create(Duration.ofMinutes(125));
        UUID id = requestAndAccept(world);
        for (int i = 0; i < 6; i++) {
            join(aUser().verified().create().id(), id).andExpect(status().isOk());
        }

        Instant checkpoint = Shift.pastCheckpoint(id, Duration.ofSeconds(5));
        assertThat(due.fire(Tournaments.CHECKPOINT + ":" + id)).isTrue();

        assertThat(Db.tournament(id).status()).isEqualTo("CANCELLED");
        assertThat(Db.tournament(id).terminalReason()).isEqualTo("MINIMUM_PARTICIPANTS_NOT_REACHED");
        assertThat(Db.activeHoldsOn(world.rooms())).isZero();
        assertThat(Db.holds(id)).filteredOn(hold -> hold.type().equals("TOURNAMENT_HOLD"))
                .allSatisfy(hold -> assertThat(hold.releasedAt()).isEqualTo(checkpoint));
        assertThat(TournamentApprovalIntegrationTest.events(IntegrationEvents.TOURNAMENT_CANCELLED, id))
                .singleElement().satisfies(event -> assertThat(event.payload())
                        .containsEntry("terminalReason", "MINIMUM_PARTICIPANTS_NOT_REACHED")
                        .containsEntry("recipientId", world.creator().id().toString()));
    }

    // ------------------------------------------------------------------------------------------------ steps 1-2

    /** Steps 1 and 2: the request (Approval Holds, nobody may join yet), then the Venue's accept. */
    private UUID requestAndAccept(World world) throws Exception {
        String body = "{\"venueId\": \"" + world.venue().id() + "\", \"activity\": \"PLAYSTATION\", "
                + "\"playMode\": \"SINGLE\", \"resourceIds\": [\"" + world.rooms().get(0) + "\", \""
                + world.rooms().get(1) + "\"], \"capacity\": 16, \"startAt\": \"" + world.startAt()
                + "\", \"durationMinutes\": 120, \"visibility\": \"PUBLIC\", \"creatorParticipates\": true}";
        UUID id = UUID.fromString(json.readTree(mvc.perform(post(CUSTOMER)
                                .header("Authorization", token(AuthSubjectType.CUSTOMER, world.creator().id()))
                                .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.status").value("PENDING_VENUE_APPROVAL"))
                        .andReturn().getResponse().getContentAsString())
                .at("/data/tournamentId").asText());
        assertThat(Db.holds(id)).hasSize(2).allSatisfy(hold -> {
            assertThat(hold.type()).isEqualTo("TOURNAMENT_APPROVAL_HOLD");
            assertThat(hold.active()).isTrue();
        });
        UUID early = aUser().verified().create().id();
        join(early, id).andExpect(status().is4xxClientError());
        assertThat(Db.participants(id)).as("only the playing Creator").hasSize(1);

        mvc.perform(post(VENUE + id + "/approve")
                        .header("Authorization", token(AuthSubjectType.VENUE, world.account().id()))
                        .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_FOR_PLAYERS"));
        assertThat(Db.holds(id)).filteredOn(hold -> hold.active())
                .extracting(Db.HoldRow::type).containsOnly("TOURNAMENT_HOLD").hasSize(2);
        return id;
    }

    private ResultActions join(UUID player, UUID tournamentId) throws Exception {
        return mvc.perform(post(CUSTOMER + "/" + tournamentId + "/join")
                .header("Authorization", token(AuthSubjectType.CUSTOMER, player))
                .header(OpenApiConfig.IDEMPOTENCY_KEY, UUID.randomUUID().toString()));
    }

    private String token(AuthSubjectType type, UUID id) {
        return "Bearer " + accessTokens.issue(type, id);
    }
}
