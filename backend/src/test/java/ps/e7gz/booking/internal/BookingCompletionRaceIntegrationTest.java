package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;
import static ps.e7gz.booking.internal.VenueAnswerDb.outboxTypes;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.attendance.internal.AttendanceFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.booking.internal.LockWaits.Waiter;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.concurrency.ConcurrentRace;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.DueJobs;

/**
 * E7-61, T5 racing the commands that share its locks, against the real database and the real deadline worker. Every
 * party serialises on the booking's Customer, Venue and Resource locks taken by {@code begin_command}
 * ({@link BookingCompleteHandler#lockSet}): a T5 fire held open by {@link GatedCommandJobHandler} makes the other
 * command wait in {@code begin_command} ({@link LockWaits}), and once T5 commits that command re-reads the booking and
 * is refused. Each race ends with exactly one terminal transition and every outcome, reward and event written once;
 * no new transaction mechanism, nothing in the database relaxed. The rollbacks of a failing completion are
 * {@link BookingCompletionIntegrationTest}'s and the tenth attended completion's
 * {@link BookingCompletionVerifiedPlayerPinTest}'s.
 */
class BookingCompletionRaceIntegrationTest extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    ApplicationContext context;

    @Autowired
    CommandRunner runner;

    @Autowired
    VenueAnswerStore store;

    @Autowired
    BookingCompletions completions;

    @Autowired
    ManualBookings manualBookings;

    BookingFlows flows;
    AttendanceFlows attendance;
    CorrelationId.Scope correlation;
    ExecutorService pool;

    @BeforeEach
    void setUp() {
        flows = BookingFlows.of(context);
        attendance = AttendanceFlows.of(context);
        correlation = CorrelationId.open(CorrelationId.generate());
        AtomicInteger threads = new AtomicInteger();
        pool = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "t5-race-" + threads.incrementAndGet());
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
     * T5 versus Mark Present - Late: the T5 fire holds the booking's locks; the Late Present waits behind it in
     * {@code begin_command}, then finds the booking COMPLETED and is refused (409 BOOKING_STATE_CHANGED). The booking
     * completes once, ATTENDANCE_UNCONFIRMED from no evidence, and no Late Present is recorded.
     */
    @Test
    void aT5InFlightMakesALatePresentWaitThenRefusesIt() throws Exception {
        Booking booking = flows.pastItsEnd(flows.active(Duration.ofMinutes(20)), Duration.ofSeconds(30));
        assertThat(flows.makeCompletionDue(booking)).isTrue();
        GatedCommandJobHandler t5 = GatedCommandJobHandler.pausingAfterFire(realT5(), TIMEOUT);
        DueJobs due = DueJobs.of(context, t5);
        try {
            Future<Boolean> worker = pool.submit(() -> due.fire(complete(booking)));
            t5.awaitReached(worker);
            assertThat(booking(booking.bookingId()).status()).as("not committed yet").isEqualTo("ACTIVE");

            Future<?> late = pool.submit(() -> inOwnCorrelation(
                    () -> attendance.latePresent(booking.venueAccountId(), booking.bookingId())));
            List<Waiter> waiting = LockWaits.awaitBlockedBy(t5.firePid(), late, TIMEOUT);
            assertThat(waiting).anySatisfy(waiter -> assertThat(waiter.blockedBy()).contains(t5.firePid()));

            t5.release();
            assertThat(worker.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(refusalOf(late)).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED);
        } finally {
            t5.release();
        }

        assertThat(t5.fires()).isOne();
        assertCompletedOnce(booking, "ATTENDANCE_UNCONFIRMED");
        assertThat(OWNER.sql("SELECT late_present_at IS NULL FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(Boolean.class).single()).as("no Late Present").isTrue();
        assertThat(outboxTypes(booking.bookingId())).doesNotContain(IntegrationEvents.LATE_PRESENT_RECORDED);
        assertThat(trustOutcomes(booking)).containsExactly("BOOKING_ATTENDANCE_UNCONFIRMED");
        assertThat(entitlements(booking)).containsExactly(0);
    }

    /**
     * T5 versus the PCR-007 cancel of an ACTIVE Manual Booking past its end (PO decision D12): with T5 in flight the
     * cancel waits, then finds the booking COMPLETED and is refused (BOOKING_STATE_CHANGED). One terminal transition,
     * COMPLETED / NOT_APPLICABLE, no cancellation event.
     */
    @Test
    void aT5InFlightMakesAManualCancelWaitThenRefusesIt() throws Exception {
        Booking booking = flows.pastItsEnd(flows.walkIn(), Duration.ofSeconds(30));
        assertThat(flows.makeCompletionDue(booking)).isTrue();
        GatedCommandJobHandler t5 = GatedCommandJobHandler.pausingAfterFire(realT5(), TIMEOUT);
        DueJobs due = DueJobs.of(context, t5);
        try {
            Future<Boolean> worker = pool.submit(() -> due.fire(complete(booking)));
            t5.awaitReached(worker);

            Future<?> cancel = pool.submit(() -> inOwnCorrelation(
                    () -> manualBookings.cancel(booking.venueAccountId(), booking.bookingId())));
            LockWaits.awaitBlockedBy(t5.firePid(), cancel, TIMEOUT);

            t5.release();
            assertThat(worker.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(refusalOf(cancel)).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED);
        } finally {
            t5.release();
        }

        assertCompletedOnce(booking, "NOT_APPLICABLE");
        assertThat(history(booking.bookingId())).noneMatch(row -> "CANCELLED".equals(row.toStatus()));
        assertThat(outboxTypes(booking.bookingId())).doesNotContain(IntegrationEvents.BOOKING_CANCELLED);
        assertThat(trustOutcomes(booking)).isEmpty();
    }

    /**
     * The other order (PCR-007, PO decision D12): the cancel commits first, after endAt, and withdraws the walk-in's
     * waiting T5 in its own transaction; T5 never fires and nothing completes.
     */
    @Test
    void aManualCancelBeforeT5WithdrawsItAndNothingCompletes() {
        Booking booking = flows.pastItsEnd(flows.walkIn(), Duration.ofSeconds(30));
        GatedCommandJobHandler t5 = GatedCommandJobHandler.counting(realT5());

        manualBookings.cancel(booking.venueAccountId(), booking.bookingId());

        assertThat(DueJobs.job(complete(booking)).status()).isEqualTo("SUPERSEDED");
        assertThat(DueJobs.of(context, t5).fire(complete(booking))).as("withdrawn: never leased").isFalse();
        assertThat(t5.fires()).isZero();
        VenueAnswerDb.BookingRow row = booking(booking.bookingId());
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo("VENUE_CANCELLED_MANUAL_BOOKING");
        assertThat(outboxTypes(booking.bookingId())).doesNotContain(IntegrationEvents.BOOKING_COMPLETED,
                IntegrationEvents.ATTENDANCE_RESOLVED);
    }

    /**
     * Two workers delivering the same due T5 at once: the job's lease lets one fire; the other finds it leased or DONE
     * and settles nothing. One completion, one Trust outcome, one reward of 200, each event once.
     */
    @Test
    void concurrentDeliveriesOfOneT5CompleteTheBookingOnce() {
        Booking started = flows.active(Duration.ofMinutes(5));
        attendance.checkIn(started.venueAccountId(), started.bookingId());
        Booking booking = flows.pastItsEnd(started, Duration.ofSeconds(30));
        assertThat(flows.makeCompletionDue(booking)).isTrue();
        GatedCommandJobHandler first = GatedCommandJobHandler.counting(realT5());
        GatedCommandJobHandler second = GatedCommandJobHandler.counting(realT5());
        List<DueJobs> workers = List.of(DueJobs.of(context, first), DueJobs.of(context, second));

        ConcurrentRace.Result<Boolean> race = ConcurrentRace.run(2, TIMEOUT, (racer, start) -> {
            start.await();
            return workers.get(racer).fire(complete(booking));
        });

        assertThat(race.failures()).isEmpty();
        assertThat(race.values()).containsOnlyOnce(true);
        assertThat(first.fires() + second.fires()).as("one fire of the handler").isOne();
        assertCompletedOnce(booking, "ATTENDED");
        assertThat(trustOutcomes(booking)).containsExactly("BOOKING_ATTENDED");
        assertThat(entitlements(booking)).containsExactly(200);
        assertThat(OWNER.sql("SELECT count(*) FROM loyalty.points_transaction WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(Long.class).single()).as("one reward").isOne();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private BookingCompleteHandler realT5() {
        return new BookingCompleteHandler(runner, store, completions);
    }

    /** One terminal ACTIVE -> COMPLETED, the reservation released, the result stored, each event once, T5 DONE. */
    private static void assertCompletedOnce(Booking booking, String result) {
        assertThat(booking(booking.bookingId()).status()).isEqualTo("COMPLETED");
        assertThat(history(booking.bookingId()).stream()
                .filter(row -> row.fromStatus() != null && "ACTIVE".equals(row.fromStatus()))).hasSize(1);
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM inventory.resource_reservation
                         WHERE booking_id = :id AND reservation_type = 'BOOKING' AND active_flag""")
                .param("id", booking.bookingId()).query(Long.class).single()).as("no stale reservation").isZero();
        assertThat(OWNER.sql("SELECT result FROM attendance.attendance_record WHERE booking_id = :id")
                .param("id", booking.bookingId()).query(String.class).list()).containsExactly(result);
        assertThat(outboxTypes(booking.bookingId())).containsOnlyOnce(IntegrationEvents.BOOKING_COMPLETED,
                IntegrationEvents.ATTENDANCE_RESOLVED);
        assertThat(DueJobs.job(complete(booking)).status()).isEqualTo("DONE");
    }

    private static String complete(Booking booking) {
        return BookingFlows.bookingCompleteKey(booking.bookingId());
    }

    private static List<String> trustOutcomes(Booking booking) {
        return OWNER.sql("SELECT event_type FROM trust.trust_event WHERE booking_id = :id ORDER BY event_sequence")
                .param("id", booking.bookingId()).query(String.class).list();
    }

    private static List<Integer> entitlements(Booking booking) {
        return OWNER.sql("""
                        SELECT entitlement_after FROM loyalty.reward_application WHERE booking_id = :id
                         ORDER BY application_sequence""")
                .param("id", booking.bookingId()).query(Integer.class).list();
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
}
