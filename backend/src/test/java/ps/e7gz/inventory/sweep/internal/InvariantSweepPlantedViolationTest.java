package ps.e7gz.inventory.sweep.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.inventory.sweep.internal.PlantedViolationDatabase.booking;
import static ps.e7gz.inventory.sweep.internal.PlantedViolationDatabase.ordered;
import static ps.e7gz.inventory.sweep.internal.PlantedViolationDatabase.reservation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ps.e7gz.common.codes.ReservationType;
import ps.e7gz.common.error.DatabaseErrorCodes;
import ps.e7gz.inventory.sweep.api.InvariantCheck;
import ps.e7gz.inventory.sweep.api.InvariantSeverity;
import ps.e7gz.inventory.sweep.api.InvariantViolation;
import ps.e7gz.inventory.sweep.api.InvariantViolationReporter;
import ps.e7gz.inventory.sweep.api.SweepReport;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-32 - planted violations: the sweep, with the two E7-32 checks only, run against
 * {@link PlantedViolationDatabase} (real column shapes, no guards) - so each test plants exactly the state it needs and
 * proves the production SQL detects it, the reporter boundary receives it, and nothing is repaired. Checks added
 * later by other tasks are never run here: this scratch database holds only the two tables the E7-32 checks read.
 */
class InvariantSweepPlantedViolationTest extends IntegrationTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2031-06-01T00:00:00Z"), ZoneOffset.UTC);

    /** The allowed overlapping pairs (A1, PO decision 3 Oct 2026); every other ordered pair is a violation. */
    static final Set<List<ReservationType>> ALLOWED = Set.of(
            List.of(ReservationType.MAINTENANCE, ReservationType.MAINTENANCE),
            List.of(ReservationType.MAINTENANCE, ReservationType.CLOSURE),
            List.of(ReservationType.CLOSURE, ReservationType.MAINTENANCE));

    /** Every check the application wires, E7-32's and any added later; only inspected, never run here. */
    @Autowired
    List<InvariantCheck> applicationChecks;

    /** The checks E7-32 owns: the only ones run against the scratch database. */
    static final List<InvariantCheck> E7_32_CHECKS =
            List.of(new ActiveReservationOverlapCheck(), new TerminalBookingReasonCheck());

    /** Records each report and whether a transaction was still open when it arrived. */
    static final class RecordingReporter implements InvariantViolationReporter {

        final List<InvariantViolation> received = new ArrayList<>();
        boolean calledInsideATransaction;

        @Override
        public void report(InvariantViolation violation) {
            calledInsideATransaction |= TransactionSynchronizationManager.isActualTransactionActive();
            received.add(violation);
        }
    }

    RecordingReporter reporter;

    @BeforeEach
    void cleanPlant() {
        PlantedViolationDatabase.clear();
        reporter = new RecordingReporter();
    }

    private SweepReport sweep() {
        return sweep(E7_32_CHECKS);
    }

    private SweepReport sweep(List<InvariantCheck> withChecks) {
        return new ReadOnlyInvariantSweep(PlantedViolationDatabase.dataSource(), withChecks, List.of(reporter), CLOCK)
                .run();
    }

    private static List<InvariantViolation> of(SweepReport report, String invariant) {
        return report.violations().stream().filter(v -> v.invariant().equals(invariant)).toList();
    }

    // ---- wiring ----------------------------------------------------------------------------------------------------

    @Test
    void theApplicationWiresBothE732Checks() {
        assertThat(applicationChecks).extracting(InvariantCheck::invariant).contains(
                ActiveReservationOverlapCheck.INVARIANT, TerminalBookingReasonCheck.INVARIANT);
        assertThat(applicationChecks).filteredOn(check -> check instanceof ActiveReservationOverlapCheck
                        || check instanceof TerminalBookingReasonCheck)
                .hasSize(2)
                .extracting(InvariantCheck::severity).containsOnly(InvariantSeverity.SEV_1);
    }

    // ---- NFR-28 active overlap ---------------------------------------------------------------------------------

    @Test
    void everyActiveOverlappingPairIsReportedExceptMaintenanceWithMaintenanceOrClosure() {
        // One Resource per ordered type pair, two active rows overlapping by 30 minutes. The first type always has the
        // lower id (SQL row a), the second the higher (row b), so both orientations of every pair are covered.
        record Pair(ReservationType first, ReservationType second, UUID resource) {
        }
        List<Pair> pairs = new ArrayList<>();
        int n = 0;
        for (ReservationType first : ReservationType.values()) {
            for (ReservationType second : ReservationType.values()) {
                UUID resource = UUID.randomUUID();
                reservation(ordered(++n), resource, first, "2031-03-01T08:00:00Z", "2031-03-01T09:00:00Z", true);
                reservation(ordered(++n), resource, second, "2031-03-01T08:30:00Z", "2031-03-01T09:30:00Z", true);
                pairs.add(new Pair(first, second, resource));
            }
        }

        List<InvariantViolation> found = of(sweep(), ActiveReservationOverlapCheck.INVARIANT);

        Set<UUID> expected = new HashSet<>();
        for (Pair pair : pairs) {
            if (!ALLOWED.contains(List.of(pair.first(), pair.second()))) {
                expected.add(pair.resource());
            }
        }
        assertThat(expected).as("36 ordered pairs, 3 allowed").hasSize(33);
        assertThat(found).extracting(InvariantViolation::subjectId).as("exactly one violation per forbidden pair")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(found).allSatisfy(v -> {
            assertThat(v.severity()).isEqualTo(InvariantSeverity.SEV_1);
            assertThat(v.subjectType()).isEqualTo("RESOURCE");
            assertThat(v.facts()).containsKeys("firstReservationId", "firstReservationType", "secondReservationId",
                    "secondReservationType");
        });
        // The forbidden cross-class pairs named by the decision are among them, in both orders.
        for (ReservationType commitment : List.of(ReservationType.BOOKING, ReservationType.LATE_CONFIRM_HOLD,
                ReservationType.TOURNAMENT_APPROVAL_HOLD, ReservationType.TOURNAMENT_HOLD)) {
            for (ReservationType guard : List.of(ReservationType.MAINTENANCE, ReservationType.CLOSURE)) {
                assertThat(found).anySatisfy(v -> assertThat(List.of(v.facts().get("firstReservationType"),
                        v.facts().get("secondReservationType"))).containsExactlyInAnyOrder(commitment.name(),
                        guard.name()));
            }
        }
    }

    @Test
    void anOverlapNamesBothReservationsOfThePair() {
        UUID resource = UUID.randomUUID();
        UUID a = reservation(resource, ReservationType.BOOKING, "2031-03-02T08:00:00Z", "2031-03-02T09:00:00Z", true);
        UUID b = reservation(resource, ReservationType.BOOKING, "2031-03-02T08:59:00Z", "2031-03-02T10:00:00Z", true);

        List<InvariantViolation> found = of(sweep(), ActiveReservationOverlapCheck.INVARIANT);

        assertThat(found).hasSize(1);
        Map<String, String> facts = found.get(0).facts();
        assertThat(Set.of(facts.get("firstReservationId"), facts.get("secondReservationId")))
                .containsExactlyInAnyOrder(a.toString(), b.toString());
        assertThat(found.get(0).subjectId()).isEqualTo(resource);
    }

    @Test
    void bothRowsOfAPairMustBeActiveWhicheverHasTheLowerId() {
        // Fixed ids: on each Resource the first row is SQL row a (lower id), the second is row b.
        UUID lowerActive = UUID.randomUUID();
        reservation(ordered(1), lowerActive, ReservationType.BOOKING, "2031-03-09T08:00:00Z", "2031-03-09T09:00:00Z",
                true);
        reservation(ordered(2), lowerActive, ReservationType.BOOKING, "2031-03-09T08:30:00Z", "2031-03-09T09:30:00Z",
                false);
        UUID lowerReleased = UUID.randomUUID();
        reservation(ordered(3), lowerReleased, ReservationType.BOOKING, "2031-03-09T08:00:00Z", "2031-03-09T09:00:00Z",
                false);
        reservation(ordered(4), lowerReleased, ReservationType.BOOKING, "2031-03-09T08:30:00Z", "2031-03-09T09:30:00Z",
                true);
        // Control: the same overlap with both rows active is reported.
        UUID bothActive = UUID.randomUUID();
        reservation(ordered(5), bothActive, ReservationType.BOOKING, "2031-03-09T08:00:00Z", "2031-03-09T09:00:00Z",
                true);
        reservation(ordered(6), bothActive, ReservationType.BOOKING, "2031-03-09T08:30:00Z", "2031-03-09T09:30:00Z",
                true);

        List<InvariantViolation> found = of(sweep(), ActiveReservationOverlapCheck.INVARIANT);

        // Without b.active_flag, lowerActive is reported; without a.active_flag, lowerReleased is.
        assertThat(found).extracting(InvariantViolation::subjectId).as("only the pair with both rows active")
                .containsExactly(bothActive);
        assertThat(found.get(0).facts()).containsEntry("firstReservationId", ordered(5).toString())
                .containsEntry("secondReservationId", ordered(6).toString());
    }

    @Test
    void theMaintenanceExclusionHoldsInBothOrientations() {
        // Allowed: MAINTENANCE as a with CLOSURE as b, CLOSURE as a with MAINTENANCE as b, MAINTENANCE with MAINTENANCE.
        UUID maintenanceThenClosure = UUID.randomUUID();
        reservation(ordered(1), maintenanceThenClosure, ReservationType.MAINTENANCE, "2031-03-10T08:00:00Z",
                "2031-03-10T09:00:00Z", true);
        reservation(ordered(2), maintenanceThenClosure, ReservationType.CLOSURE, "2031-03-10T08:30:00Z",
                "2031-03-10T09:30:00Z", true);
        UUID closureThenMaintenance = UUID.randomUUID();
        reservation(ordered(3), closureThenMaintenance, ReservationType.CLOSURE, "2031-03-10T08:00:00Z",
                "2031-03-10T09:00:00Z", true);
        reservation(ordered(4), closureThenMaintenance, ReservationType.MAINTENANCE, "2031-03-10T08:30:00Z",
                "2031-03-10T09:30:00Z", true);
        UUID maintenanceTwice = UUID.randomUUID();
        reservation(ordered(5), maintenanceTwice, ReservationType.MAINTENANCE, "2031-03-10T08:00:00Z",
                "2031-03-10T09:00:00Z", true);
        reservation(ordered(6), maintenanceTwice, ReservationType.MAINTENANCE, "2031-03-10T08:30:00Z",
                "2031-03-10T09:30:00Z", true);
        // Controls: MAINTENANCE with a commitment is still a violation in both orientations.
        UUID maintenanceThenBooking = UUID.randomUUID();
        reservation(ordered(7), maintenanceThenBooking, ReservationType.MAINTENANCE, "2031-03-10T08:00:00Z",
                "2031-03-10T09:00:00Z", true);
        reservation(ordered(8), maintenanceThenBooking, ReservationType.BOOKING, "2031-03-10T08:30:00Z",
                "2031-03-10T09:30:00Z", true);
        UUID bookingThenMaintenance = UUID.randomUUID();
        reservation(ordered(9), bookingThenMaintenance, ReservationType.BOOKING, "2031-03-10T08:00:00Z",
                "2031-03-10T09:00:00Z", true);
        reservation(ordered(10), bookingThenMaintenance, ReservationType.MAINTENANCE, "2031-03-10T08:30:00Z",
                "2031-03-10T09:30:00Z", true);

        List<InvariantViolation> found = of(sweep(), ActiveReservationOverlapCheck.INVARIANT);

        // Without the a = MAINTENANCE branch, maintenanceThenClosure and maintenanceTwice are reported; without the
        // b = MAINTENANCE branch, closureThenMaintenance is. Narrowing either IN list to MAINTENANCE alone has the same
        // effect, and widening it past CLOSURE drops a control.
        assertThat(found).extracting(InvariantViolation::subjectId)
                .containsExactlyInAnyOrder(maintenanceThenBooking, bookingThenMaintenance);
        assertThat(found).filteredOn(v -> v.subjectId().equals(maintenanceThenBooking)).singleElement()
                .satisfies(v -> assertThat(v.facts()).containsEntry("firstReservationType", "MAINTENANCE")
                        .containsEntry("secondReservationType", "BOOKING"));
        assertThat(found).filteredOn(v -> v.subjectId().equals(bookingThenMaintenance)).singleElement()
                .satisfies(v -> assertThat(v.facts()).containsEntry("firstReservationType", "BOOKING")
                        .containsEntry("secondReservationType", "MAINTENANCE"));
    }

    @Test
    void aReleasedRowIsHistoryAndNeverReportedForItsOverlappingPlannedRange() {
        UUID resource = UUID.randomUUID();
        // EC-AVAI-004: the no-show row is released, the resold row holds the same planned interval.
        reservation(resource, ReservationType.BOOKING, "2031-03-03T08:00:00Z", "2031-03-03T09:00:00Z", false);
        reservation(resource, ReservationType.BOOKING, "2031-03-03T08:20:00Z", "2031-03-03T09:00:00Z", true);
        // Two released rows overlapping each other, and a released commitment under an active Maintenance.
        UUID other = UUID.randomUUID();
        reservation(other, ReservationType.BOOKING, "2031-03-03T08:00:00Z", "2031-03-03T09:00:00Z", false);
        reservation(other, ReservationType.LATE_CONFIRM_HOLD, "2031-03-03T08:00:00Z", "2031-03-03T09:00:00Z", false);
        reservation(other, ReservationType.MAINTENANCE, "2031-03-03T08:00:00Z", "2031-03-03T09:00:00Z", true);

        assertThat(of(sweep(), ActiveReservationOverlapCheck.INVARIANT)).isEmpty();
        assertThat(reporter.received).isEmpty();
    }

    @Test
    void backToBackRowsAndRowsOnDifferentResourcesDoNotOverlap() {
        UUID resource = UUID.randomUUID();
        reservation(resource, ReservationType.BOOKING, "2031-03-04T08:00:00Z", "2031-03-04T09:00:00Z", true);
        reservation(resource, ReservationType.BOOKING, "2031-03-04T09:00:00Z", "2031-03-04T10:00:00Z", true);
        reservation(resource, ReservationType.CLOSURE, "2031-03-04T10:00:00Z", "2031-03-04T11:00:00Z", true);
        reservation(UUID.randomUUID(), ReservationType.BOOKING, "2031-03-04T08:00:00Z", "2031-03-04T09:00:00Z", true);

        assertThat(of(sweep(), ActiveReservationOverlapCheck.INVARIANT)).isEmpty();
    }

    // ---- NFR-26 terminal reason --------------------------------------------------------------------------------

    @Test
    void aRejectedOrCancelledBookingWithoutAReasonIsReported() {
        UUID rejected = booking("REJECTED", null);
        UUID cancelled = booking("CANCELLED", null);
        booking("CANCELLED", "VENUE_CANCELLED_MANUAL_BOOKING");
        booking("REJECTED", "VENUE_REJECTED");
        booking("NO_SHOW", null); // carries the NO_SHOW_RECORDED event, not a reason (arch 11 section 4)
        booking("COMPLETED", null);
        booking("CONFIRMED", null);

        List<InvariantViolation> found = of(sweep(), TerminalBookingReasonCheck.INVARIANT);

        assertThat(found).extracting(InvariantViolation::subjectId).containsExactlyInAnyOrder(rejected, cancelled);
        assertThat(found).allSatisfy(v -> {
            assertThat(v.subjectType()).isEqualTo("BOOKING");
            assertThat(v.severity()).isEqualTo(InvariantSeverity.SEV_1);
        });
        assertThat(found).extracting(v -> v.facts().get("status")).containsExactlyInAnyOrder("REJECTED", "CANCELLED");
    }

    // ---- the run, the reporter boundary, read-only -------------------------------------------------------------

    @Test
    void everyViolationReachesTheReporterAsSev1AfterTheSnapshotHasClosed() {
        UUID resource = UUID.randomUUID();
        reservation(resource, ReservationType.BOOKING, "2031-03-05T08:00:00Z", "2031-03-05T09:00:00Z", true);
        reservation(resource, ReservationType.MAINTENANCE, "2031-03-05T08:30:00Z", "2031-03-05T09:30:00Z", true);
        UUID bookingWithoutReason = booking("CANCELLED", null);

        SweepReport report = sweep();

        assertThat(report.clean()).isFalse();
        assertThat(report.invariants()).contains(TerminalBookingReasonCheck.INVARIANT,
                ActiveReservationOverlapCheck.INVARIANT);
        assertThat(report.startedAt()).isEqualTo(CLOCK.instant());
        assertThat(reporter.received).containsExactlyElementsOf(report.violations());
        assertThat(reporter.received).extracting(InvariantViolation::invariant).containsExactlyInAnyOrder(
                TerminalBookingReasonCheck.INVARIANT, ActiveReservationOverlapCheck.INVARIANT);
        assertThat(reporter.received).extracting(InvariantViolation::subjectId)
                .containsExactlyInAnyOrder(bookingWithoutReason, resource);
        assertThat(reporter.received).extracting(InvariantViolation::severity).containsOnly(InvariantSeverity.SEV_1);
        assertThat(reporter.calledInsideATransaction).as("reported outside the read-only snapshot").isFalse();
    }

    @Test
    void cleanDataProducesNoViolationAndNoReport() {
        UUID resource = UUID.randomUUID();
        reservation(resource, ReservationType.MAINTENANCE, "2031-03-06T08:00:00Z", "2031-03-06T10:00:00Z", true);
        reservation(resource, ReservationType.MAINTENANCE, "2031-03-06T09:00:00Z", "2031-03-06T11:00:00Z", true);
        reservation(resource, ReservationType.CLOSURE, "2031-03-06T09:30:00Z", "2031-03-06T10:30:00Z", true);
        reservation(resource, ReservationType.BOOKING, "2031-03-06T11:00:00Z", "2031-03-06T12:00:00Z", true);
        booking("CANCELLED", "CUSTOMER_CANCELLED");

        SweepReport report = sweep();

        assertThat(report.clean()).isTrue();
        assertThat(report.invariants()).contains(ActiveReservationOverlapCheck.INVARIANT,
                TerminalBookingReasonCheck.INVARIANT);
        assertThat(reporter.received).isEmpty();
    }

    @Test
    void theSweepNeverRepairsTheRowsItReports() {
        UUID resource = UUID.randomUUID();
        reservation(resource, ReservationType.BOOKING, "2031-03-07T08:00:00Z", "2031-03-07T09:00:00Z", true);
        reservation(resource, ReservationType.TOURNAMENT_HOLD, "2031-03-07T08:00:00Z", "2031-03-07T09:00:00Z", true);
        booking("REJECTED", null);
        List<String> before = PlantedViolationDatabase.allRows();

        SweepReport first = sweep();
        SweepReport second = sweep();

        assertThat(PlantedViolationDatabase.allRows()).as("every planted row unchanged").isEqualTo(before);
        assertThat(second.violations()).as("still there on the next run: nothing was repaired")
                .isEqualTo(first.violations()).hasSize(2);
    }

    @Test
    void aCheckThatTriesToWriteIsRefusedByTheReadOnlySnapshot() {
        UUID resource = UUID.randomUUID();
        UUID row = reservation(resource, ReservationType.BOOKING, "2031-03-08T08:00:00Z", "2031-03-08T09:00:00Z",
                true);
        List<String> before = PlantedViolationDatabase.allRows();
        InvariantCheck repairing = new InvariantCheck() {
            @Override
            public String invariant() {
                return "TEST_REPAIRING_CHECK";
            }

            @Override
            public InvariantSeverity severity() {
                return InvariantSeverity.SEV_1;
            }

            @Override
            public List<InvariantViolation> detect(JdbcClient snapshot) {
                snapshot.sql("UPDATE inventory.resource_reservation SET active_flag = false WHERE id = :id")
                        .param("id", row).update();
                return List.of();
            }
        };

        assertThatThrownBy(() -> sweep(List.of(repairing)))
                .satisfies(e -> assertThat(DatabaseErrorCodes.serverError(e)).hasValueSatisfying(
                        error -> assertThat(error.getSQLState()).as("read_only_sql_transaction").isEqualTo("25006")));
        assertThat(PlantedViolationDatabase.allRows()).isEqualTo(before);
        assertThat(reporter.received).isEmpty();
    }

    @Test
    void twoChecksWithTheSameCodeAreRefused() {
        assertThatThrownBy(() -> new ReadOnlyInvariantSweep(PlantedViolationDatabase.dataSource(),
                List.of(new ActiveReservationOverlapCheck(), new ActiveReservationOverlapCheck()), List.of(), CLOCK))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(ActiveReservationOverlapCheck.INVARIANT);
    }
}
