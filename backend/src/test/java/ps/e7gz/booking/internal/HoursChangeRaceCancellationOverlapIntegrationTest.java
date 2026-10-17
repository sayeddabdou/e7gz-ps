package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.bookingStart;
import static ps.e7gz.booking.internal.VenueAnswerDb.t1;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.booking.internal.LockWaits.Waiter;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.messaging.api.CommandAnswer;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.venue.api.HoursChangeBookings;

/**
 * E7-113 Phase 3D-C2-B: the Customer's own cancellation ({@link CustomerCancellation}, through the package's
 * {@link CustomerBookings#cancel(UUID, UUID)} - the customer route's command without the Idempotency-Key record) and
 * concurrent opening-hours cancellations (O5, {@link HoursChangeBookings#cancel} in a committed command) on genuine
 * future PENDING and CONFIRMED Online Bookings. AWAITING bookings are Phase 3D-C1's.
 *
 * <p>The two cancellations are not financially alike: the Customer's ends CANCELLED / CUSTOMER_CANCELLED with actor
 * CUSTOMER and the fee treatment {@code CUSTOMER_POLICY} ({@code booking.expected_fee_treatment}, FR-PTS-16), its fee
 * decision recorded before its jobs are withdrawn; O5 ends CANCELLED / VENUE_OPENING_HOURS_CHANGED with actor VENUE and
 * the Venue Account and {@code FULL_REFUND}, its fee decisions recorded last, in one Loyalty batch.
 *
 * <ul>
 *   <li>A1 / A2: one commits, then the other is refused against the committed terminal booking - O5 with its
 *       caller-defect {@link IllegalStateException} (D3), the Customer with {@code BOOKING_STATE_CHANGED}.</li>
 *   <li>A3: the Customer's command is held after its booking, reservation, history and fee writes (it waits on its own
 *       T1 / T4 job row); O5 waits in {@code begin_command} on the Customer row behind it, then is refused.</li>
 *   <li>A4: O5 is held after its booking, reservation and history writes - before its fee decision, which comes last
 *       in O5 - on the T1 / T4 row; the Customer's command waits in {@code begin_command} behind it, then is
 *       refused.</li>
 *   <li>B1: two O5 commands on one booking - the second waits on the Customer row, then is refused.</li>
 *   <li>B2: O5 on two bookings of one venue, distinct Customers and rooms - the second waits on the shared venue row
 *       and both commit.</li>
 *   <li>B3: O5 in two venues on two bookings of one Customer - {@code begin_command} derives every booking of a named
 *       Customer, so the first O5 also locks the other venue and room; the second waits on the shared Customer row and
 *       both commit, serialized, without a deadlock.</li>
 * </ul>
 *
 * <p>Order is set by real locks only. A held command is stopped by an ordinary row lock: a separate owner connection
 * takes {@code SELECT ... FOR UPDATE} on the booking's T1 (PENDING) or T4 (CONFIRMED) job row - a row both commands
 * update with a plain guarded {@code UPDATE} ({@code supersede}) and that {@code begin_command} never locks - so the
 * held command waits there inside its open transaction, holding every lock of its scope. The other command is then
 * proved, from {@code pg_blocking_pids} and {@code pg_locks}, to wait in {@code begin_command} behind that backend on
 * the expected row. Rolling the row lock back lets the held command commit. No trigger is off, no status is written
 * by hand, nothing is injected; outcomes are read back from committed rows.
 */
class HoursChangeRaceCancellationOverlapIntegrationTest extends IntegrationTest {

    private static final org.slf4j.Logger log =
            LoggerFactory.getLogger(HoursChangeRaceCancellationOverlapIntegrationTest.class);
    private static final String O5_REASON = "VENUE_OPENING_HOURS_CHANGED";
    private static final String CUSTOMER_REASON = "CUSTOMER_CANCELLED";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    CommandRunner runner;

    @Autowired
    HoursChangeBookings port;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    VenueResponses responses;

    @Autowired
    VenueAnswerStore store;

    @Autowired
    CustomerBookings customerBookings;

    private CorrelationId.Scope correlation;
    private ExecutorService pool;
    private RetryLog retries;

    @BeforeEach
    void open() {
        correlation = CorrelationId.open(UUID.randomUUID());
        AtomicInteger threads = new AtomicInteger();
        pool = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "overlap-race-" + threads.incrementAndGet());
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

    // ================================================================ Group A: CustomerCancellation versus O5

    // ---------------------------------------------------------------- A1: the Customer commits first

    @Test
    void a1_pending_customerCancellationCommitsFirstThenO5IsRefusedWithNothingWritten() {
        a1(false);
    }

    @Test
    void a1_confirmed_customerCancellationCommitsFirstThenO5IsRefusedWithNothingWritten() {
        a1(true);
    }

    private void a1(boolean confirmed) {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Booked b = book(world, world.customer.id(), world.room, world.start(), confirmed);
        Snapshot before = snapshot(b);
        UUID customerCorrelation = UUID.randomUUID();

        CustomerBookingRow row = inCorrelation(customerCorrelation,
                () -> customerBookings.cancel(b.customerId(), b.bookingId()));

        assertThat(row.status().name()).isEqualTo("CANCELLED");
        Snapshot cancelled = assertCustomerCancelled(b, before, customerCorrelation);
        O5 o5 = o5(world, b, null);
        UUID o5Correlation = UUID.randomUUID();

        assertO5RefusedAsTerminal(() -> inCorrelation(o5Correlation, () -> runner.run(o5)));

        assertThat(o5.runs()).as("O5 ran once and was refused").containsExactly(1);
        assertThat(o5.cancelledRuns()).as("the port never returned").isZero();
        assertThat(o5.seen()).as("O5 read the committed cancellation").containsExactly("CANCELLED");
        assertThat(writesOf(o5Correlation)).as("nothing of O5 committed").isZero();
        assertThat(snapshot(b)).as("every committed fact of the Customer's cancellation").isEqualTo(cancelled);
        assertThat(retries.serializationFailures()).isEmpty();
        assertIntegrity(b);
    }

    // ---------------------------------------------------------------- A2: O5 commits first

    @Test
    void a2_pending_o5CommitsFirstThenTheCustomerCancellationIsRefusedWithNothingWritten() {
        a2(false);
    }

    @Test
    void a2_confirmed_o5CommitsFirstThenTheCustomerCancellationIsRefusedWithNothingWritten() {
        a2(true);
    }

    private void a2(boolean confirmed) {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Booked b = book(world, world.customer.id(), world.room, world.start(), confirmed);
        Snapshot before = snapshot(b);
        O5 o5 = o5(world, b, null);
        UUID o5Correlation = UUID.randomUUID();

        Instant at = inCorrelation(o5Correlation, () -> runner.run(o5));

        assertThat(o5.runs()).containsExactly(1);
        Snapshot cancelled = assertO5Cancelled(world, b, before, at, o5Correlation);
        UUID customerCorrelation = UUID.randomUUID();

        assertRefusedStateChanged(() -> inCorrelation(customerCorrelation,
                () -> customerBookings.cancel(b.customerId(), b.bookingId())));

        assertThat(writesOf(customerCorrelation)).as("nothing of the Customer's command committed").isZero();
        assertThat(snapshot(b)).as("every committed fact of O5's cancellation").isEqualTo(cancelled);
        assertThat(retries.serializationFailures()).isEmpty();
        assertIntegrity(b);
    }

    // ---------------------------------------------------------------- A3: the Customer in flight, O5 waits

    @Test
    void a3_pending_customerCancellationInFlightMakesO5WaitThenO5IsRefused() throws Exception {
        a3(false);
    }

    @Test
    void a3_confirmed_customerCancellationInFlightMakesO5WaitThenO5IsRefused() throws Exception {
        a3(true);
    }

    private void a3(boolean confirmed) throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Booked b = book(world, world.customer.id(), world.room, world.start(), confirmed);
        Snapshot before = snapshot(b);
        O5 o5 = o5(world, b, null);
        UUID customerCorrelation = UUID.randomUUID();
        UUID o5Correlation = UUID.randomUUID();
        int customerPid;
        try (RowLock gate = RowLock.onJob(gateKey(b))) {
            Future<CustomerBookingRow> cancelling = pool.submit(() -> inCorrelation(customerCorrelation,
                    () -> customerBookings.cancel(b.customerId(), b.bookingId())));
            Waiter customer = single(waitersBehind(gate.pid(), cancelling, TIMEOUT));
            customerPid = customer.pid();
            assertHeldAtItsJobUpdate(customer, gate.pid());
            List<String> written = relationsWritten(customerPid);
            assertThat(written).as("the Customer's uncommitted booking and fee writes")
                    .contains("booking.booking", "booking.booking_history", "inventory.resource_reservation",
                            "loyalty.booking_fee_refund");
            assertThat(snapshot(b)).as("committed: nothing of the Customer's command yet").isEqualTo(before);

            Future<Instant> o5ing = pool.submit(() -> inCorrelation(o5Correlation, () -> runner.run(o5)));
            Waiter waiting = single(waitersBehind(customerPid, o5ing, TIMEOUT));
            log.info("A3 {}: row lock backend {} holds {}; Customer backend {} waits behind it ({}), writes {}; "
                    + "O5 backend {} waits behind the Customer: {}", label(confirmed), gate.pid(), gateKey(b),
                    customerPid, customer, written, waiting.pid(), waiting);
            assertWaitsInBeginCommand(waiting, customerPid, "iam.user_account");
            assertThat(o5.runs()).as("O5's body has not started: it waits in begin_command").isEmpty();

            gate.release();
            assertThat(cancelling.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS).status().name()).isEqualTo("CANCELLED");
            assertO5RefusedAsTerminal(() -> failureOf(o5ing));
            assertThat(o5.pid()).as("O5's body ran on the backend that waited").isEqualTo(waiting.pid());
        }

        log.info("A3 {}: O5 runs (attempt) {} read {}; runner 40001 lines {}", label(confirmed), o5.runs(), o5.seen(),
                retries.serializationFailures());
        assertThat(o5.runs()).as("one O5 run, attempt 1, after the Customer committed").containsExactly(1);
        assertThat(o5.cancelledRuns()).isZero();
        assertThat(o5.seen()).containsExactly("CANCELLED");
        assertThat(retries.serializationFailures()).as("no 40001 for either command").isEmpty();
        assertThat(writesOf(o5Correlation)).as("nothing of O5 committed").isZero();
        assertCustomerCancelled(b, before, customerCorrelation);
        assertIntegrity(b);
    }

    // ---------------------------------------------------------------- A4: O5 in flight, the Customer waits

    @Test
    void a4_pending_o5InFlightMakesTheCustomerCancellationWaitThenItIsRefused() throws Exception {
        a4(false);
    }

    @Test
    void a4_confirmed_o5InFlightMakesTheCustomerCancellationWaitThenItIsRefused() throws Exception {
        a4(true);
    }

    private void a4(boolean confirmed) throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Booked b = book(world, world.customer.id(), world.room, world.start(), confirmed);
        Snapshot before = snapshot(b);
        O5 o5 = o5(world, b, null);
        UUID customerCorrelation = UUID.randomUUID();
        UUID o5Correlation = UUID.randomUUID();
        Instant at;
        try (RowLock gate = RowLock.onJob(gateKey(b))) {
            Future<Instant> o5ing = pool.submit(() -> inCorrelation(o5Correlation, () -> runner.run(o5)));
            Waiter held = single(waitersBehind(gate.pid(), o5ing, TIMEOUT));
            assertHeldAtItsJobUpdate(held, gate.pid());
            List<String> written = relationsWritten(held.pid());
            assertThat(written).as("O5's uncommitted booking, reservation and history writes")
                    .contains("booking.booking", "booking.booking_history", "inventory.resource_reservation");
            assertThat(written).as("O5 records its fee decisions last, after the job it waits on")
                    .doesNotContain("loyalty.booking_fee_refund");
            assertThat(o5.runs()).containsExactly(1);
            assertThat(snapshot(b)).as("committed: nothing of O5 yet").isEqualTo(before);

            Future<CustomerBookingRow> cancelling = pool.submit(() -> inCorrelation(customerCorrelation,
                    () -> customerBookings.cancel(b.customerId(), b.bookingId())));
            Waiter customer = single(waitersBehind(held.pid(), cancelling, TIMEOUT));
            log.info("A4 {}: row lock backend {} holds {}; O5 backend {} waits behind it ({}), writes {}; "
                    + "Customer backend {} waits behind O5: {}", label(confirmed), gate.pid(), gateKey(b),
                    held.pid(), held, written, customer.pid(), customer);
            assertWaitsInBeginCommand(customer, held.pid(), "iam.user_account");

            gate.release();
            at = o5ing.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertRefusedStateChanged(() -> failureOf(cancelling));
        }

        assertThat(o5.runs()).as("O5 ran once").containsExactly(1);
        assertThat(retries.serializationFailures()).as("no 40001 for either command").isEmpty();
        assertThat(writesOf(customerCorrelation)).as("nothing of the Customer's command committed").isZero();
        assertO5Cancelled(world, b, before, at, o5Correlation);
        assertIntegrity(b);
    }

    // ================================================================ Group B: concurrent O5 commands

    // ---------------------------------------------------------------- B1: two O5 on one booking

    @Test
    void b1_twoO5OnOneBookingTheSecondWaitsOnTheCustomerThenIsRefused() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Booked b = book(world, world.customer.id(), world.room, world.start(), false);
        Snapshot before = snapshot(b);
        O5 first = o5(world, b, null);
        O5 second = o5(world, b, null);
        UUID firstCorrelation = UUID.randomUUID();
        UUID secondCorrelation = UUID.randomUUID();
        Instant at;
        try (RowLock gate = RowLock.onJob(t1(b.bookingId()))) {
            Future<Instant> one = pool.submit(() -> inCorrelation(firstCorrelation, () -> runner.run(first)));
            Waiter held = single(waitersBehind(gate.pid(), one, TIMEOUT));
            assertHeldAtItsJobUpdate(held, gate.pid());

            Future<Instant> two = pool.submit(() -> inCorrelation(secondCorrelation, () -> runner.run(second)));
            Waiter waiting = single(waitersBehind(held.pid(), two, TIMEOUT));
            log.info("B1: row lock backend {}; first O5 backend {} held ({}); second O5 backend {} waits: {}",
                    gate.pid(), held.pid(), held, waiting.pid(), waiting);
            assertWaitsInBeginCommand(waiting, held.pid(), "iam.user_account");
            assertThat(second.runs()).isEmpty();

            gate.release();
            at = one.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertO5RefusedAsTerminal(() -> failureOf(two));
            assertThat(second.pid()).isEqualTo(waiting.pid());
        }

        log.info("B1: first runs {}, second runs {} read {}; runner 40001 lines {}", first.runs(), second.runs(),
                second.seen(), retries.serializationFailures());
        assertThat(first.runs()).containsExactly(1);
        assertThat(first.cancelledRuns()).isEqualTo(1);
        assertThat(second.runs()).as("one run, no retry").containsExactly(1);
        assertThat(second.cancelledRuns()).isZero();
        assertThat(second.seen()).containsExactly("CANCELLED");
        assertThat(retries.serializationFailures()).isEmpty();
        assertThat(writesOf(secondCorrelation)).as("nothing of the second O5 committed").isZero();
        assertO5Cancelled(world, b, before, at, firstCorrelation);
        assertIntegrity(b);
    }

    // ---------------------------------------------------------------- B2: one venue, two Customers

    @Test
    void b2_twoO5InOneVenueForDistinctCustomersTheSecondWaitsOnTheVenueThenBothCommit() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource r2 = world.anotherRoom(room -> room.playStation(ConsoleType.PS5));
        Booked b1 = book(world, world.customer.id(), world.room, world.start(), false);
        Booked b2 = book(world, world.anotherCustomer(), r2, world.start(), false);
        assertThat(b1.resourceId()).isNotEqualTo(b2.resourceId());
        assertThat(b1.customerId()).isNotEqualTo(b2.customerId());
        Snapshot before1 = snapshot(b1);
        Snapshot before2 = snapshot(b2);
        O5 first = o5(world, b1, null);
        O5 second = o5(world, b2, null);
        assertThat(first.named().lockSet().venues()).isEqualTo(second.named().lockSet().venues());
        UUID firstCorrelation = UUID.randomUUID();
        UUID secondCorrelation = UUID.randomUUID();
        Instant at1;
        Instant at2;
        try (RowLock gate = RowLock.onJob(t1(b1.bookingId()))) {
            Future<Instant> one = pool.submit(() -> inCorrelation(firstCorrelation, () -> runner.run(first)));
            Waiter held = single(waitersBehind(gate.pid(), one, TIMEOUT));
            assertHeldAtItsJobUpdate(held, gate.pid());

            Future<Instant> two = pool.submit(() -> inCorrelation(secondCorrelation, () -> runner.run(second)));
            Waiter waiting = single(waitersBehind(held.pid(), two, TIMEOUT));
            log.info("B2: row lock backend {}; first O5 backend {} held; second O5 backend {} waits: {}", gate.pid(),
                    held.pid(), waiting.pid(), waiting);
            assertWaitsInBeginCommand(waiting, held.pid(), "venue.venue");
            assertThat(second.runs()).isEmpty();
            assertThat(snapshot(b2)).as("B2 untouched while the second O5 waits").isEqualTo(before2);

            gate.release();
            at1 = one.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            at2 = two.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(second.pid()).isEqualTo(waiting.pid());
        }

        assertThat(first.runs()).containsExactly(1);
        assertThat(second.runs()).containsExactly(1);
        assertThat(retries.serializationFailures()).isEmpty();
        assertThat(at2).as("serialized: the second sampled its instant after the first committed")
                .isAfterOrEqualTo(at1);
        assertO5Cancelled(world, b1, before1, at1, firstCorrelation);
        assertO5Cancelled(world, b2, before2, at2, secondCorrelation);
        assertThat(writesOf(firstCorrelation)).as("the first O5 wrote B1's rows only").isEqualTo(4);
        assertThat(writesOf(secondCorrelation)).as("the second O5 wrote B2's rows only").isEqualTo(4);
        assertIntegrity(b1);
        assertIntegrity(b2);
    }

    // ---------------------------------------------------------------- B3: two venues, one Customer

    @Test
    void b3_twoO5InTwoVenuesForOneCustomerTheSecondWaitsOnTheCustomerThenBothCommitWithoutDeadlock()
            throws Exception {
        OnlineBookingWorld world1 = OnlineBookingWorld.create();
        OnlineBookingWorld world2 = OnlineBookingWorld.create();
        UUID customer = world1.customer.id();
        Booked b1 = book(world1, customer, world1.room, world1.start(), false);
        Booked b2 = book(world2, customer, world2.room, world1.start().plus(Duration.ofHours(2)), false);
        assertThat(b1.venueId()).isNotEqualTo(b2.venueId());
        Snapshot before1 = snapshot(b1);
        Snapshot before2 = snapshot(b2);
        List<Locked> firstLocked = new CopyOnWriteArrayList<>();
        List<Locked> secondLocked = new CopyOnWriteArrayList<>();
        O5 first = o5(world1, b1, context -> firstLocked.add(new Locked(
                lockedElsewhere("venue.venue", b2.venueId()), lockedElsewhere("resource.resource", b2.resourceId()))));
        O5 second = o5(world2, b2, context -> secondLocked.add(new Locked(
                lockedElsewhere("venue.venue", b1.venueId()), lockedElsewhere("resource.resource", b1.resourceId()))));
        assertThat(first.named().lockSet().venues()).as("O5 names only its own venue").containsExactly(b1.venueId());
        assertThat(second.named().lockSet().venues()).containsExactly(b2.venueId());
        UUID firstCorrelation = UUID.randomUUID();
        UUID secondCorrelation = UUID.randomUUID();
        Instant at1;
        Instant at2;
        try (RowLock gate = RowLock.onJob(t1(b1.bookingId()))) {
            Future<Instant> one = pool.submit(() -> inCorrelation(firstCorrelation, () -> runner.run(first)));
            Waiter held = single(waitersBehind(gate.pid(), one, TIMEOUT));
            assertHeldAtItsJobUpdate(held, gate.pid());

            Future<Instant> two = pool.submit(() -> inCorrelation(secondCorrelation, () -> runner.run(second)));
            Waiter waiting = single(waitersBehind(held.pid(), two, TIMEOUT));
            log.info("B3: row lock backend {}; first O5 backend {} held, derived locks seen {}; second O5 backend {} "
                    + "waits: {}", gate.pid(), held.pid(), firstLocked, waiting.pid(), waiting);
            assertWaitsInBeginCommand(waiting, held.pid(), "iam.user_account");
            assertThat(firstLocked).as("begin_command derived the Customer's other booking: V2 and R2 locked by O5 #1")
                    .containsExactly(new Locked(true, true));
            assertThat(second.runs()).isEmpty();

            gate.release();
            at1 = one.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            at2 = two.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(second.pid()).isEqualTo(waiting.pid());
        }

        log.info("B3: first runs {}, second runs {}, second's derived locks {}; runner 40001 lines {}", first.runs(),
                second.runs(), secondLocked, retries.serializationFailures());
        assertThat(first.runs()).containsExactly(1);
        assertThat(second.runs()).as("serialized, not deadlocked, not retried").containsExactly(1);
        assertThat(secondLocked).as("O5 #2 likewise derived V1 and R1").containsExactly(new Locked(true, true));
        assertThat(retries.serializationFailures()).isEmpty();
        assertThat(at2).isAfterOrEqualTo(at1);
        assertO5Cancelled(world1, b1, before1, at1, firstCorrelation);
        assertO5Cancelled(world2, b2, before2, at2, secondCorrelation);
        assertThat(writesOf(firstCorrelation)).isEqualTo(4);
        assertThat(writesOf(secondCorrelation)).isEqualTo(4);
        assertIntegrity(b1);
        assertIntegrity(b2);
    }

    // ================================================================ setup

    /** A genuine future Online Booking: created PENDING, then accepted by the venue when {@code confirmed}. */
    private record Booked(UUID bookingId, UUID reservationId, UUID customerId, UUID venueId, UUID resourceId,
                          boolean confirmed) {
    }

    private Booked book(OnlineBookingWorld world, UUID customerId, Fixtures.Resource room,
                        Instant start, boolean confirmed) {
        CreatedBooking created = creation.create(world.request(customerId, room, start, 60));
        if (confirmed) {
            assertThat(responses.accept(world.account.id(), created.bookingId()))
                    .isInstanceOf(CommandAnswer.Ok.class);
        }
        Booked b = new Booked(created.bookingId(), created.reservationId(), customerId, world.venue.id(), room.id(),
                confirmed);
        Snapshot s = snapshot(b);
        assertThat(s.status()).isEqualTo(confirmed ? "CONFIRMED" : "PENDING");
        assertThat(s.terminalReason()).isNull();
        assertThat(s.reservations()).singleElement().satisfies(r -> assertThat(r.active()).isTrue());
        assertThat(s.refunds()).isEmpty();
        assertThat(confirmed ? s.t4() : s.t1()).as("the job the cancellation withdraws").isEqualTo("PENDING");
        assertThat(s.t5()).as("no T5 before check-in").isNull();
        assertThat(OWNER.sql("SELECT start_at > clock_timestamp() FROM booking.booking WHERE id = :id")
                .param("id", b.bookingId()).query(Boolean.class).single()).as("a future booking").isTrue();
        return b;
    }

    /** T1 while PENDING, T4 while CONFIRMED: the job row both cancellations update, and the gate. */
    private static String gateKey(Booked b) {
        return b.confirmed() ? bookingStart(b.bookingId()) : t1(b.bookingId());
    }

    private static String label(boolean confirmed) {
        return confirmed ? "CONFIRMED" : "PENDING";
    }

    /**
     * O5 as Phase 4 will call it: the port, by the venue's account, in one command under the lock set
     * {@link FindBooking} computes for the booking (its Customer, Venue and Resource), computed once before the
     * command - the same rows the Customer's command names.
     */
    private O5 o5(OnlineBookingWorld world, Booked b, Consumer<CommandContext> onBody) {
        CommandScope scope = runner.run(new FindBooking(store, b.bookingId())).orElseThrow().scope();
        assertThat(scope.lockSet().users()).containsExactly(b.customerId());
        assertThat(scope.lockSet().venues()).containsExactly(b.venueId());
        assertThat(scope.lockSet().resources()).containsExactly(b.resourceId());
        return new O5(scope, b.bookingId(), context -> {
            if (onBody != null) {
                onBody.accept(context);
            }
            port.cancel(context, world.venue.id(), world.account.id(), List.of(b.bookingId()));
        });
    }

    // ================================================================ outcomes (committed reads)

    /** Everything the Customer's cancellation must have committed, and nothing more; returns the committed state. */
    private static Snapshot assertCustomerCancelled(Booked b, Snapshot before, UUID correlationId) {
        Snapshot after = snapshot(b);
        assertThat(after.status()).isEqualTo("CANCELLED");
        assertThat(after.terminalReason()).isEqualTo(CUSTOMER_REASON);
        assertThat(after.feeTreatment()).as("FR-PTS-16: the Customer's own cancellation").isEqualTo("CUSTOMER_POLICY");
        Instant at = after.terminalAt();
        History terminal = assertOneTerminalHistory(b, before, after, CUSTOMER_REASON, at);
        assertThat(terminal.actorType()).isEqualTo("CUSTOMER");
        assertThat(terminal.actorId()).isEqualTo(b.customerId());
        assertThat(terminal.correlationId()).isEqualTo(correlationId);
        assertReleasedOnce(b, after, at, CUSTOMER_REASON);
        assertThat(after.refunds()).singleElement().satisfies(refund -> {
            assertThat(refund.treatment()).isEqualTo("CUSTOMER_POLICY");
            assertThat(refund.terminalReason()).isEqualTo(CUSTOMER_REASON);
            assertThat(refund.userId()).isEqualTo(b.customerId());
            assertThat(refund.historyId()).isEqualTo(terminal.id());
            assertThat(refund.correlationId()).isEqualTo(correlationId);
        });
        assertJobsWithdrawn(b, before, after);
        assertNewEvents(b, before, after, correlationId);
        assertThat(writesOf(correlationId)).as("its history row, fee decision and two events").isEqualTo(4);
        return after;
    }

    /** Everything O5 must have committed for {@code b}, and nothing more; returns the committed state. */
    private static Snapshot assertO5Cancelled(OnlineBookingWorld world, Booked b, Snapshot before, Instant at,
                                              UUID correlationId) {
        Snapshot after = snapshot(b);
        assertThat(after.status()).isEqualTo("CANCELLED");
        assertThat(after.terminalReason()).isEqualTo(O5_REASON);
        assertThat(after.terminalAt()).isEqualTo(at);
        assertThat(after.feeTreatment()).isEqualTo("FULL_REFUND");
        History terminal = assertOneTerminalHistory(b, before, after, O5_REASON, at);
        assertThat(terminal.actorType()).isEqualTo("VENUE");
        assertThat(terminal.actorId()).isEqualTo(world.account.id());
        assertThat(terminal.correlationId()).isEqualTo(correlationId);
        assertReleasedOnce(b, after, at, O5_REASON);
        assertThat(after.refunds()).singleElement().satisfies(refund -> {
            assertThat(refund.treatment()).isEqualTo("FULL_REFUND");
            assertThat(refund.terminalReason()).isEqualTo(O5_REASON);
            assertThat(refund.userId()).isEqualTo(b.customerId());
            assertThat(refund.historyId()).isEqualTo(terminal.id());
            assertThat(refund.correlationId()).isEqualTo(correlationId);
        });
        assertJobsWithdrawn(b, before, after);
        assertNewEvents(b, before, after, correlationId);
        return after;
    }

    private static History assertOneTerminalHistory(Booked b, Snapshot before, Snapshot after, String reason,
                                                    Instant at) {
        assertThat(after.history().subList(0, before.history().size())).isEqualTo(before.history());
        assertThat(after.history()).hasSize(before.history().size() + 1);
        History terminal = after.history().getLast();
        assertThat(after.history()).filteredOn(row -> row.terminalReason() != null).containsExactly(terminal);
        assertThat(terminal.fromStatus()).isEqualTo(b.confirmed() ? "CONFIRMED" : "PENDING");
        assertThat(terminal.toStatus()).isEqualTo("CANCELLED");
        assertThat(terminal.terminalReason()).isEqualTo(reason);
        assertThat(terminal.occurredAt()).isEqualTo(at);
        return terminal;
    }

    private static void assertReleasedOnce(Booked b, Snapshot after, Instant at, String reason) {
        assertThat(after.reservations()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(b.reservationId());
            assertThat(r.type()).isEqualTo("BOOKING");
            assertThat(r.active()).isFalse();
            assertThat(r.releasedAt()).isEqualTo(at);
            assertThat(r.releaseReason()).isEqualTo(reason);
        });
    }

    /** T1 superseded while PENDING; T4 while CONFIRMED (no T5 exists before check-in, none is invented). */
    private static void assertJobsWithdrawn(Booked b, Snapshot before, Snapshot after) {
        if (b.confirmed()) {
            assertThat(after.t1()).as("T1 as the venue's Accept left it").isEqualTo(before.t1());
            assertThat(after.t4()).isEqualTo("SUPERSEDED");
        } else {
            assertThat(after.t1()).isEqualTo("SUPERSEDED");
            assertThat(after.t4()).isNull();
        }
        assertThat(after.t5()).isNull();
    }

    /** Exactly one ReservationReleased and one BookingCancelled were added, both under the winner's correlation. */
    private static void assertNewEvents(Booked b, Snapshot before, Snapshot after, UUID correlationId) {
        assertThat(after.events().subList(0, before.events().size())).isEqualTo(before.events());
        assertThat(after.events().subList(before.events().size(), after.events().size()))
                .containsExactlyInAnyOrder(
                        new Event(IntegrationEvents.RESERVATION_RELEASED, b.reservationId(), correlationId),
                        new Event(IntegrationEvents.BOOKING_CANCELLED, b.bookingId(), correlationId));
    }

    /**
     * The database's view of the outcome: at most one terminal history row, its instant the booking's; the fee
     * treatment snapshot equal to the one decision, which points at that row; no active reservation left.
     */
    private static void assertIntegrity(Booked b) {
        assertThat(OWNER.sql("""
                        SELECT (SELECT count(*) FROM booking.booking_history h
                                 WHERE h.booking_id = b.id AND h.terminal_reason IS NOT NULL) = 1
                           AND (SELECT count(*) FROM loyalty.booking_fee_refund f WHERE f.booking_id = b.id) = 1
                           AND (SELECT bool_and(f.treatment = b.fee_treatment
                                                AND f.terminal_reason = b.terminal_reason
                                                AND h.occurred_at = b.terminal_at
                                                AND h.terminal_reason = b.terminal_reason)
                                  FROM loyalty.booking_fee_refund f
                                  JOIN booking.booking_history h ON h.id = f.booking_history_id
                                 WHERE f.booking_id = b.id)
                           AND NOT EXISTS (SELECT 1 FROM inventory.resource_reservation r
                                            WHERE r.booking_id = b.id AND r.active_flag)
                           AND (SELECT count(*) FROM technical.outbox_event e
                                 WHERE e.aggregate_id = b.id AND e.event_type = :cancelled) = 1
                          FROM booking.booking b WHERE b.id = :id""")
                .param("id", b.bookingId()).param("cancelled", IntegrationEvents.BOOKING_CANCELLED)
                .query(Boolean.class).single()).as("committed integrity of booking %s", b.bookingId()).isTrue();
    }

    private static void assertO5RefusedAsTerminal(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CANCELLED ONLINE")
                .hasMessageContaining("is not cancelled by an hours change");
    }

    private static void assertRefusedStateChanged(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ApiErrorCode.BOOKING_STATE_CHANGED));
    }

    /** The held command waits on the gate's job row, in its own {@code supersede}, inside its open transaction. */
    private static void assertHeldAtItsJobUpdate(Waiter held, int gatePid) {
        assertThat(held.waitEventType()).isEqualTo("Lock");
        assertThat(held.blockedBy()).containsExactly(gatePid);
        assertThat(held.query()).contains("UPDATE technical.scheduled_job");
    }

    /** {@code waiter} waits in {@code begin_command} behind exactly {@code holderPid}, queued on a row of relation. */
    private static void assertWaitsInBeginCommand(Waiter waiter, int holderPid, String relation) {
        assertThat(waiter.pid()).isNotEqualTo(holderPid);
        assertThat(waiter.blockedBy()).as("backend %s waits only behind %s", waiter.pid(), holderPid)
                .containsExactly(holderPid);
        assertThat(waiter.waitEventType()).isEqualTo("Lock");
        assertThat(waiter.query()).contains("tournament.begin_command");
        assertThat(tupleLocks(waiter.pid())).as("the row backend %s queues for", waiter.pid())
                .containsExactly(relation);
    }

    /**
     * The backends waiting behind {@code holderPid}, once {@code waiter} is among them, read again on a fresh
     * {@code pg_stat_activity} snapshot. {@link LockWaits#awaitBlockedBy} matches on the live {@code pg_blocking_pids}
     * while the activity columns come from the statement's snapshot, which may predate the wait (wait_event_type still
     * null). A backend blocked behind a lock its holder keeps cannot move on, so the second read is stable.
     */
    private static List<Waiter> waitersBehind(int holderPid, Future<?> waiter, Duration timeout) {
        List<Waiter> first = LockWaits.awaitBlockedBy(holderPid, waiter, timeout);
        List<Waiter> settled = LockWaits.blockedBy(holderPid);
        assertThat(settled).extracting(Waiter::pid).as("the same backends, still waiting")
                .isEqualTo(first.stream().map(Waiter::pid).toList());
        return settled;
    }

    private static Waiter single(List<Waiter> waiting) {
        assertThat(waiting).as("exactly one backend waiting").hasSize(1);
        return waiting.getFirst();
    }

    // ================================================================ the O5 command and its observer

    /** O5 as a command: records each body run's attempt, backend and the booking status it read under the locks. */
    static final class O5 implements Command<Instant> {

        private final CommandScope scope;
        private final UUID bookingId;
        private final Consumer<CommandContext> cancel;
        private final List<Integer> runs = new CopyOnWriteArrayList<>();
        private final List<String> seen = new CopyOnWriteArrayList<>();
        private final AtomicInteger cancelledRuns = new AtomicInteger();
        private volatile int pid;

        O5(CommandScope scope, UUID bookingId, Consumer<CommandContext> cancel) {
            this.scope = scope;
            this.bookingId = bookingId;
            this.cancel = cancel;
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
        public Instant execute(CommandContext context) {
            runs.add(context.attempt());
            pid = LockWaits.backendPid(context);
            seen.add(context.jdbc().sql("SELECT status FROM booking.booking WHERE id = :id")
                    .param("id", bookingId).query(String.class).single());
            cancel.accept(context);
            cancelledRuns.incrementAndGet();
            return context.commandNow();
        }

        CommandScope named() {
            return scope;
        }

        List<Integer> runs() {
            return List.copyOf(runs);
        }

        List<String> seen() {
            return List.copyOf(seen);
        }

        int cancelledRuns() {
            return cancelledRuns.get();
        }

        int pid() {
            return pid;
        }
    }

    /** Whether the other venue's row and room were locked by another transaction, seen from inside one body run. */
    record Locked(boolean venue, boolean resource) {
    }

    /**
     * Whether {@code table}'s row {@code id} is locked by another transaction: a separate owner connection tries
     * {@code SELECT ... FOR UPDATE NOWAIT} and rolls back at once. NOWAIT never waits, so it cannot block anyone.
     */
    private static boolean lockedElsewhere(String table, UUID id) {
        try (Connection connection = owner()) {
            connection.setAutoCommit(false);
            try (PreparedStatement lock = connection.prepareStatement(
                    "SELECT 1 FROM " + table + " WHERE id = ? FOR UPDATE NOWAIT")) {
                lock.setObject(1, id);
                try (ResultSet rs = lock.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("no row " + id + " in " + table);
                    }
                    return false;
                }
            } catch (SQLException e) {
                if ("55P03".equals(e.getSQLState())) {
                    return true;
                }
                throw e;
            } finally {
                connection.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * TEST-ONLY: an ordinary row lock on one PENDING scheduled job, held by its own owner connection in an open
     * transaction that writes nothing; {@link #release} rolls it back.
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
            Connection connection = owner();
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

    /** The runner's own DEBUG/WARN lines for SQLSTATE 40001, captured for one test. */
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

    record History(UUID id, long sequence, Instant occurredAt, String fromStatus, String toStatus,
                   String terminalReason, String actorType, UUID actorId, UUID correlationId) {
    }

    record Reservation(UUID id, String type, boolean active, Instant releasedAt, String releaseReason) {
    }

    record Refund(UUID id, String treatment, String terminalReason, UUID userId, UUID historyId, UUID correlationId) {
    }

    record Event(String type, UUID aggregateId, UUID correlationId) {
    }

    /** Every committed fact of one booking either cancellation writes. */
    record Snapshot(String status, String terminalReason, Instant terminalAt, String feeTreatment,
                    List<History> history, List<Reservation> reservations, List<Refund> refunds, String t1,
                    String t4, String t5, List<Event> events) {
    }

    private static Snapshot snapshot(Booked b) {
        UUID id = b.bookingId();
        return OWNER.sql("""
                        SELECT status, terminal_reason, terminal_at, fee_treatment
                          FROM booking.booking WHERE id = :id""")
                .param("id", id)
                .query((rs, n) -> new Snapshot(rs.getString("status"), rs.getString("terminal_reason"),
                        instant(rs.getObject("terminal_at", OffsetDateTime.class)), rs.getString("fee_treatment"),
                        history(id), reservations(id), refunds(id), jobStatus(t1(id)), jobStatus(bookingStart(id)),
                        jobStatus(ManualBookingCommand.bookingCompleteKey(id)), events(id)))
                .single();
    }

    private static String jobStatus(String key) {
        return VenueAnswerDb.job(key).map(VenueAnswerDb.JobRow::status).orElse(null);
    }

    private static List<History> history(UUID bookingId) {
        return OWNER.sql("""
                        SELECT id, sequence, occurred_at, from_status, to_status, terminal_reason, actor_type, actor_id,
                               correlation_id
                          FROM booking.booking_history WHERE booking_id = :id ORDER BY sequence""")
                .param("id", bookingId)
                .query((rs, n) -> new History(rs.getObject("id", UUID.class), rs.getLong("sequence"),
                        instant(rs.getObject("occurred_at", OffsetDateTime.class)), rs.getString("from_status"),
                        rs.getString("to_status"), rs.getString("terminal_reason"), rs.getString("actor_type"),
                        rs.getObject("actor_id", UUID.class), rs.getObject("correlation_id", UUID.class)))
                .list();
    }

    private static List<Reservation> reservations(UUID bookingId) {
        return OWNER.sql("""
                        SELECT id, reservation_type, active_flag, actual_reservation_release_at, release_reason
                          FROM inventory.resource_reservation WHERE booking_id = :id ORDER BY id""")
                .param("id", bookingId)
                .query((rs, n) -> new Reservation(rs.getObject("id", UUID.class), rs.getString("reservation_type"),
                        rs.getBoolean("active_flag"),
                        instant(rs.getObject("actual_reservation_release_at", OffsetDateTime.class)),
                        rs.getString("release_reason")))
                .list();
    }

    private static List<Refund> refunds(UUID bookingId) {
        return OWNER.sql("""
                        SELECT id, treatment, terminal_reason, user_id, booking_history_id, correlation_id
                          FROM loyalty.booking_fee_refund WHERE booking_id = :id ORDER BY id""")
                .param("id", bookingId)
                .query((rs, n) -> new Refund(rs.getObject("id", UUID.class), rs.getString("treatment"),
                        rs.getString("terminal_reason"), rs.getObject("user_id", UUID.class),
                        rs.getObject("booking_history_id", UUID.class), rs.getObject("correlation_id", UUID.class)))
                .list();
    }

    /** The outbox events of the booking and of its reservations, in commit order. */
    private static List<Event> events(UUID bookingId) {
        return OWNER.sql("""
                        SELECT e.event_type, e.aggregate_id, e.correlation_id FROM technical.outbox_event e
                         WHERE e.aggregate_id = :id
                            OR e.aggregate_id IN (SELECT r.id FROM inventory.resource_reservation r
                                                   WHERE r.booking_id = :id)
                         ORDER BY e.id""")
                .param("id", bookingId)
                .query((rs, n) -> new Event(rs.getString("event_type"), rs.getObject("aggregate_id", UUID.class),
                        rs.getObject("correlation_id", UUID.class)))
                .list();
    }

    /** Committed rows carrying one correlation: booking history, Booking Fee decisions and outbox events. */
    private static long writesOf(UUID correlationId) {
        return OWNER.sql("""
                        SELECT (SELECT count(*) FROM booking.booking_history WHERE correlation_id = :c)
                             + (SELECT count(*) FROM loyalty.booking_fee_refund WHERE correlation_id = :c)
                             + (SELECT count(*) FROM technical.outbox_event WHERE correlation_id = :c)""")
                .param("c", correlationId).query(Long.class).single();
    }

    /** Relations {@code pid} holds a write lock on - a row it inserted, updated or deleted takes one. */
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

    /** The tables of the tuple locks {@code pid} holds: the row a row-lock waiter queues for. */
    private static List<String> tupleLocks(int pid) {
        return OWNER.sql("""
                        SELECT n.nspname || '.' || c.relname
                          FROM pg_locks l
                          JOIN pg_class c ON c.oid = l.relation
                          JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE l.pid = :pid AND l.locktype = 'tuple' AND l.granted
                         ORDER BY 1""")
                .param("pid", pid).query(String.class).list();
    }

    // ================================================================ plumbing

    private static Connection owner() throws SQLException {
        return DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR);
    }

    private static <R> R inCorrelation(UUID id, Supplier<R> body) {
        try (CorrelationId.Scope ignored = CorrelationId.open(id)) {
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

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
