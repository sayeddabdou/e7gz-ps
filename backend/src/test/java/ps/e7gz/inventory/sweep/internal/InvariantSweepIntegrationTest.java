package ps.e7gz.inventory.sweep.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.inventory.E732Fixtures.BOOKING;
import static ps.e7gz.inventory.E732Fixtures.CLOSURE;
import static ps.e7gz.inventory.E732Fixtures.COMMITMENTS_RESOURCE;
import static ps.e7gz.inventory.E732Fixtures.MAINTENANCE_1;
import static ps.e7gz.inventory.E732Fixtures.MAINTENANCE_2;
import static ps.e7gz.inventory.E732Fixtures.MAINTENANCE_RESOURCE;
import static ps.e7gz.inventory.E732Fixtures.RELEASED_HISTORY_RESOURCE;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.inventory.E732Fixtures;
import ps.e7gz.inventory.ledger.api.NewReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.inventory.sweep.api.InvariantSweep;
import ps.e7gz.inventory.sweep.api.InvariantViolation;
import ps.e7gz.inventory.sweep.api.InvariantViolationReporter;
import ps.e7gz.inventory.sweep.api.SweepReport;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-32 - the wired sweep against the real migrated database. Every row here was written through the real ledger and
 * guards. The allowed overlaps (MAINTENANCE / MAINTENANCE, MAINTENANCE / CLOSURE) and a released row whose planned
 * interval overlaps an active one are present - checked below, so a clean sweep is never clean only because the
 * tables are empty.
 */
class InvariantSweepIntegrationTest extends IntegrationTest {

    @Autowired
    InvariantSweep sweep;

    @Autowired
    ActiveReservationOverlapCheck overlapCheck;

    @Autowired
    TerminalBookingReasonCheck terminalReasonCheck;

    @Autowired
    CommandRunner runner;

    @Autowired
    ReservationLedger ledger;

    @Autowired
    DataSource appDataSource;

    @Autowired
    Clock clock;

    record TestCommand<R>(Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    /** Later tasks add their own checks to the wired sweep; these tests assert only the E7-32 invariants. */
    static final List<String> E7_32_INVARIANTS =
            List.of(ActiveReservationOverlapCheck.INVARIANT, TerminalBookingReasonCheck.INVARIANT);

    private static boolean rowsWritten;

    @BeforeEach
    void rows() {
        E732Fixtures.load();
        synchronized (InvariantSweepIntegrationTest.class) {
            if (rowsWritten) {
                return;
            }
            // MAINTENANCE / MAINTENANCE / CLOSURE, all overlapping: Maintenance first (new Maintenance may not overlap
            // a Closure; a Closure may overlap existing Maintenance).
            create(MAINTENANCE_RESOURCE, ReservationOwner.maintenance(MAINTENANCE_1), "2031-01-01T08:00:00Z",
                    "2031-01-01T10:00:00Z");
            create(MAINTENANCE_RESOURCE, ReservationOwner.maintenance(MAINTENANCE_2), "2031-01-01T09:00:00Z",
                    "2031-01-01T11:00:00Z");
            create(MAINTENANCE_RESOURCE, ReservationOwner.closure(CLOSURE), "2031-01-01T09:30:00Z",
                    "2031-01-01T10:30:00Z");

            // EC-AVAI-004: released no-show row, then the resold row on an overlapping planned interval.
            UUID noShow = create(RELEASED_HISTORY_RESOURCE, ReservationOwner.booking(BOOKING), "2031-01-02T08:00:00Z",
                    "2031-01-02T09:00:00Z");
            runner.run(new TestCommand<>(ctx -> ledger.release(ctx, noShow, Instant.parse("2031-01-02T08:16:00Z"),
                    "E7-32 test: no-show")));
            create(RELEASED_HISTORY_RESOURCE, ReservationOwner.booking(BOOKING), "2031-01-02T08:20:00Z",
                    "2031-01-02T09:00:00Z");

            // Valid commitments on one Resource: apart, and back to back.
            create(COMMITMENTS_RESOURCE, ReservationOwner.booking(BOOKING), "2031-01-03T08:00:00Z",
                    "2031-01-03T09:00:00Z");
            create(COMMITMENTS_RESOURCE, ReservationOwner.booking(BOOKING), "2031-01-03T09:00:00Z",
                    "2031-01-03T10:00:00Z");
            create(COMMITMENTS_RESOURCE, ReservationOwner.lateConfirmHold(BOOKING), "2031-01-03T12:00:00Z",
                    "2031-01-03T13:00:00Z");
            rowsWritten = true;
        }
    }

    private UUID create(UUID resource, ReservationOwner owner, String start, String end) {
        return runner.run(new TestCommand<>(ctx -> ledger.create(ctx,
                new NewReservation(resource, owner, Instant.parse(start), Instant.parse(end)))));
    }

    @Test
    void theRealDatabaseHoldsTheAllowedOverlapsAndReleasedHistoryTheSweepMustNotReport() {
        JdbcClient db = JdbcClient.create(appDataSource);
        List<String> overlappingActivePairs = db.sql("""
                        SELECT a.reservation_type || '/' || b.reservation_type
                          FROM inventory.resource_reservation a
                          JOIN inventory.resource_reservation b ON b.resource_id = a.resource_id AND a.id < b.id
                         WHERE a.resource_id = :resource AND a.active_flag AND b.active_flag
                           AND a.planned_range && b.planned_range
                        """)
                .param("resource", MAINTENANCE_RESOURCE).query(String.class).list();
        assertThat(overlappingActivePairs).as("three allowed active overlaps exist")
                .hasSize(3).allMatch(pair -> pair.contains("MAINTENANCE"));

        Integer releasedOverlaps = db.sql("""
                        SELECT count(*)
                          FROM inventory.resource_reservation a
                          JOIN inventory.resource_reservation b ON b.resource_id = a.resource_id AND a.id <> b.id
                         WHERE a.resource_id = :resource AND NOT a.active_flag AND b.active_flag
                           AND a.planned_range && b.planned_range
                        """)
                .param("resource", RELEASED_HISTORY_RESOURCE).query(Integer.class).single();
        assertThat(releasedOverlaps).as("a released row overlaps an active one").isEqualTo(1);

        assertThat(db.sql("SELECT count(*) FROM inventory.resource_reservation WHERE resource_id = :r AND active_flag")
                .param("r", COMMITMENTS_RESOURCE).query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void theWiredSweepRunsBothChecksAndFindsTheRealDatabaseClean() {
        SweepReport report = sweep.run();

        assertThat(report.invariants()).contains(ActiveReservationOverlapCheck.INVARIANT,
                TerminalBookingReasonCheck.INVARIANT);
        assertThat(report.violations()).as("the database guards hold: nothing to report for the E7-32 invariants")
                .filteredOn(v -> E7_32_INVARIANTS.contains(v.invariant())).isEmpty();
        assertThat(report.finishedAt()).isAfterOrEqualTo(report.startedAt());
    }

    @Test
    void aCleanRealDatabaseRaisesNoReport() {
        List<InvariantViolation> received = new ArrayList<>();
        InvariantViolationReporter recording = received::add;

        SweepReport report = new ReadOnlyInvariantSweep(appDataSource,
                List.of(overlapCheck, terminalReasonCheck), List.of(recording), clock).run();

        assertThat(report.clean()).isTrue();
        assertThat(received).isEmpty();
    }
}
