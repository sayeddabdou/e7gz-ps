package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import ps.e7gz.booking.internal.LockWaits.Waiter;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRetryProperties;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.persistence.DataSourceConfig;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;
import ps.e7gz.venue.api.HoursChangeBookings;

/**
 * E7-113 Phase 3D-C2-A: the opening-hours cancellation (O5, {@link HoursChangeBookings#cancel} in a committed command)
 * waiting in {@code tournament.begin_command} behind a genuine Online Booking creation ({@link OnlineBookingCreation})
 * that commits a new booking B2 while O5 waits. O5 is given exactly one booking, B1 of Customer C1, and the lock set
 * {@link FindBooking} computes for it (C1, the venue, B1's room), once, before the command - as {@code CommandRunner}
 * keeps one {@link CommandScope} for every attempt.
 *
 * <ul>
 *   <li>D1a / D1b: B2 is C1's. O5 lists C1's bookings, then waits on C1's row behind the creation; once the creation
 *       commits, {@code begin_command}'s re-check finds a booking of a named Customer it did not list and raises the
 *       database's own SQLSTATE 40001 ("Shared command scope changed while waiting") before any write. D1a runs O5 on
 *       a test-local runner allowed one attempt, to read that error as PostgreSQL sent it. D1b runs it on the
 *       application's runner, which rolls back and reruns with the same scope: the database rediscovers B2 for
 *       locking, and the body - on attempt 2 - still cancels only B1.</li>
 *   <li>D2: B2 is another Customer's, C2, who is not in O5's lock set. O5 waits on the venue row behind the creation;
 *       {@code begin_command} lists bookings of named Customers only, so B2 is never noticed: no 40001, one run.</li>
 * </ul>
 *
 * <p>Only the direct Booking port is covered here: it never refreshes its caller's affected set. D2 does not show that
 * a stale opening-hours preview may proceed; refreshing the affected set and detecting a Customer missing from the lock
 * set are the Venue command's (Phase 4).
 *
 * <p>Order is set by real locks only. A separate owner connection holds {@code SELECT ... FOR UPDATE} on B2's room
 * (R2), a row the creation's {@code begin_command} locks after the Customer and the venue; the creation waits there
 * holding both, before any write. O5 is then proved, from {@code pg_blocking_pids} and {@code pg_locks}, to wait in
 * {@code begin_command} behind the creation on the expected row. Rolling the row lock back releases the creation. No
 * trigger is off, no row is written outside a production command, nothing is injected.
 */
class HoursChangeRaceConcurrencyIntegrationTest extends IntegrationTest {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(HoursChangeRaceConcurrencyIntegrationTest.class);
    private static final String REASON = "VENUE_OPENING_HOURS_CHANGED";
    private static final String SCOPE_CHANGED = "Shared command scope changed while waiting; retry whole transaction";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    CommandRunner runner;

    @Autowired
    DataSource appDataSource;

    @Autowired
    @Qualifier(DataSourceConfig.ADMIN_DATA_SOURCE)
    DataSource adminDataSource;

    @Autowired
    HoursChangeBookings port;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    VenueAnswerStore store;

    private CorrelationId.Scope correlation;
    private ExecutorService pool;
    private RetryLog retries;

    @BeforeEach
    void open() {
        correlation = CorrelationId.open(UUID.randomUUID());
        AtomicInteger threads = new AtomicInteger();
        pool = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "scope-race-" + threads.incrementAndGet());
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

    // ---------------------------------------------------------------- D1a: the database's own 40001, one attempt

    @Test
    void d1a_newBookingOfTheNamedCustomerCommittedWhileO5WaitsRaisesTheDatabasesOwn40001() throws Exception {
        Fixture f = fixture(false);
        Snapshot b1Before = snapshot(f.b1().bookingId());
        CommandRunner oneAttempt = new CommandRunner(appDataSource, adminDataSource,
                new CommandRetryProperties(1, Duration.ZERO, Duration.ZERO));
        O5 o5 = o5(f, null);

        Race race = race(f, oneAttempt, o5, "iam.user_account");

        Throwable failure = failureOf(race.cancelling());
        log.info("D1a: O5 backend {} failed: {}", race.o5Pid(), failure.toString());
        PSQLException database = databaseError(failure);
        log.info("D1a: SQLSTATE {}; server message '{}'; where '{}'", database.getSQLState(),
                database.getServerErrorMessage().getMessage(), database.getServerErrorMessage().getWhere());
        assertThat(database.getSQLState()).as("SQLSTATE sent by PostgreSQL").isEqualTo("40001");
        assertThat(database.getServerErrorMessage()).as("a server error, not one the client made").isNotNull();
        assertThat(database.getServerErrorMessage().getMessage()).isEqualTo(SCOPE_CHANGED);
        assertThat(database.getServerErrorMessage().getWhere()).as("raised in begin_command's re-check")
                .contains("PL/pgSQL function tournament.begin_command(").contains("at RAISE");
        assertThat(retries.serializationFailures()).as("the runner gave up after its one attempt")
                .containsExactly("Command " + O5.class.getName() + " failed with 40001 on all 1 attempts");

        assertThat(o5.scopeCalls()).as("the scope was computed once").isEqualTo(1);
        assertThat(o5.runs()).as("O5's body never ran").isEmpty();
        assertThat(writesOf(race.o5Correlation())).as("nothing of O5's failed transaction committed").isZero();
        assertThat(snapshot(f.b1().bookingId())).as("B1 exactly as before").isEqualTo(b1Before);
        assertCreatedByItsOwnCommand(race.b2(), f.b2Customer(), race.creationCorrelation());
    }

    // ---------------------------------------------------------------- D1b: the application's runner retries

    @Test
    void d1b_theRunnerRetriesWithTheSameScopeAndTheDatabaseRediscoversTheNewBookingForLocking() throws Exception {
        Fixture f = fixture(false);
        Snapshot b1Before = snapshot(f.b1().bookingId());
        List<Locked> locked = new CopyOnWriteArrayList<>();
        O5 o5 = o5(f, context -> locked.add(new Locked(context.attempt(),
                lockedElsewhere("iam.user_account", f.world().customer.id()),
                lockedElsewhere("booking.booking", b2Of(f)),
                lockedElsewhere("resource.resource", f.r2().id()))));

        Race race = race(f, runner, o5, "iam.user_account");

        Instant at = race.cancelling().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        log.info("D1b: O5 waited as backend {}; body runs (attempt) {} on backend {}; runner 40001 lines {}; "
                + "locks seen from outside on the retry {}", race.o5Pid(), o5.runs(), o5.pid(),
                retries.serializationFailures(), locked);
        assertThat(retries.serializationFailures()).as("one genuine 40001 retry, logged by the runner itself")
                .singleElement().satisfies(line -> assertThat(line)
                        .startsWith("Command " + O5.class.getName() + " attempt 1 failed with 40001; retrying in "));
        assertThat(o5.scopeCalls()).as("one CommandScope for both attempts").isEqualTo(1);
        assertThat(o5.named().lockSet().users()).as("still only C1").containsExactly(f.world().customer.id());
        assertThat(o5.runs()).as("the body ran once, on attempt 2").containsExactly(2);
        assertThat(o5.cancelledRuns()).isEqualTo(1);
        assertThat(locked).as("on the retry the database had locked C1, and rediscovered and locked B2 and R2")
                .containsExactly(new Locked(2, true, true, true));
        assertThat(at).as("O5's instant is sampled after B2 committed").isAfter(race.b2().createdAt());

        assertCancelledByO5(f, b1Before, at, race.o5Correlation());
        assertThat(writesOf(race.o5Correlation())).as("O5's only writes: B1's history row, refund and two events")
                .isEqualTo(4);
        assertCreatedByItsOwnCommand(race.b2(), f.b2Customer(), race.creationCorrelation());
    }

    // ---------------------------------------------------------------- D2: a Customer outside the lock set

    @Test
    void d2_newBookingOfACustomerOutsideTheLockSetIsNeverNoticedNo40001AndOnlyB1IsCancelled() throws Exception {
        Fixture f = fixture(true);
        Snapshot b1Before = snapshot(f.b1().bookingId());
        List<Locked> locked = new CopyOnWriteArrayList<>();
        O5 o5 = o5(f, context -> locked.add(new Locked(context.attempt(),
                lockedElsewhere("iam.user_account", f.b2Customer()),
                lockedElsewhere("booking.booking", b2Of(f)),
                lockedElsewhere("resource.resource", f.r2().id()))));

        Race race = race(f, runner, o5, "venue.venue");

        Instant at = race.cancelling().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        log.info("D2: O5 backend {} body runs (attempt) {} on backend {}; runner 40001 lines {}; locks seen {}",
                race.o5Pid(), o5.runs(), o5.pid(), retries.serializationFailures(), locked);
        assertThat(retries.serializationFailures()).as("no 40001 for any command").isEmpty();
        assertThat(o5.scopeCalls()).isEqualTo(1);
        assertThat(o5.named().lockSet().users()).as("C2 was not added").containsExactly(f.world().customer.id())
                .doesNotContain(f.b2Customer());
        assertThat(o5.runs()).as("one run, attempt 1").containsExactly(1);
        assertThat(o5.pid()).as("the body ran on the transaction that waited").isEqualTo(race.o5Pid());
        assertThat(o5.cancelledRuns()).isEqualTo(1);
        assertThat(locked).as("O5 held neither C2, nor B2, nor R2")
                .containsExactly(new Locked(1, false, false, false));
        assertThat(at).isAfter(race.b2().createdAt());

        assertCancelledByO5(f, b1Before, at, race.o5Correlation());
        assertThat(writesOf(race.o5Correlation())).isEqualTo(4);
        assertCreatedByItsOwnCommand(race.b2(), f.b2Customer(), race.creationCorrelation());
    }

    // ================================================================ the race

    /** B2's creation and O5 as they ended, with the backend O5 waited on. */
    private record Race(CreatedBooking b2, UUID creationCorrelation, Future<Instant> cancelling, int o5Pid,
                        UUID o5Correlation) {
    }

    /**
     * Holds R2, starts B2's genuine creation and proves it waits on R2 before writing; starts O5 and proves it waits
     * in {@code begin_command} on a row of {@code o5WaitsOn} behind the creation, before its body; then releases R2
     * and returns once the creation committed. O5's outcome is left to the caller.
     */
    private Race race(Fixture f, CommandRunner o5Runner, O5 o5, String o5WaitsOn) throws Exception {
        UUID creationCorrelation = UUID.randomUUID();
        UUID o5Correlation = UUID.randomUUID();
        long b2CustomerBookings = bookingsOf(f.b2Customer());
        try (RowLock r2 = RowLock.on("resource.resource", f.r2().id())) {
            Future<CreatedBooking> creating = pool.submit(() -> inCorrelation(creationCorrelation,
                    () -> creation.create(f.world().request(f.b2Customer(), f.r2(), f.b2Start(), 60))));
            Waiter creator = single(LockWaits.awaitBlockedBy(r2.pid(), creating, TIMEOUT));
            assertWaitsInBeginCommand(creator, r2.pid(), "resource.resource");
            assertThat(relationsWritten(creator.pid())).as("the creation has written nothing yet").isEmpty();

            Future<Instant> cancelling = pool.submit(() -> inCorrelation(o5Correlation, () -> o5Runner.run(o5)));
            Waiter waiting = single(LockWaits.awaitBlockedBy(creator.pid(), cancelling, TIMEOUT));
            log.info("R2 row lock backend {}; creation backend {} waits: {}; O5 backend {} waits behind it: {}",
                    r2.pid(), creator.pid(), creator, waiting.pid(), waiting);
            assertWaitsInBeginCommand(waiting, creator.pid(), o5WaitsOn);
            assertThat(o5.runs()).as("O5's body has not started").isEmpty();
            assertThat(bookingsOf(f.b2Customer())).as("O5 listed the Customer's bookings before B2 existed")
                    .isEqualTo(b2CustomerBookings);
            assertThat(relationsWritten(creator.pid())).as("still nothing written by the creation").isEmpty();

            r2.release();
            CreatedBooking b2 = creating.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            assertThat(bookingsOf(f.b2Customer())).as("B2 committed").isEqualTo(b2CustomerBookings + 1);
            return new Race(b2, creationCorrelation, cancelling, waiting.pid(), o5Correlation);
        }
    }

    /** O5 on B1 only, under the lock set FindBooking computes for B1 - C1, the venue, B1's room. */
    private O5 o5(Fixture f, Consumer<CommandContext> onBody) {
        CommandScope scope = runner.run(new FindBooking(store, f.b1().bookingId())).orElseThrow().scope();
        assertThat(scope.lockSet().users()).containsExactly(f.world().customer.id());
        assertThat(scope.lockSet().venues()).containsExactly(f.world().venue.id());
        assertThat(scope.lockSet().resources()).containsExactly(f.world().room.id());
        return new O5(scope, context -> {
            if (onBody != null) {
                onBody.accept(context);
            }
            port.cancel(context, f.world().venue.id(), f.world().account.id(), List.of(f.b1().bookingId()));
        });
    }

    /** B1 (C1's PENDING booking in the first room) and what B2 will be: its Customer, room R2 and start. */
    private record Fixture(OnlineBookingWorld world, CreatedBooking b1, UUID b2Customer, Fixtures.Resource r2,
                           Instant b2Start) {
    }

    /**
     * A fresh venue with C1's genuine PENDING booking B1 in its first room, a second PS5 room R2, and B2's Customer:
     * C1 again (D1) or a new verified Customer C2 (D2). B2 is two hours after B1, in R2, inside the same session.
     */
    private Fixture fixture(boolean otherCustomer) {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        CreatedBooking b1 = creation.create(world.request(world.customer.id(), world.room, world.start(), 60));
        Fixtures.Resource r2 = world.anotherRoom(room -> room.playStation(ConsoleType.PS5));
        UUID b2Customer = otherCustomer ? world.anotherCustomer() : world.customer.id();
        Snapshot b1Created = snapshot(b1.bookingId());
        assertThat(b1Created.status()).isEqualTo("PENDING");
        assertThat(b1Created.t1()).isEqualTo("PENDING");
        assertThat(b1Created.reservations()).singleElement().satisfies(r -> assertThat(r.active()).isTrue());
        assertThat(bookingsOf(b2Customer)).isEqualTo(otherCustomer ? 0 : 1);
        return new Fixture(world, b1, b2Customer, r2, world.start().plus(Duration.ofHours(2)));
    }

    // ================================================================ assertions (committed reads, owner connection)

    /** Everything O5 must have committed for B1, and nothing more. */
    private static void assertCancelledByO5(Fixture f, Snapshot before, Instant at, UUID o5Correlation) {
        UUID b1 = f.b1().bookingId();
        Snapshot after = snapshot(b1);
        assertThat(after.status()).isEqualTo("CANCELLED");
        assertThat(after.terminalReason()).isEqualTo(REASON);
        assertThat(after.terminalAt()).isEqualTo(at);
        assertThat(after.feeTreatment()).isEqualTo("FULL_REFUND");
        assertThat(after.customerId()).isEqualTo(f.world().customer.id());
        assertThat(after.venueId()).isEqualTo(f.world().venue.id());

        assertThat(after.history().subList(0, before.history().size())).isEqualTo(before.history());
        assertThat(after.history()).hasSize(before.history().size() + 1)
                .filteredOn(row -> "CANCELLED".equals(row.toStatus())).singleElement().satisfies(row -> {
                    assertThat(row.fromStatus()).isEqualTo("PENDING");
                    assertThat(row.terminalReason()).isEqualTo(REASON);
                    assertThat(row.occurredAt()).isEqualTo(at);
                    assertThat(row.actorType()).isEqualTo("VENUE");
                    assertThat(row.actorId()).isEqualTo(f.world().account.id());
                    assertThat(row.correlationId()).isEqualTo(o5Correlation);
                });
        UUID terminal = after.history().get(after.history().size() - 1).id();

        assertThat(after.reservations()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(f.b1().reservationId());
            assertThat(r.type()).isEqualTo("BOOKING");
            assertThat(r.active()).isFalse();
            assertThat(r.releasedAt()).isEqualTo(at);
            assertThat(r.releaseReason()).isEqualTo(REASON);
        });
        assertThat(after.refunds()).singleElement().satisfies(refund -> {
            assertThat(refund.treatment()).isEqualTo("FULL_REFUND");
            assertThat(refund.terminalReason()).isEqualTo(REASON);
            assertThat(refund.userId()).isEqualTo(f.world().customer.id());
            assertThat(refund.historyId()).isEqualTo(terminal);
            assertThat(refund.correlationId()).isEqualTo(o5Correlation);
        });
        assertThat(after.t1()).isEqualTo("SUPERSEDED");
        assertThat(after.events().subList(0, before.events().size())).isEqualTo(before.events());
        assertThat(after.events().subList(before.events().size(), after.events().size()))
                .containsExactlyInAnyOrder(
                        new Event(IntegrationEvents.RESERVATION_RELEASED, f.b1().reservationId(), o5Correlation),
                        new Event(IntegrationEvents.BOOKING_CANCELLED, b1, o5Correlation));
    }

    /** B2 exactly as its own creation committed it: PENDING, held, T1 pending, its two events; nothing of O5. */
    private static void assertCreatedByItsOwnCommand(CreatedBooking b2, UUID customerId, UUID creationCorrelation) {
        Snapshot s = snapshot(b2.bookingId());
        assertThat(s.status()).isEqualTo("PENDING");
        assertThat(s.terminalReason()).isNull();
        assertThat(s.terminalAt()).isNull();
        assertThat(s.customerId()).isEqualTo(customerId);
        assertThat(s.venueId()).isEqualTo(b2.venueId());
        assertThat(s.history()).singleElement().satisfies(row -> {
            assertThat(row.toStatus()).isEqualTo("PENDING");
            assertThat(row.terminalReason()).isNull();
            assertThat(row.correlationId()).isEqualTo(creationCorrelation);
        });
        assertThat(s.reservations()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo(b2.reservationId());
            assertThat(r.type()).isEqualTo("BOOKING");
            assertThat(r.active()).isTrue();
            assertThat(r.releasedAt()).isNull();
        });
        assertThat(s.refunds()).isEmpty();
        assertThat(s.t1()).isEqualTo("PENDING");
        assertThat(s.events()).containsExactlyInAnyOrder(
                new Event(IntegrationEvents.RESERVATION_HELD, b2.reservationId(), creationCorrelation),
                new Event(IntegrationEvents.BOOKING_CREATED, b2.bookingId(), creationCorrelation));
    }

    private static void assertWaitsInBeginCommand(Waiter waiter, int holderPid, String relation) {
        assertThat(waiter.blockedBy()).as("backend %s waits only behind %s", waiter.pid(), holderPid)
                .containsExactly(holderPid);
        assertThat(waiter.waitEventType()).isEqualTo("Lock");
        assertThat(waiter.query()).contains("tournament.begin_command");
        assertThat(tupleLocks(waiter.pid())).as("the row backend %s waits for", waiter.pid())
                .containsExactly(relation);
    }

    private static Waiter single(List<Waiter> waiting) {
        assertThat(waiting).as("exactly one backend waiting").hasSize(1);
        return waiting.get(0);
    }

    // ================================================================ the command and its observers

    /** O5 as a command: counts {@link #scope()} calls and records each body run's attempt and backend. */
    static final class O5 implements Command<Instant> {

        private final CommandScope scope;
        private final Consumer<CommandContext> cancel;
        private final AtomicInteger scopeCalls = new AtomicInteger();
        private final List<Integer> runs = new CopyOnWriteArrayList<>();
        private final AtomicInteger cancelledRuns = new AtomicInteger();
        private volatile int pid;

        O5(CommandScope scope, Consumer<CommandContext> cancel) {
            this.scope = scope;
            this.cancel = cancel;
        }

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            scopeCalls.incrementAndGet();
            return scope;
        }

        @Override
        public Instant execute(CommandContext context) {
            runs.add(context.attempt());
            pid = LockWaits.backendPid(context);
            cancel.accept(context);
            cancelledRuns.incrementAndGet();
            return context.commandNow();
        }

        /** The scope it was built with, read without counting a call. */
        CommandScope named() {
            return scope;
        }

        int scopeCalls() {
            return scopeCalls.get();
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
    }

    /** Which rows another connection found locked (NOWAIT), from inside one body run. */
    record Locked(int attempt, boolean customer, boolean b2, boolean r2) {
    }

    /**
     * Whether {@code table}'s row {@code id} is locked by another transaction: a separate owner connection tries
     * {@code SELECT ... FOR UPDATE NOWAIT} and rolls back at once. NOWAIT never waits, so it cannot block O5.
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
     * TEST-ONLY: an ordinary row lock held by its own owner connection in an open transaction that writes nothing;
     * {@link #release} rolls it back.
     */
    private static final class RowLock implements AutoCloseable {

        private final Connection connection;
        private final int pid;
        private boolean released;

        private RowLock(Connection connection, int pid) {
            this.connection = connection;
            this.pid = pid;
        }

        static RowLock on(String table, UUID id) throws SQLException {
            Connection connection = owner();
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT id FROM " + table + " WHERE id = ? FOR UPDATE")) {
                    lock.setObject(1, id);
                    try (ResultSet rs = lock.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("no row " + id + " in " + table + " to hold");
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

    // ================================================================ committed reads

    record History(UUID id, long sequence, Instant occurredAt, String fromStatus, String toStatus,
                   String terminalReason, String actorType, UUID actorId, UUID correlationId) {
    }

    record Reservation(UUID id, String type, boolean active, Instant releasedAt, String releaseReason) {
    }

    record Refund(UUID id, String treatment, String terminalReason, UUID userId, UUID historyId, UUID correlationId) {
    }

    record Event(String type, UUID aggregateId, UUID correlationId) {
    }

    /** Every committed fact of one booking either command writes. */
    record Snapshot(String status, String terminalReason, Instant terminalAt, String feeTreatment, UUID customerId,
                    UUID venueId, List<History> history, List<Reservation> reservations, List<Refund> refunds,
                    String t1, List<Event> events) {
    }

    private static Snapshot snapshot(UUID bookingId) {
        return OWNER.sql("""
                        SELECT status, terminal_reason, terminal_at, fee_treatment, customer_id, venue_id
                          FROM booking.booking WHERE id = :id""")
                .param("id", bookingId)
                .query((rs, n) -> new Snapshot(rs.getString("status"), rs.getString("terminal_reason"),
                        instant(rs.getObject("terminal_at", OffsetDateTime.class)), rs.getString("fee_treatment"),
                        rs.getObject("customer_id", UUID.class), rs.getObject("venue_id", UUID.class),
                        history(bookingId), reservations(bookingId), refunds(bookingId),
                        VenueAnswerDb.job(t1(bookingId)).map(VenueAnswerDb.JobRow::status).orElse(null),
                        events(bookingId)))
                .single();
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

    /** Committed rows carrying O5's correlation: booking history, Booking Fee decisions and outbox events. */
    private static long writesOf(UUID correlationId) {
        return OWNER.sql("""
                        SELECT (SELECT count(*) FROM booking.booking_history WHERE correlation_id = :c)
                             + (SELECT count(*) FROM loyalty.booking_fee_refund WHERE correlation_id = :c)
                             + (SELECT count(*) FROM technical.outbox_event WHERE correlation_id = :c)""")
                .param("c", correlationId).query(Long.class).single();
    }

    private static long bookingsOf(UUID customerId) {
        return OWNER.sql("SELECT count(*) FROM booking.booking WHERE customer_id = :id")
                .param("id", customerId).query(Long.class).single();
    }

    /** B2, read once it committed: B2's Customer's one booking in R2. */
    private static UUID b2Of(Fixture f) {
        return OWNER.sql("SELECT id FROM booking.booking WHERE customer_id = :c AND resource_id = :r")
                .param("c", f.b2Customer()).param("r", f.r2().id()).query(UUID.class).single();
    }

    /** Relations {@code pid} holds a write lock on - a row it inserted, updated or deleted takes one. */
    private static List<String> relationsWritten(int pid) {
        return OWNER.sql("""
                        SELECT DISTINCT c.relnamespace::regnamespace || '.' || c.relname
                          FROM pg_locks l
                          JOIN pg_class c ON c.oid = l.relation
                         WHERE l.pid = :pid AND l.mode = 'RowExclusiveLock' AND c.relkind = 'r'
                         ORDER BY 1""")
                .param("pid", pid).query(String.class).list();
    }

    /** The tables of the tuple locks {@code pid} holds: the row a row-lock waiter queues for. */
    private static List<String> tupleLocks(int pid) {
        return OWNER.sql("""
                        SELECT c.relnamespace::regnamespace || '.' || c.relname
                          FROM pg_locks l
                          JOIN pg_class c ON c.oid = l.relation
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

    /** The racer's own exception; fails if it returned or did not end in time. */
    private static Throwable failureOf(Future<?> racer) throws InterruptedException {
        Object value;
        try {
            value = racer.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            throw new AssertionError("the racer did not end within " + TIMEOUT, e);
        }
        throw new AssertionError("expected a failure, got " + value);
    }

    /** The driver's exception for the server's error, from the cause chain. */
    private static PSQLException databaseError(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof PSQLException psql) {
                return psql;
            }
        }
        throw new AssertionError("no PostgreSQL error in the cause chain", failure);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
