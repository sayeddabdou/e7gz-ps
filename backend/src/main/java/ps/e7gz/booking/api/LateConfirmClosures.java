package ps.e7gz.booking.api;

import java.time.Instant;
import java.util.UUID;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.command.CommandContext;

/**
 * The Late Confirmation side of an operational cancellation of an AWAITING_CUSTOMER_CONFIRMATION booking (E7-113: an
 * opening-hours change, FR-VEN-28; arch 06 section 2.4). Declared here and implemented by the Late Confirmation module,
 * which owns {@code booking.late_confirmation} (arch 03 section 12), so Booking cancels an AWAITING booking without
 * depending on that module - as {@link LateConfirmWindows} does for reads.
 *
 * <p>Runs on the caller's command transaction, after {@code begin_command} has locked the booking's Customer (with
 * their Bookings, Late Confirmations and reservations), Venue and Resource. It writes no Booking transition, no history
 * row and no Booking Fee decision: the caller terminates the booking under {@code reason} in the same transaction. The
 * database refuses the commit otherwise ({@code tr_late_confirmation_integrity}: an attempt closed without a Customer
 * response needs its booking CANCELLED under a Product-defined reason).
 *
 * <p>Events: it publishes no {@code BookingCancelled} and no Late Confirm integration event ({@code LateConfirm*});
 * {@code BookingCancelled} is the caller's. Releasing the hold through the {@code ReservationLedger} publishes that
 * ledger's existing {@code ReservationReleased}, as every release does.
 */
public interface LateConfirmClosures {

    /**
     * Closes the booking's open Late Confirmation without a Customer response, at {@code at}: a paused window's pause
     * ends (FR-OPS-37, FR-OPS-41), the {@code LATE_CONFIRM_HOLD} is released with {@code reason} as its release reason,
     * the attempt's {@code closed_at} is set (its deadline and warning stay as recorded) and T2/T3 are superseded - a
     * job a worker holds LEASED is left to it, and its fire re-reads the booking.
     *
     * <p>A booking that is not an AWAITING Online Booking, or whose open attempt or hold is missing or does not match
     * it, is a data defect: {@link IllegalStateException}, and the caller's command rolls back.
     *
     * <p>Reasons: E7-113 (O5) uses {@code VENUE_OPENING_HOURS_CHANGED}. The attempt is closed without a Customer
     * response, so the booking must end in a Product-defined operational cancellation of an AWAITING booking in the
     * same transaction - the database decides which: {@code booking.guard_booking_transition} (AWAITING -> CANCELLED)
     * and {@code booking.assert_late_confirmation_row} (an attempt closed without a response). This method does not
     * check the reason itself. {@code CUSTOMER_DECLINED_LATE_CONFIRM} and {@code CUSTOMER_RECONFIRM_TIMEOUT} must not
     * be used here: they mean the Customer declined or the window timed out, which the database accepts only with the
     * matching {@code DECLINED} / {@code TIMED_OUT} response (Decline and T2), so an attempt closed here under either
     * is refused at commit.
     *
     * @param reason the terminal reason the caller is cancelling the booking under - for E7-113,
     *               {@code VENUE_OPENING_HOURS_CHANGED}; also the hold's release reason
     * @param at     the caller's command instant
     */
    void closeForCancellation(CommandContext command, UUID bookingId, BookingTerminalReason reason, Instant at);
}
