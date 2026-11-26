package ps.e7gz.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aUser;

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
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
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
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.security.AccessTokens;
import ps.e7gz.identity.api.AccountDeletionTournaments;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.support.fixtures.TrustStates;
import ps.e7gz.tournament.internal.TournamentParticipantsIntegrationTest.Shift;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.Db;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.TestCommand;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.World;
import ps.e7gz.trust.BanFixtures;
import ps.e7gz.trust.api.TournamentParticipantCauses;
import ps.e7gz.trust.api.VerifiedPlayerTournamentCascade;

/**
 * E7-94 (S6-E4) - the Verified Player cascade O15 (arch 06 section 3.6, FR-TRN-20), the terminal reason priority when
 * causes compete (arch 06 section 3.2, FR-TRN-31), the ACCOUNT_DELETED over BAN precedence of O19 (arch 07 section 5.7,
 * ADR-023) and the roadmap's Phase 6 exit case of a Venue suspended after creation.
 *
 * <p>Revocation, suspension and restoration go through the Platform Admin's real HTTP routes; the Creator's VERIFIED
 * status is planted as one historical transition (the qualification that earned it is E7-72's and not this test's).
 * Account deletion runs {@code iam.anonymise_user} in an Admin command, as Sprint 7's deletion will.
 */
@AutoConfigureMockMvc
class TournamentPrecedenceIntegrationTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    private static final String VERIFIED_PLAYERS = "/api/v1/admin/verified-players/";

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
    TournamentApproval approval;

    @Autowired
    TournamentParticipation participation;

    @Autowired
    TournamentBoundaries boundaries;

    @Autowired
    VerifiedPlayerTournamentCascade vpCascade;

    @Autowired
    AccountDeletionTournaments deletions;

    @Autowired
    TournamentParticipantCauses banCauses;

    private DueJobs due;
    private CorrelationId.Scope correlation;
    private UUID admin;

    @BeforeEach
    void open() {
        correlation = CorrelationId.open(CorrelationId.generate());
        due = DueJobs.of(context, new TournamentCheckpointHandler(boundaries), new TournamentStartHandler(boundaries),
                new TournamentCompletionHandler(boundaries));
        admin = AuditFixtures.admin();
    }

    @AfterEach
    void close() {
        correlation.close();
    }

    // ------------------------------------------------------------------------------------------------ O15

    /**
     * FR-TRN-20: REVOKED cancels the Creator's PENDING_VENUE_APPROVAL and WAITING_FOR_PLAYERS Tournaments with
     * CREATOR_VERIFIED_STATUS_REVOKED - holds released, the Creator told - and leaves the CONFIRMED one alone. The
     * cascade ran once, under the transition's own key, in the revoking command.
     */
    @Test
    void aRevocationCancelsPendingAndWaitingButNotConfirmed() throws Exception {
        World first = World.create(Duration.ofMinutes(125));
        UUID creator = first.creator().id();
        plantVerified(creator);
        CreatedTournament confirmed = request(creator, first);
        approval.approve(first.account().id(), confirmed.id());
        joinN(confirmed.id(), 8);
        Shift.pastCheckpoint(confirmed.id(), Duration.ofMinutes(1));
        assertThat(due.fire(Tournaments.CHECKPOINT + ":" + confirmed.id())).isTrue();
        World second = World.create();
        CreatedTournament waiting = request(creator, second);
        approval.approve(second.account().id(), waiting.id());
        World third = World.create();
        CreatedTournament pending = request(creator, third);

        vp(creator, "revoke").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));

        for (CreatedTournament cancelled : List.of(pending, waiting)) {
            assertThat(Db.tournament(cancelled.id()).status()).isEqualTo("CANCELLED");
            assertThat(Db.tournament(cancelled.id()).terminalReason()).isEqualTo("CREATOR_VERIFIED_STATUS_REVOKED");
            assertThat(Db.history(cancelled.id())).last().satisfies(row -> {
                assertThat(row.actorType()).isEqualTo("SYSTEM");
                assertThat(row.reason()).isEqualTo("CREATOR_VERIFIED_STATUS_REVOKED");
            });
            assertThat(Db.holds(cancelled.id())).allSatisfy(hold -> assertThat(hold.active()).isFalse());
            assertThat(Db.liveJobs(cancelled.id())).isEmpty();
            assertThat(TournamentApprovalIntegrationTest.events(IntegrationEvents.TOURNAMENT_CANCELLED,
                    cancelled.id())).singleElement().satisfies(event -> assertThat(event.payload())
                            .containsEntry("terminalReason", "CREATOR_VERIFIED_STATUS_REVOKED")
                            .containsEntry("actorType", "SYSTEM")
                            .containsEntry("recipientId", creator.toString()));
        }
        assertThat(Db.tournament(confirmed.id()).status()).as("CONFIRMED remains").isEqualTo("CONFIRMED");
        assertThat(Db.holds(confirmed.id())).filteredOn(hold -> hold.type().equals("TOURNAMENT_HOLD")).hasSize(2)
                .allSatisfy(hold -> assertThat(hold.active()).isTrue());
        assertThat(cascades(creator)).as("one cascade, for the one revoking transition").hasSize(1);
    }

    /**
     * Reviewer checklist: running the cascade again with the same transition id changes nothing - no Tournament, hold,
     * history row or event moves - and the database refuses a second cascade record for that transition.
     */
    @Test
    void runningTheCascadeTwiceForOneTransitionChangesNothingTheSecondTime() throws Exception {
        World world = World.create();
        UUID creator = world.creator().id();
        plantVerified(creator);
        CreatedTournament waiting = request(creator, world);
        approval.approve(world.account().id(), waiting.id());
        vp(creator, "revoke").andExpect(status().isOk());
        UUID transitionId = cascades(creator).getFirst();
        int history = Db.history(waiting.id()).size();
        int events = TournamentApprovalIntegrationTest.events(IntegrationEvents.TOURNAMENT_CANCELLED, waiting.id())
                .size();

        runner.run(new TestCommand<>(LockSet.builder().users(creator).build(), c -> {
            vpCascade.cancelPendingHostedTournaments(c, creator, transitionId);
            return null;
        }));

        assertThat(Db.history(waiting.id())).hasSize(history);
        assertThat(TournamentApprovalIntegrationTest.events(IntegrationEvents.TOURNAMENT_CANCELLED, waiting.id()))
                .hasSize(events);
        Throwable duplicate = catchThrowable(() -> OWNER.sql("""
                        INSERT INTO trust.cascade_execution (id, cascade_type, cascade_key, subject_user_id, source_id,
                                                             executed_at, correlation_id)
                        SELECT gen_random_uuid(), cascade_type, cascade_key, subject_user_id, source_id, executed_at,
                               correlation_id
                          FROM trust.cascade_execution WHERE cascade_key = :key""")
                .param("key", "VP_TOURNAMENT_CASCADE:" + transitionId).update());
        assertThat(duplicate).as("the transition id is the cascade's idempotency key").isNotNull();
    }

    /** Reviewer checklist: restoring the status never resurrects the cancelled Tournament. */
    @Test
    void restoringTheStatusNeverResurrectsACancelledTournament() throws Exception {
        World world = World.create();
        UUID creator = world.creator().id();
        plantVerified(creator);
        CreatedTournament waiting = request(creator, world);
        approval.approve(world.account().id(), waiting.id());
        vp(creator, "revoke").andExpect(status().isOk());

        vp(creator, "restore").andExpect(status().isOk());

        assertThat(Db.tournament(waiting.id()).status()).isEqualTo("CANCELLED");
        assertThat(Db.tournament(waiting.id()).terminalReason()).isEqualTo("CREATOR_VERIFIED_STATUS_REVOKED");
        assertThat(Db.history(waiting.id())).hasSize(3);
        assertThat(Db.holds(waiting.id())).allSatisfy(hold -> assertThat(hold.active()).isFalse());
    }

    /** Arch 06 section 3.6: SUSPENDED is not REVOKED - every Tournament remains, pending and waiting too. */
    @Test
    void aSuspensionCancelsNothing() throws Exception {
        World first = World.create();
        UUID creator = first.creator().id();
        plantVerified(creator);
        CreatedTournament waiting = request(creator, first);
        approval.approve(first.account().id(), waiting.id());
        World second = World.create();
        CreatedTournament pending = request(creator, second);

        vp(creator, "suspend").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));

        assertThat(Db.tournament(waiting.id()).status()).isEqualTo("WAITING_FOR_PLAYERS");
        assertThat(Db.tournament(pending.id()).status()).isEqualTo("PENDING_VENUE_APPROVAL");
        assertThat(cascades(creator)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ priority

    /**
     * FR-TRN-31, arch 06 section 3.2: the checkpoint is overdue (the worker is late) with 7 Active Participants when
     * the Creator is revoked. The checkpoint owns the next transition and is decided as of checkpointAt, when the
     * Creator was still verified: MINIMUM_PARTICIPANTS_NOT_REACHED (priority 5), not the later revocation (6).
     */
    @Test
    void anOverdueCheckpointIsDecidedBeforeTheRevocation() throws Exception {
        World world = World.create(Duration.ofMinutes(125));
        UUID creator = world.creator().id();
        plantVerified(creator);
        CreatedTournament waiting = request(creator, world);
        approval.approve(world.account().id(), waiting.id());
        joinN(waiting.id(), 7);
        Instant checkpoint = Shift.pastCheckpoint(waiting.id(), Duration.ofMinutes(5));

        vp(creator, "revoke").andExpect(status().isOk());

        assertThat(Db.tournament(waiting.id()).terminalReason()).isEqualTo("MINIMUM_PARTICIPANTS_NOT_REACHED");
        assertThat(Db.holds(waiting.id())).filteredOn(hold -> hold.type().equals("TOURNAMENT_HOLD")).hasSize(2)
                .allSatisfy(hold -> assertThat(hold.releasedAt()).isEqualTo(checkpoint));
        assertThat(cascades(creator)).hasSize(1);
    }

    /**
     * FR-TRN-31: when an Owner Review (priority 1) is in force on a room of a waiting Tournament at the revocation,
     * the revocation (6) cancels it under the higher reason. The review row is planted past its triggers, as if E7-84's
     * own cascade had not reached the Tournament yet; the revocation is the Admin's real route.
     */
    @Test
    void aHigherConditionInForceWinsOverTheRevocation() throws Exception {
        World world = World.create();
        UUID creator = world.creator().id();
        plantVerified(creator);
        CreatedTournament waiting = request(creator, world);
        approval.approve(world.account().id(), waiting.id());
        UUID room = world.rooms().getFirst();
        Shift.withoutTriggers(jdbc -> jdbc.sql("""
                        INSERT INTO operations.owner_review (id, resource_id, entered_at, entry_cause, correlation_id)
                        VALUES (gen_random_uuid(), :room, clock_timestamp() - interval '1 minute',
                                'ROLLING_MAINTENANCE_LIMIT', gen_random_uuid())""")
                .param("room", room).update());

        vp(creator, "revoke").andExpect(status().isOk());

        assertThat(Db.tournament(waiting.id()).terminalReason()).isEqualTo("RESOURCE_OWNER_REVIEW_REQUIRED");
        assertThat(TournamentApprovalIntegrationTest.events(IntegrationEvents.TOURNAMENT_CANCELLED, waiting.id()))
                .singleElement().satisfies(event -> assertThat(event.payload())
                        .containsEntry("terminalReason", "RESOURCE_OWNER_REVIEW_REQUIRED"));
    }

    // ------------------------------------------------------------------------------------------------ O19

    /**
     * Arch 07 section 5.7, ADR-023: account deletion beats BAN. A JOINED participant and a BAN-only one are both
     * INELIGIBLE/ACCOUNT_DELETED after the deletion, the BAN-only reserved slot is released at once, and no later Ban
     * change restores either row or turns it back into BAN.
     */
    @Test
    void accountDeletionBeatsBanAndNeverDowngradesBack() {
        World world = World.create();
        CreatedTournament tournament = venueHosted(world, 8);
        List<UUID> players = joinN(tournament.id(), 8);
        UUID joined = players.get(0);
        UUID banned = players.get(1);
        UUID ban = BanFixtures.ban(banned, "ACTIVE", TrustStates.dbNow().minus(Duration.ofDays(1)),
                Duration.ofDays(2));
        reconcileBans(banned);
        assertThat(participant(tournament.id(), banned)).isEqualTo("INELIGIBLE:BAN");
        assertThat(counts(tournament.id())).as("active, occupied").containsExactly(7, 8);

        delete(joined);
        delete(banned);

        assertThat(participant(tournament.id(), joined)).isEqualTo("INELIGIBLE:ACCOUNT_DELETED");
        assertThat(participant(tournament.id(), banned)).isEqualTo("INELIGIBLE:ACCOUNT_DELETED");
        assertThat(counts(tournament.id())).as("the BAN-only slot is released").containsExactly(6, 6);

        endBan(ban);
        reconcileBans(banned);
        BanFixtures.ban(joined, "ACTIVE", TrustStates.dbNow().minus(Duration.ofHours(1)), Duration.ofDays(1));
        reconcileBans(joined);

        assertThat(participant(tournament.id(), banned)).as("never restored").isEqualTo("INELIGIBLE:ACCOUNT_DELETED");
        assertThat(participant(tournament.id(), joined)).as("never BAN again").isEqualTo("INELIGIBLE:ACCOUNT_DELETED");
        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("WAITING_FOR_PLAYERS");
    }

    /** The database refuses an anonymisation that leaves a JOINED participation behind; the port is required. */
    @Test
    void anAnonymisationWithoutTheTournamentSideIsRefused() {
        World world = World.create();
        CreatedTournament tournament = venueHosted(world, 8);
        UUID player = joinN(tournament.id(), 1).getFirst();

        Throwable refused = catchThrowable(() -> runner.run(TestCommands.under(CommandScope.admin(admin,
                CorrelationId.require(), LockSet.builder().users(player).build()), c ->
                c.jdbc().sql("SELECT iam.anonymise_user(:u, 'Deletion request')").param("u", player)
                        .query(UUID.class).single())));

        assertThat(refused).hasMessageContaining("ACCOUNT_DELETED");
        assertThat(participant(tournament.id(), player)).isEqualTo("JOINED:");
    }

    /** Running the deletion side twice changes nothing the second time. */
    @Test
    void theDeletionSideIsIdempotent() {
        World world = World.create();
        CreatedTournament tournament = venueHosted(world, 8);
        UUID player = joinN(tournament.id(), 1).getFirst();
        delete(player);
        int audits = participantAudits(tournament.id(), player);

        runner.run(TestCommands.under(CommandScope.admin(admin, CorrelationId.require(),
                LockSet.builder().users(player).build()), c -> {
                    deletions.accountDeleted(c, player);
                    return null;
                }));

        assertThat(participant(tournament.id(), player)).isEqualTo("INELIGIBLE:ACCOUNT_DELETED");
        assertThat(participantAudits(tournament.id(), player)).isEqualTo(audits);
    }

    // ------------------------------------------------------------------------------------------------ roadmap exit

    /**
     * Roadmap Phase 6 exit criterion: a Tournament at a Venue suspended after its creation still passes its checkpoint
     * (marketplace status stops new marketplace Tournaments, not the ones already accepted; FR-VEN-07).
     */
    @Test
    void aTournamentAtAVenueSuspendedAfterCreationStillPassesItsCheckpoint() throws Exception {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        joinN(tournament.id(), 8);

        mvc.perform(post("/api/v1/admin/venues/" + world.venue().id() + "/suspend")
                        .header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.ADMIN, admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Repeated complaints\"}"))
                .andExpect(status().isOk());
        assertThat(OWNER.sql("SELECT marketplace_status FROM venue.venue WHERE id = :id")
                .param("id", world.venue().id()).query(String.class).single()).isEqualTo("SUSPENDED");
        Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(1));

        assertThat(due.fire(Tournaments.CHECKPOINT + ":" + tournament.id())).isTrue();

        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("CONFIRMED");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private CreatedTournament request(UUID creator, World world) {
        return creation.requestByPlayer(creator, world.venue().id(), false, TournamentRequest.of(Activity.PLAYSTATION,
                PlayMode.SINGLE, world.rooms(), 16, world.startAt(), 120, TournamentVisibility.PUBLIC, null, null));
    }

    private CreatedTournament venueHosted(World world, int capacity) {
        return creation.createByVenue(world.account().id(), TournamentRequest.of(Activity.PLAYSTATION,
                PlayMode.SINGLE, world.rooms(), capacity, world.startAt(), 120, TournamentVisibility.PUBLIC, null,
                null));
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

    private ResultActions vp(UUID userId, String action) throws Exception {
        return mvc.perform(post(VERIFIED_PLAYERS + userId + "/" + action)
                .header("Authorization", "Bearer " + accessTokens.issue(AuthSubjectType.ADMIN, admin))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Fraud review\"}"));
    }

    /** The anonymisation and its Tournament side, in one Admin command naming the User (Sprint 7's deletion). */
    private void delete(UUID userId) {
        runner.run(TestCommands.under(CommandScope.admin(admin, CorrelationId.require(),
                LockSet.builder().users(userId).build()), c -> {
                    c.jdbc().sql("SELECT iam.anonymise_user(:u, 'Deletion request')").param("u", userId)
                            .query(UUID.class).single();
                    deletions.accountDeleted(c, userId);
                    return null;
                }));
    }

    private void reconcileBans(UUID userId) {
        runner.run(new TestCommand<>(LockSet.builder().users(userId).build(), c -> {
            banCauses.reconcileBans(c, userId);
            return null;
        }));
    }

    private List<Integer> counts(UUID tournamentId) {
        return runner.run(new TestCommand<>(LockSet.builder().tournaments(tournamentId).build(), c -> {
            TournamentParticipants participants = context.getBean(TournamentParticipants.class);
            return List.of(participants.activeParticipantCount(c, tournamentId, c.commandNow()),
                    participants.capacityOccupiedCount(c, tournamentId, c.commandNow()));
        }));
    }

    /** One VERIFIED transition a day ago, past the triggers: the status the Creator earned (E7-72). */
    private static void plantVerified(UUID userId) {
        Shift.withoutTriggers(jdbc -> jdbc.sql("""
                        INSERT INTO trust.verified_player_transition (id, user_id, transition_id, from_status, to_status,
                                                                      occurred_at, cause)
                        VALUES (gen_random_uuid(), :user, gen_random_uuid(), 'NOT_VERIFIED', 'VERIFIED',
                                clock_timestamp() - interval '1 day', 'TRUST_QUALIFICATION')""")
                .param("user", userId).update());
    }

    /** The revoking transitions whose cascade ran, oldest first. */
    private static List<UUID> cascades(UUID userId) {
        return OWNER.sql("""
                        SELECT v.transition_id FROM trust.cascade_execution c
                          JOIN trust.verified_player_transition v ON v.id = c.source_id
                         WHERE c.cascade_type = 'VERIFIED_PLAYER' AND c.subject_user_id = :user
                         ORDER BY c.executed_at""")
                .param("user", userId).query(UUID.class).list();
    }

    /** Ended a second ago, as its history: one instant for both the end and the duration, triggers off. */
    private static void endBan(UUID banId) {
        java.time.OffsetDateTime at = java.time.OffsetDateTime.ofInstant(TrustStates.dbNow().minusSeconds(1),
                java.time.ZoneOffset.UTC);
        Shift.withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE trust.ban_instance
                           SET ends_at = :at,
                               duration = make_interval(secs => EXTRACT(EPOCH FROM CAST(:at AS timestamptz))
                                                                - EXTRACT(EPOCH FROM activated_at))
                         WHERE id = :id""")
                .param("at", at).param("id", banId).update());
    }

    private static String participant(UUID tournamentId, UUID userId) {
        return OWNER.sql("""
                        SELECT status || ':' || COALESCE(ineligible_reason, '') FROM tournament.tournament_participant
                         WHERE tournament_id = :t AND user_id = :u""")
                .param("t", tournamentId).param("u", userId).query(String.class).single();
    }

    private static int participantAudits(UUID tournamentId, UUID userId) {
        return OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event a
                          JOIN tournament.tournament_participant p ON p.id = a.subject_id
                         WHERE a.subject_type = 'TOURNAMENT_PARTICIPANT' AND p.user_id = :u AND p.tournament_id = :t""")
                .param("u", userId).param("t", tournamentId).query(Integer.class).single();
    }
}
