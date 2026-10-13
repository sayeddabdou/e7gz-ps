package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.booking.internal.LateConfirmDb.attempt;
import static ps.e7gz.booking.internal.LateConfirmDb.attempts;
import static ps.e7gz.booking.internal.LateConfirmDb.history;
import static ps.e7gz.booking.internal.LateConfirmDb.reservations;
import static ps.e7gz.booking.internal.LateConfirmIntegrationTest.assertRefusedWith;
import static ps.e7gz.booking.internal.LateConfirmIntegrationTest.assertThrownWith;
import static ps.e7gz.booking.internal.LateConfirmIntegrationTest.ok;
import static ps.e7gz.booking.internal.LateConfirmIntegrationTest.race;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.count;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.booking.internal.LateConfirmDb.Attempt;
import ps.e7gz.booking.internal.LateConfirmDb.Event;
import ps.e7gz.booking.internal.LateConfirmDb.Reservation;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmAnswer;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmations;
import ps.e7gz.booking.lateconfirm.internal.SentLateConfirmation;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-58 Phase 2B.1 (decisions Q1-Q3 of 13 Oct 2026, delegated by the PO to Amr): simultaneous Late Confirmations, the
 * committed resource-conflict refusal on Accept, the venue and room refusals of Initiate and Accept, a data defect
 * rolled back, and the commitment guard ordering an Initiate and a new booking deterministically. Every AWAITING
 * booking here is made by the real Initiate and every transaction commits through the real {@code CommandRunner}.
 */
class LateConfirmScenariosIntegrationTest extends IntegrationTest {

    @Autowired
    CommandRunner runner;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    VenueAnswerStore store;

    @Autowired
    VenueAnswers answers;

    @Autowired
    LateConfirmations lateConfirmations;

    private LateConfirmDb db;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        db = new LateConfirmDb(runner, creation, store, answers);
        correlation = CorrelationId.open(UUID.randomUUID());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ---------------------------------------------------------------- Q1: simultaneous Late Confirmations

    /**
     * Two overlapping bookings of one customer, both revived: X expired, then Y was booked over it (X no longer
     * counted), then Y expired too; each venue initiation is free (D1).
     */
    private UUID[] twoOverlappingAwaiting(OnlineBookingWorld world, Fixtures.Resource second, Instant start) {
        UUID x = db.expired(world, world.customer.id(), world.room, start, 120);
        UUID y = db.expired(world, world.customer.id(), second, start.plus(Duration.ofMinutes(30)), 60);
        ok(lateConfirmations.initiate(world.account.id(), x));
        ok(lateConfirmations.initiate(world.account.id(), y));
        assertThat(booking(x).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(booking(y).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        return new UUID[] {x, y};
    }

    @ParameterizedTest(name = "accepting booking {0} first")
    @ValueSource(ints = {0, 1})
    void theFirstOfTwoOverlappingAcceptsWinsAndTheSecondConflicts(int first) {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID[] pair = twoOverlappingAwaiting(world, world.anotherRoom(room -> room), world.start());
        UUID winner = pair[first];
        UUID loser = pair[1 - first];
        Attempt loserBefore = attempt(loser).orElseThrow();

        // FR-LCF-10: the other AWAITING booking is no conflict
        assertThat(ok(lateConfirmations.accept(world.customer.id(), winner)).status())
                .isEqualTo(BookingStatus.CONFIRMED);

        assertRefusedWith(lateConfirmations.accept(world.customer.id(), loser),
                ApiErrorCode.LATE_CONFIRM_CUSTOMER_CONFLICT);
        assertThat(booking(winner).status()).isEqualTo("CONFIRMED");
        assertThat(booking(loser).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(attempt(loser).orElseThrow()).as("open, same hold and deadline").isEqualTo(loserBefore);
        assertThat(reservations(loser)).filteredOn(Reservation::active).singleElement()
                .satisfies(r -> assertThat(r.id()).isEqualTo(loserBefore.holdId()));
        assertThat(conflictEvents(loser)).hasSize(1);
        assertThat(conflictEvents(winner)).isEmpty();
    }

    @Test
    void twoOverlappingAcceptsAtOnceConfirmExactlyOne() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID[] pair = twoOverlappingAwaiting(world, world.anotherRoom(room -> room), world.start());

        List<Object> outcomes = race(List.of(
                () -> lateConfirmations.accept(world.customer.id(), pair[0]),
                () -> lateConfirmations.accept(world.customer.id(), pair[1])));

        assertThat(outcomes).filteredOn(CommandAnswer.Ok.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(CommandAnswer.Refused.class::isInstance).singleElement()
                .satisfies(refused -> assertThat(((CommandAnswer.Refused<?>) refused).refusal().code())
                        .isEqualTo(ApiErrorCode.LATE_CONFIRM_CUSTOMER_CONFLICT));
        int winner = outcomes.get(0) instanceof CommandAnswer.Ok<?> ? 0 : 1;
        assertThat(booking(pair[winner]).status()).isEqualTo("CONFIRMED");
        assertThat(booking(pair[1 - winner]).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(conflictEvents(pair[1 - winner])).hasSize(1);
        assertThat(count("booking.booking WHERE customer_id = :id AND status = 'CONFIRMED'", world.customer.id()))
                .isEqualTo(1);
    }

    @Test
    void awaitingBookingsStillCountTowardsTheLimitAndStillBlockANewOverlappingBooking() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource third = world.anotherRoom(room -> room);
        Instant start = world.start();
        twoOverlappingAwaiting(world, world.anotherRoom(room -> room), start);
        creation.create(world.request(start.plus(Duration.ofHours(3)), 60));
        creation.create(world.request(start.plus(Duration.ofHours(5)), 60));

        assertThatRefused(() -> creation.create(world.request(start.plus(Duration.ofHours(7)), 60)),
                OnlineCreationCondition.BELOW_COMMITMENT_LIMIT);
        assertThat(count("booking.booking WHERE customer_id = :id AND commitment_period IS NOT NULL",
                world.customer.id())).as("2 AWAITING + 2 PENDING").isEqualTo(4);
        assertThat(count("booking.booking WHERE resource_id = :id", third.id())).isZero();
    }

    @Test
    void aNewBookingOverlappingAnAwaitingOneIsStillRefused() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource third = world.anotherRoom(room -> room);
        Instant start = world.start();
        twoOverlappingAwaiting(world, world.anotherRoom(room -> room), start);

        assertThatRefused(() -> creation.create(world.request(world.customer.id(), third,
                start.plus(Duration.ofMinutes(15)), 60)), OnlineCreationCondition.NO_OVERLAPPING_COMMITMENT);
    }

    // ---------------------------------------------------------------- Q2: the committed resource conflict on Accept

    @Test
    void aTakenIntervalAtAcceptCommitsTheResourceConflictEventAndKeepsTheBookingAwaiting() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        UUID late = db.expired(world, start, 60);
        ok(lateConfirmations.initiate(world.account.id(), late));
        Attempt before = attempt(late).orElseThrow();
        LateConfirmDb.emergencyIntervalOnly(world.venue.id(), world.room.id(), start.plus(Duration.ofMinutes(30)));

        CommandAnswer<LateConfirmAnswer> answer = lateConfirmations.accept(world.customer.id(), late);

        assertRefusedWith(answer, ApiErrorCode.LATE_CONFIRM_RESOURCE_CONFLICT);
        assertThat(history(late).getLast()).satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("LATE_CONFIRM_FAILED_RESOURCE_CONFLICT");
            assertThat(event.actorType()).isEqualTo("CUSTOMER");
        });
        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(attempt(late).orElseThrow()).isEqualTo(before);
        assertThat(reservations(late)).filteredOn(Reservation::active).singleElement()
                .satisfies(r -> assertThat(r.id()).isEqualTo(before.holdId()));
    }

    @Test
    void aSellingBlockIsNotAResourceConflictAndRecordsNothing() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        VenueAnswerDb.resourceEmergency(world.venue.id(), world.room.id());
        int historyBefore = history(late).size();

        assertThrownWith(() -> lateConfirmations.initiate(world.account.id(), late),
                ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE);
        assertThat(history(late)).hasSize(historyBefore);
        assertThat(attempts(late)).isZero();
    }

    // ---------------------------------------------------------------- Initiate: the venue and the room

    @Test
    void initiateInAnInactiveAreaIsAreaInactive() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        LateConfirmDb.areaInactive(world.venue.areaId());

        assertInitiateRefusedWithNothingWritten(world, late, ApiErrorCode.AREA_INACTIVE);
    }

    @Test
    void initiateOutsideAnOperatingSessionNowIsIntervalNotWithinOneSession() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        UUID late = db.expired(world, start, 60);
        // the session now begins 10 minutes before the booking: still fits it, but the venue is not open now
        LateConfirmDb.sessionPeriod(world.venue.id(), start.minus(Duration.ofMinutes(10)),
                start.plus(Duration.ofHours(4)));

        assertInitiateRefusedWithNothingWritten(world, late, ApiErrorCode.INTERVAL_NOT_WITHIN_ONE_SESSION);
    }

    @Test
    void initiateWhenTheIntervalNoLongerFitsTheSessionIsIntervalNotWithinOneSession() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        UUID late = db.expired(world, start, 60);
        LateConfirmDb.sessionPeriod(world.venue.id(), world.t0.minus(Duration.ofHours(2)),
                start.plus(Duration.ofMinutes(30)));

        assertInitiateRefusedWithNothingWritten(world, late, ApiErrorCode.INTERVAL_NOT_WITHIN_ONE_SESSION);
    }

    @Test
    void initiateForADisabledRoomIsResourceNotEligible() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        VenueAnswerDb.resourceDisabled(world.room.id());

        assertInitiateRefusedWithNothingWritten(world, late, ApiErrorCode.RESOURCE_NOT_ELIGIBLE);
    }

    @Test
    void initiateUnderAResourceSellingBlockIsResourceSellingBlockActive() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        VenueAnswerDb.resourceEmergency(world.venue.id(), world.room.id());

        assertInitiateRefusedWithNothingWritten(world, late, ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE);
    }

    @Test
    void initiateByADisabledVenueAccountIsForbidden() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        LateConfirmDb.accountDisabled(world.account.id());

        assertInitiateRefusedWithNothingWritten(world, late, ApiErrorCode.FORBIDDEN);
    }

    // ---------------------------------------------------------------- Accept: the venue and the room

    @Test
    void acceptInAnInactiveAreaIsAreaInactive() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        LateConfirmDb.areaInactive(world.venue.areaId());

        assertAcceptRefusedWithNothingWritten(world, late, ApiErrorCode.AREA_INACTIVE);
    }

    @Test
    void acceptWhenTheIntervalNoLongerFitsTheSessionIsIntervalNotWithinOneSession() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        LateConfirmDb.sessionPeriod(world.venue.id(), world.t0.minus(Duration.ofHours(2)),
                world.start().plus(Duration.ofMinutes(30)));

        assertAcceptRefusedWithNothingWritten(world, late, ApiErrorCode.INTERVAL_NOT_WITHIN_ONE_SESSION);
    }

    @Test
    void acceptForADisabledRoomIsResourceNotEligible() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        VenueAnswerDb.resourceDisabled(world.room.id());

        assertAcceptRefusedWithNothingWritten(world, late, ApiErrorCode.RESOURCE_NOT_ELIGIBLE);
    }

    /** D11 of E7-47: a lost hold is a data defect - the whole command rolls back, nothing changes. */
    @Test
    void aLostHoldIsAnIntegrityFailureAndTheAcceptRollsBack() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        LateConfirmDb.holdLost(late);
        int historyBefore = history(late).size();

        assertThatThrownBy(
                        () -> lateConfirmations.accept(world.customer.id(), late))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity failure");
        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(history(late)).hasSize(historyBefore);
        assertThat(attempt(late).orElseThrow().closedAt()).isNull();
        assertThat(reservations(late)).noneMatch(r -> r.type().equals("BOOKING") && r.active());
    }

    // ---------------------------------------------------------------- the commitment guard, deterministically

    /**
     * FR-LCF-06: an Initiate and a new booking of a customer at 3 commitments, queued behind a third transaction that
     * holds the customer's row: the guard serialises them in arrival order, and whichever runs second re-counts and
     * sees 4. Each order is forced, not left to thread scheduling.
     */
    @ParameterizedTest(name = "initiate queued first: {0}")
    @ValueSource(booleans = {true, false})
    void theCommitmentGuardOrdersAnInitiateAndANewBooking(boolean initiateFirst) throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        UUID late = db.expired(world, start, 60);
        for (int i = 1; i <= 3; i++) {
            creation.create(world.request(start.plus(Duration.ofHours(i)), 60));
        }
        Callable<Object> initiate = () -> lateConfirmations.initiate(world.account.id(), late);
        Callable<Object> create = () -> creation.create(world.request(world.customer.id(), second,
                start.plus(Duration.ofHours(5)), 60));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR,
                MIGRATOR)) {
            holder.setAutoCommit(false);
            int holderPid = lockCustomer(holder, world.customer.id());

            Future<Object> firstCall = pool.submit(scoped(initiateFirst ? initiate : create));
            awaitWaiters(holderPid, 1);
            Future<Object> secondCall = pool.submit(scoped(initiateFirst ? create : initiate));
            awaitWaiters(holderPid, 2);
            holder.commit();

            Object earlier = firstCall.get(60, TimeUnit.SECONDS);
            Object later = secondCall.get(60, TimeUnit.SECONDS);
            if (initiateFirst) {
                assertThat(earlier).isInstanceOf(CommandAnswer.Ok.class);
                assertThat(later).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.COMMITMENT_LIMIT_REACHED));
                assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
            } else {
                assertThat(earlier).isInstanceOf(CreatedBooking.class);
                assertRefusedWith((CommandAnswer<?>) later, ApiErrorCode.CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM);
                assertThat(booking(late).status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("booking.booking WHERE customer_id = :id AND commitment_period IS NOT NULL",
                world.customer.id())).isEqualTo(4);
    }

    // ---------------------------------------------------------------- helpers

    private UUID awaiting(OnlineBookingWorld world) {
        UUID late = db.expired(world, world.start(), 60);
        SentLateConfirmation sent = ok(lateConfirmations.initiate(world.account.id(), late));
        assertThat(sent.status()).isEqualTo(BookingStatus.AWAITING_CUSTOMER_CONFIRMATION);
        return late;
    }

    private void assertInitiateRefusedWithNothingWritten(OnlineBookingWorld world, UUID late, ApiErrorCode code) {
        int historyBefore = history(late).size();
        assertThrownWith(() -> lateConfirmations.initiate(world.account.id(), late), code);
        assertThat(booking(late).status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(history(late)).hasSize(historyBefore);
        assertThat(attempts(late)).isZero();
        assertThat(reservations(late)).noneMatch(Reservation::active);
    }

    private void assertAcceptRefusedWithNothingWritten(OnlineBookingWorld world, UUID late, ApiErrorCode code) {
        Attempt before = attempt(late).orElseThrow();
        int historyBefore = history(late).size();
        assertThrownWith(() -> lateConfirmations.accept(world.customer.id(), late), code);
        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(history(late)).hasSize(historyBefore);
        assertThat(attempt(late).orElseThrow()).isEqualTo(before);
    }

    private static List<Event> conflictEvents(UUID bookingId) {
        return history(bookingId).stream()
                .filter(e -> "LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT".equals(e.eventType()))
                .toList();
    }

    private static void assertThatRefused(ThrowingCallable call,
                                          OnlineCreationCondition condition) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CreationRefused.class,
                refused -> assertThat(refused.condition()).isEqualTo(condition));
    }

    /** Takes the Customer's row - the first lock {@code begin_command} takes for them - and answers the holder pid. */
    private static int lockCustomer(Connection holder, UUID customerId) throws SQLException {
        try (PreparedStatement lock = holder.prepareStatement(
                "SELECT pg_backend_pid() FROM iam.user_account WHERE id = ? FOR UPDATE")) {
            lock.setObject(1, customerId);
            try (ResultSet rs = lock.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Waits until {@code expected} backends are queued behind the holder, directly or behind another waiter. */
    private static void awaitWaiters(int holderPid, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Long waiting = OWNER.sql("""
                            WITH RECURSIVE queued(pid) AS (
                                SELECT a.pid FROM pg_stat_activity a
                                 WHERE :holder = ANY (pg_blocking_pids(a.pid))
                                UNION
                                SELECT a.pid FROM pg_stat_activity a JOIN queued q
                                    ON q.pid = ANY (pg_blocking_pids(a.pid)))
                            SELECT count(*) FROM queued""")
                    .param("holder", holderPid).query(Long.class).single();
            if (waiting >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("fewer than " + expected + " commands queued behind the holder");
    }

    private static Callable<Object> scoped(Callable<Object> task) {
        return () -> {
            try (CorrelationId.Scope ignored = CorrelationId.open(UUID.randomUUID())) {
                return task.call();
            } catch (ApiException refused) {
                return refused;
            }
        };
    }
}
