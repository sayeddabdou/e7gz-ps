package ps.e7gz.inventory.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.BOOKING;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.CLOSURE;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.MAINTENANCE;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.NO_SHOW_RESOURCE;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.ONE_WAY_RESOURCE;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.OPERATING_SESSION;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.OWNER_SHAPE_RESOURCE;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.RACE_RESOURCE;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.TOURNAMENT_RESOURCE;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.TRANSLATION_RESOURCE;
import static ps.e7gz.inventory.ledger.ReservationLedgerFixtures.VENUE;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.ReservationType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.DatabaseErrorCodes;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.inventory.ledger.api.SlotTaken;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-29 - the Reservation Ledger inside real commands, against the migrated database and its real
 * {@code ex_no_double_booking} / cross-class guard (arch 05 section 3, ADR-003). Owner rows come from SQL fixtures.
 */
class ReservationLedgerIntegrationTest extends IntegrationTest {

    @Autowired
    CommandRunner runner;

    @Autowired
    ReservationLedger ledger;

    @Autowired
    DataSource appDataSource;

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

    /** Rolls a command back on purpose after its assertions ran inside the transaction. */
    static final class RollBack extends RuntimeException {

        RollBack() {
            super("roll back on purpose", null, false, false);
        }
    }

    record Row(UUID id, UUID resourceId, String reservationType, UUID bookingId, UUID tournamentId, UUID maintenanceId,
               UUID closureId, Instant plannedStart, Instant plannedEnd, boolean plannedRangeIsStartToEnd,
               Instant actualReservationStart, Instant releaseAt, String releaseReason, boolean active) {
    }

    private static final String SELECT_ROW = """
            SELECT id, resource_id, reservation_type, booking_id, tournament_id, maintenance_id, closure_id,
                   planned_start, planned_end, planned_range = tstzrange(planned_start, planned_end, '[)') AS range_ok,
                   actual_reservation_start, actual_reservation_release_at, release_reason, active_flag
              FROM inventory.resource_reservation
            """;

    /** After the context started, so Flyway has migrated the shared database; loads once per JVM. */
    /** ReservationLedger.create publishes ReservationHeld, which carries the request's correlation ID (E7-46). */
    private CorrelationId.Scope correlation;

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @BeforeEach
    void fixtures() {
        correlation = CorrelationId.open(CorrelationId.generate());
        ReservationLedgerFixtures.load();
    }

    private <R> R run(LockSet lockSet, Function<CommandContext, R> body) {
        return runner.run(new TestCommand<>(CommandScope.shared(lockSet), body));
    }

    private <R> R run(Function<CommandContext, R> body) {
        return run(LockSet.empty(), body);
    }

    /** Releases in a command of its own. */
    private boolean release(UUID id, String releaseAt, String reason) {
        Boolean released = run(ctx -> ledger.release(ctx, id, at(releaseAt), reason));
        return released;
    }

    private static Instant at(String utc) {
        return Instant.parse(utc);
    }

    private static NewReservation booking(UUID resource, String start, String end) {
        return new NewReservation(resource, ReservationOwner.booking(BOOKING), at(start), at(end));
    }

    private static Row row(JdbcClient jdbc, UUID id) {
        return jdbc.sql(SELECT_ROW + " WHERE id = :id").param("id", id).query(ReservationLedgerIntegrationTest::map)
                .single();
    }

    private List<Row> rowsOn(UUID resource) {
        return JdbcClient.create(appDataSource).sql(SELECT_ROW + " WHERE resource_id = :r ORDER BY planned_start, id")
                .param("r", resource).query(ReservationLedgerIntegrationTest::map).list();
    }

    private Row committedRow(UUID id) {
        return row(JdbcClient.create(appDataSource), id);
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("resource_id", UUID.class),
                rs.getString("reservation_type"), rs.getObject("booking_id", UUID.class),
                rs.getObject("tournament_id", UUID.class), rs.getObject("maintenance_id", UUID.class),
                rs.getObject("closure_id", UUID.class), instant(rs, "planned_start"), instant(rs, "planned_end"),
                rs.getBoolean("range_ok"), instant(rs, "actual_reservation_start"),
                instant(rs, "actual_reservation_release_at"), rs.getString("release_reason"),
                rs.getBoolean("active_flag"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    // ---- 1. concurrency: the database decides, first committer wins --------------------------------------------------

    @Test
    void twoConcurrentOverlappingBookingsCommitExactlyOneAndTheOtherFailsWithSlotTaken() throws Exception {
        NewReservation first = booking(RACE_RESOURCE, "2030-01-01T08:00:00Z", "2030-01-01T09:00:00Z");
        NewReservation second = booking(RACE_RESOURCE, "2030-01-01T08:30:00Z", "2030-01-01T09:30:00Z");
        // Both transactions are open, past begin_command, before either inserts.
        // TEST-ONLY: the commands run with LockSet.empty(), deliberately leaving out the Resource lock, which is only a
        // contention optimisation. Nothing in the application orders them, which proves the database invariant stays
        // authoritative on its own. Production commitment commands must still declare the Resource lock (ADR-003).
        CyclicBarrier bothInsideTheirTransactions = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<UUID>> results = new ArrayList<>();
        try {
            for (NewReservation reservation : List.of(first, second)) {
                results.add(executor.submit(() -> {
                    try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
                        return run(ctx -> {
                            await(bothInsideTheirTransactions);
                            return ledger.create(ctx, reservation);
                        });
                    }
                }));
            }
            List<UUID> committed = new ArrayList<>();
            List<Throwable> failed = new ArrayList<>();
            for (Future<UUID> result : results) {
                try {
                    committed.add(result.get(60, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    failed.add(e.getCause());
                }
            }

            assertThat(committed).as("exactly one transaction commits").hasSize(1);
            assertThat(failed).as("exactly one transaction fails").hasSize(1);
            assertThat(failed.get(0)).isExactlyInstanceOf(SlotTaken.class);
            assertThat(((SlotTaken) failed.get(0)).code()).isEqualTo(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);

            List<Row> rows = rowsOn(RACE_RESOURCE);
            assertThat(rows).as("the loser left no partial row").hasSize(1);
            assertThat(rows.get(0).id()).isEqualTo(committed.get(0));
            assertThat(rows.get(0).active()).isTrue();
            assertThat(rows.get(0).reservationType()).isEqualTo("BOOKING");
        } finally {
            executor.shutdownNow();
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("the two commands never met at the barrier", e);
        }
    }

    // ---- 2. EC-AVAI-004: the 8:16 case ------------------------------------------------------------------------------

    @Test
    void aReleasedRowNoLongerBlocksTheSlotAndKeepsItsPlannedInterval() {
        UUID noShow = run(ctx -> ledger.create(ctx,
                booking(NO_SHOW_RESOURCE, "2030-01-02T08:00:00Z", "2030-01-02T09:00:00Z")));
        NewReservation walkIn = booking(NO_SHOW_RESOURCE, "2030-01-02T08:20:00Z", "2030-01-02T09:00:00Z");

        assertThatThrownBy(() -> run(ctx -> ledger.create(ctx, walkIn)))
                .as("while the 08:00-09:00 row is active, 08:20-09:00 is taken").isExactlyInstanceOf(SlotTaken.class);

        boolean released = release(noShow, "2030-01-02T08:16:00Z", "E7-29 test: no-show");
        assertThat(released).isTrue();

        Row history = committedRow(noShow);
        assertThat(history.active()).isFalse();
        assertThat(history.releaseAt()).isEqualTo(at("2030-01-02T08:16:00Z"));
        assertThat(history.releaseReason()).isEqualTo("E7-29 test: no-show");
        assertThat(history.plannedStart()).as("planned_start unchanged").isEqualTo(at("2030-01-02T08:00:00Z"));
        assertThat(history.plannedEnd()).as("planned_end unchanged").isEqualTo(at("2030-01-02T09:00:00Z"));
        assertThat(history.plannedRangeIsStartToEnd()).as("planned_range still [08:00, 09:00)").isTrue();

        UUID next = run(ctx -> ledger.create(ctx, walkIn));

        assertThat(rowsOn(NO_SHOW_RESOURCE)).as("both historical rows survive")
                .extracting(Row::id, Row::active, Row::plannedStart, Row::plannedEnd)
                .containsExactly(
                        tuple(noShow, false, at("2030-01-02T08:00:00Z"),
                                at("2030-01-02T09:00:00Z")),
                        tuple(next, true, at("2030-01-02T08:20:00Z"),
                                at("2030-01-02T09:00:00Z")));
    }

    // ---- 3. release is one-way --------------------------------------------------------------------------------------

    @Test
    void aSecondReleaseChangesNothingAndTheRowIsNeverReactivated() {
        record Created(UUID id, Instant commandNow) {
        }
        Created created = run(ctx -> new Created(
                ledger.create(ctx, booking(ONE_WAY_RESOURCE, "2030-01-06T10:00:00Z", "2030-01-06T11:00:00Z")),
                ctx.commandNow()));
        UUID id = created.id();

        Row inserted = committedRow(id);
        assertThat(inserted.active()).isTrue();
        assertThat(inserted.releaseAt()).isNull();
        assertThat(inserted.releaseReason()).isNull();
        assertThat(inserted.actualReservationStart()).as("actual_reservation_start = command instant")
                .isEqualTo(created.commandNow());

        assertThat(release(id, "2030-01-06T10:05:00Z", "first release")).isTrue();
        assertThat(release(id, "2030-01-06T10:30:00Z", "second release"))
                .as("an already released row is not matched").isFalse();

        Row released = committedRow(id);
        assertThat(released.active()).as("never re-activated").isFalse();
        assertThat(released.releaseAt()).as("first release facts kept").isEqualTo(at("2030-01-06T10:05:00Z"));
        assertThat(released.releaseReason()).isEqualTo("first release");
        assertThat(released.resourceId()).isEqualTo(inserted.resourceId());
        assertThat(released.reservationType()).isEqualTo(inserted.reservationType());
        assertThat(released.bookingId()).isEqualTo(inserted.bookingId());
        assertThat(released.plannedStart()).isEqualTo(inserted.plannedStart());
        assertThat(released.plannedEnd()).isEqualTo(inserted.plannedEnd());
        assertThat(released.plannedRangeIsStartToEnd()).isTrue();
        assertThat(released.actualReservationStart()).isEqualTo(inserted.actualReservationStart());
    }

    @Test
    void anActiveRowCanBeReleasedBeforeItsPlannedStart() {
        // Cancelled before start: release now (arch 05 section 3.2). actual_reservation_start is the command instant,
        // so a release before planned_start still satisfies ck_reservation_release_chronology.
        UUID id = run(ctx -> ledger.create(ctx,
                booking(ONE_WAY_RESOURCE, "2030-01-11T10:00:00Z", "2030-01-11T11:00:00Z")));

        assertThat(release(id, "2030-01-11T09:30:00Z", "E7-29 test: cancelled before start")).isTrue();

        Row released = committedRow(id);
        assertThat(released.active()).isFalse();
        assertThat(released.releaseAt()).isEqualTo(at("2030-01-11T09:30:00Z"));
        assertThat(released.releaseReason()).isEqualTo("E7-29 test: cancelled before start");
        assertThat(released.plannedStart()).as("planned_start unchanged").isEqualTo(at("2030-01-11T10:00:00Z"));
        assertThat(released.plannedEnd()).as("planned_end unchanged").isEqualTo(at("2030-01-11T11:00:00Z"));
        assertThat(released.plannedRangeIsStartToEnd()).as("planned_range still [10:00, 11:00)").isTrue();

        assertThat(release(id, "2030-01-11T09:45:00Z", "second release")).as("already released").isFalse();

        Row after = committedRow(id);
        assertThat(after.active()).isFalse();
        assertThat(after.releaseAt()).as("first release facts kept").isEqualTo(at("2030-01-11T09:30:00Z"));
        assertThat(after.releaseReason()).isEqualTo("E7-29 test: cancelled before start");
    }

    @Test
    void releasingAnUnknownRowMatchesNothing() {
        assertThat(release(UUID.randomUUID(), "2030-01-06T10:05:00Z", "nothing"))
                .isFalse();
    }

    @Test
    void aReleaseRollsBackWithItsCommand() {
        UUID id = run(ctx -> ledger.create(ctx,
                booking(ONE_WAY_RESOURCE, "2030-01-07T10:00:00Z", "2030-01-07T11:00:00Z")));

        assertThatThrownBy(() -> run(ctx -> {
            assertThat(ledger.release(ctx, id, at("2030-01-07T10:05:00Z"), "rolled back")).isTrue();
            assertThat(row(ctx.jdbc(), id).active()).as("released inside the command transaction").isFalse();
            throw new RollBack();
        })).isInstanceOf(RollBack.class);

        Row after = committedRow(id);
        assertThat(after.active()).isTrue();
        assertThat(after.releaseAt()).isNull();
        assertThat(after.releaseReason()).isNull();
    }

    // ---- 4. owner shape ---------------------------------------------------------------------------------------------

    @Test
    void bookingAndLateConfirmHoldRowsCarryOnlyTheBookingOwner() {
        UUID booking = run(ctx -> ledger.create(ctx, new NewReservation(OWNER_SHAPE_RESOURCE,
                ReservationOwner.booking(BOOKING), at("2030-01-03T10:00:00Z"), at("2030-01-03T11:00:00Z"))));
        UUID hold = run(ctx -> ledger.create(ctx, new NewReservation(OWNER_SHAPE_RESOURCE,
                ReservationOwner.lateConfirmHold(BOOKING), at("2030-01-03T11:00:00Z"), at("2030-01-03T12:00:00Z"))));

        assertOwnerShape(committedRow(booking), ReservationType.BOOKING, BOOKING, null, null, null);
        assertOwnerShape(committedRow(hold), ReservationType.LATE_CONFIRM_HOLD, BOOKING, null, null, null);
    }

    @Test
    void maintenanceAndClosureRowsCarryOnlyTheirOperationsOwner() {
        // Each row must match its owner's Resource / Venue and period exactly (validate_operational_reservation_owner).
        UUID maintenance = run(ctx -> ledger.create(ctx, new NewReservation(OWNER_SHAPE_RESOURCE,
                ReservationOwner.maintenance(MAINTENANCE), at("2030-01-03T08:00:00Z"), at("2030-01-03T09:00:00Z"))));
        UUID closure = run(ctx -> ledger.create(ctx, new NewReservation(OWNER_SHAPE_RESOURCE,
                ReservationOwner.closure(CLOSURE), at("2030-01-04T08:00:00Z"), at("2030-01-04T09:00:00Z"))));

        assertOwnerShape(committedRow(maintenance), ReservationType.MAINTENANCE, null, null, MAINTENANCE, null);
        assertOwnerShape(committedRow(closure), ReservationType.CLOSURE, null, null, null, CLOSURE);
    }

    @Test
    void tournamentHoldRowsCarryOnlyTheTournamentOwner() {
        // A Tournament is created and checked as a whole at commit (tournament.assert_tournament_aggregate), which a
        // bare ledger row cannot satisfy. So the Tournament and its two holds are written in one command, the rows are
        // read back inside that transaction, and the command is rolled back.
        UUID tournament = UUID.fromString("e7290000-0000-4000-8000-000000000501");
        LockSet locks = LockSet.builder().venues(VENUE).resources(TOURNAMENT_RESOURCE).newTournaments(tournament)
                .build();
        List<Row> seen = new ArrayList<>();

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
            UUID approval = ledger.create(ctx, new NewReservation(TOURNAMENT_RESOURCE,
                    ReservationOwner.tournamentApprovalHold(tournament),
                    at("2030-02-01T16:00:00Z"), at("2030-02-01T17:00:00Z")));
            ledger.release(ctx, approval, ctx.commandNow(), "E7-29 test: approved");
            UUID hold = ledger.create(ctx, new NewReservation(TOURNAMENT_RESOURCE,
                    ReservationOwner.tournamentHold(tournament), at("2030-02-01T16:00:00Z"), at("2030-02-01T17:00:00Z")));
            seen.add(row(ctx.jdbc(), approval));
            seen.add(row(ctx.jdbc(), hold));
            throw new RollBack();
        })).isInstanceOf(RollBack.class);

        assertOwnerShape(seen.get(0), ReservationType.TOURNAMENT_APPROVAL_HOLD, null, tournament, null, null);
        assertOwnerShape(seen.get(1), ReservationType.TOURNAMENT_HOLD, null, tournament, null, null);
        assertThat(rowsOn(TOURNAMENT_RESOURCE)).as("rolled back").isEmpty();
    }

    private static void assertOwnerShape(Row row, ReservationType type, UUID booking, UUID tournament,
                                         UUID maintenance, UUID closure) {
        assertThat(row.reservationType()).isEqualTo(type.name());
        assertThat(row.bookingId()).as("booking_id").isEqualTo(booking);
        assertThat(row.tournamentId()).as("tournament_id").isEqualTo(tournament);
        assertThat(row.maintenanceId()).as("maintenance_id").isEqualTo(maintenance);
        assertThat(row.closureId()).as("closure_id").isEqualTo(closure);
        assertThat(row.plannedRangeIsStartToEnd()).isTrue();
    }

    // ---- 5. translation through the real database -------------------------------------------------------------------

    @Test
    void anOverlappingBookingAgainstACommittedRowIsSlotTaken() {
        UUID held = run(ctx -> ledger.create(ctx,
                booking(TRANSLATION_RESOURCE, "2030-01-05T08:00:00Z", "2030-01-05T09:00:00Z")));

        assertThatThrownBy(() -> run(ctx -> ledger.create(ctx,
                booking(TRANSLATION_RESOURCE, "2030-01-05T08:59:00Z", "2030-01-05T10:00:00Z"))))
                .isExactlyInstanceOf(SlotTaken.class)
                .extracting(e -> ((SlotTaken) e).code()).isEqualTo(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);

        assertThat(rowsOn(TRANSLATION_RESOURCE)).extracting(Row::id).containsExactly(held);
    }

    @Test
    void aBackToBackBookingIsNotAConflict() {
        run(ctx -> ledger.create(ctx, booking(TRANSLATION_RESOURCE, "2030-01-08T08:00:00Z", "2030-01-08T09:00:00Z")));
        UUID next = run(ctx -> ledger.create(ctx,
                booking(TRANSLATION_RESOURCE, "2030-01-08T09:00:00Z", "2030-01-08T10:00:00Z")));

        assertThat(committedRow(next).active()).as("[08:00,09:00) and [09:00,10:00) do not overlap").isTrue();
    }

    @Test
    void aLateConfirmHoldConflictIsNotSlotTakenAndPropagatesUnchanged() {
        run(ctx -> ledger.create(ctx, booking(TRANSLATION_RESOURCE, "2030-01-09T08:00:00Z", "2030-01-09T09:00:00Z")));

        assertThatThrownBy(() -> run(ctx -> ledger.create(ctx, new NewReservation(TRANSLATION_RESOURCE,
                ReservationOwner.lateConfirmHold(BOOKING), at("2030-01-09T08:30:00Z"), at("2030-01-09T09:30:00Z")))))
                .isInstanceOf(DataAccessException.class)
                .isNotInstanceOf(SlotTaken.class)
                .satisfies(e -> {
                    assertThat(DatabaseErrorCodes.serverError(e)).hasValueSatisfying(error -> {
                        assertThat(error.getSQLState()).isEqualTo("P0001");
                        assertThat(error.getConstraint()).isEqualTo("gd_reservation_conflict_late_confirm");
                    });
                    assertThat(DatabaseErrorCodes.translate(e)).as("left for the global error mapper")
                            .contains(ApiErrorCode.LATE_CONFIRM_RESOURCE_CONFLICT);
                });
    }

    @Test
    void aForeignKeyFailureIsNotSlotTakenAndPropagatesUnchanged() {
        UUID noSuchBooking = UUID.fromString("e7290000-0000-4000-8000-00000000dead");

        assertThatThrownBy(() -> run(ctx -> ledger.create(ctx, new NewReservation(TRANSLATION_RESOURCE,
                ReservationOwner.booking(noSuchBooking), at("2030-01-10T08:00:00Z"), at("2030-01-10T09:00:00Z")))))
                .isInstanceOf(DataAccessException.class)
                .isNotInstanceOf(SlotTaken.class)
                .satisfies(e -> assertThat(DatabaseErrorCodes.serverError(e)).hasValueSatisfying(error -> {
                    assertThat(error.getSQLState()).isEqualTo("23503");
                    assertThat(error.getConstraint()).isEqualTo("fk_resource_reservation_booking_id__booking_id");
                }));
    }

    // ---- E7-47: the owner's active row and ReservationReleased -------------------------------------------------------

    @Test
    void releasePublishesReservationReleasedOnceWithTheHeldPayload() {
        UUID id = run(ctx -> ledger.create(ctx,
                booking(ONE_WAY_RESOURCE, "2030-01-21T10:00:00Z", "2030-01-21T11:00:00Z")));

        assertThat(release(id, "2030-01-21T09:00:00Z", "E7-47 test")).isTrue();
        assertThat(release(id, "2030-01-21T09:30:00Z", "E7-47 test again")).isFalse();

        List<String> released = JdbcClient.create(appDataSource).sql("""
                        SELECT payload::text FROM technical.outbox_event
                         WHERE event_type = 'ReservationReleased' AND aggregate_id = :id""")
                .param("id", id).query(String.class).list();
        assertThat(released).as("one event, for the release that happened").singleElement()
                .satisfies(payload -> assertThat(payload)
                        .contains("\"reservationId\": \"" + id + "\"")
                        .contains("\"resourceId\": \"" + ONE_WAY_RESOURCE + "\"")
                        .contains("\"venueId\": \"" + VENUE + "\"")
                        .contains("\"start\": \"2030-01-21T10:00:00Z\"")
                        .contains("\"end\": \"2030-01-21T11:00:00Z\""));
    }

    @Test
    void activeOfAnswersTheOwnersOneActiveRowAndRefusesTwo() {
        Optional<ActiveReservation> none = run(ctx -> ledger.activeOf(ctx, ReservationOwner.booking(UUID.randomUUID())));
        assertThat(none).isEmpty();

        assertThatThrownBy(() -> run(ctx -> {
            UUID first = ledger.create(ctx, booking(ONE_WAY_RESOURCE, "2030-01-22T10:00:00Z", "2030-01-22T11:00:00Z"));
            ledger.create(ctx, booking(NO_SHOW_RESOURCE, "2030-01-22T10:00:00Z", "2030-01-22T11:00:00Z"));
            assertThat(first).isNotNull();
            return ledger.activeOf(ctx, ReservationOwner.booking(BOOKING));
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("active reservations");

        Optional<ActiveReservation> otherType = run(ctx -> ledger.activeOf(ctx,
                ReservationOwner.lateConfirmHold(UUID.randomUUID())));
        assertThat(otherType).isEmpty();
    }
}
