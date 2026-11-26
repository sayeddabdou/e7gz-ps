package ps.e7gz.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aUser;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import ps.e7gz.audit.AuditFixtures;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentParticipantStatus;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.support.fixtures.TrustStates;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.Db;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.TestCommand;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.World;
import ps.e7gz.trust.BanFixtures;
import ps.e7gz.trust.api.TournamentParticipantCauses;

/**
 * E7-93 (S6-E3; arch 06 sections 3.3, 3.4 and 3.7, ADR-023, FR-TRN-12 to FR-TRN-17, FR-TRN-21, FR-TRN-23, FR-TRN-24,
 * FR-TRN-26): Join, Leave and Remove, the two counts, the checkpoint (T8), the start and the completion (T9), the
 * cancellation authority and the participant cause reconciler, on the real database with every trigger on, the timers
 * fired by the real deadline worker, and the deferred aggregate checks at each command's {@code COMMIT}.
 *
 * <p><b>Time.</b> The database clock cannot move, a Tournament is created at least 2 hours before it starts, and its
 * instants are immutable. To reach a boundary, {@link Shift} moves one Tournament's own instants - its interval,
 * checkpoint and creation, history and audit, participants, timers and holds, and its Operating Session with it - back
 * as the migration owner with triggers off, as if it had been made that long ago; every later write goes through every
 * guard. Bans are fixture rows ({@link BanFixtures}) handed to the reconciler the way Trust hands them over.
 *
 * <p><b>Done when</b>: {@link #sevenParticipantsAtTheCheckpointCancelAndReleaseInTheSameTransaction} and the BAN-only
 * reserved-slot cases ({@link #aBanOnlyReservedSlotKeepsAFullTournamentFullAndIsRestoredWithoutAFreeSlot} onwards).
 */
class TournamentParticipantsIntegrationTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    static final String OWNER_PIN = "4321";

    @Autowired
    ApplicationContext context;

    @Autowired
    CommandRunner runner;

    @Autowired
    Tournaments tournaments;

    @Autowired
    TournamentCreation creation;

    @Autowired
    TournamentApproval approval;

    @Autowired
    TournamentParticipation participation;

    @Autowired
    TournamentCancellation cancellation;

    @Autowired
    TournamentBoundaries boundaries;

    @Autowired
    TournamentParticipantCauses causes;

    @Autowired
    PasswordEncoder encoder;

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
     * FR-TRN-13, FR-TRN-14, Spec section 37.5: 7 Active Participants at the checkpoint - CANCELLED /
     * MINIMUM_PARTICIPANTS_NOT_REACHED by SYSTEM, every hold released at checkpointAt in the checkpoint's own
     * transaction, every timer done or withdrawn. There is no "Run Anyway".
     */
    @Test
    void sevenParticipantsAtTheCheckpointCancelAndReleaseInTheSameTransaction() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        joinN(tournament, 7);
        Instant checkpoint = Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(5));

        assertThat(due.fire(checkpoint(tournament.id()))).isTrue();

        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("CANCELLED");
        assertThat(Db.tournament(tournament.id()).terminalReason()).isEqualTo("MINIMUM_PARTICIPANTS_NOT_REACHED");
        assertThat(Db.holds(tournament.id())).hasSize(2).allSatisfy(hold -> {
            assertThat(hold.active()).isFalse();
            assertThat(hold.releasedAt()).as("as of the checkpoint, not of the worker").isEqualTo(checkpoint);
            assertThat(hold.releaseReason()).isEqualTo("MINIMUM_PARTICIPANTS_NOT_REACHED");
        });
        assertThat(Db.activeHoldsOn(world.rooms())).isZero();
        assertThat(Db.history(tournament.id())).last().isEqualTo(new Db.HistoryRow(2, "WAITING_FOR_PLAYERS",
                "CANCELLED", "MINIMUM_PARTICIPANTS_NOT_REACHED", "SYSTEM", null));
        assertThat(DueJobs.job(checkpoint(tournament.id())).status()).isEqualTo("DONE");
        assertThat(Db.liveJobs(tournament.id())).isEmpty();
    }

    @Test
    void eightActiveParticipantsAtTheCheckpointConfirm() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        joinN(tournament, 8);
        Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(1));

        assertThat(due.fire(checkpoint(tournament.id()))).isTrue();

        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("CONFIRMED");
        assertThat(Db.holds(tournament.id())).allSatisfy(hold -> assertThat(hold.active()).isTrue());
        assertThat(Db.history(tournament.id())).last().isEqualTo(new Db.HistoryRow(2, "WAITING_FOR_PLAYERS",
                "CONFIRMED", null, "SYSTEM", null));
        assertThat(Db.liveJobs(tournament.id())).extracting(Db.JobRow::type)
                .containsExactlyInAnyOrder(Tournaments.START, Tournaments.COMPLETION);
    }

    /**
     * Reviewer checklist, ADR-023: at full capacity a BAN-only reserved slot still occupies capacity, so a new Join is
     * TOURNAMENT_CAPACITY_REACHED; when the Ban ends before Start the banned Participant is restored into that slot
     * without needing a free one - and the Tournament is full again, not overfilled.
     */
    @Test
    void aBanOnlyReservedSlotKeepsAFullTournamentFullAndIsRestoredWithoutAFreeSlot() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 8);
        List<UUID> players = joinN(tournament, 8);
        UUID banned = players.getFirst();
        UUID ban = banLongAgo(banned, Duration.ofHours(1));
        reconcile(banned);

        assertThat(participant(tournament.id(), banned)).isEqualTo("INELIGIBLE:BAN");
        assertThat(counts(tournament.id())).as("active, occupied").containsExactly(7, 8);
        UUID newcomer = aUser().verified().create().id();
        assertCode(() -> participation.join(newcomer, tournament.id()), ApiErrorCode.TOURNAMENT_CAPACITY_REACHED);

        endBan(ban, TrustStates.dbNow().minusSeconds(1));
        reconcile(banned);

        assertThat(participant(tournament.id(), banned)).isEqualTo("JOINED:");
        assertThat(counts(tournament.id())).containsExactly(8, 8);
        assertThat(audits(banned, tournament.id())).containsExactly("TOURNAMENT_PARTICIPANT_JOINED:CUSTOMER",
                "TOURNAMENT_PARTICIPANT_INELIGIBLE:SYSTEM", "TOURNAMENT_PARTICIPANT_RESTORED:SYSTEM");
        assertCode(() -> participation.join(newcomer, tournament.id()), ApiErrorCode.TOURNAMENT_CAPACITY_REACHED);
    }

    /** Reviewer checklist: the Ban ended before checkpointAt and nothing ran since - the checkpoint counts them. */
    @Test
    void aBanThatEndedBeforeTheCheckpointIsCountedEvenWhenTheWorkerRunsLate() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        List<UUID> players = joinN(tournament, 8);
        UUID banned = players.getFirst();
        UUID ban = banLongAgo(banned, Duration.ofHours(1));
        reconcile(banned);
        assertThat(participant(tournament.id(), banned)).isEqualTo("INELIGIBLE:BAN");

        Instant checkpoint = Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(40));   // the worker is late
        endBan(ban, checkpoint.minus(Duration.ofMinutes(10)));          // ended before it; its expiry never ran

        assertThat(due.fire(checkpoint(tournament.id()))).isTrue();

        assertThat(Db.tournament(tournament.id()).status()).as("8 Active as of checkpointAt").isEqualTo("CONFIRMED");
        assertThat(participant(tournament.id(), banned)).isEqualTo("JOINED:");
        assertThat(restoredAsOf(banned, tournament.id())).isEqualTo(checkpoint.toString());
    }

    /** A Ban still in force at checkpointAt is not restored there, even if it ended before the worker ran. */
    @Test
    void aBanStillInForceAtTheCheckpointIsNotCountedThere() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        List<UUID> players = joinN(tournament, 8);
        UUID banned = players.getFirst();
        UUID ban = banLongAgo(banned, Duration.ofHours(1));
        reconcile(banned);
        Instant checkpoint = Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(40));
        endBan(ban, checkpoint.plus(Duration.ofMinutes(10)));           // ended after the checkpoint, before now

        assertThat(due.fire(checkpoint(tournament.id()))).isTrue();

        assertThat(Db.tournament(tournament.id()).terminalReason()).isEqualTo("MINIMUM_PARTICIPANTS_NOT_REACHED");
        assertThat(participant(tournament.id(), banned)).isEqualTo("INELIGIBLE:BAN");
    }

    /**
     * Reviewer checklist: a cancelled Tournament never resurrects - the Ban that kept it at 7 ends later, before
     * startAt, and the reconciler leaves the cancelled Tournament and its row alone.
     */
    @Test
    void aCancelledTournamentIsNeverResurrectedByALaterBanEnd() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        List<UUID> players = joinN(tournament, 8);
        UUID banned = players.getFirst();
        UUID ban = banLongAgo(banned, Duration.ofHours(2));
        reconcile(banned);
        Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(2));
        assertThat(due.fire(checkpoint(tournament.id()))).isTrue();
        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("CANCELLED");

        endBan(ban, TrustStates.dbNow().minusSeconds(1));
        reconcile(banned);

        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("CANCELLED");
        assertThat(participant(tournament.id(), banned)).isEqualTo("INELIGIBLE:BAN");
        assertThat(Db.history(tournament.id())).hasSize(2);
    }

    /**
     * Reviewer checklist, Product section 37.11: a Ban ending exactly at startAt does not restore at the start, and
     * no restoration happens at or after startAt - the reserved slot ends with the Start.
     */
    @Test
    void aBanEndingAtOrAfterStartNeverRestores() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        List<UUID> players = joinN(tournament, 9);
        UUID banned = players.getFirst();
        UUID ban = banLongAgo(banned, Duration.ofHours(5));
        reconcile(banned);
        Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(1));
        assertThat(due.fire(checkpoint(tournament.id()))).isTrue();
        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("CONFIRMED");

        Instant start = Shift.pastStart(tournament.id(), Duration.ofMinutes(3));
        endBan(ban, start);                                               // ends exactly at startAt
        assertThat(due.fire(start(tournament.id()))).isTrue();

        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("ACTIVE");
        assertThat(participant(tournament.id(), banned)).isEqualTo("INELIGIBLE:BAN");
        reconcile(banned);                                                // the Ban is over now: still no return
        assertThat(participant(tournament.id(), banned)).isEqualTo("INELIGIBLE:BAN");
        assertThat(counts(tournament.id())).as("no reserved slot after Start").containsExactly(8, 8);
    }

    /** A Ban that ended just before startAt is restored at the start, even when the start worker is late. */
    @Test
    void aBanEndingBeforeStartIsRestoredAtTheStartAsOfStartAt() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        List<UUID> players = joinN(tournament, 9);
        UUID banned = players.getFirst();
        UUID ban = banLongAgo(banned, Duration.ofHours(5));
        reconcile(banned);
        Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(1));
        assertThat(due.fire(checkpoint(tournament.id()))).isTrue();

        Instant start = Shift.pastStart(tournament.id(), Duration.ofMinutes(3));
        endBan(ban, start.minusSeconds(1));
        assertThat(due.fire(start(tournament.id()))).isTrue();

        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("ACTIVE");
        assertThat(participant(tournament.id(), banned)).isEqualTo("JOINED:");
        assertThat(restoredAsOf(banned, tournament.id())).isEqualTo(start.toString());
    }

    /** Reviewer checklist, FR-TRN-14: a drop below 8 after CONFIRMED does not cancel; it still starts. */
    @Test
    void aDropBelowEightAfterConfirmationDoesNotCancel() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        List<UUID> players = joinN(tournament, 8);
        Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(1));
        assertThat(due.fire(checkpoint(tournament.id()))).isTrue();

        participation.leave(players.get(0), tournament.id());
        participation.leave(players.get(1), tournament.id());
        banLongAgo(players.get(2), Duration.ofDays(1));
        reconcile(players.get(2));

        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("CONFIRMED");
        assertThat(counts(tournament.id()).getFirst()).isEqualTo(5);
        Shift.pastStart(tournament.id(), Duration.ofSeconds(30));
        assertThat(due.fire(start(tournament.id()))).isTrue();
        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------------------------------------ join

    @Test
    void joinTakesASlotAndCountsBothWays() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 8);
        UUID player = aUser().verified().create().id();

        TournamentParticipation.Participation joined = participation.join(player, tournament.id());

        assertThat(joined).isEqualTo(new TournamentParticipation.Participation(tournament.id(), player,
                TournamentParticipantStatus.JOINED, TournamentStatus.WAITING_FOR_PLAYERS));
        assertThat(participant(tournament.id(), player)).isEqualTo("JOINED:");
        assertThat(counts(tournament.id())).containsExactly(1, 1);
        assertThat(audits(player, tournament.id())).containsExactly("TOURNAMENT_PARTICIPANT_JOINED:CUSTOMER");
        assertThat(participation.join(player, tournament.id())).as("again: unchanged").isEqualTo(joined);
        assertThat(audits(player, tournament.id())).hasSize(1);
    }

    @Test
    void theLastSlotGoesToOnePlayerOnly() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 8);
        joinN(tournament, 8);

        assertCode(() -> participation.join(aUser().verified().create().id(), tournament.id()),
                ApiErrorCode.TOURNAMENT_CAPACITY_REACHED);
        assertThat(counts(tournament.id())).containsExactly(8, 8);
    }

    @Test
    void joinRefusesTheUnverifiedTheBannedAndTheClosedWindow() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        UUID unverified = aUser().unverified().create().id();
        UUID banned = aUser().verified().create().id();
        BanFixtures.activeBan(banned);

        assertCode(() -> participation.join(unverified, tournament.id()), ApiErrorCode.PHONE_VERIFICATION_REQUIRED);
        assertCode(() -> participation.join(banned, tournament.id()), ApiErrorCode.ACCOUNT_BANNED_FOR_THIS_ACTION);

        Shift.pastStart(tournament.id(), Duration.ofSeconds(30));      // WAITING, its checkpoint and start both due
        assertCode(() -> participation.join(aUser().verified().create().id(), tournament.id()),
                ApiErrorCode.TOURNAMENT_JOIN_WINDOW_CLOSED);
    }

    /** FR-TRN-12: after a checkpoint that confirmed, Join stays open until startAt - the boundary settled first. */
    @Test
    void joinAfterTheCheckpointConfirmsItFirstAndIsStillOpen() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        joinN(tournament, 8);
        Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(1));   // T8 has not run

        TournamentParticipation.Participation late = participation.join(aUser().verified().create().id(),
                tournament.id());

        assertThat(late.tournamentStatus()).isEqualTo(TournamentStatus.CONFIRMED);
        assertThat(counts(tournament.id())).containsExactly(9, 9);
        assertThat(DueJobs.job(checkpoint(tournament.id())).status()).isEqualTo("SUPERSEDED");
        assertThat(due.fire(checkpoint(tournament.id()))).isFalse();
    }

    /** FR-TRN-06, FR-TRN-07: private by code only, never while pending; a public one never by code. */
    @Test
    void aPrivateTournamentIsJoinedOnlyWithItsCodeOnceApproved() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament pending = creation.requestByPlayer(world.creator().id(), world.venue().id(), false,
                TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, world.rooms(), 16, world.startAt(), 120,
                        TournamentVisibility.PRIVATE, null, null));
        UUID player = aUser().verified().create().id();
        String code = OWNER.sql("SELECT invite_code FROM tournament.tournament WHERE id = :id")
                .param("id", pending.id()).query(String.class).single();

        assertCode(() -> participation.joinPrivate(player, code), ApiErrorCode.NOT_FOUND);  // code inactive
        approval.approve(world.account().id(), pending.id());
        assertCode(() -> participation.join(player, pending.id()), ApiErrorCode.NOT_FOUND);   // not by id

        assertThat(participation.joinPrivate(player, code).status()).isEqualTo(TournamentParticipantStatus.JOINED);
        assertCode(() -> participation.joinPrivate(player, "NO-SUCH-CODE"), ApiErrorCode.NOT_FOUND);
    }

    // ------------------------------------------------------------------------------------------------ leave, remove

    @Test
    void leaveFreesTheSlotAndALeaverMayComeBack() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 8);
        List<UUID> players = joinN(tournament, 8);

        assertThat(participation.leave(players.getFirst(), tournament.id()).status())
                .isEqualTo(TournamentParticipantStatus.LEFT);
        assertThat(counts(tournament.id())).containsExactly(7, 7);
        assertCode(() -> participation.leave(players.getFirst(), tournament.id()),
                ApiErrorCode.OPERATIONAL_STATE_CHANGED);

        assertThat(participation.join(players.getFirst(), tournament.id()).status())
                .isEqualTo(TournamentParticipantStatus.JOINED);
        assertThat(audits(players.getFirst(), tournament.id())).containsExactly(
                "TOURNAMENT_PARTICIPANT_JOINED:CUSTOMER", "TOURNAMENT_PARTICIPANT_LEFT:CUSTOMER",
                "TOURNAMENT_PARTICIPANT_JOINED:CUSTOMER");
        assertCode(() -> participation.leave(aUser().verified().create().id(), tournament.id()),
                ApiErrorCode.NOT_FOUND);
    }

    @Test
    void leaveAndRemoveCloseAtStart() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = playerHostedApproved(world);
        List<UUID> players = joinN(tournament, 8);
        Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(1));
        assertThat(due.fire(checkpoint(tournament.id()))).isTrue();
        Shift.pastStart(tournament.id(), Duration.ofSeconds(30));

        assertCode(() -> participation.leave(players.getFirst(), tournament.id()), ApiErrorCode.DEADLINE_PASSED);
        assertCode(() -> participation.remove(world.creator().id(), tournament.id(), players.get(1)),
                ApiErrorCode.DEADLINE_PASSED);
    }

    /** FR-TRN-12: the Organizer of a Player-hosted Tournament - its Creator - removes; the removal is final. */
    @Test
    void theCreatorRemovesAParticipantForGood() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = playerHostedApproved(world);
        List<UUID> players = joinN(tournament, 2);

        assertCode(() -> participation.remove(players.get(1), tournament.id(), players.getFirst()),
                ApiErrorCode.FORBIDDEN);
        assertThat(participation.remove(world.creator().id(), tournament.id(), players.getFirst()).status())
                .isEqualTo(TournamentParticipantStatus.REMOVED);

        assertThat(audits(players.getFirst(), tournament.id())).last()
                .isEqualTo("TOURNAMENT_PARTICIPANT_REMOVED:CUSTOMER");
        assertCode(() -> participation.join(players.getFirst(), tournament.id()), ApiErrorCode.FORBIDDEN);
        assertCode(() -> participation.remove(world.creator().id(), tournament.id(), world.creator().id()),
                ApiErrorCode.FORBIDDEN);
    }

    @Test
    void aVenueHostedTournamentHasNoCustomerOrganizer() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        List<UUID> players = joinN(tournament, 2);

        assertCode(() -> participation.remove(players.get(1), tournament.id(), players.getFirst()),
                ApiErrorCode.FORBIDDEN);
    }

    // ------------------------------------------------------------------------------------------------ the ban path

    /** O14: a Ban in force while ACTIVE makes the row INELIGIBLE too; it never comes back. */
    @Test
    void aBanDuringTheTournamentMakesTheParticipantIneligibleForGood() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        List<UUID> players = joinN(tournament, 8);
        Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(1));
        assertThat(due.fire(checkpoint(tournament.id()))).isTrue();
        Shift.pastStart(tournament.id(), Duration.ofSeconds(30));
        assertThat(due.fire(start(tournament.id()))).isTrue();

        UUID ban = BanFixtures.ban(players.getFirst(), "ACTIVE", TrustStates.dbNow().minusSeconds(1),
                Duration.ofMinutes(1));
        reconcile(players.getFirst());
        assertThat(participant(tournament.id(), players.getFirst())).isEqualTo("INELIGIBLE:BAN");

        endBan(ban, TrustStates.dbNow().minusSeconds(1));
        reconcile(players.getFirst());
        assertThat(participant(tournament.id(), players.getFirst())).isEqualTo("INELIGIBLE:BAN");
        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("ACTIVE");
    }

    /**
     * ADR-023 item 5: a Ban change reaching a Tournament whose checkpoint is due settles the checkpoint first, as of
     * checkpointAt, then marks the row - the participant set never changes across a due boundary.
     */
    @Test
    void aBanReachingAnOverdueCheckpointSettlesItFirst() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        List<UUID> players = joinN(tournament, 8);
        Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(2));

        BanFixtures.ban(players.getFirst(), "ACTIVE", TrustStates.dbNow().minusSeconds(1), Duration.ofDays(1));
        reconcile(players.getFirst());

        assertThat(Db.tournament(tournament.id()).status()).as("8 Active at checkpointAt").isEqualTo("CONFIRMED");
        assertThat(participant(tournament.id(), players.getFirst())).isEqualTo("INELIGIBLE:BAN");
        assertThat(DueJobs.job(checkpoint(tournament.id())).status()).isEqualTo("SUPERSEDED");
    }

    @Test
    void reconcilingTwiceChangesNothingTheSecondTime() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        UUID player = joinN(tournament, 1).getFirst();
        BanFixtures.ban(player, "ACTIVE", TrustStates.dbNow().minusSeconds(1), Duration.ofDays(1));

        reconcile(player);
        reconcile(player);

        assertThat(audits(player, tournament.id())).containsExactly("TOURNAMENT_PARTICIPANT_JOINED:CUSTOMER",
                "TOURNAMENT_PARTICIPANT_INELIGIBLE:SYSTEM");
    }

    // ------------------------------------------------------------------------------------------------ start, end

    @Test
    void startAndCompletionFollowTheBoundaries() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        joinN(tournament, 8);
        Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(1));
        assertThat(due.fire(start(tournament.id()))).as("not due yet").isFalse();
        assertThat(due.fire(checkpoint(tournament.id()))).isTrue();
        Shift.pastStart(tournament.id(), Duration.ofSeconds(30));
        assertThat(due.fire(start(tournament.id()))).isTrue();
        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("ACTIVE");
        assertThat(Db.history(tournament.id())).last().isEqualTo(new Db.HistoryRow(3, "CONFIRMED", "ACTIVE", null,
                "SYSTEM", null));

        Instant end = Shift.pastEnd(tournament.id(), Duration.ofMinutes(7));
        assertThat(due.fire(completion(tournament.id()))).isTrue();

        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("COMPLETED");
        assertThat(Db.tournament(tournament.id()).terminalReason()).isNull();
        assertThat(Db.holds(tournament.id())).allSatisfy(hold -> {
            assertThat(hold.active()).isFalse();
            assertThat(hold.releasedAt()).isEqualTo(end);
            assertThat(hold.releaseReason()).isEqualTo("COMPLETED");
        });
        assertThat(Db.tournament(tournament.id()).venueCostTotal()).as("never recomputed").isEqualByComparingTo("400.00");
        assertThat(Db.liveJobs(tournament.id())).isEmpty();
    }

    /** A completion timer firing first after every boundary passed settles the checkpoint and the start before it. */
    @Test
    void theCompletionTimerFiredFirstSettlesEveryEarlierBoundaryInOrder() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        joinN(tournament, 8);
        Shift.pastEnd(tournament.id(), Duration.ofMinutes(1));

        assertThat(due.fire(completion(tournament.id()))).isTrue();

        assertThat(Db.history(tournament.id())).extracting(Db.HistoryRow::to)
                .containsExactly("WAITING_FOR_PLAYERS", "CONFIRMED", "ACTIVE", "COMPLETED");
        assertThat(Db.jobs(tournament.id())).extracting(Db.JobRow::status)
                .containsExactlyInAnyOrder("SUPERSEDED", "DONE", "SUPERSEDED");
    }

    // ------------------------------------------------------------------------------------------------ cancellations

    @Test
    void theCreatorCancelsBeforeTheCheckpoint() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = playerHostedApproved(world);
        joinN(tournament, 3);

        AnsweredTournament cancelled = cancellation.cancelByCreator(world.creator().id(), tournament.id());

        assertThat(cancelled).isEqualTo(new AnsweredTournament(tournament.id(), TournamentStatus.CANCELLED,
                TournamentTerminalReason.CREATOR_CANCELLED));
        assertThat(Db.history(tournament.id())).last().isEqualTo(new Db.HistoryRow(3, "WAITING_FOR_PLAYERS",
                "CANCELLED", "CREATOR_CANCELLED", "CUSTOMER", world.creator().id()));
        assertThat(Db.activeHoldsOn(world.rooms())).isZero();
        assertThat(Db.liveJobs(tournament.id())).isEmpty();
        assertThat(TournamentApprovalIntegrationTest.events(IntegrationEvents.TOURNAMENT_CANCELLED, tournament.id()))
                .singleElement().satisfies(event -> assertThat(event.payload())
                        .containsEntry("terminalReason", "CREATOR_CANCELLED").containsEntry("actorType", "CUSTOMER"));
    }

    /** Reviewer checklist, FR-TRN-21: at or after the checkpoint the Creator may no longer cancel - T8 run or not. */
    @Test
    void theCreatorMayNotCancelAtOrAfterTheCheckpoint() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = playerHostedApproved(world);
        joinN(tournament, 8);
        Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(1));

        assertCode(() -> cancellation.cancelByCreator(world.creator().id(), tournament.id()),
                ApiErrorCode.DEADLINE_PASSED);
        assertThat(Db.tournament(tournament.id()).status()).as("the refusal rolled the settling back too")
                .isEqualTo("WAITING_FOR_PLAYERS");

        assertThat(due.fire(checkpoint(tournament.id()))).isTrue();
        assertCode(() -> cancellation.cancelByCreator(world.creator().id(), tournament.id()),
                ApiErrorCode.DEADLINE_PASSED);
        assertCode(() -> cancellation.cancelByCreator(aUser().verified().create().id(), tournament.id()),
                ApiErrorCode.FORBIDDEN);
    }

    @Test
    void theVenueCancelsAWaitingTournamentWithoutTheOwnerPin() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);

        AnsweredTournament cancelled = cancellation.cancelByVenue(world.account().id(), tournament.id(), null);

        assertThat(cancelled.terminalReason()).isEqualTo(TournamentTerminalReason.VENUE_CANCELLED);
        assertThat(Db.history(tournament.id())).last().isEqualTo(new Db.HistoryRow(2, "WAITING_FOR_PLAYERS",
                "CANCELLED", "VENUE_CANCELLED", "VENUE", world.account().id()));
        assertThat(Db.activeHoldsOn(world.rooms())).isZero();
    }

    /** Reviewer checklist, FR-TRN-24: the Venue's cancel of a CONFIRMED Tournament needs the Owner PIN. */
    @Test
    void theVenueCancelsAConfirmedTournamentOnlyWithTheOwnerPin() {
        World world = World.create(Duration.ofMinutes(125));
        ownerPin(world);
        CreatedTournament tournament = venueHosted(world, 16);
        joinN(tournament, 8);
        Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(1));
        assertThat(due.fire(checkpoint(tournament.id()))).isTrue();

        assertCode(() -> cancellation.cancelByVenue(world.account().id(), tournament.id(), null),
                ApiErrorCode.OWNER_PASSWORD_REQUIRED);
        assertCode(() -> cancellation.cancelByVenue(world.account().id(), tournament.id(), "0000"),
                ApiErrorCode.OWNER_PASSWORD_INVALID);
        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("CONFIRMED");

        cancellation.cancelByVenue(world.account().id(), tournament.id(), OWNER_PIN);

        assertThat(Db.tournament(tournament.id()).terminalReason()).isEqualTo("VENUE_CANCELLED");
        assertThat(transitionAudit(tournament.id(), "CANCELLED")).isEqualTo("true:SUCCESS:");
        assertThat(Db.activeHoldsOn(world.rooms())).isZero();
    }

    /** A WAITING Tournament whose checkpoint confirmed it meanwhile now needs the PIN. */
    @Test
    void aVenueCancelRacingTheCheckpointAsksForTheOwnerPin() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament tournament = venueHosted(world, 16);
        joinN(tournament, 8);
        Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(1));   // still WAITING when read

        assertCode(() -> cancellation.cancelByVenue(world.account().id(), tournament.id(), null),
                ApiErrorCode.OWNER_PASSWORD_REQUIRED);
        assertThat(Db.tournament(tournament.id()).status()).isEqualTo("WAITING_FOR_PLAYERS");
    }

    @Test
    void aVenueCancelsNeitherAPendingRequestNorAnActiveTournamentNorAnotherVenues() {
        World world = World.create(Duration.ofMinutes(125));
        World other = World.create(Duration.ofMinutes(125));
        CreatedTournament pending = creation.requestByPlayer(world.creator().id(), world.venue().id(), false,
                TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, List.of(world.rooms().getFirst()), 16,
                        world.startAt(), 120, TournamentVisibility.PUBLIC, null, null));

        assertCode(() -> cancellation.cancelByVenue(world.account().id(), pending.id(), null),
                ApiErrorCode.OPERATIONAL_STATE_CHANGED);
        assertCode(() -> cancellation.cancelByVenue(other.account().id(), pending.id(), null), ApiErrorCode.NOT_FOUND);
    }

    /** FR-TRN-23: ACTIVE -> CANCELLED / TOURNAMENT_OPERATIONAL_TERMINATION, the PIN and the reason on its audit row. */
    @Test
    void operationalTerminationNeedsThePinAndAReason() {
        World world = World.create(Duration.ofMinutes(125));
        ownerPin(world);
        CreatedTournament tournament = venueHosted(world, 16);
        joinN(tournament, 8);
        assertCode(() -> cancellation.terminate(world.account().id(), tournament.id(), OWNER_PIN, "Power cut"),
                ApiErrorCode.OPERATIONAL_STATE_CHANGED);                    // not ACTIVE yet
        Shift.pastCheckpoint(tournament.id(), Duration.ofMinutes(1));
        assertThat(due.fire(checkpoint(tournament.id()))).isTrue();
        Shift.pastStart(tournament.id(), Duration.ofSeconds(30));
        assertThat(due.fire(start(tournament.id()))).isTrue();

        assertCode(() -> cancellation.terminate(world.account().id(), tournament.id(), OWNER_PIN, "  "),
                ApiErrorCode.VALIDATION_FAILED);
        assertCode(() -> cancellation.terminate(world.account().id(), tournament.id(), null, "Power cut"),
                ApiErrorCode.OWNER_PASSWORD_REQUIRED);

        AnsweredTournament terminated = cancellation.terminate(world.account().id(), tournament.id(), OWNER_PIN,
                "Power cut in the hall");

        assertThat(terminated.terminalReason()).isEqualTo(TournamentTerminalReason.TOURNAMENT_OPERATIONAL_TERMINATION);
        assertThat(transitionAudit(tournament.id(), "CANCELLED")).isEqualTo("true:SUCCESS:Power cut in the hall");
        assertThat(Db.activeHoldsOn(world.rooms())).isZero();
        assertThat(Db.liveJobs(tournament.id())).isEmpty();
    }

    /** FR-TRN-21: a Platform Admin may cancel any non-completed Tournament - ACTIVE too - with a reason. */
    @Test
    void anAdminCancelsAnActiveTournamentButNotACompletedOne() {
        World world = World.create(Duration.ofMinutes(125));
        CreatedTournament active = venueHosted(world, 16);
        joinN(active, 8);
        Shift.pastStart(active.id(), Duration.ofSeconds(30));
        assertThat(due.fire(start(active.id()))).isTrue();              // checkpoint, then start
        UUID admin = AuditFixtures.admin();

        AnsweredTournament cancelled = cancellation.cancelByAdmin(admin, active.id(), "Duplicate listing");

        assertThat(cancelled.terminalReason()).isEqualTo(TournamentTerminalReason.ADMIN_CANCELLED);
        assertThat(Db.history(active.id())).last().isEqualTo(new Db.HistoryRow(4, "ACTIVE", "CANCELLED",
                "ADMIN_CANCELLED", "ADMIN", admin));
        assertThat(transitionAudit(active.id(), "CANCELLED")).isEqualTo("null:null:Duplicate listing");
        assertCode(() -> cancellation.cancelByAdmin(admin, active.id(), "again"), ApiErrorCode.ADMIN_ACTION_NOT_ALLOWED);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private CreatedTournament venueHosted(World world, int capacity) {
        return creation.createByVenue(world.account().id(), TournamentRequest.of(Activity.PLAYSTATION,
                PlayMode.SINGLE, world.rooms(), capacity, world.startAt(), 120, TournamentVisibility.PUBLIC, null,
                null));
    }

    /** The World's creator asks, not playing; the Venue approves: WAITING_FOR_PLAYERS. */
    private CreatedTournament playerHostedApproved(World world) {
        CreatedTournament pending = creation.requestByPlayer(world.creator().id(), world.venue().id(), false,
                TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, world.rooms(), 16, world.startAt(), 120,
                        TournamentVisibility.PUBLIC, null, null));
        approval.approve(world.account().id(), pending.id());
        return pending;
    }

    private List<UUID> joinN(CreatedTournament tournament, int n) {
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID player = aUser().verified().create().id();
            participation.join(player, tournament.id());
            players.add(player);
        }
        return players;
    }

    /** What Trust does at the end of every reconciliation, in a command that names the User. */
    private void reconcile(UUID userId) {
        runner.run(new TestCommand<>(LockSet.builder().users(userId).build(), c -> {
            causes.reconcileBans(c, userId);
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

    private void ownerPin(World world) {
        OWNER.sql("""
                        INSERT INTO iam.venue_owner_secret (venue_account_id, owner_password_hash, changed_at, version)
                        VALUES (:account, :hash, now() - interval '1 day', 0)
                        ON CONFLICT (venue_account_id) DO NOTHING""")
                .param("account", world.account().id()).param("hash", encoder.encode(OWNER_PIN)).update();
    }

    /**
     * An ACTIVE Ban activated 30 days ago and still in force for {@code remaining}: long before any boundary a
     * {@link Shift} moves a Tournament to, so it was in force at that boundary unless {@link #endBan} ends it earlier.
     */
    private static UUID banLongAgo(UUID userId, Duration remaining) {
        Duration history = Duration.ofDays(30);
        return BanFixtures.ban(userId, "ACTIVE", TrustStates.dbNow().minus(history), history.plus(remaining));
    }

    /** Ends the Ban at {@code at} as its history: the instant and its duration, triggers off. */
    private static void endBan(UUID banId, Instant at) {
        Shift.withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE trust.ban_instance
                           SET ends_at = :at,
                               duration = make_interval(secs => EXTRACT(EPOCH FROM CAST(:at AS timestamptz))
                                                                - EXTRACT(EPOCH FROM activated_at))
                         WHERE id = :id""")
                .param("at", utc(at)).param("id", banId).update());
    }

    private static String participant(UUID tournamentId, UUID userId) {
        return OWNER.sql("""
                        SELECT status || ':' || COALESCE(ineligible_reason, '') FROM tournament.tournament_participant
                         WHERE tournament_id = :t AND user_id = :u""")
                .param("t", tournamentId).param("u", userId).query(String.class).single();
    }

    private static List<String> audits(UUID userId, UUID tournamentId) {
        return OWNER.sql("""
                        SELECT a.action || ':' || a.actor_type FROM audit.audit_event a
                          JOIN tournament.tournament_participant p ON p.id = a.subject_id
                         WHERE a.subject_type = 'TOURNAMENT_PARTICIPANT' AND p.user_id = :u AND p.tournament_id = :t
                         ORDER BY a.occurred_at, a.id""")
                .param("u", userId).param("t", tournamentId).query(String.class).list();
    }

    private static String restoredAsOf(UUID userId, UUID tournamentId) {
        return OWNER.sql("""
                        SELECT a.payload ->> 'asOf' FROM audit.audit_event a
                          JOIN tournament.tournament_participant p ON p.id = a.subject_id
                         WHERE a.action = 'TOURNAMENT_PARTICIPANT_RESTORED' AND p.user_id = :u AND p.tournament_id = :t
                         ORDER BY a.occurred_at DESC LIMIT 1""")
                .param("u", userId).param("t", tournamentId).query(String.class).single();
    }

    /** {@code owner_password_used:owner_password_outcome:reason} of the transition into {@code to}. */
    private static String transitionAudit(UUID tournamentId, String to) {
        return OWNER.sql("""
                        SELECT COALESCE(CAST(owner_password_used AS text), 'null') || ':'
                               || COALESCE(owner_password_outcome, 'null') || ':' || COALESCE(reason, '')
                          FROM audit.audit_event
                         WHERE subject_type = 'TOURNAMENT' AND subject_id = :id
                           AND action = 'TOURNAMENT_STATUS_TRANSITION' AND new_state = :to""")
                .param("id", tournamentId).param("to", to).query(String.class).single();
    }

    private static String checkpoint(UUID tournamentId) {
        return Tournaments.CHECKPOINT + ":" + tournamentId;
    }

    private static String start(UUID tournamentId) {
        return Tournaments.START + ":" + tournamentId;
    }

    private static String completion(UUID tournamentId) {
        return Tournaments.COMPLETION + ":" + tournamentId;
    }

    private static void assertCode(Runnable call, ApiErrorCode code) {
        Throwable refused = catchThrowable(call::run);
        assertThat(refused).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refused).code()).isEqualTo(code);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * TEST-ONLY: one Tournament as if it had been made earlier - its interval, checkpoint, creation and approval
     * deadline, its history and their audit events, its participants and their audit events, its timers, its holds
     * and its Operating Session, all moved back by the same amount as the migration owner with triggers off
     * ({@code session_replication_role = replica}); the CHECK constraints still hold. For a World with one Tournament.
     */
    static final class Shift {

        private Shift() {
        }

        /** Moves the Tournament so that {@code checkpointAt = now - by}; returns the new checkpointAt. */
        static Instant pastCheckpoint(UUID tournamentId, Duration by) {
            return moveTo(tournamentId, "checkpoint_at", by);
        }

        /** Moves the Tournament so that {@code startAt = now - by}; returns the new startAt. */
        static Instant pastStart(UUID tournamentId, Duration by) {
            return moveTo(tournamentId, "start_at", by);
        }

        /** Moves the Tournament so that {@code endAt = now - by}; returns the new endAt. */
        static Instant pastEnd(UUID tournamentId, Duration by) {
            return moveTo(tournamentId, "end_at", by);
        }

        private static Instant moveTo(UUID tournamentId, String column, Duration by) {
            Instant now = TrustStates.dbNow();
            Instant current = OWNER.sql("SELECT " + column + " FROM tournament.tournament WHERE id = :id")
                    .param("id", tournamentId).query(OffsetDateTime.class).single().toInstant();
            Duration back = Duration.between(now.minus(by), current);
            back(tournamentId, back);
            return current.minus(back);
        }

        static void back(UUID tournamentId, Duration by) {
            String interval = by.toNanos() / 1000 + " microseconds";
            withoutTriggers(jdbc -> {
                jdbc.sql("""
                                UPDATE tournament.tournament
                                   SET created_at = created_at - CAST(:by AS interval),
                                       start_at = start_at - CAST(:by AS interval),
                                       end_at = end_at - CAST(:by AS interval),
                                       checkpoint_at = checkpoint_at - CAST(:by AS interval),
                                       approval_expires_at = approval_expires_at - CAST(:by AS interval)
                                 WHERE id = :id""")
                        .param("by", interval).param("id", tournamentId).update();
                jdbc.sql("""
                                UPDATE venue.operating_session s
                                   SET session_period = tstzrange(lower(s.session_period) - CAST(:by AS interval),
                                                                  upper(s.session_period) - CAST(:by AS interval), '[)'),
                                       local_start = local_start - CAST(:by AS interval),
                                       local_end = local_end - CAST(:by AS interval)
                                  FROM tournament.tournament t
                                 WHERE t.id = :id AND s.id = t.operating_session_id""")
                        .param("by", interval).param("id", tournamentId).update();
                jdbc.sql("""
                                UPDATE tournament.tournament_history SET occurred_at = occurred_at - CAST(:by AS interval)
                                 WHERE tournament_id = :id""")
                        .param("by", interval).param("id", tournamentId).update();
                jdbc.sql("""
                                UPDATE audit.audit_event SET occurred_at = occurred_at - CAST(:by AS interval)
                                 WHERE (subject_type = 'TOURNAMENT' AND subject_id = :id)
                                    OR (subject_type = 'TOURNAMENT_PARTICIPANT' AND subject_id IN (
                                        SELECT p.id FROM tournament.tournament_participant p
                                         WHERE p.tournament_id = :id))""")
                        .param("by", interval).param("id", tournamentId).update();
                jdbc.sql("""
                                UPDATE tournament.tournament_participant
                                   SET joined_at = joined_at - CAST(:by AS interval),
                                       status_changed_at = status_changed_at - CAST(:by AS interval),
                                       left_at = left_at - CAST(:by AS interval),
                                       removed_at = removed_at - CAST(:by AS interval)
                                 WHERE tournament_id = :id""")
                        .param("by", interval).param("id", tournamentId).update();
                jdbc.sql("""
                                UPDATE technical.scheduled_job SET due_at = due_at - CAST(:by AS interval)
                                 WHERE subject_id = :id""")
                        .param("by", interval).param("id", tournamentId).update();
                jdbc.sql("""
                                UPDATE inventory.resource_reservation
                                   SET planned_start = planned_start - CAST(:by AS interval),
                                       planned_end = planned_end - CAST(:by AS interval),
                                       planned_range = tstzrange(planned_start - CAST(:by AS interval),
                                                                 planned_end - CAST(:by AS interval), '[)'),
                                       actual_reservation_start = actual_reservation_start - CAST(:by AS interval),
                                       actual_reservation_release_at = actual_reservation_release_at - CAST(:by AS interval)
                                 WHERE tournament_id = :id""")
                        .param("by", interval).param("id", tournamentId).update();
            });
        }

        static void withoutTriggers(Consumer<JdbcClient> writes) {
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
}
