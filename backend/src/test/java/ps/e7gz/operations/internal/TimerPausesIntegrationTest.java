package ps.e7gz.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.support.boundary.Deadlines;
import ps.e7gz.support.boundary.DueJobs;

/**
 * E7-83 (S5-E3) on the real database: the Late Confirm countdown's pause (FR-OPS-35, FR-OPS-41, the E7-58
 * carry-forward), the FAILED T1 re-armed on resume (Sprint 2 review, PO decision 12 Oct 2026), the feasibility test at
 * the pause (FR-OPS-40), the cause following the Emergency in force (FR-OPS-37, FR-OPS-38), the immutable history
 * (FR-OPS-41) and what the customer may still do while paused (FR-BOK-32).
 */
class TimerPausesIntegrationTest extends TimerPauseTestBase {

    // ---------------------------------------------------------------------------------- Late Confirm

    /** E7-58 carry-forward: the database accepts DECLINED only before expires_at - the pause keeps it valid. */
    @Test
    void aLateConfirmDeclineAfterTheOriginalDeadlineButDuringAPauseSucceeds() throws Exception {
        BookingFlows.Booking awaiting = flows.lateConfirmed();
        declareVenue(awaiting, soon());
        Instant pausedAt = lateConfirmPausedSince(awaiting.bookingId());
        assertThat(pausedAt).as("paused by the declaration").isNotNull();
        assertThat(events(IntegrationEvents.TIMER_PAUSED, awaiting.bookingId())).isOne();
        // the original deadline falls one second into the pause, and passes
        Instant original = pausedAt.plusSeconds(1);
        setLateConfirmDeadline(awaiting.bookingId(), original);
        Deadlines.awaitDatabaseClockPast(original.plusSeconds(1));

        assertThat(flows.declineLateConfirmReason(awaiting))
                .isEqualTo(BookingTerminalReason.CUSTOMER_DECLINED_LATE_CONFIRM);

        assertThat(statusOf(awaiting.bookingId())).isEqualTo("CANCELLED/CUSTOMER_DECLINED_LATE_CONFIRM");
        Instant responded = instant("SELECT responded_at FROM booking.late_confirmation WHERE booking_id = :id",
                awaiting.bookingId());
        assertThat(lateConfirmDeadline(awaiting.bookingId())).as("moved by the pause, never beyond the start")
                .isAfter(responded).isBeforeOrEqualTo(awaiting.startAt());
        assertThat(pauseRows(awaiting.bookingId())).singleElement()
                .satisfies(row -> assertThat(row.endedAt()).isEqualTo(responded));
        assertThat(lateConfirmPausedSince(awaiting.bookingId())).isNull();
    }

    @Test
    void aPausedLateConfirmResumesWithItsRemainingTimeAndItsJobsReArmed() throws Exception {
        BookingFlows.Booking awaiting = flows.lateConfirmed();
        UUID emergency = declareVenue(awaiting, soon());
        Instant pausedAt = lateConfirmPausedSince(awaiting.bookingId());
        Duration remaining = Duration.between(pausedAt, lateConfirmDeadline(awaiting.bookingId()));
        assertThat(DueJobs.job(BookingFlows.lateConfirmExpiryKey(awaiting.bookingId())).status()).isEqualTo("PAUSED");

        end(awaiting, emergency);

        Instant resumedAt = pauseRows(awaiting.bookingId()).getFirst().endedAt();
        Instant deadline = resumedAt.plus(remaining);
        assertThat(lateConfirmDeadline(awaiting.bookingId())).isEqualTo(deadline);
        assertThat(DueJobs.job(BookingFlows.lateConfirmExpiryKey(awaiting.bookingId())).dueAt()).isEqualTo(deadline);
        assertThat(DueJobs.job(BookingFlows.lateConfirmExpiryKey(awaiting.bookingId())).status()).isEqualTo("PENDING");
        assertThat(DueJobs.job(BookingFlows.lateConfirmWarningKey(awaiting)).dueAt())
                .isEqualTo(deadline.minusSeconds(60));
        assertThat(lateConfirmCounter(awaiting.bookingId())).isEqualTo(Duration.between(pausedAt, resumedAt));
        assertThat(statusOf(awaiting.bookingId())).isEqualTo("AWAITING_CUSTOMER_CONFIRMATION/-");
        assertThat(events(IntegrationEvents.TIMER_RESUMED, awaiting.bookingId())).isOne();
    }

    @Test
    void aLateConfirmPauseReachingThirtyMinutesCancelsUnderTheLateConfirmReason() throws Exception {
        BookingFlows.Booking awaiting = flows.lateConfirmed();
        declareVenue(awaiting, soon());
        // earlier pauses of this AWAITING phase used all but two seconds of the 30 minutes (FR-OPS-37)
        Instant pausedAt = lateConfirmPausedSince(awaiting.bookingId());
        Instant capAt = dbNow().plusSeconds(2);
        Duration used = Duration.ofMinutes(30).minus(Duration.between(pausedAt, capAt));
        withoutTriggers(jdbc -> jdbc.sql("""
                        UPDATE booking.booking
                           SET cumulative_late_confirm_emergency_pause_duration = make_interval(secs => :seconds)
                         WHERE id = :id""")
                .param("seconds", used.toNanos() / 1_000_000_000.0).param("id", awaiting.bookingId()).update());
        Deadlines.awaitDatabaseClockPast(capAt.plusSeconds(1));

        assertThat(fireChecks(awaiting)).isTrue();

        assertThat(statusOf(awaiting.bookingId()))
                .isEqualTo("CANCELLED/" + BookingTerminalReason.LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY);
        assertThat(lateConfirmCounter(awaiting.bookingId())).isEqualTo(Duration.ofMinutes(30));
        assertThat(count("SELECT count(*) FROM booking.late_confirmation WHERE booking_id = '"
                + awaiting.bookingId() + "' AND closed_at IS NOT NULL AND customer_response IS NULL")).isOne();
        assertThat(count("SELECT count(*) FROM inventory.resource_reservation WHERE booking_id = '"
                + awaiting.bookingId() + "' AND active_flag")).as("the hold released").isZero();
        assertThat(count("SELECT count(*) FROM technical.scheduled_job WHERE subject_id = '" + awaiting.bookingId()
                + "' AND type LIKE 'LATE_CONFIRM_%' AND status IN ('PENDING','PAUSED')")).isZero();
        assertThat(events(IntegrationEvents.TIMER_PAUSE_CAP_REACHED, awaiting.bookingId())).isOne();
        assertThat(count("SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id = '"
                + awaiting.bookingId() + "'")).isOne();
    }

    // ---------------------------------------------------------------------------------- T1 FAILED

    /** Sprint 2 review (PO decision 12 Oct 2026): resume re-arms T1 whether PAUSED or FAILED. */
    @Test
    void aT1ThatFiredDuringThePauseAndFailedIsReArmedAndExpiresOnceAtTheMovedDeadline() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        UUID emergency = declareVenue(booking, soon());
        Instant pausedAt = responsePausedSince(booking.bookingId());
        Duration remaining = Duration.between(pausedAt, booking.venueResponseExpiresAt());
        String t1 = BookingFlows.responseExpiryKey(booking.bookingId());

        flows.t1FailsWhilePaused(booking);
        assertThat(DueJobs.job(t1).status()).isEqualTo("FAILED");
        assertThat(statusOf(booking.bookingId())).isEqualTo("PENDING/-");

        end(booking, emergency);

        Instant moved = pauseRows(booking.bookingId()).getFirst().endedAt().plus(remaining);
        assertThat(DueJobs.job(t1).status()).isEqualTo("PENDING");
        assertThat(DueJobs.job(t1).dueAt()).isEqualTo(moved);
        assertThat(responseDeadline(booking.bookingId())).isEqualTo(moved);

        Instant expiredAt = flows.fireT1AtItsDeadline(booking);
        assertThat(statusOf(booking.bookingId())).isEqualTo("VENUE_RESPONSE_EXPIRED/-");
        assertThat(count("SELECT count(*) FROM booking.booking_history WHERE booking_id = '" + booking.bookingId()
                + "' AND to_status = 'VENUE_RESPONSE_EXPIRED' AND occurred_at = '" + expiredAt + "'")).isOne();
        assertThat(DueJobs.job(t1).status()).isEqualTo("DONE");
        assertThat(events(IntegrationEvents.VENUE_RESPONSE_EXPIRED, booking.bookingId())).as("expired once").isOne();
    }

    // ---------------------------------------------------------------------------------- feasibility at the pause

    /** FR-OPS-40: no window even if the block resolved now - cancelled at once, with no venue blame. */
    @Test
    void aPauseWithNoFeasibleWindowLeftCancelsAtOnceUnderTheInterruptedReason() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        // the venue's session now ends in 15 minutes: 5 minutes of intake left, fewer than the ~10 the timer has; the
        // next session opens only after the booking's start
        splitSession(booking.venueId(), dbNow().plus(Duration.ofMinutes(15)), booking.startAt());

        declareVenue(booking, dbNow().plus(Duration.ofMinutes(10)));

        assertThat(statusOf(booking.bookingId()))
                .isEqualTo("CANCELLED/" + BookingTerminalReason.VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY);
        assertThat(pauseRows(booking.bookingId())).singleElement()
                .satisfies(row -> assertThat(row.endedAt()).isEqualTo(row.startedAt()));
        assertThat(events(IntegrationEvents.TIMER_PAUSED, booking.bookingId())).isOne();
        assertThat(events(IntegrationEvents.TIMER_PAUSE_CAP_REACHED, booking.bookingId())).as("not the cap").isZero();
        assertThat(count("SELECT count(*) FROM booking.booking_history WHERE booking_id = '" + booking.bookingId()
                + "' AND to_status = 'CANCELLED' AND actor_type = 'SYSTEM'")).isOne();
    }

    // ---------------------------------------------------------------------------------- the cause in force

    /** FR-OPS-37: one counter for both types; the history follows the Emergency in force, the clock never resets. */
    @Test
    void aVenueEmergencyEndingWhileTheRoomsOwnEmergencyGoesOnKeepsThePauseUnderTheResourceCause() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        UUID venueEmergency = declareVenue(booking, soon());
        Instant pausedAt = responsePausedSince(booking.bookingId());
        UUID resourceEmergency = declareResource(booking, soon());

        end(booking, venueEmergency);

        assertThat(responsePausedSince(booking.bookingId())).as("the same pause runs on").isEqualTo(pausedAt);
        List<PauseRow> rows = pauseRows(booking.bookingId());
        assertThat(rows).extracting(PauseRow::cause)
                .containsExactly("VENUE_EMERGENCY_CLOSURE", "RESOURCE_EMERGENCY_OUTAGE");
        assertThat(rows.get(0).endedAt()).isEqualTo(rows.get(1).startedAt());
        assertThat(rows.get(1).endedAt()).isNull();
        assertThat(flows.acceptRefusal(booking)).isEqualTo(ApiErrorCode.RESOURCE_SELLING_BLOCK_ACTIVE);

        end(booking, resourceEmergency);

        List<PauseRow> closed = pauseRows(booking.bookingId());
        assertThat(closed.get(1).endedAt()).isNotNull();
        assertThat(closed.get(1).accumulated()).as("the whole pause, both causes")
                .isEqualTo(Duration.between(pausedAt, closed.get(1).endedAt()));
        assertThat(responseCounter(booking.bookingId())).isEqualTo(closed.get(1).accumulated());
        assertThat(responsePausedSince(booking.bookingId())).isNull();
    }

    // ---------------------------------------------------------------------------------- history, customer

    /** FR-OPS-41: a pause row is closed once and never edited after. */
    @Test
    void aClosedPauseRowIsNeverEditedAgain() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        UUID emergency = declareVenue(booking, soon());
        end(booking, emergency);
        assertThat(pauseRows(booking.bookingId())).singleElement()
                .satisfies(row -> assertThat(row.endedAt()).isNotNull());

        assertThatThrownBy(() -> OWNER.sql("""
                        UPDATE operations.booking_timer_pause_history
                           SET accumulated_duration = accumulated_duration + interval '1 second'
                         WHERE booking_id = :id""").param("id", booking.bookingId()).update())
                .hasMessageContaining("only the one-time pause close");
        assertThatThrownBy(() -> OWNER.sql("DELETE FROM operations.booking_timer_pause_history WHERE booking_id = :id")
                .param("id", booking.bookingId()).update())
                .hasMessageContaining("immutable history");
    }

    /** FR-BOK-32: the customer may still cancel a PENDING booking while its timer is paused; its check then stops. */
    @Test
    void theCustomerMayStillCancelAPausedPendingBookingAndItsCheckThenStops() throws Exception {
        BookingFlows.Booking booking = flows.pending();
        declareVenue(booking, soon());

        flows.customerCancels(booking);

        assertThat(statusOf(booking.bookingId())).isEqualTo("CANCELLED/CUSTOMER_CANCELLED");
        assertThat(pauseRows(booking.bookingId())).singleElement()
                .satisfies(row -> assertThat(row.endedAt()).isNotNull());
        fireChecks(booking);
        assertThat(OWNER.sql("""
                        SELECT status FROM technical.scheduled_job
                         WHERE type = 'TIMER_PAUSE_CHECK' AND subject_id = :id ORDER BY due_at LIMIT 1""")
                .param("id", booking.bookingId()).query(String.class).single()).isEqualTo("SUPERSEDED");
    }
}
