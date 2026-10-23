package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.booking.internal.LateConfirmDb.t2;
import static ps.e7gz.booking.internal.LateConfirmDb.t3;
import static ps.e7gz.booking.internal.LateConfirmIntegrationTest.ok;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.bookingStart;
import static ps.e7gz.booking.internal.VenueAnswerDb.events;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.booking.internal.LateConfirmDb.Event;
import ps.e7gz.booking.internal.LateConfirmDb.Reservation;
import ps.e7gz.booking.internal.LockWaits.Waiter;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmAnswer;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmations;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.venue.api.HoursChangeBookings;

/**
 * E7-113 Phase 3D-C1: the Customer's genuine Late Confirm Accept and Decline ({@link LateConfirmations#accept} /
 * {@link LateConfirmations#decline}, the commands behind the customer routes without the Idempotency-Key record, under
 * the booking's Customer, Venue and Resource locks) racing the opening-hours cancellation (O5,
 * {@link HoursChangeBookings#cancel} in a committed command under the same locks) on one AWAITING_CUSTOMER_CONFIRMATION
 * Online Booking with an open, unexpired attempt and an active LATE_CONFIRM_HOLD. Whichever commits first owns the
 * transition; Accept first leaves a CONFIRMED booking that O5 then cancels (FR-VEN-24, FR-VEN-28, FR-LCF-08,
 * FR-LCF-13).
 *
 * <p>Order is set by the transactions' own locks. Tests C and F hold the Customer's command open inside its
 * transaction, after its booking writes and before COMMIT, with one ordinary row lock: a separate owner connection
 * takes {@code SELECT ... FOR UPDATE} on the booking's T2 job row - a row Accept and Decline update next
 * ({@code supersedeJobs}, a plain guarded {@code UPDATE}) and that {@code begin_command} does not lock. The Customer's
 * command then waits on that row while holding every lock of the booking; O5 is started and proved, from
 * {@code pg_blocking_pids}, to wait in {@code begin_command} behind exactly that backend. Rolling the row lock back
 * releases the Customer's command, which commits. No trigger is off, no job is put LEASED, no deadline is touched.
 *
 * <p>Test C - the 40001 question, settled against the source and the running database: {@code CommandRunner} reruns
 * a command on SQLSTATE 40001 with the same {@link CommandScope} it computed once (it never recomputes the lock set).
 * {@code tournament.begin_command} raises 40001 ("Shared command scope changed while waiting") only for a new booking
 * of a named Customer, a new Ban, a new live Tournament, or a reservation of the named bookings that appears after it
 * listed them - and it lists those reservations after its row-lock waits, not before. Accept creates no booking and
 * its new BOOKING reservation is committed before O5's wait ends, so O5's {@code begin_command} lists it and locks it:
 * O5 runs once, with no 40001 and no retry. Test C proves that from the runner's own retry log and each body run's
 * {@link CommandContext#attempt()}, rather than asserting a retry the production path does not make.
 */
class HoursChangeRaceCustomerIntegrationTest extends IntegrationTest {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(HoursChangeRaceCustomerIntegrationTest.class);
    private static final String REASON = "VENUE_OPENING_HOURS_CHANGED";
    private static final String DECLINED = BookingTerminalReason.CUSTOMER_DECLINED_LATE_CONFIRM.name();
    private static final String AWAITING = BookingStatus.AWAITING_CUSTOMER_CONFIRMATION.name();
    private static final String CONFIRMED = BookingStatus.CONFIRMED.name();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    CommandRunner runner;

    @Autowired
    HoursChangeBookings port;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    VenueAnswerStore store;

    @Autowired
    VenueAnswers answers;

    @Autowired
    LateConfirmations lateConfirmations;

    private CorrelationId.Scope correlation;
    private ExecutorService pool;
    private RetryLog retries;

    @BeforeEach
    void open() {
        correlation = CorrelationId.open(UUID.randomUUID());
        AtomicInteger threads = new AtomicInteger();
        pool = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "customer-race-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        retries = RetryLog.attach();
    }

    @AfterEach
    void close() throws InterruptedException {
        try {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("race threads ended").isTrue();
        } finally {
            retries.detach();
            correlation.close();
        }
    }

    // ================================================================ Accept versus O5

    // ---------------------------------------------------------------- A: Accept commits, then O5

    @Test
    void a_customerAcceptCommitsFirstThenO5CancelsTheConfirmedBooking() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Late late = awaiting(world);
        List<String> outboxBefore = outbox(late);

        LateConfirmAnswer answer = ok(lateConfirmations.accept(world.customer.id(), late.id()));

        assertThat(answer).isEqualTo(new LateConfirmAnswer(late.id(), BookingStatus.CONFIRMED, null));
        Accepted accepted = assertAccepted(world, late);
        assertNewEvents(late, outboxBefore, acceptEvents(late, accepted));
        O5Probe o5 = new O5Probe();

        Instant at = runner.run(o5(world, late, o5));

        assertThat(o5.runs()).as("one run of O5, first attempt").containsExactly(1);
        assertThat(o5.seen()).as("O5 found the committed Accept").isEqualTo(CONFIRMED);
        assertThat(at).isAfter(accepted.at());
        assertAcceptedThenCancelled(world, late, accepted, at);
        assertNewEvents(late, outboxBefore, acceptThenCancelEvents(late, accepted));
        assertThat(retries.serializationFailures()).as("no 40001 anywhere").isEmpty();
    }

    // ---------------------------------------------------------------- B: O5 commits, then Accept

    @Test
    void b_o5CommitsFirstThenTheCustomerAcceptIsRefusedWithNothingWritten() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Late late = awaiting(world);
        List<String> outboxBefore = outbox(late);

        Instant at = runner.run(o5(world, late, null));

        assertCancelledWhileAwaiting(world, late, at);
        assertNewEvents(late, outboxBefore, IntegrationEvents.BOOKING_CANCELLED + " " + late.id(),
                IntegrationEvents.RESERVATION_RELEASED + " " + late.holdId());
        Committed before = committed(late);

        assertRefusedStateChanged(() -> lateConfirmations.accept(world.customer.id(), late.id()));

        assertThat(committed(late)).as("every committed fact of O5's outcome").isEqualTo(before);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_ACCEPTED, late.id())).isEmpty();
        assertThat(LateConfirmDb.reservations(late.id())).as("no BOOKING reservation was made")
                .extracting(Reservation::id).containsExactlyInAnyOrder(late.expiredReservationId(), late.holdId());
        assertThat(job(bookingStart(late.id()))).as("no T4").isEmpty();
        assertCancelledWhileAwaiting(world, late, at);
    }

    // ---------------------------------------------------------------- C: Accept in flight, O5 waits

    @Test
    void c_customerAcceptInFlightMakesO5WaitThenO5CancelsTheConfirmedBookingInOneRun() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Late late = awaiting(world);
        List<String> outboxBefore = outbox(late);
        Committed awaitingBefore = committed(late);
        O5Probe o5 = new O5Probe();
        Instant at;
        int acceptPid;
        try (RowLock t2Row = RowLock.onJob(t2(late.id()))) {
            Future<LateConfirmAnswer> accepting = pool.submit(
                    () -> inOwnCorrelation(() -> ok(lateConfirmations.accept(world.customer.id(), late.id()))));
            Waiter accept = singleWaiter(LockWaits.awaitBlockedBy(t2Row.pid(), accepting, TIMEOUT));
            acceptPid = accept.pid();
            assertCustomerHeldAfterItsBookingWrites(accept, t2Row.pid());
            assertThat(committed(late)).as("committed: nothing of the Accept yet").isEqualTo(awaitingBefore);

            Future<Instant> cancelling = pool.submit(() -> inOwnCorrelation(() -> runner.run(o5(world, late, o5))));
            List<Waiter> waiting = LockWaits.awaitBlockedBy(acceptPid, cancelling, TIMEOUT);
            log.info("C: row-lock backend {} holds T2; Accept backend {} waits behind it: {}; writes {}; "
                    + "waiting behind Accept: {}", t2Row.pid(), acceptPid, accept, relationsWritten(acceptPid),
                    waiting);
            assertO5WaitedBehind(waiting, acceptPid);
            assertThat(o5.runs()).as("O5's body has not started: it waits in begin_command").isEmpty();
            assertThat(retries.serializationFailures()).isEmpty();

            t2Row.release();
            assertThat(accepting.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                    .isEqualTo(new LateConfirmAnswer(late.id(), BookingStatus.CONFIRMED, null));
            at = cancelling.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(waiting).extracting(Waiter::pid).contains(o5.pid());
        }

        log.info("C: O5 backend {} body runs (CommandContext.attempt) {}, read {}; runner 40001 lines {}", o5.pid(),
                o5.runs(), o5.seen(), retries.serializationFailures());
        assertThat(o5.runs()).as("begin_command listed Accept's committed reservation after its wait: one run, "
                + "attempt 1, no 40001 retry").containsExactly(1);
        assertThat(retries.serializationFailures()).as("CommandRunner logged no 40001 for either command").isEmpty();
        assertThat(o5.seen()).as("O5 read the CONFIRMED booking Accept committed").isEqualTo(CONFIRMED);
        assertThat(o5.pid()).isNotEqualTo(acceptPid);
        Accepted accepted = acceptedFacts(late);
        assertThat(at).as("O5's instant is sampled after Accept committed").isAfter(accepted.at());
        assertAcceptedThenCancelled(world, late, accepted, at);
        assertNewEvents(late, outboxBefore, acceptThenCancelEvents(late, accepted));
    }

    // ================================================================ Decline versus O5

    // ---------------------------------------------------------------- D: Decline commits, then O5

    @Test
    void d_customerDeclineCommitsFirstThenO5IsRefusedWithNothingWritten() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Late late = awaiting(world);
        List<String> outboxBefore = outbox(late);

        LateConfirmAnswer answer = lateConfirmations.decline(world.customer.id(), late.id());

        assertThat(answer).isEqualTo(new LateConfirmAnswer(late.id(), BookingStatus.CANCELLED,
                BookingTerminalReason.CUSTOMER_DECLINED_LATE_CONFIRM));
        Instant declinedAt = assertDeclined(world, late);
        assertNewEvents(late, outboxBefore, declineEvents(late));
        Committed before = committed(late);
        O5Probe o5 = new O5Probe();

        assertO5RefusedAsTerminal(() -> runner.run(o5(world, late, o5)));

        assertThat(o5.runs()).as("O5 ran once and was refused").containsExactly(1);
        assertThat(o5.cancelledRuns()).as("the port never returned").isZero();
        assertThat(committed(late)).as("every committed fact of the Decline").isEqualTo(before);
        assertThat(assertDeclined(world, late)).isEqualTo(declinedAt);
    }

    // ---------------------------------------------------------------- E: O5 commits, then Decline

    @Test
    void e_o5CommitsFirstThenTheCustomerDeclineIsRefusedWithNothingWritten() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Late late = awaiting(world);
        List<String> outboxBefore = outbox(late);

        Instant at = runner.run(o5(world, late, null));

        assertCancelledWhileAwaiting(world, late, at);
        Committed before = committed(late);

        assertRefusedStateChanged(() -> lateConfirmations.decline(world.customer.id(), late.id()));

        assertThat(committed(late)).as("every committed fact of O5's outcome").isEqualTo(before);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_DECLINED, late.id())).isEmpty();
        assertThat(LateConfirmDb.history(late.id())).extracting(Event::terminalReason).doesNotContain(DECLINED);
        assertNewEvents(late, outboxBefore, IntegrationEvents.BOOKING_CANCELLED + " " + late.id(),
                IntegrationEvents.RESERVATION_RELEASED + " " + late.holdId());
        assertCancelledWhileAwaiting(world, late, at);
    }

    // ---------------------------------------------------------------- F: Decline in flight, O5 waits

    @Test
    void f_customerDeclineInFlightMakesO5WaitThenDeclineCommitsAndO5IsRefused() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Late late = awaiting(world);
        List<String> outboxBefore = outbox(late);
        Committed awaitingBefore = committed(late);
        O5Probe o5 = new O5Probe();
        try (RowLock t2Row = RowLock.onJob(t2(late.id()))) {
            Future<LateConfirmAnswer> declining = pool.submit(
                    () -> inOwnCorrelation(() -> lateConfirmations.decline(world.customer.id(), late.id())));
            Waiter decline = singleWaiter(LockWaits.awaitBlockedBy(t2Row.pid(), declining, TIMEOUT));
            int declinePid = decline.pid();
            assertCustomerHeldAfterItsBookingWrites(decline, t2Row.pid());
            assertThat(relationsWritten(declinePid)).as("the Booking Fee decision is in Decline's transaction")
                    .contains("loyalty.booking_fee_refund");
            assertThat(committed(late)).as("committed: nothing of the Decline yet").isEqualTo(awaitingBefore);

            Future<Instant> cancelling = pool.submit(() -> inOwnCorrelation(() -> runner.run(o5(world, late, o5))));
            List<Waiter> waiting = LockWaits.awaitBlockedBy(declinePid, cancelling, TIMEOUT);
            log.info("F: row-lock backend {} holds T2; Decline backend {} waits behind it: {}; writes {}; "
                    + "waiting behind Decline: {}", t2Row.pid(), declinePid, decline, relationsWritten(declinePid),
                    waiting);
            assertO5WaitedBehind(waiting, declinePid);
            assertThat(o5.runs()).as("O5's body has not started: it waits in begin_command").isEmpty();

            t2Row.release();
            assertThat(declining.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(new LateConfirmAnswer(
                    late.id(), BookingStatus.CANCELLED, BookingTerminalReason.CUSTOMER_DECLINED_LATE_CONFIRM));
            assertO5RefusedAsTerminal(() -> failureOf(cancelling));
            assertThat(waiting).extracting(Waiter::pid).contains(o5.pid());
        }

        assertThat(o5.runs()).as("one run, after the Decline committed; no 40001 retry").containsExactly(1);
        assertThat(o5.cancelledRuns()).as("the port never returned").isZero();
        assertThat(o5.seen()).as("O5 read the Decline's committed outcome").isEqualTo("CANCELLED");
        assertThat(retries.serializationFailures()).isEmpty();
        assertDeclined(world, late);
        assertNewEvents(late, outboxBefore, declineEvents(late));
    }

    // ================================================================ the outcomes

    /** Accept's committed facts: the instant, its BOOKING reservation and the released hold. */
    private record Accepted(Instant at, UUID reservationId) {
    }

    /**
     * Accept's outcome (FR-LCF-08, FR-LCF-13): CONFIRMED, counted, by the Customer at its instant; the attempt closed
     * ACCEPTED then, before its deadline; the hold released ACCEPTED then and the BOOKING reservation made then on the
     * same interval; T2/T3 superseded without a lease, T4 at the start; one LateConfirmAccepted.
     */
    private static Accepted assertAccepted(OnlineBookingWorld world, Late late) {
        UUID id = late.id();
        Accepted accepted = acceptedFacts(late);
        VenueAnswerDb.BookingRow row = booking(id);
        assertThat(row.status()).isEqualTo(CONFIRMED);
        assertThat(row.terminalReason()).isNull();
        assertThat(row.counted()).isTrue();
        assertThat(LateConfirmDb.reservations(id)).filteredOn(Reservation::active).singleElement()
                .satisfies(r -> assertThat(r.id()).isEqualTo(accepted.reservationId()));
        assertThat(LateConfirmDb.history(id)).filteredOn(e -> e.toStatus() != null).last().satisfies(confirmed -> {
            assertThat(confirmed.fromStatus()).isEqualTo(AWAITING);
            assertThat(confirmed.toStatus()).isEqualTo(CONFIRMED);
            assertThat(confirmed.actorType()).isEqualTo(ActorType.CUSTOMER.name());
            assertThat(confirmed.actorId()).isEqualTo(world.customer.id());
        });
        assertThat(job(bookingStart(id))).hasValueSatisfying(t4 -> {
            assertThat(t4.status()).isEqualTo("PENDING");
            assertThat(t4.dueAt()).isEqualTo(late.startAt());
        });
        assertThat(refunds(id)).as("no fee decision while CONFIRMED").isEmpty();
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, id)).isEmpty();
        assertLateConfirmIntegrityHolds(late);
        return accepted;
    }

    /** The parts of Accept's outcome that O5 never changes, read from the committed rows. */
    private static Accepted acceptedFacts(Late late) {
        UUID id = late.id();
        LateConfirmDb.Attempt closed = LateConfirmDb.attempt(id).orElseThrow();
        assertThat(closed.id()).isEqualTo(late.attemptId());
        assertThat(closed.response()).isEqualTo("ACCEPTED");
        assertThat(closed.respondedAt()).isEqualTo(closed.closedAt());
        assertThat(closed.expiresAt()).as("the deadline as Initiate stored it").isEqualTo(late.expiresAt());
        Instant at = closed.respondedAt();
        assertThat(at).as("accepted strictly before the stored deadline (FR-AVL-15)").isBefore(late.expiresAt());
        assertThat(LateConfirmDb.attempts(id)).isEqualTo(1);
        List<Reservation> rows = LateConfirmDb.reservations(id);
        assertThat(rows).as("the expired original, the hold and Accept's own").hasSize(3);
        assertOriginalReservationUntouched(late);
        assertThat(rows).filteredOn(r -> r.type().equals("LATE_CONFIRM_HOLD")).singleElement().satisfies(hold -> {
            assertThat(hold.id()).isEqualTo(late.holdId());
            assertThat(hold.active()).isFalse();
            assertThat(hold.releasedAt()).isEqualTo(at);
            assertThat(hold.releaseReason()).isEqualTo("ACCEPTED");
        });
        Reservation made = rows.stream()
                .filter(r -> r.type().equals("BOOKING") && !r.id().equals(late.expiredReservationId()))
                .findFirst().orElseThrow();
        assertThat(made.actualStart()).as("made at Accept's instant").isEqualTo(at);
        assertThat(made.plannedStart()).isEqualTo(late.startAt());
        assertThat(made.plannedEnd()).isEqualTo(late.endAt());
        assertThat(LateConfirmDb.history(id)).filteredOn(e -> CONFIRMED.equals(e.toStatus())).singleElement()
                .satisfies(confirmed -> assertThat(confirmed.occurredAt()).isEqualTo(at));
        assertTimer(t2(id), "SUPERSEDED");
        assertTimer(t3(id), "SUPERSEDED");
        assertThat(events(IntegrationEvents.LATE_CONFIRM_ACCEPTED, id)).singleElement()
                .satisfies(payload -> assertThat(payload).contains(id.toString()));
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, late.holdId())).as("hold released once").hasSize(1);
        assertThat(events(IntegrationEvents.RESERVATION_HELD, made.id())).hasSize(1);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_DECLINED, id)).isEmpty();
        assertThat(events(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, id)).isEmpty();
        return new Accepted(at, made.id());
    }

    /**
     * Accept, then O5 (FR-VEN-24): AWAITING -> CONFIRMED by the Customer, then CONFIRMED -> CANCELLED /
     * VENUE_OPENING_HOURS_CHANGED by the venue's account at O5's instant - one terminal row; Accept's BOOKING
     * reservation released then (the hold stays as Accept released it); one FULL_REFUND on O5's row; T4 superseded; one
     * LateConfirmAccepted and one BookingCancelled.
     */
    private static void assertAcceptedThenCancelled(OnlineBookingWorld world, Late late, Accepted accepted,
                                                    Instant at) {
        UUID id = late.id();
        assertThat(acceptedFacts(late)).as("Accept's facts as committed").isEqualTo(accepted);
        VenueAnswerDb.BookingRow row = booking(id);
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo(REASON);
        assertThat(row.terminalAt()).isEqualTo(at);
        assertThat(row.feeTreatment()).isEqualTo("FULL_REFUND");
        assertThat(row.counted()).as("the commitment slot is free").isFalse();
        List<Event> rows = LateConfirmDb.history(id).stream().filter(e -> e.toStatus() != null).toList();
        assertThat(rows).extracting(Event::toStatus).as("the booking's transitions")
                .endsWith(AWAITING, CONFIRMED, "CANCELLED");
        assertThat(rows).filteredOn(e -> "CANCELLED".equals(e.toStatus())).as("exactly one terminal outcome")
                .singleElement().satisfies(last -> {
                    assertThat(last).isEqualTo(rows.getLast());
                    assertThat(last.fromStatus()).isEqualTo(CONFIRMED);
                    assertThat(last.toStatus()).isEqualTo("CANCELLED");
                    assertThat(last.terminalReason()).isEqualTo(REASON);
                    assertThat(last.occurredAt()).isEqualTo(at);
                    assertThat(last.actorType()).isEqualTo(ActorType.VENUE.name());
                    assertThat(last.actorId()).isEqualTo(world.account.id());
                });
        assertThat(rows.get(rows.size() - 2)).satisfies(confirmed -> {
            assertThat(confirmed.toStatus()).isEqualTo(CONFIRMED);
            assertThat(confirmed.actorType()).isEqualTo(ActorType.CUSTOMER.name());
            assertThat(confirmed.actorId()).isEqualTo(world.customer.id());
        });
        assertThat(LateConfirmDb.reservations(id)).filteredOn(r -> r.id().equals(accepted.reservationId()))
                .singleElement().satisfies(made -> {
                    assertThat(made.active()).isFalse();
                    assertThat(made.releasedAt()).isEqualTo(at);
                    assertThat(made.releaseReason()).isEqualTo(REASON);
                });
        assertThat(LateConfirmDb.reservations(id)).filteredOn(Reservation::active)
                .as("nothing of the booking is still held").isEmpty();
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, accepted.reservationId())).as("released once")
                .hasSize(1);
        assertOneRefund(late, REASON);
        assertTimer(bookingStart(id), "SUPERSEDED");
        assertThat(job(ManualBookingCommand.bookingCompleteKey(id))).as("no T5 was ever scheduled").isEmpty();
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, id)).singleElement()
                .satisfies(payload -> assertThat(payload).contains(REASON));
        assertThat(events(IntegrationEvents.LATE_CONFIRM_ACCEPTED, id)).hasSize(1);
        assertLateConfirmIntegrityHolds(late);
    }

    /**
     * O5 on the AWAITING booking (Phase 3C, FR-VEN-28): CANCELLED / VENUE_OPENING_HOURS_CHANGED at O5's instant by the
     * venue's account, the attempt closed then without a Customer response, the hold released then, one FULL_REFUND,
     * T2/T3 superseded, no BOOKING reservation, no Late Confirm event.
     */
    private static void assertCancelledWhileAwaiting(OnlineBookingWorld world, Late late, Instant at) {
        UUID id = late.id();
        VenueAnswerDb.BookingRow row = booking(id);
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo(REASON);
        assertThat(row.terminalAt()).isEqualTo(at);
        assertThat(row.feeTreatment()).isEqualTo("FULL_REFUND");
        assertThat(row.counted()).isFalse();
        List<Event> rows = LateConfirmDb.history(id).stream().filter(e -> e.toStatus() != null).toList();
        assertThat(rows).filteredOn(e -> "CANCELLED".equals(e.toStatus())).as("exactly one terminal outcome")
                .singleElement().satisfies(last -> {
                    assertThat(last).isEqualTo(rows.getLast());
                    assertThat(last.fromStatus()).isEqualTo(AWAITING);
                    assertThat(last.terminalReason()).isEqualTo(REASON);
                    assertThat(last.occurredAt()).isEqualTo(at);
                    assertThat(last.actorType()).isEqualTo(ActorType.VENUE.name());
                    assertThat(last.actorId()).isEqualTo(world.account.id());
                });
        assertThat(rows).extracting(Event::toStatus).doesNotContain(CONFIRMED);
        LateConfirmDb.Attempt closed = LateConfirmDb.attempt(id).orElseThrow();
        assertThat(closed.id()).isEqualTo(late.attemptId());
        assertThat(closed.closedAt()).isEqualTo(at);
        assertThat(closed.response()).as("no Customer response").isNull();
        assertThat(closed.respondedAt()).isNull();
        assertThat(closed.expiresAt()).isEqualTo(late.expiresAt());
        assertOriginalReservationUntouched(late);
        assertThat(LateConfirmDb.reservations(id)).hasSize(2).filteredOn(r -> r.id().equals(late.holdId()))
                .singleElement().satisfies(hold -> {
            assertThat(hold.id()).isEqualTo(late.holdId());
            assertThat(hold.type()).isEqualTo("LATE_CONFIRM_HOLD");
            assertThat(hold.active()).isFalse();
            assertThat(hold.releasedAt()).isEqualTo(at);
            assertThat(hold.releaseReason()).isEqualTo(REASON);
        });
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, late.holdId())).hasSize(1);
        assertOneRefund(late, REASON);
        assertTimer(t2(id), "SUPERSEDED");
        assertTimer(t3(id), "SUPERSEDED");
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, id)).hasSize(1);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_ACCEPTED, id)).isEmpty();
        assertThat(events(IntegrationEvents.LATE_CONFIRM_DECLINED, id)).isEmpty();
        assertLateConfirmIntegrityHolds(late);
    }

    /**
     * Decline's outcome (FR-LCF-13): CANCELLED / CUSTOMER_DECLINED_LATE_CONFIRM by the Customer at its instant, the
     * attempt closed DECLINED then, the hold released then with that reason, one FULL_REFUND (FR-PTS-17) on its row, no
     * Trust event, T2/T3 superseded, one LateConfirmDeclined and no BookingCancelled. Returns the instant.
     */
    private static Instant assertDeclined(OnlineBookingWorld world, Late late) {
        UUID id = late.id();
        LateConfirmDb.Attempt closed = LateConfirmDb.attempt(id).orElseThrow();
        assertThat(closed.id()).isEqualTo(late.attemptId());
        assertThat(closed.response()).isEqualTo("DECLINED");
        assertThat(closed.respondedAt()).isEqualTo(closed.closedAt());
        assertThat(closed.expiresAt()).isEqualTo(late.expiresAt());
        Instant at = closed.respondedAt();
        assertThat(at).as("declined strictly before the stored deadline").isBefore(late.expiresAt());
        VenueAnswerDb.BookingRow row = booking(id);
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo(DECLINED);
        assertThat(row.terminalAt()).isEqualTo(at);
        assertThat(row.feeTreatment()).isEqualTo("FULL_REFUND");
        assertThat(row.counted()).isFalse();
        List<Event> rows = LateConfirmDb.history(id).stream().filter(e -> e.toStatus() != null).toList();
        assertThat(rows).filteredOn(e -> "CANCELLED".equals(e.toStatus())).as("exactly one terminal outcome")
                .singleElement().satisfies(last -> {
                    assertThat(last).isEqualTo(rows.getLast());
                    assertThat(last.fromStatus()).isEqualTo(AWAITING);
                    assertThat(last.terminalReason()).isEqualTo(DECLINED);
                    assertThat(last.occurredAt()).isEqualTo(at);
                    assertThat(last.actorType()).isEqualTo(ActorType.CUSTOMER.name());
                    assertThat(last.actorId()).isEqualTo(world.customer.id());
                });
        assertThat(rows).extracting(Event::terminalReason).doesNotContain(REASON);
        assertOriginalReservationUntouched(late);
        assertThat(LateConfirmDb.reservations(id)).hasSize(2).filteredOn(r -> r.id().equals(late.holdId()))
                .singleElement().satisfies(hold -> {
            assertThat(hold.id()).isEqualTo(late.holdId());
            assertThat(hold.active()).isFalse();
            assertThat(hold.releasedAt()).isEqualTo(at);
            assertThat(hold.releaseReason()).isEqualTo(DECLINED);
        });
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, late.holdId())).as("released once").hasSize(1);
        assertOneRefund(late, DECLINED);
        assertThat(VenueAnswerDb.count("trust.trust_event WHERE booking_id = :id", id)).as("no penalty").isZero();
        assertTimer(t2(id), "SUPERSEDED");
        assertTimer(t3(id), "SUPERSEDED");
        assertThat(job(bookingStart(id))).isEmpty();
        assertThat(events(IntegrationEvents.LATE_CONFIRM_DECLINED, id)).singleElement()
                .satisfies(payload -> assertThat(payload).contains(DECLINED));
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, id)).isEmpty();
        assertThat(events(IntegrationEvents.LATE_CONFIRM_ACCEPTED, id)).isEmpty();
        assertLateConfirmIntegrityHolds(late);
        return at;
    }

    private static String[] acceptEvents(Late late, Accepted accepted) {
        return new String[] {IntegrationEvents.LATE_CONFIRM_ACCEPTED + " " + late.id(),
                IntegrationEvents.RESERVATION_RELEASED + " " + late.holdId(),
                IntegrationEvents.RESERVATION_HELD + " " + accepted.reservationId()};
    }

    private static String[] acceptThenCancelEvents(Late late, Accepted accepted) {
        return new String[] {IntegrationEvents.LATE_CONFIRM_ACCEPTED + " " + late.id(),
                IntegrationEvents.RESERVATION_RELEASED + " " + late.holdId(),
                IntegrationEvents.RESERVATION_HELD + " " + accepted.reservationId(),
                IntegrationEvents.BOOKING_CANCELLED + " " + late.id(),
                IntegrationEvents.RESERVATION_RELEASED + " " + accepted.reservationId()};
    }

    private static String[] declineEvents(Late late) {
        return new String[] {IntegrationEvents.LATE_CONFIRM_DECLINED + " " + late.id(),
                IntegrationEvents.RESERVATION_RELEASED + " " + late.holdId()};
    }

    /** One FULL_REFUND decision for the Customer, resting on the booking's one terminal history row. */
    private static void assertOneRefund(Late late, String reason) {
        UUID terminalRow = OWNER.sql("""
                        SELECT id FROM booking.booking_history
                         WHERE booking_id = :id AND to_status = 'CANCELLED'""")
                .param("id", late.id()).query(UUID.class).single();
        assertThat(refunds(late.id())).singleElement().satisfies(refund -> {
            assertThat(refund.treatment()).isEqualTo("FULL_REFUND");
            assertThat(refund.terminalReason()).isEqualTo(reason);
            assertThat(refund.userId()).isEqualTo(late.customerId());
            assertThat(refund.historyId()).as("the terminal transition's own history row").isEqualTo(terminalRow);
        });
    }

    /** A timer the Customer's command or O5 withdrew: settled without a lease, holding none. */
    private static void assertTimer(String key, String status) {
        assertThat(job(key)).as(key).hasValueSatisfying(job -> {
            assertThat(job.status()).as("%s status", key).isEqualTo(status);
            assertThat(job.attempt()).as("%s leases", key).isZero();
            assertThat(job.completedAt()).as("%s completed", key).isNotNull();
            assertThat(job.leasedBy()).as("%s holds no lease", key).isNull();
            assertThat(job.lastError()).as("%s error", key).isNull();
        });
    }

    /** The booking's first BOOKING reservation, released by T1 before the Late Confirmation, stays exactly so. */
    private static void assertOriginalReservationUntouched(Late late) {
        assertThat(LateConfirmDb.reservations(late.id())).filteredOn(r -> r.id().equals(late.expiredReservationId()))
                .containsExactly(late.expired());
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, late.expiredReservationId())).hasSize(1);
    }

    /** The database's own final-state assertion of the attempt, called again on the committed rows. */
    private static void assertLateConfirmIntegrityHolds(Late late) {
        assertThat(OWNER.sql("SELECT 1 FROM (SELECT booking.assert_late_confirmation_row(:id)) checked")
                .param("id", late.attemptId()).query(Integer.class).single()).isEqualTo(1);
    }

    /** Exactly these events were committed since {@code before}, on the booking and its reservations. */
    private static void assertNewEvents(Late late, List<String> before, String... expected) {
        List<String> after = outbox(late);
        assertThat(after).as("nothing committed earlier changed").startsWith(before.toArray(String[]::new));
        assertThat(after.subList(before.size(), after.size())).containsExactlyInAnyOrder(expected);
    }

    /** The Customer's command, refused through its established contract: 409 BOOKING_STATE_CHANGED, thrown. */
    private static void assertRefusedStateChanged(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED));
    }

    /** O5 on a terminal booking: the port's caller-defect refusal (D3), the whole command rolled back. */
    private static void assertO5RefusedAsTerminal(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED ONLINE")
                .hasMessageContaining("is not cancelled by an hours change");
    }

    /**
     * The Customer's command waits on the held T2 row inside its open transaction, in {@code supersedeJobs} - after its
     * booking writes: the hold release and the BOOKING reservation (Accept) or the cancellation (Decline), the
     * attempt's response and the history row are uncommitted writes of that backend.
     */
    private static void assertCustomerHeldAfterItsBookingWrites(Waiter customer, int holderPid) {
        assertThat(customer.waitEventType()).isEqualTo("Lock");
        assertThat(customer.blockedBy()).containsExactly(holderPid);
        assertThat(customer.query()).contains("UPDATE technical.scheduled_job");
        assertThat(relationsWritten(customer.pid())).as("uncommitted writes of the Customer's backend")
                .contains("booking.booking", "booking.booking_history", "booking.late_confirmation",
                        "inventory.resource_reservation");
    }

    /** O5's own backend waited on a lock, in {@code begin_command}, behind exactly the Customer's backend. */
    private static void assertO5WaitedBehind(List<Waiter> waiting, int customerPid) {
        assertThat(waiting).as("O5 waited in begin_command behind the Customer's backend").singleElement()
                .satisfies(waiter -> {
                    assertThat(waiter.pid()).isNotEqualTo(customerPid);
                    assertThat(waiter.waitEventType()).isEqualTo("Lock");
                    assertThat(waiter.blockedBy()).containsExactly(customerPid);
                    assertThat(waiter.query()).contains("tournament.begin_command");
                });
    }

    private static Waiter singleWaiter(List<Waiter> waiting) {
        assertThat(waiting).as("only the Customer's command waits on the held row").hasSize(1);
        return waiting.getFirst();
    }

    /** The tables a backend holds ROW EXCLUSIVE on - the ones its open transaction has written. */
    private static List<String> relationsWritten(int pid) {
        return OWNER.sql("""
                        SELECT DISTINCT n.nspname || '.' || c.relname
                          FROM pg_locks l
                          JOIN pg_class c ON c.oid = l.relation
                          JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE l.pid = :pid AND l.mode = 'RowExclusiveLock' AND l.granted AND c.relkind = 'r'
                         ORDER BY 1""")
                .param("pid", pid).query(String.class).list();
    }

    // ================================================================ setup

    /** A real AWAITING booking: made, expired by T1, then Late Confirmed by the venue (open attempt, active hold). */
    private Late awaiting(OnlineBookingWorld world) {
        UUID id = new LateConfirmDb(runner, creation, store, answers).expired(world, world.start(), 60);
        ok(lateConfirmations.initiate(world.account.id(), id));
        LateConfirmDb.Attempt open = LateConfirmDb.attempt(id).orElseThrow();
        LateConfirmDb.Amounts amounts = LateConfirmDb.amounts(id);
        assertThat(booking(id).status()).isEqualTo(AWAITING);
        assertThat(open.closedAt()).isNull();
        assertThat(open.expiresAt()).as("Initiate's window is still open")
                .isAfter(VenueAnswerDb.dbNow().plus(Duration.ofMinutes(4)));
        List<Reservation> rows = LateConfirmDb.reservations(id);
        assertThat(rows).as("the original BOOKING reservation T1 released, and the hold").hasSize(2);
        assertThat(rows).filteredOn(r -> r.id().equals(open.holdId())).singleElement().satisfies(hold -> {
            assertThat(hold.type()).isEqualTo("LATE_CONFIRM_HOLD");
            assertThat(hold.active()).isTrue();
        });
        Reservation expired = rows.stream().filter(r -> !r.id().equals(open.holdId())).findFirst().orElseThrow();
        assertThat(expired.type()).isEqualTo("BOOKING");
        assertThat(expired.active()).isFalse();
        assertThat(expired.releaseReason()).isEqualTo("VENUE_RESPONSE_EXPIRED");
        assertThat(job(t2(id))).hasValueSatisfying(j -> assertThat(j.status()).isEqualTo("PENDING"));
        assertThat(job(t3(id))).hasValueSatisfying(j -> assertThat(j.status()).isEqualTo("PENDING"));
        assertThat(refunds(id)).isEmpty();
        return new Late(id, world.customer.id(), open.id(), open.holdId(), expired, open.expiresAt(),
                amounts.startAt(), amounts.endAt());
    }

    // ================================================================ commands

    /**
     * O5 as Phase 4 will call it: the port, by the venue's account, in one command under the booking's Customer, Venue
     * and Resource locks, computed once before the command (as the runner keeps them for every attempt). The probe,
     * when given, records each run's attempt number, backend and the booking status it read under the locks.
     */
    private Command<Instant> o5(OnlineBookingWorld world, Late late, O5Probe probe) {
        return TestCommands.under(scope(late), command -> {
            if (probe != null) {
                probe.started(command, late);
            }
            port.cancel(command, world.venue.id(), world.account.id(), List.of(late.id()));
            if (probe != null) {
                probe.cancelled();
            }
            return command.commandNow();
        });
    }

    /** The locks O5 must hold - the same rows Accept and Decline lock ({@link LateConfirmations#lockSetOf}). */
    private CommandScope scope(Late late) {
        CommandScope scope = runner.run(new FindBooking(store, late.id())).orElseThrow().scope();
        assertThat(scope.lockSet()).as("O5 and the Customer's commands name the same rows")
                .isEqualTo(lateConfirmations.lockSetOf(late.id()));
        return scope;
    }

    private static <R> R inOwnCorrelation(java.util.function.Supplier<R> body) {
        try (CorrelationId.Scope ignored = CorrelationId.open(UUID.randomUUID())) {
            return body.get();
        }
    }

    /** The racer's own exception, unwrapped and rethrown; fails if it returned or did not end in time. */
    private static Object failureOf(Future<?> racer) throws Throwable {
        Object value;
        try {
            value = racer.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError("the racer did not end within " + TIMEOUT, e);
        }
        throw new AssertionError("expected a refusal, got " + value);
    }

    /** The AWAITING booking: its attempt and hold, and the original BOOKING reservation T1 released before them. */
    private record Late(UUID id, UUID customerId, UUID attemptId, UUID holdId, Reservation expired,
                        Instant expiresAt, Instant startAt, Instant endAt) {

        UUID expiredReservationId() {
            return expired.id();
        }
    }

    /** What O5's runs saw: each run's {@link CommandContext#attempt()}, the last backend and booking status. */
    private static final class O5Probe {

        private final List<Integer> runs = new CopyOnWriteArrayList<>();
        private final AtomicInteger cancelledRuns = new AtomicInteger();
        private volatile int pid;
        private volatile String seen;

        void started(CommandContext context, Late late) {
            runs.add(context.attempt());
            pid = LockWaits.backendPid(context);
            seen = context.jdbc().sql("SELECT status FROM booking.booking WHERE id = :id")
                    .param("id", late.id()).query(String.class).single();
        }

        void cancelled() {
            cancelledRuns.incrementAndGet();
        }

        List<Integer> runs() {
            return List.copyOf(runs);
        }

        int cancelledRuns() {
            return cancelledRuns.get();
        }

        int pid() {
            return pid;
        }

        String seen() {
            return seen;
        }
    }

    /**
     * TEST-ONLY: an ordinary row lock on one scheduled job, held by its own owner connection in an open transaction
     * that writes nothing; {@link #release} rolls it back. Used only to hold a Customer command before its COMMIT.
     */
    private static final class RowLock implements AutoCloseable {

        private final Connection connection;
        private final int pid;
        private boolean released;

        private RowLock(Connection connection, int pid) {
            this.connection = connection;
            this.pid = pid;
        }

        static RowLock onJob(String idempotencyKey) throws SQLException {
            Connection connection = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR,
                    MIGRATOR);
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT id FROM technical.scheduled_job WHERE idempotency_key = ? AND status = 'PENDING'"
                                + " FOR UPDATE")) {
                    lock.setString(1, idempotencyKey);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("no PENDING job " + idempotencyKey + " to hold");
                        }
                    }
                }
                try (PreparedStatement self = connection.prepareStatement("SELECT pg_backend_pid()");
                     ResultSet rs = self.executeQuery()) {
                    rs.next();
                    return new RowLock(connection, rs.getInt(1));
                }
            } catch (SQLException | RuntimeException e) {
                connection.close();
                throw e;
            }
        }

        int pid() {
            return pid;
        }

        void release() throws SQLException {
            if (!released) {
                released = true;
                connection.rollback();
            }
        }

        @Override
        public void close() throws SQLException {
            try {
                release();
            } finally {
                connection.close();
            }
        }
    }

    /**
     * The runner's own record of every SQLSTATE 40001 it retried or gave up on ({@code CommandRunner}'s DEBUG and WARN
     * lines), captured for the test and detached after it.
     */
    private static final class RetryLog {

        private final Logger logger;
        private final Level previous;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        private RetryLog(Logger logger) {
            this.logger = logger;
            this.previous = logger.getLevel();
        }

        static RetryLog attach() {
            RetryLog log = new RetryLog((Logger) LoggerFactory.getLogger(CommandRunner.class));
            log.appender.start();
            log.logger.addAppender(log.appender);
            log.logger.setLevel(Level.DEBUG);
            return log;
        }

        List<String> serializationFailures() {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("40001")).toList();
        }

        void detach() {
            logger.detachAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
    }

    // ================================================================ committed reads (owner connection)

    record JobState(String status, int attempt, Instant dueAt, Instant completedAt, String leasedBy,
                    String lastError) {
    }

    record Refund(UUID id, String treatment, String terminalReason, UUID userId, UUID historyId) {
    }

    /** Every committed fact of the booking either side writes. */
    record Committed(VenueAnswerDb.BookingRow booking, List<Event> history, LateConfirmDb.Attempt attempt,
                     List<Reservation> reservations, List<Refund> refunds, Optional<JobState> t2,
                     Optional<JobState> t3, Optional<JobState> t4, Optional<JobState> t5, List<String> events) {
    }

    private static Committed committed(Late late) {
        UUID id = late.id();
        return new Committed(booking(id), LateConfirmDb.history(id), LateConfirmDb.attempt(id).orElseThrow(),
                LateConfirmDb.reservations(id), refunds(id), job(t2(id)), job(t3(id)), job(bookingStart(id)),
                job(ManualBookingCommand.bookingCompleteKey(id)), outbox(late));
    }

    private static Optional<JobState> job(String key) {
        return OWNER.sql("""
                        SELECT status, attempt, due_at, completed_at, leased_by, last_error
                          FROM technical.scheduled_job WHERE idempotency_key = :key""")
                .param("key", key)
                .query((rs, n) -> new JobState(rs.getString("status"), rs.getInt("attempt"),
                        instant(rs.getObject("due_at", OffsetDateTime.class)),
                        instant(rs.getObject("completed_at", OffsetDateTime.class)), rs.getString("leased_by"),
                        rs.getString("last_error")))
                .optional();
    }

    private static List<Refund> refunds(UUID bookingId) {
        return OWNER.sql("""
                        SELECT id, treatment, terminal_reason, user_id, booking_history_id
                          FROM loyalty.booking_fee_refund WHERE booking_id = :id ORDER BY id""")
                .param("id", bookingId)
                .query((rs, n) -> new Refund(rs.getObject("id", UUID.class), rs.getString("treatment"),
                        rs.getString("terminal_reason"), rs.getObject("user_id", UUID.class),
                        rs.getObject("booking_history_id", UUID.class)))
                .list();
    }

    /** The outbox events of the booking and of its reservations, in commit order. */
    private static List<String> outbox(Late late) {
        return OWNER.sql("""
                        SELECT e.event_type || ' ' || e.aggregate_id FROM technical.outbox_event e
                         WHERE e.aggregate_id = :id
                            OR e.aggregate_id IN (SELECT r.id FROM inventory.resource_reservation r
                                                   WHERE r.booking_id = :id)
                         ORDER BY e.id""")
                .param("id", late.id()).query(String.class).list();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
