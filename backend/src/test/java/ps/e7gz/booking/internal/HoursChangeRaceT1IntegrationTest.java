package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.events;
import static ps.e7gz.booking.internal.VenueAnswerDb.history;
import static ps.e7gz.booking.internal.VenueAnswerDb.t1;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.booking.internal.LockWaits.Waiter;
import ps.e7gz.booking.internal.VenueAnswerDb.HistoryRow;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.support.boundary.TestCommands;
import ps.e7gz.venue.api.HoursChangeBookings;

/**
 * E7-113 Phase 3D-A: the REAL T1 ({@code VENUE_RESPONSE_EXPIRY}, {@link VenueResponseExpiryHandler}) fired by the real
 * deadline worker (E7-40, through {@link DueJobs}: {@code begin_command}, {@code job_lease}, the handler,
 * {@code job_finish}, COMMIT - one transaction) racing the opening-hours cancellation (O5, {@link HoursChangeBookings#cancel}
 * in a committed command under the venue's, the Resource's and the Customer's locks) on one PENDING Online Booking past
 * its response deadline. D2 (Phase 3B): such a booking is still PENDING until T1 commits, so whichever commits first
 * owns it; the other then changes nothing.
 *
 * <p>The order is fixed by the transactions' own locks: both name the booking's Customer, Venue and Resource, so
 * {@code begin_command} serialises them. Tests C and D hold one side's transaction open at a gate (inside it, after its
 * writes, before COMMIT) and prove from {@code pg_blocking_pids} that the other side waits behind exactly that backend.
 * No trigger is disabled and no job is put LEASED by hand: the deadline and the job's due time move through the
 * guarded paths ({@code Deadlines.move}, {@code DueJobs.makeDue}), and a lease exists only inside the worker's own
 * uncommitted fire. Every assertion reads committed state on the owner's connection, after the transactions ended.
 */
class HoursChangeRaceT1IntegrationTest extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HoursChangeRaceT1IntegrationTest.class);
    private static final String REASON = "VENUE_OPENING_HOURS_CHANGED";
    private static final String EXPIRED = BookingStatus.VENUE_RESPONSE_EXPIRED.name();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

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

    private CorrelationId.Scope correlation;
    private ExecutorService pool;

    @BeforeEach
    void open() {
        correlation = CorrelationId.open(UUID.randomUUID());
        AtomicInteger threads = new AtomicInteger();
        pool = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "t1-race-" + threads.incrementAndGet());
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

    // ---------------------------------------------------------------- A: O5 commits, then T1 fires

    @Test
    void o5CommitsFirstThenTheRealT1WorkerCannotLeaseItsSupersededJob() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking pending = pending(world);
        GatedCommandJobHandler t1 = GatedCommandJobHandler.counting(realT1());
        DueJobs due = DueJobs.of(context, t1);
        Instant deadline = makeT1Due(due, pending.bookingId());

        Instant at = runner.run(o5(world, pending.bookingId(), null));

        assertThat(at).as("D2: past the deadline, T1 not run").isAfter(deadline);
        assertCancelledByTheHoursChange(world, pending, at);
        JobState superseded = t1Job(pending.bookingId());
        assertThat(superseded.status()).isEqualTo("SUPERSEDED");
        assertThat(superseded.attempt()).as("never leased").isZero();
        assertThat(superseded.completedAt()).isNotNull();
        assertThat(superseded.leasedBy()).isNull();
        Committed before = committed(pending.bookingId());

        assertThat(due.fire(t1(pending.bookingId()))).as("the worker settles nothing").isFalse();

        assertThat(t1.fires()).as("job_lease refused it: the handler never ran").isZero();
        assertThat(committed(pending.bookingId())).as("every committed fact, the job's included").isEqualTo(before);
        assertThat(events(IntegrationEvents.VENUE_RESPONSE_EXPIRED, pending.bookingId())).isEmpty();
    }

    // ---------------------------------------------------------------- B: T1 commits, then O5

    @Test
    void theRealT1WorkerCommitsFirstAndO5IsRefusedWithNothingWritten() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking pending = pending(world);
        GatedCommandJobHandler t1 = GatedCommandJobHandler.counting(realT1());
        DueJobs due = DueJobs.of(context, t1);
        Instant deadline = makeT1Due(due, pending.bookingId());

        assertThat(due.fire(t1(pending.bookingId()))).as("the real worker fired T1").isTrue();

        assertThat(t1.fires()).isEqualTo(1);
        assertExpiredByT1(pending, deadline);
        Committed before = committed(pending.bookingId());

        assertThatThrownBy(() -> runner.run(o5(world, pending.bookingId(), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VENUE_RESPONSE_EXPIRED ONLINE booking")
                .hasMessageContaining("is not cancelled by an hours change");

        assertThat(committed(pending.bookingId())).as("every committed fact of T1's outcome").isEqualTo(before);
        assertExpiredByT1(pending, deadline);
        assertThat(due.fire(t1(pending.bookingId()))).as("a DONE job never fires again").isFalse();
        assertThat(t1.fires()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- C: T1 in flight, O5 waits

    @Test
    void aT1FireInFlightMakesO5WaitThenT1CommitsAndO5IsRefused() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking pending = pending(world);
        GatedCommandJobHandler t1 = GatedCommandJobHandler.pausingAfterFire(realT1(), TIMEOUT);
        DueJobs due = DueJobs.of(context, t1);
        Instant deadline = makeT1Due(due, pending.bookingId());
        O5Probe probe = new O5Probe();
        Command<Instant> o5 = o5(world, pending.bookingId(), probe);
        try {
            Future<Boolean> worker = pool.submit(() -> due.fire(t1(pending.bookingId())));
            t1.awaitReached(worker);
            int workerPid = t1.firePid();
            // the expiry and the lease are written but not committed: nobody else sees them yet
            assertThat(booking(pending.bookingId()).status()).isEqualTo("PENDING");
            assertThat(t1Job(pending.bookingId()).status()).as("committed state, not the open lease")
                    .isEqualTo("PENDING");
            assertThat(t1Job(pending.bookingId()).attempt()).isZero();

            Future<Instant> cancelling = pool.submit(() -> inOwnCorrelation(o5));
            List<Waiter> waiting = LockWaits.awaitBlockedBy(workerPid, cancelling, TIMEOUT);
            log.info("Test C: T1 fire backend {} holds its transaction open; waiting behind it: {}", workerPid,
                    waiting);
            assertThat(probe.attempts()).as("O5's body has not started: it waits in begin_command").isZero();

            t1.release();
            assertThat(worker.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("T1 settled").isTrue();
            Throwable refused = failureOf(cancelling);

            assertThat(refused).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("VENUE_RESPONSE_EXPIRED ONLINE booking")
                    .hasMessageContaining("is not cancelled by an hours change");
            assertThat(waiting).as("O5's own backend waited on a lock behind the worker's fire")
                    .anySatisfy(waiter -> {
                        assertThat(waiter.pid()).isEqualTo(probe.pid());
                        assertThat(waiter.waitEventType()).isEqualTo("Lock");
                        assertThat(waiter.blockedBy()).contains(workerPid);
                        assertThat(waiter.query()).contains("tournament.begin_command");
                    });
            assertThat(probe.attempts()).as("one run: T1 changes nothing begin_command re-checks").isEqualTo(1);
        } finally {
            t1.release();
        }

        assertThat(t1.fires()).isEqualTo(1);
        assertExpiredByT1(pending, deadline);
    }

    // ---------------------------------------------------------------- D: O5 in flight, T1 waits

    @Test
    void anO5InFlightMakesTheT1WorkerWaitThenO5CommitsAndTheWorkerCannotLease() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking pending = pending(world);
        GatedCommandJobHandler t1 = GatedCommandJobHandler.counting(realT1());
        DueJobs due = DueJobs.of(context, t1);
        Instant deadline = makeT1Due(due, pending.bookingId());
        O5Probe probe = new O5Probe();
        probe.gate();
        Command<Instant> o5 = o5(world, pending.bookingId(), probe);
        Instant at;
        try {
            Future<Instant> cancelling = pool.submit(() -> inOwnCorrelation(o5));
            LockWaits.await(probe.reached, TIMEOUT, cancelling, "O5's gate after the port's cancel");
            // the cancellation is written but not committed: nobody else sees it yet
            assertThat(booking(pending.bookingId()).status()).isEqualTo("PENDING");
            assertThat(t1Job(pending.bookingId()).status()).isEqualTo("PENDING");

            Future<Boolean> worker = pool.submit(() -> due.fire(t1(pending.bookingId())));
            List<Waiter> waiting = LockWaits.awaitBlockedBy(probe.pid(), worker, TIMEOUT);
            log.info("Test D: O5 backend {} holds its transaction open; waiting behind it: {}", probe.pid(),
                    waiting);
            assertThat(t1.fires()).as("the worker waits before its lease and its handler").isZero();

            probe.release();
            at = cancelling.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(worker.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)).as("the worker settles nothing").isFalse();

            assertThat(waiting).as("the worker's fire command waited on a lock behind O5's backend")
                    .anySatisfy(waiter -> {
                        assertThat(waiter.pid()).isNotEqualTo(probe.pid());
                        assertThat(waiter.waitEventType()).isEqualTo("Lock");
                        assertThat(waiter.blockedBy()).contains(probe.pid());
                        assertThat(waiter.query()).contains("tournament.begin_command");
                    });
            assertThat(probe.attempts()).as("one run of O5").isEqualTo(1);
        } finally {
            probe.release();
        }

        assertThat(t1.fires()).as("job_lease refused the superseded job: the handler never ran").isZero();
        assertThat(at).isAfter(deadline);
        assertCancelledByTheHoursChange(world, pending, at);
        JobState superseded = t1Job(pending.bookingId());
        assertThat(superseded.status()).isEqualTo("SUPERSEDED");
        assertThat(superseded.attempt()).as("never leased").isZero();
        assertThat(superseded.leasedBy()).isNull();
        assertThat(superseded.completedAt()).isNotNull();
    }

    // ---------------------------------------------------------------- the two outcomes

    /**
     * O5's outcome (D1): CANCELLED / VENUE_OPENING_HOURS_CHANGED at its instant, one terminal history row by the
     * venue's account, one FULL_REFUND on that row, the BOOKING reservation released once, one BookingCancelled and no
     * expiry.
     */
    private static void assertCancelledByTheHoursChange(OnlineBookingWorld world, CreatedBooking pending, Instant at) {
        UUID id = pending.bookingId();
        VenueAnswerDb.BookingRow row = booking(id);
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo(REASON);
        assertThat(row.terminalAt()).isEqualTo(at);
        assertThat(row.feeTreatment()).isEqualTo("FULL_REFUND");
        List<HistoryRow> rows = history(id);
        assertThat(rows).extracting(HistoryRow::toStatus).as("no expiry, one cancellation")
                .containsExactly("PENDING", "CANCELLED");
        HistoryRow last = rows.get(1);
        assertThat(last.fromStatus()).isEqualTo("PENDING");
        assertThat(last.terminalReason()).isEqualTo(REASON);
        assertThat(last.occurredAt()).isEqualTo(at);
        assertThat(last.actorType()).isEqualTo(ActorType.VENUE.name());
        assertThat(last.actorId()).isEqualTo(world.account.id());
        assertThat(refunds(id)).singleElement().satisfies(refund -> {
            assertThat(refund.treatment()).isEqualTo("FULL_REFUND");
            assertThat(refund.terminalReason()).isEqualTo(REASON);
            assertThat(refund.userId()).isEqualTo(pending.customerId());
            assertThat(refund.historyId()).as("the cancellation's own history row").isEqualTo(cancellationHistoryId(id));
        });
        assertThat(reservations(id)).singleElement().satisfies(reservation -> {
            assertThat(reservation.id()).isEqualTo(pending.reservationId());
            assertThat(reservation.type()).isEqualTo("BOOKING");
            assertThat(reservation.active()).isFalse();
            assertThat(reservation.releasedAt()).isEqualTo(at);
            assertThat(reservation.releaseReason()).isEqualTo(REASON);
        });
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, pending.reservationId())).hasSize(1);
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, id)).hasSize(1);
        assertThat(events(IntegrationEvents.VENUE_RESPONSE_EXPIRED, id)).isEmpty();
    }

    /**
     * T1's outcome: VENUE_RESPONSE_EXPIRED as of the stored deadline by SYSTEM, the reservation released at it, one
     * VenueResponseExpired, no refund and no cancellation; the job DONE after exactly one lease.
     */
    private static void assertExpiredByT1(CreatedBooking pending, Instant deadline) {
        UUID id = pending.bookingId();
        VenueAnswerDb.BookingRow row = booking(id);
        assertThat(row.status()).isEqualTo(EXPIRED);
        assertThat(row.terminalReason()).isNull();
        assertThat(row.feeTreatment()).isNotEqualTo("FULL_REFUND");
        List<HistoryRow> rows = history(id);
        assertThat(rows).extracting(HistoryRow::toStatus).containsExactly("PENDING", EXPIRED);
        HistoryRow last = rows.get(1);
        assertThat(last.occurredAt()).as("as of the deadline, not the fire").isEqualTo(deadline);
        assertThat(last.actorType()).isEqualTo(ActorType.SYSTEM.name());
        assertThat(last.actorId()).isNull();
        assertThat(rows).extracting(HistoryRow::terminalReason).doesNotContain(REASON);
        assertThat(refunds(id)).isEmpty();
        assertThat(reservations(id)).singleElement().satisfies(reservation -> {
            assertThat(reservation.id()).isEqualTo(pending.reservationId());
            assertThat(reservation.active()).isFalse();
            assertThat(reservation.releasedAt()).isEqualTo(deadline);
            assertThat(reservation.releaseReason()).isEqualTo(EXPIRED);
        });
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, pending.reservationId())).hasSize(1);
        assertThat(events(IntegrationEvents.VENUE_RESPONSE_EXPIRED, id)).hasSize(1);
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, id)).isEmpty();
        JobState done = t1Job(id);
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.attempt()).as("one lease").isEqualTo(1);
        assertThat(done.completedAt()).isNotNull();
        assertThat(done.leasedBy()).isNull();
        assertThat(done.lastError()).isNull();
    }

    // ---------------------------------------------------------------- commands

    private CreatedBooking pending(OnlineBookingWorld world) {
        CreatedBooking created = creation.create(world.request(world.customer.id(), world.room, world.start(), 60));
        assertThat(t1Job(created.bookingId()).status()).isEqualTo("PENDING");
        return created;
    }

    /** The production T1 handler, built by hand: its {@code worker} profile is off in tests (E7-45). */
    private VenueResponseExpiryHandler realT1() {
        return new VenueResponseExpiryHandler(runner, store, answers);
    }

    /**
     * One committed command under the booking's locks: the stored response deadline becomes the command instant
     * through the booking's own guards ({@code Deadlines.move}) and T1 is due at it ({@code DueJobs.makeDue}: PAUSED
     * and re-armed, the one path the job guard allows). The booking still starts about an hour later.
     */
    private Instant makeT1Due(DueJobs due, UUID bookingId) {
        Instant deadline = runner.run(TestCommands.under(scope(bookingId), command -> {
            Deadlines.move(command, "booking.booking.venue_response_expires_at", bookingId, command.commandNow());
            due.makeDue(command, t1(bookingId), command.commandNow());
            return command.commandNow();
        }));
        assertThat(booking(bookingId).expiresAt()).isEqualTo(deadline);
        assertThat(t1Job(bookingId).dueAt()).isEqualTo(deadline);
        assertThat(t1Job(bookingId).status()).isEqualTo("PENDING");
        assertThat(OWNER.sql("SELECT start_at > clock_timestamp() + interval '30 minutes' FROM booking.booking"
                        + " WHERE id = :id").param("id", bookingId).query(Boolean.class).single())
                .as("still future-started").isTrue();
        return deadline;
    }

    /**
     * O5 as Phase 4 will call it: the port, by the venue's account, in one command under the venue's, the Resource's
     * and the Customer's locks. The probe, when given, records each run and may hold the transaction open after the
     * port returned and before COMMIT (the same transaction, nothing else written).
     */
    private Command<Instant> o5(OnlineBookingWorld world, UUID bookingId, O5Probe probe) {
        CommandScope scope = scope(bookingId);
        return TestCommands.under(scope, command -> {
            if (probe != null) {
                probe.started(command);
            }
            port.cancel(command, world.venue.id(), world.account.id(), List.of(bookingId));
            if (probe != null) {
                probe.cancelled(command);
            }
            return command.commandNow();
        });
    }

    private CommandScope scope(UUID bookingId) {
        return runner.run(new FindBooking(store, bookingId)).orElseThrow().scope();
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

    /**
     * What O5's runs saw - how many ran (a {@code 40001} retry is a second run) and the backend pid of the last - and,
     * when gated, a bounded pause of the first run after the port's cancel, inside the command's open transaction.
     */
    private static final class O5Probe {

        private final AtomicInteger attempts = new AtomicInteger();
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean gated;
        private volatile int pid;

        void gate() {
            gated = true;
        }

        void started(CommandContext context) {
            attempts.incrementAndGet();
            pid = LockWaits.backendPid(context);
        }

        void cancelled(CommandContext context) {
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

        int pid() {
            return pid;
        }
    }

    // ---------------------------------------------------------------- committed reads (owner connection)

    record JobState(String status, int attempt, Instant dueAt, Instant completedAt, String leasedBy,
                    String lastError) {
    }

    record ReservationState(UUID id, String type, boolean active, Instant releasedAt, String releaseReason) {
    }

    record Refund(UUID id, String treatment, String terminalReason, UUID userId, UUID historyId) {
    }

    /** Every committed fact of the booking either side writes. */
    record Committed(VenueAnswerDb.BookingRow booking, List<HistoryRow> history,
                     List<ReservationState> reservations, List<Refund> refunds, JobState t1, List<String> events) {
    }

    private static Committed committed(UUID bookingId) {
        return new Committed(booking(bookingId), history(bookingId), reservations(bookingId), refunds(bookingId),
                t1Job(bookingId), outbox(bookingId));
    }

    private static JobState t1Job(UUID bookingId) {
        return OWNER.sql("""
                        SELECT status, attempt, due_at, completed_at, leased_by, last_error
                          FROM technical.scheduled_job WHERE idempotency_key = :key""")
                .param("key", t1(bookingId))
                .query((rs, n) -> new JobState(rs.getString("status"), rs.getInt("attempt"),
                        instant(rs.getObject("due_at", OffsetDateTime.class)),
                        instant(rs.getObject("completed_at", OffsetDateTime.class)), rs.getString("leased_by"),
                        rs.getString("last_error")))
                .single();
    }

    private static List<ReservationState> reservations(UUID bookingId) {
        return OWNER.sql("""
                        SELECT id, reservation_type, active_flag, actual_reservation_release_at, release_reason
                          FROM inventory.resource_reservation WHERE booking_id = :id ORDER BY id""")
                .param("id", bookingId)
                .query((rs, n) -> new ReservationState(rs.getObject("id", UUID.class),
                        rs.getString("reservation_type"), rs.getBoolean("active_flag"),
                        instant(rs.getObject("actual_reservation_release_at", OffsetDateTime.class)),
                        rs.getString("release_reason")))
                .list();
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

    private static UUID cancellationHistoryId(UUID bookingId) {
        return OWNER.sql("""
                        SELECT id FROM booking.booking_history
                         WHERE booking_id = :id AND to_status = 'CANCELLED'""")
                .param("id", bookingId).query(UUID.class).single();
    }

    /** The outbox events of the booking and of its reservations, in commit order: "type aggregateId". */
    private static List<String> outbox(UUID bookingId) {
        return OWNER.sql("""
                        SELECT e.event_type || ' ' || e.aggregate_id FROM technical.outbox_event e
                         WHERE e.aggregate_id = :id
                            OR e.aggregate_id IN (SELECT r.id FROM inventory.resource_reservation r
                                                   WHERE r.booking_id = :id)
                         ORDER BY e.id""")
                .param("id", bookingId).query(String.class).list();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
