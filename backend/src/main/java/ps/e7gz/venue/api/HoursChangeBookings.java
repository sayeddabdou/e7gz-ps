package ps.e7gz.venue.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ps.e7gz.common.codes.BookingSource;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;

/**
 * What an opening-hours change needs from the Bookings behind affected reservation rows (FR-VEN-24, FR-VEN-25): the
 * impact preview's facts and the commit's cancellations (FR-VEN-23 step 8). Declared here and implemented by the
 * Booking module, so Venue never depends on Booking (Booking depends on Venue for its Operating Sessions). Both run on
 * the command's own transaction.
 */
public interface HoursChangeBookings {

    /** The facts of each Booking id that exists; unknown ids are left out. Read-only. */
    Map<UUID, BookingFacts> facts(CommandContext command, Collection<UUID> bookingIds);

    /**
     * E7-113: cancels exactly {@code bookingIds} - the affected set the caller computed and validated under its locks -
     * with {@code VENUE_OPENING_HOURS_CHANGED} (Spec v5.0 section 14.8): each booking's reservation is released at the
     * command instant, its history row, scheduled-job changes and {@code BookingCancelled} are written, and every
     * Online Booking's Booking Fee decision is recorded in one Loyalty batch. It never looks for other bookings to
     * cancel.
     *
     * <p>Runs on the caller's command transaction and commits nothing itself: all of the bookings are cancelled or the
     * whole command rolls back (FR-VEN-23). The caller's {@code begin_command} lock set must hold the venue, the
     * bookings' Resources and every affected Online Booking's Customer - the database refuses a Customer outside it.
     * An empty list writes nothing; a null or repeated id is refused before any write.
     *
     * <p>Cancellable here: a PENDING, CONFIRMED or AWAITING_CUSTOMER_CONFIRMATION Online Booking and a CONFIRMED Manual
     * Booking that snapshotted an Operating Session, of {@code venueId}, starting strictly after the command instant. A
     * PENDING Booking past its response deadline whose T1 has not run is still PENDING and is cancelled too. An AWAITING
     * Booking (E7-113 Phase 3C, FR-VEN-28) has its Late Confirmation closed in the same transaction - its timer stopped
     * (T2/T3 superseded, a paused window's pause ended), its {@code LATE_CONFIRM_HOLD} released and its attempt closed
     * without a Customer response - and no Late Confirm event is written; its {@code BookingCancelled} brings the
     * customer's notification. Any other id - unknown, of another venue, ACTIVE, VENUE_RESPONSE_EXPIRED, terminal, a
     * Manual Booking made outside published hours, a booking whose reservation (or, for AWAITING, open Late
     * Confirmation or hold) does not match it - is a caller defect: {@link IllegalStateException}, and the command
     * rolls back.
     *
     * <p>E7-113 Phase 3B working decisions (Amr, 15 Oct 2026; pending Sayed's final review): the history rows record
     * actor VENUE with {@code venueAccountId} (D1); a PENDING booking past its deadline is cancelled, never expired
     * here (D2); an unexpected id fails the whole command (D3). Phase 3C working decisions (Amr, 15 Oct 2026; NOT yet
     * approved by Sayed): an AWAITING booking's history actor is the same (C1); a paused window ends at the command
     * instant, the attempt's deadline and warning kept as recorded (C3). Amr's E7-113 decision 1 (FR-AVL-15) replaces
     * C2 Option A: an AWAITING booking whose unpaused Late Confirm deadline is reached belongs to T2 - it is never in
     * the affected set, and naming it here is a caller defect like any other uncancellable id.
     *
     * @param venueAccountId the authenticated Venue Account making the change, recorded as the history actor
     */
    void cancel(CommandContext command, UUID venueId, UUID venueAccountId, List<UUID> bookingIds);

    /**
     * @param customerId             the Online Booking's Customer, whom a cancelling command must lock (with their
     *                               Bookings); {@code null} for a Manual Booking
     * @param insideOperatingSession whether the Booking snapshotted an Operating Session; a Manual Booking made outside
     *                               published hours did not (FR-MAN-03) and is never affected by an hours change
     * @param lateConfirmTimedOut    an AWAITING booking whose open Late Confirmation is not paused and whose stored
     *                               deadline is at or before the command instant: T2 owns its outcome (FR-AVL-15, Amr's
     *                               E7-113 decision 1 superseding C2 Option A), even before the worker has run, so an
     *                               hours change never cancels it
     */
    record BookingFacts(UUID bookingId, BookingStatus status, BookingSource source, UUID customerId,
                        boolean insideOperatingSession, boolean lateConfirmTimedOut) {

        /** A cancellation of it needs the Owner Password: a CONFIRMED Online Booking or an AWAITING one (FR-VEN-25). */
        public boolean protectedCancellation() {
            return status == BookingStatus.AWAITING_CUSTOMER_CONFIRMATION
                    || (status == BookingStatus.CONFIRMED && source == BookingSource.ONLINE);
        }
    }
}
