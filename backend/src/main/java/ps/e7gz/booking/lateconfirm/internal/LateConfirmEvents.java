package ps.e7gz.booking.lateconfirm.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import ps.e7gz.booking.api.LateConfirmBookings.Booking;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.messaging.api.IntegrationEvents;
import ps.e7gz.messaging.api.NewOutboxEvent;

/**
 * The Late Confirm integration events of O3 (arch 03 section 12, arch 08 section 3.2; E7-58, PO decision D2 12 Oct
 * 2026), written in the transaction of their transition. Only the canonical Late Confirm events: O3 writes no
 * {@code BookingConfirmed} and no {@code BookingCancelled}. Each carries the fields of {@code BookingCreated} -
 * {@code bookingId, customerId, venueId, resourceId, startAt, endAt} - plus what the transition adds, on aggregate
 * {@code BOOKING} with the Booking ID, so every consumer can deep-link to the same booking (FR-NTF-04), and
 * {@code venueName}, the venue's display name at the event (E7-58 Q4, 13 Oct 2026: the notification copy names the
 * venue, FR-LCF-11), read through {@code venue.api.VenueContacts}. The outbox adds
 * {@code correlationId} and {@code occurredAt}. Instants are ISO-8601 UTC strings. No customer eligibility detail is
 * ever in a payload (NFR-19).
 */
final class LateConfirmEvents {

    static final String BOOKING_AGGREGATE = "BOOKING";

    private LateConfirmEvents() {
    }

    /** {@code LateConfirmInitiated}, with the customer's deadline. */
    static NewOutboxEvent initiated(Booking booking, String venueName, Instant expiresAt) {
        Map<String, Object> payload = base(booking, venueName);
        payload.put("lateConfirmExpiresAt", expiresAt.toString());
        return event(IntegrationEvents.LATE_CONFIRM_INITIATED, booking, payload);
    }

    /** {@code LateConfirmAccepted}: the booking is CONFIRMED. */
    static NewOutboxEvent accepted(Booking booking, String venueName) {
        return event(IntegrationEvents.LATE_CONFIRM_ACCEPTED, booking, base(booking, venueName));
    }

    /** {@code LateConfirmDeclined} with {@code terminalReason} CUSTOMER_DECLINED_LATE_CONFIRM. */
    static NewOutboxEvent declined(Booking booking, String venueName) {
        Map<String, Object> payload = base(booking, venueName);
        payload.put("terminalReason", BookingTerminalReason.CUSTOMER_DECLINED_LATE_CONFIRM.name());
        return event(IntegrationEvents.LATE_CONFIRM_DECLINED, booking, payload);
    }

    /**
     * {@code LateConfirmTimedOut}: T2 cancelled the booking with {@code terminalReason} CUSTOMER_RECONFIRM_TIMEOUT,
     * as of {@code lateConfirmExpiresAt}, the stored deadline (FR-LCF-13).
     */
    static NewOutboxEvent timedOut(Booking booking, String venueName, Instant expiresAt) {
        Map<String, Object> payload = base(booking, venueName);
        payload.put("lateConfirmExpiresAt", expiresAt.toString());
        payload.put("terminalReason", BookingTerminalReason.CUSTOMER_RECONFIRM_TIMEOUT.name());
        return event(IntegrationEvents.LATE_CONFIRM_TIMED_OUT, booking, payload);
    }

    /**
     * {@code LateConfirmWarningDue}: T3, about a minute before {@code lateConfirmExpiresAt}, once per attempt
     * (FR-LCF-12). For the customer only: the venue's live channel does not take it.
     */
    static NewOutboxEvent warningDue(Booking booking, String venueName, Instant expiresAt) {
        Map<String, Object> payload = base(booking, venueName);
        payload.put("lateConfirmExpiresAt", expiresAt.toString());
        return event(IntegrationEvents.LATE_CONFIRM_WARNING_DUE, booking, payload);
    }

    private static Map<String, Object> base(Booking booking, String venueName) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookingId", booking.bookingId());
        payload.put("customerId", booking.customerId());
        payload.put("venueId", booking.venueId());
        payload.put("venueName", Objects.requireNonNull(venueName, "venueName"));
        payload.put("resourceId", booking.resourceId());
        payload.put("startAt", booking.startAt().toString());
        payload.put("endAt", booking.endAt().toString());
        return payload;
    }

    private static NewOutboxEvent event(String type, Booking booking, Map<String, Object> payload) {
        return new NewOutboxEvent(type, BOOKING_AGGREGATE, booking.bookingId(), payload);
    }
}
