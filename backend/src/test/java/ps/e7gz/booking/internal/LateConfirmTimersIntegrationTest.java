package ps.e7gz.booking.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.booking.internal.LateConfirmDb.attempt;
import static ps.e7gz.booking.internal.LateConfirmDb.history;
import static ps.e7gz.booking.internal.LateConfirmDb.reservations;
import static ps.e7gz.booking.internal.LateConfirmDb.t2;
import static ps.e7gz.booking.internal.LateConfirmDb.t3;
import static ps.e7gz.booking.internal.LateConfirmIntegrationTest.ok;
import static ps.e7gz.booking.internal.LateConfirmIntegrationTest.race;
import static ps.e7gz.booking.internal.VenueAnswerDb.booking;
import static ps.e7gz.booking.internal.VenueAnswerDb.count;
import static ps.e7gz.booking.internal.VenueAnswerDb.events;
import static ps.e7gz.booking.internal.VenueAnswerDb.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import ps.e7gz.booking.internal.LateConfirmDb.Attempt;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmExpiryHandler;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmWarningHandler;
import ps.e7gz.booking.lateconfirm.internal.LateConfirmations;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.boundary.DueJobs;
import ps.e7gz.support.boundary.TestCommands;

/**
 * E7-58 Phase 2C - T2 ({@code LATE_CONFIRM_EXPIRY}) and T3 ({@code LATE_CONFIRM_WARNING}) through the real deadline
 * worker ({@link DueJobs}, E7-45) and their handlers, on bookings revived by the real Initiate, every transaction
 * committed. An open deadline may only move later in the database, so a test that needs one in the past or a minute
 * away starts from an attempt whose stored deadline was set shorter ({@link LateConfirmDb#deadlineAt}).
 */
class LateConfirmTimersIntegrationTest extends IntegrationTest {

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
    ObjectMapper json;

    private LateConfirmDb db;
    private DueJobs due;
    private CorrelationId.Scope correlation;

    @BeforeEach
    void setUp() {
        db = new LateConfirmDb(runner, creation, store, answers);
        due = DueJobs.of(context, new LateConfirmExpiryHandler(lateConfirmations),
                new LateConfirmWarningHandler(lateConfirmations));
        correlation = CorrelationId.open(UUID.randomUUID());
    }

    @AfterEach
    void closeCorrelation() {
        correlation.close();
    }

    // ---------------------------------------------------------------- T2

    @Test
    void t2FiredLateCancelsAsOfTheStoredDeadlineWithEverythingItOwes() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        Attempt open = attempt(late).orElseThrow();
        Instant deadline = open.initiatedAt().plusMillis(1);
        LateConfirmDb.deadlineAt(late, deadline);
        Deadlines.awaitDatabaseClockPast(deadline.plusSeconds(1));
        LateConfirmDb.Amounts before = LateConfirmDb.amounts(late);

        fireNow(t2(late));

        // FR-AVL-15: fired more than a second late, the effect is dated at the stored deadline, never later
        VenueAnswerDb.BookingRow row = booking(late);
        assertThat(row.status()).isEqualTo("CANCELLED");
        assertThat(row.terminalReason()).isEqualTo("CUSTOMER_RECONFIRM_TIMEOUT");
        assertThat(row.terminalAt()).isEqualTo(deadline);
        assertThat(row.feeTreatment()).as("FR-PTS-17: not an ordinary customer cancellation").isEqualTo("FULL_REFUND");
        assertThat(row.counted()).isFalse();
        assertThat(LateConfirmDb.amounts(late)).as("same Booking ID and snapshots").isEqualTo(before);
        Attempt closed = attempt(late).orElseThrow();
        assertThat(closed.response()).isEqualTo("TIMED_OUT");
        assertThat(closed.respondedAt()).isEqualTo(deadline);
        assertThat(closed.closedAt()).isEqualTo(deadline);
        assertThat(reservations(late)).filteredOn(r -> r.id().equals(open.holdId())).singleElement()
                .satisfies(hold -> {
                    assertThat(hold.active()).isFalse();
                    assertThat(hold.releasedAt()).isEqualTo(deadline);
                    assertThat(hold.releaseReason()).isEqualTo("CUSTOMER_RECONFIRM_TIMEOUT");
                });
        assertThat(history(late).getLast()).satisfies(event -> {
            assertThat(event.fromStatus()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
            assertThat(event.toStatus()).isEqualTo("CANCELLED");
            assertThat(event.terminalReason()).isEqualTo("CUSTOMER_RECONFIRM_TIMEOUT");
            assertThat(event.actorType()).isEqualTo("SYSTEM");
            assertThat(event.occurredAt()).isEqualTo(deadline);
        });
        assertThat(count("loyalty.booking_fee_refund WHERE booking_id = :id", late)).isEqualTo(1);
        assertThat(count("trust.trust_event WHERE booking_id = :id", late)).as("no penalty").isZero();
        assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("DONE");
        assertThat(job(t3(late)).orElseThrow().status()).as("T3 superseded").isEqualTo("SUPERSEDED");
        assertThat(events(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, late)).singleElement().satisfies(payload -> {
            JsonNode event = json.readTree(payload);
            assertThat(event.get("terminalReason").asText()).isEqualTo("CUSTOMER_RECONFIRM_TIMEOUT");
            assertThat(Instant.parse(event.get("lateConfirmExpiresAt").asText())).isEqualTo(deadline);
            assertThat(event.get("venueName").asText()).isEqualTo(world.venue.name());
        });
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, late)).as("D2").isEmpty();
        assertThat(events("ReservationReleased", open.holdId())).singleElement();
    }

    @Test
    void t2FiredTwiceOrRetriedExpiresOnce() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = expiredDeadline(world);
        fireNow(t2(late));

        assertThat(due.fire(t2(late))).as("a DONE job is never fired again").isFalse();
        assertThat(fireDirectly(late, "LATE_CONFIRM_EXPIRY")).isInstanceOf(JobOutcome.Superseded.class);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, late)).hasSize(1);
        assertThat(count("loyalty.booking_fee_refund WHERE booking_id = :id", late)).isEqualTo(1);
    }

    @Test
    void t2BeforeTheStoredDeadlineIsRescheduledToItAndChangesNothing() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        Instant deadline = attempt(late).orElseThrow().expiresAt();

        assertThat(fireDirectly(late, "LATE_CONFIRM_EXPIRY")).isEqualTo(JobOutcome.rescheduleAt(deadline));
        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(events(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, late)).isEmpty();
    }

    @Test
    void t2AfterAnAcceptOrDeclineIsSuperseded() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID accepted = awaiting(world);
        ok(lateConfirmations.accept(world.customer.id(), accepted));
        UUID declined = awaiting(world, world.start().plus(Duration.ofHours(2)));
        lateConfirmations.decline(world.customer.id(), declined);

        for (UUID late : new UUID[] {accepted, declined}) {
            assertThat(job(t2(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
            assertThat(fireDirectly(late, "LATE_CONFIRM_EXPIRY")).isInstanceOf(JobOutcome.Superseded.class);
            assertThat(events(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, late)).isEmpty();
        }
        assertThat(booking(accepted).status()).isEqualTo("CONFIRMED");
        assertThat(booking(declined).terminalReason()).isEqualTo("CUSTOMER_DECLINED_LATE_CONFIRM");
    }

    /** As T1 (E7-47 D13): a paused window is not expired by the clock; the fire throws and changes nothing. */
    @Test
    void t2OnAPausedWindowThrowsAndChangesNothing() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = expiredDeadline(world);
        LateConfirmDb.lateConfirmPaused(late);

        assertThatThrownBy(() -> fireDirectly(late, "LATE_CONFIRM_EXPIRY"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("paused");
        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(attempt(late).orElseThrow().closedAt()).isNull();
        assertThat(events(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, late)).isEmpty();
    }

    @Test
    void twoT2FiresAtOnceExpireOnce() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = expiredDeadline(world);

        List<Object> outcomes = race(List.of(
                () -> fireDirectly(late, "LATE_CONFIRM_EXPIRY"),
                () -> fireDirectly(late, "LATE_CONFIRM_EXPIRY")));

        assertThat(outcomes).filteredOn(JobOutcome.Done.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(JobOutcome.Superseded.class::isInstance).hasSize(1);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, late)).hasSize(1);
    }

    // ---------------------------------------------------------------- T3

    @Test
    void t3InsideItsWindowWarnsOnceAndLeavesTheBookingAwaiting() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        Instant deadline = OnlineBookingWorld.dbNow().plusSeconds(30);
        LateConfirmDb.deadlineAt(late, deadline);

        fireNow(t3(late));

        Instant sent = LateConfirmDb.warningSentAt(late);
        assertThat(sent).isNotNull().isBefore(deadline).isAfterOrEqualTo(deadline.minusSeconds(60));
        assertThat(job(t3(late)).orElseThrow().status()).isEqualTo("DONE");
        assertThat(job(t2(late)).orElseThrow().status()).as("T2 untouched").isEqualTo("PENDING");
        assertThat(booking(late).status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late)).singleElement().satisfies(payload -> {
            JsonNode event = json.readTree(payload);
            assertThat(Instant.parse(event.get("lateConfirmExpiresAt").asText())).isEqualTo(deadline);
            assertThat(event.get("venueName").asText()).isEqualTo(world.venue.name());
            assertThat(payload).doesNotContain("phone");
        });

        // a retry, a second fire or a second worker: no second warning
        assertThat(due.fire(t3(late))).isFalse();
        assertThat(fireDirectly(late, "LATE_CONFIRM_WARNING")).isInstanceOf(JobOutcome.Superseded.class);
        assertThat(LateConfirmDb.warningSentAt(late)).isEqualTo(sent);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late)).hasSize(1);
    }

    @Test
    void t3TooEarlyIsRescheduledToAMinuteBeforeTheDeadline() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        Instant deadline = attempt(late).orElseThrow().expiresAt();

        assertThat(fireDirectly(late, "LATE_CONFIRM_WARNING"))
                .isEqualTo(JobOutcome.rescheduleAt(deadline.minusSeconds(60)));
        assertThat(LateConfirmDb.warningSentAt(late)).isNull();
        assertThat(events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late)).isEmpty();
    }

    @Test
    void t3AfterTheDeadlineSendsNoWarning() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = expiredDeadline(world);

        assertThat(fireDirectly(late, "LATE_CONFIRM_WARNING")).isInstanceOf(JobOutcome.Superseded.class);
        assertThat(LateConfirmDb.warningSentAt(late)).isNull();
        assertThat(events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late)).isEmpty();
    }

    @Test
    void t3OnAClosedAttemptSendsNoWarning() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        lateConfirmations.decline(world.customer.id(), late);

        assertThat(job(t3(late)).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(fireDirectly(late, "LATE_CONFIRM_WARNING")).isInstanceOf(JobOutcome.Superseded.class);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late)).isEmpty();
    }

    @Test
    void t3OnAPausedWindowThrowsAndChangesNothing() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        LateConfirmDb.deadlineAt(late, OnlineBookingWorld.dbNow().plusSeconds(30));
        LateConfirmDb.lateConfirmPaused(late);

        assertThatThrownBy(() -> fireDirectly(late, "LATE_CONFIRM_WARNING"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("paused");
        assertThat(LateConfirmDb.warningSentAt(late)).isNull();
        assertThat(events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late)).isEmpty();
    }

    @Test
    void twoT3FiresAtOnceWarnOnce() throws Exception {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        LateConfirmDb.deadlineAt(late, OnlineBookingWorld.dbNow().plusSeconds(30));

        List<Object> outcomes = race(List.of(
                () -> fireDirectly(late, "LATE_CONFIRM_WARNING"),
                () -> fireDirectly(late, "LATE_CONFIRM_WARNING")));

        assertThat(outcomes).filteredOn(JobOutcome.Done.class::isInstance).hasSize(1);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late)).hasSize(1);
    }

    @Test
    void aWindowOfSixtySecondsOrLessNeverGetsAWarningJob() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = db.expired(world, world.start(), 60);
        BookingStates.plant(late, BookingStates.Plant.of("VENUE_RESPONSE_EXPIRED")
                .startAt(OnlineBookingWorld.dbNow().plusSeconds(50)));
        ok(lateConfirmations.initiate(world.account.id(), late));

        assertThat(job(t3(late))).isEmpty();
        assertThat(count("technical.scheduled_job WHERE subject_id = :id AND type = 'LATE_CONFIRM_WARNING'", late))
                .isZero();
    }

    // ---------------------------------------------------------------- R1: AWAITING with no open attempt

    /** R1, as E7-47 D11: a data defect is thrown and rolled back, never superseded as if an answer had won. */
    @Test
    void t2OnAnAwaitingBookingWithNoOpenAttemptIsAnIntegrityFailureAndChangesNothing() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = expiredDeadline(world);
        UUID hold = attempt(late).orElseThrow().holdId();
        LateConfirmDb.attemptLost(late);

        assertThatThrownBy(() -> fireDirectly(late, "LATE_CONFIRM_EXPIRY"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("integrity failure");
        assertNothingChanged(late, hold);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, late)).isEmpty();
        assertThat(count("loyalty.booking_fee_refund WHERE booking_id = :id", late)).isZero();
        assertThat(job(t3(late)).orElseThrow().status()).as("T3 not superseded").isEqualTo("PENDING");
    }

    @Test
    void t3OnAnAwaitingBookingWithNoOpenAttemptIsAnIntegrityFailureAndChangesNothing() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = awaiting(world);
        UUID hold = attempt(late).orElseThrow().holdId();
        LateConfirmDb.deadlineAt(late, OnlineBookingWorld.dbNow().plusSeconds(30));
        LateConfirmDb.attemptLost(late);

        assertThatThrownBy(() -> fireDirectly(late, "LATE_CONFIRM_WARNING"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("integrity failure");
        assertNothingChanged(late, hold);
        assertThat(LateConfirmDb.warningSentAt(late)).isNull();
        assertThat(events(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, late)).isEmpty();
    }

    /**
     * Through the real worker (E7-40 D1): each failed fire rolls back and is recorded as a retry after its back-off,
     * never DONE or SUPERSEDED, until the attempts run out and the job is FAILED (Sev-2) - a bounded, not an endless,
     * loop. The booking is never touched.
     */
    @Test
    void anIntegrityFailureIsRetriedWithBackOffUntilTheJobIsFailedAndTheBookingIsNeverTouched() {
        OnlineBookingWorld world = OnlineBookingWorld.create();
        UUID late = expiredDeadline(world);
        UUID hold = attempt(late).orElseThrow().holdId();
        LateConfirmDb.attemptLost(late);

        fireNow(t2(late));
        DueJobs.JobRow retried = DueJobs.job(t2(late));
        assertThat(retried.status()).isEqualTo("PENDING");
        assertThat(retried.dueAt()).as("after its back-off").isAfter(OnlineBookingWorld.dbNow());
        assertThat(lastError(t2(late))).contains("integrity failure");

        int fires = 1;
        while (DueJobs.job(t2(late)).status().equals("PENDING")) {
            assertThat(fires).as("bounded by the worker's max attempts").isLessThan(20);
            fireNow(t2(late));
            fires++;
        }
        assertThat(DueJobs.job(t2(late)).status()).isEqualTo("FAILED");
        // E7-56: every failed fire rolled back, and the defect raised one SEV_1 alert, not one per attempt
        assertThat(VenueAnswerDb.adminAlerts(late))
                .containsExactly("BOOKING_INTEGRITY_FAILURE SEV_1 " + late + " true");
        assertNothingChanged(late, hold);
        assertThat(events(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, late)).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private static void assertNothingChanged(UUID late, UUID hold) {
        VenueAnswerDb.BookingRow row = booking(late);
        assertThat(row.status()).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION");
        assertThat(row.terminalAt()).isNull();
        assertThat(reservations(late)).filteredOn(r -> r.id().equals(hold)).singleElement()
                .satisfies(reservation -> assertThat(reservation.active()).as("the hold is kept").isTrue());
        assertThat(attempt(late).orElseThrow().response()).isNull();
        assertThat(history(late)).noneMatch(event -> "CANCELLED".equals(event.toStatus()));
    }

    private static String lastError(String key) {
        return OnlineBookingWorld.OWNER.sql("SELECT last_error FROM technical.scheduled_job WHERE idempotency_key = :key")
                .param("key", key).query(String.class).single();
    }

    private UUID awaiting(OnlineBookingWorld world) {
        return awaiting(world, world.start());
    }

    private UUID awaiting(OnlineBookingWorld world, Instant start) {
        UUID late = db.expired(world, start, 60);
        ok(lateConfirmations.initiate(world.account.id(), late));
        return late;
    }

    /** An AWAITING booking whose stored deadline lies more than a second in the past (not yet fired). */
    private UUID expiredDeadline(OnlineBookingWorld world) {
        UUID late = awaiting(world);
        Instant deadline = attempt(late).orElseThrow().initiatedAt().plusMillis(1);
        LateConfirmDb.deadlineAt(late, deadline);
        Deadlines.awaitDatabaseClockPast(deadline.plusSeconds(1));
        return late;
    }

    /** Makes the job due now (it never moves its subject) and lets the real worker fire it once. */
    private void fireNow(String key) {
        UUID booking = UUID.fromString(key.substring(key.indexOf(':') + 1));
        runner.run(TestCommands.under(scope(booking), command -> {
            due.makeDue(command, key, command.commandNow());
            return null;
        }));
        assertThat(due.fire(key)).as("%s fired", key).isTrue();
    }

    /** The handler's decision, in one command under the booking's locks, without the worker's job bookkeeping. */
    private JobOutcome fireDirectly(UUID bookingId, String type) {
        ScheduledJob job = new ScheduledJob(UUID.randomUUID(), type, "BOOKING", bookingId, Instant.EPOCH, 1,
                type + ":" + bookingId, CorrelationId.generate());
        return runner.run(TestCommands.under(scope(bookingId), command -> type.equals("LATE_CONFIRM_EXPIRY")
                ? lateConfirmations.expire(command, job) : lateConfirmations.warn(command, job)));
    }

    private CommandScope scope(UUID bookingId) {
        return CommandScope.shared(lateConfirmations.lockSetOf(bookingId));
    }
}
