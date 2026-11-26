package ps.e7gz.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;
import static ps.e7gz.support.fixtures.Fixtures.aResource;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.audit.AuditFixtures;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentHostType;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.error.DatabaseErrorCodes;
import ps.e7gz.common.money.Money;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.tournament.api.TournamentResourceAvailability.Blocker;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.Db;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.World;
import ps.e7gz.venue.internal.VenueAccounts;

/**
 * E7-91 (S6-E1, O16 part 1): Player- and Venue-hosted creation through {@link TournamentCreation}, with every trigger
 * on and the deferred aggregate checks at each command's {@code COMMIT}. The "done when" (a request against a room with
 * a CONFIRMED booking fails and the booking is untouched) runs end to end over HTTP in
 * {@code TournamentCreationHttpTest}; here the same rule is shown against a room already held by another Tournament.
 */
class TournamentCreationIntegrationTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    TournamentCreation creation;

    @Autowired
    VenueAccounts venueAccounts;

    CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        correlation = CorrelationId.open(CorrelationId.generate());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ------------------------------------------------------------------------------------------------ player-hosted

    /** FR-TRN-02, FR-TRN-09, FR-TRN-10, FR-TRN-28: the request, its Approval Holds, snapshots, timers and event. */
    @Test
    void aVerifiedPlayersRequestIsPendingWithApprovalHoldsAndAFixedVenueCost() {
        World world = World.create();

        CreatedTournament created = creation.requestByPlayer(world.creator().id(), world.venue().id(), true,
                request(world, world.rooms(), TournamentVisibility.PUBLIC));

        assertThat(created.status()).isEqualTo(TournamentStatus.PENDING_VENUE_APPROVAL);
        assertThat(created.hostType()).isEqualTo(TournamentHostType.PLAYER);
        assertThat(created.creatorUserId()).isEqualTo(world.creator().id());
        assertThat(created.approvalExpiresAt()).isEqualTo(created.createdAt().plus(Duration.ofMinutes(10)));
        assertThat(created.checkpointAt()).isEqualTo(world.startAt().minus(Duration.ofHours(1)));
        assertThat(created.endAt()).isEqualTo(world.startAt().plus(Duration.ofMinutes(180)));
        assertThat(created.businessDate()).isEqualTo(world.businessDate());
        // 2 rooms x EGP 100.00 an hour x 3 hours, half-up per room and summed (FR-TRN-28)
        assertThat(created.venueCostTotal()).isEqualTo(Money.of("600.00"));
        assertThat(created.creationFee()).isEqualTo(Money.of("0.00"));
        assertThat(created.entryFee()).isEqualTo(Money.of("50.00"));
        assertThat(created.resources()).extracting(CreatedTournament.Resource::resourceId)
                .containsExactlyElementsOf(world.rooms());
        assertThat(created.resources()).allSatisfy(resource -> {
            assertThat(resource.consoleType()).isEqualTo("PS5");
            assertThat(resource.hourlyPrice()).isEqualTo(Money.of("100.00"));
        });

        // Approval Holds, never Tournament Holds, for a request (arch 06 section 3.1)
        assertThat(Db.holds(created.id())).hasSize(2).allSatisfy(hold -> {
            assertThat(hold.type()).isEqualTo("TOURNAMENT_APPROVAL_HOLD");
            assertThat(hold.active()).isTrue();
            assertThat(hold.start()).isEqualTo(world.startAt());
            assertThat(hold.end()).isEqualTo(created.endAt());
        });
        Db.TournamentRow row = Db.tournament(created.id());
        assertThat(row.venueCostTotal()).isEqualByComparingTo("600.00");
        assertThat(row.createdAt()).isEqualTo(created.createdAt());
        assertThat(Db.history(created.id())).containsExactly(
                new Db.HistoryRow(1, null, "PENDING_VENUE_APPROVAL", null, "CUSTOMER", world.creator().id()));
        assertThat(Db.participants(created.id())).containsExactly(world.creator().id() + ":JOINED");
        assertThat(Db.liveJobs(created.id())).extracting(Db.JobRow::type).containsExactlyInAnyOrder(
                "TOURNAMENT_APPROVAL_EXPIRY", "TOURNAMENT_CHECKPOINT", "TOURNAMENT_START", "TOURNAMENT_COMPLETION");
        assertThat(requestedEvents(created.id())).isEqualTo(1);
    }

    /** FR-TRN-07: a private request has its code stored, but inactive (not given out) until the Venue accepts. */
    @Test
    void aPrivateRequestsInviteCodeIsInactiveWhilePending() {
        World world = World.create();

        CreatedTournament created = creation.requestByPlayer(world.creator().id(), world.venue().id(), false,
                request(world, List.of(world.rooms().getFirst()), TournamentVisibility.PRIVATE));

        assertThat(created.inviteCode()).hasSize(InviteCodes.LENGTH);
        assertThat(created.inviteCodeActive()).isFalse();
        assertThat(inviteCode(created.id())).isEqualTo(created.inviteCode());
        assertThat(Db.participants(created.id())).as("Organizer Only (FR-TRN-05)").isEmpty();
    }

    /** FR-TRN-11: one pending request per creator platform-wide - also at another Venue, another time. */
    @Test
    void aCreatorWithAPendingRequestCannotOpenASecondAnywhere() {
        World world = World.create();
        World elsewhere = World.create();
        creation.requestByPlayer(world.creator().id(), world.venue().id(), true,
                request(world, List.of(world.rooms().getFirst()), TournamentVisibility.PUBLIC));

        Throwable refused = catchThrowable(() -> creation.requestByPlayer(world.creator().id(),
                elsewhere.venue().id(), true, request(elsewhere, elsewhere.rooms(), TournamentVisibility.PUBLIC)));

        assertThat(DatabaseErrorCodes.translate(refused)).contains(ApiErrorCode.PENDING_TOURNAMENT_REQUEST_EXISTS);
        assertThat(Db.activeHoldsOn(elsewhere.rooms())).isZero();
        assertThat(tournamentsAt(elsewhere)).isZero();
    }

    @Test
    void aCustomerWhoIsNotAVerifiedPlayerIsRefused() {
        World world = World.create();
        Fixtures.User player = aUser().verified().create();

        Throwable refused = catchThrowable(() -> creation.requestByPlayer(player.id(), world.venue().id(), true,
                request(world, world.rooms(), TournamentVisibility.PUBLIC)));

        assertThat(DatabaseErrorCodes.translate(refused)).contains(ApiErrorCode.VERIFIED_PLAYER_REQUIRED);
        assertThat(Db.activeHoldsOn(world.rooms())).isZero();
    }

    /** FR-TRN-18's mirror at creation: a Player cannot ask a Venue whose account is disabled. */
    @Test
    void aVenueWhoseAccountIsDisabledIsNotEligible() {
        World world = World.create();
        venueAccounts.setStatus(AuditFixtures.admin(), world.account().id(), VenueAccountStatus.DISABLED,
                "Support ticket 9");

        assertCode(() -> creation.requestByPlayer(world.creator().id(), world.venue().id(), true,
                request(world, world.rooms(), TournamentVisibility.PUBLIC)),
                ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY);
        assertThat(tournamentsAt(world)).isZero();
    }

    // ------------------------------------------------------------------------------------------------ venue-hosted

    @Test
    void aVenuesOwnTournamentWaitsForPlayersWithTournamentHoldsAndAnActiveCode() {
        World world = World.create();

        CreatedTournament created = creation.createByVenue(world.account().id(),
                request(world, world.rooms(), TournamentVisibility.PRIVATE));

        assertThat(created.status()).isEqualTo(TournamentStatus.WAITING_FOR_PLAYERS);
        assertThat(created.hostType()).isEqualTo(TournamentHostType.VENUE);
        assertThat(created.creatorUserId()).isNull();
        assertThat(created.approvalExpiresAt()).isNull();
        assertThat(created.venueCostTotal()).isEqualTo(Money.of("600.00"));
        assertThat(created.inviteCodeActive()).isTrue();
        assertThat(created.inviteCode()).matches("[" + InviteCodes.ALPHABET + "]{16}");
        assertThat(Db.holds(created.id())).hasSize(2)
                .allSatisfy(hold -> assertThat(hold.type()).isEqualTo("TOURNAMENT_HOLD"));
        assertThat(Db.history(created.id())).containsExactly(
                new Db.HistoryRow(1, null, "WAITING_FOR_PLAYERS", null, "VENUE", world.account().id()));
        assertThat(requestedEvents(created.id())).as("nothing for the Venue to answer").isZero();
    }

    /** FR-TRN-04: an interval may exceed the 6-hour online maximum as long as it fits one Operating Session. */
    @Test
    void aTournamentLongerThanSixHoursFitsOneSession() {
        World world = World.create();

        // 18:00 to 02:00, the end of the session
        CreatedTournament created = creation.createByVenue(world.account().id(),
                request(world, world.rooms(), 480));

        assertThat(created.endAt()).isEqualTo(world.startAt().plus(Duration.ofHours(8)));
    }

    @Test
    void anIntervalPastTheEndOfTheSessionIsRefused() {
        World world = World.create();

        assertCode(() -> creation.createByVenue(world.account().id(), request(world, world.rooms(), 510)),
                ApiErrorCode.INTERVAL_NOT_WITHIN_ONE_SESSION);
        assertThat(tournamentsAt(world)).isZero();
    }

    /** FR-TRN-03 for both host types: requestTime <= startAt - 2h. */
    @Test
    void aStartInsideTheTwoHourCutoffIsRefused() {
        World world = World.create(Duration.ofMinutes(110));

        Throwable refused = catchThrowable(() -> creation.createByVenue(world.account().id(),
                request(world, world.rooms(), 60)));

        assertThat(DatabaseErrorCodes.translate(refused)).contains(ApiErrorCode.TOURNAMENT_CREATION_CUTOFF_PASSED);
        assertThat(Db.activeHoldsOn(world.rooms())).isZero();
    }

    // ------------------------------------------------------------------------------------------------ the rooms

    /** FR-TRN-08: what is already on a room wins; the whole request fails and the other room is not held either. */
    @Test
    void aRoomAlreadyHeldWinsAndNothingIsHeld() {
        World world = World.create();
        UUID busy = world.rooms().get(1);
        CreatedTournament first = creation.createByVenue(world.account().id(),
                request(world, List.of(busy), TournamentVisibility.PUBLIC));

        ApiException refused = assertCode(() -> creation.requestByPlayer(world.creator().id(), world.venue().id(),
                true, request(world, world.rooms(), TournamentVisibility.PUBLIC)),
                ApiErrorCode.TOURNAMENT_RESOURCE_CONFLICT);

        assertThat(refused.field()).isEqualTo("resourceIds");
        assertThat(refused.details()).containsEntry("resourceId", busy.toString());
        assertThat(Db.activeHoldsOn(List.of(world.rooms().getFirst()))).isZero();
        assertThat(Db.holds(first.id())).singleElement().satisfies(hold -> assertThat(hold.active()).isTrue());
        assertThat(tournamentsAt(world)).isEqualTo(1);
        assertThat(requestedEvents(null)).isZero();
    }

    /** FR-VEN-45: no price for the play mode, no Tournament. */
    @Test
    void aRoomWithoutAPriceForThePlayModeIsNotEligible() {
        World world = World.create();
        TournamentRequest multiplayer = TournamentRequest.of(Activity.PLAYSTATION, PlayMode.MULTIPLAYER,
                world.rooms(), 16, world.startAt(), 120, TournamentVisibility.PUBLIC, null, null);

        assertCode(() -> creation.createByVenue(world.account().id(), multiplayer), ApiErrorCode.RESOURCE_NOT_ELIGIBLE);
        assertThat(tournamentsAt(world)).isZero();
    }

    /** A Venue never learns of another Venue's rooms; a Player naming a room of another Venue neither. */
    @Test
    void aRoomOfAnotherVenueIsNotFound() {
        World world = World.create();
        Fixtures.Venue other = aVenue().active().offering(Activity.PLAYSTATION).create();
        UUID foreign = aResource().at(other).playStation(ConsoleType.PS5).create().id();

        assertCode(() -> creation.createByVenue(world.account().id(),
                request(world, List.of(foreign), TournamentVisibility.PUBLIC)), ApiErrorCode.NOT_FOUND);
        assertCode(() -> creation.requestByPlayer(world.creator().id(), world.venue().id(), true,
                request(world, List.of(world.rooms().getFirst(), foreign), TournamentVisibility.PUBLIC)),
                ApiErrorCode.NOT_FOUND);
        assertThat(tournamentsAt(world)).isZero();
    }

    // ------------------------------------------------------------------------------------------------ the request

    @Test
    void theShapeOfTheRequestIsValidatedOnItsField() {
        World world = World.create();
        List<UUID> rooms = world.rooms();

        assertField(() -> TournamentRequest.of(Activity.PLAYSTATION, null, rooms, 16, world.startAt(), 120,
                TournamentVisibility.PUBLIC, null, null), "playMode");
        assertField(() -> TournamentRequest.of(Activity.BILLIARDS, PlayMode.SINGLE, rooms, 16, world.startAt(), 120,
                TournamentVisibility.PUBLIC, null, null), "playMode");
        assertField(() -> TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, List.of(), 16, world.startAt(),
                120, TournamentVisibility.PUBLIC, null, null), "resourceIds");
        assertField(() -> TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE,
                List.of(rooms.get(0), rooms.get(0)), 16, world.startAt(), 120, TournamentVisibility.PUBLIC, null,
                null), "resourceIds");
        assertField(() -> TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE,
                List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID()), 16, world.startAt(), 120, TournamentVisibility.PUBLIC, null, null),
                "resourceIds");
        assertField(() -> TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, rooms, 7, world.startAt(), 120,
                TournamentVisibility.PUBLIC, null, null), "capacity");
        assertField(() -> TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, rooms, 33, world.startAt(), 120,
                TournamentVisibility.PUBLIC, null, null), "capacity");
        assertField(() -> TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, rooms, 8, world.startAt(), 30,
                TournamentVisibility.PUBLIC, null, null), "durationMinutes");
        assertField(() -> TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, rooms, 32, world.startAt(), 75,
                TournamentVisibility.PUBLIC, null, null), "durationMinutes");
        assertThat(TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, rooms, 8, world.startAt(), 90,
                TournamentVisibility.PUBLIC, null, null).entryFee()).isEqualTo(Money.ZERO);
    }

    @Test
    void theAnswersOfTheCanonicalPredicateAsPublicCodes() {
        assertThat(TournamentCreation.codeOf(Blocker.ALREADY_RESERVED)).isEqualTo(ApiErrorCode.TOURNAMENT_RESOURCE_CONFLICT);
        assertThat(TournamentCreation.codeOf(Blocker.NOT_ELIGIBLE)).isEqualTo(ApiErrorCode.RESOURCE_NOT_ELIGIBLE);
        assertThat(TournamentCreation.codeOf(Blocker.VENUE_SELLING_BLOCK))
                .isEqualTo(ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE);
        assertThat(TournamentCreation.codeOf(Blocker.RESOURCE_SELLING_BLOCK))
                .isEqualTo(ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** 16 players, 3 hours from the World's start, EGP 50.00 entry and EGP 1000.00 prize. */
    private static TournamentRequest request(World world, List<UUID> rooms, TournamentVisibility visibility) {
        return TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, rooms, 16, world.startAt(), 180, visibility,
                Money.of("50.00"), Money.of("1000.00"));
    }

    private static TournamentRequest request(World world, List<UUID> rooms, int minutes) {
        return TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, rooms, 8, world.startAt(), minutes,
                TournamentVisibility.PUBLIC, null, null);
    }

    private static ApiException assertCode(Runnable call, ApiErrorCode code) {
        Throwable refused = catchThrowable(call::run);
        assertThat(refused).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refused).code()).isEqualTo(code);
        return (ApiException) refused;
    }

    private static void assertField(Runnable call, String field) {
        ApiException refused = assertCode(call, ApiErrorCode.VALIDATION_FAILED);
        assertThat(refused.field()).isEqualTo(field);
    }

    private static int tournamentsAt(World world) {
        return OWNER.sql("SELECT count(*) FROM tournament.tournament WHERE venue_id = :venue")
                .param("venue", world.venue().id()).query(Integer.class).single();
    }

    private static String inviteCode(UUID tournamentId) {
        return OWNER.sql("SELECT invite_code FROM tournament.tournament WHERE id = :id").param("id", tournamentId)
                .query(String.class).single();
    }

    /** {@code TournamentRequested} events about the Tournament, or about none ({@code null}: every one this test wrote). */
    private static int requestedEvents(UUID tournamentId) {
        return OWNER.sql("""
                        SELECT count(*) FROM technical.outbox_event
                         WHERE event_type = :type AND (CAST(:id AS uuid) IS NULL OR aggregate_id = :id)
                           AND correlation_id = :correlation""")
                .param("type", IntegrationEvents.TOURNAMENT_REQUESTED).param("id", tournamentId)
                .param("correlation", CorrelationId.require())
                .query(Integer.class).single();
    }
}
