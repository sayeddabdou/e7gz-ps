package ps.e7gz.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static ps.e7gz.support.fixtures.Fixtures.aDefaultPrice;
import static ps.e7gz.support.fixtures.Fixtures.aResource;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.anOperatingSession;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.postgresql.Driver;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.audit.AuditFixtures;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentHostType;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentTerminalReason;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.DatabaseErrorCodes;
import ps.e7gz.common.money.Money;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.support.fixtures.TrustStates;
import ps.e7gz.tournament.api.LiveTournament;
import ps.e7gz.tournament.api.TournamentCascades;
import ps.e7gz.venue.internal.VenueAccounts;

/**
 * E7-73 (S4-E5): the early Tournament aggregate. Every trigger is on and every write goes through a real command
 * ({@link CommandRunner}, {@code tournament.begin_command}), so the deferred aggregate checks run at that command's
 * {@code COMMIT}: a Tournament that commits here is one the database accepts as complete.
 *
 * <p>Done when: a test creates a Tournament with holds through the aggregate ({@link #aVenueHostedTournamentCommitsWithTournamentHolds},
 * {@link #aPlayerHostedRequestCommitsWithApprovalHoldsAndTheCreatorJoined}). The cascade tests run the shape of the
 * Sprint 5 owner-review cascade (arch 06 section 2.7) against it.
 */
class TournamentsIntegrationTest extends IntegrationTest {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

    @Autowired
    CommandRunner runner;

    @Autowired
    Tournaments tournaments;

    @Autowired
    TournamentCascades cascades;

    CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ------------------------------------------------------------------------------------------------ done-when

    @Test
    void aVenueHostedTournamentCommitsWithTournamentHolds() {
        World world = World.create();
        NewTournament request = world.venueHosted(world.rooms());

        Tournaments.Created created = create(request);

        assertThat(created.status()).isEqualTo(TournamentStatus.WAITING_FOR_PLAYERS);
        assertThat(created.approvalExpiresAt()).isNull();
        assertThat(created.checkpointAt()).isEqualTo(world.startAt().minus(Duration.ofHours(1)));
        assertThat(created.endAt()).isEqualTo(world.startAt().plus(Duration.ofMinutes(120)));
        // 2 rooms x 100.00 an hour x 2 hours
        assertThat(created.venueCostTotal()).isEqualTo(Money.of("400.00"));

        Db.TournamentRow row = Db.tournament(request.id());
        assertThat(row.status()).isEqualTo("WAITING_FOR_PLAYERS");
        assertThat(row.venueCostTotal()).isEqualByComparingTo("400.00");
        assertThat(row.creationFee()).isEqualByComparingTo("0.00");
        assertThat(row.createdAt()).isEqualTo(created.createdAt());

        List<Db.HoldRow> holds = Db.holds(request.id());
        assertThat(holds).hasSize(2).allSatisfy(hold -> {
            assertThat(hold.type()).isEqualTo("TOURNAMENT_HOLD");
            assertThat(hold.active()).isTrue();
            assertThat(hold.start()).isEqualTo(world.startAt());
            assertThat(hold.end()).isEqualTo(created.endAt());
        });
        assertThat(holds).extracting(Db.HoldRow::id).containsExactlyInAnyOrderElementsOf(created.reservationIds());
        assertThat(Db.assignments(request.id())).hasSize(2).allSatisfy(assignment -> {
            assertThat(assignment.consoleType()).isEqualTo("PS5");
            assertThat(assignment.hourlyPrice()).isEqualByComparingTo("100.00");
        });

        assertThat(Db.history(request.id())).containsExactly(
                new Db.HistoryRow(1, null, "WAITING_FOR_PLAYERS", null, "VENUE", world.account().id()));
        assertThat(Db.statusAudits(request.id())).isEqualTo(1);
        assertThat(Db.liveJobs(request.id())).containsExactlyInAnyOrder(
                new Db.JobRow("TOURNAMENT_CHECKPOINT", "PENDING", created.checkpointAt()),
                new Db.JobRow("TOURNAMENT_START", "PENDING", world.startAt()),
                new Db.JobRow("TOURNAMENT_COMPLETION", "PENDING", created.endAt()));
        assertThat(Db.participants(request.id())).isEmpty();
    }

    @Test
    void aPlayerHostedRequestCommitsWithApprovalHoldsAndTheCreatorJoined() {
        World world = World.create();
        NewTournament request = world.playerHosted(true, world.rooms().getFirst());

        Tournaments.Created created = create(request);

        assertThat(created.status()).isEqualTo(TournamentStatus.PENDING_VENUE_APPROVAL);
        assertThat(created.approvalExpiresAt()).isEqualTo(created.createdAt().plus(Duration.ofMinutes(10)));
        assertThat(Db.holds(request.id())).singleElement().satisfies(hold -> {
            assertThat(hold.type()).isEqualTo("TOURNAMENT_APPROVAL_HOLD");
            assertThat(hold.active()).isTrue();
        });
        assertThat(Db.history(request.id())).containsExactly(
                new Db.HistoryRow(1, null, "PENDING_VENUE_APPROVAL", null, "CUSTOMER", world.creator().id()));
        assertThat(Db.liveJobs(request.id())).containsExactlyInAnyOrder(
                new Db.JobRow("TOURNAMENT_APPROVAL_EXPIRY", "PENDING", created.approvalExpiresAt()),
                new Db.JobRow("TOURNAMENT_CHECKPOINT", "PENDING", created.checkpointAt()),
                new Db.JobRow("TOURNAMENT_START", "PENDING", world.startAt()),
                new Db.JobRow("TOURNAMENT_COMPLETION", "PENDING", created.endAt()));
        assertThat(Db.participants(request.id())).containsExactly(world.creator().id() + ":JOINED");
    }

    @Test
    void aCreatorWhoDoesNotPlayHasNoParticipantRow() {
        World world = World.create();
        NewTournament request = world.playerHosted(false, world.rooms().getFirst());

        create(request);

        assertThat(Db.participants(request.id())).isEmpty();
        assertThat(Db.tournament(request.id()).status()).isEqualTo("PENDING_VENUE_APPROVAL");
    }

    // ---------------------------------------------------------------------------- the database owns the rules

    /** Capacity 7: the aggregate does not check it; {@code ck_tournament_capacity} refuses and nothing is written. */
    @Test
    void theDatabaseRefusesACapacityBelowEight() {
        World world = World.create();
        NewTournament request = world.venueHosted(world.rooms(), 7, world.startAt(), 120);

        Throwable refused = catchThrowable(() -> create(request));

        assertThat(refused).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_tournament_capacity");
        assertThat(Db.exists(request.id())).isFalse();
        assertThat(Db.activeHoldsOn(world.rooms())).isZero();
    }

    /** 90 minutes is fine (60 + 30); 75 is not a 30-minute step: {@code ck_tournament_duration}. */
    @Test
    void theDatabaseRefusesADurationOffTheThirtyMinuteSteps() {
        World world = World.create();
        NewTournament request = world.venueHosted(world.rooms(), 8, world.startAt(), 75);

        assertThatThrownBy(() -> create(request)).hasMessageContaining("ck_tournament_duration");
        assertThat(Db.exists(request.id())).isFalse();
    }

    /** Starting less than 2 hours after creation: {@code gd_tournament_creation_cutoff}. */
    @Test
    void theDatabaseRefusesAStartInsideTheTwoHourCutoff() {
        World world = World.create(Duration.ofMinutes(90));
        NewTournament request = world.venueHosted(world.rooms());

        assertThat(guard(catchThrowable(() -> create(request)))).isEqualTo("gd_tournament_creation_cutoff");
        assertThat(Db.exists(request.id())).isFalse();
    }

    /** A room already held over the interval: the ledger's cross-class guard refuses the second Tournament's hold. */
    @Test
    void aRoomHeldByOneTournamentCannotBeHeldByAnother() {
        World world = World.create();
        create(world.venueHosted(world.rooms()));
        NewTournament second = world.venueHosted(world.rooms());

        assertThat(guard(catchThrowable(() -> create(second)))).isEqualTo("gd_reservation_conflict_tournament");
        assertThat(Db.exists(second.id())).isFalse();
    }

    /** Only an operational Verified Player hosts (FR-TRN-02); the aggregate leaves that to the insert guard. */
    @Test
    void aCreatorWhoIsNotAVerifiedPlayerIsRefused() {
        World world = World.create();
        Fixtures.User notVerified = aUser().verified().create();
        NewTournament request = new NewTournament(UUID.randomUUID(), world.venue().id(),
                new NewTournament.Host.Player(notVerified.id(), true), Activity.PLAYSTATION, PlayMode.SINGLE, 8,
                world.startAt(), 120, TournamentVisibility.PUBLIC, null, Money.ZERO, Money.ZERO, world.sessionId(),
                world.businessDate(), world.rooms());

        assertThat(guard(catchThrowable(() -> create(request))))
                .isEqualTo("gd_tournament_creator_not_verified_player");
        assertThat(Db.exists(request.id())).isFalse();
        assertThat(Db.activeHoldsOn(world.rooms())).isZero();
    }

    // ------------------------------------------------------------------------------------------------ finders

    @Test
    void cascadesFindLiveTournamentsByVenueAndByRoomInsideTheWindow() {
        World world = World.create();
        UUID first = world.rooms().get(0);
        UUID second = world.rooms().get(1);
        NewTournament onFirst = world.venueHosted(List.of(first));
        NewTournament onSecond = world.venueHosted(List.of(second), 8, world.startAt().plus(Duration.ofHours(3)), 60);
        create(onFirst);
        create(onSecond);
        Instant start = world.startAt();

        List<LiveTournament> atVenue = live(c -> cascades.liveAtVenue(c, world.venue().id(), start,
                start.plus(Duration.ofHours(6))));
        assertThat(atVenue).extracting(LiveTournament::id).containsExactly(onFirst.id(), onSecond.id());
        assertThat(atVenue.getFirst()).isEqualTo(new LiveTournament(onFirst.id(), world.venue().id(),
                TournamentHostType.VENUE, TournamentStatus.WAITING_FOR_PLAYERS, start,
                start.plus(Duration.ofMinutes(120)), List.of(first)));

        // [start + 2h, start + 3h) touches neither: the first ends at start + 2h, the second begins at start + 3h
        assertThat(live(c -> cascades.liveAtVenue(c, world.venue().id(), start.plus(Duration.ofHours(2)),
                start.plus(Duration.ofHours(3))))).isEmpty();

        assertThat(live(c -> cascades.liveOnResources(c, List.of(second), start, start.plus(Duration.ofDays(1)))))
                .extracting(LiveTournament::id).containsExactly(onSecond.id());
        assertThat(live(c -> cascades.liveOnResources(c, List.of(), start, start.plus(Duration.ofDays(1)))))
                .isEmpty();
        assertThat(live(c -> cascades.liveAtVenue(c, UUID.randomUUID(), start, start.plus(Duration.ofDays(1)))))
                .isEmpty();
    }

    @Test
    void aCancelledTournamentIsNoLongerFound() {
        World world = World.create();
        NewTournament request = world.venueHosted(world.rooms());
        create(request);

        run(LockSet.builder().tournaments(request.id()).build(), c -> tournaments.cancel(c, request.id(),
                TournamentTerminalReason.VENUE_CANCELLED, AuditActor.venue(world.account().id())));

        assertThat(live(c -> cascades.liveAtVenue(c, world.venue().id(), world.startAt(),
                world.startAt().plus(Duration.ofHours(1))))).isEmpty();
        assertThat(live(c -> cascades.liveOnResources(c, world.rooms(), world.startAt(),
                world.startAt().plus(Duration.ofHours(1))))).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ cancel

    /**
     * The owner-review cascade's shape (arch 06 section 2.7, FR-OPS-29): find the Tournament with a read-only command,
     * lock it, find it again under the locks, write the Owner Review and the restriction, cancel it with
     * RESOURCE_OWNER_REVIEW_REQUIRED - all in one command. It commits only because nothing is left holding the room
     * ({@code operations.assert_owner_review_cascade_complete}).
     */
    @Test
    void theOwnerReviewCascadeCancelsTheTournamentAndReleasesEveryHold() {
        World world = World.create();
        NewTournament request = world.playerHosted(true, world.rooms().toArray(UUID[]::new));
        Tournaments.Created created = create(request);
        UUID room = world.rooms().getFirst();
        Instant start = world.startAt();

        List<UUID> found = live(c -> cascades.liveOnResources(c, List.of(room), start,
                start.plus(Duration.ofDays(1)))).stream().map(LiveTournament::id).toList();
        assertThat(found).containsExactly(request.id());

        Instant cancelledAt = run(LockSet.builder().resources(room).tournaments(found).build(), c -> {
            enterOwnerReview(c, room);
            for (LiveTournament live : cascades.liveOnResources(c, List.of(room), c.commandNow(),
                    start.plus(Duration.ofDays(1)))) {
                assertThat(cascades.cancel(c, live.id(), TournamentTerminalReason.RESOURCE_OWNER_REVIEW_REQUIRED))
                        .isTrue();
            }
            return c.commandNow();
        });

        Db.TournamentRow row = Db.tournament(request.id());
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo("RESOURCE_OWNER_REVIEW_REQUIRED");
        assertThat(Db.holds(request.id())).hasSize(2).allSatisfy(hold -> {
            assertThat(hold.active()).isFalse();
            assertThat(hold.releasedAt()).isEqualTo(cancelledAt);
            assertThat(hold.releaseReason()).isEqualTo("RESOURCE_OWNER_REVIEW_REQUIRED");
        });
        assertThat(Db.history(request.id())).containsExactly(
                new Db.HistoryRow(1, null, "PENDING_VENUE_APPROVAL", null, "CUSTOMER", world.creator().id()),
                new Db.HistoryRow(2, "PENDING_VENUE_APPROVAL", "CANCELLED", "RESOURCE_OWNER_REVIEW_REQUIRED", "SYSTEM",
                        null));
        assertThat(Db.statusAudits(request.id())).isEqualTo(2);
        assertThat(Db.liveJobs(request.id())).isEmpty();
        assertThat(Db.jobs(request.id())).extracting(Db.JobRow::status).containsOnly("SUPERSEDED").hasSize(4);
        assertThat(created.reservationIds()).hasSize(2);
        // the creator keeps a participant row: history is retained, a terminal Tournament changes none of it
        assertThat(Db.participants(request.id())).containsExactly(world.creator().id() + ":JOINED");
    }

    @Test
    void cancellingATerminalTournamentChangesNothing() {
        World world = World.create();
        NewTournament request = world.venueHosted(world.rooms());
        create(request);
        LockSet locks = LockSet.builder().tournaments(request.id()).build();
        run(locks, c -> tournaments.cancel(c, request.id(), TournamentTerminalReason.VENUE_CANCELLED,
                AuditActor.venue(world.account().id())));

        boolean again = run(locks, c -> cascades.cancel(c, request.id(),
                TournamentTerminalReason.EMERGENCY_CLOSURE));

        assertThat(again).isFalse();
        assertThat(Db.tournament(request.id()).terminalReason()).isEqualTo("VENUE_CANCELLED");
        assertThat(Db.history(request.id())).hasSize(2);
    }

    /**
     * A condition-driven reason with no cause behind it: {@code tournament.assert_terminal_reason_provenance} refuses
     * at {@code COMMIT} (FR-TRN-31) and the whole command rolls back - the holds stay active.
     */
    @Test
    void aSystemReasonWithoutItsCauseIsRefusedAndNothingIsReleased() {
        World world = World.create();
        NewTournament request = world.venueHosted(world.rooms());
        create(request);

        Throwable refused = catchThrowable(() -> run(LockSet.builder().tournaments(request.id()).build(),
                c -> cascades.cancel(c, request.id(), TournamentTerminalReason.RESOURCE_OWNER_REVIEW_REQUIRED)));

        assertThat(refused).isInstanceOf(DataAccessException.class);
        assertThat(Db.tournament(request.id()).status()).isEqualTo("WAITING_FOR_PLAYERS");
        assertThat(Db.holds(request.id())).allSatisfy(hold -> assertThat(hold.active()).isTrue());
        assertThat(Db.liveJobs(request.id())).hasSize(3);
    }

    @Test
    void cancellingAnUnknownTournamentIsAnError() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> run(LockSet.empty(),
                c -> cascades.cancel(c, unknown, TournamentTerminalReason.EMERGENCY_CLOSURE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The guard identifier the database raised with ({@code USING CONSTRAINT}), never its message text. */
    private static String guard(Throwable refused) {
        assertThat(refused).isInstanceOf(DataAccessException.class);
        return DatabaseErrorCodes.serverError(refused).map(ServerErrorMessage::getConstraint).orElse(null);
    }

    /**
     * E7-78 (arch 06 section 2.8, FR-TRN-18, FR-TRN-19): the real Venue Account disable cancels a Tournament awaiting the
     * venue's approval at once, VENUE_ACCOUNT_DISABLED by SYSTEM, its approval holds released and timers superseded - the
     * database refused the disable before E7-78 ({@code tr_tournament_account_disable_from_account}).
     */
    @Test
    void aVenueAccountDisableCancelsATournamentAwaitingApproval() {
        World world = World.create();
        NewTournament request = world.playerHosted(true, world.rooms().getFirst());
        create(request);

        venueAccounts.setStatus(AuditFixtures.admin(), world.account().id(), VenueAccountStatus.DISABLED,
                "Support ticket 7");

        Db.TournamentRow row = Db.tournament(request.id());
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo("VENUE_ACCOUNT_DISABLED");
        assertThat(Db.holds(request.id())).allSatisfy(hold -> {
            assertThat(hold.active()).isFalse();
            assertThat(hold.releaseReason()).isEqualTo("VENUE_ACCOUNT_DISABLED");
        });
        assertThat(Db.history(request.id())).last().isEqualTo(new Db.HistoryRow(2, "PENDING_VENUE_APPROVAL",
                "CANCELLED", "VENUE_ACCOUNT_DISABLED", "SYSTEM", null));
        assertThat(Db.liveJobs(request.id())).isEmpty();
    }

    @Autowired
    VenueAccounts venueAccounts;

    private Tournaments.Created create(NewTournament request) {
        return run(request.lockSet(), c -> tournaments.create(c, request));
    }

    private List<LiveTournament> live(Function<CommandContext, List<LiveTournament>> body) {
        return run(LockSet.empty(), body);
    }

    private <R> R read(Function<CommandContext, R> body) {
        return run(LockSet.empty(), body);
    }

    private <R> R run(LockSet locks, Function<CommandContext, R> body) {
        return runner.run(new TestCommand<>(locks, body));
    }

    /** The Owner Review row and the restriction, as E7-84's cascade will write them (operations is not built yet). */
    private static void enterOwnerReview(CommandContext c, UUID room) {
        c.jdbc().sql("""
                        INSERT INTO operations.owner_review (id, resource_id, entered_at, entry_cause, correlation_id)
                        VALUES (:id, :room, tournament.command_now(), 'ROLLING_MAINTENANCE_LIMIT', :correlation)""")
                .param("id", UUID.randomUUID()).param("room", room).param("correlation", CorrelationId.require())
                .update();
        c.jdbc().sql("UPDATE resource.resource SET operational_restriction = 'OWNER_REVIEW_REQUIRED' WHERE id = :room")
                .param("room", room).update();
    }

    record TestCommand<R>(LockSet locks, Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks);
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    /**
     * A Venue with an enabled account, two PS5 rooms at EGP 100.00 an hour for single play, an Operating Session two days
     * from now (Cairo) and a Verified Player. Tournaments start at 18:00 Cairo for 120 minutes unless {@code lead} says
     * how soon after now they start.
     */
    record World(Fixtures.Venue venue, Fixtures.VenueAccount account, List<UUID> rooms, Fixtures.User creator,
                 UUID sessionId, LocalDate businessDate, Instant startAt) {

        static World create() {
            return create(null);
        }

        static World create(Duration lead) {
            return create(lead, null);
        }

        /** As {@link #create()}, with the Venue in {@code areaId} (a fresh Area when null). */
        static World inArea(UUID areaId) {
            return create(null, areaId);
        }

        private static World create(Duration lead, UUID areaId) {
            var builder = aVenue().active().offering(Activity.PLAYSTATION);
            Fixtures.Venue venue = (areaId == null ? builder : builder.inArea(areaId)).create();
            Fixtures.VenueAccount account = aVenueAccount().forVenue(venue).create();
            List<UUID> rooms = List.of(
                    aResource().at(venue).playStation(ConsoleType.PS5).create().id(),
                    aResource().at(venue).playStation(ConsoleType.PS5).create().id());
            aDefaultPrice().forVenue(venue).playStation(ConsoleType.PS5, PlayMode.SINGLE).hourly("100.00").create();
            Fixtures.User creator = aUser().verified().create();
            TrustStates.projection(creator.id(), 0, 1, 10, "VERIFIED");

            Instant now = TrustStates.dbNow();
            LocalDate day;
            Instant startAt;
            String from;
            String to;
            if (lead == null) {
                day = LocalDate.ofInstant(now, CAIRO).plusDays(2);
                startAt = day.atTime(18, 0).atZone(CAIRO).toInstant();
                from = day + "T08:00";
                to = day.plusDays(1) + "T02:00";
            } else {
                // a session around a start `lead` from now, on the start's own Cairo day
                startAt = now.plus(lead).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
                LocalDateTime local = LocalDateTime.ofInstant(startAt, CAIRO);
                day = local.toLocalDate();
                from = local.minusHours(1).toString();
                to = local.plusHours(4).toString();
            }
            Fixtures.OperatingSession session = anOperatingSession().forVenue(venue)
                    .on(day.toString(), from, to).create();
            return new World(venue, account, rooms, creator, session.id(), day, startAt);
        }

        NewTournament venueHosted(List<UUID> resources) {
            return venueHosted(resources, 8, startAt, 120);
        }

        NewTournament venueHosted(List<UUID> resources, int capacity, Instant start, int minutes) {
            return tournament(new NewTournament.Host.Venue(account.id()), capacity, start, minutes, resources);
        }

        NewTournament playerHosted(boolean creatorPlays, UUID... resources) {
            return tournament(new NewTournament.Host.Player(creator.id(), creatorPlays), 16, startAt, 120,
                    List.of(resources));
        }

        private NewTournament tournament(NewTournament.Host host, int capacity, Instant start, int minutes,
                                         List<UUID> resources) {
            return new NewTournament(UUID.randomUUID(), venue.id(), host, Activity.PLAYSTATION, PlayMode.SINGLE,
                    capacity, start, minutes, TournamentVisibility.PUBLIC, null, Money.of("50.00"),
                    Money.of("1000.00"), sessionId, businessDate, resources);
        }
    }

    /** Reads as the migration owner, after the command committed. */
    static final class Db {

        private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
                PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

        private static JdbcClient db() {
            return OWNER;
        }

        record TournamentRow(String status, String terminalReason, BigDecimal venueCostTotal, BigDecimal creationFee,
                             Instant createdAt) {
        }

        record HoldRow(UUID id, String type, boolean active, Instant start, Instant end, Instant releasedAt,
                       String releaseReason) {
        }

        record AssignmentRow(String consoleType, BigDecimal hourlyPrice) {
        }

        record HistoryRow(long sequence, String from, String to, String reason, String actorType, UUID actorId) {
        }

        record JobRow(String type, String status, Instant dueAt) {
        }

        static boolean exists(UUID id) {
            return db().sql("SELECT EXISTS (SELECT 1 FROM tournament.tournament WHERE id = :id)").param("id", id)
                    .query(Boolean.class).single();
        }

        static TournamentRow tournament(UUID id) {
            return db().sql("""
                            SELECT status, terminal_reason, venue_cost_total, creation_fee_snapshot, created_at
                              FROM tournament.tournament WHERE id = :id""")
                    .param("id", id)
                    .query((rs, n) -> new TournamentRow(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                            rs.getBigDecimal(4), rs.getObject(5, OffsetDateTime.class).toInstant()))
                    .single();
        }

        static List<HoldRow> holds(UUID tournamentId) {
            return db().sql("""
                            SELECT id, reservation_type, active_flag, planned_start, planned_end,
                                   actual_reservation_release_at, release_reason
                              FROM inventory.resource_reservation WHERE tournament_id = :id ORDER BY resource_id""")
                    .param("id", tournamentId)
                    .query((rs, n) -> new HoldRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3),
                            rs.getObject(4, OffsetDateTime.class).toInstant(),
                            rs.getObject(5, OffsetDateTime.class).toInstant(),
                            rs.getObject(6, OffsetDateTime.class) == null ? null
                                    : rs.getObject(6, OffsetDateTime.class).toInstant(),
                            rs.getString(7)))
                    .list();
        }

        static int activeHoldsOn(List<UUID> rooms) {
            return db().sql("""
                            SELECT count(*) FROM inventory.resource_reservation
                             WHERE resource_id IN (:rooms) AND active_flag""")
                    .param("rooms", rooms).query(Integer.class).single();
        }

        static List<AssignmentRow> assignments(UUID tournamentId) {
            return db().sql("""
                            SELECT console_type_snapshot, hourly_price_snapshot
                              FROM tournament.tournament_resource WHERE tournament_id = :id""")
                    .param("id", tournamentId)
                    .query((rs, n) -> new AssignmentRow(rs.getString(1), rs.getBigDecimal(2)))
                    .list();
        }

        static List<HistoryRow> history(UUID tournamentId) {
            return db().sql("""
                            SELECT sequence, from_status, to_status, terminal_reason, actor_type, actor_id
                              FROM tournament.tournament_history WHERE tournament_id = :id ORDER BY sequence""")
                    .param("id", tournamentId)
                    .query((rs, n) -> new HistoryRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class)))
                    .list();
        }

        static int statusAudits(UUID tournamentId) {
            return db().sql("""
                            SELECT count(*) FROM audit.audit_event
                             WHERE subject_type = 'TOURNAMENT' AND subject_id = :id
                               AND action = 'TOURNAMENT_STATUS_TRANSITION'""")
                    .param("id", tournamentId).query(Integer.class).single();
        }

        static List<JobRow> jobs(UUID tournamentId) {
            return db().sql("""
                            SELECT type, status, due_at FROM technical.scheduled_job
                             WHERE subject_id = :id ORDER BY type""")
                    .param("id", tournamentId)
                    .query((rs, n) -> new JobRow(rs.getString(1), rs.getString(2),
                            rs.getObject(3, OffsetDateTime.class).toInstant()))
                    .list();
        }

        static List<JobRow> liveJobs(UUID tournamentId) {
            return jobs(tournamentId).stream()
                    .filter(job -> job.status().equals("PENDING") || job.status().equals("LEASED")).toList();
        }

        static List<String> participants(UUID tournamentId) {
            return db().sql("""
                            SELECT user_id || ':' || status FROM tournament.tournament_participant
                             WHERE tournament_id = :id ORDER BY user_id""")
                    .param("id", tournamentId).query(String.class).list();
        }
    }
}
