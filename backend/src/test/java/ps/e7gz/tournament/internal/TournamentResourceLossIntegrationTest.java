package ps.e7gz.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aResource;
import static ps.e7gz.support.fixtures.Fixtures.aUser;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.web.servlet.MockMvc;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentResourceRemovalCause;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.tournament.api.TournamentCascades;
import ps.e7gz.tournament.api.TournamentCascades.ResourceLoss;
import ps.e7gz.tournament.internal.TournamentParticipantsIntegrationTest.Shift;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.Db;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.TestCommand;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.World;

/**
 * E7-94 (S6-E4) - resource loss (arch 06 section 3.5, FR-TRN-27, FR-TRN-28): before the start any lost Resource
 * cancels the whole Tournament; during ACTIVE the failed Resources are removed and the Tournament goes on, its Creator
 * and Participants told (Tournament Resource Availability Changed), until none is left - then it is cancelled under
 * the resource reason. The stored venue cost never changes.
 *
 * <p>The losses arrive the real way: a Venue declares Resource Emergency Outages over HTTP, and E7-82's cascade hands
 * each Tournament to {@link TournamentCascades#loseResource}. An Owner Review is planted as E7-84's cascade writes it
 * and handed over in the same command. A Tournament reaches ACTIVE through its real checkpoint and start, moved back in
 * time by {@link Shift}.
 *
 * <p><b>Done when</b>: {@link #losingThreeOfFourRoomsContinuesAndTheFourthCancelsWithTheCostUnchanged}.
 */
@AutoConfigureMockMvc
class TournamentResourceLossIntegrationTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokens accessTokens;

    @Autowired
    CommandRunner runner;

    @Autowired
    TournamentCreation creation;

    @Autowired
    TournamentParticipation participation;

    @Autowired
    TournamentBoundaries boundaries;

    @Autowired
    TournamentCancellation cancellation;

    @Autowired
    TournamentCascades cascades;

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

    // ------------------------------------------------------------------------------------------------ done-when

    /**
     * Reviewer checklist: an ACTIVE Tournament on 4 rooms loses 3 of them to outages one after another - each time it
     * stays ACTIVE, the room is removed with its cause and its hold released, and one
     * {@code TournamentResourceAvailabilityChanged} names the rooms left; the stored venue cost stays 800.00. Losing
     * the 4th cancels it with RESOURCE_EMERGENCY_OUTAGE, and still the cost is the one stored at creation.
     */
    @Test
    void losingThreeOfFourRoomsContinuesAndTheFourthCancelsWithTheCostUnchanged() throws Exception {
        World world = World.create(Duration.ofMinutes(125));
        List<UUID> rooms = fourRooms(world);
        CreatedTournament tournament = active(world, rooms);
        BigDecimal cost = Db.tournament(tournament.id()).venueCostTotal();
        assertThat(cost).as("4 rooms x 100.00 an hour x 2 hours").isEqualByComparingTo("800.00");

        for (int lost = 1; lost <= 3; lost++) {
            UUID room = rooms.get(lost - 1);
            outage(world, room);

            assertThat(Db.tournament(tournament.id()).status()).as("after losing %d of 4", lost).isEqualTo("ACTIVE");
            assertThat(Db.tournament(tournament.id()).venueCostTotal()).isEqualByComparingTo(cost);
            assertThat(removalCause(tournament.id(), room)).isEqualTo("RESOURCE_EMERGENCY_OUTAGE");
            assertThat(holdOf(tournament.id(), room)).isEqualTo("false:RESOURCE_EMERGENCY_OUTAGE");
            List<TournamentApprovalIntegrationTest.Event> changed = TournamentApprovalIntegrationTest.events(
                    IntegrationEvents.TOURNAMENT_RESOURCE_AVAILABILITY_CHANGED, tournament.id());
            assertThat(changed).hasSize(lost);
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) (Map<?, ?>) changed.getLast().payload();
            assertThat(payload).containsEntry("resourceId", room.toString())
                    .containsEntry("removalCause", "RESOURCE_EMERGENCY_OUTAGE")
                    .containsEntry("venueId", world.venue().id().toString())
                    .doesNotContainKey("creatorUserId");
            assertThat(payload.get("remainingResourceIds")).isEqualTo(
                    rooms.subList(lost, 4).stream().map(UUID::toString).sorted().toList());
        }

        outage(world, rooms.get(3));

        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("CANCELLED");
        assertThat(Db.tournament(tournament.id()).terminalReason()).isEqualTo("RESOURCE_EMERGENCY_OUTAGE");
        assertThat(Db.tournament(tournament.id()).venueCostTotal()).isEqualByComparingTo(cost);
        assertThat(Db.activeHoldsOn(rooms)).isZero();
        assertThat(Db.history(tournament.id())).last().isEqualTo(new Db.HistoryRow(4, "ACTIVE", "CANCELLED",
                "RESOURCE_EMERGENCY_OUTAGE", "SYSTEM", null));
        assertThat(TournamentApprovalIntegrationTest.events(IntegrationEvents.TOURNAMENT_RESOURCE_AVAILABILITY_CHANGED,
                tournament.id())).as("the last loss cancels; it does not announce a change").hasSize(3);
        assertThat(interruptions(tournament.id())).isOne();
        assertThat(Db.liveJobs(tournament.id())).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ before start

    /**
     * FR-TRN-27: before the start the rule is stricter - one room of two lost while the request is still
     * PENDING_VENUE_APPROVAL cancels the whole request (not trimmed), both Approval Holds released, the stored cost
     * unchanged, and the Creator is told. (The WAITING_FOR_PLAYERS case through a real outage is
     * {@code EmergencyTournamentCascadeTest}; an outage cannot be declared here, no Operating Session being open now.)
     */
    @Test
    void aRoomLostWhileStillPendingApprovalCancelsTheWholeRequest() {
        World world = World.create();
        CreatedTournament pending = creation.requestByPlayer(world.creator().id(), world.venue().id(), false,
                TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, world.rooms(), 16, world.startAt(), 120,
                        TournamentVisibility.PUBLIC, null, null));
        BigDecimal cost = Db.tournament(pending.id()).venueCostTotal();
        UUID room = world.rooms().getFirst();

        ResourceLoss loss = runner.run(new TestCommand<>(LockSet.builder().tournaments(pending.id()).resources(room)
                .build(), c -> {
                    enterOwnerReview(c, room);
                    return cascades.loseResource(c, pending.id(), room,
                            TournamentResourceRemovalCause.RESOURCE_OWNER_REVIEW_REQUIRED);
                }));

        assertThat(loss).isEqualTo(ResourceLoss.CANCELLED);
        assertThat(Db.tournament(pending.id()).status()).isEqualTo("CANCELLED");
        assertThat(Db.tournament(pending.id()).terminalReason()).isEqualTo("RESOURCE_OWNER_REVIEW_REQUIRED");
        assertThat(Db.tournament(pending.id()).venueCostTotal()).isEqualByComparingTo(cost);
        assertThat(Db.activeHoldsOn(world.rooms())).isZero();
        assertThat(removalCause(pending.id(), room)).as("cancelled, not trimmed").isNull();
        assertThat(TournamentApprovalIntegrationTest.events(IntegrationEvents.TOURNAMENT_CANCELLED, pending.id()))
                .singleElement().satisfies(event -> assertThat(event.payload())
                        .containsEntry("terminalReason", "RESOURCE_OWNER_REVIEW_REQUIRED")
                        .containsEntry("actorType", "SYSTEM")
                        .containsEntry("recipientId", world.creator().id().toString()));
        assertThat(TournamentApprovalIntegrationTest.events(IntegrationEvents.TOURNAMENT_RESOURCE_AVAILABILITY_CHANGED,
                pending.id())).isEmpty();
    }

    /**
     * A CONFIRMED Tournament (after its checkpoint, before its start) is cancelled by any one lost room - only an
     * ACTIVE one is trimmed.
     */
    @Test
    void aRoomLostAfterTheCheckpointButBeforeTheStartCancels() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament confirmed = venueHosted(world, world.rooms());
        for (int i = 0; i < 8; i++) {
            participation.join(aUser().verified().create().id(), confirmed.id());
        }
        Shift.pastCheckpoint(confirmed.id(), Duration.ofMinutes(1));
        assertThat(due.fire(Tournaments.CHECKPOINT + ":" + confirmed.id())).isTrue();
        assertThat(Db.tournament(confirmed.id()).status()).isEqualTo("CONFIRMED");
        UUID room = world.rooms().get(1);

        ResourceLoss loss = runner.run(new TestCommand<>(LockSet.builder().tournaments(confirmed.id()).resources(room)
                .build(), c -> {
                    enterOwnerReview(c, room);
                    return cascades.loseResource(c, confirmed.id(), room,
                            TournamentResourceRemovalCause.RESOURCE_OWNER_REVIEW_REQUIRED);
                }));

        assertThat(loss).isEqualTo(ResourceLoss.CANCELLED);
        assertThat(Db.tournament(confirmed.id()).terminalReason()).isEqualTo("RESOURCE_OWNER_REVIEW_REQUIRED");
        assertThat(Db.activeHoldsOn(world.rooms())).isZero();
    }

    // ------------------------------------------------------------------------------------------------ owner review

    /**
     * FR-OPS-28, FR-TRN-27: an Owner Review on one of two rooms of an ACTIVE Tournament trims it (cause
     * RESOURCE_OWNER_REVIEW_REQUIRED) and it continues; handing the same loss over again changes nothing.
     */
    @Test
    void anOwnerReviewTrimsAnActiveTournamentAndASecondHandOverChangesNothing() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = active(world, world.rooms());
        UUID room = world.rooms().getFirst();
        LockSet locks = LockSet.builder().tournaments(tournament.id()).resources(room).build();

        ResourceLoss first = runner.run(new TestCommand<>(locks, c -> {
            enterOwnerReview(c, room);
            return cascades.loseResource(c, tournament.id(), room,
                    TournamentResourceRemovalCause.RESOURCE_OWNER_REVIEW_REQUIRED);
        }));
        ResourceLoss second = runner.run(new TestCommand<>(locks, c -> cascades.loseResource(c, tournament.id(), room,
                TournamentResourceRemovalCause.RESOURCE_OWNER_REVIEW_REQUIRED)));

        assertThat(first).isEqualTo(ResourceLoss.CONTINUES);
        assertThat(second).isEqualTo(ResourceLoss.NOT_AFFECTED);
        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("ACTIVE");
        assertThat(removalCause(tournament.id(), room)).isEqualTo("RESOURCE_OWNER_REVIEW_REQUIRED");
        assertThat(TournamentApprovalIntegrationTest.events(IntegrationEvents.TOURNAMENT_RESOURCE_AVAILABILITY_CHANGED,
                tournament.id())).singleElement().satisfies(event -> assertThat(event.payload())
                        .containsEntry("removalCause", "RESOURCE_OWNER_REVIEW_REQUIRED"));
    }

    /** A loss handed over for a terminal Tournament changes nothing. */
    @Test
    void aTerminalTournamentIsNotAffected() {
        World world = World.create(Duration.ofMinutes(180));
        CreatedTournament tournament = venueHosted(world, world.rooms());
        UUID room = world.rooms().getFirst();
        LockSet locks = LockSet.builder().tournaments(tournament.id()).resources(room).build();
        cancellation.cancelByAdmin(ps.e7gz.audit.AuditFixtures.admin(), tournament.id(), "Duplicate listing");

        ResourceLoss loss = runner.run(new TestCommand<>(locks, c -> cascades.loseResource(c, tournament.id(), room,
                TournamentResourceRemovalCause.RESOURCE_EMERGENCY_OUTAGE)));

        assertThat(loss).isEqualTo(ResourceLoss.NOT_AFFECTED);
        assertThat(Db.history(tournament.id())).hasSize(2);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static List<UUID> fourRooms(World world) {
        List<UUID> rooms = new ArrayList<>(world.rooms());
        rooms.add(aResource().at(world.venue()).playStation(ConsoleType.PS5).create().id());
        rooms.add(aResource().at(world.venue()).playStation(ConsoleType.PS5).create().id());
        return rooms.stream().sorted().toList();
    }

    private CreatedTournament venueHosted(World world, List<UUID> rooms) {
        return creation.createByVenue(world.account().id(), TournamentRequest.of(Activity.PLAYSTATION,
                PlayMode.SINGLE, rooms, 16, world.startAt(), 120, TournamentVisibility.PUBLIC, null, null));
    }

    /** Venue-hosted on {@code rooms}, 8 players, through its real checkpoint and start: ACTIVE since 30 s ago. */
    private CreatedTournament active(World world, List<UUID> rooms) {
        CreatedTournament tournament = venueHosted(world, rooms);
        for (int i = 0; i < 8; i++) {
            participation.join(aUser().verified().create().id(), tournament.id());
        }
        Shift.pastStart(tournament.id(), Duration.ofSeconds(30));
        assertThat(due.fire(Tournaments.START + ":" + tournament.id())).isTrue();      // checkpoint, then start
        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("ACTIVE");
        return tournament;
    }

    /** The Venue declares a Resource Emergency Outage on {@code room}, back in an hour (inside the moved session). */
    private void outage(World world, UUID room) throws Exception {
        outage(world, room, ps.e7gz.support.fixtures.TrustStates.dbNow().plus(Duration.ofHours(1)));
    }

    /** As {@link #outage(World, UUID)}, expected back at {@code back}, which must lie inside an Operating Session. */
    private void outage(World world, UUID room, Instant back) throws Exception {
        mvc.perform(post("/api/v1/venue/emergencies/resource-outage")
                        .header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.VENUE,
                                world.account().id()))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\":\"" + room + "\",\"reason\":\"Overheating\",\"expectedReturnAt\":\""
                                + back + "\"}"))
                .andExpect(status().isCreated());
    }

    /** The Owner Review row and the restriction, as E7-84's cascade writes them. */
    static void enterOwnerReview(ps.e7gz.common.command.CommandContext c, UUID room) {
        c.jdbc().sql("""
                        INSERT INTO operations.owner_review (id, resource_id, entered_at, entry_cause, correlation_id)
                        VALUES (:id, :room, tournament.command_now(), 'ROLLING_MAINTENANCE_LIMIT', :correlation)""")
                .param("id", UUID.randomUUID()).param("room", room).param("correlation", CorrelationId.require())
                .update();
        c.jdbc().sql("UPDATE resource.resource SET operational_restriction = 'OWNER_REVIEW_REQUIRED' WHERE id = :room")
                .param("room", room).update();
    }

    private static String removalCause(UUID tournamentId, UUID room) {
        return OWNER.sql("""
                        SELECT removal_cause FROM tournament.tournament_resource
                         WHERE tournament_id = :t AND resource_id = :r""")
                .param("t", tournamentId).param("r", room).query(String.class).optional().orElse(null);
    }

    /** {@code active:releaseReason} of the Tournament's hold on {@code room}. */
    private static String holdOf(UUID tournamentId, UUID room) {
        return OWNER.sql("""
                        SELECT active_flag || ':' || COALESCE(release_reason, '') FROM inventory.resource_reservation
                         WHERE tournament_id = :t AND resource_id = :r AND reservation_type = 'TOURNAMENT_HOLD'""")
                .param("t", tournamentId).param("r", room).query(String.class).single();
    }

    private static long interruptions(UUID tournamentId) {
        return OWNER.sql("""
                        SELECT count(*) FROM operations.operational_interruption_record
                         WHERE subject_type = 'TOURNAMENT' AND subject_id = :id""")
                .param("id", tournamentId).query(Long.class).single();
    }
}
