package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.booking.internal.OnlineBookingWorld.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ps.e7gz.booking.internal.VenueAnswerStore.Facts;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.inventory.ledger.api.ActiveReservation;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.inventory.ledger.api.ReservationOwner;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.loyalty.api.BookingFeeRefunds.Decision;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-113 - the Loyalty refund batch on the real database, every trigger on. One command, run through the
 * {@link CommandRunner} with both Customers in its lock set, cancels Online Bookings of two Customers
 * (PENDING -> CANCELLED / VENUE_OPENING_HOURS_CHANGED, FULL_REFUND) with the production booking stores, then records
 * their fee decisions in one batch. What is checked is the committed state: the refund rows, or - when the command
 * fails - that nothing of it committed. The venue-to-booking wiring of an opening-hours change is a later phase; the
 * cancel here only gives the batch real terminal bookings to decide on.
 */
class BookingFeeRefundBatchIntegrationTest extends IntegrationTest {

    private static final BookingTerminalReason REASON = BookingTerminalReason.VENUE_OPENING_HOURS_CHANGED;
    /** The order the batch records in (Customer, then booking) - {@code JdbcBookingFeeRefunds.ORDER}. */
    private static final Comparator<Decision> ORDER =
            Comparator.comparing(Decision::customerId).thenComparing(Decision::bookingId);

    @Autowired
    CommandRunner runner;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    ManualBookings manual;

    @Autowired
    VenueAnswerStore store;

    @Autowired
    ReservationLedger ledger;

    @Autowired
    ScheduledJobs jobs;

    @Autowired
    BookingFeeRefunds refunds;

    private CorrelationId.Scope correlation;
    private UUID correlationId;
    private OnlineBookingWorld world;
    private UUID customerA;
    private UUID customerB;
    private CreatedBooking a1;
    private CreatedBooking a2;
    private CreatedBooking b1;

    @BeforeEach
    void world() {
        correlationId = CorrelationId.generate();
        correlation = CorrelationId.open(correlationId);
        world = OnlineBookingWorld.create();
        customerA = world.customer.id();
        customerB = world.anotherCustomer();
        a1 = creation.create(world.request(customerA, world.room, world.start(), 60));
        a2 = creation.create(world.request(customerA, world.room, world.start().plus(Duration.ofHours(2)), 60));
        b1 = creation.create(world.request(customerB, world.room, world.start().plus(Duration.ofHours(4)), 60));
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    @Test
    void twoCustomersThreeBookingsCommitThreeFullRefundDecisions() {
        Cancelled cancelled = run(bothCustomers(), List.of(a1, a2, b1), refunds::recordDecisions);

        assertThat(cancelled.result()).containsOnlyKeys(a1.bookingId(), a2.bookingId(), b1.bookingId());
        for (CreatedBooking created : List.of(a1, a2, b1)) {
            assertThat(booking(created.bookingId())).satisfies(row -> {
                assertThat(row.status()).isEqualTo("CANCELLED");
                assertThat(row.terminalReason()).isEqualTo(REASON.name());
                assertThat(row.feeTreatment()).isEqualTo("FULL_REFUND");
            });
            assertThat(refundRows(created.bookingId())).singleElement().satisfies(refund -> {
                assertThat(refund.treatment()).isEqualTo("FULL_REFUND");
                assertThat(refund.terminalReason()).isEqualTo(REASON.name());
                assertThat(refund.amount()).isEqualByComparingTo(refund.feeSnapshot());
            });
        }
    }

    @Test
    void eachDecisionReferencesItsCustomerBookingTerminalHistoryAndCorrelation() {
        Cancelled cancelled = run(bothCustomers(), List.of(a1, a2, b1), refunds::recordDecisions);

        for (Decision decision : cancelled.decisions()) {
            RefundRow refund = refundRows(decision.bookingId()).getFirst();
            assertThat(refund.id()).as("the returned id is the committed row")
                    .isEqualTo(cancelled.result().get(decision.bookingId()));
            assertThat(refund.userId()).isEqualTo(decision.customerId());
            assertThat(refund.bookingHistoryId()).isEqualTo(decision.terminalHistoryId());
            assertThat(refund.correlationId()).isEqualTo(correlationId);
            assertThat(OWNER.sql("SELECT to_status FROM booking.booking_history WHERE id = :id")
                    .param("id", refund.bookingHistoryId()).query(String.class).single()).isEqualTo("CANCELLED");
        }
        assertThat(refundRows(a1.bookingId()).getFirst().userId()).isEqualTo(customerA);
        assertThat(refundRows(a2.bookingId()).getFirst().userId()).isEqualTo(customerA);
        assertThat(refundRows(b1.bookingId()).getFirst().userId()).isEqualTo(customerB);
    }

    @Test
    void repeatedSingleCustomerCallsCannotServeTwoCustomersInOneCommand() {
        assertThatThrownBy(() -> run(bothCustomers(), List.of(a1, a2, b1), (context, decisions) -> {
            decisions.forEach(d -> refunds.recordDecision(context, d.customerId(), d.bookingId(),
                    d.terminalHistoryId()));
            return Map.of();
        })).rootCause().hasMessageContaining("One immutable Loyalty command context per transaction");

        assertNothingCommitted();
    }

    @Test
    void aMissingDecisionFailsAtCommitAndNothingCommits() {
        List<String> bodyFinished = new ArrayList<>();

        assertThatThrownBy(() -> run(bothCustomers(), List.of(a1, a2, b1), (context, decisions) -> {
            Map<UUID, UUID> partial = refunds.recordDecisions(context, decisions.stream()
                    .filter(d -> !d.bookingId().equals(b1.bookingId())).toList());
            bodyFinished.add("the batch returned");
            return partial;
        })).rootCause().hasMessageContaining("Terminal Booking fee decision omitted");

        assertThat(bodyFinished).as("the refusal is the deferred check at COMMIT, not the batch").hasSize(1);
        assertNothingCommitted();
    }

    @Test
    void aCustomerOutsideTheLockSetIsRefusedAndNothingCommits() {
        LockSet onlyA = LockSet.builder().users(customerA).venues(world.venue.id()).resources(world.room.id()).build();

        assertThatThrownBy(() -> run(onlyA, List.of(a1, a2), (context, decisions) -> {
            List<Decision> withB = new ArrayList<>(decisions);
            withB.add(new Decision(customerB, b1.bookingId(), creationHistory(b1.bookingId())));
            return refunds.recordDecisions(context, withB);
        })).rootCause().hasMessageContaining("All Loyalty Users must be prelocked in the shared scope");

        assertNothingCommitted();
    }

    @Test
    void aFailureMidwayRollsBackEveryDecisionOfTheBatch() {
        assertThatThrownBy(() -> run(bothCustomers(), List.of(a1, a2, b1), (context, decisions) -> {
            List<Decision> ordered = decisions.stream().sorted(ORDER).toList();
            Decision last = ordered.getLast();
            // The last decision recorded points at another booking's history: the earlier two are written first.
            UUID foreign = ordered.getFirst().terminalHistoryId();
            List<Decision> broken = new ArrayList<>(ordered.subList(0, ordered.size() - 1));
            broken.add(new Decision(last.customerId(), last.bookingId(), foreign));
            return refunds.recordDecisions(context, broken);
        })).rootCause().hasMessageContaining("Refund history belongs to another Booking");

        assertNothingCommitted();
        assertThat(OWNER.sql("""
                        SELECT count(*) FROM audit.audit_event
                         WHERE action = 'BOOKING_FEE_REFUND_DECIDED' AND correlation_id = :correlation""")
                .param("correlation", correlationId).query(Integer.class).single())
                .as("no decision audit survives either").isZero();
    }

    @Test
    void aManualBookingIsRefusedByTheRefundRulesAndTheWholeBatchRollsBack() {
        UUID manualId = manual.create(world.account.id(), ManualBookingRequest.of(world.room.id(), PlayMode.SINGLE,
                world.start().plus(Duration.ofHours(6)), 60, null, null)).bookingId();
        UUID manualHistory = creationHistory(manualId);

        assertThatThrownBy(() -> run(bothCustomers(), List.of(a1), (context, decisions) -> {
            List<Decision> withManual = new ArrayList<>(decisions);
            withManual.add(new Decision(customerA, manualId, manualHistory));
            return refunds.recordDecisions(context, withManual);
        })).rootCause().hasMessageContaining("Manual Booking has no customer refund ledger");

        assertThat(booking(a1.bookingId()).status()).isEqualTo("PENDING");
        assertThat(refundRows(a1.bookingId())).isEmpty();
        assertThat(refundRows(manualId)).isEmpty();
    }

    @Test
    void aReplayWithTheSameEvidenceAnswersTheSameDecisions() {
        Cancelled cancelled = run(bothCustomers(), List.of(a1, a2, b1), refunds::recordDecisions);

        UUID replayCorrelation = CorrelationId.generate();
        Map<UUID, UUID> replay;
        try (CorrelationId.Scope ignored = CorrelationId.open(replayCorrelation)) {
            replay = runner.run(new TestCommand<>(bothCustomers(),
                    context -> refunds.recordDecisions(context, cancelled.decisions())));
        }

        assertThat(replay).isEqualTo(cancelled.result());
        for (CreatedBooking created : List.of(a1, a2, b1)) {
            assertThat(refundRows(created.bookingId())).singleElement().satisfies(refund ->
                    assertThat(refund.correlationId()).as("the first decision stands").isEqualTo(correlationId));
        }
    }

    // ------------------------------------------------------------------------------------------------------ helpers

    /** The decisions given to the batch, and what it answered. */
    record Cancelled(List<Decision> decisions, Map<UUID, UUID> result) {
    }

    record RefundRow(UUID id, UUID userId, UUID bookingHistoryId, String treatment, String terminalReason,
                     BigDecimal amount, BigDecimal feeSnapshot, UUID correlationId) {
    }

    private LockSet bothCustomers() {
        return LockSet.builder().users(customerA, customerB).venues(world.venue.id()).resources(world.room.id())
                .build();
    }

    /**
     * One command: each PENDING booking cancelled as the production stores do it (state, reservation, history row,
     * T1 withdrawn), then {@code loyalty} decides on the cancelled bookings, given in booking-creation order.
     */
    private Cancelled run(LockSet locks, List<CreatedBooking> toCancel,
                          BiFunction<CommandContext, List<Decision>, Map<UUID, UUID>> loyalty) {
        return runner.run(new TestCommand<>(locks, context -> {
            List<Decision> decisions = new ArrayList<>();
            for (CreatedBooking created : toCancel) {
                decisions.add(cancel(context, created));
            }
            return new Cancelled(List.copyOf(decisions), loyalty.apply(context, decisions));
        }));
    }

    private Decision cancel(CommandContext context, CreatedBooking created) {
        Facts facts = store.facts(context, created.bookingId()).orElseThrow();
        assertThat(facts.status()).isEqualTo(BookingStatus.PENDING);
        ActiveReservation reservation =
                ledger.activeOf(context, ReservationOwner.booking(created.bookingId())).orElseThrow();
        assertThat(store.terminate(context, created.bookingId(), BookingStatus.PENDING, BookingStatus.CANCELLED,
                REASON, context.commandNow(), null)).isTrue();
        ledger.release(context, reservation.id(), context.commandNow(), REASON.name());
        UUID history = store.insertTransition(context, created.bookingId(), context.commandNow(),
                BookingStatus.PENDING, BookingStatus.CANCELLED, REASON, ActorType.SYSTEM, null,
                CorrelationId.require());
        jobs.supersede(context, VenueAnswers.venueResponseExpiryKey(created.bookingId()));
        return new Decision(facts.customerId(), created.bookingId(), history);
    }

    private void assertNothingCommitted() {
        for (CreatedBooking created : List.of(a1, a2, b1)) {
            assertThat(booking(created.bookingId()).status()).as("rolled back").isEqualTo("PENDING");
            assertThat(VenueAnswerDb.history(created.bookingId())).hasSize(1);
            assertThat(refundRows(created.bookingId())).isEmpty();
        }
    }

    private static UUID creationHistory(UUID bookingId) {
        return OWNER.sql("SELECT id FROM booking.booking_history WHERE booking_id = :id AND sequence = 1")
                .param("id", bookingId).query(UUID.class).single();
    }

    private static List<RefundRow> refundRows(UUID bookingId) {
        return OWNER.sql("""
                        SELECT id, user_id, booking_history_id, treatment, terminal_reason, amount, fee_snapshot,
                               correlation_id
                          FROM loyalty.booking_fee_refund WHERE booking_id = :id""")
                .param("id", bookingId)
                .query((rs, n) -> new RefundRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("booking_history_id", UUID.class), rs.getString("treatment"),
                        rs.getString("terminal_reason"), rs.getBigDecimal("amount"), rs.getBigDecimal("fee_snapshot"),
                        rs.getObject("correlation_id", UUID.class)))
                .list();
    }

    /** A category B command (existing commitments) under the given lock set. */
    private record TestCommand<R>(LockSet locks, Function<CommandContext, R> body)
            implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(locks);
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }
}
