package ps.e7gz.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
import ps.e7gz.audit.AuditFixtures;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentTerminalReason;
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
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.Db;
import ps.e7gz.tournament.internal.TournamentsIntegrationTest.World;
import ps.e7gz.venue.internal.VenueAccounts;

/**
 * E7-92 (S6-E2; FR-TRN-10, FR-TRN-11, FR-TRN-18, Spec v5 sections 37.2 and 37.3): the Venue's approve and reject of a
 * Player-hosted request and its approval timeout T7, fired by the real deadline worker, with every trigger on and the
 * deferred aggregate checks at each command's {@code COMMIT}.
 *
 * <p><b>Time.</b> A request's approval deadline is {@code created + 10 min} and no guard lets it or its timer move (a
 * Tournament timer never pauses). To reach it, {@link Backdate} shifts the request's own instants - creation,
 * deadline, history and its audit, the approval timer and the holds' start - back as the migration owner with triggers
 * off, as if it had been made that long ago; every later write goes through every guard.
 *
 * <p><b>Done when</b> ({@link #disablingTheVenueAccountWhilePendingFreesTheSlotWithNoCooldown}): disabling the Venue
 * Account during PENDING_VENUE_APPROVAL frees the creator's slot with no cooldown.
 */
class TournamentApprovalIntegrationTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Autowired
    ApplicationContext context;

    @Autowired
    TournamentCreation creation;

    @Autowired
    TournamentApproval approval;

    @Autowired
    VenueAccounts venueAccounts;

    private DueJobs due;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void open() {
        correlation = CorrelationId.open(CorrelationId.generate());
        due = DueJobs.of(context, new TournamentApprovalExpiryHandler(approval));
    }

    @AfterEach
    void close() {
        correlation.close();
    }

    // ------------------------------------------------------------------------------------------------ done-when

    /**
     * FR-TRN-18: the disable cancels the request at once, VENUE_ACCOUNT_DISABLED - no venue non-response blame - and the
     * creator's platform-wide pending slot is free: a new request elsewhere goes through at once, and once the account
     * is enabled again, the same Venue and interval goes through at once too - no 5-minute cooldown.
     */
    @Test
    void disablingTheVenueAccountWhilePendingFreesTheSlotWithNoCooldown() {
        World world = World.create();
        World elsewhere = World.create();
        CreatedTournament pending = request(world, world.rooms());

        venueAccounts.setStatus(AuditFixtures.admin(), world.account().id(), VenueAccountStatus.DISABLED,
                "Support ticket 92");

        assertThat(Db.tournament(pending.id()).status()).isEqualTo("CANCELLED");
        assertThat(Db.tournament(pending.id()).terminalReason()).isEqualTo("VENUE_ACCOUNT_DISABLED");
        assertThat(Db.activeHoldsOn(world.rooms())).isZero();
        assertThat(events(IntegrationEvents.TOURNAMENT_CANCELLED, pending.id())).singleElement()
                .satisfies(event -> assertThat(event.payload()).containsEntry("terminalReason", "VENUE_ACCOUNT_DISABLED")
                        .containsEntry("actorType", "SYSTEM")
                        .containsEntry("recipientId", world.creator().id().toString()));

        // the slot is free platform-wide at once (FR-TRN-11's one-pending rule no longer counts it)
        CreatedTournament other = creation.requestByPlayer(world.creator().id(), elsewhere.venue().id(), false,
                shape(elsewhere, elsewhere.rooms()));
        assertThat(other.status()).isEqualTo(TournamentStatus.PENDING_VENUE_APPROVAL);
        approval.reject(elsewhere.account().id(), other.id());  // frees the slot again; cools down elsewhere only
        // and no cooldown for the disabled Venue's interval once it can take requests again
        venueAccounts.setStatus(AuditFixtures.admin(), world.account().id(), VenueAccountStatus.ENABLED,
                "Support ticket 92 closed");

        CreatedTournament again = request(world, world.rooms());

        assertThat(again.status()).isEqualTo(TournamentStatus.PENDING_VENUE_APPROVAL);
        assertThat(Db.tournament(pending.id()).status()).as("a re-enable never restores it").isEqualTo("CANCELLED");
    }

    // ------------------------------------------------------------------------------------------------ approve

    /**
     * FR-TRN-10, FR-TRN-09, arch 05 section 3.2: WAITING_FOR_PLAYERS, each Approval Hold released and its Tournament
     * Hold made at the same command instant in the same transaction - the room is never free in between.
     */
    @Test
    void approvingTurnsTheApprovalHoldsIntoTournamentHoldsInOneTransaction() {
        World world = World.create();
        CreatedTournament pending = request(world, world.rooms());

        AnsweredTournament answered = approval.approve(world.account().id(), pending.id());

        assertThat(answered).isEqualTo(new AnsweredTournament(pending.id(), TournamentStatus.WAITING_FOR_PLAYERS, null));
        assertThat(Db.tournament(pending.id()).status()).isEqualTo("WAITING_FOR_PLAYERS");
        assertThat(Db.tournament(pending.id()).terminalReason()).isNull();
        List<Db.HoldRow> holds = Db.holds(pending.id());
        assertThat(holds).hasSize(4);
        List<Db.HoldRow> approvalHolds = holds.stream().filter(h -> h.type().equals("TOURNAMENT_APPROVAL_HOLD")).toList();
        List<Db.HoldRow> tournamentHolds = holds.stream().filter(h -> h.type().equals("TOURNAMENT_HOLD")).toList();
        assertThat(approvalHolds).hasSize(2).allSatisfy(hold -> {
            assertThat(hold.active()).isFalse();
            assertThat(hold.releaseReason()).isEqualTo("WAITING_FOR_PLAYERS");
        });
        Instant convertedAt = approvalHolds.getFirst().releasedAt();
        assertThat(tournamentHolds).hasSize(2).allSatisfy(hold -> {
            assertThat(hold.active()).isTrue();
            assertThat(hold.start()).isEqualTo(world.startAt());
            assertThat(hold.end()).isEqualTo(pending.endAt());
        });
        assertThat(holdStarts(pending.id(), "TOURNAMENT_HOLD")).as("held again at the very instant of the release")
                .containsOnly(convertedAt);
        assertThat(assignedHoldTypes(pending.id())).containsOnly("TOURNAMENT_HOLD");
        assertThat(Db.history(pending.id())).last().isEqualTo(new Db.HistoryRow(2, "PENDING_VENUE_APPROVAL",
                "WAITING_FOR_PLAYERS", null, "VENUE", world.account().id()));
        assertThat(Db.jobs(pending.id())).filteredOn(job -> job.type().equals(Tournaments.APPROVAL_EXPIRY))
                .singleElement().satisfies(job -> assertThat(job.status()).isEqualTo("SUPERSEDED"));
        assertThat(Db.liveJobs(pending.id())).extracting(Db.JobRow::type).containsExactlyInAnyOrder(
                Tournaments.CHECKPOINT, Tournaments.START, Tournaments.COMPLETION);
        assertThat(events(IntegrationEvents.TOURNAMENT_APPROVED, pending.id())).singleElement().satisfies(event ->
                assertThat(event.payload()).containsEntry("recipientType", "CUSTOMER")
                        .containsEntry("recipientId", world.creator().id().toString())
                        .containsEntry("actorType", "VENUE")
                        .containsEntry("historySequence", "2")
                        .doesNotContainKey("terminalReason"));
    }

    /** FR-TRN-07: a private request's code was stored but inactive; once approved, it is the Tournament's code. */
    @Test
    void anApprovedPrivateRequestKeepsItsInviteCode() {
        World world = World.create();
        CreatedTournament pending = creation.requestByPlayer(world.creator().id(), world.venue().id(), false,
                TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, world.rooms(), 16, world.startAt(), 120,
                        TournamentVisibility.PRIVATE, null, null));

        approval.approve(world.account().id(), pending.id());

        assertThat(OWNER.sql("SELECT invite_code FROM tournament.tournament WHERE id = :id").param("id", pending.id())
                .query(String.class).single()).isEqualTo(pending.inviteCode());
    }

    /**
     * Reviewer checklist: approve after the window is refused even if T7 has not run yet - the boundary is the deadline
     * instant - and nothing changes; reject alike.
     */
    @Test
    void anAnswerAtOrAfterTheDeadlineIsRefusedBeforeT7HasRun() {
        World world = World.create();
        CreatedTournament pending = request(world, world.rooms());
        Backdate.by(pending.id(), Duration.ofMinutes(10));  // approvalExpiresAt = now (and passing)

        assertCode(() -> approval.approve(world.account().id(), pending.id()), ApiErrorCode.DEADLINE_PASSED);
        assertCode(() -> approval.reject(world.account().id(), pending.id()), ApiErrorCode.DEADLINE_PASSED);

        assertThat(Db.tournament(pending.id()).status()).isEqualTo("PENDING_VENUE_APPROVAL");
        assertThat(Db.holds(pending.id())).allSatisfy(hold -> assertThat(hold.active()).isTrue());
        assertThat(DueJobs.job(t7(pending.id())).status()).as("T7 still owns the outcome").isEqualTo("PENDING");
    }

    /** A second before the deadline the Venue may still answer. */
    @Test
    void anAnswerJustBeforeTheDeadlineIsAccepted() {
        World world = World.create();
        CreatedTournament pending = request(world, world.rooms());
        Backdate.by(pending.id(), Duration.ofMinutes(10).minusSeconds(30));

        approval.approve(world.account().id(), pending.id());

        assertThat(Db.tournament(pending.id()).status()).isEqualTo("WAITING_FOR_PLAYERS");
    }

    @Test
    void aRequestTheTimeoutCancelledIsPastItsDeadline() {
        World world = World.create();
        CreatedTournament pending = request(world, world.rooms());
        expire(pending);

        assertCode(() -> approval.approve(world.account().id(), pending.id()), ApiErrorCode.DEADLINE_PASSED);
        assertCode(() -> approval.reject(world.account().id(), pending.id()), ApiErrorCode.DEADLINE_PASSED);
    }

    @Test
    void aTournamentNoLongerPendingIsAChangedState() {
        World world = World.create();
        CreatedTournament pending = request(world, List.of(world.rooms().getFirst()));
        approval.approve(world.account().id(), pending.id());
        CreatedTournament venueHosted = creation.createByVenue(world.account().id(),
                TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, List.of(world.rooms().get(1)), 8,
                        world.startAt(), 120, TournamentVisibility.PUBLIC, null, null));

        assertCode(() -> approval.approve(world.account().id(), pending.id()), ApiErrorCode.OPERATIONAL_STATE_CHANGED);
        assertCode(() -> approval.reject(world.account().id(), pending.id()), ApiErrorCode.OPERATIONAL_STATE_CHANGED);
        assertCode(() -> approval.approve(world.account().id(), venueHosted.id()),
                ApiErrorCode.OPERATIONAL_STATE_CHANGED);
        assertThat(Db.history(pending.id())).hasSize(2);
    }

    /** A Venue never answers, nor learns of, another Venue's request. */
    @Test
    void anotherVenuesRequestIsNotFound() {
        World world = World.create();
        World other = World.create();
        CreatedTournament pending = request(world, world.rooms());

        assertCode(() -> approval.approve(other.account().id(), pending.id()), ApiErrorCode.NOT_FOUND);
        assertCode(() -> approval.reject(other.account().id(), pending.id()), ApiErrorCode.NOT_FOUND);
        assertCode(() -> approval.approve(world.account().id(), UUID.randomUUID()), ApiErrorCode.NOT_FOUND);
        assertThat(Db.tournament(pending.id()).status()).isEqualTo("PENDING_VENUE_APPROVAL");
    }

    // ------------------------------------------------------------------------------------------------ reject

    /** FR-TRN-10 and ck_tournament_rejected_reason: REJECTED / VENUE_REJECTED_TOURNAMENT, never a cancellation. */
    @Test
    void rejectingEndsTheRequestRejectedAndReleasesItsHolds() {
        World world = World.create();
        CreatedTournament pending = request(world, world.rooms());

        AnsweredTournament answered = approval.reject(world.account().id(), pending.id());

        assertThat(answered).isEqualTo(new AnsweredTournament(pending.id(), TournamentStatus.REJECTED,
                TournamentTerminalReason.VENUE_REJECTED_TOURNAMENT));
        assertThat(Db.tournament(pending.id()).status()).isEqualTo("REJECTED");
        assertThat(Db.tournament(pending.id()).terminalReason()).isEqualTo("VENUE_REJECTED_TOURNAMENT");
        assertThat(Db.holds(pending.id())).hasSize(2).allSatisfy(hold -> {
            assertThat(hold.active()).isFalse();
            assertThat(hold.releaseReason()).isEqualTo("VENUE_REJECTED_TOURNAMENT");
        });
        assertThat(Db.activeHoldsOn(world.rooms())).isZero();
        assertThat(Db.history(pending.id())).last().isEqualTo(new Db.HistoryRow(2, "PENDING_VENUE_APPROVAL",
                "REJECTED", "VENUE_REJECTED_TOURNAMENT", "VENUE", world.account().id()));
        assertThat(Db.liveJobs(pending.id())).isEmpty();
        assertThat(events(IntegrationEvents.TOURNAMENT_REJECTED, pending.id())).singleElement().satisfies(event ->
                assertThat(event.payload()).containsEntry("recipientType", "CUSTOMER")
                        .containsEntry("recipientId", world.creator().id().toString())
                        .containsEntry("actorType", "VENUE")
                        .containsEntry("historySequence", "2")
                        .containsEntry("terminalReason", "VENUE_REJECTED_TOURNAMENT"));
    }

    /** FR-TRN-11: 5 minutes after a rejection, for the same Venue and interval - and only for those. */
    @Test
    void afterARejectionTheSameRequestWaitsFiveMinutes() {
        World world = World.create();
        CreatedTournament rejected = request(world, world.rooms());
        approval.reject(world.account().id(), rejected.id());

        assertThat(DatabaseErrorCodes.translate(catchThrowable(() -> request(world, world.rooms()))))
                .contains(ApiErrorCode.TOURNAMENT_RETRY_COOLDOWN_ACTIVE);
        assertThat(Db.activeHoldsOn(world.rooms())).isZero();

        Backdate.by(rejected.id(), Duration.ofMinutes(4));
        assertThat(DatabaseErrorCodes.translate(catchThrowable(() -> request(world, world.rooms()))))
                .as("still inside the 5 minutes").contains(ApiErrorCode.TOURNAMENT_RETRY_COOLDOWN_ACTIVE);

        Backdate.by(rejected.id(), Duration.ofMinutes(1).plusSeconds(1));
        assertThat(request(world, world.rooms()).status()).isEqualTo(TournamentStatus.PENDING_VENUE_APPROVAL);
    }

    @Test
    void theCooldownIsOnlyForTheSameInterval() {
        World world = World.create();
        CreatedTournament rejected = request(world, world.rooms());
        approval.reject(world.account().id(), rejected.id());

        CreatedTournament later = creation.requestByPlayer(world.creator().id(), world.venue().id(), false,
                TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, world.rooms(), 16,
                        world.startAt().plus(Duration.ofMinutes(30)), 120, TournamentVisibility.PUBLIC, null, null));

        assertThat(later.status()).isEqualTo(TournamentStatus.PENDING_VENUE_APPROVAL);
    }

    // ------------------------------------------------------------------------------------------------ T7

    /**
     * FR-TRN-10: no answer by approvalExpiresAt - CANCELLED / VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME by SYSTEM, the
     * holds released at the deadline, every timer done or withdrawn, the creator told.
     */
    @Test
    void t7CancelsAnUnansweredRequestAsOfItsDeadline() {
        World world = World.create();
        CreatedTournament pending = request(world, world.rooms());

        Instant deadline = expire(pending);

        assertThat(Db.tournament(pending.id()).status()).isEqualTo("CANCELLED");
        assertThat(Db.tournament(pending.id()).terminalReason()).isEqualTo("VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME");
        assertThat(Db.holds(pending.id())).hasSize(2).allSatisfy(hold -> {
            assertThat(hold.active()).isFalse();
            assertThat(hold.releasedAt()).as("as of the deadline, not of the worker").isEqualTo(deadline);
            assertThat(hold.releaseReason()).isEqualTo("VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME");
        });
        assertThat(Db.history(pending.id())).last().isEqualTo(new Db.HistoryRow(2, "PENDING_VENUE_APPROVAL",
                "CANCELLED", "VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME", "SYSTEM", null));
        assertThat(DueJobs.job(t7(pending.id())).status()).isEqualTo("DONE");
        assertThat(Db.liveJobs(pending.id())).isEmpty();
        assertThat(events(IntegrationEvents.TOURNAMENT_CANCELLED, pending.id())).singleElement().satisfies(event ->
                assertThat(event.payload()).containsEntry("terminalReason", "VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME")
                        .containsEntry("actorType", "SYSTEM")
                        .containsEntry("recipientId", world.creator().id().toString()));
    }

    /** Arch 08 section 2.2: a worker an hour late decides the same outcome, dated at the deadline. */
    @Test
    void t7FiredLateDecidesAsOfTheDeadline() {
        World world = World.create();
        CreatedTournament pending = request(world, world.rooms());
        Backdate.by(pending.id(), Duration.ofMinutes(70));
        Instant deadline = approvalExpiresAt(pending.id());

        assertThat(due.fire(t7(pending.id()))).isTrue();

        assertThat(Db.tournament(pending.id()).terminalReason()).isEqualTo("VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME");
        assertThat(Db.holds(pending.id())).allSatisfy(hold -> assertThat(hold.releasedAt()).isEqualTo(deadline));
    }

    @Test
    void t7FiredTwiceCancelsOnce() {
        World world = World.create();
        CreatedTournament pending = request(world, world.rooms());
        expire(pending);

        assertThat(due.fire(t7(pending.id()))).as("a DONE job never fires again").isFalse();
        assertThat(Db.history(pending.id())).hasSize(2);
        assertThat(events(IntegrationEvents.TOURNAMENT_CANCELLED, pending.id())).hasSize(1);
    }

    /** An answered request's T7 is withdrawn with the answer, so it never fires. */
    @Test
    void anAnsweredRequestHasNoT7Left() {
        World world = World.create();
        CreatedTournament approved = request(world, List.of(world.rooms().getFirst()));
        approval.approve(world.account().id(), approved.id());

        assertThat(DueJobs.job(t7(approved.id())).status()).isEqualTo("SUPERSEDED");
        assertThat(due.fire(t7(approved.id()))).isFalse();
        assertThat(Db.tournament(approved.id()).status()).isEqualTo("WAITING_FOR_PLAYERS");
    }

    /** FR-TRN-11: 5 minutes after an approval expiry too. */
    @Test
    void afterATimeoutTheSameRequestWaitsFiveMinutes() {
        World world = World.create();
        CreatedTournament expired = request(world, world.rooms());
        expire(expired);

        assertThat(DatabaseErrorCodes.translate(catchThrowable(() -> request(world, world.rooms()))))
                .contains(ApiErrorCode.TOURNAMENT_RETRY_COOLDOWN_ACTIVE);

        Backdate.by(expired.id(), Duration.ofMinutes(5).plusSeconds(1));
        assertThat(request(world, world.rooms()).status()).isEqualTo(TournamentStatus.PENDING_VENUE_APPROVAL);
    }

    /** The one-pending rule frees the slot with the timeout: another Venue at once (no cooldown there). */
    @Test
    void afterATimeoutTheCreatorMayAskAnotherVenueAtOnce() {
        World world = World.create();
        World elsewhere = World.create();
        CreatedTournament expired = request(world, world.rooms());
        expire(expired);

        CreatedTournament next = creation.requestByPlayer(world.creator().id(), elsewhere.venue().id(), false,
                shape(elsewhere, elsewhere.rooms()));

        assertThat(next.status()).isEqualTo(TournamentStatus.PENDING_VENUE_APPROVAL);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The World's creator asks the World's Venue for these rooms, 16 players, 120 minutes, not playing. */
    private CreatedTournament request(World world, List<UUID> rooms) {
        return creation.requestByPlayer(world.creator().id(), world.venue().id(), false, shape(world, rooms));
    }

    private static TournamentRequest shape(World world, List<UUID> rooms) {
        return TournamentRequest.of(Activity.PLAYSTATION, PlayMode.SINGLE, rooms, 16, world.startAt(), 120,
                TournamentVisibility.PUBLIC, Money.of("50.00"), null);
    }

    /** Moves the request 11 minutes into the past - its deadline a minute ago - and fires T7; returns the deadline. */
    private Instant expire(CreatedTournament pending) {
        Backdate.by(pending.id(), Duration.ofMinutes(11));
        assertThat(due.fire(t7(pending.id()))).isTrue();
        return approvalExpiresAt(pending.id());
    }

    private static String t7(UUID tournamentId) {
        return Tournaments.APPROVAL_EXPIRY + ":" + tournamentId;
    }

    private static Instant approvalExpiresAt(UUID tournamentId) {
        return OWNER.sql("SELECT approval_expires_at FROM tournament.tournament WHERE id = :id")
                .param("id", tournamentId).query(OffsetDateTime.class).single().toInstant();
    }

    private static List<Instant> holdStarts(UUID tournamentId, String type) {
        return OWNER.sql("""
                        SELECT actual_reservation_start FROM inventory.resource_reservation
                         WHERE tournament_id = :id AND reservation_type = :type""")
                .param("id", tournamentId).param("type", type)
                .query(OffsetDateTime.class).list().stream().map(OffsetDateTime::toInstant).toList();
    }

    private static List<String> assignedHoldTypes(UUID tournamentId) {
        return OWNER.sql("""
                        SELECT rr.reservation_type FROM tournament.tournament_resource tr
                          JOIN inventory.resource_reservation rr ON rr.id = tr.reservation_id
                         WHERE tr.tournament_id = :id""")
                .param("id", tournamentId).query(String.class).list();
    }

    record Event(UUID correlationId, Map<String, String> payload) {
    }

    @SuppressWarnings("unchecked")
    static List<Event> events(String type, UUID tournamentId) {
        return OWNER.sql("""
                        SELECT correlation_id, payload::text FROM technical.outbox_event
                         WHERE event_type = :type AND aggregate_type = 'TOURNAMENT' AND aggregate_id = :id
                         ORDER BY created_at""")
                .param("type", type).param("id", tournamentId)
                .query((rs, n) -> {
                    try {
                        return new Event(rs.getObject(1, UUID.class), new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(rs.getString(2), Map.class));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .list();
    }

    private static ApiException assertCode(Runnable call, ApiErrorCode code) {
        Throwable refused = catchThrowable(call::run);
        assertThat(refused).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refused).code()).isEqualTo(code);
        return (ApiException) refused;
    }

    /**
     * TEST-ONLY: the Tournament as if made {@code by} earlier - its creation and approval deadline, its history and
     * their audit events, its approval timer and its holds' start and release - written as the migration owner with
     * triggers off ({@code session_replication_role = replica}); the CHECK constraints still hold. For a request
     * whose creator does not play (no participant row). The checkpoint, start and end stay where they are.
     */
    static final class Backdate {

        private Backdate() {
        }

        static void by(UUID tournamentId, Duration by) {
            String interval = by.toMillis() + " milliseconds";
            withoutTriggers(jdbc -> {
                jdbc.sql("""
                                UPDATE tournament.tournament
                                   SET created_at = created_at - CAST(:by AS interval),
                                       approval_expires_at = approval_expires_at - CAST(:by AS interval)
                                 WHERE id = :id""")
                        .param("by", interval).param("id", tournamentId).update();
                jdbc.sql("""
                                UPDATE tournament.tournament_history SET occurred_at = occurred_at - CAST(:by AS interval)
                                 WHERE tournament_id = :id""")
                        .param("by", interval).param("id", tournamentId).update();
                jdbc.sql("""
                                UPDATE audit.audit_event SET occurred_at = occurred_at - CAST(:by AS interval)
                                 WHERE subject_type = 'TOURNAMENT' AND subject_id = :id""")
                        .param("by", interval).param("id", tournamentId).update();
                jdbc.sql("""
                                UPDATE technical.scheduled_job SET due_at = due_at - CAST(:by AS interval)
                                 WHERE subject_id = :id AND type = 'TOURNAMENT_APPROVAL_EXPIRY'""")
                        .param("by", interval).param("id", tournamentId).update();
                jdbc.sql("""
                                UPDATE inventory.resource_reservation
                                   SET actual_reservation_start = actual_reservation_start - CAST(:by AS interval),
                                       actual_reservation_release_at = actual_reservation_release_at - CAST(:by AS interval)
                                 WHERE tournament_id = :id""")
                        .param("by", interval).param("id", tournamentId).update();
            });
        }

        private static void withoutTriggers(Consumer<JdbcClient> writes) {
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
