package ps.e7gz.operations.api;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.command.CommandContext;

/**
 * The venue-response timer pause history of a Booking ({@code operations.booking_timer_pause_history}, FR-OPS-41).
 * Added for the venue's Reject on a paused timer (E7-47 review, PO decision 10 Oct 2026): FR-OPS-34 disables only
 * Accept, so the venue may still reject, and the open pause is closed with the rejection. Runs on the command's own
 * transaction.
 */
public interface ResponseTimerPauses {

    /**
     * Closes the Booking's open {@code VENUE_RESPONSE} pause row once ({@code tr_booking_timer_pause_history_immutable_close}):
     * {@code pause_ended_at = endedAt}, {@code accumulated_duration = accumulated} - the Booking's cumulative pause
     * duration including this pause (FR-OPS-37).
     *
     * @throws IllegalStateException when the Booking has no open {@code VENUE_RESPONSE} pause row - an integrity defect
     */
    void closeOpen(CommandContext command, UUID bookingId, Instant endedAt, Duration accumulated);

    /**
     * Closes the Booking's open {@code LATE_CONFIRM} pause row once, as {@link #closeOpen} does for the response timer:
     * the customer may Decline while the Late Confirm countdown is paused (FR-OPS-41; E7-58), and the pause ends with the
     * Decline. {@code accumulated} is the Booking's cumulative Late Confirm pause including this pause (FR-OPS-37).
     *
     * @throws IllegalStateException when the Booking has no open {@code LATE_CONFIRM} pause row - an integrity defect
     */
    void closeOpenLateConfirm(CommandContext command, UUID bookingId, Instant endedAt, Duration accumulated);
}
