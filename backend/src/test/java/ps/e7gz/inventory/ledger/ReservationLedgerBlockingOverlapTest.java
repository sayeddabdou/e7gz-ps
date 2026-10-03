package ps.e7gz.inventory.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.BLOCKING_RESOURCE;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.BOOKING;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.GUARD_CLOSURE;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.GUARD_MAINTENANCE;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.GUARD_ROWS_RESOURCE;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.OPERATING_SESSION;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.VENUE;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-30 - {@link ReservationLedger#hasBlockingOverlap} (check 8 of the availability predicate) against the migrated
 * database: active commitment rows only, half-open planned intervals, on the command's own transaction.
 */
class ReservationLedgerBlockingOverlapTest extends IntegrationTest {

    @Autowired
    CommandRunner runner;

    @Autowired
    ReservationLedger ledger;

    record TestCommand<R>(CommandScope scope, Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    static final class RollBack extends RuntimeException {

        RollBack() {
            super("roll back on purpose", null, false, false);
        }
    }

    @BeforeEach
    void fixtures() {
        ReservationLedgerFixtures.load();
    }

    private <R> R run(LockSet locks, Function<CommandContext, R> body) {
        return runner.run(new TestCommand<>(CommandScope.shared(locks), body));
    }

    private <R> R run(Function<CommandContext, R> body) {
        return run(LockSet.empty(), body);
    }

    private static Instant at(String utc) {
        return Instant.parse(utc);
    }

    private boolean overlaps(UUID resource, String start, String end) {
        return run(ctx -> ledger.hasBlockingOverlap(ctx, resource, at(start), at(end)));
    }

    private UUID create(ReservationOwner owner, String start, String end) {
        return run(ctx -> ledger.create(ctx, new NewReservation(BLOCKING_RESOURCE, owner, at(start), at(end))));
    }

    @Test
    void anActiveBookingBlocksOnlyItsResourceInsideItsHalfOpenInterval() {
        create(ReservationOwner.booking(BOOKING), "2030-01-14T08:00:00Z", "2030-01-14T09:00:00Z");

        assertThat(overlaps(BLOCKING_RESOURCE, "2030-01-14T08:30:00Z", "2030-01-14T08:45:00Z")).isTrue();
        assertThat(overlaps(BLOCKING_RESOURCE, "2030-01-14T08:59:00Z", "2030-01-14T10:00:00Z")).isTrue();
        assertThat(overlaps(BLOCKING_RESOURCE, "2030-01-14T07:00:00Z", "2030-01-14T08:00:00Z"))
                .as("back-to-back before").isFalse();
        assertThat(overlaps(BLOCKING_RESOURCE, "2030-01-14T09:00:00Z", "2030-01-14T10:00:00Z"))
                .as("back-to-back after").isFalse();
        assertThat(overlaps(GUARD_ROWS_RESOURCE, "2030-01-14T08:00:00Z", "2030-01-14T09:00:00Z"))
                .as("another Resource").isFalse();
    }

    @Test
    void anActiveLateConfirmHoldBlocks() {
        create(ReservationOwner.lateConfirmHold(BOOKING), "2030-01-15T08:00:00Z", "2030-01-15T09:00:00Z");

        assertThat(overlaps(BLOCKING_RESOURCE, "2030-01-15T08:15:00Z", "2030-01-15T08:30:00Z")).isTrue();
    }

    @Test
    void aReleasedRowNoLongerBlocks() {
        UUID noShow = create(ReservationOwner.booking(BOOKING), "2030-01-16T08:00:00Z", "2030-01-16T09:00:00Z");
        assertThat(overlaps(BLOCKING_RESOURCE, "2030-01-16T08:20:00Z", "2030-01-16T09:00:00Z")).isTrue();

        Boolean released = run(ctx -> ledger.release(ctx, noShow, at("2030-01-16T08:16:00Z"), "E7-30 test: no-show"));

        assertThat(released).isTrue();
        assertThat(overlaps(BLOCKING_RESOURCE, "2030-01-16T08:00:00Z", "2030-01-16T09:00:00Z"))
                .as("released: its planned interval is history, not occupancy").isFalse();
    }

    @Test
    void tournamentApprovalAndStandardHoldsBlockAndAreSeenInsideTheirOwnCommand() {
        // As in the E7-29 owner-shape test: a Tournament is checked as a whole at commit, so it and its holds are
        // written in one command, read inside that transaction (which is the point: the read uses ctx.jdbc()), and
        // rolled back.
        UUID tournament = UUID.fromString("e7290000-0000-4000-8000-000000000502");
        LockSet locks = LockSet.builder().venues(VENUE).resources(BLOCKING_RESOURCE).newTournaments(tournament)
                .build();
        Instant start = at("2030-02-01T16:00:00Z");
        Instant end = at("2030-02-01T17:00:00Z");

        assertThatThrownBy(() -> run(locks, ctx -> {
            ctx.jdbc().sql("""
                    INSERT INTO tournament.tournament (id, venue_id, host_type, activity, play_mode, status, capacity,
                                                       created_at, start_at, end_at, nominal_minutes, checkpoint_at,
                                                       visibility, venue_cost_total, creation_fee_snapshot, entry_fee,
                                                       prize, business_date, operating_session_id)
                    VALUES (:id, :venue, 'VENUE', 'PLAYSTATION', 'SINGLE', 'WAITING_FOR_PLAYERS', 8,
                            :start, :start, :end, 60, :start, 'PUBLIC', 0, 0, 0, 0, DATE '2030-02-01', :session)
                    """)
                    .param("id", tournament).param("venue", VENUE).param("session", OPERATING_SESSION)
                    .param("start", OffsetDateTime.parse("2030-02-01T18:00:00+02:00"))
                    .param("end", OffsetDateTime.parse("2030-02-01T19:00:00+02:00"))
                    .update();
            assertThat(ledger.hasBlockingOverlap(ctx, BLOCKING_RESOURCE, start, end)).as("nothing yet").isFalse();

            UUID approval = ledger.create(ctx, new NewReservation(BLOCKING_RESOURCE,
                    ReservationOwner.tournamentApprovalHold(tournament), start, end));
            assertThat(ledger.hasBlockingOverlap(ctx, BLOCKING_RESOURCE, start, end))
                    .as("TOURNAMENT_APPROVAL_HOLD blocks").isTrue();

            ledger.release(ctx, approval, ctx.commandNow(), "E7-30 test: approved");
            assertThat(ledger.hasBlockingOverlap(ctx, BLOCKING_RESOURCE, start, end))
                    .as("released approval hold").isFalse();

            ledger.create(ctx, new NewReservation(BLOCKING_RESOURCE, ReservationOwner.tournamentHold(tournament),
                    start, end));
            assertThat(ledger.hasBlockingOverlap(ctx, BLOCKING_RESOURCE, start, end))
                    .as("TOURNAMENT_HOLD blocks").isTrue();
            throw new RollBack();
        })).isInstanceOf(RollBack.class);

        assertThat(overlaps(BLOCKING_RESOURCE, "2030-02-01T16:00:00Z", "2030-02-01T17:00:00Z")).as("rolled back")
                .isFalse();
    }

    @Test
    void activeMaintenanceAndClosureGuardRowsAreNotCounted() {
        assertThatThrownBy(() -> run(ctx -> {
            ledger.create(ctx, new NewReservation(GUARD_ROWS_RESOURCE, ReservationOwner.maintenance(GUARD_MAINTENANCE),
                    at("2030-01-12T08:00:00Z"), at("2030-01-12T09:00:00Z")));
            ledger.create(ctx, new NewReservation(GUARD_ROWS_RESOURCE, ReservationOwner.closure(GUARD_CLOSURE),
                    at("2030-01-13T08:00:00Z"), at("2030-01-13T09:00:00Z")));
            Integer active = ctx.jdbc().sql("""
                    SELECT count(*) FROM inventory.resource_reservation
                     WHERE resource_id = :r AND active_flag AND reservation_type IN ('MAINTENANCE', 'CLOSURE')
                    """).param("r", GUARD_ROWS_RESOURCE).query(Integer.class).single();
            assertThat(active).as("both guard rows are active").isEqualTo(2);

            assertThat(ledger.hasBlockingOverlap(ctx, GUARD_ROWS_RESOURCE, at("2030-01-12T08:00:00Z"),
                    at("2030-01-12T09:00:00Z"))).as("MAINTENANCE is check 6, not check 8").isFalse();
            assertThat(ledger.hasBlockingOverlap(ctx, GUARD_ROWS_RESOURCE, at("2030-01-13T08:00:00Z"),
                    at("2030-01-13T09:00:00Z"))).as("CLOSURE is check 7, not check 8").isFalse();
            throw new RollBack();
        })).isInstanceOf(RollBack.class);
    }

    @Test
    void anEmptyOrReversedPeriodIsRefused() {
        assertThatThrownBy(() -> overlaps(BLOCKING_RESOURCE, "2030-01-14T08:00:00Z", "2030-01-14T08:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> overlaps(BLOCKING_RESOURCE, "2030-01-14T09:00:00Z", "2030-01-14T08:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
