package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.booking.internal.BookingStates.Plant;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.error.DatabaseErrorCodes;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * E7-58 D1 (PO decision 12 Oct 2026; FR-LCF-15/16, FR-BOK-03, FR-BOK-04): {@code V202610131300} exempts
 * AWAITING_CUSTOMER_CONFIRMATION from {@code ex_customer_no_overlap}, so a Late Confirm may wait on the customer
 * despite a schedule conflict. An AWAITING booking keeps its {@code commitment_period} and still counts towards the
 * 4-commitment limit, and a new Online Booking overlapping it is still refused - by the creation predicate under the
 * {@code iam.user_commitment_guard} lock, since the constraint no longer covers that pair. Every other overlapping pair
 * of counted commitments stays refused by the constraint.
 *
 * <p>The Late Confirm commands are not built yet (E7-58 later phase), so an AWAITING booking is planted
 * ({@link BookingStates}) or, where the real triggers matter, moved there in a transaction that is rolled back before
 * the deferred Late Confirm integrity checks run.
 */
class LateConfirmAwaitingOverlapTest extends IntegrationTest {

    @Autowired
    OnlineBookingCreation creation;

    private CorrelationId.Scope correlation;

    @BeforeEach
    void openCorrelation() {
        correlation = CorrelationId.open(UUID.randomUUID());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ---------------------------------------------------------------- the constraint (D1 migration)

    @Test
    void theDatabaseLetsAnAwaitingBookingOverlapAnotherCommitmentAndItKeepsItsCommitmentPeriod() throws SQLException {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        UUID lateConfirmed = expiredBooking(world, world.room, start, 120);
        creation.create(world.request(world.customer.id(), second, start.plus(Duration.ofMinutes(30)), 60));

        try (Connection db = rolledBackTransaction()) {
            // the real BEFORE triggers run (tr_00 derives the period); the deferred Late Confirm checks never do
            update(db, "UPDATE booking.booking SET status = 'AWAITING_CUSTOMER_CONFIRMATION' WHERE id = ?",
                    lateConfirmed);

            try (PreparedStatement read = db.prepareStatement("""
                    SELECT status, commitment_period = tstzrange(start_at, end_at, '[)') AS kept
                      FROM booking.booking WHERE id = ?""")) {
                read.setObject(1, lateConfirmed);
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("status")).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
                    assertThat(row.getBoolean("kept")).as("AWAITING keeps its commitment_period").isTrue();
                }
            }
            db.rollback();
        }
    }

    @Test
    void anAcceptIntoAnOverlapIsStillRefusedByTheDatabase() throws SQLException {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        UUID lateConfirmed = expiredBooking(world, world.room, start, 120);
        creation.create(world.request(world.customer.id(), second, start.plus(Duration.ofMinutes(30)), 60));

        try (Connection db = rolledBackTransaction()) {
            update(db, "UPDATE booking.booking SET status = 'AWAITING_CUSTOMER_CONFIRMATION' WHERE id = ?",
                    lateConfirmed);

            // AWAITING -> CONFIRMED puts the row back under the predicate: the database stays the backstop of Accept
            assertThatThrownBy(() -> update(db, "UPDATE booking.booking SET status = 'CONFIRMED' WHERE id = ?",
                    lateConfirmed))
                    .isInstanceOfSatisfying(SQLException.class, e -> {
                        assertThat(e.getSQLState()).isEqualTo(DatabaseErrorCodes.EXCLUSION_VIOLATION);
                        assertThat(e.getMessage()).contains("ex_customer_no_overlap");
                    });
            db.rollback();
        }
    }

    @Test
    void twoOverlappingCountedCommitmentsAreStillRefusedByTheDatabase() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        UUID first = expiredBooking(world, world.room, start, 120);
        creation.create(world.request(world.customer.id(), second, start.plus(Duration.ofMinutes(30)), 60));

        assertThatThrownBy(() -> BookingStates.plant(first, Plant.of("CONFIRMED")))
                .satisfies(e -> assertThat(DatabaseErrorCodes.translate(e)).contains(ApiErrorCode.OVERLAPPING_COMMITMENT));
        assertThat(VenueAnswerDb.booking(first).status()).isEqualTo("VENUE_RESPONSE_EXPIRED");
    }

    @Test
    void anAwaitingBookingOverlappingACommitmentCanBePlantedWhichV1Refused() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        UUID lateConfirmed = expiredBooking(world, world.room, start, 120);
        creation.create(world.request(world.customer.id(), second, start.plus(Duration.ofMinutes(30)), 60));

        BookingStates.plant(lateConfirmed, Plant.of("AWAITING_CUSTOMER_CONFIRMATION").reopenedAt(OnlineBookingWorld.dbNow()));

        assertThat(VenueAnswerDb.booking(lateConfirmed).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(VenueAnswerDb.booking(lateConfirmed).counted()).isTrue();
    }

    // ---------------------------------------------------------------- creation against an AWAITING booking (FR-BOK-03/04)

    @Test
    void anAwaitingBookingStillCountsTowardsTheFourCommitmentLimit() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Instant start = world.start();
        awaitingBooking(world, world.room, start, 60);
        for (int i = 1; i <= 3; i++) {
            creation.create(world.request(start.plus(Duration.ofHours(2L * i)), 60));
        }

        assertRefused(() -> creation.create(world.request(start.plus(Duration.ofHours(8)), 60)),
                OnlineCreationCondition.BELOW_COMMITMENT_LIMIT);
    }

    @Test
    void aNewOnlineBookingOverlappingAnAwaitingOneIsRefusedByTheCreationPredicate() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        awaitingBooking(world, world.room, start, 120);

        // FR-BOK-04 (O1 condition 4): refused before any write; the constraint alone would accept this pair
        assertRefused(() -> creation.create(world.request(world.customer.id(), second,
                start.plus(Duration.ofMinutes(60)), 120)), OnlineCreationCondition.NO_OVERLAPPING_COMMITMENT);
        assertRefused(() -> creation.create(world.request(world.customer.id(), second,
                start.minus(Duration.ofMinutes(30)), 60)), OnlineCreationCondition.NO_OVERLAPPING_COMMITMENT);
        assertThat(VenueAnswerDb.count("booking.booking WHERE resource_id = :id", second.id())).isZero();
    }

    @Test
    void aNewOnlineBookingBackToBackWithAnAwaitingOneIsAccepted() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        awaitingBooking(world, world.room, start, 120);

        CreatedBooking created = creation.create(world.request(world.customer.id(), second,
                start.plus(Duration.ofMinutes(120)), 60));

        assertThat(VenueAnswerDb.booking(created.bookingId()).status()).isEqualTo("PENDING");
    }

    @Test
    void anotherCustomerMayBookTheSameIntervalAsAnAwaitingBooking() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        awaitingBooking(world, world.room, start, 120);

        assertThat(creation.create(world.request(world.anotherCustomer(), second, start, 120))).isNotNull();
    }

    // ---------------------------------------------------------------- concurrency (the commitment guard lock)

    @Test
    void concurrentCreationsOverlappingAnAwaitingBookingAreAllRefused() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        List<Fixtures.Resource> rooms = List.of(world.anotherRoom(room -> room), world.anotherRoom(room -> room),
                world.anotherRoom(room -> room));
        Instant start = world.start();
        awaitingBooking(world, world.room, start, 120);

        List<Object> outcomes = race(List.of(
                () -> creation.create(world.request(world.customer.id(), rooms.get(0), start, 60)),
                () -> creation.create(world.request(world.customer.id(), rooms.get(1),
                        start.plus(Duration.ofMinutes(60)), 60)),
                () -> creation.create(world.request(world.customer.id(), rooms.get(2),
                        start.plus(Duration.ofMinutes(90)), 60))));

        assertThat(outcomes).hasSize(3).allSatisfy(outcome -> assertThat(outcome)
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.OVERLAPPING_COMMITMENT)));
        assertThat(VenueAnswerDb.count("booking.booking WHERE customer_id = :id", world.customer.id())).isEqualTo(1);
    }

    @Test
    void concurrentCreationsCountTheAwaitingBookingUnderTheCommitmentGuard() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        Fixtures.Resource second = world.anotherRoom(room -> room);
        Instant start = world.start();
        awaitingBooking(world, world.room, start, 60);
        creation.create(world.request(start.plus(Duration.ofHours(2)), 60));
        creation.create(world.request(start.plus(Duration.ofHours(4)), 60));

        List<Object> outcomes = race(List.of(
                () -> creation.create(world.request(start.plus(Duration.ofHours(6)), 60)),
                () -> creation.create(world.request(world.customer.id(), second, start.plus(Duration.ofHours(8)), 60))));

        assertThat(outcomes).filteredOn(CreatedBooking.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ApiException.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ApiErrorCode.COMMITMENT_LIMIT_REACHED));
        assertThat(OWNER.sql("SELECT count(*) FROM booking.booking WHERE customer_id = :id "
                        + "AND commitment_period IS NOT NULL")
                .param("id", world.customer.id()).query(Long.class).single()).isEqualTo(4);
    }

    // ---------------------------------------------------------------- helpers

    /** A real Online Booking, then planted VENUE_RESPONSE_EXPIRED: no longer counted, its room released. */
    private UUID expiredBooking(OnlineBookingWorld world, Fixtures.Resource room, Instant start, int minutes) {
        UUID id = creation.create(world.request(world.customer.id(), room, start, minutes)).bookingId();
        BookingStates.plant(id, Plant.of("VENUE_RESPONSE_EXPIRED").expiredAt(OnlineBookingWorld.dbNow()));
        return id;
    }

    /** A real Online Booking, then planted AWAITING_CUSTOMER_CONFIRMATION through VENUE_RESPONSE_EXPIRED. */
    private UUID awaitingBooking(OnlineBookingWorld world, Fixtures.Resource room, Instant start, int minutes) {
        UUID id = creation.create(world.request(world.customer.id(), room, start, minutes)).bookingId();
        Instant now = OnlineBookingWorld.dbNow();
        BookingStates.plant(id, Plant.of("AWAITING_CUSTOMER_CONFIRMATION").expiredAt(now).reopenedAt(now));
        VenueAnswerDb.BookingRow row = VenueAnswerDb.booking(id);
        assertThat(row.status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(row.counted()).isTrue();
        return id;
    }

    /** The database owner on one connection, every trigger on, in a transaction the caller rolls back. */
    private static Connection rolledBackTransaction() throws SQLException {
        Connection db = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR);
        db.setAutoCommit(false);
        return db;
    }

    private static void update(Connection db, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            statement.setObject(1, id);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    /** Starts every task at once; each outcome is the task's result or the exception it threw. */
    private static List<Object> race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    try (CorrelationId.Scope ignored = CorrelationId.open(UUID.randomUUID())) {
                        return task.call();
                    } catch (ApiException refused) {
                        return refused;
                    }
                }));
            }
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertRefused(ThrowingCallable call, OnlineCreationCondition condition) {
        assertThatThrownBy(call).isInstanceOfSatisfying(CreationRefused.class, refused -> {
            assertThat(refused.condition()).isEqualTo(condition);
            assertThat(refused.code()).isEqualTo(condition.code());
        });
    }
}
