package ps.e7gz.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;

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
import ps.e7gz.support.boundary.TestCommands;

/**
 * Emergency pause and resume of a response timer (arch 08 sections 2.5 and 9; FR-OPS-34...41). The E7-45 boundary tests,
 * moved here from {@code support/boundary/pending} by E7-83 and filled in on the real database: real Emergencies, the
 * real worker for {@code TIMER_PAUSE_CHECK} and T1.
 */
class TimerPauseResumeBoundaryTest extends TimerPauseTestBase {

    @Test
    void aTenMinuteTimerPausedAfterThreeMinutesResumesWithSevenMinutesLeft() throws Exception {
        // FR-OPS-36: T1 re-armed at resume + 7 min, to the second (DueJobs.job(key).dueAt()).
        BookingFlows.Booking booking = flows.pending();
        UUID emergency = declareVenue(booking, soon());
        Instant pausedAt = responsePausedSince(booking.bookingId());
        assertThat(pausedAt).as("paused by the declaration").isNotNull();
        // 3 of its 10 minutes had run when it paused: 7 were left
        set("booking.booking.venue_response_expires_at", booking.bookingId(), pausedAt.plus(Duration.ofMinutes(7)));

        end(booking, emergency);

        List<PauseRow> rows = pauseRows(booking.bookingId());
        assertThat(rows).hasSize(1);
        Instant resumedAt = rows.getFirst().endedAt();
        assertThat(resumedAt).as("the pause closed when the Emergency ended").isNotNull();
        String t1 = BookingFlows.responseExpiryKey(booking.bookingId());
        assertThat(DueJobs.job(t1).status()).isEqualTo("PENDING");
        assertThat(DueJobs.job(t1).dueAt()).isEqualTo(resumedAt.plus(Duration.ofMinutes(7)));
        assertThat(responseDeadline(booking.bookingId())).isEqualTo(resumedAt.plus(Duration.ofMinutes(7)));
        assertThat(responsePausedSince(booking.bookingId())).isNull();
        assertThat(responseCounter(booking.bookingId())).isEqualTo(Duration.between(pausedAt, resumedAt));
        assertThat(rows.getFirst().accumulated()).isEqualTo(Duration.between(pausedAt, resumedAt));
        assertThat(events(IntegrationEvents.TIMER_RESUMED, booking.bookingId())).isOne();
        assertThat(statusOf(booking.bookingId())).isEqualTo("PENDING/-");
    }

    @Test
    void twelveMinutesVenuePauseThenNineteenResourcePauseCancelsAtThirtyUnderTheResourceReason() throws Exception {
        // FR-OPS-37, FR-OPS-38: the 30-minute limit is reached during the resource pause, so the reason is the
        // resource's.
        BookingFlows.Booking booking = flows.pending();
        UUID venueEmergency = declareVenue(booking, soon());
        // the venue pause has run 12 minutes when the venue Emergency ends (the database clock cannot be moved, so the
        // booking's pause instant is)
        set("booking.booking.response_pause_started_at", booking.bookingId(),
                responsePausedSince(booking.bookingId()).minus(Duration.ofMinutes(12)));
        end(booking, venueEmergency);
        Duration afterVenue = responseCounter(booking.bookingId());
        assertThat(afterVenue).isBetween(Duration.ofMinutes(12), Duration.ofMinutes(13));
        assertThat(statusOf(booking.bookingId())).as("resumed").isEqualTo("PENDING/-");

        declareResource(booking, soon());
        Instant resourcePausedAt = responsePausedSince(booking.bookingId());
        assertThat(resourcePausedAt).as("paused again, by the resource").isNotNull();
        // the resource pause has run long enough to reach 30 minutes in total two seconds from now - 12 + 18 of its 19
        Duration toCap = Duration.ofMinutes(30).minus(afterVenue);
        Instant capAt = dbNow().plusSeconds(2);
        set("booking.booking.response_pause_started_at", booking.bookingId(), capAt.minus(toCap));
        Deadlines.awaitDatabaseClockPast(capAt.plusSeconds(1));

        assertThat(fireChecks(booking)).isTrue();

        assertThat(statusOf(booking.bookingId()))
                .isEqualTo("CANCELLED/" + BookingTerminalReason.RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY);
        assertThat(instant("SELECT terminal_at FROM booking.booking WHERE id = :id", booking.bookingId()))
                .as("at the 30-minute mark, not when the check ran").isEqualTo(capAt);
        assertThat(responseCounter(booking.bookingId())).isEqualTo(Duration.ofMinutes(30));
        List<PauseRow> rows = pauseRows(booking.bookingId());
        assertThat(rows).extracting(PauseRow::cause)
                .containsExactly("VENUE_EMERGENCY_CLOSURE", "RESOURCE_EMERGENCY_OUTAGE");
        assertThat(rows.get(1).endedAt()).isEqualTo(capAt);
        assertThat(rows.get(1).accumulated()).isEqualTo(Duration.ofMinutes(30));
        assertThat(events(IntegrationEvents.TIMER_PAUSE_CAP_REACHED, booking.bookingId())).isOne();
        assertThat(events(IntegrationEvents.BOOKING_CANCELLED, booking.bookingId())).isOne();
        assertThat(count("SELECT count(*) FROM booking.booking_history WHERE booking_id = '" + booking.bookingId()
                + "' AND to_status = 'CANCELLED' AND actor_type = 'SYSTEM'")).as("no venue blame").isOne();
        assertThat(count("SELECT count(*) FROM inventory.resource_reservation WHERE booking_id = '"
                + booking.bookingId() + "' AND active_flag")).as("released").isZero();
        assertThat(count("SELECT count(*) FROM loyalty.booking_fee_refund WHERE booking_id = '"
                + booking.bookingId() + "'")).as("no penalty: the fee decision").isOne();
    }

    @Test
    void anEmergencyEndingWhileTheVenueIsClosedDoesNotRestartTheTimer() throws Exception {
        // FR-OPS-39: the timer waits for the next valid intake window.
        BookingFlows.Booking booking = flows.pending();
        UUID emergency = declareVenue(booking, dbNow().plus(Duration.ofMinutes(4)));
        Instant pausedAt = responsePausedSince(booking.bookingId());
        // the session now ends in 5 minutes - inside its last 10, intake is closed - and the next opens in 15
        Instant nextOpens = dbNow().plus(Duration.ofMinutes(15));
        splitSession(booking.venueId(), dbNow().plus(Duration.ofMinutes(5)), nextOpens);

        end(booking, emergency);

        assertThat(responsePausedSince(booking.bookingId())).as("still paused").isEqualTo(pausedAt);
        assertThat(DueJobs.job(BookingFlows.responseExpiryKey(booking.bookingId())).status()).isEqualTo("PAUSED");
        assertThat(pauseRows(booking.bookingId())).singleElement()
                .satisfies(row -> assertThat(row.endedAt()).isNull());
        assertThat(waitingChecks(booking.bookingId())).as("checked again when the next window opens")
                .contains(nextOpens.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        // PO decision 12 Oct 2026: an Accept on a pause still waiting after its emergency ended
        assertThat(flows.acceptRefusal(booking)).isEqualTo(ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE);

        // the next intake window opens (moved to now: the clock cannot be), and the check resumes it
        Instant opened = dbNow();
        splitSession(booking.venueId(), opened, opened);
        assertThat(fireChecks(booking)).isTrue();

        assertThat(responsePausedSince(booking.bookingId())).isNull();
        PauseRow row = pauseRows(booking.bookingId()).getFirst();
        assertThat(row.endedAt()).isAfter(opened);
        assertThat(DueJobs.job(BookingFlows.responseExpiryKey(booking.bookingId())).status()).isEqualTo("PENDING");
        assertThat(responseDeadline(booking.bookingId()))
                .isEqualTo(row.endedAt().plus(Duration.between(pausedAt, booking.venueResponseExpiresAt())));
    }

    @Test
    void feasibilityOnIdenticalStateIsTheSameDecisionEveryTime() throws Exception {
        // FR-OPS-40.
        BookingFlows.Booking booking = flows.pending();
        declareVenue(booking, soon());

        List<TimerPauses.Decision> decisions = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            decisions.add(runner.run(TestCommands.under(flows.scope(booking),
                    command -> timers.reconsider(command, booking.bookingId()))));
        }
        assertThat(decisions).extracting(TimerPauses.Decision::outcome).containsOnly(TimerPauses.Outcome.PAUSED);
        assertThat(decisions).extracting(TimerPauses.Decision::nextCheck).as("the cap: the same instant every time")
                .containsOnly(decisions.getFirst().nextCheck());
        assertThat(pauseRows(booking.bookingId())).singleElement()
                .satisfies(row -> assertThat(row.endedAt()).isNull());
        assertThat(statusOf(booking.bookingId())).isEqualTo("PENDING/-");
    }
}
