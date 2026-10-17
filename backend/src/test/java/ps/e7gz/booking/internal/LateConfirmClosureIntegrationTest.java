package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.booking.internal.LateConfirmDb.attempt;
import static ps.e7gz.booking.internal.LateConfirmDb.reservations;
import static ps.e7gz.booking.internal.LateConfirmDb.t2;
import static ps.e7gz.booking.internal.LateConfirmDb.t3;
import static ps.e7gz.booking.internal.LateConfirmIntegrationTest.ok;
import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.count;
import static ps.e7gz.booking.internal.VenueAnswerDb.events;
import static ps.e7gz.booking.internal.VenueAnswerDb.job;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.booking.api.LateConfirmClosures;
import ps.e7gz.booking.internal.LateConfirmDb.Attempt;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmExpiryHandler;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmWarningHandler;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmations;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.loyalty.api.BookingFeeRefunds;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.support.boundary.TestCommands;

/**
 * E7-113 Phase 3A - {@link LateConfirmClosures}: the Late Confirmation side of an operational cancellation of an
 * AWAITING booking (FR-VEN-28), on bookings revived by the real Initiate, every transaction committed so the deferred
 * integrity triggers run. The port writes no Booking transition; where a test needs a committed cancellation it
 * writes the transition, history row and Booking Fee decision itself in the same command, through the same Booking
 * operations a cancellation uses, as the Booking orchestrator (Phase 3B/3C) will.
 */
class LateConfirmClosureIntegrationTest extends IntegrationTest {

    private static final BookingTerminalReason REASON = BookingTerminalReason.VENUE_OPENING_HOURS_CHANGED;
    private static final BookingStatus AWAITING = BookingStatus.AWAITING_CUSTOMER_CONFIRMATION;

    /** Every Late Confirm integration event: the closure writes none of them (D2: O5 writes BookingCancelled). */
    private static final List<String> LATE_CONFIRM_EVENTS = List.of(IntegrationEvents.LATE_CONFIRM_ACCEPTED,
            IntegrationEvents.LATE_CONFIRM_DECLINED, IntegrationEvents.LATE_CONFIRM_TIMED_OUT,
            IntegrationEvents.LATE_CONFIRM_WARNING_DUE);

    @Autowired
    ApplicationContext context;

    @Autowired
    CommandRunner runner;

    @Autowired
    OnlineBookingCreation creation;

    @Autowired
    VenueAnswerStore store;

    @Autowired
    VenueAnswers answers;

    @Autowired
    LateConfirmations lateConfirmations;

    @Autowired
    LateConfirmClosures closures;

    @Autowired
    BookingFeeRefunds refunds;

    private LateConfirmDb db;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        db = new LateConfirmDb(runner, creation, store, answers);
        correlation = CorrelationId.open(UUID.randomUUID());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ---------------------------------------------------------------- closure with the booking's cancellation

    @Test
    void closesTheAttemptWithoutAResponseReleasesTheHoldAndSupersedesT2AndT3() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        Attempt open = attempt(late).orElseThrow();
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(job(t3(late)).orElseThrow().status()).as("a five-minute window has a warning").isEqualTo("PENDING");
        List<String> bookingEventsBefore = VenueAnswerDb.outboxTypes(late);
        int historyBefore = LateConfirmDb.history(late).size();

        Cancellation cancellation = closeAndCancel(world, late);
        Instant at = cancellation.at();

        Attempt closed = attempt(late).orElseThrow();
        assertThat(closed.id()).isEqualTo(open.id());
        assertThat(closed.closedAt()).isEqualTo(at);
        assertThat(closed.response()).as("no Customer response is invented").isNull();
        assertThat(closed.respondedAt()).isNull();
        assertThat(closed.expiresAt()).as("the deadline stays as recorded").isEqualTo(open.expiresAt());
        assertThat(LateConfirmDb.warningSentAt(late)).isNull();
        assertThat(reservations(late)).filteredOn(r -> r.id().equals(open.holdId())).singleElement()
                .satisfies(hold -> {
                    assertThat(hold.type()).isEqualTo("LATE_CONFIRM_HOLD");
                    assertThat(hold.active()).isFalse();
                    assertThat(hold.releasedAt()).isEqualTo(at);
                    assertThat(hold.releaseReason()).isEqualTo(REASON.name());
                });
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, open.holdId())).hasSize(1);
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(job(t3(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");

        VenueAnswerDb.BookingRow row = booking(late);
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo(REASON.name());
        assertThat(row.terminalAt()).isEqualTo(at);
        assertThat(count("loyalty.booking_fee_refund WHERE booking_id = :id", late)).isEqualTo(1);
        assertIntegrityHolds(late);

        // the committed history: exactly one new row - the terminal transition the same command wrote
        List<LateConfirmDb.Event> history = LateConfirmDb.history(late);
        assertThat(history).hasSize(historyBefore + 1);
        assertThat(history.getLast()).satisfies(transition -> {
            assertThat(transition.entryKind()).isEqualTo("STATUS_TRANSITION");
            assertThat(transition.fromStatus()).isEqualTo(AWAITING.name());
            assertThat(transition.toStatus()).isEqualTo(BookingStatus.CANCELLED.name());
            assertThat(transition.terminalReason()).isEqualTo(REASON.name());
            assertThat(transition.eventType()).isNull();
            assertThat(transition.actorType()).as("the fixture's actor").isEqualTo(ActorType.SYSTEM.name());
            assertThat(transition.actorId()).as("SYSTEM records no actor id").isNull();
            assertThat(transition.occurredAt()).as("the command instant").isEqualTo(at);
        });
        // ...and it is the row the Booking Fee decision of that transaction rests on, for this booking and customer
        Map<String, Object> refund = OWNER.sql("""
                        SELECT r.booking_history_id, r.user_id, r.terminal_reason, r.treatment,
                               h.booking_id AS history_booking_id, h.sequence AS history_sequence
                          FROM loyalty.booking_fee_refund r
                          JOIN booking.booking_history h ON h.id = r.booking_history_id
                         WHERE r.booking_id = :id""")
                .param("id", late).query().singleRow();
        assertThat(refund.get("booking_history_id")).as("the history row written in that command")
                .isEqualTo(cancellation.historyId());
        assertThat(refund.get("history_booking_id")).isEqualTo(late);
        assertThat(((Number) refund.get("history_sequence")).longValue()).isEqualTo(history.getLast().sequence());
        assertThat(refund.get("user_id")).isEqualTo(world.customer.id());
        assertThat(refund.get("terminal_reason")).isEqualTo(REASON.name());
        assertThat(refund.get("treatment")).isEqualTo("FULL_REFUND");

        // the closure writes no event on the booking: no Late Confirm event, so no extra customer notification
        assertThat(VenueAnswerDb.outboxTypes(late)).isEqualTo(bookingEventsBefore);
        LATE_CONFIRM_EVENTS.forEach(type -> assertThat(events(type, late)).as(type).isEmpty());
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, late)).as("the caller's, not the port's").isEmpty();
    }

    @Test
    void aSentWarningAndAFinishedT3StayAsRecorded() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        LateConfirmDb.deadlineAt(late, OnlineBookingWorld.dbNow().plusSeconds(30));
        fireNow(late, t3(late));
        Attempt open = attempt(late).orElseThrow();
        Instant warned = LateConfirmDb.warningSentAt(late);
        assertThat(warned).isNotNull();
        assertThat(job(t3(late)).orElseThrow().status()).isEqualTo("DONE");

        Instant at = closeAndCancel(world, late).at();

        Attempt closed = attempt(late).orElseThrow();
        assertThat(closed.closedAt()).isEqualTo(at);
        assertThat(closed.expiresAt()).isEqualTo(open.expiresAt());
        assertThat(LateConfirmDb.warningSentAt(late)).isEqualTo(warned);
        assertThat(job(t3(late)).orElseThrow().status()).as("a DONE job is never superseded").isEqualTo("DONE");
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late)).as("only T3's own").hasSize(1);
        assertIntegrityHolds(late);
    }

    @Test
    void aPausedWindowEndsItsPauseAndKeepsItsAccounting() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        LateConfirmDb.lateConfirmPaused(late);
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("PAUSED");

        Instant at = closeAndCancel(world, late).at();

        assertThat(LateConfirmDb.openLateConfirmPauses(late)).isZero();
        PauseFacts pause = pause(late);
        assertThat(pause.pausedNow()).isFalse();
        assertThat(pause.endedAt()).isEqualTo(at);
        assertThat(pause.cumulativeSeconds()).as("FR-OPS-37: the pause is added to the counter").isPositive();
        assertThat(pause.rowSeconds()).as("FR-OPS-41: the row records the same cumulative duration")
                .isEqualByComparingTo(pause.cumulativeSeconds());
        assertThat(job(t2(late)).orElseThrow().status()).as("a PAUSED job is superseded").isEqualTo("SUPERSEDED");
        assertThat(job(t3(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(attempt(late).orElseThrow().closedAt()).isEqualTo(at);
        assertIntegrityHolds(late);
    }

    @Test
    void aLeasedT2IsLeftToItsWorkerWhichThenSupersedesItself() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        leased(t2(late));

        closeAndCancel(world, late);

        assertThat(job(t2(late)).orElseThrow().status()).as("never taken from its lease holder").isEqualTo("LEASED");
        assertThat(job(t3(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(fireDirectly(late, "LATE_CONFIRM_EXPIRY")).isInstanceOf(JobOutcome.Superseded.class);
        assertThat(fireDirectly(late, "LATE_CONFIRM_WARNING")).isInstanceOf(JobOutcome.Superseded.class);
        assertThat(booking(late).terminalReason()).isEqualTo(REASON.name());
        LATE_CONFIRM_EVENTS.forEach(type -> assertThat(events(type, late)).as(type).isEmpty());
    }

    // ---------------------------------------------------------------- refused: nothing changes

    @Test
    void closingWithoutTheBookingsCancellationIsRefusedAtCommit() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        Attempt open = attempt(late).orElseThrow();

        assertThatThrownBy(() -> runner.run(TestCommands.under(scope(late), command -> {
            closures.closeForCancellation(command, late, REASON, command.commandNow());
            return null;
        }))).hasStackTraceContaining("Late Confirmation closed without Customer response requires");

        assertNothingChanged(late, open);
    }

    @Test
    void aLostHoldIsAnIntegrityFailure() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        LateConfirmDb.holdLost(late);

        assertThatThrownBy(() -> closeAndCancel(world, late))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("holds no active hold");

        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(attempt(late).orElseThrow().closedAt()).isNull();
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(count("loyalty.booking_fee_refund WHERE booking_id = :id", late)).isZero();
    }

    @Test
    void aMissingOpenAttemptIsAnIntegrityFailure() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        UUID hold = attempt(late).orElseThrow().holdId();
        LateConfirmDb.attemptLost(late);

        assertThatThrownBy(() -> closeAndCancel(world, late))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no open Late Confirmation");

        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(reservations(late)).filteredOn(r -> r.id().equals(hold)).singleElement()
                .satisfies(reservation -> assertThat(reservation.active()).isTrue());
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("PENDING");
    }

    @Test
    void aBookingThatIsNotAwaitingIsAnIntegrityFailure() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID expired = db.expired(world, world.start(), 60);
        UUID pending = creation.create(world.request(world.start().plusSeconds(3 * 3600), 60)).bookingId();

        for (UUID bookingId : List.of(expired, pending)) {
            String before = booking(bookingId).status();
            List<LateConfirmDb.Reservation> reservationsBefore = reservations(bookingId);
            assertThatThrownBy(() -> runner.run(TestCommands.under(scope(bookingId), command -> {
                closures.closeForCancellation(command, bookingId, REASON, command.commandNow());
                return null;
            }))).isInstanceOf(IllegalStateException.class).hasMessageContaining("has no Late Confirmation to close");
            assertThat(booking(bookingId).status()).isEqualTo(before);
            assertThat(reservations(bookingId)).isEqualTo(reservationsBefore);
        }
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> runner.run(TestCommands.under(CommandScope.shared(LockSet.empty()), command -> {
                    closures.closeForCancellation(command, unknown, REASON, command.commandNow());
                    return null;
                }))).isInstanceOf(IllegalStateException.class).hasMessageContaining("no such booking");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * One committed command under the booking's locks: the closure, then the Booking side of the cancellation the
     * future orchestrator writes - AWAITING -> CANCELLED, its history row and its Booking Fee decision - all at the
     * command instant. Returns that instant and the id of the history row it wrote.
     */
    private Cancellation closeAndCancel(OnlineBookingWorld world, UUID late) {
        return runner.run(TestCommands.under(scope(late), command -> {
            Instant now = command.commandNow();
            closures.closeForCancellation(command, late, REASON, now);
            assertThat(store.terminate(command, late, AWAITING, BookingStatus.CANCELLED, REASON, now, null))
                    .isTrue();
            UUID history = store.insertTransition(command, late, now, AWAITING, BookingStatus.CANCELLED, REASON,
                    ActorType.SYSTEM, null, CorrelationId.require());
            refunds.recordDecision(command, world.customer.id(), late, history);
            return new Cancellation(now, history);
        }));
    }

    private record Cancellation(Instant at, UUID historyId) {
    }

    /** The database's own final-state assertion of the attempt, called again on the committed rows. */
    private static void assertIntegrityHolds(UUID late) {
        assertThat(OWNER.sql("SELECT 1 FROM (SELECT booking.assert_late_confirmation_row(:id)) checked")
                .param("id", attempt(late).orElseThrow().id()).query(Integer.class).single()).isEqualTo(1);
    }

    private static void assertNothingChanged(UUID late, Attempt open) {
        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        Attempt now = attempt(late).orElseThrow();
        assertThat(now.closedAt()).isNull();
        assertThat(now.expiresAt()).isEqualTo(open.expiresAt());
        assertThat(reservations(late)).filteredOn(r -> r.id().equals(open.holdId())).singleElement()
                .satisfies(hold -> assertThat(hold.active()).isTrue());
        assertThat(events(IntegrationEvents.RESERVATION_RELEASED, open.holdId())).isEmpty();
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(job(t3(late)).orElseThrow().status()).isEqualTo("PENDING");
    }

    private UUID awaiting(OnlineBookingWorld world) {
        UUID late = db.expired(world, world.start(), 60);
        ok(lateConfirmations.initiate(world.account.id(), late));
        return late;
    }

    private CommandScope scope(UUID bookingId) {
        return CommandScope.shared(lateConfirmations.lockSetOf(bookingId));
    }

    /** Makes the job due now and lets the real deadline worker fire it once. */
    private void fireNow(UUID bookingId, String key) {
        DueJobs due = DueJobs.of(context, new LateConfirmExpiryHandler(lateConfirmations),
                new LateConfirmWarningHandler(lateConfirmations));
        runner.run(TestCommands.under(scope(bookingId), command -> {
            due.makeDue(command, key, command.commandNow());
            return null;
        }));
        assertThat(due.fire(key)).as("%s fired", key).isTrue();
    }

    /** The handler's decision, in one command under the booking's locks - as the lease holder's fire would run it. */
    private JobOutcome fireDirectly(UUID bookingId, String type) {
        ScheduledJob job = new ScheduledJob(UUID.randomUUID(), type, "BOOKING", bookingId, Instant.EPOCH, 1,
                type + ":" + bookingId, CorrelationId.generate());
        return runner.run(TestCommands.under(scope(bookingId), command -> type.equals("LATE_CONFIRM_EXPIRY")
                ? lateConfirmations.expire(command, job) : lateConfirmations.warn(command, job)));
    }

    /** TEST-ONLY: a worker holds the job LEASED right now (the lease columns its lifecycle shape requires). */
    private static void leased(String key) {
        VenueAnswerDb.withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE technical.scheduled_job
                           SET status = 'LEASED', leased_at = now(), leased_until = now() + interval '5 minutes',
                               leased_by = 'E7-113 test'
                         WHERE idempotency_key = :key""")
                .param("key", key).update());
    }

    private record PauseFacts(boolean pausedNow, BigDecimal cumulativeSeconds, Instant endedAt,
                              BigDecimal rowSeconds) {
    }

    private static PauseFacts pause(UUID bookingId) {
        return OWNER.sql("""
                        SELECT b.late_confirm_pause_started_at IS NOT NULL AS paused_now,
                               EXTRACT(EPOCH FROM b.cumulative_late_confirm_emergency_pause_duration) AS cumulative,
                               p.pause_ended_at, EXTRACT(EPOCH FROM p.accumulated_duration) AS row_seconds
                          FROM booking.booking b
                          JOIN operations.booking_timer_pause_history p
                            ON p.booking_id = b.id AND p.pause_kind = 'LATE_CONFIRM'
                         WHERE b.id = :id""")
                .param("id", bookingId)
                .query((rs, n) -> new PauseFacts(rs.getBoolean("paused_now"), rs.getBigDecimal("cumulative"),
                        rs.getObject("pause_ended_at", OffsetDateTime.class).toInstant(),
                        rs.getBigDecimal("row_seconds")))
                .single();
    }
}
