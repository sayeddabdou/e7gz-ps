package ps.e7gz.inventory.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.inventory.E732Fixtures.RACE_RESOURCE;
import static ps.e7gz.inventory.E732Fixtures.VENUE;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.concurrency.ConcurrentRace;
import ps.e7gz.inventory.E732Fixtures;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.inventory.ledger.api.SlotTaken;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-32 - N BOOKING attempts for the same slot (one Resource, one identical planned interval), raced through
 * {@link ConcurrentRace}, each in its own {@link CommandRunner} transaction against the real
 * {@code ex_no_double_booking} / cross-class guard (arch 14 section 10, NFR-28, ADR-003). Each race is repeated, every repetition on a fresh day of the race Resource:
 * <ul>
 *   <li>{@code databaseAlone...}: no application lock; all N racers are released together from inside their open
 *       transactions, so only the database orders them.</li>
 *   <li>{@code productionShaped...}: the Resource lock declared as a production commitment command does, and each
 *       racer writes its own owner Booking first, so a loser's rollback must leave no owner row behind.</li>
 * </ul>
 *
 * <p>No {@code @Transactional} here: every racer's command opens its own transaction (CommandRunner refuses to join
 * one), and the final assertions read committed rows on a separate connection.
 */
class ReservationLedgerRaceTest extends IntegrationTest {

    /** Racers per race. The app pool must hold all N open command transactions at once (checked below). */
    static final int RACERS = 8;
    static final int REPETITIONS = 5;
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    CommandRunner runner;

    @Autowired
    ReservationLedger ledger;

    @Autowired
    DataSource appDataSource;

    record RaceCommand<R>(LockSet lockSet, Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(lockSet);
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    /** What a racer saw inside its transaction. */
    record Inside(int backendPid, long transactionId, UUID bookingId) {
    }

    record Won(UUID bookingId, UUID reservationId) {
    }

    @BeforeEach
    void fixtures() {
        E732Fixtures.load();
        int pool = ((HikariDataSource) appDataSource).getMaximumPoolSize();
        assertThat(RACERS).as("the app pool (%d) holds all %d racers' transactions at once, plus one spare", pool,
                RACERS).isLessThan(pool);
    }

    @RepeatedTest(REPETITIONS)
    void databaseAloneLetsExactlyOneOfNBookingsForTheSameSlotCommit(RepetitionInfo repetition) {
        Instant base = raceStart(repetition, 0);
        Map<Integer, Inside> inside = new ConcurrentHashMap<>();

        // TEST-ONLY: LockSet.empty(), as in the E7-29 two-racer test. Declared, the Resource lock would serialise the
        // racers in begin_command before the start line. Leaving it out proves the database invariant is
        // authoritative on its own. The racers share the fixture owner Booking: with no Resource lock, a racer that
        // inserted its own Booking first (FK -> KEY SHARE on the Resource) would deadlock with the guard's
        // FOR UPDATE of the same Resource row (40P01) - the reason production commands declare the lock (ADR-003).
        // Each racer is its own request, with its own correlation ID (ReservationHeld carries it; E7-46).
        ConcurrentRace.Result<Won> race = ConcurrentRace.run(RACERS, TIMEOUT, (racer, start) -> {
            try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
                return runner.run(new RaceCommand<>(LockSet.empty(), ctx -> {
                    inside.put(racer, inside(ctx, E732Fixtures.BOOKING));
                    // Every racer is inside its own open transaction, past begin_command, when released.
                    start.await();
                    return new Won(E732Fixtures.BOOKING, ledger.create(ctx, booking(base, E732Fixtures.BOOKING)));
                }));
            }
        });

        // Separate real transactions and connections: N distinct backends and transaction ids, all open at once
        // (the start line opened only when all N were inside).
        assertThat(inside.values()).extracting(Inside::backendPid).as("one connection per racer")
                .hasSize(RACERS).doesNotHaveDuplicates();
        assertThat(inside.values()).extracting(Inside::transactionId).as("one transaction per racer")
                .doesNotHaveDuplicates();

        assertExactlyOneWinner(race, base);
    }

    @RepeatedTest(REPETITIONS)
    void productionShapedRaceCommitsOneBookingAndLeavesNoLoserOwnerBehind(RepetitionInfo repetition) {
        Instant base = raceStart(repetition, 10);
        Map<Integer, Inside> inside = new ConcurrentHashMap<>();
        LockSet locks = LockSet.builder().venues(VENUE).resources(RACE_RESOURCE).build();

        // Released together at the command boundary; begin_command then orders them on the Resource lock.
        ConcurrentRace.Result<Won> race = ConcurrentRace.run(RACERS, TIMEOUT, (racer, start) -> {
            start.await();
            try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
                return runner.run(new RaceCommand<>(locks, ctx -> {
                    UUID owner = UUID.randomUUID();
                    inside.put(racer, inside(ctx, owner));
                    insertOwner(ctx, owner, base);
                    return new Won(owner, ledger.create(ctx, booking(base, owner)));
                }));
            }
        });

        assertThat(inside.values()).extracting(Inside::transactionId).as("one transaction per racer")
                .hasSize(RACERS).doesNotHaveDuplicates();

        Won winner = assertExactlyOneWinner(race, base);

        List<UUID> racedOwners = inside.values().stream().map(Inside::bookingId).toList();
        List<UUID> committedOwners = JdbcClient.create(appDataSource)
                .sql("SELECT id FROM booking.booking WHERE id IN (:ids)").param("ids", racedOwners)
                .query(UUID.class).list();
        assertThat(committedOwners).as("no loser left its owner Booking behind").containsExactly(winner.bookingId());
    }

    /** 1 winner, N - 1 typed SlotTaken, and the committed rows agree. */
    private Won assertExactlyOneWinner(ConcurrentRace.Result<Won> race, Instant base) {
        assertThat(race.threadsUsed()).isEqualTo(RACERS);
        assertThat(race.values()).as("exactly one racer commits").hasSize(1);
        assertThat(race.failures()).as("every other racer fails").hasSize(RACERS - 1);
        assertThat(race.failures()).allSatisfy(failure -> {
            assertThat(failure).isExactlyInstanceOf(SlotTaken.class);
            assertThat(((SlotTaken) failure).code()).isEqualTo(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);
        });
        Won winner = race.values().get(0);

        JdbcClient db = JdbcClient.create(appDataSource);
        List<Map<String, Object>> rows = db.sql("""
                        SELECT id, booking_id, reservation_type, active_flag
                          FROM inventory.resource_reservation
                         WHERE resource_id = :resource AND planned_range && tstzrange(:from, :to, '[)')
                        """)
                .param("resource", RACE_RESOURCE)
                .param("from", utc(base.minus(Duration.ofHours(1))))
                .param("to", utc(base.plus(Duration.ofHours(3))))
                .query().listOfRows();
        assertThat(rows).as("only the winner's reservation row exists for the raced slot; no loser left one").hasSize(1);
        assertThat(rows.get(0)).containsEntry("id", winner.reservationId())
                .containsEntry("booking_id", winner.bookingId())
                .containsEntry("reservation_type", "BOOKING")
                .containsEntry("active_flag", true);

        Integer overlappingPairs = db.sql("""
                        SELECT count(*)
                          FROM inventory.resource_reservation a
                          JOIN inventory.resource_reservation b ON b.resource_id = a.resource_id AND a.id < b.id
                         WHERE a.resource_id = :resource
                           AND a.active_flag AND b.active_flag
                           AND a.reservation_type = 'BOOKING' AND b.reservation_type = 'BOOKING'
                           AND a.planned_range && b.planned_range
                        """)
                .param("resource", RACE_RESOURCE).query(Integer.class).single();
        assertThat(overlappingPairs).as("no overlapping pair of committed BOOKING rows on the Resource").isZero();
        return winner;
    }

    /** 18:00 UTC on a day of its own per test method and repetition. */
    private static Instant raceStart(RepetitionInfo repetition, int dayOffset) {
        return Instant.parse("2031-02-01T18:00:00Z")
                .plus(Duration.ofDays(dayOffset + repetition.getCurrentRepetition()));
    }

    /** The one raced slot: every racer asks for the same Resource and the same [base, base + 1 h). */
    private static NewReservation booking(Instant base, UUID owner) {
        return new NewReservation(RACE_RESOURCE, ReservationOwner.booking(owner), base, base.plus(Duration.ofHours(1)));
    }

    private static Inside inside(CommandContext ctx, UUID owner) {
        return new Inside(ctx.jdbc().sql("SELECT pg_backend_pid()").query(Integer.class).single(),
                ctx.jdbc().sql("SELECT txid_current()").query(Long.class).single(), owner);
    }

    /** The racer's own owner Booking, in its transaction: rolled back with it when the racer loses. */
    private static void insertOwner(CommandContext ctx, UUID owner, Instant base) {
        NewReservation slot = booking(base, owner);
        ctx.jdbc().sql("""
                        INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity,
                                                     console_type, play_mode, resource_name_snapshot, start_at, end_at,
                                                     nominal_booked_minutes, hourly_price_snapshot, venue_price_total,
                                                     booking_fee_snapshot, total_amount, business_date,
                                                     session_timezone, created_at)
                        VALUES (:id, :venue, :resource, NULL, 'MANUAL', 'CONFIRMED', 'PLAYSTATION', 'PS5', 'SINGLE',
                                'E7-32 race room', :from, :to, 60, 100.00, 100.00, 0.00, 100.00, :day,
                                'Africa/Cairo', :now)
                        """)
                .param("id", owner).param("venue", VENUE).param("resource", RACE_RESOURCE)
                .param("from", utc(slot.plannedStart())).param("to", utc(slot.plannedEnd()))
                .param("day", slot.plannedStart().atOffset(ZoneOffset.UTC).toLocalDate())
                .param("now", utc(ctx.commandNow()))
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
