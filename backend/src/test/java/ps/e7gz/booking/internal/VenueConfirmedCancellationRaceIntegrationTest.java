package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.booking.internal.BookingFlows.OWNER_PIN;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.bookingStart;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;
import static ps.e7gz.booking.internal.VenueAnswerDb.outboxTypes;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.booking.internal.LockWaits.Waiter;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.security.owner.OwnerProtected;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.venue.api.OwnerPasswordGate;

/**
 * E7-114 racing T4 (E7-60's BOOKING_START) and a second cancellation, against the real database and the real deadline
 * worker. Every party serialises on the booking's Customer, Venue and Resource locks, taken by {@code begin_command}
 * ({@link FindBooking.Keys#scope}, {@link BookingStartHandler#lockSet}): a command held open inside its transaction
 * makes the other wait there ({@link LockWaits}), and the waiter then re-reads the booking. Each race ends with exactly
 * one valid terminal state and every dependent write once; no new transaction mechanism, nothing in the database
 * relaxed. The venue's cancellation runs through the shared Owner Password gate, which checks the PIN first in the same
 * transaction (PO decisions Q2/Q3, 24 Oct 2026).
 */
class VenueConfirmedCancellationRaceIntegrationTest extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    ApplicationContext context;

    @Autowired
    CommandRunner runner;

    @Autowired
    VenueAnswerStore store;

    @Autowired
    VenueConfirmedCancellations cancellations;

    BookingFlows flows;
    CorrelationId.Scope correlation;
    ExecutorService pool;

    @BeforeEach
    void setUp() {
        flows = BookingFlows.of(context);
        correlation = CorrelationId.open(CorrelationId.generate());
        AtomicInteger threads = new AtomicInteger();
        pool = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "e7-114-race-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("race threads ended").isTrue();
        correlation.close();
    }

    /**
     * 1. The cancellation wins: held open after its writes, it makes a due T4 wait in {@code begin_command}. Once it
     * commits - T4 withdrawn in the same transaction - the worker gets the locks, finds the job no longer PENDING and
     * cannot lease it: the start never fires. CANCELLED once; no BookingStarted.
     */
    @Test
    void aCancellationInFlightMakesT4WaitThenT4NeverFires() throws Exception {
        Booking booking = dueStart();
        Holding cancel = holdingCancel(booking);
        GatedCommandJobHandler t4 = GatedCommandJobHandler.counting(realT4());
        DueJobs due = DueJobs.of(context, t4);
        try {
            Future<?> venue = pool.submit(() -> inOwnCorrelation(() -> throughTheGate(cancel, booking)));
            cancel.awaitReached(venue);

            Future<Boolean> worker = pool.submit(() -> due.fire(bookingStart(booking.bookingId())));
            List<Waiter> waiting = LockWaits.awaitBlockedBy(cancel.pid(), worker, TIMEOUT);
            assertThat(waiting).anySatisfy(waiter -> assertThat(waiter.blockedBy()).contains(cancel.pid()));

            cancel.release();
            venue.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(worker.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("not leased: withdrawn").isFalse();
        } finally {
            cancel.release();
        }

        assertThat(t4.fires()).as("the start never ran").isZero();
        assertCancelledOnce(booking);
        assertThat(DueJobs.job(bookingStart(booking.bookingId())).status()).isEqualTo("SUPERSEDED");
    }

    /**
     * 2. T4 wins: its fire, held open after starting the booking, makes the cancellation wait in {@code begin_command};
     * once T4 commits the cancellation checks the PIN, finds the booking ACTIVE and is refused (409
     * BOOKING_STATE_CHANGED) - its success audit row rolls back with it. ACTIVE once; no cancellation write.
     */
    @Test
    void aT4InFlightMakesTheCancellationWaitThenRefusesIt() throws Exception {
        Booking booking = dueStart();
        GatedCommandJobHandler t4 = GatedCommandJobHandler.pausingAfterFire(realT4(), TIMEOUT);
        DueJobs due = DueJobs.of(context, t4);
        try {
            Future<Boolean> worker = pool.submit(() -> due.fire(bookingStart(booking.bookingId())));
            t4.awaitReached(worker);
            assertThat(booking(booking.bookingId()).status()).as("not committed yet").isEqualTo("CONFIRMED");

            Future<?> venue = pool.submit(() -> inOwnCorrelation(
                    () -> cancellations.cancel(booking.venueAccountId(), booking.bookingId(), OWNER_PIN)));
            LockWaits.awaitBlockedBy(t4.firePid(), venue, TIMEOUT);

            t4.release();
            assertThat(worker.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(refusalOf(venue)).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED);
        } finally {
            t4.release();
        }

        assertThat(t4.fires()).isOne();
        assertThat(booking(booking.bookingId()).status()).isEqualTo("ACTIVE");
        assertThat(history(booking.bookingId())).noneMatch(row -> "CANCELLED".equals(row.toStatus()));
        assertThat(outboxTypes(booking.bookingId())).containsOnlyOnce(IntegrationEvents.BOOKING_STARTED)
                .doesNotContain(IntegrationEvents.BOOKING_CANCELLED);
        assertThat(DueJobs.job(bookingStart(booking.bookingId())).status()).isEqualTo("DONE");
        assertThat(successAudit(booking)).isZero();
        assertThat(refunds(booking)).isZero();
        assertThat(activeReservations(booking)).as("the started booking keeps its room").isOne();
    }

    /**
     * 3. T4 already LEASED by a worker when the cancellation commits: the cancellation does not take the job from its
     * lease holder (ScheduledJobs#supersede returns false and leaves it). When that lease is fired - here, once it has
     * expired, by the real worker, which may then take it - the fire re-reads the booking, finds it CANCELLED and
     * supersedes itself: nothing starts.
     */
    @Test
    void aT4LeasedWhenTheCancellationCommitsSupersedesItselfWhenItFires() {
        Booking booking = dueStart();
        flows.startLeased(booking);

        cancellations.cancel(booking.venueAccountId(), booking.bookingId(), OWNER_PIN);
        assertThat(DueJobs.job(bookingStart(booking.bookingId())).status()).as("left to its lease holder")
                .isEqualTo("LEASED");

        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE technical.scheduled_job
                           SET leased_at = now() - interval '2 minutes', leased_until = now() - interval '1 second'
                         WHERE idempotency_key = :key AND status = 'LEASED'""")
                .param("key", bookingStart(booking.bookingId())).update());
        GatedCommandJobHandler t4 = GatedCommandJobHandler.counting(realT4());
        assertThat(DueJobs.of(context, t4).fire(bookingStart(booking.bookingId()))).isTrue();

        assertThat(t4.fires()).as("the lease holder's fire ran").isOne();
        assertThat(DueJobs.job(bookingStart(booking.bookingId())).status()).isEqualTo("SUPERSEDED");
        assertCancelledOnce(booking);
    }

    /**
     * 4. Two cancellations race: the first, held open after its writes, makes the second wait in {@code begin_command};
     * the second then checks the PIN, finds the booking CANCELLED and is refused (409). One cancellation and one of
     * every write.
     */
    @Test
    void twoCancellationsRaceAndOnlyOneCancels() throws Exception {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(5));
        flows.ownerPin(booking.venueAccountId());
        Holding first = holdingCancel(booking);
        try {
            Future<?> winner = pool.submit(() -> inOwnCorrelation(() -> throughTheGate(first, booking)));
            first.awaitReached(winner);

            Future<?> second = pool.submit(() -> inOwnCorrelation(
                    () -> cancellations.cancel(booking.venueAccountId(), booking.bookingId(), OWNER_PIN)));
            LockWaits.awaitBlockedBy(first.pid(), second, TIMEOUT);

            first.release();
            winner.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(refusalOf(second)).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED);
        } finally {
            first.release();
        }

        assertCancelledOnce(booking);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** A CONFIRMED Online Booking past its start whose T4 is PENDING and due now; the venue account has its PIN. */
    private Booking dueStart() {
        Booking booking = flows.confirmedWithOverdueStart(Duration.ofMinutes(5));
        flows.makeStartDue(booking);
        flows.ownerPin(booking.venueAccountId());
        return booking;
    }

    private BookingStartHandler realT4() {
        return new BookingStartHandler(runner, store, context.getBean(BookingStarts.class));
    }

    /**
     * The real protected cancellation command, held open after its writes and before COMMIT. It runs through the Owner
     * Password gate with the right PIN ({@link #throughTheGate}): the gate checks it first, then the command reads that
     * check.
     */
    private Holding holdingCancel(Booking booking) {
        CommandScope scope = flows.scope(booking);
        return new Holding(scope, correlation -> new VenueConfirmedCancellations.ProtectedCancel(scope,
                booking.bookingId(), booking.venueId(), booking.venueAccountId(), correlation,
                context.getBean(VenueConfirmedCancellation.class), context.getBean(OwnerPasswordGate.class),
                context.getBean(AuditWriter.class)));
    }

    /** One CANCELLED / VENUE_CANCELLED_CONFIRMED_BOOKING, and every dependent write exactly once. */
    private static void assertCancelledOnce(Booking booking) {
        VenueAnswerDb.BookingRow row = booking(booking.bookingId());
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo("VENUE_CANCELLED_CONFIRMED_BOOKING");
        assertThat(row.feeTreatment()).isEqualTo("FULL_REFUND");
        assertThat(row.counted()).isFalse();
        assertThat(history(booking.bookingId()).stream().filter(h -> "CANCELLED".equals(h.toStatus()))).hasSize(1);
        assertThat(history(booking.bookingId())).noneMatch(h -> "ACTIVE".equals(h.toStatus()));
        assertThat(outboxTypes(booking.bookingId())).containsOnlyOnce(IntegrationEvents.BOOKING_CANCELLED)
                .doesNotContain(IntegrationEvents.BOOKING_STARTED);
        assertThat(refunds(booking)).isOne();
        assertThat(successAudit(booking)).isOne();
        assertThat(activeReservations(booking)).isZero();
    }

    private static long successAudit(Booking booking) {
        return VenueAnswerDb.count("""
                audit.audit_event WHERE subject_id = :id AND action = 'VENUE_CANCELLED_CONFIRMED_BOOKING'
                   AND owner_password_outcome = 'SUCCESS'""", booking.bookingId());
    }

    private static long refunds(Booking booking) {
        return VenueAnswerDb.count("loyalty.booking_fee_refund WHERE booking_id = :id", booking.bookingId());
    }

    private static long activeReservations(Booking booking) {
        return OWNER.sql("""
                        SELECT count(*) FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING' AND active_flag""")
                .param("id", booking.bookingId()).query(Long.class).single();
    }

    /** The held cancellation run as the API runs it: through the gate, with the right PIN, in this correlation. */
    private VenueBookingRow throughTheGate(Holding held, Booking booking) {
        return context.getBean(OwnerPasswordGate.class).run(held, booking.venueAccountId(), OWNER_PIN,
                CorrelationId.require());
    }

    private static <T> T inOwnCorrelation(Supplier<T> action) {
        try (CorrelationId.Scope ignored = CorrelationId.open(UUID.randomUUID())) {
            return action.get();
        }
    }

    /** The code of the racer's refusal; fails if it returned or did not end in time. */
    private static ApiErrorCode refusalOf(Future<?> racer) throws Exception {
        try {
            Object value = racer.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            throw new AssertionError("expected a refusal, got " + value);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(ApiException.class);
            return ((ApiException) e.getCause()).code();
        }
    }

    /**
     * TEST-ONLY: a command run as given, then held open - inside its transaction, holding every lock of its
     * {@code begin_command} - until {@link #release}, as {@link GatedCommandJobHandler} holds a job's fire. Bounded:
     * on timeout it throws and the command rolls back. Built in the running thread's correlation.
     */
    @OwnerProtected(VenueConfirmedCancellations.PROTECTED_ACTION)
    private static final class Holding implements Command<VenueBookingRow> {

        private final CommandScope scope;
        private final Function<UUID, Command<VenueBookingRow>> command;
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile int pid;

        Holding(CommandScope scope, Function<UUID, Command<VenueBookingRow>> command) {
            this.scope = Objects.requireNonNull(scope, "scope");
            this.command = Objects.requireNonNull(command, "command");
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return scope;
        }

        @Override
        public VenueBookingRow execute(CommandContext context) {
            VenueBookingRow row = command.apply(CorrelationId.require()).execute(context);
            pid = LockWaits.backendPid(context);
            reached.countDown();
            try {
                if (!release.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the held cancellation was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted inside the held cancellation", e);
            }
            return row;
        }

        int pid() {
            return pid;
        }

        void awaitReached(Future<?> task) {
            LockWaits.await(reached, TIMEOUT, task, "the held cancellation");
        }

        void release() {
            release.countDown();
        }
    }
}
