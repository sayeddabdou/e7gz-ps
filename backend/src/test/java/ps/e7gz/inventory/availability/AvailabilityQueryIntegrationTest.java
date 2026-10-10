package ps.e7gz.inventory.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.inventory.availability.api.AvailabilityCheck;
import ps.e7gz.inventory.availability.api.AvailabilityPeriod;
import ps.e7gz.inventory.availability.api.AvailabilityQuery;
import ps.e7gz.inventory.availability.api.PredicateResult;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.operations.api.OperationalOverlaps;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-30 - {@link AvailabilityQuery#isFree} end to end: the canonical predicate over the real owner-module reads, on the
 * migrated database, inside real commands (arch 05 section 2). Includes the Phase 1 exit criteria EC-AVAI-002,
 * EC-AVAI-003 and EC-AVAI-004. The strict check order with every later check failing too is proven on fakes in
 * {@code CanonicalAvailabilityQueryTest}; here several scenarios also fail later checks.
 */
class AvailabilityQueryIntegrationTest extends IntegrationTest {

    private static final UUID MAIN_VENUE = id(0x002);
    private static final UUID BOOKING = id(0x201);
    private static final UUID OPERATING_SESSION = id(0x006);
    private static final UUID MAINTENANCE = id(0x301);
    private static final UUID CLOSURE = id(0x401);

    private static final UUID CLEAR_ROOM = id(0x101);
    private static final UUID DISABLED_REVIEW_ROOM = id(0x102);
    private static final UUID ARCHIVED_ROOM = id(0x103);
    private static final UUID OWNER_REVIEW_ROOM = id(0x104);
    private static final UUID BLOCKED_ROOM = id(0x105);
    private static final UUID MAINTAINED_ROOM = id(0x106);
    private static final UUID CLOSURE_ROOM = id(0x107);
    private static final UUID NO_SHOW_ROOM = id(0x108);
    private static final UUID HOLDS_ROOM = id(0x109);
    private static final UUID BOOKED_ROOM = id(0x10a);
    private static final UUID BLOCKED_VENUE_ROOM = id(0x111);

    private static final UUID VENUE_EMERGENCY_VENUE = id(0x022);
    private static final UUID VENUE_EMERGENCY_ROOM = id(0x121);
    private static final UUID VENUE_EMERGENCY = id(0x027);
    private static final UUID FAILED_ROOM = id(0x131);
    private static final UUID WORKING_ROOM = id(0x132);
    private static final UUID RESOURCE_EMERGENCY = id(0x037);

    private static final String SCRIPT = "ps/e7gz/inventory/availability/availability-fixtures.sql";
    private static boolean loaded;

    @Autowired
    CommandRunner runner;

    @Autowired
    AvailabilityQuery availability;

    @Autowired
    ReservationLedger ledger;

    @Autowired
    OperationalOverlaps operations;

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

    static final class RollBack extends RuntimeException {

        RollBack() {
            super("roll back on purpose", null, false, false);
        }
    }

    record Declared(Instant start, Instant end) {
    }

    record Row(UUID id, boolean active, Instant plannedStart, Instant plannedEnd, boolean plannedRangeIsStartToEnd,
               Instant releaseAt) {
    }

    /** ReservationLedger.create publishes ReservationHeld, which carries the request's correlation ID (E7-46). */
    private CorrelationId.Scope correlation;

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @BeforeEach
    void fixtures() {
        correlation = CorrelationId.open(CorrelationId.generate());
        load();
    }

    /** One transaction: each Emergency must commit together with its selling block. */
    private static synchronized void load() {
        if (loaded) {
            return;
        }
        try (Connection owner = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR)) {
            owner.setAutoCommit(false);
            ScriptUtils.executeSqlScript(owner, new ClassPathResource(SCRIPT));
            owner.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Availability fixtures failed", e);
        }
        loaded = true;
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

    private static AvailabilityPeriod period(String start, String end) {
        return new AvailabilityPeriod(at(start), at(end));
    }

    /** {@code isFree} in a command of its own, with selling blocks read at the command instant. */
    private PredicateResult isFree(UUID resource, AvailabilityPeriod period) {
        return run(ctx -> availability.isFree(ctx, resource, period, ctx.commandNow()));
    }

    private PredicateResult isFree(UUID resource, String start, String end) {
        return isFree(resource, period(start, end));
    }

    private UUID create(UUID resource, ReservationOwner owner, String start, String end) {
        return run(ctx -> ledger.create(ctx, new NewReservation(resource, owner, at(start), at(end))));
    }

    private Declared declared(UUID emergency) {
        return JdbcClient.create(appDataSource)
                .sql("SELECT declared_at, expected_end_at FROM operations.emergency WHERE id = :id")
                .param("id", emergency)
                .query((rs, n) -> new Declared(rs.getObject("declared_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("expected_end_at", OffsetDateTime.class).toInstant()))
                .single();
    }

    private List<Row> rowsOn(UUID resource) {
        return JdbcClient.create(appDataSource).sql("""
                        SELECT id, active_flag, planned_start, planned_end,
                               planned_range = tstzrange(planned_start, planned_end, '[)') AS range_ok,
                               actual_reservation_release_at
                          FROM inventory.resource_reservation
                         WHERE resource_id = :r
                         ORDER BY planned_start, id
                        """)
                .param("r", resource)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getBoolean("active_flag"),
                        rs.getObject("planned_start", OffsetDateTime.class).toInstant(),
                        rs.getObject("planned_end", OffsetDateTime.class).toInstant(), rs.getBoolean("range_ok"),
                        rs.getObject("actual_reservation_release_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("actual_reservation_release_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static void assertNotFree(PredicateResult result, AvailabilityCheck check) {
        assertThat(result.isFree()).as("not free").isFalse();
        assertThat(result.failingCheck()).contains(check);
        assertThat(result.failingCheckIndex()).hasValue(check.index());
    }

    // ---- all clear and checks 1-4 -------------------------------------------------------------------------------------

    @Test
    void aResourceWithNothingAgainstItIsFree() {
        PredicateResult result = isFree(CLEAR_ROOM, "2030-03-10T08:00:00Z", "2030-03-10T09:00:00Z");

        assertThat(result.isFree()).isTrue();
        assertThat(result.failingCheck()).isEmpty();
    }

    @Test
    void check1AVenueSellingBlockRefusesBeforeTheResourcesOwnBlockAndStates() {
        // The room is also blocked, DISABLED and under owner review: check 1 still wins.
        assertNotFree(isFree(BLOCKED_VENUE_ROOM, "2030-03-10T08:00:00Z", "2030-03-10T09:00:00Z"),
                AvailabilityCheck.VENUE_SELLING_BLOCK);
    }

    @Test
    void check2AResourceSellingBlockRefusesBeforeTheResourceStates() {
        // The room is also DISABLED and under owner review: check 2 still wins.
        assertNotFree(isFree(BLOCKED_ROOM, "2030-03-10T08:00:00Z", "2030-03-10T09:00:00Z"),
                AvailabilityCheck.RESOURCE_SELLING_BLOCK);
    }

    @Test
    void check3ABaseStateOtherThanActiveRefusesBeforeOwnerReview() {
        assertNotFree(isFree(DISABLED_REVIEW_ROOM, "2030-03-10T08:00:00Z", "2030-03-10T09:00:00Z"),
                AvailabilityCheck.RESOURCE_BASE_STATE);
        assertNotFree(isFree(ARCHIVED_ROOM, "2030-03-10T08:00:00Z", "2030-03-10T09:00:00Z"),
                AvailabilityCheck.RESOURCE_BASE_STATE);
    }

    @Test
    void ecAvai002AnActiveResourceUnderOwnerReviewFailsCheck4() {
        PredicateResult result = isFree(OWNER_REVIEW_ROOM, "2030-03-10T08:00:00Z", "2030-03-10T09:00:00Z");

        assertNotFree(result, AvailabilityCheck.RESOURCE_OWNER_REVIEW);
        assertThat(result.failingCheckIndex()).hasValue(4);
    }

    @Test
    void anUnknownResourceIsNotFound() {
        assertThatThrownBy(() -> isFree(UUID.randomUUID(), "2030-03-10T08:00:00Z", "2030-03-10T09:00:00Z"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo(ApiErrorCode.NOT_FOUND);
    }

    // ---- selling blocks are states; emergencies are bounded (EC-AVAI-003, check 5) -----------------------------------

    @Test
    void ecAvai003AVenueEmergencyBlockRefusesAPeriodOutsideItsDeclaredInterval() {
        Declared declared = declared(VENUE_EMERGENCY);
        AvailabilityPeriod later = new AvailabilityPeriod(declared.end().plus(Duration.ofDays(30)),
                declared.end().plus(Duration.ofDays(30)).plus(Duration.ofHours(1)));

        Boolean declaredIntervalOverlaps = run(ctx -> operations.declaredEmergencyOverlaps(ctx, VENUE_EMERGENCY_VENUE,
                VENUE_EMERGENCY_ROOM, later.start(), later.end()));
        assertThat(declaredIntervalOverlaps).as("the unresolved Emergency is not an infinite interval").isFalse();

        assertNotFree(isFree(VENUE_EMERGENCY_ROOM, later), AvailabilityCheck.VENUE_SELLING_BLOCK);
        assertNotFree(isFree(VENUE_EMERGENCY_ROOM, new AvailabilityPeriod(declared.start(), declared.end())),
                AvailabilityCheck.VENUE_SELLING_BLOCK);
    }

    @Test
    void aResourceEmergencyBlockRefusesAPeriodOutsideItsDeclaredIntervalButNotAnotherResource() {
        Declared declared = declared(RESOURCE_EMERGENCY);
        AvailabilityPeriod later = new AvailabilityPeriod(declared.end().plus(Duration.ofDays(30)),
                declared.end().plus(Duration.ofDays(30)).plus(Duration.ofHours(1)));
        AvailabilityPeriod declaredPeriod = new AvailabilityPeriod(declared.start(), declared.end());

        assertNotFree(isFree(FAILED_ROOM, later), AvailabilityCheck.RESOURCE_SELLING_BLOCK);
        assertThat(isFree(WORKING_ROOM, declaredPeriod).isFree())
                .as("a RESOURCE Emergency on another room of the Venue does not block this one").isTrue();
    }

    @Test
    void check5TheDeclaredEmergencyIntervalRefusesWhenTheBlockStateIsNotInForceAtTheInstant() {
        // Every unresolved Emergency holds a selling block from declared_at, so check 5 is reached first only when the
        // block is not yet in force at `at`: read the states just before the declaration.
        Declared declared = declared(RESOURCE_EMERGENCY);
        Instant beforeDeclaration = declared.start().minusSeconds(1);

        PredicateResult result = run(ctx -> availability.isFree(ctx, FAILED_ROOM,
                new AvailabilityPeriod(declared.start(), declared.end()), beforeDeclaration));
        PredicateResult afterExpectedEnd = run(ctx -> availability.isFree(ctx, FAILED_ROOM,
                new AvailabilityPeriod(declared.end(), declared.end().plus(Duration.ofHours(1))), beforeDeclaration));

        assertNotFree(result, AvailabilityCheck.EMERGENCY_INTERVAL);
        assertThat(afterExpectedEnd.isFree()).as("back-to-back after Expected End: bounded").isTrue();
    }

    // ---- checks 6 and 7 come from Operations, not from guard rows in the ledger ------------------------------------

    @Test
    void check6MaintenanceRefusesItsHalfOpenPeriodEvenWithItsGuardRowInTheLedger() {
        assertNotFree(isFree(MAINTAINED_ROOM, "2030-03-01T08:30:00Z", "2030-03-01T08:45:00Z"),
                AvailabilityCheck.MAINTENANCE);
        assertThat(isFree(MAINTAINED_ROOM, "2030-03-01T09:00:00Z", "2030-03-01T10:00:00Z").isFree())
                .as("back-to-back after").isTrue();

        assertThatThrownBy(() -> run(ctx -> {
            ledger.create(ctx, new NewReservation(MAINTAINED_ROOM, ReservationOwner.maintenance(MAINTENANCE),
                    at("2030-03-01T08:00:00Z"), at("2030-03-01T09:00:00Z")));
            assertNotFree(availability.isFree(ctx, MAINTAINED_ROOM, period("2030-03-01T08:00:00Z",
                    "2030-03-01T09:00:00Z"), ctx.commandNow()), AvailabilityCheck.MAINTENANCE);
            throw new RollBack();
        })).isInstanceOf(RollBack.class);
    }

    @Test
    void check7AVenueClosureRefusesEveryRoomOfTheVenueEvenWithItsGuardRowInTheLedger() {
        assertNotFree(isFree(CLOSURE_ROOM, "2030-03-02T08:30:00Z", "2030-03-02T08:45:00Z"), AvailabilityCheck.CLOSURE);
        assertNotFree(isFree(CLEAR_ROOM, "2030-03-02T07:00:00Z", "2030-03-02T08:01:00Z"), AvailabilityCheck.CLOSURE);
        assertThat(isFree(CLOSURE_ROOM, "2030-03-02T07:00:00Z", "2030-03-02T08:00:00Z").isFree())
                .as("back-to-back before").isTrue();

        assertThatThrownBy(() -> run(ctx -> {
            ledger.create(ctx, new NewReservation(CLOSURE_ROOM, ReservationOwner.closure(CLOSURE),
                    at("2030-03-02T08:00:00Z"), at("2030-03-02T09:00:00Z")));
            assertNotFree(availability.isFree(ctx, CLOSURE_ROOM, period("2030-03-02T08:00:00Z",
                    "2030-03-02T09:00:00Z"), ctx.commandNow()), AvailabilityCheck.CLOSURE);
            throw new RollBack();
        })).isInstanceOf(RollBack.class);
    }

    // ---- check 8 --------------------------------------------------------------------------------------------------

    @Test
    void check8AnActiveBookingRefusesOverlapsButNotBackToBackPeriods() {
        create(BOOKED_ROOM, ReservationOwner.booking(BOOKING), "2030-03-05T08:00:00Z", "2030-03-05T09:00:00Z");

        assertNotFree(isFree(BOOKED_ROOM, "2030-03-05T08:59:00Z", "2030-03-05T10:00:00Z"),
                AvailabilityCheck.BLOCKING_RESERVATION);
        assertThat(isFree(BOOKED_ROOM, "2030-03-05T07:00:00Z", "2030-03-05T08:00:00Z").isFree())
                .as("back-to-back before").isTrue();
        assertThat(isFree(BOOKED_ROOM, "2030-03-05T09:00:00Z", "2030-03-05T10:00:00Z").isFree())
                .as("back-to-back after").isTrue();
    }

    @Test
    void check8AnActiveLateConfirmHoldRefuses() {
        create(HOLDS_ROOM, ReservationOwner.lateConfirmHold(BOOKING), "2030-03-04T08:00:00Z", "2030-03-04T09:00:00Z");

        assertNotFree(isFree(HOLDS_ROOM, "2030-03-04T08:30:00Z", "2030-03-04T09:30:00Z"),
                AvailabilityCheck.BLOCKING_RESERVATION);
    }

    @Test
    void check8TournamentHoldsRefuseInsideTheCommandThatWroteThem() {
        // A Tournament is checked as a whole at commit, so it and its holds are written in one command, the predicate is
        // asked inside that transaction (proving it reads on ctx.jdbc()), and the command is rolled back.
        UUID tournament = id(0x501);
        LockSet locks = LockSet.builder().venues(MAIN_VENUE).resources(HOLDS_ROOM).newTournaments(tournament).build();
        AvailabilityPeriod slot = period("2030-02-01T16:00:00Z", "2030-02-01T17:00:00Z");

        assertThatThrownBy(() -> run(locks, ctx -> {
            ctx.jdbc().sql("""
                    INSERT INTO tournament.tournament (id, venue_id, host_type, activity, play_mode, status, capacity,
                                                       created_at, start_at, end_at, nominal_minutes, checkpoint_at,
                                                       visibility, venue_cost_total, creation_fee_snapshot, entry_fee,
                                                       prize, business_date, operating_session_id)
                    VALUES (:id, :venue, 'VENUE', 'PLAYSTATION', 'SINGLE', 'WAITING_FOR_PLAYERS', 8,
                            :start, :start, :end, 60, :start, 'PUBLIC', 0, 0, 0, 0, DATE '2030-02-01', :session)
                    """)
                    .param("id", tournament).param("venue", MAIN_VENUE).param("session", OPERATING_SESSION)
                    .param("start", OffsetDateTime.parse("2030-02-01T18:00:00+02:00"))
                    .param("end", OffsetDateTime.parse("2030-02-01T19:00:00+02:00"))
                    .update();
            assertThat(availability.isFree(ctx, HOLDS_ROOM, slot, ctx.commandNow()).isFree()).isTrue();

            UUID approval = ledger.create(ctx, new NewReservation(HOLDS_ROOM,
                    ReservationOwner.tournamentApprovalHold(tournament), slot.start(), slot.end()));
            assertNotFree(availability.isFree(ctx, HOLDS_ROOM, slot, ctx.commandNow()),
                    AvailabilityCheck.BLOCKING_RESERVATION);

            ledger.release(ctx, approval, ctx.commandNow(), "E7-30 test: approved");
            assertThat(availability.isFree(ctx, HOLDS_ROOM, slot, ctx.commandNow()).isFree()).isTrue();

            ledger.create(ctx, new NewReservation(HOLDS_ROOM, ReservationOwner.tournamentHold(tournament),
                    slot.start(), slot.end()));
            assertNotFree(availability.isFree(ctx, HOLDS_ROOM, slot, ctx.commandNow()),
                    AvailabilityCheck.BLOCKING_RESERVATION);
            throw new RollBack();
        })).isInstanceOf(RollBack.class);

        assertThat(isFree(HOLDS_ROOM, slot).isFree()).as("rolled back").isTrue();
    }

    // ---- EC-AVAI-004: the 8:16 no-show ----------------------------------------------------------------------------

    @Test
    void ecAvai004AReleasedNoShowDoesNotBlockThe0820Resale() {
        UUID noShow = create(NO_SHOW_ROOM, ReservationOwner.booking(BOOKING), "2030-03-03T08:00:00Z",
                "2030-03-03T09:00:00Z");
        assertNotFree(isFree(NO_SHOW_ROOM, "2030-03-03T08:20:00Z", "2030-03-03T09:00:00Z"),
                AvailabilityCheck.BLOCKING_RESERVATION);

        Boolean released = run(ctx -> ledger.release(ctx, noShow, at("2030-03-03T08:16:00Z"), "E7-30 test: no-show"));
        assertThat(released).isTrue();

        Row history = rowsOn(NO_SHOW_ROOM).get(0);
        assertThat(history.id()).isEqualTo(noShow);
        assertThat(history.active()).isFalse();
        assertThat(history.releaseAt()).isEqualTo(at("2030-03-03T08:16:00Z"));
        assertThat(history.plannedStart()).as("planned_start unchanged").isEqualTo(at("2030-03-03T08:00:00Z"));
        assertThat(history.plannedEnd()).as("planned_end unchanged").isEqualTo(at("2030-03-03T09:00:00Z"));
        assertThat(history.plannedRangeIsStartToEnd()).as("planned_range still [08:00, 09:00)").isTrue();

        PredicateResult resale = isFree(NO_SHOW_ROOM, "2030-03-03T08:20:00Z", "2030-03-03T09:00:00Z");
        assertThat(resale.isFree()).as("08:20-09:00 is free once the no-show is released").isTrue();

        UUID walkIn = create(NO_SHOW_ROOM, ReservationOwner.booking(BOOKING), "2030-03-03T08:20:00Z",
                "2030-03-03T09:00:00Z");

        assertThat(rowsOn(NO_SHOW_ROOM)).as("both historical facts stay valid")
                .extracting(Row::id, Row::active, Row::plannedStart, Row::plannedEnd, Row::plannedRangeIsStartToEnd)
                .containsExactly(
                        tuple(noShow, false, at("2030-03-03T08:00:00Z"),
                                at("2030-03-03T09:00:00Z"), true),
                        tuple(walkIn, true, at("2030-03-03T08:20:00Z"),
                                at("2030-03-03T09:00:00Z"), true));
        assertNotFree(isFree(NO_SHOW_ROOM, "2030-03-03T08:20:00Z", "2030-03-03T09:00:00Z"),
                AvailabilityCheck.BLOCKING_RESERVATION);
    }

    private static UUID id(int n) {
        return UUID.fromString("e7300000-0000-4000-8000-%012x".formatted(n));
    }
}
