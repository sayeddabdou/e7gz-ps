package ps.e7gz.venue.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.BookingStatus;
import ps.e7gz.common.command.CommandContext;

/**
 * What a Venue Account disable (O10; arch 06 section 2.8, FR-VEN-09; E7-78) needs from the Bookings of its Venue: find
 * the ones it reaches and end their pre-confirmation response lifecycle. Declared here and implemented by the Booking
 * module, so Venue never depends on Booking (Booking depends on Venue for its Operating Sessions) - a port in the
 * caller's {@code api}, as {@link HoursChangeBookings} (CLAUDE.md section 3).
 *
 * <p>Both run on the disable command's own transaction. A writing call needs the Venue, every booking's Resource and
 * every Customer in the command's lock set ({@code begin_command}); the database refuses anything else. Nothing commits
 * here: the whole disable commits, or none of it does.
 */
public interface AccountDisableBookings {

    /**
     * The Online Bookings of the Venue a disable reaches, in start, then id order. Read-only:
     * <ul>
     *   <li>PENDING and AWAITING_CUSTOMER_CONFIRMATION - the disable ends their response lifecycle
     *       ({@link #interrupt});</li>
     *   <li>ACTIVE whose {@code endAt} is after the command instant - they continue (FR-ATT-14), and each is a
     *       Platform Attendance Protection candidate (FR-ATT-20, arch 07 section 7.5).</li>
     * </ul>
     * CONFIRMED bookings are unaffected and may still start (FR-VEN-09, FR-VEN-10); VENUE_RESPONSE_EXPIRED ones are
     * resolved at their {@code startAt} by T6 (E7-118), and Manual Bookings have no response lifecycle and no customer.
     */
    List<Affected> affected(CommandContext command, UUID venueId);

    /**
     * Ends the response lifecycle of exactly {@code bookingIds} - PENDING and AWAITING Online Bookings of
     * {@code venueId} the caller found under its locks - at the command instant (FR-VEN-09, actor SYSTEM):
     * <ul>
     *   <li>PENDING: VENUE_RESPONSE_EXPIRED at once with the Booking Event
     *       {@code VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE}, its reservation released, its response timer stopped
     *       (T1 withdrawn; an emergency-paused one's pause ended first, FR-OPS-37) and its start-time resolution T6
     *       scheduled (E7-118; PO decision E3, 19 Nov 2026). No customer notification (PO decision E1, 19 Nov 2026):
     *       the app tells the cause from the Booking Event (FR-NTF-06);</li>
     *   <li>AWAITING: CANCELLED {@code VENUE_ACCOUNT_DISABLED}, its Late Confirmation closed and hold released, its
     *       Booking Fee decision (a full refund inside the app, PO 19 Nov 2026) and one {@code BookingCancelled}.</li>
     * </ul>
     * Any other id is a caller defect: {@link IllegalStateException}, and the command rolls back.
     */
    void interrupt(CommandContext command, UUID venueId, List<UUID> bookingIds);

    /**
     * One booking a disable reaches.
     *
     * @param customerId the Online Booking's Customer, whom a writing command must lock
     */
    record Affected(UUID bookingId, UUID resourceId, UUID customerId, BookingStatus status, Instant startAt,
                    Instant endAt) {

        public Affected {
            Objects.requireNonNull(bookingId, "bookingId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(customerId, "customerId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(endAt, "endAt");
        }

        /** Its response lifecycle ends with the disable ({@link #interrupt}); an ACTIVE booking's does not. */
        public boolean interrupted() {
            return status == BookingStatus.PENDING || status == BookingStatus.AWAITING_CUSTOMER_CONFIRMATION;
        }
    }
}
