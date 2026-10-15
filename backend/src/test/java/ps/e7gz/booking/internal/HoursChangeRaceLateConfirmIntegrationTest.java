package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.booking.internal.LateConfirmDb.t2;
import static ps.e7gz.booking.internal.LateConfirmDb.t3;
import static ps.e7gz.booking.internal.LateConfirmIntegrationTest.ok;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.events;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.booking.internal.LockWaits.Waiter;
import ps.e7gz.booking.internal.VenueAnswerDb.HistoryRow;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmExpiryHandler;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmWarningHandler;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmations;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.scheduling.api.CommandJobHandler;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.Boundary;
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.venue.api.HoursChangeBookings;

/**
 * E7-113 Phase 3D-B: the REAL Late Confirm timers - T2 ({@code LATE_CONFIRM_EXPIRY}, {@link LateConfirmExpiryHandler})
 * and T3 ({@code LATE_CONFIRM_WARNING}, {@link LateConfirmWarningHandler}) - fired by the real deadline worker (E7-40,
 * through {@link DueJobs}: {@code begin_command}, {@code job_lease}, the handler, {@code job_finish}, COMMIT - one
 * transaction) racing the opening-hours cancellation (O5, {@link HoursChangeBookings#cancel} in a committed command
 * under the booking's Customer, Venue and Resource locks) on one AWAITING_CUSTOMER_CONFIRMATION Online Booking with an
 * open attempt and an active LATE_CONFIRM_HOLD. Whichever commits first owns the booking; the other changes nothing
 * (FR-VEN-28, FR-LCF-09, FR-LCF-12).
 *
 * <p>The order is fixed by the transactions' own locks: the timers' lock set ({@link LateConfirmations#lockSetOf}) and
 * O5's name the same Customer, Venue and Resource, so {@code begin_command} serialises them. Tests C and D hold one
 * side open at a gate (inside its transaction, after its writes, before COMMIT) and prove from
 * {@code pg_blocking_pids} that the other side waits behind exactly that backend. No lease is put by hand and no
 * trigger is off while a racer runs.
 *
 * <p>Deadline fixtures (disclosed, setup only): the open attempt's stored deadline only moves later
 * ({@code guard_late_confirmation_mutation}), and Initiate stores a five-minute window. Every test therefore first
 * shortens it with {@link LateConfirmDb#deadlineAt} (triggers off, before any racer starts, as if the window had been
 * shorter), then moves it later to the instant it needs through the guarded path ({@code Deadlines.move}) inside a
 * committed command under the booking's locks, and makes the timer due with {@code DueJobs.makeDue}
 * ({@code job_rearm}). The C2 boundary tests pin the deadline to the racer's own command instant through the same
 * guarded {@code Deadlines.move}, inside that racer's own transaction - the decision is still the real handler's or
 * the real port's.
 *
 * <p>C2, Option A - PROVISIONAL (Amr, 15 Oct 2026), PENDING SAYED'S FINAL PRODUCT REVIEW: an AWAITING booking at or
 * past its stored deadline whose T2 has not committed is still AWAITING, and O5 may cancel it. T2-A, T2-D and the C2
 * boundary tests rest on it; if Sayed decides FR-AVL-15 gives T2 the state from the deadline, they change with
 * {@code HoursChangeCancellation}.
 */
class HoursChangeRaceLateConfirmIntegrationTest extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HoursChangeRaceLateConfirmIntegrationTest.class);
    private static final String REASON = "VENUE_OPENING_HOURS_CHANGED";
    private static final String TIMEOUT_REASON = "CUSTOMER_RECONFIRM_TIMEOUT";
    private static final String AWAITING = BookingStatus.AWAITING_CUSTOMER_CONFIRMATION.name();
    private static final String EXPIRES_AT = "booking.late_confirmation.expires_at";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** T3's deadline ahead of the command that makes it due: inside the warning minute, far from its end. */
    private static final Duration WARNING_WINDOW_LEFT = Duration.ofSeconds(45);

    @Autowired
    ApplicationContext context;

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

    @BeforeEach
    void open() {
        correlation = CorrelationId.open(UUID.randomUUID());
        AtomicInteger threads = new AtomicInteger();
        pool = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "lc-race-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterEach
    void close() throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("race threads ended").isTrue();
        correlation.close();
    }

    // ================================================================ T2 (Late Confirm expiry) versus O5

    // ---------------------------------------------------------------- T2-A: O5 commits, then T2 fires

    @Test
    void t2A_o5CommitsFirstThenTheRealT2WorkerCannotLeaseItsSupersededJob() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Late late = awaiting(world);
        WorkerProbe probe = new WorkerProbe(realT2(), null, null);
        GatedCommandJobHandler t2 = GatedCommandJobHandler.counting(probe);
        DueJobs due = DueJobs.of(context, t2);
        Instant deadline = makeT2Due(due, late);
        List<String> outboxBefore = outbox(late);

        Instant at = runner.run(o5(world, late, null, null));

        assertThat(at).as("C2 Option A: past the deadline, T2 not committed").isAfter(deadline);
        assertCancelledByTheHoursChange(world, late, at, deadline);
        assertTimer(t2(late.id()), "SUPERSEDED", 0);
        assertTimer(t3(late.id()), "SUPERSEDED", 0);
        assertNewEvents(late, outboxBefore, IntegrationEvents.BOOKING_CANCELLED + " " + late.id(),
                IntegrationEvents.RESERVATION_RELEASED + " " + late.holdId());
        Committed before = committed(late);

        assertThat(due.fire(t2(late.id()))).as("the worker settles nothing").isFalse();
        assertThat(due.fire(t2(late.id()))).as("nor on a second poll").isFalse();

        assertThat(t2.fires()).as("job_lease refused the superseded T2: the handler never ran").isZero();
        assertThat(committed(late)).as("every committed fact, the timers included").isEqualTo(before);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, late.id())).isEmpty();
    }

    // ---------------------------------------------------------------- T2-B: T2 commits, then O5

    @Test
    void t2B_theRealT2WorkerCommitsFirstAndO5IsRefusedWithNothingWritten() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Late late = awaiting(world);
        WorkerProbe probe = new WorkerProbe(realT2(), null, null);
        GatedCommandJobHandler t2 = GatedCommandJobHandler.counting(probe);
        DueJobs due = DueJobs.of(context, t2);
        Instant deadline = makeT2Due(due, late);
        List<String> outboxBefore = outbox(late);

        assertThat(due.fire(t2(late.id()))).as("the real worker fired T2").isTrue();

        assertThat(t2.fires()).isEqualTo(1);
        assertThat(probe.outcome()).isInstanceOf(JobOutcome.Done.class);
        assertTimedOutByT2(late, deadline);
        assertNewEvents(late, outboxBefore, IntegrationEvents.LATE_CONFIRM_TIMED_OUT + " " + late.id(),
                IntegrationEvents.RESERVATION_RELEASED + " " + late.holdId());
        Committed before = committed(late);
        O5Probe o5 = new O5Probe();

        assertThatThrownBy(() -> runner.run(o5(world, late, o5, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED ONLINE")
                .hasMessageContaining("is not cancelled by an hours change");

        assertThat(o5.attempts()).as("O5 ran once and was refused").isEqualTo(1);
        assertThat(committed(late)).as("every committed fact of T2's outcome").isEqualTo(before);
        assertTimedOutByT2(late, deadline);
        assertThat(due.fire(t2(late.id()))).as("a DONE job never fires again").isFalse();
        assertThat(t2.fires()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- T2-C: T2 in flight, O5 waits

    @Test
    void t2C_aT2FireInFlightMakesO5WaitThenT2CommitsAndO5IsRefused() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Late late = awaiting(world);
        WorkerProbe probe = new WorkerProbe(realT2(), null, null);
        GatedCommandJobHandler t2 = GatedCommandJobHandler.pausingAfterFire(probe, TIMEOUT);
        DueJobs due = DueJobs.of(context, t2);
        Instant deadline = makeT2Due(due, late);
        List<String> outboxBefore = outbox(late);
        O5Probe o5 = new O5Probe();
        try {
            Future<Boolean> worker = pool.submit(() -> due.fire(t2(late.id())));
            t2.awaitReached(worker);
            int workerPid = t2.firePid();
            assertUncommittedTimerFire(probe, late, "CANCELLED");
            assertThat(booking(late.id()).status()).as("committed: still AWAITING").isEqualTo(AWAITING);
            assertTimer(t2(late.id()), "PENDING", 0);

            Future<Instant> cancelling = pool.submit(() -> inOwnCorrelation(o5(world, late, o5, null)));
            List<Waiter> waiting = LockWaits.awaitBlockedBy(workerPid, cancelling, TIMEOUT);
            log.info("T2-C: T2 fire backend {} holds its transaction open; waiting behind it: {}", workerPid, waiting);
            assertThat(o5.attempts()).as("O5's body has not started: it waits in begin_command").isZero();

            t2.release();
            assertThat(worker.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("T2 settled").isTrue();
            Throwable refused = failureOf(cancelling);

            assertThat(refused).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CANCELLED ONLINE")
                    .hasMessageContaining("is not cancelled by an hours change");
            assertO5WaitedBehind(waiting, o5.pid(), workerPid);
            assertThat(o5.attempts()).as("one run, refused, nothing kept").isEqualTo(1);
            assertThat(o5.cancelledRuns()).as("the port never returned").isZero();
        } finally {
            t2.release();
        }

        assertThat(t2.fires()).isEqualTo(1);
        assertTimedOutByT2(late, deadline);
        assertNewEvents(late, outboxBefore, IntegrationEvents.LATE_CONFIRM_TIMED_OUT + " " + late.id(),
                IntegrationEvents.RESERVATION_RELEASED + " " + late.holdId());
    }

    // ---------------------------------------------------------------- T2-D: O5 in flight, T2 waits

    @Test
    void t2D_anO5InFlightMakesTheT2WorkerWaitThenO5CommitsAndTheWorkerCannotLease() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Late late = awaiting(world);
        WorkerProbe probe = new WorkerProbe(realT2(), null, null);
        GatedCommandJobHandler t2 = GatedCommandJobHandler.counting(probe);
        DueJobs due = DueJobs.of(context, t2);
        Instant deadline = makeT2Due(due, late);
        List<String> outboxBefore = outbox(late);
        O5Probe o5 = new O5Probe();
        o5.gate();
        Instant at;
        try {
            Future<Instant> cancelling = pool.submit(() -> inOwnCorrelation(o5(world, late, o5, null)));
            LockWaits.await(o5.reached, TIMEOUT, cancelling, "O5's gate after the port's cancel");
            assertUncommittedO5(o5, late);

            Future<Boolean> worker = pool.submit(() -> due.fire(t2(late.id())));
            List<Waiter> waiting = LockWaits.awaitBlockedBy(o5.pid(), worker, TIMEOUT);
            log.info("T2-D: O5 backend {} holds its transaction open; waiting behind it: {}", o5.pid(), waiting);
            assertThat(t2.fires()).as("the worker waits before its lease and its handler").isZero();

            o5.release();
            at = cancelling.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(worker.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("the worker settles nothing").isFalse();

            assertWorkerWaitedBehind(waiting, o5.pid());
            assertThat(o5.attempts()).as("one run of O5").isEqualTo(1);
        } finally {
            o5.release();
        }

        assertThat(t2.fires()).as("job_lease refused the superseded T2: the handler never ran").isZero();
        assertThat(at).as("C2 Option A: past the deadline, T2 not committed").isAfter(deadline);
        assertCancelledByTheHoursChange(world, late, at, deadline);
        assertTimer(t2(late.id()), "SUPERSEDED", 0);
        assertTimer(t3(late.id()), "SUPERSEDED", 0);
        assertNewEvents(late, outboxBefore, IntegrationEvents.BOOKING_CANCELLED + " " + late.id(),
                IntegrationEvents.RESERVATION_RELEASED + " " + late.holdId());
        assertThat(due.fire(t2(late.id()))).as("still nothing to lease").isFalse();
        assertThat(t2.fires()).isZero();
    }

    // ================================================================ T3 (Late Confirm warning) versus O5

    // ---------------------------------------------------------------- T3-A: O5 commits, then T3 fires

    @Test
    void t3A_o5CommitsFirstThenTheRealT3WorkerCannotWarn() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Late late = awaiting(world);
        WorkerProbe probe = new WorkerProbe(realT3(), null, null);
        GatedCommandJobHandler t3 = GatedCommandJobHandler.counting(probe);
        DueJobs due = DueJobs.of(context, t3);
        Instant deadline = makeT3Due(due, late);
        List<String> outboxBefore = outbox(late);

        Instant at = runner.run(o5(world, late, null, null));

        assertThat(at).as("inside the warning minute").isBefore(deadline);
        assertCancelledByTheHoursChange(world, late, at, deadline);
        assertTimer(t2(late.id()), "SUPERSEDED", 0);
        assertTimer(t3(late.id()), "SUPERSEDED", 0);
        assertThat(LateConfirmDb.warningSentAt(late.id())).as("never warned, none invented").isNull();
        assertNewEvents(late, outboxBefore, IntegrationEvents.BOOKING_CANCELLED + " " + late.id(),
                IntegrationEvents.RESERVATION_RELEASED + " " + late.holdId());
        Committed before = committed(late);

        assertThat(due.fire(t3(late.id()))).as("the worker settles nothing").isFalse();

        assertThat(t3.fires()).as("job_lease refused the superseded T3: the handler never ran").isZero();
        assertThat(committed(late)).as("every committed fact, the timers included").isEqualTo(before);
        assertThat(LateConfirmDb.warningSentAt(late.id())).isNull();
        assertThat(events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late.id())).isEmpty();
        assertThat(events(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, late.id())).isEmpty();
    }

    // ---------------------------------------------------------------- T3-B: T3 commits, then O5

    @Test
    void t3B_theRealT3WorkerWarnsFirstThenO5CancelsAndKeepsTheWarning() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Late late = awaiting(world);
        WorkerProbe probe = new WorkerProbe(realT3(), null, null);
        GatedCommandJobHandler t3 = GatedCommandJobHandler.counting(probe);
        DueJobs due = DueJobs.of(context, t3);
        Instant deadline = makeT3Due(due, late);
        List<String> outboxBefore = outbox(late);

        assertThat(due.fire(t3(late.id()))).as("the real worker fired T3").isTrue();

        assertThat(t3.fires()).isEqualTo(1);
        assertThat(probe.outcome()).isInstanceOf(JobOutcome.Done.class);
        Instant warned = LateConfirmDb.warningSentAt(late.id());
        assertThat(warned).as("the worker's own command instant").isEqualTo(probe.commandNow());
        assertTimer(t3(late.id()), "DONE", 1);
        assertThat(booking(late.id()).status()).isEqualTo(AWAITING);

        Instant at = runner.run(o5(world, late, null, null));

        assertWarnedThenCancelled(world, late, at, deadline, warned);
        assertNewEvents(late, outboxBefore, IntegrationEvents.LATE_CONFIRM_WARNING_DUE + " " + late.id(),
                IntegrationEvents.BOOKING_CANCELLED + " " + late.id(),
                IntegrationEvents.RESERVATION_RELEASED + " " + late.holdId());
        assertThat(due.fire(t3(late.id()))).as("a DONE job never fires again").isFalse();
        assertThat(t3.fires()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- T3-C: T3 in flight, O5 waits

    @Test
    void t3C_aT3FireInFlightMakesO5WaitThenT3CommitsAndO5CancelsKeepingTheWarning() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Late late = awaiting(world);
        WorkerProbe probe = new WorkerProbe(realT3(), null, null);
        GatedCommandJobHandler t3 = GatedCommandJobHandler.pausingAfterFire(probe, TIMEOUT);
        DueJobs due = DueJobs.of(context, t3);
        Instant deadline = makeT3Due(due, late);
        List<String> outboxBefore = outbox(late);
        O5Probe o5 = new O5Probe();
        Instant at;
        try {
            Future<Boolean> worker = pool.submit(() -> due.fire(t3(late.id())));
            t3.awaitReached(worker);
            int workerPid = t3.firePid();
            assertUncommittedTimerFire(probe, late, AWAITING);
            assertThat(probe.inTransaction().warningSentAt()).as("written inside the worker's transaction")
                    .isEqualTo(probe.commandNow());
            assertThat(LateConfirmDb.warningSentAt(late.id())).as("committed: not yet warned").isNull();
            assertThat(events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late.id())).as("committed").isEmpty();
            assertTimer(t3(late.id()), "PENDING", 0);

            Future<Instant> cancelling = pool.submit(() -> inOwnCorrelation(o5(world, late, o5, null)));
            List<Waiter> waiting = LockWaits.awaitBlockedBy(workerPid, cancelling, TIMEOUT);
            log.info("T3-C: T3 fire backend {} holds its transaction open; waiting behind it: {}", workerPid, waiting);
            assertThat(o5.attempts()).as("O5's body has not started: it waits in begin_command").isZero();

            t3.release();
            assertThat(worker.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("T3 settled").isTrue();
            at = cancelling.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            assertO5WaitedBehind(waiting, o5.pid(), workerPid);
            assertThat(o5.attempts()).as("one run of O5, after T3's commit").isEqualTo(1);
        } finally {
            t3.release();
        }

        assertThat(t3.fires()).isEqualTo(1);
        assertThat(probe.outcome()).isInstanceOf(JobOutcome.Done.class);
        assertThat(at).as("O5 ran after T3's fire").isAfter(probe.commandNow());
        assertWarnedThenCancelled(world, late, at, deadline, probe.commandNow());
        assertNewEvents(late, outboxBefore, IntegrationEvents.LATE_CONFIRM_WARNING_DUE + " " + late.id(),
                IntegrationEvents.BOOKING_CANCELLED + " " + late.id(),
                IntegrationEvents.RESERVATION_RELEASED + " " + late.holdId());
    }

    // ---------------------------------------------------------------- T3-D: O5 in flight, T3 waits

    @Test
    void t3D_anO5InFlightMakesTheT3WorkerWaitThenO5CommitsAndTheWorkerCannotWarn() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Late late = awaiting(world);
        WorkerProbe probe = new WorkerProbe(realT3(), null, null);
        GatedCommandJobHandler t3 = GatedCommandJobHandler.counting(probe);
        DueJobs due = DueJobs.of(context, t3);
        Instant deadline = makeT3Due(due, late);
        List<String> outboxBefore = outbox(late);
        O5Probe o5 = new O5Probe();
        o5.gate();
        Instant at;
        try {
            Future<Instant> cancelling = pool.submit(() -> inOwnCorrelation(o5(world, late, o5, null)));
            LockWaits.await(o5.reached, TIMEOUT, cancelling, "O5's gate after the port's cancel");
            assertUncommittedO5(o5, late);

            Future<Boolean> worker = pool.submit(() -> due.fire(t3(late.id())));
            List<Waiter> waiting = LockWaits.awaitBlockedBy(o5.pid(), worker, TIMEOUT);
            log.info("T3-D: O5 backend {} holds its transaction open; waiting behind it: {}", o5.pid(), waiting);
            assertThat(t3.fires()).as("the worker waits before its lease and its handler").isZero();

            o5.release();
            at = cancelling.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(worker.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("the worker settles nothing").isFalse();

            assertWorkerWaitedBehind(waiting, o5.pid());
            assertThat(o5.attempts()).as("one run of O5").isEqualTo(1);
        } finally {
            o5.release();
        }

        assertThat(t3.fires()).as("job_lease refused the superseded T3: the handler never ran").isZero();
        assertThat(at).isBefore(deadline);
        assertCancelledByTheHoursChange(world, late, at, deadline);
        assertTimer(t2(late.id()), "SUPERSEDED", 0);
        assertTimer(t3(late.id()), "SUPERSEDED", 0);
        assertThat(LateConfirmDb.warningSentAt(late.id())).as("no warning after O5 won").isNull();
        assertThat(events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late.id())).isEmpty();
        assertNewEvents(late, outboxBefore, IntegrationEvents.BOOKING_CANCELLED + " " + late.id(),
                IntegrationEvents.RESERVATION_RELEASED + " " + late.holdId());
    }

    // ================================================================ C2 boundary (PROVISIONAL Option A)

    /**
     * C2 boundary, O5 first: O5's own command stores the deadline on {@code side} of its instant (exactly at it, or one
     * second before it) and cancels; held open, the real T2 worker waits behind it, then cannot lease.
     */
    @ParameterizedTest
    @EnumSource(value = Boundary.class, names = {"AT", "ONE_SECOND_AFTER"})
    void c2Boundary_o5AtOrPastTheDeadlineCommitsFirstWhileT2Waits(Boundary side) throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Late late = awaiting(world);
        WorkerProbe probe = new WorkerProbe(realT2(), null, null);
        GatedCommandJobHandler t2 = GatedCommandJobHandler.counting(probe);
        DueJobs due = DueJobs.of(context, t2);
        makeT2DueForTheBoundary(due, late);
        List<String> outboxBefore = outbox(late);
        O5Probe o5 = new O5Probe();
        o5.gate();
        Instant at;
        try {
            Future<Instant> cancelling = pool.submit(() -> inOwnCorrelation(o5(world, late, o5, side)));
            LockWaits.await(o5.reached, TIMEOUT, cancelling, "O5's gate after the port's cancel");
            assertUncommittedO5(o5, late);

            Future<Boolean> worker = pool.submit(() -> due.fire(t2(late.id())));
            List<Waiter> waiting = LockWaits.awaitBlockedBy(o5.pid(), worker, TIMEOUT);
            log.info("C2 {} O5-first: O5 backend {} holds its transaction open; waiting behind it: {}", side,
                    o5.pid(), waiting);
            assertThat(t2.fires()).isZero();

            o5.release();
            at = cancelling.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(worker.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("the worker settles nothing").isFalse();
            assertWorkerWaitedBehind(waiting, o5.pid());
            assertThat(o5.attempts()).isEqualTo(1);
        } finally {
            o5.release();
        }

        Instant deadline = o5.pinnedDeadline();
        assertThat(at).as("O5's instant is exactly on the %s side of the stored deadline", side)
                .isEqualTo(side.now(deadline));
        assertThat(t2.fires()).isZero();
        assertCancelledByTheHoursChange(world, late, at, deadline);
        assertTimer(t2(late.id()), "SUPERSEDED", 0);
        assertTimer(t3(late.id()), "SUPERSEDED", 0);
        assertNewEvents(late, outboxBefore, IntegrationEvents.BOOKING_CANCELLED + " " + late.id(),
                IntegrationEvents.RESERVATION_RELEASED + " " + late.holdId());
    }

    /**
     * C2 boundary, T2 first: the real T2's own fire command stores the deadline on {@code side} of its instant (exactly
     * at it, or one second before it), then the real handler decides - FR-AVL-15, {@code now >= deadline}: timed out.
     * Held open, O5 waits behind it, then is refused.
     */
    @ParameterizedTest
    @EnumSource(value = Boundary.class, names = {"AT", "ONE_SECOND_AFTER"})
    void c2Boundary_t2AtOrPastTheDeadlineCommitsFirstWhileO5Waits(Boundary side) throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Late late = awaiting(world);
        WorkerProbe probe = new WorkerProbe(realT2(), late.attemptId(), side);
        GatedCommandJobHandler t2 = GatedCommandJobHandler.pausingAfterFire(probe, TIMEOUT);
        DueJobs due = DueJobs.of(context, t2);
        makeT2DueForTheBoundary(due, late);
        List<String> outboxBefore = outbox(late);
        O5Probe o5 = new O5Probe();
        try {
            Future<Boolean> worker = pool.submit(() -> due.fire(t2(late.id())));
            t2.awaitReached(worker);
            int workerPid = t2.firePid();
            assertUncommittedTimerFire(probe, late, "CANCELLED");
            assertThat(booking(late.id()).status()).as("committed: still AWAITING").isEqualTo(AWAITING);

            Future<Instant> cancelling = pool.submit(() -> inOwnCorrelation(o5(world, late, o5, null)));
            List<Waiter> waiting = LockWaits.awaitBlockedBy(workerPid, cancelling, TIMEOUT);
            log.info("C2 {} T2-first: T2 fire backend {} holds its transaction open; waiting behind it: {}", side,
                    workerPid, waiting);

            t2.release();
            assertThat(worker.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(failureOf(cancelling)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CANCELLED ONLINE")
                    .hasMessageContaining("is not cancelled by an hours change");
            assertO5WaitedBehind(waiting, o5.pid(), workerPid);
            assertThat(o5.attempts()).isEqualTo(1);
            assertThat(o5.cancelledRuns()).isZero();
        } finally {
            t2.release();
        }

        Instant deadline = probe.pinnedDeadline();
        assertThat(probe.commandNow()).as("T2's instant is exactly on the %s side of the stored deadline", side)
                .isEqualTo(side.now(deadline));
        assertThat(probe.outcome()).isInstanceOf(JobOutcome.Done.class);
        assertTimedOutByT2(late, deadline);
        assertNewEvents(late, outboxBefore, IntegrationEvents.LATE_CONFIRM_TIMED_OUT + " " + late.id(),
                IntegrationEvents.RESERVATION_RELEASED + " " + late.holdId());
    }

    // ================================================================ the outcomes

    /**
     * O5's outcome (FR-VEN-28, Phase 3C): CANCELLED / VENUE_OPENING_HOURS_CHANGED at its instant, one terminal history
     * row by the venue's account, the attempt closed then without a Customer response (its deadline as stored), the
     * hold released then, one FULL_REFUND on that row, no timeout - and the database's own integrity check holds.
     */
    private static void assertCancelledByTheHoursChange(OnlineBookingWorld world, Late late, Instant at,
                                                        Instant deadline) {
        UUID id = late.id();
        VenueAnswerDb.BookingRow row = booking(id);
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo(REASON);
        assertThat(row.terminalAt()).isEqualTo(at);
        assertThat(row.feeTreatment()).isEqualTo("FULL_REFUND");
        assertThat(row.counted()).as("the commitment slot is free").isFalse();
        List<HistoryRow> rows = history(id);
        assertThat(rows).filteredOn(h -> "CANCELLED".equals(h.toStatus())).as("exactly one terminal outcome")
                .singleElement().satisfies(last -> {
                    assertThat(last).isEqualTo(rows.getLast());
                    assertThat(last.fromStatus()).isEqualTo(AWAITING);
                    assertThat(last.terminalReason()).isEqualTo(REASON);
                    assertThat(last.occurredAt()).isEqualTo(at);
                    assertThat(last.actorType()).isEqualTo(ActorType.VENUE.name());
                    assertThat(last.actorId()).isEqualTo(world.account.id());
                });
        assertThat(rows).extracting(HistoryRow::terminalReason).doesNotContain(TIMEOUT_REASON);
        LateConfirmDb.Attempt closed = LateConfirmDb.attempt(id).orElseThrow();
        assertThat(closed.id()).isEqualTo(late.attemptId());
        assertThat(closed.closedAt()).isEqualTo(at);
        assertThat(closed.response()).as("no Customer response, no TIMED_OUT").isNull();
        assertThat(closed.respondedAt()).isNull();
        assertThat(closed.expiresAt()).as("the deadline stays as stored").isEqualTo(deadline);
        assertThat(LateConfirmDb.attempts(id)).isEqualTo(1);
        assertHoldReleased(late, at, REASON);
        assertOneRefund(late, REASON);
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, id)).hasSize(1);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, id)).isEmpty();
        assertLateConfirmIntegrityHolds(late);
    }

    /**
     * T2's outcome (FR-LCF-09/13): CANCELLED / CUSTOMER_RECONFIRM_TIMEOUT as of the stored deadline by SYSTEM, the
     * attempt TIMED_OUT then, the hold released then, one FULL_REFUND (FR-PTS-17) on that row, one LateConfirmTimedOut
     * and no BookingCancelled; T2 DONE after exactly one lease, T3 superseded by it.
     */
    private static void assertTimedOutByT2(Late late, Instant deadline) {
        UUID id = late.id();
        VenueAnswerDb.BookingRow row = booking(id);
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo(TIMEOUT_REASON);
        assertThat(row.terminalAt()).as("as of the deadline, not the fire").isEqualTo(deadline);
        assertThat(row.feeTreatment()).isEqualTo("FULL_REFUND");
        List<HistoryRow> rows = history(id);
        assertThat(rows).filteredOn(h -> "CANCELLED".equals(h.toStatus())).as("exactly one terminal outcome")
                .singleElement().satisfies(last -> {
                    assertThat(last).isEqualTo(rows.getLast());
                    assertThat(last.fromStatus()).isEqualTo(AWAITING);
                    assertThat(last.terminalReason()).isEqualTo(TIMEOUT_REASON);
                    assertThat(last.occurredAt()).isEqualTo(deadline);
                    assertThat(last.actorType()).isEqualTo(ActorType.SYSTEM.name());
                    assertThat(last.actorId()).isNull();
                });
        assertThat(rows).extracting(HistoryRow::terminalReason).doesNotContain(REASON);
        LateConfirmDb.Attempt closed = LateConfirmDb.attempt(id).orElseThrow();
        assertThat(closed.id()).isEqualTo(late.attemptId());
        assertThat(closed.response()).isEqualTo("TIMED_OUT");
        assertThat(closed.respondedAt()).isEqualTo(deadline);
        assertThat(closed.closedAt()).isEqualTo(deadline);
        assertThat(closed.expiresAt()).isEqualTo(deadline);
        assertThat(LateConfirmDb.attempts(id)).isEqualTo(1);
        assertHoldReleased(late, deadline, TIMEOUT_REASON);
        assertOneRefund(late, TIMEOUT_REASON);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, id)).hasSize(1);
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, id)).isEmpty();
        assertThat(events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, id)).isEmpty();
        assertTimer(t2(id), "DONE", 1);
        assertTimer(t3(id), "SUPERSEDED", 0);
        assertLateConfirmIntegrityHolds(late);
    }

    /** T3's warning, then O5's cancellation: the warning, its event and the DONE T3 kept; T2 superseded by O5. */
    private static void assertWarnedThenCancelled(OnlineBookingWorld world, Late late, Instant at, Instant deadline,
                                                  Instant warned) {
        assertCancelledByTheHoursChange(world, late, at, deadline);
        assertThat(LateConfirmDb.warningSentAt(late.id())).as("warning_sent_at as T3 recorded it").isEqualTo(warned);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late.id())).as("only T3's own").hasSize(1);
        assertTimer(t3(late.id()), "DONE", 1);
        assertTimer(t2(late.id()), "SUPERSEDED", 0);
    }

    private static void assertHoldReleased(Late late, Instant at, String reason) {
        assertThat(LateConfirmDb.reservations(late.id())).filteredOn(r -> r.type().equals("LATE_CONFIRM_HOLD"))
                .singleElement().satisfies(hold -> {
                    assertThat(hold.id()).isEqualTo(late.holdId());
                    assertThat(hold.active()).isFalse();
                    assertThat(hold.releasedAt()).isEqualTo(at);
                    assertThat(hold.releaseReason()).isEqualTo(reason);
                });
        assertThat(LateConfirmDb.reservations(late.id())).filteredOn(LateConfirmDb.Reservation::active)
                .as("nothing of the booking is still held").isEmpty();
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, late.holdId())).as("released once").hasSize(1);
    }

    /** One FULL_REFUND decision for the Customer, resting on the booking's one terminal history row. */
    private static void assertOneRefund(Late late, String reason) {
        UUID terminalRow = OWNER.sql("""
                        SELECT id FROM booking.booking_history WHERE booking_id = :id AND to_status = 'CANCELLED'""")
                .param("id", late.id()).query(UUID.class).single();
        assertThat(refunds(late.id())).singleElement().satisfies(refund -> {
            assertThat(refund.treatment()).isEqualTo("FULL_REFUND");
            assertThat(refund.terminalReason()).isEqualTo(reason);
            assertThat(refund.userId()).isEqualTo(late.customerId());
            assertThat(refund.historyId()).as("the terminal transition's own history row").isEqualTo(terminalRow);
        });
    }

    /** The timer as committed: its status and how many leases it took; a settled one holds no lease and no error. */
    private static void assertTimer(String key, String status, int leases) {
        JobState job = job(key);
        assertThat(job.status()).as("%s status", key).isEqualTo(status);
        assertThat(job.attempt()).as("%s leases", key).isEqualTo(leases);
        if (!status.equals("PENDING")) {
            assertThat(job.completedAt()).as("%s completed", key).isNotNull();
            assertThat(job.leasedBy()).as("%s holds no lease", key).isNull();
            assertThat(job.lastError()).as("%s error", key).isNull();
        }
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

    /**
     * Inside the gated worker's open transaction: its lease (LEASED, one attempt) and the handler's writes are there;
     * the committed rows, read on the owner's connection, still show neither.
     */
    private static void assertUncommittedTimerFire(WorkerProbe probe, Late late, String bookingStatusInside) {
        InTransaction inside = probe.inTransaction();
        assertThat(inside).as("the worker's in-transaction view was recorded").isNotNull();
        assertThat(inside.jobStatus()).as("inside the worker: its own lease").isEqualTo("LEASED");
        assertThat(inside.jobAttempt()).isEqualTo(1);
        assertThat(inside.bookingStatus()).as("inside the worker").isEqualTo(bookingStatusInside);
        assertThat(booking(late.id()).status()).as("committed").isEqualTo(AWAITING);
        assertThat(LateConfirmDb.attempt(late.id()).orElseThrow().closedAt()).as("committed: still open").isNull();
        assertThat(job(probe.key(late)).status()).as("committed: no lease yet").isEqualTo("PENDING");
        assertThat(job(probe.key(late)).attempt()).isZero();
    }

    /** Inside O5's open transaction the booking is cancelled and its timers withdrawn; committed, nothing changed. */
    private static void assertUncommittedO5(O5Probe o5, Late late) {
        assertThat(o5.inside()).as("inside O5").isEqualTo(new O5View("CANCELLED", "SUPERSEDED", "SUPERSEDED"));
        assertThat(booking(late.id()).status()).as("committed").isEqualTo(AWAITING);
        assertThat(job(t2(late.id())).status()).as("committed").isEqualTo("PENDING");
        assertThat(job(t3(late.id())).status()).as("committed").isEqualTo("PENDING");
        assertThat(LateConfirmDb.attempt(late.id()).orElseThrow().closedAt()).as("committed: still open").isNull();
    }

    /** O5's own backend waited on a lock, in {@code begin_command}, behind the worker's fire backend. */
    private static void assertO5WaitedBehind(List<Waiter> waiting, int o5Pid, int workerPid) {
        assertThat(waiting).as("O5's own backend waited on a lock behind the worker's fire").anySatisfy(waiter -> {
            assertThat(waiter.pid()).isEqualTo(o5Pid);
            assertThat(waiter.waitEventType()).isEqualTo("Lock");
            assertThat(waiter.blockedBy()).contains(workerPid);
            assertThat(waiter.query()).contains("tournament.begin_command");
        });
    }

    /** The worker's fire command waited on a lock, in {@code begin_command}, behind O5's backend. */
    private static void assertWorkerWaitedBehind(List<Waiter> waiting, int o5Pid) {
        assertThat(waiting).as("the worker's fire command waited on a lock behind O5's backend").anySatisfy(waiter -> {
            assertThat(waiter.pid()).isNotEqualTo(o5Pid);
            assertThat(waiter.waitEventType()).isEqualTo("Lock");
            assertThat(waiter.blockedBy()).contains(o5Pid);
            assertThat(waiter.query()).contains("tournament.begin_command");
        });
    }

    // ================================================================ setup

    /** A real AWAITING booking: made, expired by T1, then Late Confirmed by the venue (open attempt, active hold). */
    private Late awaiting(OnlineBookingWorld world) {
        UUID id = new LateConfirmDb(runner, creation, store, answers).expired(world, world.start(), 60);
        ok(lateConfirmations.initiate(world.account.id(), id));
        LateConfirmDb.Attempt open = LateConfirmDb.attempt(id).orElseThrow();
        assertThat(booking(id).status()).isEqualTo(AWAITING);
        assertThat(open.closedAt()).isNull();
        assertThat(LateConfirmDb.reservations(id)).filteredOn(r -> r.id().equals(open.holdId())).singleElement()
                .satisfies(hold -> {
                    assertThat(hold.type()).isEqualTo("LATE_CONFIRM_HOLD");
                    assertThat(hold.active()).isTrue();
                });
        assertTimer(t2(id), "PENDING", 0);
        assertTimer(t3(id), "PENDING", 0);
        return new Late(id, world.customer.id(), open.id(), open.holdId(), open.initiatedAt());
    }

    /**
     * T2 due, its deadline passed: FIXTURE {@link LateConfirmDb#deadlineAt} shortens the window to just after the
     * initiation (setup only, before any racer), then one committed command under the booking's locks moves the
     * deadline later to its own instant through the guard ({@code Deadlines.move}) and makes T2 due then
     * ({@code DueJobs.makeDue}). Any later command - a fire or O5 - is past it.
     */
    private Instant makeT2Due(DueJobs due, Late late) {
        LateConfirmDb.deadlineAt(late.id(), late.initiatedAt().plusMillis(1));
        Instant deadline = runner.run(TestCommands.under(scope(late), command -> {
            Deadlines.move(command, EXPIRES_AT, late.attemptId(), command.commandNow());
            due.makeDue(command, t2(late.id()), command.commandNow());
            return command.commandNow();
        }));
        assertDueAndStillAwaiting(late, t2(late.id()), deadline, deadline);
        return deadline;
    }

    /**
     * T3 due inside its warning minute: FIXTURE {@link LateConfirmDb#deadlineAt} as {@link #makeT2Due}, then one
     * committed command stores the deadline {@link #WARNING_WINDOW_LEFT} after its own instant (guarded), makes T3 due
     * at that instant and keeps T2 at the deadline, as Initiate would have scheduled them.
     */
    private Instant makeT3Due(DueJobs due, Late late) {
        LateConfirmDb.deadlineAt(late.id(), late.initiatedAt().plusMillis(1));
        Instant[] dueAt = new Instant[1];
        Instant deadline = runner.run(TestCommands.under(scope(late), command -> {
            Instant at = command.commandNow().plus(WARNING_WINDOW_LEFT);
            Deadlines.move(command, EXPIRES_AT, late.attemptId(), at);
            due.makeDue(command, t3(late.id()), command.commandNow());
            due.makeDue(command, t2(late.id()), at);
            dueAt[0] = command.commandNow();
            return at;
        }));
        assertDueAndStillAwaiting(late, t3(late.id()), dueAt[0], deadline);
        assertThat(job(t2(late.id())).dueAt()).isEqualTo(deadline);
        return deadline;
    }

    /**
     * T2 due for a C2 boundary race: FIXTURE {@link LateConfirmDb#deadlineAt} as {@link #makeT2Due}, then wait until a
     * deadline one second before "now" is still after the initiation (the guard moves it only later), and make T2 due
     * in a committed command. The racer that commits first stores the deadline at its own instant.
     */
    private void makeT2DueForTheBoundary(DueJobs due, Late late) {
        LateConfirmDb.deadlineAt(late.id(), late.initiatedAt().plusMillis(1));
        Deadlines.awaitDatabaseClockPast(late.initiatedAt().plusSeconds(2));
        Instant dueAt = runner.run(TestCommands.under(scope(late), command -> {
            due.makeDue(command, t2(late.id()), command.commandNow());
            return command.commandNow();
        }));
        assertDueAndStillAwaiting(late, t2(late.id()), dueAt, late.initiatedAt().plusMillis(1));
    }

    private static void assertDueAndStillAwaiting(Late late, String key, Instant dueAt, Instant deadline) {
        assertThat(job(key).status()).isEqualTo("PENDING");
        assertThat(job(key).dueAt()).isEqualTo(dueAt);
        assertThat(LateConfirmDb.attempt(late.id()).orElseThrow().expiresAt()).isEqualTo(deadline);
        assertThat(booking(late.id()).status()).isEqualTo(AWAITING);
        assertThat(OWNER.sql("SELECT start_at > clock_timestamp() + interval '30 minutes' FROM booking.booking"
                        + " WHERE id = :id").param("id", late.id()).query(Boolean.class).single())
                .as("still future-started").isTrue();
    }

    private LateConfirmExpiryHandler realT2() {
        return new LateConfirmExpiryHandler(lateConfirmations);
    }

    private LateConfirmWarningHandler realT3() {
        return new LateConfirmWarningHandler(lateConfirmations);
    }

    // ================================================================ commands

    /**
     * O5 as Phase 4 will call it: the port, by the venue's account, in one command under the booking's Customer, Venue
     * and Resource locks. With {@code side}, the same command first stores the deadline on that side of its own instant
     * (guarded). The probe, when given, records each run and may hold the transaction open after the port returned.
     */
    private Command<Instant> o5(OnlineBookingWorld world, Late late, O5Probe probe, Boundary side) {
        return TestCommands.under(scope(late), command -> {
            if (probe != null) {
                probe.started(command);
            }
            if (side != null) {
                Instant deadline = Deadlines.moveTo(command, side, EXPIRES_AT, late.attemptId());
                if (probe != null) {
                    probe.pinnedDeadline = deadline;
                }
            }
            port.cancel(command, world.venue.id(), world.account.id(), List.of(late.id()));
            if (probe != null) {
                probe.cancelled(command, late);
            }
            return command.commandNow();
        });
    }

    /** The locks O5 must hold - the same rows the Late Confirm timers lock ({@link LateConfirmations#lockSetOf}). */
    private CommandScope scope(Late late) {
        CommandScope scope = runner.run(new FindBooking(store, late.id())).orElseThrow().scope();
        assertThat(scope.lockSet()).as("O5 and the timers name the same rows")
                .isEqualTo(lateConfirmations.lockSetOf(late.id()));
        return scope;
    }

    private Instant inOwnCorrelation(Command<Instant> command) {
        try (CorrelationId.Scope ignored = CorrelationId.open(UUID.randomUUID())) {
            return runner.run(command);
        }
    }

    /** The racer's own exception, unwrapped; fails if it returned or did not end in time. */
    private static Throwable failureOf(Future<?> racer) throws InterruptedException, TimeoutException {
        try {
            Object value = racer.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            throw new AssertionError("expected a refusal, got " + value);
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private record Late(UUID id, UUID customerId, UUID attemptId, UUID holdId, Instant initiatedAt) {
    }

    /** What the worker's own transaction saw right after the real handler's fire - not yet committed. */
    private record InTransaction(String jobStatus, int jobAttempt, String bookingStatus, Instant warningSentAt) {
    }

    private record O5View(String bookingStatus, String t2Status, String t3Status) {
    }

    /**
     * TEST-ONLY observer around the REAL Late Confirm handler: type, category, lock set and the decision are the real
     * handler's. It records the fire's command instant and outcome and, after the real fire, reads the worker's own
     * uncommitted rows on its connection. With {@code pin}, it first stores the open attempt's deadline on that side of
     * the fire's own instant through the guarded {@code Deadlines.move} (C2 boundary tests only) - the deadline the
     * real handler then reads under the same locks.
     */
    private static final class WorkerProbe implements CommandJobHandler {

        private final CommandJobHandler delegate;
        private final UUID attemptId;
        private final Boundary pin;
        private volatile Instant commandNow;
        private volatile Instant pinnedDeadline;
        private volatile JobOutcome outcome;
        private volatile InTransaction inTransaction;

        WorkerProbe(CommandJobHandler delegate, UUID attemptId, Boundary pin) {
            this.delegate = delegate;
            this.attemptId = attemptId;
            this.pin = pin;
        }

        @Override
        public String type() {
            return delegate.type();
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return delegate.eligibilityCategory();
        }

        @Override
        public LockSet lockSet(ScheduledJob job) {
            return delegate.lockSet(job);
        }

        @Override
        public JobOutcome fire(CommandContext context, ScheduledJob job) {
            commandNow = context.commandNow();
            if (pin != null) {
                pinnedDeadline = Deadlines.moveTo(context, pin, EXPIRES_AT, attemptId);
            }
            JobOutcome result = delegate.fire(context, job);
            outcome = result;
            inTransaction = context.jdbc().sql("""
                            SELECT j.status AS job_status, j.attempt, b.status AS booking_status, lc.warning_sent_at
                              FROM technical.scheduled_job j
                              JOIN booking.booking b ON b.id = j.subject_id
                              JOIN booking.late_confirmation lc ON lc.booking_id = b.id
                             WHERE j.id = :job
                             ORDER BY lc.attempt_no DESC LIMIT 1""")
                    .param("job", job.id())
                    .query((rs, n) -> new InTransaction(rs.getString("job_status"), rs.getInt("attempt"),
                            rs.getString("booking_status"),
                            instant(rs.getObject("warning_sent_at", OffsetDateTime.class))))
                    .single();
            return result;
        }

        String key(Late late) {
            return type() + ":" + late.id();
        }

        Instant commandNow() {
            return commandNow;
        }

        Instant pinnedDeadline() {
            return pinnedDeadline;
        }

        JobOutcome outcome() {
            return outcome;
        }

        InTransaction inTransaction() {
            return inTransaction;
        }
    }

    /**
     * What O5's runs saw - how many ran (a {@code 40001} retry is a second run), the backend pid of the last, how many
     * got past the port, and its own uncommitted view - and, when gated, a bounded pause of the first run after the
     * port's cancel, inside the command's open transaction.
     */
    private static final class O5Probe {

        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger cancelledRuns = new AtomicInteger();
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean gated;
        private volatile int pid;
        private volatile O5View inside;
        private volatile Instant pinnedDeadline;

        void gate() {
            gated = true;
        }

        void started(CommandContext context) {
            attempts.incrementAndGet();
            pid = LockWaits.backendPid(context);
        }

        void cancelled(CommandContext context, Late late) {
            cancelledRuns.incrementAndGet();
            inside = context.jdbc().sql("""
                            SELECT b.status,
                                   (SELECT status FROM technical.scheduled_job WHERE idempotency_key = :t2) AS t2,
                                   (SELECT status FROM technical.scheduled_job WHERE idempotency_key = :t3) AS t3
                              FROM booking.booking b WHERE b.id = :id""")
                    .param("id", late.id()).param("t2", t2(late.id())).param("t3", t3(late.id()))
                    .query((rs, n) -> new O5View(rs.getString("status"), rs.getString("t2"), rs.getString("t3")))
                    .single();
            if (!gated || context.attempt() != 1) {
                return;
            }
            reached.countDown();
            try {
                if (!release.await(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
                    throw new IllegalStateException("O5's gate was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted at O5's gate", e);
            }
        }

        void release() {
            release.countDown();
        }

        int attempts() {
            return attempts.get();
        }

        int cancelledRuns() {
            return cancelledRuns.get();
        }

        int pid() {
            return pid;
        }

        O5View inside() {
            return inside;
        }

        Instant pinnedDeadline() {
            return pinnedDeadline;
        }
    }

    // ================================================================ committed reads (owner connection)

    record JobState(String status, int attempt, Instant dueAt, Instant completedAt, String leasedBy,
                    String lastError) {
    }

    record Refund(UUID id, String treatment, String terminalReason, UUID userId, UUID historyId) {
    }

    /** Every committed fact of the booking either side writes. */
    record Committed(VenueAnswerDb.BookingRow booking, List<HistoryRow> history, LateConfirmDb.Attempt attempt,
                     Instant warningSentAt, List<LateConfirmDb.Reservation> reservations, List<Refund> refunds,
                     JobState t2, JobState t3, List<String> events) {
    }

    private static Committed committed(Late late) {
        UUID id = late.id();
        return new Committed(booking(id), history(id), LateConfirmDb.attempt(id).orElseThrow(),
                LateConfirmDb.warningSentAt(id), LateConfirmDb.reservations(id), refunds(id), job(t2(id)),
                job(t3(id)), outbox(late));
    }

    private static JobState job(String key) {
        return OWNER.sql("""
                        SELECT status, attempt, due_at, completed_at, leased_by, last_error
                          FROM technical.scheduled_job WHERE idempotency_key = :key""")
                .param("key", key)
                .query((rs, n) -> new JobState(rs.getString("status"), rs.getInt("attempt"),
                        instant(rs.getObject("due_at", OffsetDateTime.class)),
                        instant(rs.getObject("completed_at", OffsetDateTime.class)), rs.getString("leased_by"),
                        rs.getString("last_error")))
                .single();
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

    /** The outbox events of the booking and of its reservations (the hold included), in commit order. */
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
