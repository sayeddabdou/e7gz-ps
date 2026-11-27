package ps.e7gz.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aUser;

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
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ps.e7gz.audit.AuditFixtures;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.support.fixtures.TrustStates;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.TestCommand;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.World;
import ps.e7gz.trust.BanFixtures;
import ps.e7gz.trust.api.TournamentParticipantCauses;

/**
 * E7-126 (S6-E5) over HTTP: the seven Sprint 6 tournament reads (SPRINT-6-CONTRACT.md section 2) with the real
 * security chain. Every Discover call names its World's own Area: the database is shared with every other test.
 */
@AutoConfigureMockMvc
class TournamentReadsHttpTest extends IntegrationTest {

    private static final String CUSTOMER = "/api/v1/customer/tournaments";
    private static final String VENUE = "/api/v1/venue/tournaments";
    private static final String ADMIN = "/api/v1/admin/tournaments";

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

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

    @Autowired
    TournamentParticipation participation;

    @Autowired
    TournamentCancellation cancellation;

    @Autowired
    CommandRunner runner;

    @Autowired
    TournamentParticipantCauses causes;

    private CorrelationId.Scope correlation;

    @BeforeEach
    void open() {
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void close() {
        correlation.close();
    }

    // ------------------------------------------------------------------------------------------------ Discover

    @Test
    void discoverListsOnlyAcceptedPublicTournamentsBeforeTheirStartSoonestFirst() throws Exception {
        World world = World.create();
        UUID later = venueHosted(world, TournamentVisibility.PUBLIC, 16, hours(world, 3));
        UUID open = venueHosted(world, TournamentVisibility.PUBLIC, 16, hours(world, 0));
        venueHosted(world, TournamentVisibility.PRIVATE, 16, hours(world, -3));
        UUID approved = playerHosted(world, TournamentVisibility.PUBLIC, hours(world, 5));
        approval.approve(world.account().id(), approved);
        UUID pending = playerHosted(world, TournamentVisibility.PUBLIC, hours(world, -6)); // one pending per creator
        UUID cancelled = venueHosted(world, TournamentVisibility.PUBLIC, 16, hours(world, -9));
        cancellation.cancelByVenue(world.account().id(), cancelled, null);
        String stranger = token(AuthSubjectType.CUSTOMER, aUser().verified().create().id());

        JsonNode page = read(mvc.perform(get(CUSTOMER).param("areaId", world.venue().areaId().toString())
                        .header("Authorization", stranger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextCursor").isEmpty()));

        assertThat(ids(page)).containsExactly(open, later, approved);
        JsonNode first = page.at("/data/items/0");
        assertThat(first.get("venueName").asText()).isEqualTo(world.venue().name());
        assertThat(first.get("status").asText()).isEqualTo("WAITING_FOR_PLAYERS");
        assertThat(first.get("capacity").asInt()).isEqualTo(16);
        assertThat(first.get("capacityOccupiedCount").asInt()).isZero();
        assertThat(first.get("entryFee").get("amount").asText()).isEqualTo("0.00");
        assertThat(first.get("createdByMe").asBoolean()).isFalse();
        assertThat(first.get("myParticipantStatus").isNull()).isTrue();
        assertThat(ids(page)).doesNotContain(pending, cancelled);

        mvc.perform(get(CUSTOMER).param("areaId", world.venue().areaId().toString()).param("activity", "BILLIARDS")
                        .header("Authorization", stranger))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void aTournamentWhoseStartHasComeIsNotInDiscover() throws Exception {
        World world = World.create();
        UUID id = venueHosted(world, TournamentVisibility.PUBLIC, 16, world.startAt());
        TournamentParticipantsIntegrationTest.Shift.pastStart(id, Duration.ofMinutes(1));

        assertThat(ids(discover(world, aUser().verified().create().id()))).doesNotContain(id);
    }

    @Test
    void aVenueThatIsNotLiveHasNoTournamentInDiscover() throws Exception {
        World world = World.create();
        UUID id = venueHosted(world, TournamentVisibility.PUBLIC, 16, world.startAt());
        UUID viewer = aUser().verified().create().id();
        assertThat(ids(discover(world, viewer))).containsExactly(id);

        OWNER.sql("UPDATE iam.venue_account SET account_status = 'DISABLED' WHERE id = :id")
                .param("id", world.account().id()).update();

        assertThat(ids(discover(world, viewer))).isEmpty();
    }

    @Test
    void aFullTournamentStaysInDiscoverAndABanOnlySlotStillOccupies() throws Exception {
        World world = World.create(Duration.ofMinutes(185));
        UUID id = venueHosted(world, TournamentVisibility.PUBLIC, 8, world.startAt());
        List<UUID> players = joinN(id, 8);
        UUID banned = players.getFirst();
        BanFixtures.ban(banned, "ACTIVE", TrustStates.dbNow().minus(Duration.ofDays(30)), Duration.ofDays(31));
        runner.run(new TestCommand<>(LockSet.builder().users(banned).build(), c -> {
            causes.reconcileBans(c, banned);
            return null;
        }));
        assertThat(TournamentFlows.participant(id, banned)).isEqualTo("INELIGIBLE:BAN");

        JsonNode page = discover(world, players.get(1));
        assertThat(ids(page)).containsExactly(id);
        assertThat(page.at("/data/items/0/capacityOccupiedCount").asInt()).isEqualTo(8);
        assertThat(page.at("/data/items/0/myParticipantStatus").asText()).isEqualTo("JOINED");

        String venueBody = body(mvc.perform(get(VENUE + "/" + id)
                        .header("Authorization", token(AuthSubjectType.VENUE, world.account().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capacityOccupiedCount").value(8)));
        JsonNode participants = json.readTree(venueBody).at("/data/participants");
        assertThat(participants).hasSize(8);
        assertThat(participants.findValuesAsText("status")).contains("INELIGIBLE");
        assertThat(venueBody).doesNotContain("\"BAN\"", "ineligibleReason", "ACCOUNT_DELETED");
    }

    @Test
    void discoverPagesAcrossHiddenRowsWithoutLosingOrRepeatingAndABadCursorIsRefused() throws Exception {
        World world = World.create();
        UUID a = venueHosted(world, TournamentVisibility.PUBLIC, 16, hours(world, -6));
        venueHosted(world, TournamentVisibility.PRIVATE, 16, hours(world, -3));
        UUID b = venueHosted(world, TournamentVisibility.PUBLIC, 16, hours(world, 0));
        UUID c = venueHosted(world, TournamentVisibility.PUBLIC, 16, hours(world, 3));
        String viewer = token(AuthSubjectType.CUSTOMER, aUser().verified().create().id());

        assertThat(allPages(world, viewer)).containsExactly(a, b, c);

        mvc.perform(get(CUSTOMER).param("cursor", "not-a-cursor").header("Authorization", viewer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void discoverPagesPastATournamentOfAVenueThatIsNotLiveInTheSameArea() throws Exception {
        World live = World.create();
        World notLive = World.inArea(live.venue().areaId());
        UUID a1 = venueHosted(live, TournamentVisibility.PUBLIC, 16, hours(live, -6));
        venueHosted(notLive, TournamentVisibility.PUBLIC, 16, hours(live, -5));
        UUID a2 = venueHosted(live, TournamentVisibility.PUBLIC, 16, hours(live, -3));
        venueHosted(notLive, TournamentVisibility.PUBLIC, 16, hours(live, -2));
        UUID a3 = venueHosted(live, TournamentVisibility.PUBLIC, 16, hours(live, 0));
        OWNER.sql("UPDATE iam.venue_account SET account_status = 'DISABLED' WHERE id = :id")
                .param("id", notLive.account().id()).update();
        String viewer = token(AuthSubjectType.CUSTOMER, aUser().verified().create().id());

        assertThat(allPages(live, viewer)).containsExactly(a1, a2, a3);
    }

    // ------------------------------------------------------------------------------------------------ mine + detail

    @Test
    void aPendingRequestIsInTheCreatorsListAndTheVenueListButHiddenFromEveryoneElse() throws Exception {
        World world = World.create();
        UUID pending = playerHosted(world, TournamentVisibility.PUBLIC, world.startAt());
        String creator = token(AuthSubjectType.CUSTOMER, world.creator().id());
        String stranger = token(AuthSubjectType.CUSTOMER, aUser().verified().create().id());

        assertThat(ids(read(mvc.perform(get(CUSTOMER + "/mine").header("Authorization", creator))
                .andExpect(status().isOk())))).containsExactly(pending);
        assertThat(ids(discover(world, aUser().verified().create().id()))).isEmpty();
        assertThat(ids(read(mvc.perform(get(VENUE).header("Authorization",
                        token(AuthSubjectType.VENUE, world.account().id())))
                .andExpect(status().isOk())))).containsExactly(pending);

        mvc.perform(get(CUSTOMER + "/" + pending).header("Authorization", creator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_VENUE_APPROVAL"))
                .andExpect(jsonPath("$.data.createdByMe").value(true))
                .andExpect(jsonPath("$.data.approvalExpiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.inviteCode").isEmpty());
        mvc.perform(get(CUSTOMER + "/" + pending).header("Authorization", stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        mvc.perform(get(CUSTOMER + "/" + UUID.randomUUID()).header("Authorization", stranger))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRequestCancelledBeforeTheVenueAcceptedItStaysHiddenFromStrangers() throws Exception {
        World world = World.create();
        UUID id = playerHosted(world, TournamentVisibility.PUBLIC, world.startAt());
        cancellation.cancelByCreator(world.creator().id(), id);

        mvc.perform(get(CUSTOMER + "/" + id)
                        .header("Authorization", token(AuthSubjectType.CUSTOMER, aUser().verified().create().id())))
                .andExpect(status().isNotFound());
        mvc.perform(get(CUSTOMER + "/" + id)
                        .header("Authorization", token(AuthSubjectType.CUSTOMER, world.creator().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.terminalReason").value("CREATOR_CANCELLED"));
    }

    @Test
    void theInviteCodeAndParticipantsGoToTheCreatorOnlyAndAPrivateTournamentIsHiddenFromStrangers()
            throws Exception {
        World world = World.create();
        UUID id = playerHosted(world, TournamentVisibility.PRIVATE, world.startAt());
        approval.approve(world.account().id(), id);
        String code = TournamentFlows.inviteCode(id);
        Fixtures.User player = aUser().verified().create();
        participation.joinPrivate(player.id(), code);

        String creatorBody = body(mvc.perform(get(CUSTOMER + "/" + id)
                        .header("Authorization", token(AuthSubjectType.CUSTOMER, world.creator().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inviteCode").value(code))
                .andExpect(jsonPath("$.data.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.data.capacityOccupiedCount").value(1))
                .andExpect(jsonPath("$.data.participants[0].userId").value(player.id().toString()))
                .andExpect(jsonPath("$.data.participants[0].username").value(player.username()))
                .andExpect(jsonPath("$.data.participants[0].status").value("JOINED"))
                .andExpect(jsonPath("$.data.rooms.length()").value(2)));
        assertThat(creatorBody).doesNotContain(player.phoneE164(), player.localPhone(), "phone", "email");

        mvc.perform(get(CUSTOMER + "/" + id).header("Authorization", token(AuthSubjectType.CUSTOMER, player.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myParticipantStatus").value("JOINED"))
                .andExpect(jsonPath("$.data.createdByMe").value(false))
                .andExpect(jsonPath("$.data.inviteCode").isEmpty())
                .andExpect(jsonPath("$.data.participants").isEmpty());
        mvc.perform(get(CUSTOMER + "/" + id)
                        .header("Authorization", token(AuthSubjectType.CUSTOMER, aUser().verified().create().id())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        participation.leave(player.id(), id);
        mvc.perform(get(CUSTOMER + "/" + id).header("Authorization", token(AuthSubjectType.CUSTOMER, player.id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myParticipantStatus").value("LEFT"));
        assertThat(ids(read(mvc.perform(get(CUSTOMER + "/mine")
                        .header("Authorization", token(AuthSubjectType.CUSTOMER, player.id())))
                .andExpect(status().isOk())))).containsExactly(id);
    }

    @Test
    void anAcceptedPublicTournamentIsVisibleToAnyCustomerWithoutItsParticipants() throws Exception {
        World world = World.create();
        UUID id = venueHosted(world, TournamentVisibility.PUBLIC, 16, world.startAt());
        participation.join(aUser().verified().create().id(), id);

        mvc.perform(get(CUSTOMER + "/" + id)
                        .header("Authorization", token(AuthSubjectType.CUSTOMER, aUser().verified().create().id())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hostType").value("VENUE"))
                .andExpect(jsonPath("$.data.capacityOccupiedCount").value(1))
                .andExpect(jsonPath("$.data.checkpointAt").isNotEmpty())
                .andExpect(jsonPath("$.data.durationMinutes").value(120))
                .andExpect(jsonPath("$.data.participants").isEmpty())
                .andExpect(jsonPath("$.data.inviteCode").isEmpty());
    }

    @Test
    void mineSeparatesEndedTournamentsNewestFirst() throws Exception {
        World world = World.create();
        UUID rejected = playerHosted(world, TournamentVisibility.PUBLIC, hours(world, -3));
        approval.reject(world.account().id(), rejected);
        UUID cancelled = playerHosted(world, TournamentVisibility.PUBLIC, hours(world, 3));
        cancellation.cancelByCreator(world.creator().id(), cancelled);
        UUID live = playerHosted(world, TournamentVisibility.PUBLIC, hours(world, 0)); // one pending per creator
        String creator = token(AuthSubjectType.CUSTOMER, world.creator().id());

        assertThat(ids(read(mvc.perform(get(CUSTOMER + "/mine").header("Authorization", creator))
                .andExpect(status().isOk())))).containsExactly(live);
        assertThat(ids(read(mvc.perform(get(CUSTOMER + "/mine").param("ended", "true")
                        .header("Authorization", creator))
                .andExpect(status().isOk())))).containsExactly(cancelled, rejected);
    }

    // ------------------------------------------------------------------------------------------------ venue

    @Test
    void theVenueSeesItsOwnTournamentsOnlyWithParticipantsAndTheCode() throws Exception {
        World world = World.create();
        World other = World.create();
        UUID id = venueHosted(world, TournamentVisibility.PRIVATE, 16, world.startAt());
        Fixtures.User player = aUser().verified().create();
        participation.joinPrivate(player.id(), TournamentFlows.inviteCode(id));
        UUID foreign = venueHosted(other, TournamentVisibility.PUBLIC, 16, other.startAt());
        String venue = token(AuthSubjectType.VENUE, world.account().id());

        String detail = body(mvc.perform(get(VENUE + "/" + id).header("Authorization", venue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inviteCode").value(TournamentFlows.inviteCode(id)))
                .andExpect(jsonPath("$.data.creatorUsername").isEmpty())
                .andExpect(jsonPath("$.data.creatorParticipates").isEmpty())
                .andExpect(jsonPath("$.data.businessDate").value(world.businessDate().toString()))
                .andExpect(jsonPath("$.data.rooms[0].hourlyPrice.amount").value("100.00"))
                .andExpect(jsonPath("$.data.venueCost.amount").isNotEmpty())
                .andExpect(jsonPath("$.data.participants[0].username").value(player.username())));
        assertThat(detail).doesNotContain(player.phoneE164(), "phone", "email");

        mvc.perform(get(VENUE + "/" + foreign).header("Authorization", venue))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
        assertThat(ids(read(mvc.perform(get(VENUE).header("Authorization", venue))
                .andExpect(status().isOk())))).containsExactly(id);
    }

    @Test
    void theVenueListFiltersByStateAndPagesWithACursor() throws Exception {
        World world = World.create();
        UUID pending = playerHosted(world, TournamentVisibility.PUBLIC, hours(world, 3));
        UUID first = venueHosted(world, TournamentVisibility.PUBLIC, 16, hours(world, -3));
        UUID second = venueHosted(world, TournamentVisibility.PUBLIC, 16, hours(world, 0));
        UUID ended = venueHosted(world, TournamentVisibility.PUBLIC, 16, hours(world, -6));
        cancellation.cancelByVenue(world.account().id(), ended, null);
        String venue = token(AuthSubjectType.VENUE, world.account().id());

        JsonNode requests = read(mvc.perform(get(VENUE).param("status", "PENDING_VENUE_APPROVAL")
                        .header("Authorization", venue))
                .andExpect(status().isOk()));
        assertThat(ids(requests)).containsExactly(pending);
        assertThat(requests.at("/data/items/0/approvalExpiresAt").isNull()).isFalse();
        assertThat(requests.at("/data/items/0/creatorUsername").asText()).isEqualTo(world.creator().username());
        assertThat(requests.at("/data/items/0/roomNames")).hasSize(2);

        JsonNode page1 = read(mvc.perform(get(VENUE).param("limit", "2").header("Authorization", venue))
                .andExpect(status().isOk()));
        assertThat(ids(page1)).containsExactly(first, second);
        JsonNode page2 = read(mvc.perform(get(VENUE).param("limit", "2")
                        .param("cursor", page1.at("/data/nextCursor").asText()).header("Authorization", venue))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nextCursor").isEmpty()));
        assertThat(ids(page2)).containsExactly(pending);

        assertThat(ids(read(mvc.perform(get(VENUE).param("ended", "true").header("Authorization", venue))
                .andExpect(status().isOk())))).containsExactly(ended);
        assertThat(ids(read(mvc.perform(get(VENUE).param("status", "CANCELLED").header("Authorization", venue))
                .andExpect(status().isOk())))).containsExactly(ended);
    }

    // ------------------------------------------------------------------------------------------------ admin

    @Test
    void theAdminListsNewestFirstWithFiltersAndReadsAnyDetail() throws Exception {
        World world = World.create();
        UUID older = venueHosted(world, TournamentVisibility.PUBLIC, 16, hours(world, -3));
        UUID newer = playerHosted(world, TournamentVisibility.PRIVATE, hours(world, 0));
        Fixtures.User player = aUser().verified().create();
        participation.join(player.id(), older);
        String admin = token(AuthSubjectType.ADMIN, AuditFixtures.admin());

        assertThat(ids(read(mvc.perform(get(ADMIN).param("venueId", world.venue().id().toString())
                        .header("Authorization", admin))
                .andExpect(status().isOk())))).containsExactly(newer, older);
        JsonNode players = read(mvc.perform(get(ADMIN).param("venueId", world.venue().id().toString())
                        .param("hostType", "PLAYER").param("status", "PENDING_VENUE_APPROVAL")
                        .header("Authorization", admin))
                .andExpect(status().isOk()));
        assertThat(ids(players)).containsExactly(newer);
        assertThat(players.at("/data/items/0/creatorUserId").asText()).isEqualTo(world.creator().id().toString());
        assertThat(players.at("/data/items/0/venueName").asText()).isEqualTo(world.venue().name());

        String detail = body(mvc.perform(get(ADMIN + "/" + older).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.creationFee.amount").isNotEmpty())
                .andExpect(jsonPath("$.data.roomNames.length()").value(2))
                .andExpect(jsonPath("$.data.participants[0].userId").value(player.id().toString())));
        assertThat(detail).doesNotContain(player.phoneE164(), "phone", "email");
        mvc.perform(get(ADMIN + "/" + UUID.randomUUID()).header("Authorization", admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static Instant hours(World world, int offset) {
        return world.startAt().plus(Duration.ofHours(offset));
    }

    private UUID venueHosted(World world, TournamentVisibility visibility, int capacity, Instant startAt) {
        return creation.createByVenue(world.account().id(), TournamentRequest.of(Activity.PLAYSTATION,
                PlayMode.SINGLE, world.rooms(), capacity, startAt, 120, visibility, null, null)).id();
    }

    private UUID playerHosted(World world, TournamentVisibility visibility, Instant startAt) {
        return creation.requestByPlayer(world.creator().id(), world.venue().id(), false,
                TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, world.rooms(), 16, startAt, 120,
                        visibility, null, null)).id();
    }

    private List<UUID> joinN(UUID tournamentId, int n) {
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID player = aUser().verified().create().id();
            participation.join(player, tournamentId);
            players.add(player);
        }
        return players;
    }

    private JsonNode discover(World world, UUID viewer) throws Exception {
        return read(mvc.perform(get(CUSTOMER).param("areaId", world.venue().areaId().toString())
                        .header("Authorization", token(AuthSubjectType.CUSTOMER, viewer)))
                .andExpect(status().isOk()));
    }

    /** Every Discover page of the World's Area, one item a page. */
    private List<UUID> allPages(World world, String viewer) throws Exception {
        List<UUID> seen = new ArrayList<>();
        String cursor = null;
        do {
            var request = get(CUSTOMER).param("areaId", world.venue().areaId().toString()).param("limit", "1")
                    .header("Authorization", viewer);
            if (cursor != null) {
                request = request.param("cursor", cursor);
            }
            JsonNode page = read(mvc.perform(request).andExpect(status().isOk()));
            seen.addAll(ids(page));
            cursor = page.at("/data/nextCursor").isNull() ? null : page.at("/data/nextCursor").asText();
        } while (cursor != null);
        return seen;
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.at("/data/items").forEach(item -> ids.add(UUID.fromString(item.get("tournamentId").asText())));
        return ids;
    }

    private String token(AuthSubjectType type, UUID id) {
        return "Bearer " + accessTokens.issue(type, id);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(body(result));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
    }
}
