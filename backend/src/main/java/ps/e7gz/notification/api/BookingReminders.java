package ps.e7gz.notification.api;

import java.util.UUID;

/**
 * The booking reminder timers of the notification module (E7-55; PO decisions 12 Oct 2026), as
 * {@code technical.scheduled_job} rows. Both are scheduled by the notification consumer from the booking's events and
 * re-read the booking when they fire, so a booking that moved on (answered, cancelled, expired, started) supersedes them
 * without any booking command touching them.
 *
 * <p>Exposed for the one thing another module must do with them: the venue-response pause (FR-OPS-34, E7-80) moves
 * {@link #VENUE_PENDING_REQUEST_EXPIRING} to {@code PAUSED} together with T1 and re-arms it on resume at the new
 * deadline minus {@link #VENUE_PENDING_REQUEST_EXPIRING_LEAD_MINUTES} ("pauses with the response timer").
 */
public final class BookingReminders {

    /** {@code VENUE_PENDING_REQUEST_EXPIRING:{bookingId}}, due at {@code venue_response_expires_at} - 3 minutes. */
    public static final String VENUE_PENDING_REQUEST_EXPIRING = "VENUE_PENDING_REQUEST_EXPIRING";

    /** Minute 7 of the 10-minute response window (PO decision 12 Oct 2026). */
    public static final int VENUE_PENDING_REQUEST_EXPIRING_LEAD_MINUTES = 3;

    /** {@code BOOKING_STARTS_SOON:{bookingId}}, due at {@code startAt} - 20 minutes of a CONFIRMED Online Booking. */
    public static final String BOOKING_STARTS_SOON = "BOOKING_STARTS_SOON";

    /** PO decision 12 Oct 2026 (Sprint 3 review). */
    public static final int BOOKING_STARTS_SOON_LEAD_MINUTES = 20;

    private BookingReminders() {
    }

    public static String venuePendingRequestExpiringKey(UUID bookingId) {
        return VENUE_PENDING_REQUEST_EXPIRING + ":" + bookingId;
    }

    public static String bookingStartsSoonKey(UUID bookingId) {
        return BOOKING_STARTS_SOON + ":" + bookingId;
    }
}
